<script lang="ts">
  /*
   * THE CODE, ONCE, LARGE ENOUGH TO COPY ONTO PAPER WITHOUT MISREADING IT.
   *
   * ─── EVERY DECISION HERE IS ABOUT TRANSCRIPTION, NOT ABOUT LOOKS ──────────────────────────────
   *
   * Thirty characters get read off this screen, written by hand, put in a drawer, and read back
   * months or years later by somebody under stress. Each choice below removes one way that goes
   * wrong, and the ways it goes wrong are known:
   *
   *   MONOSPACE, and the tabular numeric face. A proportional font renders the same string with
   *   different letter widths, and the eye loses its place in a random string when the rhythm is
   *   uneven. A mono face gives every character the same box, so counting to the fourth character
   *   of a group is a glance rather than a count.
   *
   *   LARGE, and with letter-spacing. This is not a title, it is a transcription source. The size
   *   is set in rem so a person who has enlarged their browser text gets a larger code, and the
   *   letter-spacing separates adjacent glyphs so that RN does not read as M and VV does not read
   *   as W. Those two pairs are not in the alphabet's excluded set — the alphabet fixed 0/O and
   *   1/I/L, and the check character covers the rest — but spacing removes them at no cost.
   *
   *   NUMBERED GROUPS. Every diagnosis this feature can produce says "group 3": the write-down
   *   check asks for a group by number, and the entry screen reports a bad character by group and
   *   offset. That vocabulary is worth nothing if the groups are not numbered where the person
   *   reads them, and worse than nothing if they are numbered here and not on the paper — so the
   *   number is above the group in a chrome micro-label, which is what someone copying carefully
   *   will reproduce as a row of six.
   *
   *   NO SELECTION AID, NO COPY BUTTON. There is deliberately no "copy to clipboard" control on
   *   this component. The clipboard is a system-wide store read by any application on the machine
   *   and, on some platforms, synchronised to other devices; putting a key that opens years of
   *   somebody's journal into it — to save a person fifteen seconds of typing they are being asked
   *   to do precisely because typing it makes the paper exist — is a bad trade twice over.
   *
   *   IT WRAPS, AND IT SCROLLS ITS OWN BOX. On a narrow screen the six groups wrap into rows
   *   rather than shrinking, because a code shrunk to fit a phone is a code transcribed wrong.
   *
   * ─── WHAT THIS COMPONENT DOES NOT DO ──────────────────────────────────────────────────────────
   *
   * It does not decide when the code is visible, does not generate it, does not save it and does
   * not know what happens next. It is handed a code and draws it. The single-use property lives in
   * the flow that owns the state, which is the only place it can live.
   */
  import { IF_BOTH_ARE_LOST, PRINT_SHEET_CAVEAT, SHOWN_ONCE, WHAT_THIS_OPENS } from './copy'

  let { display }: { display: string } = $props()

  /*
   * The groups as the person will copy them. Split from the display form rather than re-derived
   * from the canonical one, so that what is drawn is provably what formatRecoveryCode() produced —
   * there is no second opinion here about where the boundaries fall.
   */
  const groups = $derived(display.split('-'))
</script>

<div class="sheet">
  <!--
    A print-only heading. On screen the surrounding flow says what this is; on paper this box may
    be the only thing anybody sees in five years' time, cut out of a page whose context is gone.
  -->
  <p class="printed-only">{WHAT_THIS_OPENS}</p>

  <div class="groups u-scroll-x">
    {#each groups as group, i}
      <div class="group">
        <span class="u-label">Group {i + 1}</span>
        <!--
          `aria-label` spells the group out character by character. A screen reader handed
          "K7MQ2" as a word makes a sound, and the person is trying to write down letters.
        -->
        <span class="symbols" aria-label={`Group ${i + 1}: ${group.split('').join(' ')}`}>
          {group}
        </span>
      </div>
    {/each}
  </div>

  <p class="cost">{IF_BOTH_ARE_LOST}</p>
  <p class="once">{SHOWN_ONCE}</p>
  <!-- Screen-hidden, printed. See PRINT_SHEET_CAVEAT: the paper outlives the build. -->
  <p class="printed-only">{PRINT_SHEET_CAVEAT}</p>
</div>

<style>
  .sheet {
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    background: var(--paper-sheet);
    padding: var(--space-5);
    color: var(--ink-text);
  }

  .groups {
    display: flex;
    flex-wrap: wrap;
    /* Generous, because the gap between groups is the only thing telling a copier where one group
       ends — the hyphens of the display form are not drawn, they are this space. */
    gap: var(--space-3) var(--space-5);
    padding-bottom: var(--space-2);
  }

  .group {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .symbols {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    /* 1.6rem at the default root size. Set in rem rather than px so browser text zoom enlarges it,
       which is the accessibility case AND the transcription case at once. */
    font-size: 1.6rem;
    line-height: 1.3;
    letter-spacing: 0.18em;
    color: var(--ink-text);
    /* The trailing letter-space would otherwise push the group off-centre against its label. */
    text-indent: 0;
  }

  .cost {
    margin: var(--space-4) 0 0;
    max-width: 44rem;
    font-size: 0.9rem;
    line-height: 1.55;
    color: var(--ink-text);
  }

  .once {
    margin: var(--space-2) 0 0;
    max-width: 44rem;
    font-size: 0.85rem;
    line-height: 1.55;
    color: var(--ink-soft);
  }

  /* Not drawn on screen, drawn on paper. Both sentences are context the printed sheet loses. */
  .printed-only {
    display: none;
  }

  @media print {
    .sheet {
      border-color: var(--ink-soft);
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
    }

    .printed-only {
      display: block;
      margin: 0 0 var(--space-3);
      max-width: none;
      font-size: 0.85rem;
      line-height: 1.5;
      color: var(--ink-text);
    }

    .printed-only ~ .printed-only {
      margin: var(--space-3) 0 0;
    }

    /* Nothing on a printed sheet should be dimmed: a photocopy or a fading inkjet takes the light
       greys out entirely, and every sentence here is one the reader needs. */
    .once {
      color: var(--ink-text);
    }
  }
</style>
