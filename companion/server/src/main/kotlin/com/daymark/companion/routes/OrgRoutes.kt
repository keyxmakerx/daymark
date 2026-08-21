package com.daymark.companion.routes

import com.daymark.companion.auth.AttemptLimiter
import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.auth.AuthStore
import com.daymark.companion.auth.Secrets
import com.daymark.companion.auth.Totp
import com.daymark.companion.clientAddress
import com.daymark.companion.org.Membership
import com.daymark.companion.org.OrgAction
import com.daymark.companion.org.OrgRole
import com.daymark.companion.org.OrgStore
import com.daymark.companion.org.OrgWrite
import com.daymark.companion.storage.AuditAction
import com.daymark.companion.storage.AuditActor
import com.daymark.companion.storage.AuditStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.Base64

private val log = LoggerFactory.getLogger("com.daymark.companion.audit")

/** The audit log is additive, never load-bearing: a logging bug must never fail a real request. */
private fun auditSafely(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        log.warn("org audit append failed", e)
    }
}

@Serializable data class CreateOrgRequest(val name: String, val adminMemberId: String)
@Serializable data class OrgDto(val orgId: String, val name: String, val createdAt: Long)
@Serializable data class AddMemberRequest(val memberId: String, val role: String)
@Serializable data class ChangeRoleRequest(val role: String)

/**
 * One person's standing in one practice, as the wire sees it.
 *
 * Four fields, and the list of fields is the feature. There is no wrapped key here, no grant
 * handle, no patient reference, no ciphertext and no pointer to any — not withheld by a filter that
 * could be relaxed, but absent from the type, so there is no version of this response that carries
 * one. An org admin reading their whole roster learns who works at their practice and what each of
 * them may *do*. What any of those people may *read* is not in this object because it is not in
 * this plane: it lives in grants the patients minted, and this server cannot open those either.
 *
 * `accepted` is the fourth field and the newest, and it is membership metadata like the other
 * three: it says whether the person named has themselves agreed that this seat is theirs. It is on
 * the wire rather than kept private to the store because an admin looking at a roster needs to be
 * able to tell a colleague from an outstanding offer — and because the answer explains, without
 * anyone having to read this file, why removing an unaccepted seat cuts no sessions.
 */
@Serializable data class OrgMemberDto(
    val memberId: String,
    val role: String,
    val addedAt: Long,
    val accepted: Boolean,
)

@Serializable data class OrgRosterDto(val orgId: String, val members: List<OrgMemberDto>)

/**
 * The result of removing somebody, including how many live sessions were cut in the same act.
 *
 * The count is here because "immediately" is a claim, and an admin who has just revoked somebody is
 * entitled to see it land rather than take it on faith. It is a number of sessions — not a number
 * of anything read, opened, or held — and it is a long way short of revocation in full: the
 * cryptographic half is a patient rotating their own key on their own device, which no response
 * from this server could honestly report on, and the removed person's own credential is untouched
 * either way.
 *
 * It is zero, always, for a seat the person never accepted, and that is not a rounding of the
 * truth — nothing is cut. A count that reported on an unaccepted seat would answer "is this
 * credential signed in right now?" for any id an admin cared to type, which is a liveness oracle
 * over the whole deployment dressed up as a receipt. See the delete handler.
 */
@Serializable data class OrgMemberRemovedDto(val memberId: String, val sessionsCut: Int)

/** Fresh-proof-of-presence header for the two acts that widen who may be granted. See [stepUpSatisfied]. */
private const val STEP_UP_HEADER = "X-Stepup-Code"

