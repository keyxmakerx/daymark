package com.daymark.companion.routes

import com.daymark.companion.auth.AttemptBudget
import com.daymark.companion.auth.CredentialKind
import com.daymark.companion.auth.DeviceSignature
import com.daymark.companion.auth.OwnerSignIn
import com.daymark.companion.auth.Totp
import com.daymark.companion.clientAddress
import com.daymark.companion.mail.MailMessage
import com.daymark.companion.mail.Mailer
import com.daymark.companion.mail.OwnerAccountStore
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditStore
import com.daymark.companion.storage.KeyDocumentStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.util.Base64

private val log = LoggerFactory.getLogger("com.daymark.companion.security")

@Serializable data class RecoveryChallengeDto(
    val challengeId: String,
    val challenge: String,
    val ownerId: String,
    val expiresAt: Long,
    /** The owner's wrapped key with its recovery-code slots only: never the passphrase slot. */
    val recoveryKey: JsonElement,
)

@Serializable data class OwnerRecoverRequest(
    val challengeId: String,
    val signature: String,
    val name: String,
    val totpSecret: String,
    val totpCode: String,
)

/** Every refusal of a recovery signature reads the same, so nothing says which check failed. */
const val RECOVERY_REFUSED = "that did not prove this server's recovery key; ask for a new challenge and try again"

/** How long a challenge may be signed for: long enough to type a recovery code, short enough to be useless later. */
const val RECOVERY_CHALLENGE_TTL_MS = 10 * 60 * 1000L

/**
 * Getting back in by the owner's own key (#325), once the owner's account exists and the sign-in is
 * lost. It replaces the emailed token reset, which handed the server's front door to whoever held
 * the mailbox.
 *
 * THE PROOF. The owner opens their wrapped key with the recovery code, derives the recovery key from
 * the master (subkey 5 of `dmsync01`, companion/web/src/lib/owner/recoveryKey.ts) and signs
 * [OwnerSignIn.recoveryMessage]: a fixed context, the owner's id, the address the browser is
 * talking to, and a challenge from here. The signature is checked against the public key recorded
 * when the account was made. Anyone who can make it already holds the key that opens the journal,
 * so the door and the lock are the same thing.
 *
 * WHAT MUST STAY TRUE.
 * - A CHALLENGE IS GOOD ONCE, FOR TEN MINUTES, FOR THIS OWNER AND THIS ADDRESS. It is spent before
 *   the signature is checked, so a replay and a wrong signature are the same refusal, [RECOVERY_REFUSED].
 * - THE ADDRESS IS THE CONFIGURED ONE, never the request's Host header, which the caller writes.
 *   A server without `DAYMARK_PUBLIC_BASE_URL` cannot bind the proof to itself and answers 503.
 * - THE PASSPHRASE SLOT IS NEVER SERVED HERE. The challenge carries the wrapped key's recovery-code
 *   slots alone: a recovery code carries about 143 bits and no guess can be tested against it, while
 *   a passphrase slot would let anyone test passphrase guesses offline.
 * - THE RECOVERY KEY IS INSERT-ONLY (OwnerSignIn.registerRecoveryKey). No route replaces it.
 * - A RECOVERY ENDS EVERY OWNER SESSION, writes one audit row and one log line, neither with a
 *   secret in it, and sends the fixed security notice to the registered address, if there is one.
 * - It decrypts nothing and resets nothing else: the phones, the CLI credentials, the key document
 *   and every relationship stay as they were.
 */
