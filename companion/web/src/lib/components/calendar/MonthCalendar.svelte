<script lang="ts">
  /*
   * THE PERSON'S OWN MONTH (#335) — a read-only calendar of what they recorded, from their own
   * backup. It lives in the Dashboard, which is what both views of a person's own data mount: the
   * backup viewer and the owner console's Review tab. The clinician's view of a share mounts the
   * Dashboard too and turns this off (`ownData={false}`); a clinician has their own calendar,
   * which draws no mood's colour.
   *
   * A projector. calendar/ownMonth.ts decides what each day shows and what a screen reader hears,
   * over the grid and the bucketing it shares with the clinician's calendar (calendar/core.ts);
   * calendar/print.ts puts the month on paper. This file draws what they return:
   *
   *  · Every day is the same square with its number in ink. A day with nothing on it is that
   *    square with no marks: nothing dimmer, dotted, struck or counted. The day's button is the
   *    same element with the same attributes whatever the day holds; only the marks differ.
   *  · A check-in is its mood's square beside its word (MoodMark.svelte), in the person's own
   *    colour where they chose one. A journal entry, a sleep log and a self-check are their kind's
   *    shape in ink (CalendarMark.svelte): ring, bar, slash — never a tick.
   *  · Nothing is graded or totalled: no average, no best or worst day, no count, no arrow.
   *  · Choosing a day lists its records in the panel beside the grid: the calendar in words.
   *  · "Print this month" prints the month as it is, ink on white, each square keeping its word;
   *    the panel and the controls are left off the paper.
   *
   * `now` is read once, when the month is opened, to find today; a calendar is not a stopwatch.
   *
   * THE CHRONICLE LOOK (the signed-off Companion mockup). One soft card with a faint light in its
   * corner, the month's name large in the display serif, an open grid of hairlines, and the date in
   * a circle, filled in ink for today. Choosing a day opens its card from that day: beside it on a
   * wide screen, above or below it on a narrow one, with the rest of the month faded back. Escape,
   * the close button or a click anywhere else closes it, and focus goes back to the day. Changing
   * month fades from one to the next, and not at all when the person asks for less motion.
   */
  import type { BackupData } from '../../backup'
  import {
    EVENT_KINDS,
    KIND_LABEL,
    KIND_SHAPE,
    SHAPE_LABEL,
    addMonths,
    bucketByDay,
    buildEvents,
    civilFromDays,
    epochDayLocal,
    formatDayLong,
    type EpochDay,
  } from '../../calendar/core'
  import { buildOwnMonth, dayRecords } from '../../calendar/ownMonth'
  import { browserPrintPort, printMonth } from '../../calendar/print'
  import { ownMoodColours } from '../../mood'
  import CalendarMark from './CalendarMark.svelte'
  import MoodMark from './MoodMark.svelte'

  let { data, now = Date.now() }: { data: BackupData; now?: number } = $props()

  const today = $derived(epochDayLocal(now))
  const buckets = $derived(bucketByDay(buildEvents(data)))
  const palette = $derived(ownMoodColours(data.moodColors))

  /* Which month is on screen, as an offset from today's month, so "Today" is always one step. */
  let offset = $state(0)
  const view = $derived.by(() => {
    const { year, month } = civilFromDays(today)
    return addMonths(year, month, offset)
  })
  const month = $derived(buildOwnMonth(buckets, data.moodLabels, view.year, view.month, today))

  /* Nothing is chosen until the person chooses: the panel does not open on a verdict about today. */
  let chosen = $state<EpochDay | null>(null)
  const records = $derived(chosen === null ? [] : dayRecords(buckets.get(chosen)?.events ?? [], data.moodLabels))

  /* Where the day card sits, in the month card's own coordinates. */
  let cardEl = $state<HTMLElement | null>(null)
  let rootEl = $state<HTMLElement | null>(null)
  let from: HTMLElement | null = null
  let pos = $state({ left: 0, top: 0, width: 0 })
  let fading = $state(false)

  const reducedMotion = () =>
    typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches

  function place(dayEl: HTMLElement) {
    if (!rootEl) return
    const box = rootEl.getBoundingClientRect()
    const d = dayEl.getBoundingClientRect()
    const gap = 8
    const narrow = box.width < 640
    const width = narrow ? box.width - 2 * gap : Math.min(304, box.width - 2 * gap)
    let left: number
    let top: number
    if (narrow) {
      left = gap
      // Below the day when the card fits there, otherwise above it.
      const below = d.bottom - box.top + gap
      top = below + 320 < box.height ? below : Math.max(gap, d.top - box.top - 320 - gap)
    } else {
      const right = d.right - box.left + gap
      left = right + width + gap <= box.width ? right : Math.max(gap, d.left - box.left - width - gap)
      top = Math.max(gap, Math.min(d.top - box.top, box.height - 340))
    }
    pos = { left, top, width }
  }

  function choose(day: EpochDay, el: HTMLElement) {
    from = el
    chosen = day
    place(el)
    queueMicrotask(() => cardEl?.querySelector<HTMLElement>('.panel-day')?.focus())
  }

  function close() {
    if (chosen === null) return
    chosen = null
    from?.focus()
  }

  function step(n: number) {
    chosen = null
    if (reducedMotion()) {
      offset += n
      return
    }
    fading = true
    setTimeout(() => {
      offset += n
      fading = false
    }, 160)
  }

  function toToday() {
    chosen = null
    offset = 0
  }

  $effect(() => {
    if (chosen === null) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && close()
    const onDown = (e: PointerEvent) => {
      const t = e.target as Node
      if (cardEl?.contains(t)) return
      if (t instanceof Element && t.closest('.day')) return
      close()
    }
    window.addEventListener('keydown', onKey)
    window.addEventListener('pointerdown', onDown)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('pointerdown', onDown)
    }
  })