/**
 * The ORG / PRACTICE CONTROL PLANE: practices, their members, and those members' roles.
 *
 * Everything in this file is docs/COMPANION_ACCESS_CONTROL.md's **control plane**, and the single
 * sentence the whole design rests on is the one to hold in mind while reading it:
 *
 *   "admins live in the control and monitoring planes, never the data plane. That is how 'an admin
 *    can revoke anyone and see who-accessed-what, yet cannot read a single clinical note' is true,
 *    not marketing."
 *
 * ## What that means concretely, here, in this file
 *
 * There is **no route below that returns key material, ciphertext, or a grant** — not gated behind
 * a role, not behind step-up, not behind anything. Not because each handler carefully declines to,
 * but because nothing this file can reach has any. It does not import the relationship blob layer
 * and does not call the public-key relay; the four types it hands back are declared above and are
 * made of identifiers, role names and timestamps. An org admin here can add anyone, remove anyone,
 * re-role anyone and read every line of their practice's history, and there is no sequence of those
 * requests that ends with a sentence a patient wrote. That is what a control plane is.
 *
 * The complement matters just as much and is easier to get wrong: **membership grants nothing.**
 * Adding a clinician to a practice does not fetch anything for them, does not ask a patient
 * anything on their behalf, and does not cause this server to hand them something it was holding.
 * Read capability is a grant — a key wrapped on the patient's device for one named person — and
 * grants are minted in a place this file cannot reach by a person this file has no authority over.
 * A role is permission to *act*; a grant is permission to *read*; the spec keeps them separate and
 * so does the code.
 *
 * ## What is deliberately not here: org-consent
 *
 * The spec describes an org-consent under which a client consents once to "my care team at Practice
 * X", after which membership changes issue and revoke grants automatically. That is real and it is
 * not in this slice, and the reason is the constraint above rather than a shortage of time: issuing
 * a grant is a cryptographic act — wrapping a key for a named person — and it happens on a device
 * belonging to somebody who holds one. This plane holds none and must continue to hold none, so the
 * automatic half of org-consent belongs on the client side of that line, triggered by these changes
 * rather than performed by them. Adding a member here therefore notifies nothing and mints nothing;
 * it records that somebody is now eligible to be offered access by a person who decides that
 * themselves.
 *
 * ## A seat is an offer, and removal ends a membership rather than an access
 *
 * Two sentences that a reader of this file will otherwise supply for themselves, wrongly, and both
 * are load-bearing enough to be at the top.
 *
 * **Seating somebody is a claim, not authority over them.** An admin may add any id the charset
 * allows; the server does not audit a practice's hiring and checking the id against the credential
 * table would hand every admin on the deployment an oracle for whether a given person has an
 * account. What the server does insist is that the claim stays inert until the person accepts it
 * — `POST /members/me/accept`, from their own session — because the one destructive thing removal
 * does reaches a CREDENTIAL rather than a practice, and a practice must not acquire that reach by
 * typing a name. Without that gate, add-then-remove is a cross-tenant session kill and a liveness
 * oracle built out of two perfectly ordinary admin requests.
 *
 * **Removal ends a membership. It does not end an access.** It deletes the row, and for a member
 * who accepted, it cuts their live portal sessions. It does not disable their credential — no route
 * in this server does, and it would not be this practice's credential to disable — and it does not
 * touch a single grant, because grants are the patient's. Somebody removed at 09:00 who still holds
 * their authenticator is signed back in at 09:01, reading exactly what a patient still lets them
 * read. That is not a gap to be closed here: closing it would mean an admin with authority in the
 * data plane, which is the one thing this design says an admin never has. It is a limit to state
 * in the product, in the words the spec uses — revocation stops future access, cannot un-read what
 * was already decrypted, and the practice's half of it stops at the practice's own door.
 *
 * ## Tenancy
 *
 * Every path under `/v1/orgs/{orgId}` is authorised by looking up the caller's membership **in that
 * practice**, freshly, on that request. An admin of practice A presenting a perfectly valid session
 * at practice B is not an admin there; they are a stranger, and they are answered the way a
 * stranger is answered. There is no global administrator role and no route that operates across
 * practices, which is the spec's "not a global super-admin" expressed as the absence of a thing
 * rather than as a check.
 *
 * ## Non-enumerating, and why a stranger gets 404 rather than 403
 *
 * A caller who is not a member of the named practice is told **not found**, in the same words and
 * with the same status as a caller who named a practice that has never existed. 403 would be more
 * literally accurate and would also be an oracle: an attacker with any valid session could walk
 * practice ids and separate the real from the imaginary by reading the refusals, which is a map of
 * every clinic on the deployment. 403 is reserved for callers who ARE members and lack the
 * capability — they already know the practice exists, they are standing in it, and telling them
 * their role is insufficient reveals nothing they did not bring with them. Same distinction the
 * public-key relay draws between "authenticate again" and "your session simply does not reach here".
 *
 * ## Where the friction is, and where it deliberately is not
 *
 * From the spec's annoyance budget: adding a member and changing a role are **step-up (MFA)**,
 * because they change who *can* be granted. Removing a member is **deliberately cheap** — session
 * auth only — because it is the safe direction, and "never make the safe direction expensive" is
 * the corollary that keeps revocation something people actually reach for. A design that charged
 * the same for both would have mispriced both.
 */
