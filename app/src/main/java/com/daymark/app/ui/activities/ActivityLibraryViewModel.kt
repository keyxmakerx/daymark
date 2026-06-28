package com.daymark.app.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.ActivityCatalog
import com.daymark.app.data.ActivityRepository
import com.daymark.app.data.CatalogActivity
import com.daymark.app.data.CatalogCategory
import com.daymark.app.data.entity.ActivityEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ActivityLibraryViewModel @Inject constructor(
    private val repository: ActivityRepository,
) : ViewModel() {

    val categories: List<CatalogCategory> = ActivityCatalog.categories

    /** Lower-cased names already in the user's list, so the library can mark them "Added". */
    val existingNames: StateFlow<Set<String>> = repository.observeAll()
        .map { list -> list.map { it.name.trim().lowercase() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Adds every selected suggestion that isn't already present (case-insensitive). */
    fun add(selected: Collection<CatalogActivity>) {
        if (selected.isEmpty()) return
        val present = existingNames.value
        val toInsert = selected
            .filter { it.name.trim().lowercase() !in present }
            .map { ActivityEntity(name = it.name, iconKey = it.iconKey) }
        if (toInsert.isEmpty()) return
        viewModelScope.launch { repository.insertAll(toInsert) }
    }
}
