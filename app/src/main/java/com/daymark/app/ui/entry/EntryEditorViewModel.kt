package com.daymark.app.ui.entry

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ActivityRepository
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.OfferLedgerRepository
import com.daymark.app.data.PhotoStore
import com.daymark.app.data.SettingsRepository
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.MoodEntry
import com.daymark.app.data.entity.OfferKind
import com.daymark.app.data.entity.OfferOutcome
import com.daymark.app.security.AutoLockController
import com.daymark.app.stats.InterruptionBudget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class EntryEditorUiState(
    val entryId: Long = 0L,
    val moodLevel: Int = 3,
    val note: String = "",
    val dateTime: Long = System.currentTimeMillis(),
    val selectedActivityIds: Set<Long> = emptySet(),
    val activities: List<ActivityEntity> = emptyList(),
    /** Relative filename of an attached photo, or null. */
    val photoPath: String? = null,
    val isEditing: Boolean = false,
    val saved: Boolean = false,
    /**
     * True when this save may *take you* to the support space. Rationed by
     * [com.daymark.app.stats.InterruptionBudget]; off unless the person asked to be taken there.
     */
    val offerSupport: Boolean = false,
    /** Whether the person has opted into gentle support at all. */
    val gentleSupportOn: Boolean = false,
) {
    /**
     * The quiet corner action, shown the moment a low mood is picked — before saving, while you're
     * still here. It only ever *appears*; it never moves you or reflows what you're typing, so it
     * needs no rationing. Ignoring it costs nothing.
     */
    val showSupportAction: Boolean get() = gentleSupportOn && moodLevel <= LOW_MOOD_MAX
}

/** Awful (1) and Bad (2). The mood levels that put the support action within reach. */
const val LOW_MOOD_MAX = 2

