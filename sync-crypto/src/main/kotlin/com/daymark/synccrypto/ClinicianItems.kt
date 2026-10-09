package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import java.io.IOException

/**
 * What a clinician sends the owner, fetched and opened on the phone (#177): assignments and game plans
 * from a relationship's `assignments` and `gameplans` channels, and the owner's own grant from its
 * `grants` channel. Kotlin mirror of the web's `assignments/crypto.ts` (`openAssignmentSigned`),
 * `assignments/inbox.ts` (`evaluateBlob`, `fetchInbox`), `therapist/gamePlan.ts` (`openGamePlan`) and
 * `assignments/grant.ts` (`verifyGrant`).
 *
 * AN ITEM IS BELIEVED ONLY IF ALL OF THESE HOLD, and each is checked here, in this order:
 *  1. it opens with the owner's box key (it was sealed to this owner, and not altered since);
 *  2. its padding is one `pad` could have produced, or it is the older unpadded form;
 *  3. its signature verifies under the clinician's signing key as PINNED AT PAIRING, never a key the
 *     item or the server offers;
 *  4. its `context` names its kind, so an assignment cannot be replayed as a game plan or a grant;
 *  5. its `recipientOwnerFp` is this owner's box key, so it cannot be re-pointed at another owner;
 *  6. the lineage and version signed inside it are the ones the server filed it under, so an old item
 *     cannot be shown again as new.
 * The signature covers the payload's text exactly as received; nothing here re-serialises it.
 *
 * NOTHING HERE ACCEPTS, APPLIES OR WRITES. An opened item is a proposal; the owner decides. Every read
 * is one the owner's tap asked for: no poll, no retry.
 */
