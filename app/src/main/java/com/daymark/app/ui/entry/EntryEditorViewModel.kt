package com.daymark.app.ui.entry

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ActivityRepository
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.PhotoStore
import com.daymark.app.data.SettingsRepository
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.MoodEntry
import com.daymark.app.security.AutoLockController
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
    /** True when this save should *offer* (never force) gentle support — a low mood + opt-in. */
    val offerSupport: Boolean = false,
)

@HiltViewModel
class EntryEditorViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
    private val activityRepository: ActivityRepository,
    private val settingsRepository: SettingsRepository,
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
            val offer = s.moodLevel <= 2 && settingsRepository.gentleSupportEnabled
            _uiState.update { it.copy(saved = true, offerSupport = offer) }
        }
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
