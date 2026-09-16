package com.daymark.app.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
import com.daymark.app.data.PeopleRepository
import com.daymark.app.data.entity.EntryWithActivities
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Deleting an entry, and taking it back.
 *
 * This lives apart from the screens on purpose. The undo snackbar is hosted by the app scaffold so
 * it survives navigation — which means you can delete on **All entries**, press back, and still hit
 * **Undo** while the snackbar is up. If the restore ran in the *screen's* ViewModel, popping that
 * destination would clear its ViewModelStore and cancel `viewModelScope`, and the undo would
 * silently do nothing: the entry would be gone for good with no error. Obtained at the scaffold
 * (where the store owner is the activity), this scope outlives any destination the user can pop.
 *
 * ## The *with* list, and why it is held rather than joined
 *
 * `EntryWithActivities` carries the entry's activities, so an undo can put them back. It does not
 * carry the entry's people, and it must not start to: it is the type `EntryDao` returns, and
 * `EntryDao` is the one that returns a mood level. A `with` list on it would be a query in the data
 * layer that hands back a (person, mood) pair, which is exactly what `data/PeopleRepository.kt`'s
 * header says there is none of.
 *
 * So the ids are read, held here for as long as the undo can happen, and put back by their own
 * call. The two repositories are never handed each other's arguments.
 */
@HiltViewModel
class EntryActionsViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
    private val peopleRepository: PeopleRepository,
) : ViewModel() {

    /**
     * The *with* list of each entry deleted but still undoable, by entry id.
     *
     * Touched only from `viewModelScope`, which is the main dispatcher, so there is one thread
     * here. An entry whose undo window closes is removed by [purgePhoto], the call the scaffold
     * already makes when the snackbar goes away without being tapped.
     */
    private val withListsOfDeletedEntries: MutableMap<Long, List<Long>> = mutableMapOf()

    fun delete(entry: EntryWithActivities) {
        viewModelScope.launch {
            // Read before the delete, because after it there is nothing to read from.
            val withIds: List<Long> = peopleRepository.personIdsForEntry(entry.entry.id)
            if (withIds.isNotEmpty()) withListsOfDeletedEntries[entry.entry.id] = withIds
            // `entry_people` has no foreign key and SQLite reuses the highest row id, so a link
            // left behind here would become the *with* list of some later, unrelated entry.
            // `EntryEditorViewModel.delete` carries the long version of that note.
            peopleRepository.setPeopleOnEntry(entry.entry.id, emptyList())
            entryRepository.delete(entry.entry)
        }
    }

    /** Puts a deleted entry back, keeping its id, its activity links and its *with* list. */
    fun restore(entry: EntryWithActivities) {
        viewModelScope.launch {
            entryRepository.restore(entry.entry, entry.activities.map { it.id })
            val withIds: List<Long> = withListsOfDeletedEntries.remove(entry.entry.id) ?: emptyList()
            if (withIds.isNotEmpty()) {
                peopleRepository.setPeopleOnEntry(entry.entry.id, withIds)
            }
        }
    }

    /**
     * Finalizes a delete once undo is no longer possible: drop the photo file, and forget the
     * *with* list that was being held in case of an undo.
     * [EntryRepository.delete] deliberately leaves the photo on disk so undo can restore it.
     */
    fun purgePhoto(entry: EntryWithActivities) {
        entryRepository.deletePhoto(entry.entry.photoPath)
        withListsOfDeletedEntries.remove(entry.entry.id)
    }
}