class ClinicianItems(
    private val sodium: LazySodium,
    private val transport: Transport,
    private val clock: PhoneClock = PhoneClock.SYSTEM,
) {

    enum class Channel(val wire: String) { ASSIGNMENTS("assignments"), GAME_PLANS("gameplans"), GRANTS("grants") }

    /** The newest version of one lineage, as the server served it. Unopened. */
    class Fetched(val lineage: String, val version: Long, val bytes: ByteArray) {
        override fun toString(): String = "Fetched($lineage, $version)"
    }

    /** One channel's items, newest version of each lineage. */
    sealed interface Listing {
        /** [unreadable] counts lineages whose answer was not one an item can be read from. */
        class Items(val items: List<Fetched>, val gone: Int, val unreadable: Int) : Listing
        object NoAnswer : Listing
        object Refused : Listing
    }

    /** The owner's box key pair. The secret half is the caller's to wipe. */
    class OwnerBox(val publicKey: ByteArray, val secretKey: ByteArray) {
        init {
            require(publicKey.size == Box.PUBLICKEYBYTES && secretKey.size == Box.SECRETKEYBYTES) { "not a box key pair" }
        }

        override fun toString(): String = "OwnerBox(not shown)"
    }

    /** Why an item was not believed. The screen has words for each. */
    enum class Refusal {
        /** It did not open with this owner's key: not for this owner, or altered. */
        NOT_OPENED,

        /** It opened, and its signature is not the pinned clinician's. */
        NOT_THEIRS,

        /** Signed by them, and not this kind of item, or not addressed to this owner, or filed under another label. */
        MISDIRECTED,

        /** Signed by them, and not readable as this kind of item. */
        MALFORMED,
    }

    sealed interface Opening {
        class Refused(val refusal: Refusal) : Opening

        /** A signed assignment. [fields] is the whole `assignment` object; [payload] its `payload`, as plain values. */
        class Assignment(
            val lineageId: String,
            val version: Long,
            val type: String,
            val capability: String,
            val payload: Any?,
            val note: String?,
            val issuedAt: Long,
            val authorFingerprint: String,
            val payloadJson: String,
            val sigB64: String,
        ) : Opening

        class GamePlan(
            val lineageId: String,
            val version: Long,
            val supersedes: Long?,
            /** `active` or `withdrawn`. */
            val status: String,
            val items: List<PlanItem>,
            val reviewEvery: String?,
            val reviewCount: Int?,
            val authorFingerprint: String,
            val issuedAt: Long,
            val payloadJson: String,
            val sigB64: String,
        ) : Opening
    }

    class PlanItem(
        val itemRef: String,
        /** `goal`, `exercise`, `task` or `note`. */
        val kind: String,
        val title: String,
        val detail: String?,
        val targetPerWeek: Int?,
        val dueAt: Long?,
        val recurrence: String?,
    )

    /** A grant the owner signed for one clinician: which capabilities, and whether each applies on its own. */
    class Grant(val therapistFingerprint: String, val capabilities: Map<String, Capability>)

    class Capability(val granted: Boolean, val apply: String)

    sealed interface GrantReading {
        class Found(val grant: Grant) : GrantReading

        /** No grant has been published for this relationship: nothing is granted. */
        object None : GrantReading

        /** A grant is there and is not one this owner signed for this clinician: nothing is granted. */
        object NotBelieved : GrantReading
        object NoAnswer : GrantReading
    }

    /**
     * The newest version of every lineage in [channel], for the relationship [inboxToken] names. Lists
     * the lineages, then reads each one's current version: one request each, at the owner's tap.
     */
    fun fetch(server: PairedServer, inboxToken: String, channel: Channel): Listing {
        val relRef = relRefOf(inboxToken)
        val signed = listOf(REL_TOKEN to inboxToken)
        val requests = SignedRequests(server, transport, clock)
        val list = try {
            requests.get("/v1/rel/$relRef/${channel.wire}", signed)
        } catch (_: IOException) {
            return Listing.NoAnswer
        }
        when (list.status) {
            200 -> Unit
            404, 410 -> return Listing.Items(emptyList(), 0, 0)
            in 500..599 -> return Listing.NoAnswer
            else -> return Listing.Refused
        }
        val lineages = (Answers.jsonObject(list.body)?.get("lineages") as? List<*>) ?: return Listing.NoAnswer
        val items = ArrayList<Fetched>()
        var gone = 0
        var unreadable = 0
        for (entry in lineages.take(MAX_LINEAGES)) {
            val lineage = entry as? String
            if (lineage == null || !LINEAGE.matches(lineage)) {
                unreadable++
                continue
            }
            val answer = try {
                requests.get("/v1/rel/$relRef/${channel.wire}/$lineage/current", signed)
            } catch (_: IOException) {
                return Listing.NoAnswer
            }
            when (answer.status) {
                200 -> {
                    val version = answer.header("X-Version")?.takeIf { WHOLE.matches(it) }?.toLongOrNull()
                    if (version == null || answer.body.size > MAX_ITEM_BYTES) unreadable++
                    else items += Fetched(lineage, version, answer.body)
                }
                404, 410 -> gone++
                in 500..599 -> return Listing.NoAnswer
                else -> unreadable++
            }
        }
        unreadable += (lineages.size - MAX_LINEAGES).coerceAtLeast(0)
        return Listing.Items(items, gone, unreadable)
    }

    /** Opens [item] as an assignment sent by the clinician whose pinned signing key is [pinnedSignPub]. */
    fun openAssignment(item: Fetched, owner: OwnerBox, pinnedSignPub: ByteArray): Opening {
        val signed = when (val s = openSigned(item.bytes, owner, pinnedSignPub)) {
            is Signed.Refused -> return Opening.Refused(s.refusal)
            is Signed.Payload -> s
        }
        val top = signed.fields
        if (top["context"] != ASSIGNMENT_CONTEXT) return Opening.Refused(Refusal.MISDIRECTED)
        if (top["recipientOwnerFp"] != fingerprint(owner.publicKey)) return Opening.Refused(Refusal.MISDIRECTED)
        val a = top["assignment"] as? Map<*, *> ?: return Opening.Refused(Refusal.MALFORMED)
        val lineageId = a["lineageId"] as? String ?: return Opening.Refused(Refusal.MALFORMED)
        val version = Answers.wholeNumber(a["version"]) ?: return Opening.Refused(Refusal.MALFORMED)
        if (lineageId != item.lineage || version != item.version) return Opening.Refused(Refusal.MISDIRECTED)
        val type = a["type"] as? String ?: return Opening.Refused(Refusal.MALFORMED)
        val capability = a["capability"] as? String ?: return Opening.Refused(Refusal.MALFORMED)
        val author = a["authorFingerprint"] as? String ?: return Opening.Refused(Refusal.MALFORMED)
        // The signed name must be the key's own: it decides whose item this is shown as, and whose lineage.
        if (author != fingerprint(pinnedSignPub)) return Opening.Refused(Refusal.NOT_THEIRS)
        val issuedAt = Answers.wholeNumber(a["issuedAt"]) ?: return Opening.Refused(Refusal.MALFORMED)
        val note = when (val n = a["note"]) {
            null -> null
            is String -> n.takeIf { it.length <= MAX_TEXT } ?: return Opening.Refused(Refusal.MALFORMED)
            else -> return Opening.Refused(Refusal.MALFORMED)
        }
        if (!a.containsKey("payload")) return Opening.Refused(Refusal.MALFORMED)
        return Opening.Assignment(
            lineageId, version, type, capability, plain(a["payload"]), note, issuedAt, author, signed.payloadJson, signed.sigB64,
        )
    }

    /** Opens [item] as a game plan sent by the clinician whose pinned signing key is [pinnedSignPub]. */
    fun openGamePlan(item: Fetched, owner: OwnerBox, pinnedSignPub: ByteArray): Opening {
        val signed = when (val s = openSigned(item.bytes, owner, pinnedSignPub)) {
            is Signed.Refused -> return Opening.Refused(s.refusal)
            is Signed.Payload -> s
        }
        val p = signed.fields
        if (p["context"] != GAMEPLAN_CONTEXT) return Opening.Refused(Refusal.MISDIRECTED)
        if (p["recipientOwnerFp"] != fingerprint(owner.publicKey)) return Opening.Refused(Refusal.MISDIRECTED)
        val lineageId = p["lineageId"] as? String ?: return Opening.Refused(Refusal.MALFORMED)
        val version = Answers.wholeNumber(p["version"]) ?: return Opening.Refused(Refusal.MALFORMED)
        if (lineageId != item.lineage || version != item.version) return Opening.Refused(Refusal.MISDIRECTED)
        val supersedes = when (val v = p["supersedes"]) {
            null -> null
            else -> Answers.wholeNumber(v)?.takeIf { it < version } ?: return Opening.Refused(Refusal.MALFORMED)
        }
        val status = (p["status"] as? String)?.takeIf { it == "active" || it == "withdrawn" } ?: return Opening.Refused(Refusal.MALFORMED)
        val author = p["authorFingerprint"] as? String ?: return Opening.Refused(Refusal.MALFORMED)
        // The signed name must be the key's own: it decides whose item this is shown as, and whose lineage.
        if (author != fingerprint(pinnedSignPub)) return Opening.Refused(Refusal.NOT_THEIRS)
        val issuedAt = Answers.wholeNumber(p["issuedAt"]) ?: return Opening.Refused(Refusal.MALFORMED)
        val rawItems = p["items"] as? List<*> ?: return Opening.Refused(Refusal.MALFORMED)
        if (rawItems.size > MAX_PLAN_ITEMS) return Opening.Refused(Refusal.MALFORMED)
        if (status == "withdrawn" && rawItems.isNotEmpty()) return Opening.Refused(Refusal.MALFORMED)
        val items = rawItems.map { planItemOf(it) ?: return Opening.Refused(Refusal.MALFORMED) }
        if (items.map { it.itemRef }.toSet().size != items.size) return Opening.Refused(Refusal.MALFORMED)
        var every: String? = null
        var count: Int? = null
        p["reviewCadence"]?.let { c ->
            val m = c as? Map<*, *> ?: return Opening.Refused(Refusal.MALFORMED)
            every = (m["every"] as? String)?.takeIf { it in CADENCE_UNITS } ?: return Opening.Refused(Refusal.MALFORMED)
            count = Answers.wholeNumber(m["count"])?.takeIf { it in 1..MAX_COUNT }?.toInt() ?: return Opening.Refused(Refusal.MALFORMED)
        }
        return Opening.GamePlan(lineageId, version, supersedes, status, items, every, count, author, issuedAt, signed.payloadJson, signed.sigB64)
    }

    /**
     * The owner's current grant for this relationship, from its `grants` channel: believed only when it
     * verifies under the owner's own signing key and names the clinician whose pinned signing key is
     * [pinnedTherapistSignPub]. Anything else grants nothing.
     */
    fun grant(server: PairedServer, inboxToken: String, ownerSignPub: ByteArray, pinnedTherapistSignPub: ByteArray): GrantReading {
        val listing = fetch(server, inboxToken, Channel.GRANTS)
        val items = when (listing) {
            Listing.NoAnswer -> return GrantReading.NoAnswer
            Listing.Refused -> return GrantReading.NoAnswer
            is Listing.Items -> listing.items
        }
        val item = items.firstOrNull { it.lineage == GRANT_LINEAGE } ?: return GrantReading.None
        return verifyGrant(item.bytes, ownerSignPub, pinnedTherapistSignPub)
    }

    /** `grant.ts` `decodeSignedGrant` then `verifyGrant`, plus the clinician it names checked against the pinned key. */
    fun verifyGrant(bytes: ByteArray, ownerSignPub: ByteArray, pinnedTherapistSignPub: ByteArray): GrantReading {
        val env = Answers.jsonObject(bytes) ?: return GrantReading.NotBelieved
        val payloadJson = env["payloadJson"] as? String ?: return GrantReading.NotBelieved
        val sigB64 = env["ownerSigB64"] as? String ?: return GrantReading.NotBelieved
        val signingFp = env["ownerSigningFp"] as? String ?: return GrantReading.NotBelieved
        if (signingFp != fingerprint(ownerSignPub)) return GrantReading.NotBelieved
        if (!verifies(payloadJson, sigB64, ownerSignPub)) return GrantReading.NotBelieved
        val g = try {
            StrictJson.parse(payloadJson) as? Map<*, *>
        } catch (_: StrictJson.JsonException) {
            null
        } ?: return GrantReading.NotBelieved
        val fp = g["therapistFingerprint"] as? String ?: return GrantReading.NotBelieved
        if (fp != fingerprint(pinnedTherapistSignPub)) return GrantReading.NotBelieved
        val caps = g["capabilities"] as? Map<*, *> ?: return GrantReading.NotBelieved
        val out = LinkedHashMap<String, Capability>()
        for ((name, value) in caps) {
            val m = value as? Map<*, *> ?: return GrantReading.NotBelieved
            val granted = m["granted"] as? Boolean ?: return GrantReading.NotBelieved
            val apply = (m["apply"] as? String)?.takeIf { it == "auto" || it == "propose" } ?: return GrantReading.NotBelieved
            out[name as String] = Capability(granted, apply)
        }
        return GrantReading.Found(Grant(fp, out))
    }

    /** `crypto.ts` `fingerprint`: BLAKE2b-128 of the public key, base64url without padding. */
    fun fingerprint(publicKey: ByteArray): String {
        val out = ByteArray(FINGERPRINT_BYTES)
        if (!sodium.cryptoGenericHash(out, out.size, publicKey, publicKey.size.toLong())) {
            throw SyncCrypto.SyncCryptoException("BLAKE2b failed")
        }
        return SyncCrypto.toBase64(out)
    }

    private sealed interface Signed {
        class Refused(val refusal: Refusal) : Signed
        class Payload(val payloadJson: String, val sigB64: String, val fields: Map<*, *>) : Signed
    }

    /** Steps 1 to 3: opened, unpadded, and signed by the pinned key. The text is parsed only after it verifies. */
    private fun openSigned(blob: ByteArray, owner: OwnerBox, pinnedSignPub: ByteArray): Signed {
        if (blob.size < Box.SEALBYTES) return Signed.Refused(Refusal.NOT_OPENED)
        val opened = ByteArray(blob.size - Box.SEALBYTES)
        if (!sodium.cryptoBoxSealOpen(opened, blob, blob.size.toLong(), owner.publicKey, owner.secretKey)) {
            return Signed.Refused(Refusal.NOT_OPENED)
        }
        val envelope = try {
            if (opened.isNotEmpty() && opened[0] == UNPADDED_FIRST_BYTE) opened else Padding.unpad(opened)
        } catch (_: Padding.PaddingException) {
            return Signed.Refused(Refusal.NOT_OPENED)
        }
        val env = Answers.jsonObject(envelope) ?: return Signed.Refused(Refusal.NOT_OPENED)
        val payloadJson = env["payloadJson"] as? String ?: return Signed.Refused(Refusal.NOT_OPENED)
        val sigB64 = env["sigB64"] as? String ?: return Signed.Refused(Refusal.NOT_OPENED)
        if (!verifies(payloadJson, sigB64, pinnedSignPub)) return Signed.Refused(Refusal.NOT_THEIRS)
        val fields = try {
            StrictJson.parse(payloadJson) as? Map<*, *>
        } catch (_: StrictJson.JsonException) {
            null
        } ?: return Signed.Refused(Refusal.MALFORMED)
        return Signed.Payload(payloadJson, sigB64, fields)
    }

    private fun verifies(text: String, sigB64: String, signPub: ByteArray): Boolean {
        if (signPub.size != Sign.PUBLICKEYBYTES) return false
        val sig = try {
            SyncCrypto.fromBase64(sigB64)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (sig.size != Sign.BYTES) return false
        val message = text.toByteArray(Charsets.UTF_8)
        return sodium.cryptoSignVerifyDetached(sig, message, message.size, signPub)
    }

    private fun planItemOf(value: Any?): PlanItem? {
        val m = value as? Map<*, *> ?: return null
        val itemRef = (m["itemRef"] as? String)?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
        val kind = (m["kind"] as? String)?.takeIf { it in PLAN_KINDS } ?: return null
        val title = (m["title"] as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_TITLE } ?: return null
        val detail = optionalText(m, "detail", MAX_TEXT) ?: return null
        val recurrence = optionalText(m, "recurrence", MAX_TITLE) ?: return null
        val target = optionalWhole(m, "targetPerWeek") ?: return null
        val due = optionalWhole(m, "dueAt") ?: return null
        val targetPerWeek = target.value?.takeIf { it <= MAX_COUNT }?.toInt()
        if (target.value != null && targetPerWeek == null) return null
        return PlanItem(itemRef, kind, title, detail.value, targetPerWeek, due.value, recurrence.value)
    }

    /** A member that may be absent or null; the outer null means it is there and wrong. */
    private class Present<T>(val value: T?)

    private fun optionalText(m: Map<*, *>, name: String, max: Int): Present<String>? = when (val v = m[name]) {
        null -> Present(null)
        is String -> if (v.length <= max) Present(v) else null
        else -> null
    }

    private fun optionalWhole(m: Map<*, *>, name: String): Present<Long>? = when (val v = m[name]) {
        null -> Present(null)
        else -> Answers.wholeNumber(v)?.let { Present(it) }
    }

    private fun relRefOf(inboxToken: String): String {
        val input = inboxToken.toByteArray(Charsets.UTF_8)
        val out = ByteArray(32)
        if (!sodium.cryptoGenericHash(out, out.size, input, input.size.toLong())) {
            throw SyncCrypto.SyncCryptoException("BLAKE2b failed")
        }
        return SyncCrypto.toBase64(out)
    }

    companion object {
        const val ASSIGNMENT_CONTEXT = "daymark.assignment.v1"
        const val GAMEPLAN_CONTEXT = "daymark.gameplan.v1"

        /** The one lineage the owner's grant for a relationship is published under (`GrantManager.svelte`). */
        const val GRANT_LINEAGE = "grant"
        private const val REL_TOKEN = "X-Rel-Token"
        private const val UNPADDED_FIRST_BYTE: Byte = 0x7B
        private const val FINGERPRINT_BYTES = 16

        private const val MAX_LINEAGES = 200
        private const val MAX_ITEM_BYTES = 1 shl 20
        private const val MAX_PLAN_ITEMS = 200
        private const val MAX_TITLE = 500
        private const val MAX_TEXT = 4000
        private const val MAX_COUNT = 1000L
        private val PLAN_KINDS = setOf("goal", "exercise", "task", "note")
        private val CADENCE_UNITS = setOf("day", "week", "month")
        private val LINEAGE = Regex("[A-Za-z0-9._-]{1,128}")
        private val WHOLE = Regex("0|[1-9][0-9]{0,17}")

        /**
         * A JSON value as plain Kotlin: objects as maps, arrays as lists, whole numbers as [Long] and
         * other numbers as [Double]. What the app's assignment rules read.
         */
        internal fun plain(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { (k, v) -> k to plain(v) }
            is List<*> -> value.map { plain(it) }
            is StrictJson.Number -> value.lexeme.toLongOrNull() ?: value.lexeme.toDouble()
            else -> value
        }
    }
}
