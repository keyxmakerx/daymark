<script lang="ts">
  /*
   * The one table. A chrome-layer head over content-layer rows.
   *
   * WHY IT EXISTS. Six screens had grown their own <table>, and they disagreed about
   * everything a table is judged on: header casing, row separators, where numbers aligned,
   * whether a wide table pushed the whole page sideways. One component makes the head always
   * the chrome micro-label (mono, uppercase, tracked out — a field name, not prose), the rows
   * always warm content on paper, and the boundary between the machine's labels and a person's
   * data legible without reading a word.
   *
   * WHY tabular-nums ON NUMERIC COLUMNS IS NOT DECORATION. This is the part of the file that
   * is about correctness rather than taste. In proportional digits a column of scores — 9, 12,
   * 118 — sets at three different widths, so a reader scanning down is comparing glyph runs
   * instead of magnitudes, and a date column ragged in the same way makes "2026-08-11" and
   * "2026-08-01" cost real effort to tell apart. Mono + tabular-nums puts every digit on the
   * same advance width, so the column becomes a ruler. Right-alignment finishes the job: it is
   * what makes the units digits stack, which tabular figures alone cannot do for numbers of
   * unequal length. A numeric column that loses either half is a misreading waiting to happen.
   *
   * WHY THE HEAD IS NOT PAINTED --chrome. It is a chrome-layer head by type treatment, not by
   * fill. --chrome-soft at 10px on the --chrome ground falls under the readable contrast ratio
   * (the same trap noted in Chip.svelte); on paper it clears it. The head earns its separation
   * from the body with a --chrome-hair rule, one step stronger than the --hairline that
   * divides rows, so "where the labels stop and the data starts" is unambiguous.
   *
   * WHY HOVER IS INDIGO AND NEVER A MOOD WASH. A hovered row is interface state — where the
   * pointer happens to be — and the mood ramp encodes a person's reported experience and
   * nothing else (app.css, invariant 1). A row tinted with a wash from that ramp would read as
   * a claim about that row's contents, and a reader who has learned the legend would be right
   * to believe it. So the tint is a mix of --indigo-wash, the structural accent, which asserts
   * nothing about the data. The ramp's own token names are kept out of this file entirely —
   * comments included, following Card.svelte — so the "only BandTag references the ramp" grep
   * stays a simple one. This file also imports neither StatusPill nor BandTag: anything that
   * needs the data ramp arrives through the `cell` snippet, from the caller.
   *
   * NO GREEN, INCLUDING BY OMISSION. There is no "ok" row variant and no success column style.
   * A healthy row is an unpainted row — see app.css, invariant 2.
   *
   * WHY THE SCROLLER IS PART OF THE COMPONENT. A wide table has to scroll inside its own box,
   * because the alternative is the page body scrolling sideways and the navigation leaving the
   * screen. Leaving that to the caller means it is correct on five screens and forgotten on
   * the sixth, so the container ships with the table.
   */
  import type { Snippet } from 'svelte'
  import type { Column } from './table'

  let {
    columns,
    rows,
    caption,
    cell,
  }: {
    columns: Column[]
    rows: Record<string, unknown>[]
    caption?: string
    cell?: Snippet<[string, Record<string, unknown>]>
  } = $props()

  /*
   * The fallback renderer, used only when no `cell` snippet is given.
   *
   * A missing value renders as nothing rather than as the strings "null" or "undefined": an
   * absent fact should look absent, and printing the machine's word for absence into a cell is
   * a small lie about what was recorded. Objects are stringified defensively (JSON.stringify
   * throws on cycles and returns undefined for functions) so one odd value degrades to a blank
   * cell instead of taking the page down. Anything richer than a scalar belongs in the `cell`
   * snippet, where the caller knows what it means.
   */
  function display(value: unknown): string {
    if (value === null || value === undefined) return ''
    if (value instanceof Date) return value.toISOString()
    if (typeof value === 'object') {
      try {
        return JSON.stringify(value) ?? ''
      } catch {
        return ''
      }
    }
    return String(value)
  }

  /*
   * Is anything actually hidden off the right edge?
   *
   * This drives the keyboard affordance, and it is measured rather than assumed because both
   * of the cheap alternatives are wrong. Never making the scroller focusable leaves content a
   * keyboard user cannot reach in any engine that does not focus overflow containers on its
   * own. Always making it focusable puts a dead tab stop in front of every table on the page,
   * including the narrow ones that have nothing to scroll — and a keyboard user tabbing
   * through five of those learns to distrust the tab key, which costs more than it buys.
   * Focusable exactly when there is something off-screen is the only version that is honest in
   * both directions.
   *
   * Reading `rows` and `columns` re-runs the effect when the table's shape changes (effects
   * run after the DOM is updated, so the measurement sees the new layout); the observer covers
   * the other half, where the box changes size but the data did not.
   */
  let scroller = $state<HTMLDivElement | null>(null)
  let overflows = $state(false)

  $effect(() => {
    void rows.length
    void columns.length

    const el = scroller
    if (!el) return

    // A one-pixel tolerance: sub-pixel layout rounding otherwise reports a permanent overflow
    // on tables that fit exactly, which would reintroduce the dead tab stop.
    const measure = () => {
      overflows = el.scrollWidth - el.clientWidth > 1
    }

    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    return () => observer.disconnect()
  })
