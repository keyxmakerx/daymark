package com.daymark.companion.routes

import com.daymark.companion.auth.DeviceKeyStore
import com.daymark.companion.auth.DeviceSignature
import com.daymark.companion.auth.OwnerAuth
import com.daymark.companion.auth.PairingCode
import com.daymark.companion.clientAddress
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.daymark.companion.audit")

/** The audit log is additive, never load-bearing: a logging bug must never fail a real request. */
private fun auditSafely(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        log.warn("audit log append failed", e)
    }
}

/** One line of the device list: when it was paired and its key, from which the console shows the words. No name. */
@Serializable data class DeviceDto(val keyId: String, val publicKey: String, val pairedAt: Long, val revokedAt: Long? = null)
@Serializable data class DeviceList(val devices: List<DeviceDto>)

/**
 * A minted code, for the console to show once and put in the QR with [baseUrl], the server's https
 * address. [codeId] is how the console asks after it; the code itself is never stored.
 */
@Serializable data class PairingCodeMinted(val codeId: String, val code: String, val baseUrl: String, val expiresAt: Long)

/**
 * Where a code stands, for the console: `waiting` until [expiresAt]; `redeemed` by [keyId] with
 * [publicKey], confirmable until [confirmBy]; `registered` since [pairedAt].
 */
@Serializable
data class PairingCodeView(
    val state: String,
    val expiresAt: Long? = null,
    val keyId: String? = null,
    val publicKey: String? = null,
    val confirmBy: Long? = null,
    val pairedAt: Long? = null,
)

/** The console's confirmation names the key whose words it showed, so it confirms that key and no other. */
@Serializable data class PairingConfirmRequest(val keyId: String)
@Serializable data class DeviceRegistered(val keyId: String, val pairedAt: Long)

/** The phone's redemption: the code, its public key, and its signature over the code's id and that key. */
@Serializable data class PairingRedeemRequest(val code: String, val publicKey: String, val signature: String)
@Serializable data class PairingRedeemed(val keyId: String, val confirmBy: Long)

/** The phone's own registration, as its poll reads it: `registered`, or `pending` until [confirmBy]. */
@Serializable data class RegistrationView(val state: String, val confirmBy: Long? = null)

/** The one refusal for a code that is unknown, lapsed, taken, or not this owner's — and for any code under http. */
const val NO_SUCH_CODE_MESSAGE = "no such pairing code"
const val NO_SUCH_DEVICE_MESSAGE = "no such device"

/** Minting's refusal while the server's public address is not https (#189). */
const val PAIRING_NEEDS_HTTPS_MESSAGE = "pairing needs an https address"

/** `by` on a `device.revoked` row: the console's Revoke, or a re-issued token revoking every device. */
const val DEVICE_REVOKED_BY_OWNER = "owner"
const val DEVICE_REVOKED_BY_REISSUE = "reissue"

/**
 * The one row a lockout of an owner credential writes in the owner's log (#186): on arming, never per
 * probe. [credential] names what armed it — `token`, `device` or `pairing-code` — and never a value.
 */
internal fun auditLockout(ownerAudit: AuditStore, ownerId: String, credential: String, sourceIp: String?) {
    auditSafely {
        val meta = buildMap {
            put("credential", credential)
            if (sourceIp != null) put("sourceIp", sourceIp)
        }
        ownerAudit.append(ownerId, AuditActor.OWNER, AuditAction.LOCKOUT, meta = meta)
    }
}

/** One row in the owner's log about the device [keyId] (#189). */
internal fun auditDevice(ownerAudit: AuditStore, ownerId: String, action: AuditAction, keyId: String, by: String?, sourceIp: String? = null) {
    auditSafely {
        val meta = buildMap {
            if (by != null) put("by", by)
            if (sourceIp != null) put("sourceIp", sourceIp)
        }
        ownerAudit.append(ownerId, AuditActor.OWNER, action, objectRef = keyId, meta = meta.ifEmpty { null })
    }
}

