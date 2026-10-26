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

    /** Whether the person has said the line is right for them; the Call button waits on it (#482). */
    private val _confirmed = MutableStateFlow(store.confirmed)
    val confirmed: StateFlow<Boolean> = _confirmed.asStateFlow()

    fun save(label: String, contact: String, number: String?, takesTexts: Boolean) {
        store.save(label, contact, number, takesTexts)
        refresh()
    }

    fun confirm() {
        store.confirm()
        refresh()
    }

    private fun refresh() {
        _resource.value = store.get()
        _confirmed.value = store.confirmed
    }
}
