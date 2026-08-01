package com.daymark.app.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.EntryRepository
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
 */
@HiltViewModel
class EntryActionsViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
) : ViewModel() {

    fun delete(entry: EntryWithActivities) {
        viewModelScope.launch { entryRepository.delete(entry.entry) }
    }

    /** Puts a deleted entry back, keeping its id and its activity links. */
    fun restore(entry: EntryWithActivities) {
        viewModelScope.launch {
            entryRepository.restore(entry.entry, entry.activities.map { it.id })
        }
    }

    /**
     * Finalizes a delete once undo is no longer possible: drop the photo file.
     * [EntryRepository.delete] deliberately leaves it on disk so undo can restore it.
     */
    fun purgePhoto(entry: EntryWithActivities) {
        entryRepository.deletePhoto(entry.entry.photoPath)
    }
}