/**
 * Pairing a phone with the server, and the phones already paired (#186, #189).
 *
 * THE CEREMONY. The owner console mints a code; the phone makes a fresh key pair and redeems the code
 * with its public key and a signature over the code's id and that key, which proves it holds the key;
 * both screens show the words of that public key; the person confirms on the console, and only that
 * writes the key's row. Until then the key authenticates nothing and the phone keeps nothing, and a
 * key nobody confirms lapses by its timestamp.
 *
 * ONLY OVER HTTPS. A code is minted only while the server's public address is https (the same check
 * the cookies and #181 make), and on an http server every route here answers as if no code exists. On
 * plain http, anyone on the same network is on the path of both screens and could make their words
 * match; the phone refuses an http QR itself, and this is the depth behind it.
 *
 * THE CONSOLE'S, NOT THE PHONE'S. Minting, reading a code, confirming, the list and Revoke take the
 * owner console's credential only ([ownerConsole]): the console is the side already trusted, and a
 * phone may not add a device. The phone has two routes of its own: redeem, which takes no credential,
 * and the registration poll, which its key signs.
 *
 * ONE REFUSAL for every code that is not a live one of this owner's, 404 [NO_SUCH_CODE_MESSAGE]: a
 * code never minted, lapsed, taken by another key, or asked for under http. A wrong code burns
 * nothing, and counts toward the source's lockout exactly as a wrong token does.
 *
 * THE OWNER'S LOG (`owner-audit.db`, keyed on the owner id) gets one row when a key is registered
 * and one when it is revoked. Nothing here logs to stdout, and no answer repeats a code, a signature
 * or a key the caller did not send.
 */
fun Route.deviceRoutes(
    auth: OwnerAuth,
    /** Whether the server's public address is https (#189). */
    pairingOpen: Boolean,
    /** The server's public address, which the QR carries. */
    publicBaseUrl: String?,
    ownerAudit: AuditStore,
    auditSourceIp: Boolean = false,
) {
    val devices = auth.devices

    route("/v1/devices") {

        // The device list: every phone paired to this owner, with the date it was paired and its key,
        // revoked ones with the date they were. No free-text name: the server stores no content.
        get {
            val owner = call.ownerConsole(auth) ?: return@get
            call.respond(DeviceList(devices.devices(owner.ownerId).map { DeviceDto(it.keyId, it.publicKeyB64, it.pairedAt, it.revokedAt) }))
        }

        // Mint a code, good for two minutes and one redemption. Under http, nothing is minted.
        post("/pairing") {
            val owner = call.ownerConsole(auth) ?: return@post
            if (!pairingOpen || publicBaseUrl == null) {
                return@post call.respond(HttpStatusCode.Conflict, ErrorDto(PAIRING_NEEDS_HTTPS_MESSAGE))
            }
            val minted = devices.mintCode(owner.ownerId)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.Created, PairingCodeMinted(minted.codeId, minted.code, publicBaseUrl.trimEnd('/'), minted.expiresAt))
        }

        // Where one of the owner's codes stands: what the console polls while the phone redeems it.
        get("/pairing/{codeId}") {
            val owner = call.ownerConsole(auth) ?: return@get
            val codeId = call.parameters["codeId"]
            val state = if (pairingOpen && codeId != null) devices.codeState(owner.ownerId, codeId) else null
            when (state) {
                null -> call.respond(HttpStatusCode.NotFound, ErrorDto(NO_SUCH_CODE_MESSAGE))
                is DeviceKeyStore.CodeState.Waiting -> call.respond(PairingCodeView("waiting", expiresAt = state.expiresAt))
                is DeviceKeyStore.CodeState.Redeemed -> call.respond(
                    PairingCodeView("redeemed", keyId = state.keyId, publicKey = state.publicKeyB64, confirmBy = state.confirmBy),
                )
                is DeviceKeyStore.CodeState.Registered -> call.respond(
                    PairingCodeView("registered", keyId = state.keyId, pairedAt = state.pairedAt),
                )
            }
        }

        // The person compared the words and confirmed: the one thing that registers a key.
        post("/pairing/{codeId}/confirm") {
            val owner = call.ownerConsole(auth) ?: return@post
            val codeId = call.parameters["codeId"]
            val req = call.receiveCappedJson<PairingConfirmRequest>() ?: return@post
            val result = if (pairingOpen && codeId != null) {
                devices.confirm(owner.ownerId, codeId, req.keyId)
            } else {
                DeviceKeyStore.Confirmation.Refused
            }
            when (result) {
                is DeviceKeyStore.Confirmation.Registered -> {
                    val status = if (result.fresh) HttpStatusCode.Created else HttpStatusCode.OK
                    call.respond(status, DeviceRegistered(result.keyId, result.pairedAt))
                    if (result.fresh) {
                        auditDevice(ownerAudit, owner.ownerId, AuditAction.DEVICE_REGISTERED, result.keyId, by = null, call.sourceIpIf(auditSourceIp))
                    }
                }
                DeviceKeyStore.Confirmation.Refused -> call.respond(HttpStatusCode.NotFound, ErrorDto(NO_SUCH_CODE_MESSAGE))
            }
        }

        // Revoke a phone: every request it signs is refused from the next one on. A second Revoke
        // changes nothing and writes no second row.
        post("/{keyId}/revoke") {
            val owner = call.ownerConsole(auth) ?: return@post
            val keyId = call.parameters["keyId"]
            when (keyId?.let { devices.revoke(owner.ownerId, it) }) {
                null -> call.respond(HttpStatusCode.NotFound, ErrorDto(NO_SUCH_DEVICE_MESSAGE))
                true -> {
                    call.respond(HttpStatusCode.NoContent)
                    auditDevice(ownerAudit, owner.ownerId, AuditAction.DEVICE_REVOKED, keyId, DEVICE_REVOKED_BY_OWNER, call.sourceIpIf(auditSourceIp))
                }
                false -> call.respond(HttpStatusCode.NoContent)
            }
        }

        // The phone redeems a code. No credential: the code, and the proof that the phone holds the key
        // it names, are the whole of what it has. Everything that is not a live code for a key that has
        // never been seen gets the one refusal, and counts toward the source's lockout.
        post("/redeem") {
            val source = call.clientAddress()
            auth.admit(source)?.let { return@post call.refuse(it) }
            val req = call.receiveCappedJson<PairingRedeemRequest>() ?: return@post
            when (val redemption = redeem(devices, pairingOpen, req)) {
                is DeviceKeyStore.Redemption.Pending -> {
                    auth.succeededAnonymously(source)
                    call.respond(HttpStatusCode.Accepted, PairingRedeemed(redemption.keyId, redemption.confirmBy))
                }
                DeviceKeyStore.Redemption.Refused -> {
                    auth.failedAnonymously(source, PAIRING_CODE_CREDENTIAL)
                    call.respond(HttpStatusCode.NotFound, ErrorDto(NO_SUCH_CODE_MESSAGE))
                }
            }
        }

        // The phone asks whether its key is registered yet, signing the question with that key. The
        // only route a key awaiting confirmation reaches, and all it learns here is that it waits.
        get("/registration") {
            when (val registration = auth.checkRegistration(call)) {
                OwnerAuth.Registration.Registered -> call.respond(RegistrationView("registered"))
                is OwnerAuth.Registration.Pending -> call.respond(HttpStatusCode.Accepted, RegistrationView("pending", registration.confirmBy))
                is OwnerAuth.Registration.Refused -> call.refuse(registration.outcome)
            }
        }
    }

    // The owner's own log: phones paired and revoked, and the lockouts owner credentials armed. Owner
    // credential of either kind, newest first, paged as the relationship log is.
    get("/v1/owner/audit") {
        val owner = call.owner(auth) ?: return@get
        val before = call.request.queryParameters["before"]?.toLongOrNull()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        val cap = limit.coerceIn(1, AuditStore.MAX_PAGE_SIZE)
        val events = ownerAudit.list(owner.ownerId, before, cap)
        val nextCursor = if (events.size >= cap) events.lastOrNull()?.seq else null
        call.respond(AuditLogPage(events.map { it.toDto() }, nextCursor))
    }
}