fun Route.orgRoutes(
    orgStore: OrgStore,
    authStore: AuthStore,
    ownerGuard: AuthGuard,
    sessionIdleSeconds: Long,
    orgAudit: AuditStore,
    auditSourceIp: Boolean = false,
    /**
     * Per-source budget for authenticated cookie callers, built here for the same reason the
     * relationship surface and the public-key relay build their own: `Application.module` has no
     * knob for it, and the operator's `DAYMARK_RATE_LIMIT_RPS` sizes the bearer bucket, which is a
     * different resource. Sized off the same constants so the cookie surfaces do not drift apart.
     */
    orgLimiter: AttemptLimiter = AttemptLimiter(THERAPIST_MAX_PER_WINDOW, THERAPIST_WINDOW_MS),
    /** Injectable so a test can drive step-up without waiting on a real TOTP window. */
    clock: () -> Long = { System.currentTimeMillis() },
) {
    route("/v1/orgs") {

        /*
         * Create a practice and seat its first admin. The operator's provisioning credential only.
         *
         * WHOSE CREDENTIAL THIS IS, stated plainly rather than left to be discovered. This route is
         * gated exactly as POST /v1/invite is, on the deployment's bearer token. In the spec's
         * vocabulary that is the PLATFORM SYSADMIN plane: whoever runs the server, who by the role
         * catalog reads no clinical content by design. In this build the same token is also the
         * owner's own data-plane credential, because this is a single-owner self-hosted deployment
         * and there is exactly one of them. That conflation is inherited from the existing
         * deployment model, not introduced here, and it is worth naming so nobody later reads this
         * route as evidence that a practice administrator is also a reader: nothing in this file
         * gives the bearer token any clinical read, and what it already had, it already had.
         *
         * The practice id is MINTED here rather than accepted from the body. A caller-chosen id
         * would let whoever holds the token pick an identifier that collides with something else's,
         * and the identifier is what a whole audit chain is keyed on. It costs nothing to generate
         * one and it closes the question permanently.
         *
         * The first admin is seated in the same transaction as the practice, because a practice with
         * no admin cannot be given one — adding members is the thing admins do — so an empty
         * practice would be a permanently stuck row.
         */
        post {
            if (!call.ownerAuthorized(ownerGuard)) return@post
            val req = call.receiveCappedJson<CreateOrgRequest>() ?: return@post
            val orgId = Secrets.newToken(16)
            when (val outcome = orgStore.createOrg(orgId, req.name, req.adminMemberId, PROVISIONER)) {
                OrgWrite.OK -> {
                    val created = orgStore.org(orgId)
                    call.respond(HttpStatusCode.Created, OrgDto(orgId, req.name, created?.createdAt ?: 0L))
                    // Respond first, audit second: the practice has committed, and a logging failure
                    // must never turn a created practice into an error the operator retries — where
                    // the retry would mint a SECOND practice. Same ordering as the enrol path.
                    auditSafely {
                        orgAudit.append(
                            orgId, AuditActor.PLATFORM, AuditAction.ORG_CREATED,
                            objectRef = req.adminMemberId,
                            meta = orgMeta(auditSourceIp, call, "role" to OrgRole.ORG_ADMIN.wire),
                        )
                    }
                }
                else -> call.respondOrgWrite(outcome)
            }
        }

        route("/{orgId}") {

            // The roster. Any member of the practice may read it; nobody outside it can tell the
            // practice exists. What comes back is membership metadata and nothing else — see
            // OrgMemberDto for the list of fields and why the list IS the point.
            //
            // There is deliberately no companion route listing the practice's PATIENTS. A patient is
            // not owned by the org, so "this practice's patients" is not a set this server is
            // entitled to assemble; a roster that quietly grew one would have turned an addressing
            // convenience into a register of who is in therapy where, which is exactly the metadata
            // leak the honest-limits section asks be minimised rather than manufactured.
            get("/members") {
                val actor = resolveActor(orgStore, authStore, sessionIdleSeconds, orgLimiter, requireCsrf = false) ?: return@get
                if (!call.requireCapability(actor, OrgAction.VIEW_ROSTER, orgAudit, auditSourceIp)) return@get
                val members = orgStore.roster(actor.orgId).map { it.toDto() }
                call.respond(OrgRosterDto(actor.orgId, members))
            }

            /*
             * Seat somebody in the practice. Membership authority AND step-up.
             *
             * Step-up is charged here because this is one of the two acts in the file that changes
             * who may be *offered* read capability, and the annoyance budget puts friction exactly
             * there. The threat it answers is narrow and real: a session in the wrong hands should
             * not be able to quietly add readers to a practice, one at a time, over an afternoon. A
             * fresh code proves somebody is present now, not that somebody was present once.
             *
             * What it is NOT is a second authorisation. The membership check above already decided
             * whether this caller may manage membership; step-up decides whether this REQUEST is
             * really theirs. Which is why a failed step-up is a refusal of the request and never a
             * penalty against the credential — see stepUpSatisfied.
             */
            post("/members") {
                val actor = resolveActor(orgStore, authStore, sessionIdleSeconds, orgLimiter, requireCsrf = true) ?: return@post
                if (!call.requireCapability(actor, OrgAction.MANAGE_MEMBERSHIP, orgAudit, auditSourceIp)) return@post
                val req = call.receiveCappedJson<AddMemberRequest>() ?: return@post
                val role = OrgRole.fromWire(req.role)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("unknown role"))
                // Step-up is spent LAST, after the request is known to be well formed. A single-use
                // code burned on a mistyped role name is thirty seconds of waiting for a mistake
                // the server could see was a mistake — "charge per decision, not per action", and a
                // malformed body is not a decision. Nothing is risked by parsing first: the caller
                // is already an authenticated member of this practice with membership authority,
                // and the body was capped before it was read.
                if (!call.requireStepUp(actor, authStore, clock, orgAudit, auditSourceIp)) return@post
                when (val outcome = orgStore.addMember(actor.orgId, req.memberId, role, actor.membership.memberId)) {
                    OrgWrite.OK -> {
                        val seated = orgStore.membership(actor.orgId, req.memberId)
                        // accepted=false is not a default here, it is the answer: a seat the person
                        // has not taken yet is exactly what was just created, and saying so on the
                        // way out is what stops a client rendering an offer as a colleague.
                        call.respond(
                            HttpStatusCode.Created,
                            OrgMemberDto(req.memberId, role.wire, seated?.addedAt ?: 0L, seated?.accepted ?: false),
                        )
                        auditSafely {
                            orgAudit.append(
                                actor.orgId, AuditActor.ORG_ADMIN, AuditAction.ORG_MEMBER_ADDED,
                                objectRef = req.memberId,
                                meta = orgMeta(auditSourceIp, call, "actor" to actor.membership.memberId, "role" to role.wire),
                            )
                        }
                    }
                    else -> call.respondOrgWrite(outcome)
                }
            }

            /*
             * The seated person says yes. THEIR OWN seat, and nobody else's, ever.
             *
             * WHY THIS ROUTE EXISTS AT ALL, since a practice can plainly hire whoever it likes
             * without the server's permission. It is not about hiring. Removal cuts the member's
             * portal sessions, and a portal session belongs to a CREDENTIAL — one a patient's
             * invitation created, on a relationship this practice has nothing to do with. So
             * "add, then remove" is a pair of ordinary admin acts whose combined effect lands on a
             * credential the practice was never given authority over, and an admin who typed a
             * clinician of another practice into their own roster could end that person's working
             * session mid-appointment, watch the response to learn whether they had been signed in
             * at all, and leave no trace anywhere the other practice can see. Acceptance is the
             * fact that closes it: the practice acquires standing over a person when the person
             * says so, and until then a seat is an offer with nothing hanging off it.
             *
             * The subject is taken from the SESSION and there is no parameter for it. An admin
             * cannot accept on somebody's behalf, cannot accept in bulk, and cannot un-accept —
             * there is no route, no flag and no store method that does any of those, which is a
             * stronger statement than a check would be because there is nothing to bypass.
             *
             * NO STEP-UP, deliberately. Accepting confers nothing on the accepter and creates no
             * read capability for anyone; what it does is expose the accepter to their own
             * practice's revocation, which is the safe direction and the one the annoyance budget
             * says never to make expensive. CSRF is still required, and it is the load-bearing gate
             * here: a cross-site page must not be able to accept a seat on a member's behalf simply
             * because their browser attached a cookie.
             *
             * Idempotent, and quiet about it: a second accept is an OK with no second audit line.
             * A client that retries on a flaky connection must not be able to write the practice's
             * chain full of the same event.
             */
            post("/members/me/accept") {
                val actor = resolveActor(orgStore, authStore, sessionIdleSeconds, orgLimiter, requireCsrf = true) ?: return@post
                val me = actor.membership
                if (me.accepted) return@post call.respond(me.toDto())
                when (val outcome = orgStore.acceptMembership(actor.orgId, me.memberId)) {
                    OrgWrite.OK -> {
                        call.respond(OrgMemberDto(me.memberId, me.role.wire, me.addedAt, accepted = true))
                        auditSafely {
                            orgAudit.append(
                                actor.orgId, AuditActor.ORG_MEMBER, AuditAction.ORG_MEMBER_ACCEPTED,
                                objectRef = me.memberId,
                                meta = orgMeta(auditSourceIp, call, "role" to me.role.wire),
                            )
                        }
                    }
                    else -> call.respondOrgWrite(outcome)
                }
            }

            // Change one member's role, within this practice. Membership authority AND step-up —
            // a promotion widens what somebody may do, which is the same kind of decision as an
            // addition and priced the same way. Demoting the practice's last admin is refused; see
            // OrgWrite.LAST_ADMIN for why that is a kindness rather than an obstruction.
            post("/members/{memberId}/role") {
                val actor = resolveActor(orgStore, authStore, sessionIdleSeconds, orgLimiter, requireCsrf = true) ?: return@post
                if (!call.requireCapability(actor, OrgAction.MANAGE_MEMBERSHIP, orgAudit, auditSourceIp)) return@post
                val memberId = call.parameters["memberId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("missing member"))
                val req = call.receiveCappedJson<ChangeRoleRequest>() ?: return@post
                val role = OrgRole.fromWire(req.role)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("unknown role"))
                // Spent last, once the request is known to be well formed — see the add route above.
                if (!call.requireStepUp(actor, authStore, clock, orgAudit, auditSourceIp)) return@post
                // Read the outgoing role BEFORE the write, or the audit entry can only say where the
                // person ended up. "Promoted to admin" and "was already admin" are different facts
                // and the log is the only place either is ever recoverable.
                val previous = orgStore.membership(actor.orgId, memberId)?.role
                when (val outcome = orgStore.changeRole(actor.orgId, memberId, role)) {
                    OrgWrite.OK -> {
                        val reRoled = orgStore.membership(actor.orgId, memberId)
                        call.respond(OrgMemberDto(memberId, role.wire, reRoled?.addedAt ?: 0L, reRoled?.accepted ?: false))
                        auditSafely {
                            orgAudit.append(
                                actor.orgId, AuditActor.ORG_ADMIN, AuditAction.ORG_MEMBER_ROLE_CHANGED,
                                objectRef = memberId,
                                meta = orgMeta(
                                    auditSourceIp, call,
                                    "actor" to actor.membership.memberId,
                                    "from" to (previous?.wire ?: "unknown"),
                                    "to" to role.wire,
                                ),
                            )
                        }
                    }
                    else -> call.respondOrgWrite(outcome)
                }
            }

            /*
             * Remove somebody from the practice. Membership authority, a session, and NO step-up.
             *
             * The missing step-up is the deliberate part. Every instinct says the destructive
             * operation should cost the most, and the annoyance budget says the opposite in as many
             * words: revocation and kill switches are "deliberately cheap", and "never make the safe
             * direction expensive" — revoking, narrowing and turning off must always be easier than
             * granting, widening and turning on, because the asymmetry IS the control. An admin who
             * has just learned that a colleague's laptop is gone should be able to cut them off in
             * one click, on whatever device is to hand, without hunting for an authenticator. The
             * failure mode of a cheap revocation is a member who has to be re-added — which costs
             * step-up, so the round trip is still net-expensive in the widening direction. The
             * failure mode of an expensive one is somebody deciding to deal with it in the morning.
             *
             * WHAT HAPPENS. The membership row is deleted, so the very next request they make is
             * answered as a stranger's — nothing caches a role, so there is no window. And if the
             * person had ACCEPTED this seat, every live portal session that credential holds is
             * deleted too, because otherwise "removed" would have meant "removed within the next
             * eight hours" and a revocation you have to wait out is not one people trust.
             *
             * WHY THE SESSION CUT IS GATED ON ACCEPTANCE, which is the part that looks like an
             * inconsistency and is the opposite. A portal session belongs to a CREDENTIAL, not to a
             * practice, and the credential was created by a patient's invitation on a relationship
             * this practice has nothing to do with. An admin may seat any id their charset allows —
             * they must, since the server does not get a vote in who a practice says works there —
             * so if removal cut sessions unconditionally then "add, then remove" would be a pair of
             * ordinary admin acts whose combined effect lands on any credential id an admin can
             * type. A clinician of another practice would be signed out mid-appointment, their own
             * practice's chain would record nothing, and the response would say whether they had
             * been signed in at all. Acceptance is what turns a claim about somebody into standing
             * over them, and the cut waits for it. An unaccepted seat is withdrawn silently and
             * cuts nothing, and the count says zero because zero is what happened.
             *
             * THE SIDE EFFECT THAT DOES CROSS A PRACTICE, said plainly rather than left to be
             * found. For somebody who accepted seats at two practices and is removed from one, both
             * sessions go: they are one credential's. Their standing at the other is untouched —
             * still a member, same role, signing in again restores everything — so what crosses the
             * boundary is an interruption, never authority, and it crosses only for a person who
             * agreed to both practices. The alternative was leaving a session alive that the
             * removing practice had every reason to believe it had cut.
             *
             * WHAT DOES NOT HAPPEN, and cannot from here. No key is rotated and no ciphertext is
             * touched, because the control plane has never held either. And — the part most likely
             * to be misread as more than it is — THE PERSON'S ACCESS IS NOT ENDED. Their credential
             * is not disabled: there is no route in this server that disables one, and it would not
             * be this practice's to disable if there were, because a patient's invitation created
             * it. Nothing here touches any grant, because grants are the patient's. So a removed
             * clinician who still holds their authenticator signs in again within the minute and
             * reads exactly what they read yesterday, and the only person who can change that is
             * the patient, by withdrawing the share. What this route ends is a MEMBERSHIP. The spec
             * is explicit that the cryptographic cutoff is the patient's device's act, and equally
             * explicit about the honest limit to state in-product: revocation stops FUTURE access
             * and cannot un-read what somebody already decrypted. An admin who believes this button
             * did more than it did will make a worse decision than one who knows — which is why the
             * product copy for it must say "removed from the practice" and never "access revoked".
             */
            delete("/members/{memberId}") {
                val actor = resolveActor(orgStore, authStore, sessionIdleSeconds, orgLimiter, requireCsrf = true) ?: return@delete
                if (!call.requireCapability(actor, OrgAction.MANAGE_MEMBERSHIP, orgAudit, auditSourceIp)) return@delete
                val memberId = call.parameters["memberId"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorDto("missing member"))
                // Read before the delete, because the row is what says whether this practice has
                // standing over the person, and after the DELETE there is nothing left to ask.
                val seat = orgStore.membership(actor.orgId, memberId)
                when (val outcome = orgStore.removeMember(actor.orgId, memberId)) {
                    OrgWrite.OK -> {
                        // Sessions go after the row, not before. If the process died between the
                        // two, the surviving state is "no longer a member, still holds a stale
                        // session" — which the control plane already refuses on every request,
                        // because membership is looked up fresh. The other ordering would leave
                        // "logged out but still a member", which looks like revocation and is not.
                        val cut = if (seat?.accepted == true) authStore.revokeSessionsForCredential(memberId) else 0
                        call.respond(OrgMemberRemovedDto(memberId, cut))
                        auditSafely {
                            orgAudit.append(
                                actor.orgId, AuditActor.ORG_ADMIN, AuditAction.ORG_MEMBER_REMOVED,
                                objectRef = memberId,
                                meta = orgMeta(
                                    auditSourceIp, call,
                                    "actor" to actor.membership.memberId,
                                    "sessionsCut" to cut.toString(),
                                ),
                            )
                        }
                    }
                    else -> call.respondOrgWrite(outcome)
                }
            }

            /*
             * The practice's own control-plane history. Admin only — "audit review" is in their row
             * of the role catalog and nobody else's.
             *
             * This reads a DIFFERENT database from the relationship audit log, and the separation is
             * the point rather than an implementation detail. A practice's chain is keyed on the
             * practice id; a patient's chain is keyed on their relationship reference; both are
             * opaque tokens from the same alphabet, so one shared table would be one collision away
             * from an admin's review paging into a patient's access history. Two files makes it
             * unreachable instead of unlikely.
             *
             * Metadata only, like every chain in this system: who was added, removed and re-roled,
             * by whom, when. Never content, and there is no content within reach of the store this
             * reads from.
             */
            get("/audit") {
                val actor = resolveActor(orgStore, authStore, sessionIdleSeconds, orgLimiter, requireCsrf = false) ?: return@get
                if (!call.requireCapability(actor, OrgAction.REVIEW_ORG_AUDIT, orgAudit, auditSourceIp)) return@get
                val before = call.request.queryParameters["before"]?.toLongOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val cap = limit.coerceIn(1, AuditStore.MAX_PAGE_SIZE)
                val events = orgAudit.list(actor.orgId, before, cap)
                val next = if (events.size >= cap) events.lastOrNull()?.seq else null
                call.respond(
                    AuditLogPage(
                        events.map { AuditEventDto(it.seq, it.ts, it.actor, it.action, it.objectRef, it.meta, it.entryHash) },
                        next,
                    ),
                )
            }
        }
    }
}

