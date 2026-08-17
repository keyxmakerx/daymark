package com.daymark.companion.auth

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Only a person may destroy an invitation.
 *
 * The pairing design briefly said that a refused confirmation should burn the invite, and that was
 * wrong in a way worth pinning down with tests rather than a comment. With a password-authenticated
 * exchange a wrong code and an attacker's guess are the SAME EVENT — indistinguishable by
 * construction, which is the point of the construction — so "burn on a failed attempt" hands anyone
 * who has seen the invite link a cheap, repeatable veto over the product's core flow: the owner
 * mints, the attacker types one wrong character, the invitation dies, and round again. The
 * therapist never gets in. It is just as hostile to the honest case, where someone mistypes a code
 * read to them over the phone and loses their invitation for it.
 *
 * `redeemInvite` has always had this right, and its comment says so. These tests exist so that it
 * cannot quietly stop being right: the first one is a regression guard on behaviour that is already
 * correct, which is the only kind of test that would have caught the change being proposed.
 *
 * The second half covers what replaces it. A human report is unambiguous in a way a failed attempt
 * never is, so that — and only that — reaches the terminal state.
 */
class InviteBurnRuleTest {

    private var now = 1_000_000L
    private fun store() = AuthStore(Files.createTempDirectory("invite-burn").toString(), clock = { now })

    private val fails = 3
    private val base = 60_000L

    @Test
    fun `repeated wrong secrets never consume the invite`() {
        val auth = store()
        val minted = auth.mintInvite("relA", listOf("read.share"), ttlSeconds = 86_400L)

        // Forty wrong guesses, spread across enough expired lockouts to be a sustained campaign
        // rather than one burst. If any rule anywhere burns on failure, this loop finds it.
        repeat(40) {
            val r = auth.redeemInvite(minted.inviteId, "wrong-$it", fails, base)
            assertNotEquals(
                AuthStore.RedeemStatus.GONE,
                r.status,
                "a wrong secret must never make the invite unavailable — that is the denial of service",
            )
            now += base * 2 // sit out whatever lockout that attempt armed
        }

        assertEquals("PENDING", auth.inviteStatusFor(minted.inviteId), "still waiting for its therapist")
        assertEquals(
            AuthStore.RedeemStatus.OK,
            auth.redeemInvite(minted.inviteId, minted.secret, fails, base).status,
            "and the real therapist can still enrol, which is the property the owner cares about",
        )
        auth.close()
    }

    @Test
    fun `wrong secrets on the report route do not consume the invite either`() {
        // The report route verifies the same secret, so it would be the obvious second place for a
        // burn-on-failure rule to appear. It must behave exactly like redeem does.
        val auth = store()
        val minted = auth.mintInvite("relA", listOf("read.share"), ttlSeconds = 86_400L)

        repeat(10) {
            assertNotEquals(
                AuthStore.ReportStatus.OK,
                auth.reportInvite(minted.inviteId, "wrong-$it", fails, base).status,
            )
            now += base * 2
        }

        assertEquals("PENDING", auth.inviteStatusFor(minted.inviteId))
        assertEquals(AuthStore.RedeemStatus.OK, auth.redeemInvite(minted.inviteId, minted.secret, fails, base).status)
        auth.close()
    }

    @Test
    fun `a wrong secret on the report route still pays the capped backoff`() {
        // Refusing to burn must not turn this route into a free guessing oracle: it shares the
        // per-invite counter with redeem, so attempts spent here are attempts not available there.
        val auth = store()
        val minted = auth.mintInvite("relA", listOf("read.share"), ttlSeconds = 86_400L)

        repeat(fails) {
            assertEquals(AuthStore.ReportStatus.WRONG_SECRET, auth.reportInvite(minted.inviteId, "nope", fails, base).status)
        }
        assertEquals(AuthStore.ReportStatus.LOCKED, auth.reportInvite(minted.inviteId, "nope", fails, base).status)
        assertEquals(
            AuthStore.RedeemStatus.LOCKED,
            auth.redeemInvite(minted.inviteId, minted.secret, fails, base).status,
            "one counter between the two routes, so an attacker cannot alternate to double their budget",
        )
        auth.close()
    }

