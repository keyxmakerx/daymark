package com.daymark.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daymark.app.ui.theme.CardShape
import com.daymark.app.ui.theme.HairlineWidth

/**
 * How honest a tool is about what it is. Every questionnaire, screener, or structured exercise in
 * the app declares one of these — see `docs/PROVENANCE.md`.
 *
 * The label is immutable per version: a tool cannot quietly promote itself, and editing a
 * Validated tool's wording downgrades it to [ADAPTED].
 *
 * [symbol] says how a tool departs from a published instrument: a half circle for part of one, a
 * pencil for none. A Validated tool departs from nothing, so it carries no mark and its word says the
 * rest. Never a tick and never green: beside a questionnaire either reads as "you passed", and a
 * filled circle beside Adapted's half would read as full marks against half (`CLAUDE.md` §4, #278).
 * `TickAndGreenSourceTest` holds `ui/components/` to that.
 */
enum class ProvenanceTier(val symbol: String?, val label: String) {
    /** A published instrument used faithfully — exact wording, scoring, and banding. */
    VALIDATED(null, "Validated"),

    /** Built on an evidence-based method but modified. Names the method it draws from. */
    ADAPTED("◐", "Adapted"),

    /** Ours, informed by nothing in particular. Makes no evidence claim at all. */
    ORIGINAL("✎", "Original"),
}

/**
 * The small pill naming a tool's tier, shown on its start screen.
 *
 * Its words, and the note's below, take the full ink: on the hairline fill the soft ink measures
 * 4.36:1 in the light theme, under the 4.5:1 small text needs (#408). `HairlineFillSourceTest` holds
 * every word on that fill to it.
 */
@Composable
fun ProvenanceBadge(tier: ProvenanceTier, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text = tier.symbol?.let { "$it ${tier.label}" } ?: tier.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The badge plus the tool's plain-language disclaimer, as one block.
 *
 * The disclaimer is deliberately not collapsible and not behind a tap. A person deciding whether
 * to trust a number needs to know what produced it *before* they read it, not after.
 */
@Composable
fun ProvenanceNote(
    tier: ProvenanceTier,
    disclaimer: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(HairlineWidth, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProvenanceBadge(tier)
            Text(
                text = disclaimer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