/** Recorded as the seating authority for a practice's first admin. Not a member of anything. */
private const val PROVISIONER = "platform"

private fun Membership.toDto() = OrgMemberDto(memberId, role.wire, addedAt, accepted)

/**
 * The caller, resolved as a member of the practice named in the path — or nothing.
 *
 * [orgId] is the practice from the PATH, and it is the only practice any handler downstream will
 * touch, because [membership] was looked up against it. There is no way for a handler to end up
 * operating on a different practice than the one the caller was authorised in: the two come from
 * one lookup and are carried together.
 */
private data class OrgActor(val orgId: String, val membership: Membership)

/**
 * Session, then budget, then membership — and every one of those orderings is deliberate.
 *
 * The session check comes first because it is one hashed SELECT and everything behind it is more
 * expensive. The rate limit is charged AFTER it for the same reason the public-key relay charges
 * after its own: only a caller who already holds a real session can spend it, so an anonymous flood
 * cannot burn a working clinician's allowance from a shared address and lock them out of their own
 * practice. Membership is looked up last, freshly, on every request — never cached in the session —
 * which is precisely what makes a removal effective on the removed member's next request instead of
 * whenever their session happened to lapse.
 *
 * A caller who is not a member gets 404, in the same words a nonexistent practice gets. See the file
 * header for why that is not politeness but the absence of an enumeration oracle.
 */
