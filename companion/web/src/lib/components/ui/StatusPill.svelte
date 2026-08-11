<script lang="ts">
  /*
   * The assignment lifecycle, rendered so that FORM carries the meaning and hue is the last
   * resort.
   *
   * WHY FORM FIRST. Seven states is more than any colour ramp can distinguish safely — around
   * one in twelve men cannot separate the two ends of a red/green pair, and no one can separate
   * anything at all on a projector, in bright sun, or on a printout. So the states are told
   * apart first by shape (outline vs. wash vs. solid vs. hatch), second by the marker (hollow =
   * nothing has come back, solid = something happened), third by a line-through where the label
   * itself is negated, and only then by hue. Every pill also carries its full written label, so
   * colour is never the sole carrier of meaning even for a reader who sees it perfectly.
   *
   * WHY THERE IS NO GREEN "COMPLETED". Completed is solid ink, not a green tick. Green is a
   * claim — that we checked, that it held, that you may stop reading — and this product is not
   * in a position to make it (see app.css, invariant 2). Ink is emphatic without asserting
   * anything about the quality of what was completed.
   *
   * WHY NO MOOD TOKENS. The mood ramp encodes a person's reported experience and nothing
   * else. An assignment's delivery state is interface state, and borrowing the data ramp for
   * it would make the legend lie — a wash the reader has learned to read as "rough day" would
   * suddenly mean "delivered". Interface state uses chrome, indigo and clay only.
   *
   * WHY CLAY IS SPENT SPARINGLY. Exactly two states reach for the alarm hue: noResponse
   * (silence past the point where a human should look) and overdue. If every state that were
   * merely "not finished" turned red, red would stop meaning anything.
   *
   * Silence is not refusal: noResponse and declined are drawn differently on purpose, because
   * reporting a decision the person never made would be a lie about their behaviour.
   */
  import { STATUS_LABEL, type AssignmentStatus } from './status'

  let { status }: { status: AssignmentStatus } = $props()
</script>

<span class="pill" data-status={status}>
  <span class="marker" aria-hidden="true"></span>
  <span class="label">{STATUS_LABEL[status]}</span>
</span>

<style>
  /* Every state paints a 1px border and the same box metrics, whatever its fill, so a pill
     that changes state animates its colours in place instead of resizing and shoving the row
     it sits in. That is also why the transition names background and border-color together. */
  .pill {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: 0.1875rem var(--space-2);
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    background: transparent;
    color: var(--chrome-soft);
    font-family: var(--font-mono);
    font-size: 11px;
    line-height: 1.45;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    white-space: nowrap;
    transition: background 180ms ease, border-color 180ms ease;
  }

  /* Square, not a dot: the chrome layer is orthogonal and machine-like, and a square reads as
     a state marker rather than as a decorative bullet. box-sizing is border-box globally, so
     the hollow and filled variants occupy exactly the same 5px. */
  .marker {
    width: 5px;
    height: 5px;
    flex: 0 0 auto;
    border: 1px solid currentColor;
    background: currentColor;
  }

  /* Nothing has come back from the person yet — the mark is empty because the state is. */
  .pill[data-status='scheduled'] .marker,
  .pill[data-status='noResponse'] .marker {
    background: transparent;
  }

  /* Not sent yet. No fill at all: an empty outline is the quietest thing the layout can hold,
     and a not-yet-started row should not compete with the rows that need reading. */
  .pill[data-status='scheduled'] {
    color: var(--chrome-soft);
    border-color: var(--chrome-hair);
  }

  /* Sent, unacknowledged. A wash and a soft edge — the system did something; the person has
     not yet. */
  .pill[data-status='delivered'] {
    color: var(--indigo);
    background: var(--indigo-wash);
    border-color: color-mix(in srgb, var(--indigo) 40%, transparent);
  }

  /* Acknowledged. Same wash as delivered, but the edge resolves to a solid line and the text
     deepens: the progression from delivered to accepted is legible as the border firming up,
     not as a change of hue. */
  .pill[data-status='accepted'] {
    color: var(--indigo-deep);
    background: var(--indigo-wash);
    border-color: var(--indigo);
  }

  /* Done. Solid ink on paper — maximum weight, zero claim. Deliberately NOT green. */
  .pill[data-status='completed'] {
    color: var(--paper-sheet);
    background: var(--ink-text);
    border-color: var(--ink-text);
  }

  /* Refused. A decision was made, so the marker is solid — but the label is struck, which
     survives greyscale, screenshots and colour blindness in a way a hue never does. */
  .pill[data-status='declined'] {
    color: var(--chrome-soft);
    border-color: var(--chrome-hair);
  }
  .pill[data-status='declined'] .label {
    text-decoration: line-through;
  }

  /* Silence past the point where a human should look. The hatch is the load-bearing signal:
     it is a texture, so it reads as "unresolved" on a monochrome printout too, and it keeps
     noResponse visually distinct from the solid alarm of overdue. */
  .pill[data-status='noResponse'] {
    color: var(--clay);
    background-color: var(--clay-wash);
    background-image: repeating-linear-gradient(
      135deg,
      color-mix(in srgb, var(--clay) 24%, transparent) 0 2px,
      transparent 2px 6px
    );
    border-color: color-mix(in srgb, var(--clay) 45%, transparent);
  }

  /* The one state that is allowed to shout. Solid alarm, reversed text. */
  .pill[data-status='overdue'] {
    color: var(--paper-sheet);
    background: var(--clay);
    border-color: var(--clay);
  }

  /* A pill can change state under the reader (a poll lands, an assignment tips overdue). For
     anyone who has asked for less motion, that change is instant rather than a crossfade. */
  @media (prefers-reduced-motion: reduce) {
    .pill {
      transition: none;
    }
  }
</style>
