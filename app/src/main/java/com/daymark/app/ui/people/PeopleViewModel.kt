package com.daymark.app.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.PeopleRepository
import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The list of everybody the person has named, and the one write it offers: adding another.
 *
 * `PeopleRepository` is the only collaborator, and nothing it exposes returns a mood — the
 * repository's own header sets out why its signatures are shaped that way. This view model
 * therefore cannot put a person and a mood into the same expression even by accident, because it
 * has nothing to ask.
 *
 * There is no `suggest`, no `detect` and no `maybeAdd`. Every row in `people` is written by
 * somebody typing a name.
 */
@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val repository: PeopleRepository,
) : ViewModel() {

    /**
     * Everybody, archived included.
     *
     * The picker uses `observeActive`; this screen is where an archived name is put back, so it
     * has to be able to see one. The screen draws the two sets apart.
     */
    val people: StateFlow<List<Person>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Adds a name.
     *
     * A blank one is dropped rather than stored — the name is the whole of what the row says, and
     * the screen also keeps its confirm button disabled while the field is empty, so this is the
     * second guard and not the message.
     *
     * The new row's sharing is `null`, *follow the group*, and every group is off until somebody
     * turns it on. Adding somebody never shares them.
     */
    fun add(name: String, group: PersonGroup) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.add(
                name = name,
                group = group,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }
}
