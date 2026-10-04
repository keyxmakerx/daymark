package com.daymark.app.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.synccrypto.ClinicianCeremony
import com.daymark.synccrypto.KeptClinicians
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

/** One clinician in the list: the owner's own name for them. */
data class ClinicianRow(val id: String, val name: String)

/** The sign-in key of a clinician just added, shown once and then dropped. */
class NewClinician(val id: String, val name: String, val inboxToken: String) {
    override fun toString(): String = "NewClinician($id)"
}

/** The clinician open on screen, and where their invitation stands. */
data class OpenClinician(
    val id: String,
    val name: String,
    /** Null while the server is first asked where things stand. */
    val phase: ClinicianCeremony.Phase?,
    /** The last halt's words, or null. */
    val words: String? = null,
    val busy: Boolean = false,
)

data class CliniciansUiState(
    val loading: Boolean = true,
    /** Paired, and the passphrase has opened the key: the owner's keys are here to seal back. */
    val ready: Boolean = false,
    val unreadable: Boolean = false,
    val clinicians: List<ClinicianRow> = emptyList(),
    val nameRefused: Boolean = false,
    val added: NewClinician? = null,
    val open: OpenClinician? = null,
)

/**
 * The Clinicians screens' errands (#174): add a clinician, then run [ClinicianCeremony] for one at a
 * time. Each tap is one call into the ceremony, off the main thread, and nothing here repeats one: there
 * is no timer and no poll, because asking more often after a reply failed to open would tell the server
 * the code was wrong (docs/COMPANION_PAIRING.md §4). What the ceremony keeps goes through
 * [ClinicianStore] before the call returns, so a run's scalar is on disk before its code is on screen.
 */
