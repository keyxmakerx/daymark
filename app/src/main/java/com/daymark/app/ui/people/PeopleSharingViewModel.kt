package com.daymark.app.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.PeopleRepository
import com.daymark.app.data.entity.Person
import com.daymark.app.data.entity.PersonGroup
import com.daymark.app.data.entity.PersonGroupShare
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One row of the sharing list: somebody, the answer that currently applies to them, and whether
 * that answer is their own or their group's.
 *
 * [resolved] is **not** computed here. It is whatever `PeopleRepository.isShared` returned, carried
 * through so the screen can draw it. [followsGroup] is a different question — *has this one been
 * given its own answer* — and it is read straight off the column rather than inferred from
 * [resolved], because `true` and `null-under-a-group-that-is-on` look identical once resolved and
 * mean opposite things the moment the group changes.
 */
data class SharingRow(
    val person: Person,
    val resolved: Boolean,
    val followsGroup: Boolean,
    /** False when the stored group key is one this build does not recognise. */
    val groupIsKnown: Boolean,
)

data class PeopleSharingUiState(
    val loaded: Boolean = false,
    /** The stored rows, as a map. A group with no row is off — `PersonGroupShare` requires it. */
    val groupDefaults: Map<String, Boolean> = emptyMap(),
    val groups: List<Pair<PersonGroup, List<SharingRow>>> = emptyList(),
)

/**
 * The sharing screen's state: every person and community, a default per group, an override per
 * item, and everything off until somebody says otherwise.
 *
 * `docs/FEATURES.md` §11.4: *"One screen lists every person and community, with a default per
 * group (all off) and an override per person."* Sharing stays off even under an accept-all grant.
 *
 * ## The rule is called, never copied
 *
 * `PeopleRepository.isShared` is the single expression that decides whether somebody's name leaves
 * the device. It is a companion function precisely so every caller can reach it, and this screen
 * calls it. There is no `sharedOverride ?: groupDefault` anywhere in `ui/people` and
 * `PeopleUiSourceTest` asserts that, with a planted copy to prove the check can see one. A screen
 * that re-derives the rule is a screen that can disagree with the code that actually sends, and
 * the disagreement is invisible from the phone: it would look right.
 *
 * ## Archived names are still listed
 *
 * *Every* person and community, says `docs/FEATURES.md` §11.4, and archiving is only about the
 * picker. Somebody archived two years ago is still named by two years of entries, so the screen
 * that says what is shared has to be able to say it about them.
 */
@HiltViewModel
class PeopleSharingViewModel @Inject constructor(
    private val repository: PeopleRepository,
) : ViewModel() {

    val uiState: StateFlow<PeopleSharingUiState> = combine(
        repository.observeAll(),
        repository.observeGroupShares(),
    ) { people: List<Person>, shares: List<PersonGroupShare> ->
        val defaults: Map<String, Boolean> = PeopleRepository.groupDefaults(shares)
        PeopleSharingUiState(
            loaded = true,
            groupDefaults = defaults,
            groups = peopleByGroup(people).map { pair ->
                pair.first to pair.second.map { person ->
                    SharingRow(
                        person = person,
                        resolved = PeopleRepository.isShared(person, defaults),
                        followsGroup = person.sharedOverride == null,
                        groupIsKnown = PersonGroup.isKnown(person.groupKey),
                    )
                }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleSharingUiState())

    /** The whole group's default. A person with their own answer keeps it. */
    fun setGroupDefault(group: PersonGroup, shared: Boolean) {
        viewModelScope.launch { repository.setGroupDefault(group, shared) }
    }

    /** One person's own answer. It beats their group, in both directions. */
    fun setOverride(person: Person, shared: Boolean) {
        viewModelScope.launch { repository.setSharedOverride(person, shared) }
    }

    /** Gives them back to their group: the third state, and the one a `Boolean` cannot hold. */
    fun followGroup(person: Person) {
        viewModelScope.launch { repository.setSharedOverride(person, null) }
    }
}
