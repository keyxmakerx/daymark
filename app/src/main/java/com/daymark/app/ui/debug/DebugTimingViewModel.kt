package com.daymark.app.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.data.OfferLedgerRepository
import com.daymark.app.data.SettingsRepository
import com.daymark.app.data.entity.OfferKind
import com.daymark.app.stats.PhrasePool
import com.daymark.app.stats.RuleReadout
import com.daymark.app.stats.SupportOfferFrequency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * One feature's row on the debug screen: the [RuleReadout.Feature] itself, plus the one thing the
 * readout cannot know.
 *
 * [declaredIsDefault] is that thing. [RuleReadout] labels the declared frequency "Your setting",
 * which is true for the support space and not yet true for anything else — three of the four kinds
 * have no setting anywhere in the app, so what the screen is showing is
 * [OfferLedgerRepository.defaultFrequency]'s starting point. Saying "your setting" over a number
 * the person never chose is the small dishonesty this flag exists to prevent.
 */
data class DebugFeature(
    val feature: RuleReadout.Feature,
    val declaredIsDefault: Boolean,
)

/** Everything the debug screen draws, computed once per visit. */
data class DebugTimingState(
    val features: List<DebugFeature> = emptyList(),
    /** What the rule reads — the same list for every feature, so the screen shows it once. */
    val reads: List<String> = RuleReadout.READS,
    /** The hour the readout was taken at, 0..23, in the person's own zone. */
    val hour: Int = 0,
    /** The weekday it was taken on, 1..7 with Monday as 1. */
    val weekday: Int = 1,
    /** The zone that hour and weekday are in, named, because it is the thing that makes them mean anything. */
    val zoneId: String = "",
    /** Which half of the day the openers are drawn from — a fact about the clock and nothing else. */
    val band: PhrasePool.Band = PhrasePool.Band.Morning,
    /** Every line in that pool, in order. */
    val openers: List<String> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * The timing layer, read back out of the real engines — `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §5.
 *
 * It computes nothing. Every number on the screen comes from calling [RuleReadout.feature] with the
 * rows the ledger actually holds, which is the property that keeps the description from drifting
 * away from the behaviour: there is no second copy of the rules here to get out of date.
 *
 * **It reads the ledger and nothing else.** No entries, no moods, no goals, no screeners, no
 * people. The screen describes how the app decides to speak; it has no route to anything about the
 * person, and that absence is the whole point of a screen like this existing at all — a debug view
 * is exactly where an inference gets written down as a sentence for the first time.
 *
 * The clock and the zone are read here, once, and handed to the pure engines, which hold neither.
 */
@HiltViewModel
class DebugTimingViewModel @Inject constructor(
    private val ledger: OfferLedgerRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DebugTimingState())
    val state: StateFlow<DebugTimingState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the ledger. The screen offers this because the values move while you watch them. */
    fun refresh() {
        viewModelScope.launch {
            _state.value = read(System.currentTimeMillis(), ZoneId.systemDefault())
        }
    }

    private suspend fun read(nowMillis: Long, zone: ZoneId): DebugTimingState {
        val at: ZonedDateTime = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val hour = at.hour
        val features = OfferKind.entries.map { kind ->
            DebugFeature(
                feature = RuleReadout.feature(
                    kind = ledger.budgetKind(kind),
                    declared = declaredFor(kind),
                    recent = ledger.recentOffers(kind),
                    saidStop = ledger.saidStop(kind),
                    timed = ledger.timedOffers(kind),
                    hour = hour,
                    nowMillis = nowMillis,
                ),
                declaredIsDefault = !hasStoredSetting(kind),
            )
        }
        val band = PhrasePool.bandForHour(hour)
        return DebugTimingState(
            features = features,
            hour = hour,
            weekday = at.dayOfWeek.value,
            zoneId = zone.id,
            band = band,
            openers = PhrasePool.pool(band),
            loaded = true,
        )
    }

    /**
     * The frequency this kind is running at, from wherever it is actually kept.
     *
     * Only [OfferKind.SUPPORT] has a setting the person can reach today, so the others report the
     * starting point [OfferLedgerRepository.defaultFrequency] gives them. Guessing one here would
     * be indistinguishable on screen from one somebody chose, which is why [hasStoredSetting] says
     * which is which and the screen prints it.
     */
    private fun declaredFor(kind: OfferKind): SupportOfferFrequency = when (kind) {
        OfferKind.SUPPORT -> settings.supportOfferFrequency
        else -> ledger.defaultFrequency(kind)
    }

    /** Whether [declaredFor] read a stored choice rather than a default. */
    private fun hasStoredSetting(kind: OfferKind): Boolean = kind == OfferKind.SUPPORT
}