@HiltViewModel
class EntryEditorViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
    private val activityRepository: ActivityRepository,
    private val settingsRepository: SettingsRepository,
    private val offerLedger: OfferLedgerRepository,
    private val photoStore: PhotoStore,
    private val autoLock: AutoLockController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The photo the entry had when loaded; used to clean up a replaced/removed original on save. */
    private var loadedPhotoPath: String? = null

    /** Call right before launching the photo picker so returning doesn't trip the app lock. */
    fun prepareForPicker() = autoLock.suppressNextBackgroundLock()

    private val entryId: Long = savedStateHandle.get<String>("entryId")?.toLongOrNull() ?: 0L
    private val prefillMood: Int = savedStateHandle.get<String>("mood")?.toIntOrNull() ?: -1

    private val _uiState = MutableStateFlow(
        EntryEditorUiState(
            entryId = entryId,
            moodLevel = if (entryId == 0L && prefillMood in 1..5) prefillMood else 3,
            gentleSupportOn = settingsRepository.gentleSupportEnabled,
        ),
    )
    val uiState: StateFlow<EntryEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            activityRepository.observeActive().collect { list ->
                _uiState.update { it.copy(activities = list) }
            }
        }
        if (entryId != 0L) loadExisting(entryId)
    }

    private fun loadExisting(id: Long) {
        viewModelScope.launch {
            entryRepository.getById(id)?.let { ewa ->
                loadedPhotoPath = ewa.entry.photoPath
                _uiState.update {
                    it.copy(
                        moodLevel = ewa.entry.moodLevel,
                        note = ewa.entry.note,
                        dateTime = ewa.entry.dateTime,
                        selectedActivityIds = ewa.activities.map { a -> a.id }.toSet(),
                        photoPath = ewa.entry.photoPath,
                        isEditing = true,
                    )
                }
            }
        }
    }

    fun setMood(level: Int) = _uiState.update { it.copy(moodLevel = level) }

    fun setNote(note: String) = _uiState.update { it.copy(note = note) }

    fun setDateTime(millis: Long) = _uiState.update { it.copy(dateTime = millis) }

    fun toggleActivity(id: Long) = _uiState.update { state ->
        val next = state.selectedActivityIds.toMutableSet()
        if (!next.add(id)) next.remove(id)
        state.copy(selectedActivityIds = next)
    }

    /** Copies the picked image into private storage and attaches it, dropping any unsaved pick. */
    fun setPhoto(uri: Uri) {
        viewModelScope.launch {
            val current = _uiState.value.photoPath
            val newPath = withContext(Dispatchers.IO) {
                runCatching { photoStore.copyFromUri(uri) }.getOrNull()
            } ?: return@launch
            // Discard a previous unsaved pick (one that isn't the entry's saved original).
            if (current != null && current != loadedPhotoPath) photoStore.delete(current)
            _uiState.update { it.copy(photoPath = newPath) }
        }
    }

    fun clearPhoto() {
        val current = _uiState.value.photoPath ?: return
        if (current != loadedPhotoPath) photoStore.delete(current)
        _uiState.update { it.copy(photoPath = null) }
    }

    fun save() {
        val s = _uiState.value
        viewModelScope.launch {
            entryRepository.save(
                MoodEntry(
                    id = s.entryId,
                    dateTime = s.dateTime,
                    moodLevel = s.moodLevel,
                    note = s.note.trim(),
                    photoPath = s.photoPath,
                ),
                s.selectedActivityIds.toList(),
            )
            // If the saved photo changed, the entry's original file is now orphaned.
            if (loadedPhotoPath != null && loadedPhotoPath != s.photoPath) {
                photoStore.delete(loadedPhotoPath)
            }
            loadedPhotoPath = s.photoPath
            // Being moved somewhere you didn't ask to go is the expensive kind of offer, so it is
            // rationed and off by default. The corner action above is the unrationed one.
            val now = System.currentTimeMillis()
            // The two conditions in front of the gate are the feature's own and stay here: the
            // arbiter is asked *whether* it may interrupt, never *what* the interruption is about.
            val interrupt = s.moodLevel <= LOW_MOOD_MAX && s.gentleSupportOn && mayOffer(now)
            if (interrupt) {
                settingsRepository.supportOfferLastShownAt = now
                recordOffer(now)
            }
            _uiState.update { it.copy(saved = true, offerSupport = interrupt) }
        }
    }

    /**
     * May the support space take the person over right now? — the whole of what this screen asks
     * the decision engine, and the same question [com.daymark.app.stats.SupportOffer] answered
     * before the ledger existed.
     *
     * The person's declared frequency is still the ceiling: [InterruptionBudget] may step it down
     * when offers are being waved away and can never step it up, so nothing here can produce a
     * shorter gap than the setting they chose.
     *
     * The arguments are assembled by hand rather than through `OfferLedgerRepository.mayInterrupt`
     * for one reason — [SettingsRepository.supportOfferLastShownAt]. That preference is where every
     * offer made before the ledger shipped was recorded, and an upgrade must not read as "never
     * asked" and permit an extra interruption on the first hard day. Taking the later of the two
     * timestamps is the safe direction of that merge: a more recent last-offer only ever lengthens
     * the wait. Both readings are [OfferKind.SUPPORT]'s own — pairing one kind's rows with
     * another's reception is the mistake available here, and it is not made.
     */
    private suspend fun mayOffer(now: Long): Boolean {
        val recent = offerLedger.recentOffers(OfferKind.SUPPORT)
        return InterruptionBudget.shouldInterrupt(
            declared = settingsRepository.supportOfferFrequency,
            reception = InterruptionBudget.receptionOf(
                kind = InterruptionBudget.Kind.SUPPORT,
                recent = recent,
                saidStop = offerLedger.saidStop(OfferKind.SUPPORT),
            ),
            // A zero stamp is not "no record" here — it is GentleSupportViewModel.setFrequency
            // deliberately clearing the ration because the person just re-declared how often they
            // want this. Taking maxOf with the ledger would re-supply the timestamp that reset just
            // cleared, and someone reaching for the setting to ask for MORE would still be held off
            // with nothing on screen explaining the wait — the exact failure that KDoc forbids.
            // Their new declaration wins over the engine's memory of the old one.
            lastOfferedAt = if (settingsRepository.supportOfferLastShownAt == 0L) {
                0L
            } else {
                maxOf(
                    InterruptionBudget.lastOfferedAt(InterruptionBudget.Kind.SUPPORT, recent),
                    settingsRepository.supportOfferLastShownAt,
                )
            },
            nowMillis = now,
        )
    }

    /**
     * Writes the ledger line for an offer this screen just made. Called only when the offer is
     * actually made, because a line means "the app asked" and nothing else.
     *
     * The line must be written here rather than left for whoever learns the outcome: `offeredAt` is
     * what the minimum-gap timer counts from, so an offer with no row is an offer that never
     * happened, and the next one is permitted *sooner* — the one direction nothing in this system
     * may move.
     *
     * **Why [OfferOutcome.ACCEPTED] and not something else.** This editor's offer is to *take the
     * person to the support space*, and by the time this line is written that is what is happening.
     * What they do once they are there is the support space's knowledge, not this screen's, and the
     * key chosen is the one the engine weighs at zero — it leaves the declared frequency exactly as
     * the person set it, which is the only honest treatment of an outcome the caller cannot see. It
     * is not a claim that they found it useful, and it can never make the app ask more than the
     * setting allows. A dismissal recorded from the support space itself, once that surface can
     * tell, is a strictly better line than this one.
     */
    private suspend fun recordOffer(now: Long) {
        offerLedger.record(OfferKind.SUPPORT, OfferOutcome.ACCEPTED, now)
    }

    fun delete() {
        val s = _uiState.value
        if (s.entryId == 0L) return
        viewModelScope.launch {
            entryRepository.delete(
                MoodEntry(s.entryId, s.dateTime, s.moodLevel, s.note, s.photoPath),
            )
            // Permanent delete from the editor (no undo here) — drop the photo file too.
            photoStore.delete(s.photoPath)
            loadedPhotoPath = null
            _uiState.update { it.copy(saved = true) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // If the screen closes with an unsaved photo pick, don't leave the file orphaned.
        val current = _uiState.value.photoPath
        if (!_uiState.value.saved && current != null && current != loadedPhotoPath) {
            photoStore.delete(current)
        }
    }
}
