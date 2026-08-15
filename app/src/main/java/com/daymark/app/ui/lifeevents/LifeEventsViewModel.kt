package com.daymark.app.ui.lifeevents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.dao.LifeEventDao
import com.daymark.app.data.entity.LifeEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The life-events screen's state and its three writes.
 *
 * **The DAO is injected here rather than a repository**, which is not the house pattern — every
 * other view model in `ui/` takes a `data/*Repository`. `data/LifeEventRepository.kt` was outside
 * the set of files this change was allowed to create, and the alternative (reaching the DAO off
 * `AppDatabase` the way `BackupManager` reaches `goalStepDao`) is the shape that already hid a
 * collaborator from a test. `provideLifeEventDao` exists in `di/AppModule.kt`, so this is a plain
 * binding; when a repository is added, this constructor is the only line that changes.
 *
 * There is no `suggest`, `detect` or `maybeAdd` here, and there is no writer for `life_events`
 * anywhere else in the app. [add] is called from one place: the person's own tap. See [LifeEvent].
 */
@HiltViewModel
class LifeEventsViewModel @Inject constructor(
    private val dao: LifeEventDao,
) : ViewModel() {

    val events: StateFlow<List<LifeEvent>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Adds one mark on [epochDay].
     *
     * A blank label is dropped rather than stored: with no category, no mood and no body, the label
     * is the whole of what the row says, and an empty one would draw a star nobody could read. The
     * screen also keeps its confirm button disabled while the field is empty, so this is the second
     * guard and not the message.
     *
     * [epochDay] is not validated. Years in the past are the ordinary case, and a date someone
     * knows is coming is still their decision — [LifeEvent] sets out why neither is refused.
     */
    fun add(epochDay: Long, label: String) {
        val text = label.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            dao.insert(
                LifeEvent(epochDay = epochDay, label = text, createdAt = System.currentTimeMillis()),
            )
        }
    }

    fun delete(event: LifeEvent) {
        viewModelScope.launch { dao.delete(event) }
    }

    /**
     * Puts a deleted event back exactly as it was, row id included.
     *
     * The id can be reused because nothing in the schema points at a life event, so an undo cannot
     * reattach anything to the wrong row. [LifeEvent.createdAt] is carried through rather than
     * refreshed — an undo restores a record, it does not write a new one.
     */
    fun restore(event: LifeEvent) {
        viewModelScope.launch { dao.insert(event) }
    }
}
