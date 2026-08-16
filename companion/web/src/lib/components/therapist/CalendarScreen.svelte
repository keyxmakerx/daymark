<script lang="ts">
  /*
   * CALENDAR — month grid, agenda rail, twelve-week ribbon.
   *
   * This component is a projector. Every date calculation, every bucketing decision and every
   * accessible-name string lives in lib/therapist/calendar.ts, which imports neither Svelte nor
   * the DOM and is covered by calendar.test.ts. Nothing here decides anything; it draws.
   *
   * ─────────────────────────────────────────────────────────────────────────
   * WHY `now` IS A PROP AND NOT A CLOCK READ
   *
   * The bug that already shipped in this portal: a $derived that called a function which read
   * Date.now() internally. $derived re-runs when a TRACKED dependency changes, and the clock is
   * not one — so the value was computed once and then lied for the rest of the session. The fix
   * there was an explicit `let clock = $state(Date.now())` ticked by a setInterval.
   *
   * This screen takes the harder line and does not own a clock at all: `now` arrives as a prop,
   * so `today` below is a $derived over a genuinely tracked value. If the caller wants the "today"
   * ring to move across midnight, it ticks the prop; a calendar is not a stopwatch and does not
   * need to tick on its own. There is no Date.now() anywhere in this file, which is the only
   * version of this rule that cannot be re-broken by accident.
   *
   * ─────────────────────────────────────────────────────────────────────────
   * WHY THE MARKS ARE GEOMETRY AND ALL ONE INK
   *
   * A mark is 3–11 px. Hue at that size fails for a large minority of clinicians, fails on a
   * projector, fails in print and fails in a greyscale screenshot pasted into a case note. So the
   * four kinds are told apart by SHAPE — filled square, hollow ring, horizontal bar, diagonal
   * slash — and every mark is drawn in `currentColor`, inheriting the ink of the cell it sits in.
   * Colour is carrying nothing, so nothing is lost when colour is. The legend names each shape in
   * words for the same reason.
   *
   * The self-check mark is a slash, not a check mark: a tick on a mental-health surface reads as
   * "done, and well done", which is the software grading someone.
   *
   * ─────────────────────────────────────────────────────────────────────────
   * WHY THERE IS NO MOOD COLOUR ON THIS SCREEN
   *
   * A calendar of someone's check-ins is the most tempting place in the product to paint the mood
   * ramp, and this file does not, because the ramp is licensed to a short list of data surfaces
   * and this is not one of them. Mood is carried as the WORD the bundle itself supplies ("Meh",
   * "Rad") on the agenda row. That is arguably the better reading anyway: a month of coloured
   * squares invites a clinician to scan for a pattern in a palette rather than read what a person
   * wrote. If the ramp is ever wanted here it is an allowlist decision, not a CSS one.
   *
   * ─────────────────────────────────────────────────────────────────────────
   * WHY AN EMPTY DAY IS BLANK AND STAYS BLANK
   *
   * No red, no dash, no "0", no "no data" label, no shading that implies a hole. A person who
   * recorded nothing for three weeks may have been having the worst month of their life, and a
   * calendar that draws that as a run of failures has made a claim about them on no evidence.
   * Absence renders as absence. The only place absence is spelled out is the screen-reader name,
   * which says "nothing recorded" because a blank cell has to be readable without eyes too.
   *
   * There is no completion figure, no percentage, no streak and no comparison between weeks
   * anywhere on this screen, and the ribbon's own data shape refuses to carry one.
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
    buildMonthGrid,
    buildRibbon,
    civilFromDays,
    describeDay,
    describeWeek,
    epochDayLocal,
    formatClock,
    formatDayLong,
    formatDayShort,
    formatMonthTitle,
    type EpochDay,
    type WeekStart,
  } from '../../therapist/calendar'
  import { Card, Chip, EmptyState, PageHeader, ProvenanceBadge } from '../ui'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'

  let {
    data,
    now,
    weekStart = 'mon',
  }: {
    data: BackupData
    /** Epoch millis. Supplied by the caller — this component never reads the clock. See the note above. */
    now: number
    weekStart?: WeekStart
  } = $props()

  /* Derived from props only, so every one of these re-evaluates when it should. */
  const today = $derived(epochDayLocal(now))
  const events = $derived(buildEvents(data))
  const buckets = $derived(bucketByDay(events))
  const ribbon = $derived(buildRibbon(events, today, weekStart))

  /* Which month is on screen, as an offset from the anchor month rather than as a stored date —
     so "this month" is always the anchor's month even if `now` moves. */
  let monthOffset = $state(0)
  const view = $derived(addMonths(civilFromDays(today).year, civilFromDays(today).month, monthOffset))
  const grid = $derived(buildMonthGrid(view.year, view.month, weekStart))
  const monthTitle = $derived(formatMonthTitle(view.year, view.month))

  /* The agenda follows the selection; the selection starts on the anchor day. */
  let picked = $state<EpochDay | null>(null)
  const selected = $derived(picked ?? today)
  const agenda = $derived(buckets.get(selected)?.events ?? [])

  const monthSpan = $derived({
    first: grid.weeks[0][0].day,
    last: grid.weeks[grid.weeks.length - 1][6].day,
  })
  const inViewCount = $derived(
    events.filter((e) => e.day >= monthSpan.first && e.day <= monthSpan.last).length,
  )

  function step(delta: number) {
    monthOffset += delta
  }

  function toAnchorMonth() {
    monthOffset = 0
    picked = today
  }
