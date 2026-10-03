package com.daymark.app.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.backup.BackupManager
import com.daymark.synccrypto.KeptLink
import com.daymark.synccrypto.PendingPairing
import com.daymark.synccrypto.PhonePairing
import com.daymark.synccrypto.PhoneSync
import com.daymark.synccrypto.PhoneWords
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Where the server screen stands (#432). */
enum class ServerSyncStage {
    /** Reading what the phone keeps. */
    LOADING,

    /** Not paired: the address and the code. */
    NOT_PAIRED,

    /** The code is on its way to the server. */
    REDEEMING,

    /** The six words are shown, and the phone asks whether the web has confirmed. */
    COMPARING,

    /** Paired, and the passphrase has not opened the sync key yet. */
    NEEDS_PASSPHRASE,

    /** Argon2id is running over the passphrase. */
    OPENING,

    /** Paired and the sync key kept: a copy can be sent. */
    READY,

    /** A copy is on its way. */
    SENDING,

    /** The newest copy on the server is being fetched and opened (#168). */
    FETCHING,

    /** A fetched copy is held in memory, and the person chooses what to do with it. Nothing has changed yet. */
    CHOOSING,

    /** The chosen copy is going into the journal. */
    TAKING_IN,
}

/** What a fetched copy holds, beside what the phone holds, for the person to choose between. */
data class FoundCopy(
    /** When the copy was made, written inside the copy: the server cannot change it. */
    val savedAt: Long,
    val copyEntries: Int,
    val copyPages: Int,
    val phoneEntries: Int,
    val phonePages: Int,
)

data class ServerSyncUiState(
    val stage: ServerSyncStage = ServerSyncStage.LOADING,
    /** The paired server's address, once there is one. */
    val address: String? = null,
    /** The six words, while they are compared. */
    val words: List<String> = emptyList(),
    /** The last thing the phone said, in fixed words ([PhoneWords]); null for nothing to say. */
    val message: String? = null,
    /** When the server took the last copy, on this phone's clock. */
    val lastSentAt: Long? = null,
    /** The name this phone's copies have on the server, once one has been sent: the web reads them by it. */
    val lineage: String? = null,
    /** While [ServerSyncStage.CHOOSING]: what the fetched copy and the phone each hold. */
    val found: FoundCopy? = null,
)

/**
 * The server screen's one errand at a time (#432): pair, open the sync key, send a copy. Every word it
 * says is one of [PhoneWords]; everything it keeps goes through [ServerLinkStore], and only once the
 * server has registered the key. Each errand runs off the main thread and keeps what it must before it
 * returns, so a screen closed at the wrong moment loses nothing the server already holds.
 *
 * The poll runs interruptibly: leaving the screen, or Cancel, calls off its next pause, and the pending
 * key goes with it, never kept. An errand that fails in a way the protocol does not name (the keystore
 * refusing a key, say) ends in fixed words too, never in a crash.
 */
@HiltViewModel
class ServerSyncViewModel @Inject constructor(
    private val store: ServerLinkStore,
    private val parts: ServerSyncParts,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val pairing: PhonePairing get() = parts.pairing
    private val phoneSync: PhoneSync get() = parts.phoneSync
    private val sodium get() = parts.sodium

    private val _state = MutableStateFlow(ServerSyncUiState())
    val state: StateFlow<ServerSyncUiState> = _state.asStateFlow()

    private var pairingJob: Job? = null

    /**
     * The fetched copy's backup, in memory only while the person chooses, and dropped the moment they
     * do. It is never written anywhere but into the journal, and only by [takeIn].
     */
    @Volatile private var heldCopy: String? = null

    init {
        viewModelScope.launch {
            _state.value = offMain({ stateOf(null, null) }) {
                val link = store.read()
                try {
                    stateOf(link, null)
                } finally {
                    link?.wipe()
                }
            }
        }
    }

    /**
     * The screen for what the phone keeps: nothing, a pairing, or a pairing and its sync key. Reads the
     * keystore once a copy has been sent, for the lineage's name, so it runs off the main thread.
     */
    private fun stateOf(link: KeptLink?, message: String?): ServerSyncUiState {
        if (link == null) return ServerSyncUiState(ServerSyncStage.NOT_PAIRED, message = message)
        val stage = if (link.hasSyncKey) ServerSyncStage.READY else ServerSyncStage.NEEDS_PASSPHRASE
        val lineage = if (link.lastSentAt != null) store.lineage() else null
        return ServerSyncUiState(stage, link.address, message = message, lastSentAt = link.lastSentAt, lineage = lineage)
    }

    /** What the person typed, or the pairing text they pasted into the address: read, redeemed, then asked about. */
    fun pair(addressOrText: String, code: String) {
        if (_state.value.stage != ServerSyncStage.NOT_PAIRED) return
        val ready = when (val reading = pairing.read(addressOrText, code)) {
            is PhonePairing.Reading.Declined -> {
                _state.update { it.copy(message = reading.words) }
                return
            }
            is PhonePairing.Reading.Ready -> reading
        }
        _state.value = ServerSyncUiState(ServerSyncStage.REDEEMING)
        pairingJob = viewModelScope.launch {
            val pending = when (val redemption = offMain<PhonePairing.Redemption?>({ null }) { pairing.redeem(ready) }) {
                null -> {
                    _state.value = ServerSyncUiState(ServerSyncStage.NOT_PAIRED, message = PhoneWords.UNREACHABLE)
                    return@launch
                }
                is PhonePairing.Redemption.Stopped -> {
                    _state.value = ServerSyncUiState(ServerSyncStage.NOT_PAIRED, message = redemption.words)
                    return@launch
                }
                is PhonePairing.Redemption.Waiting -> redemption.pending
            }
            _state.value = ServerSyncUiState(ServerSyncStage.COMPARING, pending.address, words = pending.words)
            val kept: Kept = try {
                runInterruptible(Dispatchers.IO) { awaitAndKeep(pending) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Kept(PhoneWords.UNREACHABLE, paired = false)
            }
            _state.value = if (kept.paired) {
                ServerSyncUiState(ServerSyncStage.NEEDS_PASSPHRASE, pending.address, message = kept.words)
            } else {
                ServerSyncUiState(ServerSyncStage.NOT_PAIRED, message = kept.words)
            }
        }
    }

    /** What the poll came to: the words to say, and whether the pairing is now kept. */
    private class Kept(val words: String, val paired: Boolean)

    /**
     * [pair]'s poll, blocking and interruptible. The key is kept here, before this returns: once the
     * server has registered it, the phone keeps it even if the screen closes in the same moment.
     */
    private fun awaitAndKeep(pending: PendingPairing): Kept =
        when (val registration = pairing.awaitRegistration(pending)) {
            is PhonePairing.Registration.Stopped -> Kept(registration.words, paired = false)
            is PhonePairing.Registration.Registered -> try {
                store.keepPaired(registration.server)
                Kept(PhoneWords.paired(registration.server.address), paired = true)
            } catch (_: Exception) {
                Kept(PhoneWords.COULD_NOT_KEEP, paired = false)
            }
        }

    /** Stops asking: the pending key is dropped, and nothing was kept. */
    fun cancelPairing() {
        if (_state.value.stage != ServerSyncStage.COMPARING) return
        pairingJob?.cancel()
        pairingJob = null
        _state.value = ServerSyncUiState(ServerSyncStage.NOT_PAIRED)
    }

    /** Opens the sync key on the server with [passphrase], and keeps it. Argon2id runs here: seconds. */
    fun unlock(passphrase: String) {
        if (_state.value.stage != ServerSyncStage.NEEDS_PASSPHRASE) return
        val before = _state.value
        _state.update { it.copy(stage = ServerSyncStage.OPENING, message = null) }
        viewModelScope.launch {
            _state.value = offMain({ before.copy(message = PhoneWords.COULD_NOT_OPEN) }) { unlockKept(passphrase) }
        }
    }

    /** [unlock]'s errand, off the main thread: the kept link's key, the key document, and the sync key kept. */
    private fun unlockKept(passphrase: String): ServerSyncUiState {
        val link = store.read() ?: return stateOf(null, null)
        try {
            val server = link.server(sodium) ?: return forgotten()
            return when (val unlock = phoneSync.unlock(server, passphrase)) {
                is PhoneSync.Unlock.Opened -> {
                    val unlocked = link.withSyncKey(unlock.syncKey, unlock.keyDocumentTag)
                    unlock.syncKey.fill(0)
                    try {
                        store.keep(unlocked)
                        stateOf(unlocked, null)
                    } finally {
                        unlocked.wipe()
                    }
                }
                is PhoneSync.Unlock.Stopped -> after(unlock.then, link, unlock.words)
            }
        } finally {
            link.wipe()
        }
    }

    /** Sends the journal's backup as the next copy, sealed under the kept sync key. */
    fun sendNow() {
        if (_state.value.stage != ServerSyncStage.READY) return
        val before = _state.value
        _state.update { it.copy(stage = ServerSyncStage.SENDING, message = null) }
        viewModelScope.launch {
            val plaintext = try {
                withContext(Dispatchers.IO) { backupManager.exportToJson(System.currentTimeMillis()).toByteArray(Charsets.UTF_8) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = before.copy(message = PhoneWords.NOT_SENT)
                return@launch
            }
            _state.value = offMain({ before.copy(message = PhoneWords.NOT_SENT) }) {
                try {
                    sendKept(plaintext)
                } finally {
                    plaintext.fill(0)
                }
            }
        }
    }

    /** [sendNow]'s errand, off the main thread: the kept link, its key and its sync key, and the copy sent. */
    private fun sendKept(plaintext: ByteArray): ServerSyncUiState {
        val link = store.read() ?: return stateOf(null, null)
        val syncKey = link.syncKey()
        val tag = link.keyDocumentTag
        try {
            val server = link.server(sodium) ?: return forgotten()
            if (syncKey == null || tag == null) return stateOf(link, null)
            return when (val sending = phoneSync.send(server, syncKey, tag, store.lineage(), plaintext)) {
                is PhoneSync.Sending.Sent -> {
                    val sent = link.withLastSent(sending.atMillis, sending.version)
                    try {
                        store.keep(sent)
                        stateOf(sent, null)
                    } finally {
                        sent.wipe()
                    }
                }
                is PhoneSync.Sending.Stopped -> after(sending.then, link, sending.words)
            }
        } finally {
            syncKey?.fill(0)
            link.wipe()
        }
    }

    /**
     * Fetches the newest copy on the server and opens it (#168). Nothing on the phone changes: the
     * copy is held while the screen shows what it and the phone each hold, and the person chooses.
     */
    fun fetchNow() {
        if (_state.value.stage != ServerSyncStage.READY) return
        val before = _state.value
        _state.update { it.copy(stage = ServerSyncStage.FETCHING, message = null) }
        viewModelScope.launch {
            // This phone's counts first, so the choice shows both sides; counts only, no row is read.
            val here = try {
                withContext(Dispatchers.IO) { backupManager.countsHere() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = before.copy(message = PhoneWords.NOT_FETCHED)
                return@launch
            }
            _state.value = offMain({ before.copy(message = PhoneWords.NOT_FETCHED) }) { fetchKept(here) }
        }
    }

    /** [fetchNow]'s errand, off the main thread: the copy fetched, opened and read, and the counts beside it. */
    private fun fetchKept(here: Pair<Int, Int>): ServerSyncUiState {
        val link = store.read() ?: return stateOf(null, null)
        val syncKey = link.syncKey()
        val tag = link.keyDocumentTag
        try {
            val server = link.server(sodium) ?: return forgotten()
            if (syncKey == null || tag == null) return stateOf(link, null)
            return when (val fetching = phoneSync.fetch(server, syncKey, tag)) {
                is PhoneSync.Fetching.Stopped -> after(fetching.then, link, fetching.words)
                is PhoneSync.Fetching.Fetched -> {
                    val text = try {
                        String(fetching.plaintext, Charsets.UTF_8)
                    } finally {
                        fetching.plaintext.fill(0)
                    }
                    when (val reading = backupManager.read(text)) {
                        BackupManager.Reading.Unreadable -> stateOf(link, PhoneWords.NOT_FETCHED)
                        BackupManager.Reading.FromNewerApp -> stateOf(link, PhoneWords.COPY_FROM_NEWER_APP)
                        is BackupManager.Reading.Readable -> {
                            val (entries, pages) = here
                            heldCopy = text
                            val c = reading.contents
                            stateOf(link, null).copy(
                                stage = ServerSyncStage.CHOOSING,
                                found = FoundCopy(c.exportedAt, c.entries, c.journalPages, entries, pages),
                            )
                        }
                    }
                }
            }
        } finally {
            syncKey?.fill(0)
            link.wipe()
        }
    }

    /**
     * Takes the held copy into the journal, as the person chose: [replace] empties this phone's journal
     * and puts the copy in its place, in one transaction; otherwise the copy's rows are added beside
     * this phone's. The held copy is dropped either way.
     */
    fun takeIn(replace: Boolean) {
        if (_state.value.stage != ServerSyncStage.CHOOSING) return
        val text = heldCopy ?: return keepThisPhone()
        heldCopy = null
        val before = _state.value.copy(stage = ServerSyncStage.READY, found = null)
        _state.value = _state.value.copy(stage = ServerSyncStage.TAKING_IN, message = null)
        viewModelScope.launch {
            val mode = if (replace) BackupManager.ImportMode.REPLACE else BackupManager.ImportMode.MERGE
            val words = try {
                withContext(Dispatchers.IO) { backupManager.importFromJson(text, mode) }
                if (replace) PhoneWords.REPLACED else PhoneWords.ADDED
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (replace) PhoneWords.NOT_REPLACED else PhoneWords.NOT_ALL_ADDED
            }
            _state.value = before.copy(message = words)
        }
    }

    /** Drops the held copy: nothing on the phone changes, and the server's copy stays where it is. */
    fun keepThisPhone() {
        if (_state.value.stage != ServerSyncStage.CHOOSING) return
        heldCopy = null
        _state.update { it.copy(stage = ServerSyncStage.READY, found = null, message = null) }
    }

    override fun onCleared() {
        heldCopy = null
        super.onCleared()
    }

    /** Forgets the server on this phone. The server keeps the key's row until it is revoked on the web. */
    fun forget() {
        val stage = _state.value.stage
        if (stage != ServerSyncStage.NEEDS_PASSPHRASE && stage != ServerSyncStage.READY) return
        viewModelScope.launch {
            offMain({ Unit }) { store.forgetLink() }
            _state.value = ServerSyncUiState(ServerSyncStage.NOT_PAIRED)
        }
    }

    /** Clears the last thing said, once the person starts typing again. */
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** What the phone keeps after an errand stopped, and the screen for it. Runs off the main thread. */
    private fun after(then: PhoneSync.Then, link: KeptLink, words: String): ServerSyncUiState = when (then) {
        PhoneSync.Then.TRY_AGAIN -> stateOf(link, words)
        PhoneSync.Then.UNLOCK_AGAIN -> {
            val relocked = link.withoutSyncKey()
            try {
                store.keep(relocked)
                stateOf(relocked, words)
            } finally {
                relocked.wipe()
            }
        }
        PhoneSync.Then.PAIR_AGAIN -> {
            store.forgetLink()
            stateOf(null, words)
        }
    }

    /** A kept link whose seed does not make the key it names: forgotten, and the phone pairs again. */
    private fun forgotten(): ServerSyncUiState {
        store.forgetLink()
        return stateOf(null, PhoneWords.DISCONNECTED)
    }

    /**
     * [block] on the IO dispatcher; [fallback] if it throws anything but a cancellation, which is passed
     * on. The protocol names every failure it expects; this is for the ones it cannot, such as a
     * keystore that will not seal.
     */
    private suspend fun <T> offMain(fallback: () -> T, block: () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback()
    }
}