    @Test
    fun `an owner report kills the invite at once`() {
        val auth = store()
        val minted = auth.mintInvite("relA", listOf("read.share"), ttlSeconds = 86_400L)

        val reported = auth.reportInviteByOwner(minted.inviteId)
        assertEquals(AuthStore.ReportStatus.OK, reported.status)
        assertEquals("relA", reported.relRef, "the caller gets the relRef back so the report can be audited")

        assertEquals("REPORTED", auth.inviteStatusFor(minted.inviteId))
        assertEquals(
            AuthStore.RedeemStatus.GONE,
            auth.redeemInvite(minted.inviteId, minted.secret, fails, base).status,
            "even the correct secret is refused — a report is immediate and total",
        )
        assertEquals(AuthStore.ReportStatus.GONE, auth.reportInviteByOwner(minted.inviteId).status, "and terminal")
        auth.close()
    }

    @Test
    fun `the invited party can report with the correct secret`() {
        // Requiring the right secret is not the denial of service this avoids: whoever can pass
        // that gate could have redeemed the invite and become the therapist instead, so letting
        // them close it takes nothing from anyone that was not already gone.
        val auth = store()
        val minted = auth.mintInvite("relA", listOf("read.share"), ttlSeconds = 86_400L)

        assertEquals(AuthStore.ReportStatus.OK, auth.reportInvite(minted.inviteId, minted.secret, fails, base).status)
        assertEquals("REPORTED", auth.inviteStatusFor(minted.inviteId))
        assertEquals(AuthStore.RedeemStatus.GONE, auth.redeemInvite(minted.inviteId, minted.secret, fails, base).status)
        auth.close()
    }

    @Test
    fun `a report takes the outstanding enrollment ticket with it`() {
        // The case a report is FOR: somebody else got there first and is mid-enrolment. Once an
        // invite has been redeemed it is the TICKET, not the invite row, that still mints a
        // credential — so a report that left the ticket alive would be theatre.
        val auth = store()
        val minted = auth.mintInvite("relA", listOf("read.share"), ttlSeconds = 86_400L)
        val redeemed = auth.redeemInvite(minted.inviteId, minted.secret, fails, base)
        assertEquals(AuthStore.RedeemStatus.OK, redeemed.status)
        assertEquals("REDEEMING", auth.inviteStatusFor(minted.inviteId))
        assertEquals(1, auth.enrollTicketCountFor(minted.inviteId))

        assertEquals(
            AuthStore.ReportStatus.OK,
            auth.reportInviteByOwner(minted.inviteId).status,
            "REDEEMING is reportable — that is exactly the state the therapist rings about",
        )
        assertEquals(0, auth.enrollTicketCountFor(minted.inviteId))
        assertEquals(
            AuthStore.EnrollStatus.NO_TICKET,
            auth.enrollTotp(redeemed.enrollTicket!!, "attacker-cred", Secrets.b64url(ByteArray(20) { 7 })).status,
            "the in-flight enrolment dies with the invite",
        )
        auth.close()
    }

    @Test
    fun `REPORTED is its own terminal state, not CONSUMED and not EXPIRED`() {
        // Those two say the invite did its job or ran out of time; this one says a person judged it
        // hostile. Collapsing them would throw away the only part of an invite's history worth
        // reading back.
        val consumedStore = store()
        val consumed = consumedStore.mintInvite("relA", listOf("read.share"), ttlSeconds = 86_400L)
        val ticket = consumedStore.redeemInvite(consumed.inviteId, consumed.secret, fails, base).enrollTicket!!
        consumedStore.enrollTotp(ticket, "cred-1", Secrets.b64url(ByteArray(20) { 3 }))
        assertEquals("CONSUMED", consumedStore.inviteStatusFor(consumed.inviteId))
        assertEquals(
            AuthStore.ReportStatus.GONE,
            consumedStore.reportInviteByOwner(consumed.inviteId).status,
            "a consumed invite's remedy is revoking the credential it produced, not re-killing the row",
        )
        consumedStore.close()

        val expiredStore = store()
        val expired = expiredStore.mintInvite("relA", listOf("read.share"), ttlSeconds = 10L)
        now += 20_000
        assertEquals(AuthStore.ReportStatus.GONE, expiredStore.reportInviteByOwner(expired.inviteId).status)
        assertEquals("EXPIRED", expiredStore.inviteStatusFor(expired.inviteId))
        expiredStore.close()

        val reportedStore = store()
        val reported = reportedStore.mintInvite("relA", listOf("read.share"), ttlSeconds = 86_400L)
        reportedStore.reportInviteByOwner(reported.inviteId)
        assertEquals("REPORTED", reportedStore.inviteStatusFor(reported.inviteId))
        reportedStore.close()
    }

