<script lang="ts">
  /*
   * A MOOD MARK — one mood a person reported, as a filled square in that mood's colour, with the
   * mood's word beside it (#280, #335).
   *
   * The only component that draws a mood's colour in HTML, and it never draws one alone: the square
   * and the word are one element's two children, so wherever a mood's colour appears its word is
   * next to it and the colour is never the only signal. The square is aria-hidden; the word is what
   * is read.
   *
   * The colour is `--mood-fill`: the shipped ramp, set per level below, unless the person chose
   * their own colour for that level, which comes out of their palette through `ownMoodFill` onto
   * the square alone — a leaf, so nothing inherits it — and wins. A person's colour fills this
   * square and nothing else; components/ownMoodColour.tree.test.ts holds the tree to that.
   */
  import { ownMoodFill, type MoodLevel, type OwnPalette } from '../../mood'

  let { level, word, palette }: { level: MoodLevel; word: string; palette: OwnPalette } = $props()
</script>

<span class="mood"><span class="mood-mark" data-level={level} aria-hidden="true" style:--mood-fill={ownMoodFill(level, palette)}></span><span class="mood-word">{word}</span></span>

<style>
  .mood {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    min-width: 0;
    max-width: 100%;
  }

  /* DATA. The shipped ramp, per level: what a mood looks like until the person says otherwise. */
  .mood-mark[data-level='1'] { --mood-fill: var(--mood-1); }
  .mood-mark[data-level='2'] { --mood-fill: var(--mood-2); }
  .mood-mark[data-level='3'] { --mood-fill: var(--mood-3); }
  .mood-mark[data-level='4'] { --mood-fill: var(--mood-4); }
  .mood-mark[data-level='5'] { --mood-fill: var(--mood-5); }

  /* The same 7px square as the check-in's shape in the legend, filled with the mood. */
  .mood-mark {
    flex: none;
    width: 7px;
    height: 7px;
    background: var(--mood-fill);
  }

  /* The word is ink, always; it is the reading, and the square only repeats it. */
  .mood-word {
    min-width: 0;
    overflow-wrap: anywhere;
    font-size: 0.75rem;
    line-height: 1.25;
    color: var(--ink-text);
  }

  /* A browser leaves backgrounds off the paper by default, which would print the word beside no
     square. The square prints as drawn. */
  @media print {
    .mood-mark {
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
    }
  }
</style>