private suspend fun RoutingContext.resolveActor(
    orgStore: OrgStore,
    authStore: AuthStore,
    sessionIdleSeconds: Long,
    orgLimiter: AttemptLimiter,
    requireCsrf: Boolean,
): OrgActor? {
    val sessionId = call.request.cookies["daymark_session"] ?: run {
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); return null
    }
    // On a state-changing path the anti-CSRF token MUST be present. A missing header is a rejection,
    // not a bypass — a null header must never validate against a null stored token. A browser
    // attaches the session cookie to a cross-site POST on its own, so without this any page a
    // clinician visited could re-role their colleagues.
    val csrf = if (requireCsrf) {
        call.request.headers["X-CSRF-Token"] ?: run {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); return null
        }
    } else {
        null
    }
    val validation = authStore.validateSession(sessionId, sessionIdleSeconds, requireCsrf = csrf)
    val session = validation.record
    if (validation.check != AuthStore.SessionCheck.OK || session == null) {
        // Non-enumerating, like the rest of this surface: expired, revoked, never-existed and
        // wrong-CSRF are one 401 between them.
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")); return null
    }
    if (!orgLimiter.allow(call.clientAddress())) {
        call.respond(HttpStatusCode.TooManyRequests, ErrorDto("rate limited")); return null
    }
    val orgId = call.parameters["orgId"] ?: run {
        call.respond(HttpStatusCode.NotFound, ErrorDto("not found")); return null
    }
    // The acting person IS their portal credential id. That is the only server-side identity a
    // clinician has, and taking it from the SESSION rather than from the request is what stops a
    // caller nominating whose authority they are acting under.
    val membership = orgStore.membership(orgId, session.credentialId) ?: run {
        call.respond(HttpStatusCode.NotFound, ErrorDto("not found")); return null
    }
    return OrgActor(orgId, membership)
}

