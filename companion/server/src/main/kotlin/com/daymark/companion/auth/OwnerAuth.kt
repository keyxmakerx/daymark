package com.daymark.companion.auth

import com.daymark.companion.clientAddress
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readAvailable
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** The kinds of credential an owner request can carry today (#186). Owner accounts add `account` and `cli` (#324). */
enum class CredentialKind(val wire: String) {
    /** The shared bearer token: the owner console's, until #324 gives it an account session. */
    TOKEN("token"),

    /** A phone's key, registered through the owner console, signing each request (#186, #189). */
    DEVICE("device"),
}

/**
 * Who an authenticated request is from: the owner, by the credential it carried. Every check
 * authorises on [ownerId] and the [kind], never on the token's digest (#186). [credentialId] is `token`
 * for the bearer token and the key id for a device, and is never a secret.
 */
data class OwnerPrincipal(val ownerId: String, val kind: CredentialKind, val credentialId: String)

/**
 * The one gate every owner route shares (#186): the bearer token, or a registered device's signature
 * (DeviceSignature says what one is), resolved to an [OwnerPrincipal].
 *
 * - A REQUEST CARRYING ANY OF THE SIGNATURE'S HEADERS is judged by its signature alone, and its
 *   `Authorization` header is not read. Otherwise it is judged by its bearer token.
 * - A SIGNATURE IS TAKEN when every header is there in its one spelling; its time is within
 *   [DeviceSignature.WINDOW_SECONDS] of the server's clock, either way, when the request arrives and
 *   again once its body has been read; the key it names is registered to this owner and has no
 *   revocation; the signature is that key's over the method, the target, the body, the time, the
 *   nonce and every header an owner route acts on ([DeviceSignature.SIGNED_HEADERS]), each at most
 *   once; and the key has not used the nonce before. The key and its revocation are read again once
 *   the nonce is taken, the last thing before the handler.
 * - NOTHING THAT DEPENDS ON THE KEY IS ANSWERED BEFORE THE BODY. Before it, only what the request's
 *   own headers and the clock can tell, and its address in [check]: a header missing or misspelled, a
 *   signed header twice or empty, a time outside the window, a length not stated (411) or over the cap
 *   (413), a rate or a lockout (429). Everything that reads the key or the nonce waits until the whole
 *   body has been read, so a body held back is answered no sooner for a key nobody paired than for a
 *   live one; and a key that is not live is checked against a key nobody's request can use, so a
 *   forged request costs the same work whatever key it names.
 * - A NONCE IS TAKEN ONLY BY A REQUEST ITS KEY SIGNED, after the signature is checked: a forged request
 *   leaves nothing behind. The time is judged after the body with the same reading of the clock that
 *   decides which nonces have lapsed, so a captured request sent again meets its nonce while it is
 *   inside its window, and is refused for its time once it is past it, however its body is held.
 * - EVERY REFUSAL IS THE SAME 401, whichever check said no. A failed signature counts toward the
 *   source's lockout exactly as a bad token does, in the same [AuthGuard], so one source has one
 *   budget whichever credential it tries. The lockout's audit row is written on arming, never per
 *   probe, and at most one a minute server-wide ([LOCKOUT_ROW_GAP_MS]), so sources without number
 *   cannot turn the owner's log into a disk-filler. A row therefore says that at least one address
 *   was paused that minute, not which ones: the first lockout armed in the minute takes its row, a
 *   decoy's as readily as any other.
 * - A KEY AWAITING ITS CONSOLE'S CONFIRMATION authenticates nothing. The one exception is
 *   [checkRegistration], which answers such a key "pending" and nothing more.
 * - A REGISTERED KEY reaches every owner route but those in [phoneRefusedRoutes], which the routes'
 *   gate answers 403 once the key has authenticated.
 *
 * Nothing here logs, and no refusal repeats a header, a signature, a nonce or a code.
 */
class OwnerAuth(
    val guard: AuthGuard,
    val devices: DeviceKeyStore,
    /** The largest body a signed request may carry: `DAYMARK_MAX_REQUEST_BYTES`, the most any route takes. */
    private val maxBodyBytes: Long,
    /**
     * The routes, `METHOD /path`, that answer a registered phone 403: `PHONE_REFUSED_ROUTES` in
     * `routes/OwnerCredentials.kt`, the one list. A parameter so a test can plant a route in it.
     */
    val phoneRefusedRoutes: Set<String>,
    /** Called when a failure from [source] arms a lockout; [credential] names what was tried. */
    private val onLockoutArmed: (source: String, credential: String) -> Unit = { _, _ -> },
) {
    /** The owner every principal names. */
    val ownerId: String get() = devices.ownerId

    sealed interface Outcome {
        data class Ok(val principal: OwnerPrincipal) : Outcome
        data object RateLimited : Outcome
        data object Locked : Outcome
        data object Unauthorized : Outcome

        /** A signed request's body is larger than any route takes. Answered 413; not a failure. */
        data object TooLarge : Outcome

        /** A signed request's body does not state its length (it is chunked). Answered 411; not a failure. */
        data object LengthRequired : Outcome
    }

    /** Where a key that signed the registration poll stands. */
    sealed interface Registration {
        data object Registered : Registration
        data class Pending(val confirmBy: Long) : Registration
        data class Refused(val outcome: Outcome) : Registration
    }

    /** Whether [call] carries any owner credential: a bearer token, or any of the signature's headers. */
    fun presentsCredential(call: ApplicationCall): Boolean =
        call.request.headers[HttpHeaders.Authorization] != null || isSigned(call)

    /** Whether [call] is a signed request: it carries at least one of the signature's headers. */
    fun isSigned(call: ApplicationCall): Boolean = DeviceSignature.HEADERS.any { call.request.headers[it] != null }

    /** The owner behind [call], or why not. */
    suspend fun check(call: ApplicationCall): Outcome {
        val source = call.clientAddress()
        admit(source)?.let { return it }
        if (isSigned(call)) {
            return when (val signed = verifySigned(call, allowPending = false)) {
                is Signed.Registered -> settle(source, Outcome.Ok(OwnerPrincipal(ownerId, CredentialKind.DEVICE, signed.keyId)), CredentialKind.DEVICE.wire)
                Signed.TooLarge -> Outcome.TooLarge
                Signed.LengthRequired -> Outcome.LengthRequired
                else -> settle(source, null, CredentialKind.DEVICE.wire)
            }
        }
        val bearer = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        val principal = if (guard.tokenMatches(bearer)) OwnerPrincipal(ownerId, CredentialKind.TOKEN, TOKEN_CREDENTIAL_ID) else null
        return settle(source, principal?.let { Outcome.Ok(it) }, CredentialKind.TOKEN.wire)
    }

    /**
     * The phone's registration poll (#189): a signed request, checked as every other is, from a key
     * that is registered or is awaiting its console's confirmation. Neither answer lets the key reach
     * anything else.
     */
    suspend fun checkRegistration(call: ApplicationCall): Registration {
        val source = call.clientAddress()
        admit(source)?.let { return Registration.Refused(it) }
        return when (val signed = verifySigned(call, allowPending = true)) {
            is Signed.Registered -> { guard.recordSuccess(source); Registration.Registered }
            is Signed.Pending -> { guard.recordSuccess(source); Registration.Pending(signed.confirmBy) }
            Signed.TooLarge -> Registration.Refused(Outcome.TooLarge)
            Signed.LengthRequired -> Registration.Refused(Outcome.LengthRequired)
            Signed.Refused -> Registration.Refused(settle(source, null, CredentialKind.DEVICE.wire))
        }
    }

    /** The rate limit and the lockout for [source], before a pairing code is looked at. Null to go on. */
    fun admit(source: String): Outcome? = when (guard.admit(source)) {
        null -> null
        AuthGuard.Result.RATE_LIMITED -> Outcome.RateLimited
        else -> Outcome.Locked
    }

    /** Count a wrong pairing code, or a redemption that proves nothing, as a failure of [source]. */
    fun failedAnonymously(source: String, credential: String) {
        settle(source, null, credential)
    }

    /** A pairing code taken: the source's failures are forgotten, as for a good credential. */
    fun succeededAnonymously(source: String) {
        guard.recordSuccess(source)
    }

    /** [ok] recorded as a success, or a failure recorded, and a lockout it arms reported once. */
    private fun settle(source: String, ok: Outcome.Ok?, credential: String): Outcome {
        if (ok != null) {
            guard.recordSuccess(source)
            return ok
        }
        if (guard.recordFailure(source)) reportLockout(source, credential)
        return Outcome.Unauthorized
    }

    private val lastLockoutRow = AtomicLong(Long.MIN_VALUE / 2)

    /**
     * The owner's log row for a lockout [source] has just armed, unless one was written in the last
     * minute: the row says that at least one address was paused this minute, and is the first one's.
     */
    private fun reportLockout(source: String, credential: String) {
        val now = devices.now()
        val previous = lastLockoutRow.get()
        if (now - previous < LOCKOUT_ROW_GAP_MS || !lastLockoutRow.compareAndSet(previous, now)) return
        onLockoutArmed(source, credential)
    }

    private sealed interface Signed {
        data class Registered(val keyId: String) : Signed
        data class Pending(val keyId: String, val confirmBy: Long) : Signed
        data object Refused : Signed
        data object TooLarge : Signed
        data object LengthRequired : Signed
    }

    /**
     * DeviceSignature's checks. Before the body, only what the request's own headers and the clock can
     * tell; everything that reads the key or the nonce once the whole body is in.
     */
    private suspend fun verifySigned(call: ApplicationCall, allowPending: Boolean): Signed {
        val headers = call.request.headers
        val keyId = headers[DeviceSignature.KEY_HEADER] ?: return Signed.Refused
        val time = headers[DeviceSignature.TIME_HEADER] ?: return Signed.Refused
        val nonce = headers[DeviceSignature.NONCE_HEADER] ?: return Signed.Refused
        val signatureB64 = headers[DeviceSignature.SIGNATURE_HEADER] ?: return Signed.Refused
        if (DeviceSignature.decodeCanonical(keyId, DeviceSignature.KEY_ID_BYTES) == null) return Signed.Refused
        if (DeviceSignature.decodeCanonical(nonce, DeviceSignature.NONCE_BYTES) == null) return Signed.Refused
        val signature = DeviceSignature.decodeCanonical(signatureB64, DeviceSignature.SIGNATURE_BYTES) ?: return Signed.Refused
        // Every header an owner route acts on is in the message, so none can be changed on the path.
        val headerValues = DeviceSignature.signedHeaderValues { name -> headers.getAll(name) } ?: return Signed.Refused
        val sentAt = (DeviceSignature.parseTime(time) ?: return Signed.Refused) * 1000
        // A request whose own time is already outside the window is refused before its body is read.
        if (!withinWindow(devices.now(), sentAt)) return Signed.Refused
        if (headers[HttpHeaders.TransferEncoding] != null) return Signed.LengthRequired
        val declared = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > maxBodyBytes) return Signed.TooLarge

        // The whole body, before anything that depends on the key or the nonce.
        val body = call.requestBody(maxBodyBytes) ?: return Signed.TooLarge

        // One reading of the clock judges the request's time and decides which nonces have lapsed: a body
        // finished after the window is refused, and inside it a nonce already used is still kept.
        val now = devices.now()
        if (!withinWindow(now, sentAt)) return Signed.Refused
        // The key and its revocation, read now: no verdict is kept between requests.
        val live = liveKey(keyId, allowPending)
        val message = DeviceSignature.requestMessage(
            call.request.httpMethod.value,
            call.request.local.uri,
            DeviceSignature.bodyHash(body),
            time,
            nonce,
            headerValues,
        )
        val signedByKey = DeviceSignature.verify(live?.first ?: NOBODYS_KEY, message, signature)
        if (live == null || !signedByKey) return Signed.Refused
        // Only a request its key signed takes a nonce, so a forged one leaves no row behind.
        if (!devices.rememberNonce(keyId, nonce, keepUntil = sentAt + DeviceSignature.WINDOW_MS, now = now)) return Signed.Refused
        // Read again, last, with nothing kept from the first reading: a phone revoked in between is
        // refused, and the handler never runs for it.
        return liveKey(keyId, allowPending)?.second ?: Signed.Refused
    }

    private fun withinWindow(now: Long, sentAt: Long): Boolean = abs(now - sentAt) <= DeviceSignature.WINDOW_MS

    /**
     * The key [keyId] names and what it may do: registered to this owner with no revocation, or, where
     * [allowPending], waiting for its console's confirmation. Null for anything else. Read from the
     * database on every call.
     */
    private fun liveKey(keyId: String, allowPending: Boolean): Pair<ByteArray, Signed>? {
        val registered = devices.registeredKey(keyId)
        return when {
            registered != null ->
                if (registered.revokedAt != null || registered.ownerId != ownerId) null else registered.publicKey to Signed.Registered(keyId)
            allowPending -> devices.pendingKey(keyId)?.let { it.publicKey to Signed.Pending(keyId, it.confirmBy) }
            else -> null
        }
    }

    companion object {
        /** The bearer token's credential id: there is one token, and its digest is never an id. */
        const val TOKEN_CREDENTIAL_ID = "token"

        /**
         * The key a signature is checked against when the key the request names is not live. What that
         * check answers is never taken; it is made so that a forged request costs the same whatever key
         * it names.
         */
        private val NOBODYS_KEY: ByteArray =
            ByteArray(DeviceSignature.PUBLIC_KEY_BYTES).also { Ed25519.generatePublicKey(ByteArray(32) { 0x5a }, 0, it, 0) }

        /**
         * The fewest milliseconds between two lockout rows in the owner's log. Each source arms at most
         * one lockout per episode; this bounds how many episodes at once reach the log. A row means that
         * at least one address was paused in the minute it opens, not that it was the only one: the
         * lockouts armed after it in that minute write none.
         */
        const val LOCKOUT_ROW_GAP_MS = 60_000L
    }
}

