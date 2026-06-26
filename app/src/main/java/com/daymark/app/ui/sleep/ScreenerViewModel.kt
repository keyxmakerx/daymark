package com.daymark.app.ui.sleep

import androidx.lifecycle.ViewModel
import com.daymark.app.data.ScreeningStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ScreenerViewModel @Inject constructor(
    private val store: ScreeningStore,
) : ViewModel() {

    fun save(key: String, band: String) {
        store.save(key, band, System.currentTimeMillis())
    }
}
