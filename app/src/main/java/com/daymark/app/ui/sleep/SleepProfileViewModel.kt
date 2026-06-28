package com.daymark.app.ui.sleep

import androidx.lifecycle.ViewModel
import com.daymark.app.data.SleepProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SleepProfileViewModel @Inject constructor(
    private val store: SleepProfileStore,
) : ViewModel() {
    fun load(): SleepProfileStore.Profile = store.load()
    fun save(profile: SleepProfileStore.Profile) = store.save(profile)
}
