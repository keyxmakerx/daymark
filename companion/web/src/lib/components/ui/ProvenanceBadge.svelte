<script lang="ts">
  /*
   * What a tool clinically IS, stated on the tool itself.
   *
   * WHY IT EXISTS AT ALL. Every questionnaire in this product looks the same once it is
   * running: a prompt, some options, a number at the end. Nothing in that form tells a reader
   * whether they are looking at a published instrument reproduced faithfully, a loose
   * adaptation of one, or something a practitioner wrote on a Tuesday. Those three things
   * carry wildly different weight in a clinical conversation, and the difference is invisible
   * unless the interface says it. This badge is the place it is said.
   *
   * WHY IT IS DESIGNED TO REPEAT. Every tool in the catalog today is tier 'custom'. A single
   * banner at the top of a list would be read once and then scrolled past, and the reader
   * would spend the rest of the page assuming provenance they were never told. So this badge
   * is small enough to sit on every row of a table and inside every card header, and it is
   * meant to be repeated there. A clinician must never have to remember, infer, or scroll back
   * up to find out what they are holding.
   *
   * WHY THE STRINGS ARE NOT HERE. PROVENANCE_LABEL and PROVENANCE_GLYPH already exist in
   * lib/instruments/provenance.ts, next to the disclaimer copy and the validation that
   * enforces the tiers. Re-spelling "Custom" here would let the badge and the disclaimer drift
   * apart, and a badge that says one thing while the gate says another is worse than no badge.
   * Both maps are keyed by every ProvenanceTier, so a new tier is a type error rather than a
   * blank badge.
   *
   * WHY VALIDATED HAS NO MARK. The marks on the other two tiers say how a tool departs from a
   * published instrument: ◐ draws on part of one, ✎ on none. A validated tool departs from
   * nothing, so the badge carries its word alone. Any mark there would read as a verdict, and a
   * tick as a pass: reassurance the product has no standing to offer, and a tick it never draws
   * (CLAUDE.md §4, #278). The badge is never green either: there is no success colour in this
   * system (app.css, invariant 2).
   *
   * WHY custom IS AMBER AND THE OTHER TWO ARE NOT. Amber means warn severity and only warn
   * severity, and provenance "Custom" is one of exactly two places in the whole system allowed
   * to spend it (the other is console warn rows). 'Custom' is a real caveat — not a validated
   * or clinical instrument, not for diagnosis — so it gets a filled ground that a reader
   * cannot skim past. 'adapted' and 'validated' are quiet outlines: they qualify without
   * interrupting, and a page of validated tools should look calm rather than decorated.
   * Clay is deliberately absent — a custom tool is not an alarm, and if the warn hue crept
   * toward the alarm hue neither would mean anything.
   *
   * WHY HUE IS NEVER THE ONLY SIGNAL. Three non-colour signals, in order of reliability: the
   * written tier name, which is always present and always the point; the mark the adapted and
   * custom tiers carry; and the edge FORM — validated closes a solid outline, adapted is broken (it is
   * not the whole instrument), custom is filled. That ordering survives greyscale, a
   * projector, a printout and any colour vision. A visually hidden "Provenance:" prefix gives
   * a screen reader the context a sighted reader gets from the badge's position on the row.
   *
   * WHY NO MOOD TOKENS. The mood ramp encodes a person's reported experience. A tool's
   * paperwork is not a person's experience, and painting provenance with the data ramp would
   * make the legend lie.
   */

  /* The maps come from the instrument module that owns the copy; the type comes from the
     schema module that owns the union. They are separate imports because a Svelte instance
     script cannot export types and provenance.ts does not re-export the ones it consumes —
     so the type is taken from its source rather than laundered through a second file. */
  import { PROVENANCE_LABEL, PROVENANCE_GLYPH } from '../../instruments/provenance'
  import type { ProvenanceTier } from '../../instruments/types'

  let { tier }: { tier: ProvenanceTier } = $props()
</script>

<span class="badge" data-tier={tier}>
  <span class="visually-hidden">Provenance: </span>
  {#if PROVENANCE_GLYPH[tier]}<span class="glyph" aria-hidden="true">{PROVENANCE_GLYPH[tier]}</span>{/if}
  <span class="tier">{PROVENANCE_LABEL[tier]}</span>
</span>

<style>
  /* Every tier paints a full 1px border and identical box metrics, whatever its fill, so a
     column of badges down a table lines up exactly and a tier change cannot shove the row it
     sits in by a pixel. */
  .badge {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: 0.125rem var(--space-2);
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    background: transparent;
    font-family: var(--font-mono);
    font-size: 10px;
    line-height: 1.5;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    white-space: nowrap;
    transition: background 180ms ease, border-color 180ms ease;
  }

  /* Sits on the same optical line as the tier name rather than on the text baseline: the
     marks have different heights (◐ ✎) and a shared baseline makes them jitter. */
  .glyph {
    font-size: 11px;
    line-height: 1;
  }

  /* Faithfully reproduces a published instrument. The quietest of the three — there is no
     caveat to carry, so the badge states the fact and gets out of the way. Solid edge: the
     whole instrument is here. */
  .badge[data-tier='validated'] {
    border-color: var(--chrome-hair);
    color: var(--chrome-soft);
  }

  /* Draws on a method without being it. Same quiet outline, but broken — the dashed edge says
     "not the whole thing" in a way that survives greyscale, and the ink steps up to
     --chrome-ink because this tier carries a caveat the validated tier does not. */
  .badge[data-tier='adapted'] {
    border-style: dashed;
    border-color: var(--chrome-hair);
    color: var(--chrome-ink);
  }

  /* Not a validated or clinical instrument, not for diagnosis. The one tier with a filled
     ground, in the one warn hue the system has. Every catalog tool is this tier today, so this
     is the badge a reader will actually see — it has to hold up under repetition without
     escalating into alarm. */
  .badge[data-tier='custom'] {
    background: var(--amber-wash);
    border-color: color-mix(in srgb, var(--amber) 45%, transparent);
    color: var(--amber);
  }

  /* A badge can change tier under the reader when a draft is published or re-tiered. For
     anyone who has asked for less motion, that change is instant rather than a crossfade. */
  @media (prefers-reduced-motion: reduce) {
    .badge {
      transition: none;
    }
  }
</style>
