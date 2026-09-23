package com.daymark.companion.routes

import com.daymark.companion.auth.AuthGuard
import com.daymark.companion.storage.AuditStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * The wire shape of one chain check: AuditStore.ChainVerification, field for field.
 *
 * Note what is NOT here: no entries, no actors, no actions, no timestamps, no verdict word. The
 * response is counts, sequence extents and one hash, so nothing in it quotes what the log records
 * — the same property the store's own result type has, kept rather than decorated. The absent
 * optionals are omitted from the JSON entirely (explicitNulls=false in Application.module), so a
 * clean chain simply has no `firstBreakSeq` field rather than a null one.
 */
@Serializable
data class ChainVerificationDto(
    val entryCount: Long,
    val oldestSeq: Long? = null,
    val headSeq: Long? = null,
    val headHash: String? = null,
    val firstBreakSeq: Long? = null,
)

/**
 * The store accepts references in exactly this shape and throws on anything else. The route checks
 * first so that a malformed path segment is a request refused, never an exception turned into a
 * generic 500 that reads like the server failed at its own arithmetic.
 */
private val CHAIN_REL_REF = Regex("^[A-Za-z0-9_-]{1,64}$")

/**
 * The audit chain's own check: recompute what is stored and hand back the head.
 *
 * WHAT THIS CLOSES. AuditStore has appended a hash chain since the day it existed and nothing
 * anywhere ever checked one — the admin console states that gap about this build in as many words
 * (companion/web/src/lib/admin/health.ts), and task #16 has been open on it. This route calls
 * [AuditStore.verifyChain] for ONE relationship and returns the summary: how many entries stand,
 * the sequence extent, the head hash as stored, and where the first internal break sits if there
 * is one.
 *
 * WHY IT IS GATED ON THE OWNER BEARER TOKEN, exactly as GET /v1/relations/{relRef}/therapist-keys
 * is — the same [ownerAuthorized], not a lookalike, so the two cannot drift apart. It is tempting
 * to serve this unauthenticated, because a chain head opens nothing and reads as harmless. It is
 * not harmless: a head plus an entry count, served per relRef to whoever asks, answers "does this
 * relationship exist on this server" and "how active has it been" for any reference an anonymous
 * caller cares to probe — which is precisely the metadata a compromised or nosy observer is
 * documented as being able to take (docs/COMPANION_ARCHITECTURE.md §6, "Metadata is visible": how
 * many relationships exist, how active each one is), and this server does not volunteer it to
 * callers who present nothing. The bearer token is the difference between the owner checking
 * their own log's spine and an open directory of who has a therapist and how often they talk.
 *
 * Why the owner token ALONE, without the X-Rel-Token second factor the audit-entry read next door
 * demands: what that second factor protects on the entry read is the log's CONTENT — who did what,
 * when. Nothing in this response carries content; it is one hash and three numbers about a chain
 * the owner's token already establishes standing over. This mirrors the reasoning on the
 * therapist-keys GET, which is the gate this route is specified to copy.
 *
 * WHY THE CHECK DOES NOT WRITE AN AUDIT ENTRY, which every other read on this surface does. Two
 * reasons, either sufficient alone. First: an entry appended here would extend the very chain
 * just checked, so every look would move the head — and the head's entire value is that a person
 * can write it down today and compare it tomorrow; a check that changed the thing it reported
 * would make itself useless on the first use. Second: relRef here is a raw path parameter, and
 * appending for whatever arrives in it would let any owner-token holder seed the audit store with
 * rows keyed on relationships that do not exist — the same reasoning that keeps a missed
 * therapist-key read out of the log. A verification is a question ABOUT the record, not an event
 * IN the relationship, and the record stays exactly as found.
 *
 * WHAT A 200 HERE IS NOT. The server recomputed its own chain and is reporting on itself. Against
 * an honest server with damaged storage the break report is real evidence; against a hostile one
 * it is nothing, because whoever can rewrite the entries can recompute the chain over them and
 * whoever writes this response can write it clean. The response is therefore worded as data, not
 * verdicts — the client side is responsible for saying, next to whatever it renders, that internal
 * consistency is not completeness and that a server that declines to append, or truncates,
 * verifies perfectly (docs/COMPANION_SECURITY.md §9, R12). What outlives a lying server is the
 * head hash once it is anchored beyond the server's reach — a person's note now, the phone's own
 * copy later (docs/COMPANION_ARCHITECTURE.md §6; not built: #182). This route exists to hand that
 * value over.
 *
 * NOTHING HERE LOGS. Not the relRef, not the head, not the outcome. The server is a zero-knowledge
 * relay and a chain check is not an incident.
 */
fun Route.auditChainRoutes(store: AuditStore, ownerGuard: AuthGuard) {
    route("/v1/relations/{relRef}/audit-chain") {
        get {
            // The guard first, and it is the whole gate: rate limiting, lockout and the token
            // check all live in AuthGuard, shared with every other owner-token surface. The
            // recompute below is O(entries) of SHA-256, so it is deliberately behind the same
            // budget as every other thing an owner token buys.
            if (!call.ownerAuthorized(ownerGuard)) return@get
            val relRef = call.parameters["relRef"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("missing relRef"))
            if (!CHAIN_REL_REF.matches(relRef)) {
                return@get call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid relRef"))
            }
            // A reference the store has never seen comes back as zero entries with no head —
            // deliberately the same answer as a real but quiet relationship, so this route cannot
            // be used by an owner-token holder to enumerate which well-formed references are real
            // beyond what the count they are entitled to already tells them.
            val v = store.verifyChain(relRef)
            call.respond(
                ChainVerificationDto(
                    entryCount = v.entryCount,
                    oldestSeq = v.oldestSeq,
                    headSeq = v.headSeq,
                    headHash = v.headHash,
                    firstBreakSeq = v.firstBreakSeq,
                ),
            )
        }
    }
}
