package com.daymark.app.data

import com.daymark.app.data.dao.OfferRecordDao
import com.daymark.app.data.entity.OfferKind
import com.daymark.app.data.entity.OfferOutcome
import com.daymark.app.data.entity.OfferRecord
import com.daymark.app.stats.InterruptionBudget
import com.daymark.app.stats.SupportOfferFrequency
import com.daymark.app.stats.TimingGrid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reception ledger, as everything outside `data/` is allowed to see it.
 *
 * [OfferRecord] is a Room entity and [InterruptionBudget] is pure — Android-free, JVM-testable, and
 * required to stay that way. This class is the seam between them: it owns the [OfferRecordDao], and
 * it is **the only place [OfferRecord] is turned into [InterruptionBudget.Offer]**. That mapping is
 * here rather than in `stats/` for the reason `InterruptionBudget`'s own header gives — the
 * dependency runs one way, `data` reads `stats` and never the reverse, so nothing in `stats/` can
 * acquire an Android dependency by having an entity handed to it, and its unit tests keep running
 * on a plain JVM. `DiscussionPrompts` / `DiscussionPrompts.Inputs` is the same shape.
 *
 * ## What this class is not allowed to become
 *
 * It stores the app's behaviour and how that behaviour landed. It stores **nothing about the
 * person** — [OfferRecord] has no free-text field and this class adds none. Every column is a fact
 * about the app's own asking: which feature asked, when, when in the week, whether anything came
 * back, and what became of it. Nothing here counts consecutive runs, computes rates, or produces
 * anything a report or a clinician could read as a signal about how someone is doing
 * (`docs/DECISIONS_2026-08.md` §D1a, §D6). A quiet ledger means the app was quiet.
 *
 * **None of it is ever shared.** `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §4: *the reception ledger
 * and the timing grid are never shared with a clinician — when someone answers the app is the app's
 * business with them, and it stays on the phone.* There is no export path to close, and that is not
 * an accident: `BackupManager` deliberately carries no `offer_records` table, so a backup, a CSV or
 * a PDF report has nowhere to put one. [timedOffers] is read by a debug screen and by placement,
 * and by nothing that leaves the device.
 *
 * It also holds no policy. Whether a feature may interrupt is [InterruptionBudget]'s answer; this
 * class reads rows, hands them over unjudged, and returns what the arbiter said. The one thing it
 * must actively protect is the direction of travel:
 *
 * > Falling reception may only ever make the app ask **less**. No combination of rows may make it
 * > ask more, and escalation is a setting the person changes, never an inference.
 *
 * Two operations here can move against that direction if they are written carelessly — writing a
 * row late (or not at all), and deleting old rows — so each is called out where it happens.
 *
 * ## Retention — [RETENTION_DAYS] days, fixed
 *
 * The ledger is behaviour data about a person and there is no version of this product in which
 * keeping it forever is right. The behavioural-guard section of `docs/COMPANION_ACCESS_CONTROL.md`
 * already states the rule for exactly this kind of store: *log the minimum — a rich behavioural
 * store is its own target and its own privacy liability — and keep it a short time.* This is that
 * store, so it takes that rule: [sweepRetention] deletes every line older than [RETENTION_DAYS]
 * days. The window is a constant, not a setting, so there is no configuration in which the ledger
 * quietly becomes a long-term record.
 *
 * **Why sixty days and not seven.** Deleting rows is the one operation in this file that can make
 * the app ask *more*, because reception is read from what is still there: forget the dismissals and
 * the kind reads as [InterruptionBudget.Reception.Open] again. The arbiter reads a kind's last
 * [InterruptionBudget.RECENT_WINDOW] offers, and the quietest rung it can reach on its own is one
 * offer a week, so a full reading can span four to five weeks. A window shorter than that would
 * routinely delete the very rows that were keeping the app quiet. Sixty days clears that span with
 * room to spare while still being short enough that the table is working state rather than history.
 */
@Singleton
class OfferLedgerRepository @Inject constructor(
    private val dao: OfferRecordDao,
) {

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------

    /**
     * Writes one line: this kind asked at [offeredAtMillis], and this is what became of it.
     *
     * A ledger line is a fact about a moment that happened, so there is no update path — the row is
     * written once, when the outcome is known, and never edited.
     *
     * **A feature that shows an offer must always end up writing a line for it**, including when
     * the person simply ignores it ([OfferOutcome.DISMISSED] is the outcome for being waved away,
     * and not engaging is being waved away). This is not tidiness: [offeredAtMillis] is what the
     * minimum-gap timer counts from, so an offer with no row is an offer that never happened, and
     * the next one is permitted *sooner*. Missing rows push in the one direction nothing here may
     * move. Which outcome fits is the calling feature's judgement, not this class's — it knows what
     * it showed and this class deliberately does not.
     *
     * **[responded] is the narrower fact, and it defaults to "not recorded".** The outcome says what
     * became of the offer and is what the budget spends; [responded] says only whether anybody was
     * there, and it is the whole of placement's input. See
     * [com.daymark.app.data.entity.OfferRecord.responded] for why this is a column rather than a
     * fifth [OfferOutcome].
     *
     * The default is `null` and not `true`, because `null` is the only one of the three that is
     * true of a caller who has not been told. A feature writes its row at the moment it asks, which
     * for most of them is *before* any answer could have arrived — claiming `true` there would be
     * the same mistake as a `NOT NULL DEFAULT 0` on a column nobody filled in, and this table's
     * whole discipline is that it never records what it does not know.
     *
     * Nothing is lost by being honest about it: [timedOffers] nulls an outcome only on a stored
     * `false`, so `null` and `true` reach placement identically — as an answered hour, which keeps
     * the app asking. A forgotten argument therefore fails in the only direction this system may
     * move, and it fails without lying.
     *
     * The caller supplies the clock, as everywhere else in this layer, so the behaviour is testable
     * without one. [zone] is a parameter for the same reason and defaults to the phone's, which is
     * the person's own — the only zone that means anything here.
     */
    suspend fun record(
        kind: OfferKind,
        outcome: OfferOutcome,
        offeredAtMillis: Long,
        responded: Boolean? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long =
        dao.insert(
            OfferRecord(
                kind = kind.key,
                offeredAt = offeredAtMillis,
                outcome = outcome.key,
                offeredHour = hourIn(offeredAtMillis, zone),
                offeredWeekday = weekdayIn(offeredAtMillis, zone),
                responded = responded,
            ),
        )

    /**
     * [offeredAtMillis] as an hour of the day in [zone], 0..23.
     *
     * Derived here rather than passed in by every caller, and that is the point: a feature cannot
     * forget to stamp it, the way it could forget an argument. It is sound only because of the
     * contract [record] already states — **a line is written at the moment of the ask** — so the
     * zone being read is the zone the person is in right now, applied to a timestamp from right
     * now. The same two lines run over a timestamp from last March would be the invented evidence
     * [com.daymark.app.data.entity.OfferRecord.offeredHour] forbids, which is why the one caller in
     * this file that writes a row for something that was *not* an ask ([sweepRetention]) does not
     * come through here.
     */
    private fun hourIn(offeredAtMillis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(offeredAtMillis).atZone(zone).hour

    /** The same for the weekday, 1..7 with Monday as 1 — `java.time.DayOfWeek.value`. */
    private fun weekdayIn(offeredAtMillis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(offeredAtMillis).atZone(zone).dayOfWeek.value

    // ---------------------------------------------------------------------------------------
    // Reading — per kind
    // ---------------------------------------------------------------------------------------

    /**
     * What became of this kind's most recent offer, or null when it has never asked.
     *
     * Null also covers an `outcome` key this version does not recognise — an older backup, a
     * hand-edited file, a row a later version wrote — because there is no typed value to return for
     * one. Both cases mean the same thing to a caller: no usable outcome. The arbiter does not go
     * through here for exactly that reason; [recentOffers] hands it the raw keys so it can decide
     * for itself, quietly, what an unknown one is worth.
     */
    suspend fun lastOutcome(kind: OfferKind): OfferOutcome? =
        OfferOutcome.fromKey(dao.latestForKind(kind.key)?.outcome)

    /** When this kind last asked, or 0 if it never has. */
    suspend fun lastOfferedAt(kind: OfferKind): Long = dao.lastOfferedAt(kind.key)

    /**
     * Whether the person has told this kind to stop asking.
     *
     * Read separately from [recentOffers] because it is a standing preference rather than a fact
     * about one moment: it holds until the person lifts it themselves, so it must not depend on the
     * row still falling inside [InterruptionBudget.RECENT_WINDOW]. [sweepRetention] will not delete
     * it either.
     */
    suspend fun saidStop(kind: OfferKind): Boolean =
        dao.hasOutcome(kind.key, OfferOutcome.STOP.key)

    /**
     * This kind's recent rows as the arbiter's own input type — the mapping this class exists for.
     *
     * Exactly [InterruptionBudget.RECENT_WINDOW] rows, newest first, which is what
     * [InterruptionBudget.receptionOf] reads and enough for [InterruptionBudget.lastOfferedAt] to
     * find this kind's newest offer.
     *
     * The `kind` written onto each [InterruptionBudget.Offer] is the arbiter's key, not the string
     * that came out of the table. The query already filtered to one kind, so every row is known to
     * belong to it, and stamping the arbiter's own key means its filter cannot silently drop a row
     * if the two enums' keys ever drift apart. That failure would read as an empty history — as
     * [InterruptionBudget.Reception.Open], as asking more — so it is worth closing by construction
     * rather than by a comment asking the two files to agree.
     *
     * `outcome` is passed through **raw and unparsed**, which is the arbiter's stated preference: a
     * key it does not recognise is a case it decides for itself, and it decides it towards quiet.
     * Parsing here would move that decision out of its reach.
     */
    suspend fun recentOffers(kind: OfferKind): List<InterruptionBudget.Offer> =
        dao.recentForKind(kind.key, InterruptionBudget.RECENT_WINDOW).map { record ->
            InterruptionBudget.Offer(
                kind = budgetKind(kind).key,
                offeredAt = record.offeredAt,
                outcome = record.outcome,
            )
        }

    /**
     * This kind's rows as **placement's** own input type — the second mapping this class exists for,
     * and the counterpart to [recentOffers].
     *
     * **Every row of the kind, not a window.** [recentOffers] takes
     * [InterruptionBudget.RECENT_WINDOW] because reception is a question about the last few asks;
     * [TimingGrid] asks a different question — which hours of the week this feature has ever been
     * answered in — and a window there would make an hour's standing depend on how recently the app
     * happened to try it, so a quiet fortnight would erase what a month of answers established. The
     * table is still bounded: [sweepRetention] keeps [RETENTION_DAYS] days and this adds nothing to
     * that.
     *
     * Like [recentOffers] this stamps the arbiter's own key rather than the string out of the table,
     * for the same reason — the query already filtered to one kind, and a drift between the two
     * enums would otherwise read as a kind with no history, which is the direction that asks more.
     *
     * **The outcome is nulled only by a recorded `false`.** [TimingGrid.Ask.outcome] means something
     * narrower than "what became of it": `null` is *no response was ever recorded*, and any other
     * value — recognised or not — is an answer. So the rule here is exactly one line long, and it
     * never looks at the outcome key:
     *
     *  - [OfferRecord.responded] `== false` → `null`. The app asked and nothing came back.
     *  - anything else, including `null` → the stored key, unparsed.
     *
     * A row whose [OfferRecord.responded] is `null` predates the distinction, and "we do not know"
     * must not be read as "nobody was there" — that is the direction that gives up an hour on
     * evidence nothing recorded. Such a row carries [OfferRecord.UNRECORDED] slots too, so
     * [TimingGrid] drops it before the outcome matters; the rule above is the belt to that pair of
     * braces, and it fails in the same direction either way.
     */
    suspend fun timedOffers(kind: OfferKind): List<TimingGrid.Ask> =
        dao.allForKind(kind.key).map { record ->
            TimingGrid.Ask(
                kind = budgetKind(kind).key,
                hour = record.offeredHour,
                weekday = record.offeredWeekday,
                outcome = if (record.responded == false) null else record.outcome,
            )
        }

    /**
     * May this kind interrupt right now?
     *
     * The whole interface a feature needs, and a thin pass-through: every decision is
     * [InterruptionBudget]'s, and this only saves each caller from assembling the arguments — where
     * the mistake available is pairing one kind's rows with another kind's reception.
     *
     * [declared] is the person's own setting for this kind and has to come from the settings layer;
     * it is not defaulted here, because a frequency this class guessed would be indistinguishable
     * at the call site from one the person chose. [defaultFrequency] is what to use before they
     * have chosen.
     */
    suspend fun mayInterrupt(
        kind: OfferKind,
        declared: SupportOfferFrequency,
        nowMillis: Long,
    ): Boolean = InterruptionBudget.shouldInterrupt(
        kind = budgetKind(kind),
        declared = declared,
        recent = recentOffers(kind),
        saidStop = saidStop(kind),
        nowMillis = nowMillis,
    )

    /** This kind's starting frequency, before the person has said otherwise. */
    fun defaultFrequency(kind: OfferKind): SupportOfferFrequency =
        InterruptionBudget.defaultFrequency(budgetKind(kind))

    // ---------------------------------------------------------------------------------------
    // Reading — the signal layer
    // ---------------------------------------------------------------------------------------

    /**
     * The `outcome` of the newest row in the whole table, or null when nothing has ever been
     * offered — `Signals.Inputs.lastOfferOutcomeKey`, which is across kinds rather than per kind.
     *
     * Returned as the stored string, unparsed, because that is what `Signals` takes and it does its
     * own narrowing against `Signals.OFFER_OUTCOME_KEYS`; a key neither side knows becomes "no such
     * fact yet" there, which is an ordinary state and not an error. Reading every row rather than
     * asking each [OfferKind] in turn is deliberate — it also sees rows whose `kind` this version
     * does not recognise, which is the honest answer to "what happened most recently".
     */
    suspend fun lastOutcomeKey(): String? = dao.getAll().maxByOrNull { it.offeredAt }?.outcome

    /**
     * The same value, emitted again whenever the ledger changes, for a surface that should update
     * without being told to.
     *
     * Only the key crosses this boundary. Handing out the rows themselves would put Room entities —
     * and a full behavioural history — into UI code, which is the leak this class exists to stop.
     */
    fun observeLastOutcomeKey(): Flow<String?> =
        dao.observeAll().map { records -> records.firstOrNull()?.outcome }.distinctUntilChanged()

    // ---------------------------------------------------------------------------------------
    // Retention
    // ---------------------------------------------------------------------------------------

    /**
     * Deletes every line older than [RETENTION_DAYS] days. Safe to call as often as convenient;
     * once on app start is enough.
     *
     * **The standing "stop asking" survives the sweep.** Every other row is a fact about one moment
     * and forgetting it is fine; [OfferOutcome.STOP] is a preference the person expressed, and
     * deleting it would resume asking someone who asked us not to — the sweep would have become the
     * escalation path §D1a forbids. So a kind that was closed before the sweep is still closed
     * after it: if the deletion took its only `stop` row, one is written back at the cutoff, which
     * is the oldest moment this table is still allowed to remember. The preference is carried
     * forward; the behaviour history behind it is not, which is the whole point of the sweep.
     *
     * The carry-forward covers the [OfferKind]s this version knows. A `stop` stored under a kind
     * from a later version is outside what this code can enumerate and will age out — an old app
     * opening a newer backup, and worth knowing about rather than being surprised by.
     */
    suspend fun sweepRetention(nowMillis: Long) {
        val cutoff = nowMillis - RETENTION_MILLIS
        if (cutoff <= 0L) return
        val closedBefore = OfferKind.entries.filter { saidStop(it) }
        dao.deleteOlderThan(cutoff)
        for (kind in closedBefore) {
            if (!saidStop(kind)) {
                dao.insert(
                    OfferRecord(
                        kind = kind.key,
                        offeredAt = cutoff,
                        outcome = OfferOutcome.STOP.key,
                        // NOT the hour the sweep happened to run in. This row is a preference being
                        // carried forward, not an ask that was made — writing a real slot on it
                        // would inject a phantom ask into placement's grid at whatever hour the app
                        // was next opened, which is evidence of the app's behaviour that the app did
                        // not perform. UNRECORDED is out of range for both, so TimingGrid drops it.
                        offeredHour = OfferRecord.UNRECORDED,
                        offeredWeekday = OfferRecord.UNRECORDED,
                        // Not an ask, so there was nothing to respond to. Left unrecorded rather
                        // than called false: false would say an hour went unanswered, and this row
                        // is not about an hour.
                        responded = null,
                    ),
                )
            }
        }
    }

    /**
     * Erases the ledger entirely.
     *
     * This one *is* allowed to make the app ask more, because it is the person doing it: their own
     * erase, or an import replacing their data. §D1a's rule is that the app may not talk itself into
     * asking more, not that the person may not — escalation is theirs to choose. It is also how a
     * standing "stop asking" gets lifted, since a ledger line is never edited or deleted singly.
     */
    suspend fun clear() = dao.deleteAll()

    /**
     * The arbiter's [InterruptionBudget.Kind] for a stored [OfferKind].
     *
     * Written as an exhaustive `when` rather than a lookup by key so that the two enums drifting
     * apart is a compile error here — the one place it can be caught — instead of a lookup that
     * quietly returns nothing and reads as a kind with no history.
     *
     * Public so that a caller holding an [OfferKind] can ask the pure engines a question directly —
     * `ui/debug` does, to build a `RuleReadout` — rather than keeping a second copy of this `when`
     * and reintroducing exactly the drift it exists to prevent.
     */
    fun budgetKind(kind: OfferKind): InterruptionBudget.Kind = when (kind) {
        OfferKind.COMPANION -> InterruptionBudget.Kind.COMPANION
        OfferKind.REMINDER -> InterruptionBudget.Kind.REMINDER
        OfferKind.ASSIGNMENT -> InterruptionBudget.Kind.ASSIGNMENT
        OfferKind.SUPPORT -> InterruptionBudget.Kind.SUPPORT
    }

    companion object {
        /**
         * How long a ledger line is kept. Fixed, and deliberately not a setting — see the class
         * note for why it is sixty days rather than a week.
         */
        const val RETENTION_DAYS: Int = 60

        /** [RETENTION_DAYS] in millis, which is what the rows are stamped in. */
        const val RETENTION_MILLIS: Long = RETENTION_DAYS * 24L * 60L * 60L * 1000L
    }
}
