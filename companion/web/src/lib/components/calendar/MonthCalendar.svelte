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

  function toToday() {
    offset = 0
    chosen = today
  }
</script>

<div class="own-month" data-print-root="month">
  <div class="head">
    <h3 class="title" aria-live="polite">{month.title}</h3>
    <div class="controls">
      <button type="button" onclick={() => (offset -= 1)}>Previous month</button>
      <button type="button" onclick={toToday}>Today</button>
      <button type="button" onclick={() => (offset += 1)}>Next month</button>
      <button type="button" onclick={() => printMonth(browserPrintPort())}>Print this month</button>
    </div>
  </div>

  <div class="split">
    <div class="sheet">
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
                        onclick={() => (chosen = cell.day)}
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

    <div class="panel">
      {#if chosen === null}
        <p class="prompt">Choose a day to see what was recorded on it.</p>
      {:else}
        <h4 class="panel-day">{formatDayLong(chosen)}</h4>
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
      {/if}
    </div>
  </div>
</div>

<style>
  .own-month {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-4);
  }

  .title {
    font-size: 1.05rem;
  }

  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .controls button {
    padding: var(--space-1) var(--space-3);
    font-size: 0.85rem;
  }

  /* Grid beside the panel on a wide window, stacked on a narrow one. */
  .split {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 15rem;
    gap: var(--space-4);
    align-items: start;
  }

  @media (max-width: 56rem) {
    .split {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  .sheet {
    min-width: 0;
  }

  /* On a narrow screen the month keeps a readable width and scrolls inside its own box, so a
     person's words are never crushed into a column too thin to read. */
  .scroll {
    overflow-x: auto;
  }

  .grid {
    width: 100%;
    min-width: 30rem;
    border-collapse: separate;
    border-spacing: 3px;
    table-layout: fixed;
  }

  .grid th {
    padding: 0 0 var(--space-1);
    text-align: center;
    font-weight: 400;
  }

  /* The 1px height is what lets a day fill its cell: every day in a week is as tall as the fullest
     one, so no day is drawn smaller for holding less. */
  .grid td {
    height: 1px;
    padding: 0;
    vertical-align: top;
  }

  /* Every day is this square, whatever it holds: the same paper, the same edge, the number in the
     same ink. Nothing here is chosen by what a day holds. */
  .day {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    justify-content: flex-start;
    gap: var(--space-1);
    width: 100%;
    height: 100%;
    min-height: 4.75rem;
    padding: var(--space-1) var(--space-2) var(--space-2);
    background: var(--paper-sheet);
    border: 1px solid var(--hairline);
    border-radius: var(--radius-sm);
    color: var(--ink-text);
    text-align: left;
  }

  .day:hover {
    border-color: var(--border-strong);
  }

  .num {
    font-size: 0.8rem;
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

  /* Today, and the chosen day: structure, so both are indigo. Neither says anything about what the
     day holds — a ringed day with nothing on it is still a day with nothing on it. */
  .day.today {
    border-color: var(--indigo);
  }

  .day.chosen {
    background: var(--indigo-wash);
    border-color: var(--indigo);
    box-shadow: inset 0 0 0 1px var(--indigo);
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

  /* ---- the day panel: the chosen day in words ---- */

  .panel {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3);
    background: var(--paper-bg);
    border: 1px solid var(--hairline);
    border-radius: var(--radius-sm);
  }

  .prompt {
    margin: 0;
    font-size: 0.85rem;
    color: var(--ink-soft);
  }

  .panel-day {
    font-size: 0.95rem;
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

    .day,
    .day.today,
    .day.chosen {
      background: transparent;
      border-color: var(--ink-faint);
      box-shadow: none;
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