/** What a failed redemption counts as on a lockout's audit row. */
internal const val PAIRING_CODE_CREDENTIAL = "pairing-code"

/**
 * The redemption's checks, cheapest first, and the store's verdict. The code is checked for its form,
 * the proof for the key it names, and the key for being a usable one, before anything is looked up.
 */
private fun redeem(devices: DeviceKeyStore, pairingOpen: Boolean, req: PairingRedeemRequest): DeviceKeyStore.Redemption {
    if (!PairingCode.isCanonical(req.code)) return DeviceKeyStore.Redemption.Refused
    val publicKey = DeviceSignature.decodeCanonical(req.publicKey, DeviceSignature.PUBLIC_KEY_BYTES) ?: return DeviceKeyStore.Redemption.Refused
    val signature = DeviceSignature.decodeCanonical(req.signature, DeviceSignature.SIGNATURE_BYTES) ?: return DeviceKeyStore.Redemption.Refused
    val codeId = DeviceSignature.codeIdOf(req.code)
    if (!DeviceSignature.verify(publicKey, DeviceSignature.redeemMessage(codeId, req.publicKey), signature)) return DeviceKeyStore.Redemption.Refused
    if (!DeviceSignature.isUsablePublicKey(publicKey)) return DeviceKeyStore.Redemption.Refused
    // Under http no code exists to be taken, whatever was minted before the address changed.
    if (!pairingOpen) return DeviceKeyStore.Redemption.Refused
    return devices.redeem(codeId, publicKey)
}

/** The caller's address for an audit row, when the operator turned that on; otherwise nothing. */
private fun ApplicationCall.sourceIpIf(enabled: Boolean): String? = if (enabled) clientAddress() else null