fun Route.ownerRecoveryRoutes(
    signIn: OwnerSignIn,
    accountStore: OwnerAccountStore,
    keyDocuments: KeyDocumentStore,
    ownerAudit: AuditStore,
    mailer: Mailer,
    publicBaseUrl: String?,
    challengeBudget: AttemptBudget,
    recoverBudget: AttemptBudget,
    sessionAbsoluteSeconds: Long,
    cookieSecure: Boolean,
    auditSourceIp: Boolean,
) {
    val address = publicBaseUrl?.trimEnd('/')

    post("/v1/owner/recovery/challenge") {
        if (!challengeBudget.allow(call.clientAddress())) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorDto("too many attempts from here; wait a minute and try again"))
            return@post
        }
        if (!signIn.exists()) {
            // Before the account there is no sign-in to lose: the token is the operator's file.
            call.respond(HttpStatusCode.NotFound, ErrorDto("this server's owner has no sign-in yet"))
            return@post
        }
        if (address == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto(NO_ADDRESS_MESSAGE))
            return@post
        }
        val slots = signIn.recoveryKey()?.let { recoverySlotsOf(keyDocuments.current()) }
        if (slots == null) {
            call.respond(HttpStatusCode.Conflict, ErrorDto(NO_RECOVERY_KEY_MESSAGE))
            return@post
        }
        val issued = signIn.issueRecoveryChallenge(RECOVERY_CHALLENGE_TTL_MS)
        call.respond(RecoveryChallengeDto(issued.challengeId, issued.challenge, signIn.ownerId, issued.expiresAt, slots))
    }

    post("/v1/owner/recovery") {
        if (!recoverBudget.allow(call.clientAddress())) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorDto("too many attempts from here; wait a minute and try again"))
            return@post
        }
        if (address == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorDto(NO_ADDRESS_MESSAGE))
            return@post
        }
        val req = call.receiveCappedJson<OwnerRecoverRequest>() ?: return@post
        val name = req.name.trim()
        if (!validName(name)) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("the name must be 1 to 64 letters, digits, spaces, dots, dashes or underscores"))
            return@post
        }
        val secret = decodeSeed(req.totpSecret)
        if (secret == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorDto("the authenticator key could not be read; start again"))
            return@post
        }
        // The new authenticator first, and nothing spent if it is wrong, so a mistyped six digits
        // costs no fresh challenge.
        val step = Totp.verifyStep(secret, req.totpCode.trim(), signIn.now() / 1000)
        if (step == null) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("the six digits did not match the authenticator; nothing was changed, so try the next six"))
            return@post
        }
        // Spent whatever happens next, so a challenge is never checked twice.
        val challenge = signIn.spendRecoveryChallenge(req.challengeId)
        val publicKey = signIn.recoveryKey()?.let { DeviceSignature.decodeCanonical(it, DeviceSignature.PUBLIC_KEY_BYTES) }
        val signature = DeviceSignature.decodeCanonical(req.signature, DeviceSignature.SIGNATURE_BYTES)
        val proved = challenge != null && publicKey != null && signature != null &&
            DeviceSignature.verify(publicKey, OwnerSignIn.recoveryMessage(signIn.ownerId, address, challenge), signature)
        if (!proved) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto(RECOVERY_REFUSED))
            return@post
        }
        if (!signIn.replaceSignIn(name, Base64.getUrlEncoder().withoutPadding().encodeToString(secret), step)) {
            call.respond(HttpStatusCode.NotFound, ErrorDto("this server's owner has no sign-in yet"))
            return@post
        }
        recoverBudget.reset(call.clientAddress())
        try {
            ownerAudit.append(
                signIn.ownerId, AuditActor.OWNER, AuditAction.OWNER_RECOVERED,
                meta = auditMeta(auditSourceIp, call, "credential" to CredentialKind.ACCOUNT.wire),
            )
        } catch (e: Exception) {
            log.warn("owner audit append failed", e)
        }
        log.info("the owner proved their recovery key and made a new sign-in; every earlier owner session is ended")
        accountStore.registeredEmail()?.let { to ->
            call.application.launch(Dispatchers.IO) {
                runCatching { mailer.send(MailMessage.SecurityNotice(to, MailMessage.SecurityEvent.SIGN_IN_RECOVERED)) }
                    .onFailure { log.warn("recovery notice failed to send: {}", it.javaClass.simpleName) }
            }
        }
        startOwnerSession(call, signIn, name, sessionAbsoluteSeconds, cookieSecure)
    }
}

const val NO_ADDRESS_MESSAGE =
    "this server has no public address set, so a recovery cannot be bound to it; its operator sets DAYMARK_PUBLIC_BASE_URL"

const val NO_RECOVERY_KEY_MESSAGE =
    "this server holds no recovery key or no recovery code for its owner, so getting back in this way is not possible here"

/**
 * The newest wrapped key with only its `recovery` slots, as `{"v":1,"slots":[…]}`, or null when
 * there is no wrapped key or it has no recovery slot. The server reads the slot list and its `kind`
 * labels and nothing else: the slots stay opaque, and are passed on exactly as stored.
 */
internal fun recoverySlotsOf(current: KeyDocumentStore.Current): JsonElement? {
    val wrapped = current as? KeyDocumentStore.Current.Wrapped ?: return null
    val doc = runCatching { Json.parseToJsonElement(wrapped.document.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject ?: return null
    val slots = doc["slots"] as? JsonArray ?: return null
    val recovery = slots.filter { slot -> ((slot as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull == "recovery" }
    if (recovery.isEmpty()) return null
    return JsonObject(mapOf("v" to (doc["v"] ?: JsonPrimitive(1)), "slots" to JsonArray(recovery)))
}
