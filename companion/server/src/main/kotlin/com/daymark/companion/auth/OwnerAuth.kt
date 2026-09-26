package com.daymark.companion.auth

import com.daymark.companion.clientAddress
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import io.ktor.util.AttributeKey
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
 *   revocation, both read from the database when the request arrives and again once the signature has
 *   been checked, the last thing before the handler; the key has not used the nonce before;
 *   and the signature is that key's over the method, the target, the body, the time and the nonce.
 *   The nonce is taken before the body is read, so a captured request sent again is refused however
 *   its body is held, and the time judged with the same reading of the clock that decides which
 *   nonces have lapsed. A stranger's nonces cost a failure each, toward the lockout, and lapse with
 *   the window.
 * - EVERY REFUSAL IS THE SAME 401, whichever check said no. A failed signature counts toward the
 *   source's lockout exactly as a bad token does, in the same [AuthGuard], so one source has one
 *   budget whichever credential it tries. The lockout's audit row is written on arming, never per
 *   probe, and at most one a minute server-wide ([LOCKOUT_ROW_GAP_MS]), so sources without number
 *   cannot turn the owner's log into a disk-filler.
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
    }

    /** DeviceSignature's checks, cheapest first; the body is read only for a key that could pass. */
    private suspend fun verifySigned(call: ApplicationCall, allowPending: Boolean): Signed {
        val headers = call.request.headers
        val keyId = headers[DeviceSignature.KEY_HEADER] ?: return Signed.Refused
        val time = headers[DeviceSignature.TIME_HEADER] ?: return Signed.Refused
        val nonce = headers[DeviceSignature.NONCE_HEADER] ?: return Signed.Refused
        val signatureB64 = headers[DeviceSignature.SIGNATURE_HEADER] ?: return Signed.Refused
        if (DeviceSignature.decodeCanonical(keyId, DeviceSignature.KEY_ID_BYTES) == null) return Signed.Refused
        if (DeviceSignature.decodeCanonical(nonce, DeviceSignature.NONCE_BYTES) == null) return Signed.Refused
        val signature = DeviceSignature.decodeCanonical(signatureB64, DeviceSignature.SIGNATURE_BYTES) ?: return Signed.Refused
        val sentAt = (DeviceSignature.parseTime(time) ?: return Signed.Refused) * 1000
        // One reading of the clock judges the request's time and decides which nonces have lapsed, so
        // no later reading can forget the nonce of a request whose time was found good.
        val now = devices.now()
        if (!withinWindow(now, sentAt)) return Signed.Refused

        // The key and its revocation, read now: no verdict is kept between requests.
        val (publicKey, _) = liveKey(keyId, allowPending) ?: return Signed.Refused

        // The nonce is taken before the body is read, so a captured request sent again is refused
        // however slowly its body comes, and before any of it is read.
        if (!devices.rememberNonce(keyId, nonce, keepUntil = sentAt + DeviceSignature.WINDOW_MS, now = now)) return Signed.Refused

        val body = call.requestBody(maxBodyBytes) ?: return Signed.TooLarge
        // A body finished after the request's window has closed is refused: a request that could be
        // held open past its time could be completed by whoever held it.
        if (!withinWindow(devices.now(), sentAt)) return Signed.Refused

        val message = DeviceSignature.requestMessage(
            call.request.httpMethod.value,
            call.request.local.uri,
            DeviceSignature.bodyHash(body),
            time,
            nonce,
        )
        if (!DeviceSignature.verify(publicKey, message, signature)) return Signed.Refused
        // Read again, last, with nothing kept from the first reading: a phone revoked while its body was
        // on the way is refused, and the handler never runs for it.
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
         * The fewest milliseconds between two lockout rows in the owner's log. Each source arms at most
         * one lockout per episode; this bounds how many episodes at once reach the log.
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
 * crossed is not kept, and every later read of it answers null.
 */
suspend fun ApplicationCall.requestBody(max: Long): ByteArray? {
    if (attributes.contains(REQUEST_BODY_TOO_LARGE)) return null
    attributes.getOrNull(REQUEST_BODY)?.let { return if (it.size > max) null else it }
    val stream = receiveStream()
    val buf = ByteArray(8 * 1024)
    val out = java.io.ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val n = stream.read(buf)
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
