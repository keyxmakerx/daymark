package com.daymark.companion.routes

import com.daymark.companion.auth.CredentialKind
import com.daymark.companion.auth.OwnerAuth
import com.daymark.companion.auth.OwnerPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RootRouteSelector
import io.ktor.server.routing.RoutePathComponent
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingNode

/** The one body every refused owner credential gets, whichever check said no (#186). */
const val UNAUTHORIZED_MESSAGE = "unauthorized"

/**
 * The one answer, 403, a registered phone gets on every route in [PHONE_REFUSED_ROUTES]. It names the
 * credential rather than a page, because the pages these routes belong to differ: device management,
 * the notification settings and the key are the owner console's, practice provisioning the practice
 * console's.
 */
const val PHONE_REFUSED_MESSAGE = "a paired phone cannot do this"

/**
 * The routes a paired phone may not use, by method and path as the route tree names them (#186, #189):
 * the one list, read by [mayUseRoute] on every request an owner credential passes, and by
 * DeviceKeyRevocationTest's walk of the route tree.
 *
 * A phone is the owner's journal device, not the server operator's console. It is kept off:
 *  - DEVICE MANAGEMENT: minting a pairing code and reading one, confirming one, the device list and
 *    Revoke. The console is the side already trusted, and a phone may not add a device, so revoking a
 *    phone can never leave behind another it made.
 *  - PROVISIONING: creating a practice and seating its first admin, which is the operator's act (its
 *    audit actor is `platform`), not the journal owner's.
 *  - HOW THE OWNER RECOVERS:
 *    - The notification settings, which hold the address the token's re-issue link is mailed to. The
 *      recovery routes take no credential, so a phone that could set the address could have the
 *      console's token re-issued to whoever holds its key, and every other phone revoked with it; one
 *      that could read it would hand them the owner's email address, which no phone needs.
 *    - Writing the key documents: publishing the key parameters, creating the wrapped key, and writing
 *      a new version of it. The key documents are how the owner recovers: a phone that could write one
 *      could leave the owner's passphrase and recovery code opening nothing the server serves, and no
 *      route undoes that. The console sets and changes the key; a phone only reads it, so both reads
 *      stay open.
 *
 * Every other owner route takes a registered phone exactly as it takes the token, on the same owner
 * id. A route added here is refused to phones from then on; nothing else needs to change.
 */
internal val PHONE_REFUSED_ROUTES: Set<String> = setOf(
    "GET /v1/devices",
    "POST /v1/devices/pairing",
    "GET /v1/devices/pairing/{codeId}",
    "POST /v1/devices/pairing/{codeId}/confirm",
    "POST /v1/devices/{keyId}/revoke",
    "POST /v1/orgs",
    "GET /v1/owner/notifications",
    "PUT /v1/owner/notifications",
    "PUT /v1/keyparams",
    "POST /v1/keydoc",
    "PUT /v1/keydoc/{version}",
)

/**
 * The owner behind this request, or null once it has been answered: the gate every owner route shares,
 * for the bearer token and a registered device's signature alike (#186). Refusals are fixed and
 * non-enumerating: 401 for any credential that does not pass, 429 for a source over its rate or locked
 * out, 413 for a signed body larger than any route takes, 411 for one that does not state its length.
 *
 * Which routes a device reaches is [mayUseRoute]'s to say, once the credential has passed.
 */
internal suspend fun ApplicationCall.owner(auth: OwnerAuth): OwnerPrincipal? {
    val principal = when (val outcome = auth.check(this)) {
        is OwnerAuth.Outcome.Ok -> outcome.principal
        else -> {
            refuse(outcome)
            return null
        }
    }
    return if (mayUseRoute(principal, auth)) principal else null
}

/**
 * THE RULE FOR WHICH ROUTES A DEVICE REACHES: whether [principal], whose credential has passed, may use
 * the route this call is on; false once the one 403 [PHONE_REFUSED_MESSAGE] has been answered. The
 * token may use every owner route; a registered phone every one but those in the gate's
 * [OwnerAuth.phoneRefusedRoutes] ([PHONE_REFUSED_ROUTES] in a running server). A route whose name
 * cannot be read is refused to a phone, never opened to one. A revoked or pending key never gets this
 * far: it is answered the 401 every route gives it.
 *
 * Every owner check goes through here: [owner] for the owner's routes, and the relationship routes,
 * which take the owner or a clinician, for the owner's side. So a route put on the list is refused to
 * a phone wherever it is mounted.
 */
internal suspend fun ApplicationCall.mayUseRoute(principal: OwnerPrincipal, auth: OwnerAuth): Boolean {
    if (principal.kind != CredentialKind.DEVICE) return true
    val route = (this as? RoutingCall)?.route?.let(::routeKey)
    if (route != null && route !in auth.phoneRefusedRoutes) return true
    respond(HttpStatusCode.Forbidden, ErrorDto(PHONE_REFUSED_MESSAGE))
    return false
}

/** [owner], for the routes that need only to know the caller is the owner. */
internal suspend fun ApplicationCall.ownerAuthorized(auth: OwnerAuth): Boolean = owner(auth) != null

/**
 * A route as [PHONE_REFUSED_ROUTES] names it, `POST /v1/devices/{keyId}/revoke`: its method, then the
 * path segments from the root, a parameter as `{name}`. Read from the route tree's own selectors, so
 * the name is the one the router matched. Null for a node that is no method's.
 */
internal fun routeKey(node: RoutingNode): String? {
    val method = (node.selector as? HttpMethodRouteSelector)?.method ?: return null
    val segments = generateSequence(node.parent) { it.parent }.toList().asReversed().mapNotNull { ancestor ->
        when (val selector = ancestor.selector) {
            is RootRouteSelector -> null
            is PathSegmentConstantRouteSelector -> selector.value
            is PathSegmentParameterRouteSelector -> "${selector.prefix.orEmpty()}{${selector.name}}${selector.suffix.orEmpty()}"
            is RoutePathComponent -> selector.toString()
            else -> null
        }
    }.filter { it.isNotEmpty() }
    return "${method.value} /${segments.joinToString("/")}"
}

/** The fixed answer for each way an owner credential can be refused. */
internal suspend fun ApplicationCall.refuse(outcome: OwnerAuth.Outcome) {
    when (outcome) {
        is OwnerAuth.Outcome.Ok -> error("an accepted credential is not refused")
        OwnerAuth.Outcome.RateLimited -> respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited"))
        OwnerAuth.Outcome.Locked -> respond(HttpStatusCode.TooManyRequests, ErrorDto("temporarily locked"))
        OwnerAuth.Outcome.Unauthorized -> respond(HttpStatusCode.Unauthorized, ErrorDto(UNAUTHORIZED_MESSAGE))
        OwnerAuth.Outcome.TooLarge -> respond(HttpStatusCode.PayloadTooLarge, ErrorDto("request body too large"))
        OwnerAuth.Outcome.LengthRequired -> respond(HttpStatusCode.LengthRequired, ErrorDto(LENGTH_REQUIRED_MESSAGE))
    }
}

/** The answer, 411, to a signed request whose body does not state its length: a chunked one (#186). */
const val LENGTH_REQUIRED_MESSAGE = "a signed request must state its length"
