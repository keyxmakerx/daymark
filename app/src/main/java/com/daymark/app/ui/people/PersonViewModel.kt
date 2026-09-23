package com.daymark.app.ui.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.PeopleRepository
import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroup
import com.daymark.app.data.entity.PersonGroupShare
import com.daymark.app.data.entity.PersonNote
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One entry, as a person's page is allowed to know it: when it was written and what the person
 * wrote. Three fields, and the ones that are missing are the point.
 *
 * There is no mood level here, and there is nothing this type could be given one by. `PersonScreen`
 * draws a dated line and the person's own words; tapping it opens the entry, where the whole record
 * is shown in its own context. A column of faces under somebody's name would be a
 * mood-with-person statistic drawn for the eye to read, which `docs/FEATURES.md` §11.2 rules out
 * by name, so this type makes one impossible to assemble here.
 */
data class PersonEntryLine(
    val entryId: Long,
    val dateTime: Long,
    val note: String,
)

/** Everything a person's page shows, and nothing it does not. */
data class PersonUiState(
    /** False until the first read comes back, so "not found" is not drawn over "not read yet". */
    val loaded: Boolean = false,
    val person: Person? = null,
    /** Newest first — `PersonNoteDao` orders them. */
    val notes: List<PersonNote> = emptyList(),
    val entries: List<PersonEntryLine> = emptyList(),
    /** The resolved answer from `PeopleRepository.isShared`, never a second copy of the rule. */
    val shared: Boolean = PeopleRepository.SHARING_OFF,
) {
    /**
     * When the most recent note was written, or `null` when there are none.
     *
     * `docs/FEATURES.md` §11.3 allows the page to state *last note: June* as a fact when it is
     * opened, and allows **nothing** to be said when there are none. A `null` here draws no
     * sentence at all — it must never become "nothing since March", which is the forbidden prompt
     * wearing a date.
     */
    val lastNoteAt: Long? get() = notes.firstOrNull()?.dateTime
}

/**
 * A single person or community: who they are in the person's own words, the notes they have
 * written about them, the entries that name them, and whether any of it is shared.
 *
 * ## Why `EntryRepository` is here, and what it is allowed to be asked
 *
 * `docs/FEATURES.md` §11.1 says the page shows *"the entries that name them"*, so something has to
 * join the two. The data layer chose where: `PeopleRepository.observeEntryIds` returns **ids**, and
 * its header says *"a caller that needs to draw the list asks `EntryRepository` for those ids and
 * does the join itself, in a view model, for a list it is about to render."* This is that view
 * model and that is the whole of what it does with them.
 *
 * The join is one filter and one map into [PersonEntryLine], which cannot carry a mood. Nothing
 * groups, counts, averages, ranks or orders by anything but time, and no person id and no group
 * key is passed to anything in `stats/`. `PeopleUiSourceTest` asserts the absence with planted
 * controls, because an absence nobody can see fail is not evidence.
 */
@HiltViewModel
class PersonViewModel @Inject constructor(
    private val repository: PeopleRepository,
    entryRepository: EntryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val personId: Long = savedStateHandle.get<String>("personId")?.toLongOrNull() ?: 0L

    private val entryLines: Flow<List<PersonEntryLine>> = combine(
        repository.observeEntryIds(personId),
        entryRepository.observeAll(),
    ) { ids, all ->
        val wanted: Set<Long> = ids.toSet()
        all.filter { wanted.contains(it.entry.id) }
            .map { found ->
                PersonEntryLine(
                    entryId = found.entry.id,
                    dateTime = found.entry.dateTime,
                    note = found.entry.note,
                )
            }
    }

    val uiState: StateFlow<PersonUiState> = combine(
        repository.observe(personId),
        repository.observeNotes(personId),
        repository.observeGroupShares(),
        entryLines,
    ) { person: Person?, notes: List<PersonNote>, shares: List<PersonGroupShare>, lines: List<PersonEntryLine> ->
        PersonUiState(
            loaded = true,
            person = person,
            notes = notes,
            entries = lines,
            // The rule lives in one expression in `PeopleRepository` and is called, never copied.
            // A second copy of it on a screen is how a person's exclusion ends up true in one
            // place and false in the one that decides what leaves the phone.
            shared = if (person == null) {
                PeopleRepository.SHARING_OFF
            } else {
                PeopleRepository.isShared(person, PeopleRepository.groupDefaults(shares))
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonUiState())

    /** A dated note in the person's own words. Blank is dropped rather than stored. */
    fun addNote(body: String) {
        val text = body.trim()
        if (text.isEmpty() || personId == 0L) return
        viewModelScope.launch {
            repository.addNote(
                personId = personId,
                dateTime = System.currentTimeMillis(),
                body = text,
            )
        }
    }

    fun deleteNote(note: PersonNote) {
        viewModelScope.launch { repository.deleteNote(note) }
    }

    /**
     * Hides them from the *with* picker, or puts them back.
     *
     * Nothing else changes: every entry that names them still names them, every note is still
     * here, and this page still opens. `Person.archived` is where that promise is written down and
     * `PersonScreen` says it out loud at the point of the click.
     */
    fun setArchived(archived: Boolean) {
        val person = uiState.value.person ?: return
        viewModelScope.launch { repository.setArchived(person, archived) }
    }

    /**
     * Renames, refiles and rewrites the *"who (or what) is this to you"* line.
     *
     * [group] is nullable and `null` means **leave the stored group key exactly as it is**. That is
     * not a convenience: `PersonGroup` forbids normalising a key this build does not recognise,
     * because a row written by a newer version would otherwise be quietly refiled as "other" the
     * first time somebody fixed a typo in a name. A group is only ever rewritten when the person
     * taps a group chip themselves.
     */
    fun edit(name: String, group: PersonGroup?, whoTheyAre: String) {
        val person = uiState.value.person ?: return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        viewModelScope.launch {
            repository.update(
                person.copy(
                    name = trimmedName,
                    groupKey = group?.key ?: person.groupKey,
                    whoTheyAre = whoTheyAre.trim(),
                ),
            )
        }
    }
}