/**
 * Does this member's role carry this action? 403 and an audit line if not.
 *
 * The refusal is recorded because it is the more informative half: successful membership changes are
 * routine, while a front-desk account repeatedly attempting them is either a broken client or
 * somebody finding the wall, and the server cannot tell those apart. It refuses both identically and
 * writes the line so a person can ask.
 *
 * Recorded against the practice ONLY because the caller is already established as a member of it —
 * [resolveActor] returned, so the membership is real. A stranger's attempt is never audited, since
 * the practice id on that request is an unverified path parameter and appending for it would let
 * anyone holding any session seed another practice's chain with entries of their choosing.
 */
private suspend fun ApplicationCall.requireCapability(
    actor: OrgActor,
    action: OrgAction,
    orgAudit: AuditStore,
    auditSourceIp: Boolean,
): Boolean {
    if (actor.membership.role.can(action)) return true
    respond(HttpStatusCode.Forbidden, ErrorDto("insufficient role"))
    auditSafely {
        orgAudit.append(
            actor.orgId, AuditActor.ORG_MEMBER, AuditAction.ORG_ACTION_DENIED,
            objectRef = action.name,
            meta = orgMeta(
                auditSourceIp, this,
                "actor" to actor.membership.memberId,
                "role" to actor.membership.role.wire,
            ),
        )
    }
    return false
}

