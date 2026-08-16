<script lang="ts">
  /*
   * CLIENT RECORD — a thin renderer over lib/therapist/clientRecord.ts.
   *
   * WHAT THIS FILE IS ALLOWED TO DECIDE. Arrangement, and the one thing the pure module refuses
   * to do: turn an instant into a date, which means picking a calendar. Every rule that could be
   * got wrong in a way that matters — which band a score falls in, whether a recorded band may be
   * overruled, what happens to an instrument nobody ran — lives in the module where vitest holds
   * it. If a behaviour here cannot be described as layout, it is in the wrong file.
   *
   * WHY THE Y-AXIS IS WORDS. This is the whole screen. A questionnaire total drawn against a
   * numeric axis invites a reader to interpolate: to treat the gap between 13 and 14 as a known
   * quantity, to see a line cross a horizontal and read a threshold into it. These instruments
   * band descriptively and define no clinical cutoff at all (instruments/types.ts), so the axis is
   * the instrument's own band labels in the instrument's own order, and the totals — which do
   * exist, in the file — are never drawn and never printed. The module does not even carry them
   * into the view model, so this component could not draw one if it tried.
   *
   * WHY UP THE PAGE CARRIES NO VALENCE. Lanes run in the scale's own declared order, last band at
   * the top. For the wellbeing self-check the top lane is "Brighter than your own usual today"; for
   * the focus self-check it is "More focus bumps noted today". Up therefore means "more of whatever
   * this instrument counts" and nothing else — which is exactly why every lane is labelled with the
   * band's own words, and why no lane is ever tinted. A coloured lane would supply a verdict the
   * instrument does not have and the module deliberately does not carry (`Band.tone` is dropped at
   * the module boundary for this reason).
   *
   * WHY THERE IS NO MOOD COLOUR HERE, AND NO BandTag. The mood ramp encodes a person's REPORTED
   * MOOD LEVEL, 1–5, and BandTag exists to draw exactly that. An instrument band is a different
   * object: the scales here have three steps, not five, and their steps have no shared meaning —
   * band 3 of the focus check ("More focus bumps") and band 3 of the wellbeing check ("Brighter
   * than your own usual") would land on the same ramp position and read as the same claim. Mapping
   * one onto the other would manufacture a five-point scale the instruments do not have and paint
   * a verdict on it. So the marks are drawn in ink, the lanes are named in words, and the ramp is
   * left to the surfaces it belongs to.
   *
   * WHY THE TREND IS aria-hidden AND THE TABLE IS NOT. Every mark on the trend is a row in the
   * table beneath it — same instant, same band, same source — so the chart is a second encoding of
   * data that is already fully readable. Announcing it as well would make a screen-reader user
   * listen to the record twice, and no aria-label short enough to be bearable could carry a dozen
   * runs honestly. The table is the accessible copy, and it is the complete one.
   *
   * WHY MARKS ARE NOT JOINED BY A LINE. A line between two runs asserts a value for every day in
   * between, on days nobody was assessed. The gaps here are real and are drawn to scale: a quarter
   * with no runs is a quarter of empty track. It is not shaded, not annotated, and not counted. A
   * person who was not assessed for three months may have been having the worst quarter of their
   * life, and a surface that draws that as a dip has decided otherwise on no evidence.
   *
   * WHY `now` IS A PROP. `$derived` re-evaluates only when a TRACKED dependency changes, and a
   * `Date.now()` inside a callee is invisible to it — the bug that shipped in TherapistPortal.svelte,
   * whose idle guard therefore never fired again. `now` arrives as a value, so a parent that wants
   * this screen to age can tick a `$state` and get exactly that.
   *
   * ALL PROSE BELOW IS PREMADE AND WRITTEN BY A PERSON. Nothing on this screen is generated,
   * inferred, summarised or phrased by a model, and nothing here recommends an action.
   */
  import { Card, PageHeader, EmptyState, DataTable, Callout, Chip, ProvenanceBadge } from '../ui'
  import type { Column } from '../ui'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'
  import type { BackupData } from '../../backup'
  import { buildClientRecord, bandLanes } from '../../therapist/clientRecord'
  import type {
    AxisAbsence,
    InstrumentRecord,
    RunPoint,
    UnplacedReason,
  } from '../../therapist/clientRecord'

  let {
    data,
    now,
  }: {
    /** The decrypted bundle. Read only — this screen writes nothing, anywhere. */
    data: BackupData
    /** Epoch millis, supplied by the parent. See the note above: never read from a clock here. */
    now: number
  } = $props()

  const view = $derived(buildClientRecord({ data, now }))

  /*
   * Three columns and no fourth. There is no score column for the same reason there is no numeric
   * axis, and there is no "change since last run" column at all: a delta between two descriptive
   * bands is a direction the instrument does not define, and printing one would hand a clinician a
   * judgement the software computed rather than a fact it read.
   */
  const COLUMNS: Column[] = [
    { key: 'at', label: 'Taken', numeric: true, width: '15ch' },
    { key: 'label', label: 'Band' },
    { key: 'provenance', label: 'Source' },
  ]

  const DAY_FORMAT = new Intl.DateTimeFormat(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  })

  function day(ms: number): string {
    return DAY_FORMAT.format(new Date(ms))
  }

  /** Why an instrument has no band axis. One premade sentence per case. */
  const AXIS_ABSENCE: Record<AxisAbsence, string> = {
    instrumentNotInCatalog:
      'This build does not carry a definition for this tool, so its bands — and, more to the point, the order they run in — are not known here. There is no axis that could be drawn without inventing one. Every run is listed below with the band the file recorded for it.',
    instrumentScoresNothing:
      'This tool records no score and defines no bands. It is a written exercise, and what it leaves behind is a note that it was opened on a given day. Nothing was measured, so there is nothing to place.',
    moreThanOneScale:
      'This tool defines more than one scale. The file records one band per run without saying which scale it belongs to, so placing these runs on an axis would mean choosing a scale on the reader’s behalf.',
  }

  /** Why one run is not on the trend. Stated on the row, never resolved by moving the run. */
  const UNPLACED: Record<UnplacedReason, string> = {
    noAxis: 'Not on the trend — this tool has no band axis here.',
    bandNotOnAxis:
      'Not on the trend — this build’s definition does not contain this band. The band the file recorded is kept as it stands.',
    scoreBetweenBands:
      'Not on the trend — no band was recorded, and the stored total falls between two of this build’s bands.',
  }

  const DERIVED_NOTE = 'No band was recorded; placed by this build’s own bands.'
  const NOT_RECORDED = 'No band recorded'
  const SOURCE_UNKNOWN = 'Source not in catalog'

  function anyDerived(record: InstrumentRecord): boolean {
    return record.runs.some((r) => r.placement.kind === 'derived')
  }

  function runWord(n: number): string {
    return n === 1 ? 'run' : 'runs'
  }
