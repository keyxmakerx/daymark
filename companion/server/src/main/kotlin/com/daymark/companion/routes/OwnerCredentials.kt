package com.daymark.companion.routes

import com.daymark.companion.auth.CredentialKind
import com.daymark.companion.auth.OwnerAuth
import com.daymark.companion.auth.OwnerPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/** The one body every refused owner credential gets, whichever check said no (#186). */
const val UNAUTHORIZED_MESSAGE = "unauthorized"

/** The answer to a registered phone on a route only the owner console may use (#189). */
const val CONSOLE_ONLY_MESSAGE = "devices are managed from the owner console"

/**
 * The owner behind this request, or null once it has been answered: the gate every owner route shares,
 * for the bearer token and a registered device's signature alike (#186). Refusals are fixed and
 * non-enumerating: 401 for any credential that does not pass, 429 for a source over its rate or locked
 * out, 413 for a signed body larger than any route takes.
 *
 * THE RULE FOR WHICH ROUTES A DEVICE REACHES. Every route that takes the owner's token takes a
 * registered, unrevoked device key in its place, on the same owner id — except the routes that manage
 * devices ([ownerConsole]), which take the owner console's credential only.
 */
internal suspend fun ApplicationCall.owner(auth: OwnerAuth): OwnerPrincipal? = when (val outcome = auth.check(this)) {
    is OwnerAuth.Outcome.Ok -> outcome.principal
    else -> {
        refuse(outcome)
        null
    }
}

/** [owner], for the routes that need only to know the caller is the owner. */
internal suspend fun ApplicationCall.ownerAuthorized(auth: OwnerAuth): Boolean = owner(auth) != null

/**
 * [owner], for the routes that manage devices: minting and confirming a pairing code, the device list,
 * revoking. The owner console is the side already trusted; a phone may not add a device or extend its
 * own reach, so a device key is answered 403 here once it has authenticated. A revoked or pending key
 * never gets that far: it is answered the 401 every other route gives it.
 */
internal suspend fun ApplicationCall.ownerConsole(auth: OwnerAuth): OwnerPrincipal? {
    val principal = owner(auth) ?: return null
    if (principal.kind == CredentialKind.DEVICE) {
        respond(HttpStatusCode.Forbidden, ErrorDto(CONSOLE_ONLY_MESSAGE))
        return null
    }
    return principal
}

/** The fixed answer for each way an owner credential can be refused. */
internal suspend fun ApplicationCall.refuse(outcome: OwnerAuth.Outcome) {
    when (outcome) {
        is OwnerAuth.Outcome.Ok -> error("an accepted credential is not refused")
        OwnerAuth.Outcome.RateLimited -> respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
        OwnerAuth.Outcome.Locked -> respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
        OwnerAuth.Outcome.Unauthorized -> respond(HttpStatusCode.Unauthorized, ErrorDto(UNAUTHORIZED_MESSAGE))
        OwnerAuth.Outcome.TooLarge -> respond(HttpStatusCode.PayloadTooLarge, ErrorDto("request body too large"))
    }
}