</script>

<!--
  Every attribute here is conditional, and on two different conditions.

  The tabindex appears only while something is genuinely off the right edge (see the effect
  above): a scroll container a mouse can reach and a keyboard cannot is content a keyboard user
  cannot read, and the WAI remedy is to let the scroller take focus so the arrow keys can drive
  it. Some engines now do this for overflow containers unprompted, but not all of them, and
  "most browsers" is not a standard worth holding keyboard access to. The lint suppressed below
  is the known false positive for exactly this shape: the rule fires because a <div
  role="region"> is not an interactive widget, which is true and beside the point — this is not
  a control, it is a scrollable area, and it takes focus in order to be scrolled, not activated.

  The landmark needs both an overflow to justify it and a caption to name it. A region that
  announces itself and then cannot say what it holds is worse than no region; and a landmark
  around every table on the page, scrollable or not, is noise — the table's own <caption> is
  what names the table to a screen reader.
-->
<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<div
  class="scroller"
  bind:this={scroller}
  role={overflows && caption ? 'region' : undefined}
  aria-label={overflows ? caption : undefined}
  tabindex={overflows ? 0 : undefined}
>
  <table>
    {#if caption}
      <caption class="visually-hidden">{caption}</caption>
    {/if}

    <thead>
      <tr>
        {#each columns as column (column.key)}
          <th scope="col" class:numeric={column.numeric} style:width={column.width}>
            {column.label}
          </th>
        {/each}
      </tr>
    </thead>

    <tbody>
      {#each rows as row}
        <tr>
          {#each columns as column (column.key)}
            <td class:numeric={column.numeric}>
              {#if cell}{@render cell(column.key, row)}{:else}{display(row[column.key])}{/if}
            </td>
          {/each}
        </tr>
      {/each}
    </tbody>
  </table>
</div>

<style>
  /* The containment promise: wide content scrolls in here, never in the page body. Mirrors
     the .u-scroll-x utility in app.css, kept local so the guarantee cannot be lost by a
     caller forgetting a class. */
  .scroller {
    overflow-x: auto;
    max-width: 100%;
  }

  table {
    width: 100%;
    /* Collapsed, so a row's bottom hairline and the next row's top edge are one line rather
       than two stacked ones that read as a heavier rule at every second row. */
    border-collapse: collapse;
    font-size: 0.875rem;
    color: var(--ink-text);
  }

  /* The chrome micro-label, matching .u-label in app.css: 10px mono, uppercase, .12em
     tracking, --chrome-soft. Field names, not prose. */
  th {
    font-family: var(--font-mono);
    font-size: 10px;
    font-weight: 500;
    line-height: 1.4;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--chrome-soft);
    text-align: left;
    padding: var(--space-2) var(--space-3);
    /* One step stronger than the row hairline — this is the layer boundary. */
    border-bottom: 1px solid var(--chrome-hair);
    /* A header is short by construction; wrapping it into two lines to save a few pixels
       makes the whole head taller and the column no easier to read. Let it push the width
       and let the scroller handle the consequence. */
    white-space: nowrap;
  }

  td {
    padding: var(--space-3);
    border-bottom: 1px solid var(--hairline);
    /* Top, so a cell holding two lines of prose keeps its row's short cells on the first
       baseline instead of floating them into the middle of the row. */
    vertical-align: top;
  }

  /* The table's own bottom edge belongs to whatever contains it (a Card border, a page rule),
     so the last row does not draw one of its own. */
  tbody tr:last-child td {
    border-bottom: 0;
  }

  /* Numeric: dates, scores, counts, IDs — everything read by position rather than as prose.
     See the note in the script block; both halves are load-bearing. */
  .numeric {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    text-align: right;
  }

  /* Structural accent, never the data ramp. Mixed toward transparent rather than used neat so
     the tint reads as "the pointer is here" and not as a second, meaningful row state; the
     hover: hover guard keeps a touch device from leaving a row painted after a tap. */
  @media (hover: hover) {
    tbody tr:hover > td {
      background: color-mix(in srgb, var(--indigo-wash) 55%, transparent);
    }
  }

  tbody tr > td {
    transition: background 120ms ease;
  }

  @media (prefers-reduced-motion: reduce) {
    tbody tr > td {
      transition: none;
    }
  }
</style>