    @Test
    fun `an unknown invite id is refused without saying so`() {
        // Non-enumerating: a guessed id gets the same answer a real-but-terminal one does, so the
        // report route cannot be used to discover which invitations exist.
        val auth = store()
        assertEquals(AuthStore.ReportStatus.GONE, auth.reportInviteByOwner("no-such-invite").status)
        assertEquals(AuthStore.ReportStatus.GONE, auth.reportInvite("no-such-invite", "guess", fails, base).status)
        auth.close()
    }

    /**
     * A dead invitation's enrolment ticket is dead too — including when the invite died of old age.
     *
     * This is the hole the adversarial review of the report/burn change found, and it made the
     * report route a control in name only. An enrolment ticket carries its own ten-minute expiry,
     * and `enrollTotp` used to consult nothing else, so the ticket outlived the invite that minted
     * it. `killInviteLocked` does sweep tickets, so the *reported* path was covered — but both
     * report functions check the invite's TTL FIRST and, when it has lapsed, flip the row to EXPIRED
     * and answer GONE without sweeping anything. That is exactly the window an attacker who redeemed
     * the invite is sitting in:
     *
     *   attacker redeems, holding a live ticket -> the invite's TTL lapses -> the owner reports it
     *   and is told GONE -> the owner believes the invitation is dead -> the attacker enrols anyway.
     *
     * The fix checks the invite's status where the ticket is SPENT rather than patching each way an
     * invite can die, so a future third path cannot reopen it by forgetting to sweep. This test
     * drives the TTL-lapse route specifically, because that is the one that was never covered.
     */
    @Test
    fun `an enrolment ticket cannot outlive the invite that minted it`() {
        val auth = store()

        /*
         * THE INVITE MUST OUTLIVE ITS OWN TTL WHILE THE TICKET IS STILL YOUNG, and getting that
         * wrong is how the first version of this test passed against the unfixed code.
         *
         * There are two independent clocks. The ticket carries ENROLL_TICKET_TTL_MS (ten minutes);
         * the invite carries whatever TTL it was minted with. The bug only exists in the window
         * where the INVITE has died and the TICKET has not. The first attempt minted a one-hour
         * invite and then advanced an hour — which blew past the ticket's ten minutes as well, so
         * `enrollTotp` refused on the ticket's own expiry check and never reached the invite check
         * at all. It asserted the right thing for the wrong reason and would have reported green
         * over the very hole it was written for.
         *
         * So: a SHORT invite, and a step that lands inside the ticket's life. The relationship
         * between the two is asserted below rather than left to arithmetic a later edit could break.
         */
        val ttlSeconds = 60L
        val step = ttlSeconds * 1000 + 1
        assert(step < AuthStore.ENROLL_TICKET_TTL_MS) {
            "this test proves nothing unless the ticket is still live after the step: " +
                "step=${step}ms vs ticket life=${AuthStore.ENROLL_TICKET_TTL_MS}ms"
        }

        val minted = auth.mintInvite("relA", listOf("read.share"), ttlSeconds = ttlSeconds)

        // Someone redeems and is holding a live enrolment ticket.
        val redeemed = auth.redeemInvite(minted.inviteId, minted.secret, fails, base)
        assertEquals(AuthStore.RedeemStatus.OK, redeemed.status)
        val ticket = redeemed.enrollTicket!!

        // The invite ages out. The ticket has roughly nine minutes left.
        now += step

        // The owner reports it. The answer is GONE — the row had already aged out — and it is
        // precisely that answer which makes the owner believe the matter is closed.
        assertEquals(AuthStore.ReportStatus.GONE, auth.reportInviteByOwner(minted.inviteId).status)

        // So the ticket must not work. Without the invite-status check in `enrollTotp` this returns
        // OK and mints a credential — verified by removing that guard and watching this fail.
        assertEquals(
            AuthStore.EnrollStatus.NO_TICKET,
            auth.enrollTotp(ticket, "cred-attacker", "c3VwZXJzZWNyZXRrZXltYXRlcmlhbA").status,
            "a ticket from an invite the owner was told is GONE must not still enrol a therapist",
        )
        auth.close()
    }
}
