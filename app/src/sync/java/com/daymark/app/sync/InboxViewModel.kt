package com.daymark.app.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.companion.ApplyMode
import com.daymark.app.companion.AssignableCatalogue
import com.daymark.app.companion.AssignmentRules
import com.daymark.app.companion.CapabilityGrant
import com.daymark.app.companion.Grant
import com.daymark.app.data.dao.CompanionDao
import com.daymark.app.data.entity.AcceptedAssignment
import com.daymark.app.data.entity.GamePlan
import com.daymark.app.data.entity.GamePlanItem
import com.daymark.synccrypto.ClinicianItems
import com.daymark.synccrypto.KeptClinicians
import com.daymark.synccrypto.PairedServer
import com.daymark.synccrypto.SyncCrypto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One item waiting for the owner. [key] names it exactly: clinician, channel, lineage and version. */
sealed interface InboxEntry {
    val key: String
    val clinician: String

    class Plan(
        override val key: String,
        override val clinician: String,
        val plan: ClinicianItems.Opening.GamePlan,
        /** An earlier version of this plan is already accepted. */
        val replaces: Boolean,
    ) : InboxEntry

    class Suggestion(
        override val key: String,
        override val clinician: String,
        val assignment: ClinicianItems.Opening.Assignment,
        /** Why it cannot be added, or null when it can. */
        val cannotAdd: String?,
    ) : InboxEntry

    /** An item that failed a check: shown so it is not silently dropped, and only dismissed. */
    class NotBelieved(override val key: String, override val clinician: String, val words: String) : InboxEntry
}

/** An accepted plan as the screen lists it. */
data class AcceptedPlan(val lineageId: String, val clinician: String, val withdrawn: Boolean, val items: List<GamePlanItem>)

/** An accepted suggestion, described in the inbox's fixed words. */
data class AcceptedSuggestion(val lineageId: String, val clinician: String, val line: String)

data class InboxUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val hasClinicians: Boolean = false,
    val checked: Boolean = false,
    val entries: List<InboxEntry> = emptyList(),
    /** Lines about the last check: what was added on its own, what expired, what could not be read. */
    val notes: List<String> = emptyList(),
    val words: String? = null,
    val plans: List<AcceptedPlan> = emptyList(),
    val suggestions: List<AcceptedSuggestion> = emptyList(),
)