/** 403 and an audit line when a widening act arrives without fresh proof of presence. */
private suspend fun ApplicationCall.requireStepUp(
    actor: OrgActor,
    authStore: AuthStore,
    clock: () -> Long,
    orgAudit: AuditStore,
    auditSourceIp: Boolean,
): Boolean {
    if (stepUpSatisfied(authStore, this, actor.membership.memberId, clock())) return true
    respond(HttpStatusCode.Forbidden, ErrorDto("step-up required"))
    auditSafely {
        orgAudit.append(
            actor.orgId, AuditActor.ORG_MEMBER, AuditAction.ORG_ACTION_DENIED,
            objectRef = "step_up",
            meta = orgMeta(
                auditSourceIp, this,
                "actor" to actor.membership.memberId,
                "role" to actor.membership.role.wire,
            ),
        )
    }
    return false
}

/**
 * Fresh proof of presence: a currently-valid, previously-unspent TOTP code from the acting member.
 *
 * Fail-closed in every direction — a missing header, a member with no enrolled authenticator, an
 * undecodable seed, a wrong code and a REPLAYED code are one false between them. The replay case is
 * the one worth naming: the step is consumed through the same atomic compare-and-set the sign-in
 * path uses, so a code observed over somebody's shoulder cannot be used to add a second member
 * inside the ninety seconds it stays otherwise valid. A step-up that could be replayed would be
 * proof that somebody was present once, which is exactly the property it exists to improve on.
 *
 * WHAT IS DELIBERATELY NOT DONE: a failure here does NOT drive the per-credential lockout that a
 * failed sign-in does. It looks like an omission and is a decision. Reaching this line at all
 * requires an authenticated session belonging to a member of the practice with membership authority
 * — so the only party who can spend that budget is the admin themselves, or somebody who has
 * already taken their session and has better options than annoying them. Meanwhile locking the
 * counter would lock them out of SIGNING IN, and signing in is the prerequisite for the cheap
 * revocation this file is careful to keep cheap. Making the safe direction unreachable by mistyping
 * a code in the expensive one gets the asymmetry exactly backwards. Request volume is metered by the
 * per-source budget already spent in resolveActor, which is the right tool for the shape of abuse
 * that is actually reachable here.
 */