private val REQUEST_BODY = AttributeKey<ByteArray>("daymark.requestBody")
private val REQUEST_BODY_TOO_LARGE = AttributeKey<Unit>("daymark.requestBodyTooLarge")

/**
 * The request's body, up to [max] bytes, read from the network once and kept for every later reader on
 * this call; null when it is larger. A signed request's check reads it to hash it, and the handler
 * then reads the same bytes; a handler that read first leaves them for the check.
 *
 * Streamed, so a body over the cap is refused as soon as it crosses it. What was read of one that
 * crossed is not kept, and every later read of it answers null. Read by suspending, so a body held
 * back on the path holds no thread while it waits: every signed request's body is read before its
 * answer.
 */
suspend fun ApplicationCall.requestBody(max: Long): ByteArray? {
    if (attributes.contains(REQUEST_BODY_TOO_LARGE)) return null
    attributes.getOrNull(REQUEST_BODY)?.let { return if (it.size > max) null else it }
    val channel = receiveChannel()
    val buf = ByteArray(8 * 1024)
    val out = java.io.ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val n = channel.readAvailable(buf, 0, buf.size)
        if (n < 0) break
        total += n
        if (total > max) {
            attributes.put(REQUEST_BODY_TOO_LARGE, Unit)
            return null
        }
        out.write(buf, 0, n)
    }
    return out.toByteArray().also { attributes.put(REQUEST_BODY, it) }
}