</script>

<div class="own-month" data-print-root="month" bind:this={rootEl}>
  <div class="glow" aria-hidden="true"></div>
  <div class="head">
    <span class="kicker">Your month</span>
    <div class="hrow">
      <h3 class="title" aria-live="polite">{month.title}</h3>
      <div class="controls">
        <button type="button" class="ib" aria-label="Previous month" onclick={() => step(-1)}>‹</button>
        <button type="button" class="tbtn" onclick={toToday}>Today</button>
        <button type="button" class="ib" aria-label="Next month" onclick={() => step(1)}>›</button>
        <button type="button" class="tbtn" onclick={() => printMonth(browserPrintPort())}>Print this month</button>
      </div>
    </div>
    <p class="prompt">Choose a day to see what was recorded on it.</p>
  </div>

  <div class="split">
    <div class="sheet" class:fading class:dim={chosen !== null}>
      <div class="scroll">
        <table class="grid">
          <caption class="visually-hidden">
            {month.title}. Each day is a button that names what was recorded on it.
          </caption>
          <thead>
            <tr>
              {#each month.weekdayLabels as label (label)}
                <th scope="col"><span class="u-label">{label}</span></th>
              {/each}
            </tr>
          </thead>
          <tbody>
            {#each month.weeks as week, w (w)}
              <tr>
                {#each week as cell, i (i)}
                  <td>
                    {#if cell}
                      <button
                        type="button"
                        class="day"
                        class:today={cell.isToday}
                        class:chosen={cell.day === chosen}
                        aria-pressed={cell.day === chosen}
                        aria-label={cell.label}
                        onclick={(e) => choose(cell.day, e.currentTarget)}
                      >
                        <span class="num" aria-hidden="true">{cell.dayOfMonth}</span>
                        <span class="marks" aria-hidden="true">
                          {#each cell.marks as mark, m (m)}
                            {#if mark.mood}
                              <MoodMark level={mark.mood.level} word={mark.mood.word} {palette} />
                            {:else}
                              <CalendarMark shape={KIND_SHAPE[mark.kind]} />
                            {/if}
                          {/each}
                        </span>
                      </button>
                    {/if}
                  </td>
                {/each}
              </tr>
            {/each}
          </tbody>
        </table>
      </div>

      <ul class="legend">
        {#each EVENT_KINDS as kind (kind)}
          <li>
            <CalendarMark shape={KIND_SHAPE[kind]} />
            <span class="legend-kind">{KIND_LABEL[kind]}</span>
            <span class="legend-shape">{SHAPE_LABEL[KIND_SHAPE[kind]]}</span>
          </li>
        {/each}
      </ul>
    </div>

    {#if chosen !== null}
      <div
        class="panel"
        role="dialog"
        aria-label="What was recorded on this day"
        bind:this={cardEl}
        style:left="{pos.left}px"
        style:top="{pos.top}px"
        style:width="{pos.width}px"
      >
        <div class="crease">
          <span>The day</span>
          <button type="button" class="x" aria-label="Close" onclick={close}>×</button>
        </div>
        <div class="card-body">
          <h4 class="panel-day" tabindex="-1">{formatDayLong(chosen)}</h4>
          {#if records.length === 0}
            <p class="nothing">Nothing recorded.</p>
          {:else}
            <ul class="records">
              {#each records as record (record.key)}
                <li class="record">
                  <span class="kind">{KIND_LABEL[record.kind]}</span>
                  {#if record.time}<span class="when u-mono">{record.time}</span>{/if}
                  {#if record.mood}<MoodMark level={record.mood.level} word={record.mood.word} {palette} />{/if}
                  {#if record.text}<span class="what">{record.text}</span>{/if}
                </li>
              {/each}
            </ul>
          {/if}
        </div>
      </div>
    {/if}
  </div>
</div>

<style>
  /* One soft card, with a faint cool light in its top corner. It is the positioning frame for the
     day card, which opens over the month rather than beside it. */
  .own-month {
    position: relative;
    isolation: isolate;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-4) var(--space-4) var(--space-3);
    background: var(--paper-sheet);
    border: 1px solid var(--hairline);
    border-radius: 20px;
    box-shadow: var(--elevation);
  }

  .glow {
    position: absolute;
    inset: 0;
    z-index: -1;
    border-radius: inherit;
    pointer-events: none;
    background: radial-gradient(90% 70% at 8% -8%, color-mix(in srgb, var(--indigo-wash) 70%, transparent), transparent 62%);
  }

  .head {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .hrow {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-3);
  }

  .kicker {
    font-size: 0.68rem;
    font-weight: 650;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--ink-soft);
  }

  .title {
    margin: 0;
    font-family: var(--font-display);
    font-weight: 500;
    font-size: clamp(1.6rem, 4vw, 2.2rem);
    line-height: 1.08;
    letter-spacing: -0.012em;
  }

  .prompt {
    margin: 0;
    font-size: 0.86rem;
    color: var(--ink-soft);
  }

  .controls {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 2px;
  }

  .controls button {
    border: 0;
    background: transparent;
    color: var(--ink-soft);
    transition: background-color 200ms ease, color 200ms ease;
  }

  .controls button:hover {
    background: var(--paper-bg);
    color: var(--ink-text);
  }

  .ib {
    width: 2.5rem;
    height: 2.5rem;
    padding: 0;
    border-radius: 50%;
    font-size: 1.25rem;
    line-height: 1;
  }

  .tbtn {
    min-height: 2.25rem;
    padding: 0 0.8rem;
    border-radius: 9px;
    font-size: 0.85rem;
    font-weight: 550;
  }

  .split {
    display: block;
  }

  .sheet {
    min-width: 0;
    transition: opacity 320ms ease;
  }

  /* Changing month fades out, swaps, and fades back in. */
  .sheet.fading {
    opacity: 0;
  }

  /* With a day open, the rest of the month steps back; the open day keeps full ink. */
  .sheet.dim .day:not(.chosen) {
    opacity: 0.5;
  }

  @media (prefers-reduced-motion: reduce) {
    .sheet,
    .day {
      transition: none;
    }
  }

  /* On a narrow screen the month keeps a readable width and scrolls inside its own box, so a
     person's words are never crushed into a column too thin to read. */
  .scroll {
    overflow-x: auto;
  }

  .grid {
    width: 100%;
    min-width: 30rem;
    border-collapse: collapse;
    table-layout: fixed;
  }

  .grid th {
    padding: var(--space-2) var(--space-2) var(--space-2);
    text-align: left;
    font-size: 0.64rem;
    font-weight: 650;
    letter-spacing: 0.13em;
    text-transform: uppercase;
    color: var(--ink-soft);
  }

  /* An open grid of hairlines: one across the top of each week, one between days. The 1px height
     lets a day fill its cell, so every day in a week is as tall as the fullest one. */
  .grid td {
    height: 1px;
    padding: 0;
    vertical-align: top;
    border-top: 1px solid var(--hairline);
    border-left: 1px solid var(--hairline);
  }

  .grid td:first-child {
    border-left-color: transparent;
  }

  /* Every day is this cell, whatever it holds: the same ground, the number in the same ink.
     Nothing here is chosen by what a day holds. */
  .day {
    position: relative;
    isolation: isolate;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    justify-content: flex-start;
    gap: 7px;
    width: 100%;
    height: 100%;
    min-height: 5.25rem;
    padding: 8px 6px 6px 9px;
    background: transparent;
    border: 0;
    border-radius: 0;
    color: var(--ink-text);
    text-align: left;
    transition: opacity 320ms ease;
  }

  .day::before {
    content: '';
    position: absolute;
    inset: 3px;
    z-index: -1;
    border-radius: 10px;
    background: var(--paper-bg);
    opacity: 0;
    transition: opacity 200ms ease;
  }

  .day:hover::before {
    opacity: 0.85;
  }

  .num {
    display: grid;
    place-items: center;
    width: 26px;
    height: 26px;
    margin: -3px 0 0 -5px;
    border-radius: 50%;
    font-size: 0.82rem;
    font-weight: 500;
    line-height: 1;
    font-variant-numeric: tabular-nums;
    color: var(--ink-text);
  }

  .marks {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 3px 6px;
    min-width: 0;
  }

  /* Today is the date filled in ink; the open day is a soft ground with an edge. Both are
     structure. Neither says anything about what the day holds. */
  .day.today .num {
    background: var(--ink-accent);
    color: var(--on-accent);
    font-weight: 650;
  }

  .day.chosen::before {
    opacity: 1;
    box-shadow: inset 0 0 0 1px var(--border-strong);
  }

  /* ---- legend: the shapes, named in words, so the month reads in greyscale and on paper ---- */

  .legend {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-5);
    margin: var(--space-3) 0 0;
    padding: var(--space-3) 0 0;
    border-top: 1px solid var(--hairline);
    list-style: none;
  }

  .legend li {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    color: var(--ink-text);
  }

  .legend-kind {
    font-size: 0.85rem;
  }

  .legend-shape {
    font-family: var(--font-mono);
    font-size: 10px;
    letter-spacing: 0.06em;
    color: var(--chrome-soft);
  }

  /* ---- the day card: the chosen day in words, opened from the day ---- */

  .panel {
    position: absolute;
    z-index: 20;
    max-height: 24rem;
    overflow: auto;
    overscroll-behavior: contain;
    background: var(--paper-sheet);
    border: 1px solid var(--hairline);
    border-radius: 14px;
    box-shadow: var(--elevation);
    animation: grow 320ms cubic-bezier(0.2, 0, 0, 1) both;
  }

  @keyframes grow {
    from { opacity: 0; transform: scale(0.96); }
    to { opacity: 1; transform: none; }
  }

  @media (prefers-reduced-motion: reduce) {
    .panel { animation: none; }
  }

  .crease {
    position: sticky;
    top: 0;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    min-height: 2.5rem;
    padding: 4px 6px 4px 16px;
    background: var(--paper-sheet);
    border-bottom: 1px dashed var(--hairline);
    font-size: 0.66rem;
    font-weight: 650;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--ink-soft);
  }

  .x {
    margin-left: auto;
    width: 2rem;
    height: 2rem;
    padding: 0;
    border: 0;
    border-radius: 8px;
    background: transparent;
    color: var(--ink-soft);
    font-size: 1.15rem;
    line-height: 1;
  }

  .x:hover {
    background: var(--paper-bg);
    color: var(--ink-text);
  }

  .card-body {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4) var(--space-4);
  }

  .panel-day {
    margin: 0;
    font-family: var(--font-display);
    font-weight: 500;
    font-size: 1.4rem;
    line-height: 1.12;
    outline: none;
  }

  .nothing {
    margin: 0;
    font-size: 0.9rem;
    color: var(--ink-text);
  }

  .records {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .record {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: var(--space-1) var(--space-2);
    padding-bottom: var(--space-2);
    border-bottom: 1px solid var(--hairline);
  }

  .kind {
    font-size: 0.9rem;
    color: var(--ink-text);
  }

  .when {
    font-size: 0.8rem;
    color: var(--text-subtle);
  }

  .what {
    font-size: 0.85rem;
    color: var(--ink-soft);
    overflow-wrap: anywhere;
  }

  /* ---- paper ----
     The month as it is, ink on white: calendar/print.ts switches the page to the light theme for
     the print, and nothing here spends a background on paper. The day panel and the controls are
     left off; so is the screen's today ring and chosen day, which are the screen's own state. */
  @media print {
    .controls,
    .panel {
      display: none;
    }

    .split {
      display: block;
    }

    .scroll {
      overflow: visible;
    }

    .grid {
      min-width: 0;
    }

    .grid tr {
      break-inside: avoid;
    }

    .own-month {
      box-shadow: none;
      border-color: var(--ink-faint);
    }

    .glow {
      display: none;
    }

    .day::before {
      opacity: 0;
    }

    .grid td {
      border-color: var(--ink-faint);
    }

    .day.today .num {
      background: transparent;
      color: var(--ink-text);
    }

    .sheet.dim .day:not(.chosen) {
      opacity: 1;
    }

    /* While the month is printing, everything that neither is the month, holds it, nor sits inside
       it is left off the paper, and what holds it keeps no frame of its own. Keyed to the flag
       calendar/print.ts sets, so printing the page any other way prints it as it always has. */
    :global(html[data-printing='month'] body *:not([data-print-root], [data-print-root] *, :has([data-print-root]))) {
      display: none !important;
    }

    :global(html[data-printing='month'] body :has([data-print-root])) {
      margin: 0 !important;
      padding: 0 !important;
      border: 0 !important;
      box-shadow: none !important;
      background: none !important;
      gap: 0 !important;
      max-width: none !important;
      min-height: 0 !important;
    }
  }
</style>