/**
 * The inbox's errands (#177): check every approved clinician's channels at the owner's tap, show what
 * opened as proposals, and write one into the app only when the owner accepts it, or when it is a
 * suggestion the owner's own grant lets apply on its own. Opening and verifying is
 * [ClinicianItems]'s; what may be added is [AssignmentRules]'. Nothing here polls or retries.
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val links: ServerLinkStore,
    private val clinicianStore: ClinicianStore,
    private val parts: ServerSyncParts,
    private val dao: CompanionDao,
) : ViewModel() {

    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val shown = offMain({ InboxUiState(loading = false) }) { accepted(InboxUiState(loading = false)) }
            _state.value = shown
        }
    }

    /** The clinicians on this phone whose keys are recorded: only they can have sent anything believable. */
    private fun approved(): List<KeptClinicians.Clinician> =
        when (val r = clinicianStore.read()) {
            is ClinicianStore.Reading.Kept -> r.clinicians.clinicians.filter { it.current != null }
            ClinicianStore.Reading.Unreadable -> emptyList()
        }

    /** The owner's name for whoever signed with the key fingerprinted [fp], from the kept records. */
    private fun nameFor(fp: String, kept: List<KeptClinicians.Clinician>): String =
        kept.firstOrNull { c -> c.keys.any { parts.clinicianItems.fingerprint(it.signPub) == fp } }?.displayName ?: InboxWords.SOMEONE

    /** [base] with what is accepted read from the app's tables. Runs off the main thread. */
    private suspend fun accepted(base: InboxUiState): InboxUiState {
        val kept = approved()
        val plans = dao.latestGamePlans().map { p ->
            AcceptedPlan(p.lineageId, nameFor(p.authorFingerprint, kept), p.status == "withdrawn", dao.gamePlanItems(p.lineageId, p.version))
        }
        val suggestions = dao.latestAssignments().map { a ->
            AcceptedSuggestion(a.lineageId, nameFor(a.authorFingerprint, kept), describeStored(a))
        }
        return base.copy(hasClinicians = kept.isNotEmpty(), plans = plans, suggestions = suggestions)
    }

    /** Looks for new items from every approved clinician. One tap, one pass, no repeat. */
    fun check() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, words = null) }
        viewModelScope.launch {
            val next = offMain({ _state.value.copy(busy = false, words = InboxWords.NO_ANSWER) }) { checkAll() }
            _state.value = next.copy(busy = false)
        }
    }

    private suspend fun checkAll(): InboxUiState {
        val before = _state.value
        val clinicians = approved()
        if (clinicians.isEmpty()) return accepted(before.copy(checked = true, entries = emptyList(), notes = emptyList()))
        val ownerKeys = links.ownerKeys() ?: return before.copy(words = InboxWords.NOT_SAVED)
        val secret = links.ownerBoxSecret() ?: return before.copy(words = InboxWords.NOT_SAVED)
        val link = links.read() ?: run { secret.fill(0); return before.copy(words = InboxWords.NOT_SAVED) }
        val server = try {
            link.server(parts.sodium)
        } finally {
            link.wipe()
        } ?: run { secret.fill(0); return before.copy(words = InboxWords.NOT_SAVED) }
        val owner = ClinicianItems.OwnerBox(SyncCrypto.fromBase64(ownerKeys.boxPubB64), secret)
        try {
            val declined = clinicianStore.declined()
            val entries = ArrayList<InboxEntry>()
            val notes = ArrayList<String>()
            var auto = 0
            var gone = 0
            var unreadable = 0
            for (c in clinicians) {
                val pinned = c.current!!.signPub
                val step = checkOne(server, c, owner, pinned, SyncCrypto.fromBase64(ownerKeys.signPubB64), declined)
                    ?: return before.copy(words = InboxWords.NO_ANSWER)
                entries += step.entries
                notes += step.notes
                auto += step.auto
                gone += step.gone
                unreadable += step.unreadable
            }
            if (auto > 0) notes += InboxWords.addedOnItsOwn(auto)
            if (gone > 0) notes += InboxWords.gone(gone)
            if (unreadable > 0) notes += InboxWords.unreadable(unreadable)
            return accepted(before.copy(checked = true, entries = entries, notes = notes))
        } finally {
            secret.fill(0)
        }
    }

    private class Checked(val entries: List<InboxEntry>, val notes: List<String>, val auto: Int, val gone: Int, val unreadable: Int)

    /** One clinician's grant, plans and suggestions; null when the server did not answer. */
    private suspend fun checkOne(
        server: PairedServer,
        c: KeptClinicians.Clinician,
        owner: ClinicianItems.OwnerBox,
        pinned: ByteArray,
        ownerSignPub: ByteArray,
        declined: Set<String>,
    ): Checked? {
        val items = parts.clinicianItems
        val name = c.displayName
        val entries = ArrayList<InboxEntry>()
        val notes = ArrayList<String>()
        var auto = 0

        val plans = items.fetch(server, c.inboxToken, ClinicianItems.Channel.GAME_PLANS)
        val suggestions = items.fetch(server, c.inboxToken, ClinicianItems.Channel.ASSIGNMENTS)
        if (plans !is ClinicianItems.Listing.Items || suggestions !is ClinicianItems.Listing.Items) return null

        for (f in plans.items) {
            val key = keyOf(c.id, "gameplans", f)
            if (key in declined) continue
            when (val o = items.openGamePlan(f, owner, pinned)) {
                is ClinicianItems.Opening.Refused -> entries += InboxEntry.NotBelieved(key, name, InboxWords.notBelieved(o.refusal, name))
                is ClinicianItems.Opening.GamePlan -> {
                    // A lineage is one clinician's: another's item naming it would replace what they sent.
                    if (dao.gamePlanAuthors(o.lineageId).any { it != o.authorFingerprint }) {
                        entries += InboxEntry.NotBelieved(key, name, InboxWords.sameNameAsAnother(name))
                        continue
                    }
                    val highest = dao.highestAcceptedGamePlanVersion(o.lineageId)
                    if (highest != null && o.version <= highest) continue
                    // A withdrawal of a plan never accepted withdraws nothing on this phone.
                    if (o.status == "withdrawn" && highest == null) continue
                    entries += InboxEntry.Plan(key, name, o, replaces = highest != null)
                }
                is ClinicianItems.Opening.Assignment -> Unit
            }
        }

        // The grant decides what a suggestion may do. Read only when there is a suggestion to judge.
        var grant = Grant(items.fingerprint(pinned), emptyMap())
        if (suggestions.items.isNotEmpty()) {
            when (val g = items.grant(server, c.inboxToken, ownerSignPub, pinned)) {
                is ClinicianItems.GrantReading.Found -> grant = Grant(
                    g.grant.therapistFingerprint,
                    g.grant.capabilities.mapValues { (_, v) ->
                        CapabilityGrant(v.granted, if (v.apply == "auto") ApplyMode.AUTO else ApplyMode.PROPOSE)
                    },
                )
                ClinicianItems.GrantReading.None -> Unit
                ClinicianItems.GrantReading.NotBelieved -> notes += InboxWords.grantNotBelieved(name)
                ClinicianItems.GrantReading.NoAnswer -> return null
            }
        }

        for (f in suggestions.items) {
            val key = keyOf(c.id, "assignments", f)
            if (key in declined) continue
            when (val o = items.openAssignment(f, owner, pinned)) {
                is ClinicianItems.Opening.Refused -> entries += InboxEntry.NotBelieved(key, name, InboxWords.notBelieved(o.refusal, name))
                is ClinicianItems.Opening.Assignment -> {
                    if (dao.assignmentAuthors(o.lineageId).any { it != o.authorFingerprint }) {
                        entries += InboxEntry.NotBelieved(key, name, InboxWords.sameNameAsAnother(name))
                        continue
                    }
                    val highest = dao.highestAcceptedAssignmentVersion(o.lineageId)
                    if (highest != null && o.version <= highest) continue
                    val rule = com.daymark.app.companion.Assignment(o.type, o.capability, o.payload, o.authorFingerprint)
                    val check = AssignmentRules.validate(rule, grant, AssignableCatalogue.NONE)
                    val cannotAdd = when {
                        !check.ok -> InboxWords.refused(check.refusals, name)
                        // Settings are never applied from here: the phone has none of the allowlisted keys to change.
                        o.type == "setting" -> InboxWords.CANNOT_ADD_SETTING
                        else -> null
                    }
                    if (cannotAdd == null && AssignmentRules.shouldAutoApply(rule, check.applyMode!!)) {
                        store(o)
                        auto++
                    } else {
                        entries += InboxEntry.Suggestion(key, name, o, cannotAdd)
                    }
                }
                is ClinicianItems.Opening.GamePlan -> Unit
            }
        }
        return Checked(
            entries,
            notes,
            auto,
            plans.gone + suggestions.gone,
            plans.unreadable + suggestions.unreadable,
        )
    }

    /** Accepts [key]: the item as it was opened and checked, written into the app's tables. */
    fun accept(key: String) {
        val entry = _state.value.entries.firstOrNull { it.key == key } ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, words = null) }
        viewModelScope.launch {
            val saved = offMain({ false }) {
                when (entry) {
                    is InboxEntry.Plan -> store(entry.plan)
                    is InboxEntry.Suggestion -> if (entry.cannotAdd == null) store(entry.assignment) else return@offMain false
                    is InboxEntry.NotBelieved -> return@offMain false
                }
                true
            }
            val base = _state.value.copy(busy = false)
            _state.value = if (saved) {
                offMain({ base }) { accepted(base.copy(entries = base.entries.filter { it.key != key })) }
            } else {
                base.copy(words = InboxWords.NOT_SAVED)
            }
        }
    }

    /** Declines [key] on this phone. Nothing is sent: the clinician is not told. */
    fun decline(key: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            val kept = offMain({ false }) {
                clinicianStore.decline(key)
                true
            }
            _state.update {
                if (kept) it.copy(entries = it.entries.filter { e -> e.key != key }, words = InboxWords.DECLINED_NOTE)
                else it.copy(words = InboxWords.NOT_SAVED)
            }
        }
    }

    private suspend fun store(p: ClinicianItems.Opening.GamePlan) {
        val now = System.currentTimeMillis()
        val plan = GamePlan(
            lineageId = p.lineageId,
            version = p.version,
            supersedes = p.supersedes,
            status = p.status,
            reviewEvery = p.reviewEvery,
            reviewCount = p.reviewCount,
            authorFingerprint = p.authorFingerprint,
            issuedAt = p.issuedAt,
            acceptedAt = now,
            payloadJson = p.payloadJson,
            sigB64 = p.sigB64,
        )
        val items = p.items.mapIndexed { i, it ->
            GamePlanItem(
                lineageId = p.lineageId,
                version = p.version,
                itemRef = it.itemRef,
                position = i,
                kind = it.kind,
                title = it.title,
                detail = it.detail,
                targetPerWeek = it.targetPerWeek,
                dueAt = it.dueAt,
                recurrence = it.recurrence,
            )
        }
        dao.acceptGamePlan(plan, items)
    }

    private suspend fun store(a: ClinicianItems.Opening.Assignment) {
        dao.insertAssignment(
            AcceptedAssignment(
                lineageId = a.lineageId,
                version = a.version,
                type = a.type,
                authorFingerprint = a.authorFingerprint,
                issuedAt = a.issuedAt,
                acceptedAt = System.currentTimeMillis(),
                payloadJson = a.payloadJson,
                sigB64 = a.sigB64,
            ),
        )
    }

    private fun keyOf(clinicianId: String, channel: String, f: ClinicianItems.Fetched) = "$clinicianId/$channel/${f.lineage}/${f.version}"

    /** A stored suggestion in the inbox's words, from its type and the signed payload kept with it. */
    private fun describeStored(a: AcceptedAssignment): String {
        val payload = Regex("\"payload\":\\{([^}]*)\\}").find(a.payloadJson)?.groupValues?.get(1).orEmpty()
        fun field(name: String) = Regex("\"$name\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(payload)?.groupValues?.get(1)
        return when (a.type) {
            "goal" -> field("title")?.let { InboxWords.goal(it) } ?: InboxWords.SELF_CHECK
            "reminder" -> {
                val count = Regex("\"count\":(\\d+)").find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: 1
                InboxWords.reminder(count, field("every") ?: "week")
            }
            else -> InboxWords.SELF_CHECK
        }
    }

    private suspend fun <T> offMain(fallback: () -> T, block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback()
    }
}
