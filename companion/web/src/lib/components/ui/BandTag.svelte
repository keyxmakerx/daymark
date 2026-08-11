<script lang="ts">
  /*
   * A band — a person's own reported experience, named.
   *
   * THIS IS THE ONE COMPONENT IN ui/ PERMITTED TO TOUCH THE MOOD RAMP, and it is worth being
   * precise about why. --mood-1..--mood-5 encode a person's reported experience and nothing
   * else (app.css, invariant 1). A band IS that reported experience, reduced to a descriptive
   * label — so here, and only here, the ramp is being used for exactly what it means. Every
   * other component in this directory signals interface state, and interface state belongs to
   * --chrome-*, --indigo-*, --clay-* and --amber-*. If a second component in ui/ ever reaches
   * for a mood token, the ramp has stopped being a legend the reader can trust: a wash they
   * learned to read as "a rough week" would start turning up on a hover state.
   *
   * WHY THE WORDS ARE AUTHORITATIVE AND THE BAR IS NOT. The label is the band's own name —
   * "Moderate", "Elevated", whatever the instrument's author wrote. It is always rendered, and
   * it carries the entire meaning on its own. The coloured bar is a redundant second encoding
   * that lets a reader scan a column of bands quickly; it is never the only signal, so the tag
   * survives greyscale, a printout, a projector and any colour vision with nothing lost. The
   * aria-label carries the same words plus the trend, so assistive tech gets the scannable
   * version too.
   *
   * WHY level: null IS A FIRST-CLASS STATE. It means the person has not shared this yet. That
   * is a fact about what we know, not a fact about them, and it must never be rendered as a
   * zero, an empty ramp position, or a low band — all of which would be the interface
   * inventing data on a person's behalf and putting it in front of a clinician. So it gets its
   * own neutral fill (--border-strong, which is not on the ramp and never will be), a faint
   * label, and the words "not shared yet" in the accessible name. Absence looks like absence.
   *
   * WHY THE BAND IS NEVER A SCORE. This product bands descriptively and has no clinical
   * cutoff, no positive/negative flag and no diagnostic threshold (see instruments/types.ts).
   * The tag therefore never renders the numeric level — the number exists only as the ramp
   * position that picks a hue. Showing "3/5" would manufacture a precision the instrument does
   * not have and invite a reader to compare two people's bands as if they were measurements.
   *
   * WHY THE TREND IS MONO AND FAINT. "↑ 3 wk" is machine text — a direction over a window,
   * computed, not written by the person. Mono with tabular figures so a column of them lines
   * up; faint so it stays subordinate to the band it qualifies. It is optional because most
   * bands have no window long enough to say anything honest about direction, and a trend
   * slot padded with "—" would read as a claim that nothing changed.
   */

  let {
    label,
    level,
    trend,
  }: {
    label: string
    /** Ramp position 1–5. `null` means the person has not shared this yet — not a zero. */
    level: 1 | 2 | 3 | 4 | 5 | null
    /** Optional machine-computed direction, e.g. "↑ 3 wk". Omit when there is nothing honest to say. */
    trend?: string
  } = $props()

  /* The accessible name. Assembled rather than templated so an absent trend contributes
     nothing at all — no stray comma, no empty slot a screen reader has to read past. */
  let description = $derived.by(() => {
    const parts: string[] = [label]
    if (level === null) parts.push('not shared yet')
    if (trend) parts.push(trend)
    return parts.join(', ')
  })
</script>

<span class="band" role="img" aria-label={description}>
  <span class="bar" data-level={level ?? 'none'}></span>
  <span class="label" data-level={level ?? 'none'}>{label}</span>
  {#if trend}<span class="trend u-mono">{trend}</span>{/if}
</span>

<style>
  .band {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    font-size: 0.9rem;
    line-height: 1.4;
    white-space: nowrap;
  }

  /* Fixed 9x20px whatever the band, so a column of tags reads as one ruled edge and a band
     that changes does not resize the row. Upright rather than a dot: a bar reads as a position
     on a scale, which is what it is, while a dot would read as a status light. */
  .bar {
    width: 9px;
    height: 20px;
    flex: 0 0 auto;
    border-radius: var(--radius-sm);
    transition: background 180ms ease;
  }

  /* The data ramp, awful → rad. Identical in both themes: a 3 is a 3 at midnight. */
  .bar[data-level='1'] { background: var(--mood-1); }
  .bar[data-level='2'] { background: var(--mood-2); }
  .bar[data-level='3'] { background: var(--mood-3); }
  .bar[data-level='4'] { background: var(--mood-4); }
  .bar[data-level='5'] { background: var(--mood-5); }

  /* Not shared yet. --border-strong is the deliberate choice here: it is a neutral that sits
     off the ramp entirely, so this bar cannot be misread as a band at either end of it. */
  .bar[data-level='none'] { background: var(--border-strong); }

  .label {
    color: var(--ink-text);
    min-width: 0;
    overflow-wrap: anywhere;
    white-space: normal;
  }

  /* Faint, because the label of an unshared band names a thing we have no data for. The word
     "unshared" itself is carried by the accessible name, never by this colour alone. */
  .label[data-level='none'] {
    color: var(--ink-faint);
  }

  .trend {
    color: var(--ink-faint);
    font-size: 0.75rem;
  }

  /* A band can change under the reader when a new response syncs. For anyone who has asked for
     less motion, that change is instant rather than a crossfade. */
  @media (prefers-reduced-motion: reduce) {
    .bar {
      transition: none;
    }
  }
</style>