</script>

<section class="record">
  <PageHeader title="Client record">
    <Chip>{view.scope.runCount} self-check {runWord(view.scope.runCount)}</Chip>
    <Chip>{view.scope.instrumentCount} {view.scope.instrumentCount === 1 ? 'tool' : 'tools'}</Chip>
  </PageHeader>

  <NonDiagnosticBanner />

  <!--
    The axis rule, stated before the first chart rather than under the last one. A reader who
    scrolls past this and reads the trends anyway must still be unable to get a number off them,
    which is why the rule is enforced in the module and only explained here.
  -->
  <Callout tone="info" title="The vertical axis is bands, not scores">
    <p class="para">
      Each lane is one of the instrument's own bands, in its own words and its own order. The totals
      behind them are in the file and are deliberately not drawn: a number on an axis invites
      reading a cut-off at a precision these instruments do not define. They band descriptively and
      have no clinical threshold.
    </p>
    <p class="para">
      Up the page means more of whatever that instrument counts, and nothing beyond that. A run sits
      on the band the file recorded for it — the band the person was shown at the time.
    </p>
  </Callout>

  {#if view.scope.empty}
    <Card title="Self-checks">
      <EmptyState title="This bundle holds no self-check runs">
        <p>
          Nothing in this file records a self-check having been taken. That is a statement about the
          file. It describes neither the person whose bundle this is nor anything they did.
        </p>
      </EmptyState>
    </Card>
  {:else}
    {#each view.instruments as record (record.key)}
      <Card title={record.title}>
        {#snippet header()}
          {#if record.provenance.kind === 'instrument'}
            <ProvenanceBadge tier={record.provenance.tier} />
          {:else}
            <Chip>{SOURCE_UNKNOWN}</Chip>
          {/if}
          <Chip>{record.runs.length} {runWord(record.runs.length)}</Chip>
        {/snippet}

        {#if record.axis === null && record.axisAbsence !== null}
          <Callout tone="warn" title="No band axis for this tool">
            <p class="para">{AXIS_ABSENCE[record.axisAbsence]}</p>
          </Callout>
        {:else if record.axis !== null}
          <!--
            A derived axis says so, above the lanes and not under them.

            When this build has no definition for the instrument, the axis is recovered from the
            person's own runs: the bands they actually landed in, ordered by the scores that
            produced them. That is strictly less than a declared scale — bands they have never
            landed in are absent — and a reader who mistook it for the instrument's own scale would
            read the top lane as its ceiling. On real bundles this is the ordinary case, not the
            exception, because the phone writes instrument keys this catalog does not carry.
          -->
          {#if record.axisDerived}
            <p class="derived-note">{record.axis.framing}</p>
          {/if}
          <!--
            Redundant with the table below, by design — see the header note. The runs are announced
            there once, in full, rather than twice in two different shapes.
          -->
          <div class="trend" aria-hidden="true">
            {#each bandLanes(record) as lane (lane.step.index)}
              <div class="lane">
                <p class="lane-name">{lane.step.label}</p>
                <div class="lane-track">
                  <span class="lane-rail"></span>
                  <span class="lane-marks">
                    {#each lane.runs as point (point.id)}
                      <span
                        class="mark"
                        data-placement={point.placement.kind}
                        style:left="{point.fraction * 100}%"
                      ></span>
                    {/each}
                  </span>
                </div>
              </div>
            {/each}

            {#if record.firstAt !== null && record.lastAt !== null}
              <div class="lane axis-x">
                <span></span>
                <span class="axis-dates u-mono">
                  <span>{day(record.firstAt)}</span>
                  <span>{day(record.lastAt)}</span>
                </span>
              </div>
            {/if}
          </div>

          {#if anyDerived(record) || record.unplacedCount > 0}
            <ul class="legend">
              {#if anyDerived(record)}
                <li>A hollow mark is a run the file recorded no band for. {DERIVED_NOTE}</li>
              {/if}
              {#if record.unplacedCount > 0}
                <li>
                  {record.unplacedCount}
                  {runWord(record.unplacedCount)} carry a band this axis does not contain. They are listed
                  below and are not drawn above.
                </li>
              {/if}
            </ul>
          {/if}
        {/if}

        <div class="runs">
          <DataTable columns={COLUMNS} rows={record.runs} caption="Runs of {record.title}">
            {#snippet cell(key, row)}
              {@const point = row as RunPoint}
              {#if key === 'at'}
                {day(point.at)}
              {:else if key === 'label'}
                {#if point.label === null}
                  <span class="absent">{NOT_RECORDED}</span>
                {:else}
                  {point.label}
                {/if}
                {#if point.placement.kind === 'derived'}
                  <span class="qualifier">{DERIVED_NOTE}</span>
                {:else if point.placement.kind === 'unplaced'}
                  <span class="qualifier">{UNPLACED[point.placement.reason]}</span>
                {/if}
              {:else if key === 'provenance'}
                {#if point.provenance.kind === 'instrument'}
                  <ProvenanceBadge tier={point.provenance.tier} />
                {:else}
                  <Chip>{SOURCE_UNKNOWN}</Chip>
                {/if}
              {/if}
            {/snippet}
          </DataTable>
        </div>

        {#snippet footer()}
          {#if record.axis !== null}
            {record.axis.framing}
          {:else}
            This tool's runs are listed as the file recorded them. Nothing here is a score, a
            threshold, or a clinical reading.
          {/if}
        {/snippet}
      </Card>
    {/each}
  {/if}

  <!--
    THE EDGE OF THIS SCREEN. Written as a statement of scope rather than as a disclaimer, and
    placed where the record ends so it is read as the boundary of what came before it rather than
    as small print attached to the product. It says what is not here, which is the half a reader
    cannot work out for themselves: an absent thing leaves no mark on a page.
  -->
  <Card title="The edge of this record" tone="quiet">
    <div class="margin">
      <p class="para">
        This page holds self-check runs and nothing else: the day each one was taken, the band it
        fell in, and where that band's wording comes from.
      </p>
      <p class="para">
        It does not hold the person's journal or anything written in it, their check-in notes, or
        their answers to any individual question — those stay on their phone and are not in a share
        at all. It does not hold their totals, which are in the file and are left undrawn on
        purpose.
      </p>
      <p class="para">
        It covers only the tools that were actually run. A tool that does not appear above was not
        used in this bundle; that is a fact about the file, and a stretch of time with no runs in it
        is shown as the empty space it is, never as a shortfall.
      </p>
      <p class="para">
        {#if view.scope.exportedAt !== null}
          The record ends where the export does — {day(view.scope.exportedAt)}. Nothing that happened
          afterwards is missing from this page; it is simply not in the file.
        {:else}
          This bundle does not record when it was exported, so where the record ends cannot be
          stated. Nothing above should be read as current.
        {/if}
      </p>
      <p class="para">
        It is a reading of one exported file. It is not a clinical record, it is not a case note,
        and it is not a source of truth about anything that happened away from the app.
      </p>
    </div>
  </Card>
</section>

<style>
  .record {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    max-width: var(--maxw);
  }

  /* The derived-axis caveat: quieter than the lanes it qualifies, and never an alarm. */
  .derived-note {
    margin: 0 0 var(--space-3);
    color: var(--ink-soft);
    font-size: 0.9em;
  }

  .para {
    margin: 0 0 var(--space-2);
  }

  .para:last-child {
    margin-bottom: 0;
  }

  .margin {
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.6;
  }

  /* ── The trend ─────────────────────────────────────────────────────────────────────────
     The band names are the y-axis, so they get a real column rather than a truncated stub: a
     band read half-way is a band misread, and these labels are whole sentences in some
     instruments. Below the breakpoint the label moves above its own track instead, which keeps
     the words intact at the cost of vertical space — the right trade, since the alternative is
     an axis nobody can read. */
  .trend {
    --lane-name-w: 15rem;
    margin-bottom: var(--space-4);
  }

  .lane {
    display: grid;
    grid-template-columns: var(--lane-name-w) 1fr;
    gap: var(--space-3);
    align-items: center;
  }

  .lane-name {
    margin: 0;
    font-size: 0.8rem;
    line-height: 1.35;
    color: var(--ink-soft);
    overflow-wrap: anywhere;
  }

  .lane-track {
    position: relative;
    height: 2.25rem;
    min-width: 0;
  }

  /* One hairline per band. Every lane is drawn identically: no lane is tinted, weighted or
     emphasised, because the instrument supplies no ranking between its own bands and this
     surface will not invent one. */
  .lane-rail {
    position: absolute;
    left: 0;
    right: 0;
    top: 50%;
    height: 1px;
    background: var(--hairline);
  }

  /* Inset from the track's ends so a mark at either extreme sits whole inside the box instead
     of being clipped in half by the lane's edge. */
  .lane-marks {
    position: absolute;
    inset: 0;
    margin: 0 7px;
  }

  /* Ink, not a data hue. The mark's meaning is entirely carried by which lane it is in, and the
     ring in the sheet's own ground keeps two runs a day apart countable where they overlap. */
  .mark {
    position: absolute;
    top: 50%;
    box-sizing: border-box;
    width: 11px;
    height: 11px;
    margin: -5.5px 0 0 -5.5px;
    border: 2px solid transparent;
    border-radius: 50%;
    background: var(--ink-accent);
    box-shadow: 0 0 0 2px var(--paper-sheet);
  }

  /* Hollow: the file recorded no band for this run and its position was worked out here rather
     than read off the record. The difference is a FORM, not a hue, so it survives greyscale, a
     printout and any colour vision — and the legend under the chart says it in words as well. */
  .mark[data-placement='derived'] {
    background: transparent;
    border-color: var(--ink-accent);
  }

  /* The x-axis: the first and last run in this instrument's own record, and no ticks between
     them. A tick every week would imply a grid the runs were sampled against; they were not. */
  .axis-x {
    margin-top: var(--space-1);
  }

  .axis-dates {
    display: flex;
    justify-content: space-between;
    font-size: 0.7rem;
    color: var(--text-subtle);
  }

  .legend {
    margin: 0 0 var(--space-4);
    padding-left: var(--space-4);
    color: var(--text-subtle);
    font-size: 0.8rem;
    line-height: 1.5;
  }

  .runs {
    margin-top: var(--space-2);
  }

  /*
   * A cell with nothing in it. Faint and in words, because "no band recorded" is a fact about the
   * file that a reader is entitled to tell apart from a cell that failed to render. It carries no
   * tint and no icon: an absence is not a severity, and the moment it is painted it starts reading
   * as a person's failure rather than as a fact about a file.
   */
  .absent {
    color: var(--text-subtle);
  }

  /* Subordinate to the band it qualifies — the band's own words are the row's subject, and this
     says where they came from. */
  .qualifier {
    display: block;
    margin-top: var(--space-1);
    color: var(--text-subtle);
    font-size: 0.75rem;
    line-height: 1.45;
  }

  @media (max-width: 48rem) {
    .lane {
      grid-template-columns: 1fr;
      gap: 0;
    }

    .lane-name {
      margin-top: var(--space-2);
    }

    .axis-x span:first-child {
      display: none;
    }
  }
</style>
