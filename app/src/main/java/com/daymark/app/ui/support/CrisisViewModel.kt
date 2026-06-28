package com.daymark.app.ui.support

import androidx.lifecycle.ViewModel
import com.daymark.app.data.CrisisStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class CrisisViewModel @Inject constructor(
    private val store: CrisisStore,
) : ViewModel() {

    private val _resource = MutableStateFlow(store.get())
    val resource: StateFlow<CrisisStore.Resource> = _resource.asStateFlow()

    fun save(label: String, contact: String) {
        store.save(label, contact)
        _resource.value = store.get()
    }
}