@HiltViewModel
class CliniciansViewModel @Inject constructor(
    private val links: ServerLinkStore,
    private val store: ClinicianStore,
    private val parts: ServerSyncParts,
) : ViewModel() {

    private val _state = MutableStateFlow(CliniciansUiState())
    val state: StateFlow<CliniciansUiState> = _state.asStateFlow()

    /** What is kept, as last read or written; null while unreadable. */
    @Volatile private var kept: KeptClinicians? = null
    @Volatile private var ceremony: ClinicianCeremony? = null
    @Volatile private var book: KeptClinicians.Book? = null

    init {
        viewModelScope.launch {
            _state.value = offMain({ CliniciansUiState(loading = false) }) { load() }
        }
    }

    private fun load(): CliniciansUiState {
        val link = links.read()
        val ready = try {
            link != null && link.hasSyncKey && links.ownerKeys() != null
        } finally {
            link?.wipe()
        }
        return when (val reading = store.read()) {
            ClinicianStore.Reading.Unreadable -> {
                kept = null
                CliniciansUiState(loading = false, ready = ready, unreadable = true)
            }
            is ClinicianStore.Reading.Kept -> {
                kept = reading.clinicians
                CliniciansUiState(loading = false, ready = ready, clinicians = rowsOf(reading.clinicians))
            }
        }
    }

    private fun rowsOf(k: KeptClinicians) = k.clinicians.map { ClinicianRow(it.id, it.displayName) }

    /** Adds a clinician under the owner's [name] with a fresh sign-in key, which is shown once. */
    fun add(name: String) {
        val current = kept ?: return
        val trimmed = name.trim()
        if (!KeptClinicians.validName(trimmed)) {
            _state.update { it.copy(nameRefused = true) }
            return
        }
        viewModelScope.launch {
            val added = offMain({ null }) {
                val (next, clinician) = current.adding(parts.sodium, trimmed, parts.clinicianInvites.newInboxToken())
                store.keep(next)
                kept = next
                clinician
            }
            _state.update {
                if (added == null) it.copy(nameRefused = false)
                else it.copy(nameRefused = false, clinicians = rowsOf(kept!!), added = NewClinician(added.id, added.displayName, added.inboxToken))
            }
        }
    }

    /** The sign-in key has been given: it leaves the screen and is not shown again. */
    fun tokenGiven() {
        val added = _state.value.added ?: return
        _state.update { it.copy(added = null) }
        open(added.id)
    }

    /** Leaves the sign-in key without opening the clinician. It is not shown again. */
    fun dropShownToken() = _state.update { it.copy(added = null) }

    fun clearNameRefused() = _state.update { it.copy(nameRefused = false) }

    /** Opens one clinician, and asks the server where their invitation stands. */
    fun open(id: String) {
        val current = kept ?: return
        val clinician = current.find(id) ?: return
        _state.update { it.copy(open = OpenClinician(id, clinician.displayName, null, busy = true)) }
        viewModelScope.launch {
            val built = offMain({ false }) { build(id) }
            if (!built) {
                _state.update { it.copy(open = it.open?.copy(phase = null, words = ClinicianWords.NO_ANSWER_UNSENT, busy = false)) }
                return@launch
            }
            _state.update { it.copy(open = it.open?.copy(busy = false)) }
            run(sent = false, restoring = true) { c, _ -> c.restore() }
        }
    }

    /** The ceremony for [id], over the kept server link and the owner's keys. Runs off the main thread. */
    private fun build(id: String): Boolean {
        val current = kept ?: return false
        val clinician = current.find(id) ?: return false
        val ownerKeys = links.ownerKeys() ?: return false
        val link = links.read() ?: return false
        val server = try {
            link.server(parts.sodium)
        } finally {
            link.wipe()
        } ?: return false
        val keep = KeptClinicians.Book(current, id, parts.clock) { next ->
            store.keep(next)
            kept = next
        }
        book = keep
        ceremony = ClinicianCeremony(
            server,
            parts.clinicianInvites.relRefOf(clinician.inboxToken),
            parts.clinicianPairing,
            parts.clinicianInvites,
            ownerKeys,
            keep,
            parts::drawCode,
        )
        return true
    }

    fun startInvitation() = run(sent = true) { c, _ -> c.startInvitation() }
    fun showCode() = run(sent = true) { c, p -> c.openRun(p) }
    fun checkForReply() = run(sent = false) { c, p -> c.checkForReply(p) }
    fun approve() = run(sent = true) { c, p -> c.approve(p) }
    fun newCode() = run(sent = true) { c, p -> c.newCode(p) }
    fun keepOpen() = run(sent = false) { c, p -> c.keepInvitation(p) }
    fun takeBack() = run(sent = true) { c, p -> c.abandonApproval(p) }
    fun stop() = run(sent = true) { c, p -> c.stopInvitation(p) }
    fun lookAgain() = run(sent = false, restoring = true) { c, _ -> c.restore() }

    /**
     * Puts an offer that would replace held keys aside without approving: nothing is sent or recorded,
     * and the run stays open to be checked again.
     */
    fun notNow() {
        val open = _state.value.open ?: return
        val phase = open.phase as? ClinicianCeremony.Phase.Answered ?: return
        val c = ceremony ?: return
        c.discard(phase)
        val run = book?.loadRun()
        _state.update {
            it.copy(
                open = open.copy(
                    phase = if (run == null) ClinicianCeremony.Phase.Invited(phase.invite, phase.attemptsLeft, phase.failCount)
                    else ClinicianCeremony.Phase.Resumed(phase.invite, run, phase.attemptsLeft, phase.failCount),
                    words = null,
                ),
            )
        }
    }

    /** Back to the list. What an answered offer holds is wiped as it goes. */
    fun close() {
        val open = _state.value.open ?: return
        open.phase?.let { p -> ceremony?.discard(p) }
        ceremony = null
        book = null
        _state.update { it.copy(open = null, clinicians = kept?.let(::rowsOf) ?: it.clinicians) }
    }

    /** One tap: [step] off the main thread, then its phase and its words on screen. */
    private fun run(
        sent: Boolean,
        restoring: Boolean = false,
        step: (ClinicianCeremony, ClinicianCeremony.Phase) -> ClinicianCeremony.Step,
    ) {
        val open = _state.value.open ?: return
        val c = ceremony ?: return
        if (open.busy) return
        val phase = open.phase ?: ClinicianCeremony.Phase.Idle
        _state.update { it.copy(open = open.copy(busy = true, words = null)) }
        viewModelScope.launch {
            val result = offMain({ ClinicianCeremony.Step(phase, ClinicianCeremony.Halt.NO_ANSWER) }) { step(c, phase) }
            // A tap that ran while the screen was left speaks to nobody: drop it.
            if (ceremony !== c) {
                c.discard(result.phase)
                return@launch
            }
            _state.update {
                it.copy(
                    open = it.open?.copy(
                        // Where things stand is unknown when the server did not say: no phase, so
                        // nothing offers to mint a second invitation beside a live one.
                        phase = if (restoring && result.halt != null) null else result.phase,
                        words = result.halt?.let { h -> ClinicianWords.halt(h, sent) },
                        busy = false,
                    ),
                )
            }
        }
    }

    override fun onCleared() {
        _state.value.open?.phase?.let { p -> ceremony?.discard(p) }
        super.onCleared()
    }

    private suspend fun <T> offMain(fallback: () -> T, block: () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback()
    }
}
