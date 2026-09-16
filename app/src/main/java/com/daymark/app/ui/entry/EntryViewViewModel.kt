package com.daymark.app.ui.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.PeopleRepository
import com.daymark.app.data.dao.LifeEventDao
import com.daymark.app.data.entity.ActivityEntity
import com.daymark.app.data.entity.LifeEvent
import com.daymark.app.data.entity.Person
import com.daymark.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One entry, exactly as it was written.
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §3: *"Descriptive only: the person's own mood word,
 * activities, with, note, photo. No commentary of any kind. Never 'you seem'."*
 *
 * Everything here is a field read back. There is no score, no summary, no comparison with another
 * day, no "this was one of your better ones" and nothing derived from the mood level except the
 * person's own word for it, which they chose. `CLAUDE.md` §0 is the rule and this screen is one of
 * the places it is easiest to break: a page showing a single day is exactly where a helpful
 * sentence wants to appear.
 */
data class EntryViewUiState(
    /** False until the first read comes back, so "gone" is never drawn over "not read yet". */
    val loaded: Boolean = false,
    val entryId: Long = 0L,
    val exists: Boolean = false,
    /** The level the person picked. Shown as their own word for it, and never interpreted. */
    val moodLevel: Int = 3,
    val dateTime: Long = 0L,
    val note: String = "",
    val activities: List<ActivityEntity> = emptyList(),
    /**
     * Who, or what, this entry names — archived included.
     *
     * Archiving hides a name from the picker and changes nothing that was already written, so an
     * entry from two years ago still says who it was with. Dropping them here would be the app
     * quietly editing the past on somebody's behalf.
     */
    val withPeople: List<Person> = emptyList(),
    val photoPath: String? = null,
    /** Life events already placed on this entry's date. Facts, listed; never a prompt. */
    val marksOnThisDay: List<LifeEvent> = emptyList(),
)

/**
 * The entry page's state, and the one thing it can write: a mark on this day.
 *
 * ## Why a person and a mood may be in this one state object
 *
 * The rule the people feature is built around — *a person or a community may never reach anything
 * that reads mood* — is about rules, correlations and the cards they produce, not about showing a
 * person their own entry. §3 asks for the mood word, the activities, the *with* list, the note and
 * the photo on one page, so one object holds them.
 *
 * What makes that safe is that nothing here combines them. The mood level is read from the entry
 * and handed to the screen; the *with* list is read from `PeopleRepository` by entry id and handed
 * to the screen; no function takes both, nothing aggregates, nothing is grouped by anybody, and
 * nothing is kept. This is the *plain display of one entry* that the rule sets aside, and it is
 * the only place in the app where the two appear together.
 *
 * ## Why the life-event DAO is injected directly
 *
 * The same reason `LifeEventsViewModel` does it: there is no `LifeEventRepository` yet, and
 * `provideLifeEventDao` is already a plain binding in `di/AppModule.kt`. When a repository is
 * added, this constructor is one of the two lines that change.
 */
@HiltViewModel
class EntryViewViewModel @Inject constructor(
    entryRepository: EntryRepository,
    peopleRepository: PeopleRepository,
    private val lifeEventDao: LifeEventDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val entryId: Long = savedStateHandle.get<String>("entryId")?.toLongOrNull() ?: 0L

    val uiState: StateFlow<EntryViewUiState> = combine(
        entryRepository.observeAll(),
        peopleRepository.observePersonIdsForEntry(entryId),
        peopleRepository.observeAll(),
        lifeEventDao.observeAll(),
    ) { entries, withIds, people, events ->
        val found = entries.firstOrNull { candidate -> candidate.entry.id == entryId }
        val epochDay: Long? = found?.let { DateUtils.toLocalDate(it.entry.dateTime).toEpochDay() }
        EntryViewUiState(
            loaded = true,
            entryId = entryId,
            exists = found != null,
            moodLevel = found?.entry?.moodLevel ?: 3,
            dateTime = found?.entry?.dateTime ?: 0L,
            note = found?.entry?.note ?: "",
            activities = found?.activities ?: emptyList(),
            withPeople = people.filter { person -> withIds.contains(person.id) },
            photoPath = found?.entry?.photoPath,
            marksOnThisDay = if (epochDay == null) {
                emptyList()
            } else {
                events.filter { event -> event.epochDay == epochDay }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryViewUiState())

    /**
     * Places a life event on this entry's date.
     *
     * `docs/SKY.md` §2.2 gives life events one writer: the person's own tap. This is a second door
     * onto the same act, from the day they are already looking at, and it asks for exactly what the
     * life-events screen asks for — a few words — because a mark is a date and a label and the app
     * has no business adding a third field to it.
     *
     * A blank label is dropped rather than stored: with no category and no body, the label is the
     * whole of what the row says. The dialog also keeps its button disabled while the field is
     * empty, so this is the second guard and not the message.
     *
     * The entry's **mood is not read here and is not passed anywhere**. A mark records that a day
     * mattered, which is the person's judgement; a mark the app placed because of how a day was
     * logged would be the app making it for them.
     */
    fun markThisDay(label: String) {
        val text = label.trim()
        if (text.isEmpty()) return
        val millis = uiState.value.dateTime
        if (!uiState.value.exists) return
        val epochDay: Long = DateUtils.toLocalDate(millis).toEpochDay()
        viewModelScope.launch {
            lifeEventDao.insert(
                LifeEvent(
                    epochDay = epochDay,
                    label = text,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