private fun stepUpSatisfied(authStore: AuthStore, call: ApplicationCall, memberId: String, nowMs: Long): Boolean {
    val presented = call.request.headers[STEP_UP_HEADER]?.trim()?.ifBlank { null } ?: return false
    val credential = authStore.getTotp(memberId) ?: return false
    val seed = decodeStepUpSeed(credential.secretB64) ?: return false
    val step = Totp.verifyStep(seed, presented, nowMs / 1000) ?: return false
    return authStore.consumeTotpStep(memberId, step)
}

/** Accept a seed encoded as base64url (no pad), base64, or raw utf-8 — matching how enrolment stored it. */
private fun decodeStepUpSeed(s: String): ByteArray? {
    runCatching { return Base64.getUrlDecoder().decode(s) }
    runCatching { return Base64.getDecoder().decode(s) }
    return s.toByteArray(Charsets.UTF_8).takeIf { it.size >= 16 }
}

/**
 * Small fixed non-content annotations for a control-plane audit entry.
 *
 * Member ids and role names, which are membership metadata — the thing the spec says a
 * multi-tenant-but-blind server legitimately sees — plus the source IP only when the operator opted
 * in. Never a practice name, never anything a person wrote, never anything sealed. The audit log is
 * a record of what happened, never of what was in it.
 */
private fun orgMeta(sourceIpEnabled: Boolean, call: ApplicationCall, vararg extra: Pair<String, String>): Map<String, String> {
    val meta = extra.toMap().toMutableMap()
    if (sourceIpEnabled) meta["sourceIp"] = call.clientAddress()
    return meta
}

/**
 * Map a refused control-plane write onto a status, non-enumerating where it has to be.
 *
 * NO_SUCH_ORG and NOT_A_MEMBER both answer 404 with identical wording, and the collapse is on
 * purpose: they are the two ways a caller can name somebody who is not in front of them, and
 * telling them apart would say whether a given person is at a given practice — which is a fact about
 * somebody's employment that no caller earned by guessing an id.
 */
private suspend fun ApplicationCall.respondOrgWrite(outcome: OrgWrite) {
    val (status, message) = when (outcome) {
        // Never reached: OK is handled at every call site. Mapped anyway so the `when` is exhaustive
        // without an else that would swallow a genuinely new value added later.
        OrgWrite.OK -> HttpStatusCode.OK to "ok"
        OrgWrite.BAD_NAME -> HttpStatusCode.BadRequest to "invalid request"
        OrgWrite.NO_SUCH_ORG -> HttpStatusCode.NotFound to "not found"
        OrgWrite.NOT_A_MEMBER -> HttpStatusCode.NotFound to "not found"
        OrgWrite.ALREADY_EXISTS -> HttpStatusCode.Conflict to "already a member"
        OrgWrite.LAST_ADMIN -> HttpStatusCode.Conflict to "the practice must keep an admin"
    }
    respond(status, ErrorDto(message))
}
