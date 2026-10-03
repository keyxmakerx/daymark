package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import java.io.IOException

/**
 * The owner's invitations to a clinician, run from the phone (docs/COMPANION_PAIRING.md §3, §7;
 * #174): a relationship's inbox token and its reference, minting an invitation, listing where each
 * stands, and the one act that ends one. Kotlin mirror of `mintInviteToken`, `relRefOf`,
 * `mintInvite`, `reportInvite` and the invitation listing the owner console reads; every request is
 * signed by the phone's own key, which the server takes on these routes as it takes the owner's
 * token.
 *
 * ONLY A PERSON ENDS AN INVITATION. [report] is the one call here that does, and nothing in this
 * file calls it on the person's behalf: a reply that did not open, a run that was cancelled and an
 * invitation that ran out of runs all leave it where it is (§7).
 *
 * The server's answers are read strictly and never shown. The link is the one exception, because
 * the person sends it: it is taken only as an `https://` URL of printable ASCII, and the code, not
 * the link, is what a server that turned on its owner cannot forge.
 */
class ClinicianInvites(
    private val sodium: LazySodium,
    private val transport: Transport,
    private val clock: PhoneClock = PhoneClock.SYSTEM,
) {

    /**
     * One invitation. The link is for the clinician and the code goes by another way. [link] is null
     * once the screen that minted it has gone: it carries the invitation's secret, so it is kept in
     * memory only and never shown again.
     */
    class Invite(val inviteId: String, val link: String?, val expiresAt: Long) {
        override fun toString(): String = "Invite($inviteId)"
    }

    /** What minting came to. */
    sealed interface Minted {
        class Made internal constructor(val invite: Invite) : Minted
        /** No answer: an invitation may have been made, and the listing shows whether. */
        object NoAnswer : Minted
        /** An answer that was not an invitation. None was made that this phone can use. */
        object Refused : Minted
    }

    /** One invitation as the owner's listing reports it. */
    class Summary(
        val inviteId: String,
        val status: String,
        val expiresAt: Long,
        val failCount: Long,
        val exchangeCount: Long,
        /** The newest run's id and state, when the invitation has had one. */
        val latestExchangeId: String?,
        val latestExchangeState: String?,
    ) {
        /** Still able to take a run: not used, withdrawn or past its time. */
        val live: Boolean get() = status == "PENDING" || status == "REDEEMING"
    }

    /** Mint an invitation for [relRef]. */
    fun mint(server: PairedServer, relRef: String, scope: List<String>): Minted {
        if (!ID.matches(relRef) || scope.isEmpty() || scope.any { !SCOPE.matches(it) }) return Minted.Refused
        val scopes = scope.joinToString(",") { "\"$it\"" }
        val body = """{"relRef":"$relRef","scope":[$scopes]}"""
        val answer = try {
            SignedRequests(server, transport, clock).post("/v1/invite", body.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            return Minted.NoAnswer
        }
        if (answer.status != 201) return Minted.Refused
        val obj = Answers.jsonObject(answer.body) ?: return Minted.Refused
        val inviteId = obj["inviteId"] as? String ?: return Minted.Refused
        val link = obj["link"] as? String ?: return Minted.Refused
        val expiresAt = Answers.wholeNumber(obj["expiresAt"]) ?: return Minted.Refused
        if (!ID.matches(inviteId) || !LINK.matches(link)) return Minted.Refused
        return Minted.Made(Invite(inviteId, link, expiresAt))
    }

    /** Every invitation of [relRef], newest first. Null when the list could not be read. */
    fun list(server: PairedServer, relRef: String): List<Summary>? {
        if (!ID.matches(relRef)) return null
        val answer = try {
            SignedRequests(server, transport, clock).get("/v1/relations/$relRef/invites")
        } catch (_: IOException) {
            return null
        }
        if (answer.status != 200) return null
        val text = Answers.utf8(answer.body) ?: return null
        val rows = try {
            StrictJson.parse(text) as? List<*>
        } catch (_: StrictJson.JsonException) {
            null
        } ?: return null
        return rows.map { row -> summaryOf(row as? Map<*, *> ?: return null) ?: return null }
    }

    /**
     * End the invitation: the one act that kills one. True when the server ended it, false when
     * there was nothing left to end, null when no answer said either.
     */
    fun report(server: PairedServer, inviteId: String): Boolean? {
        if (!ID.matches(inviteId)) return false
        val answer = try {
            SignedRequests(server, transport, clock).post("/v1/invite/$inviteId/report", null)
        } catch (_: IOException) {
            return null
        }
        return when (answer.status) {
            204 -> true
            410 -> false
            else -> null
        }
    }

    /** A new relationship's inbox token: 32 random bytes, which the clinician is given by another way. */
    fun newInboxToken(): String = SyncCrypto.toBase64(sodium.randomBytesBuf(INBOX_TOKEN_BYTES))

    /** The relationship's reference on the server: BLAKE2b-256 of the token's text, as the web's `relRefOf`. */
    fun relRefOf(inboxToken: String): String {
        val input = inboxToken.toByteArray(Charsets.UTF_8)
        val out = ByteArray(32)
        if (!sodium.cryptoGenericHash(out, out.size, input, input.size.toLong())) {
            throw SyncCrypto.SyncCryptoException("BLAKE2b failed")
        }
        return SyncCrypto.toBase64(out)
    }

    private fun summaryOf(row: Map<*, *>): Summary? {
        val inviteId = row["inviteId"] as? String ?: return null
        val status = row["status"] as? String ?: return null
        val expiresAt = Answers.wholeNumber(row["expiresAt"]) ?: return null
        val failCount = Answers.wholeNumber(row["failCount"]) ?: return null
        val exchangeCount = Answers.wholeNumber(row["exchangeCount"]) ?: return null
        val latest = row["latestExchange"]
        val latestMap = latest as? Map<*, *>
        if (latest != null && latestMap == null) return null
        val latestId = latestMap?.let { it["exchangeId"] as? String ?: return null }
        val latestState = latestMap?.let { it["state"] as? String ?: return null }
        if (!ID.matches(inviteId) || (latestId != null && !ID.matches(latestId))) return null
        return Summary(inviteId, status, expiresAt, failCount, exchangeCount, latestId, latestState)
    }

    companion object {
        const val INBOX_TOKEN_BYTES = 32

        /** What an invitation from the phone lets the clinician do: read what the owner shares. */
        val READ_SHARE: List<String> = listOf("read.share")

        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val SCOPE = Regex("[a-z]+(\\.[a-z]+)*")

        /** An `https://` URL of printable ASCII and no space, at most 2048 characters. */
        private val LINK = Regex("https://[\\x21-\\x7e]{1,2040}")
    }
}