</script>

<section class="calendar">
  <NonDiagnosticBanner />

  <PageHeader title="Calendar">
    {#snippet children()}
      <Chip>{monthTitle}</Chip>
      <Chip>{inViewCount} shown</Chip>
    {/snippet}
    {#snippet trailing()}
      <button type="button" onclick={() => step(-1)} aria-label="Previous month">‹</button>
      <button type="button" onclick={toAnchorMonth}>{formatDayShort(today)}</button>
      <button type="button" onclick={() => step(1)} aria-label="Next month">›</button>
    {/snippet}
  </PageHeader>

  <div class="split">
    <div class="col-grid">
      <Card title={monthTitle}>
        <table class="grid">
          <caption class="visually-hidden">
            {monthTitle}. One cell per day; each cell names what was recorded on it.
          </caption>
          <thead>
            <tr>
              {#each grid.weekdayLabels as label (label)}
                <th scope="col"><span class="u-label">{label}</span></th>
              {/each}
            </tr>
          </thead>
          <tbody>
            {#each grid.weeks as week (week[0].day)}
              <tr>
                {#each week as cell (cell.day)}
                  {@const bucket = buckets.get(cell.day)}
                  <td>
                    <button
                      type="button"
                      class="cell"
                      class:out={!cell.inMonth}
                      class:anchor={cell.day === today}
                      class:picked={cell.day === selected}
                      aria-pressed={cell.day === selected}
                      aria-label={describeDay(cell.day, bucket)}
                      onclick={() => (picked = cell.day)}
                    >
                      <span class="num u-mono" aria-hidden="true">{cell.date.day}</span>
                      <span class="marks" aria-hidden="true">
                        {#if bucket}
                          {#each bucket.kinds as kind (kind)}
                            <span class="mark" data-shape={KIND_SHAPE[kind]}></span>
                          {/each}
                        {/if}
                      </span>
                    </button>
                  </td>
                {/each}
              </tr>
            {/each}
          </tbody>
        </table>

        <ul class="legend">
          {#each EVENT_KINDS as kind (kind)}
            <li>
              <span class="mark" data-shape={KIND_SHAPE[kind]} aria-hidden="true"></span>
              <span class="legend-kind">{KIND_LABEL[kind]}</span>
              <span class="legend-shape">{SHAPE_LABEL[KIND_SHAPE[kind]]}</span>
            </li>
          {/each}
        </ul>

        {#snippet footer()}
          A day with no marks is a day with nothing written down. It is drawn blank because that is
          all it means.
        {/snippet}
      </Card>
    </div>

    <div class="col-rail">
      <Card title="Agenda" tone="quiet">
        {#snippet header()}
          <span class="u-mono rail-day">{formatDayLong(selected)}</span>
        {/snippet}

        {#if agenda.length === 0}
          <EmptyState title="Nothing recorded on this day">
            <p class="rail-note">
              No check-in, journal entry, sleep log or self-check carries this date. Pick another day
              in the grid to read it.
            </p>
          </EmptyState>
        {:else}
          <ul class="agenda">
            {#each agenda as event (event.key)}
              {@const measure = event.measure}
              <li class="row">
                <div class="row-head">
                  <span class="mark" data-shape={KIND_SHAPE[event.kind]} aria-hidden="true"></span>
                  <span class="u-label kind">{KIND_LABEL[event.kind]}</span>
                  <span class="u-mono when">{formatClock(event.at)}</span>
                </div>
                <p class="row-title">{event.title}</p>
                {#if event.detail}
                  <p class="row-detail">{event.detail}</p>
                {/if}
                {#if measure}
                  <div class="measure">
                    <span class="u-label">{measure.label}</span>
                    <span class="u-mono value">{measure.value}</span>
                    {#if measure.tier}
                      <ProvenanceBadge tier={measure.tier} />
                    {:else}
                      <span class="source">{measure.source}</span>
                    {/if}
                  </div>
                {/if}
              </li>
            {/each}
          </ul>
        {/if}
      </Card>
    </div>
  </div>

  <Card title="Twelve weeks">
    {#snippet header()}
      <span class="u-label">oldest first</span>
    {/snippet}

    <ol class="ribbon">
      {#each ribbon as week (week.start)}
        <li class="week" class:blank={week.empty}>
          <span class="week-marks" aria-hidden="true">
            {#each week.kinds as kind (kind)}
              <span class="mark" data-shape={KIND_SHAPE[kind]}></span>
            {/each}
          </span>
          <span class="week-label u-mono" aria-hidden="true">{formatDayShort(week.start)}</span>
          <span class="visually-hidden">{describeWeek(week)}</span>
        </li>
      {/each}
    </ol>

    {#snippet footer()}
      Twelve weeks, oldest on the left, showing which kinds of record each week contains. There is
      no rate and no comparison between weeks here; a week with nothing in it is simply blank.
    {/snippet}
  </Card>
</section>

<style>
  .calendar {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
  }

  /* Grid beside rail on a wide window, stacked on a narrow one. The rail is given a fixed
     comfortable measure rather than a fraction, so the agenda's prose does not reflow every
     time the month grid changes width. */
  .split {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 21rem;
    gap: var(--space-4);
    align-items: start;
  }

  .col-grid,
  .col-rail {
    min-width: 0;
  }

  @media (max-width: 62rem) {
    .split {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  /* ---- the marks --------------------------------------------------------
     Four geometries, one ink. `currentColor` is deliberate: a mark takes the colour of whatever
     it sits in, so out-of-month cells fade with their cell and nothing has to be re-themed. No
     mark carries a hue of its own, which is what makes a greyscale screenshot of this screen
     fully readable. */
  .mark {
    display: inline-block;
    flex: none;
    color: inherit;
  }

  .mark[data-shape='square'] {
    width: 7px;
    height: 7px;
    background: currentColor;
  }

  .mark[data-shape='ring'] {
    width: 8px;
    height: 8px;
    border: 1.5px solid currentColor;
    border-radius: 50%;
    background: transparent;
  }

  .mark[data-shape='bar'] {
    width: 11px;
    height: 3px;
    background: currentColor;
    border-radius: 1px;
  }

  /* The slash is a rotated stroke inside a box the same size as the ring, so all four marks sit
     on one baseline grid however they are combined. */
  .mark[data-shape='slash'] {
    position: relative;
    width: 8px;
    height: 8px;
  }

  .mark[data-shape='slash']::before {
    content: '';
    position: absolute;
    left: 50%;
    top: -1px;
    width: 2px;
    height: 10px;
    margin-left: -1px;
    background: currentColor;
    transform: rotate(45deg);
  }

  /* ---- month grid -------------------------------------------------------- */

  .grid {
    width: 100%;
    border-collapse: separate;
    border-spacing: 2px;
    table-layout: fixed;
  }

  .grid th {
    padding: 0 0 var(--space-1);
    text-align: center;
    font-weight: 400;
  }

  .grid td {
    padding: 0;
  }

  /* A cell is a button because it is one: it changes the agenda. Metrics are identical in every
     state so nothing shifts on selection — the day a reader is looking at must not move. */
  .cell {
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    gap: var(--space-1);
    width: 100%;
    min-height: 3.4rem;
    padding: var(--space-1) var(--space-2) var(--space-2);
    background: var(--paper-sheet);
    border: 1px solid var(--hairline);
    border-radius: var(--radius-sm);
    color: var(--ink-text);
    text-align: left;
    transition: background 120ms ease, border-color 120ms ease;
  }

  .cell:hover {
    background: var(--chrome);
    border-color: var(--chrome-hair);
  }

  .num {
    font-size: 0.75rem;
    line-height: 1;
    color: var(--ink-soft);
  }

  /* Marks wrap rather than overflow: a day can hold all four kinds. */
  .marks {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 3px;
    min-height: 8px;
  }

  /* Out-of-month padding: still a real date, still readable, visibly not this month. It is not
     hidden, because a hidden cell would make the first and last weeks look truncated. */
  .cell.out {
    background: transparent;
    border-color: transparent;
    color: var(--ink-faint);
  }

  .cell.out .num {
    color: var(--ink-faint);
  }

  /* Today, and the selected day: structure, so both are indigo. Neither says anything about the
     day's contents — an unmarked ring on an empty day is still an empty day. */
  .cell.anchor {
    border-color: var(--indigo);
  }

  .cell.picked {
    background: var(--indigo-wash);
    border-color: var(--indigo);
    box-shadow: inset 0 0 0 1px var(--indigo);
  }

  /* ---- legend ------------------------------------------------------------ */

  .legend {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-5);
    margin: var(--space-4) 0 0;
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

  /* The shape is named in words, so the legend still works printed, projected or in greyscale. */
  .legend-shape {
    font-family: var(--font-mono);
    font-size: 10px;
    letter-spacing: 0.06em;
    color: var(--chrome-soft);
  }

  /* ---- agenda rail ------------------------------------------------------- */

  .rail-day {
    font-size: 0.8rem;
    color: var(--text-subtle);
  }

  .rail-note {
    margin: 0;
    max-width: 26ch;
    color: var(--ink-soft);
    font-size: 0.85rem;
  }

  .agenda {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .row {
    padding: var(--space-3);
    background: var(--paper-sheet);
    border: 1px solid var(--hairline);
    border-radius: var(--radius-sm);
  }

  .row-head {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    color: var(--ink-text);
  }

  .kind {
    color: var(--chrome-soft);
  }

  .when {
    margin-left: auto;
    font-size: 0.75rem;
    color: var(--text-subtle);
  }

  .row-title {
    margin: var(--space-2) 0 0;
    font-family: var(--font-display);
    font-size: 0.95rem;
    line-height: 1.3;
    color: var(--ink-text);
  }

  .row-detail {
    margin: var(--space-1) 0 0;
    font-size: 0.85rem;
    line-height: 1.45;
    color: var(--ink-soft);
    overflow-wrap: anywhere;
  }

  /* Every row that carries a number states where the number came from, on the row. */
  .measure {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-1) var(--space-2);
    margin-top: var(--space-2);
    padding-top: var(--space-2);
    border-top: 1px solid var(--hairline);
  }

  .value {
    font-size: 0.85rem;
    color: var(--ink-text);
  }

  .source {
    font-size: 0.72rem;
    line-height: 1.4;
    color: var(--text-subtle);
  }

  /* ---- twelve-week ribbon ------------------------------------------------ */

  .ribbon {
    display: grid;
    grid-template-columns: repeat(12, minmax(0, 1fr));
    gap: var(--space-1);
    margin: 0;
    padding: 0;
    list-style: none;
    overflow-x: auto;
  }

  .week {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: flex-end;
    gap: var(--space-2);
    min-height: 4.5rem;
    padding: var(--space-2) 0;
    background: var(--chrome);
    border: 1px solid var(--chrome-hair);
    border-radius: var(--radius-sm);
    color: var(--chrome-ink);
  }

  .week-marks {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    flex: 1 1 auto;
    min-height: 1.75rem;
  }

  .week-label {
    font-size: 9px;
    letter-spacing: 0.04em;
    color: var(--chrome-soft);
    white-space: nowrap;
  }

  /* A week with nothing in it. It loses its fill and keeps its date, and that is the whole
     treatment: no hue, no hatching, no label, nothing that reads as a verdict on the week. */
  .week.blank {
    background: transparent;
    border-style: dashed;
    border-color: var(--hairline);
  }

  @media (max-width: 40rem) {
    .ribbon {
      grid-template-columns: repeat(12, 2.5rem);
    }
  }
</style>
