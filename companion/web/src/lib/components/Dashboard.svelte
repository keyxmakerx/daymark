<script lang="ts">
  import type { BackupData } from '../backup'
  import { summarize, dailyMoodInRange, activityAssociation, assessmentSeries, formatDate, WINDOW_DAYS, type RangeDays } from '../stats'
  import { MOODS, moodWord, ownMoodColours, ownMoodFill } from '../mood'
  import Sparkline from '../charts/Sparkline.svelte'
  import JournalReader from './JournalReader.svelte'
  import MonthCalendar from './calendar/MonthCalendar.svelte'

  /*
   * showAverage: only the clinician's view of a share passes it (#361). A person's own views
   * describe what was logged and never mark it (#203), and an average of somebody's moods is a
   * mark: one figure for how they have been. Off unless asked for, so a new mount over a person's
   * own data shows none. dashboardAverage.test.ts holds every mount to that.
   *
   * ownData: whether these records are the person's own — a backup opened in the viewer or the
   * owner console — or a share a clinician is reading. A person's own moods are drawn in their own
   * names and colours (#280). A share carries neither, and the clinician's view says
   * `ownData={false}`, so it draws the shipped scale whatever a bundle might one day hold.
   * ownMoodColour.tree.test.ts holds the clinician's view, and only it, to saying so. The month
   * calendar is the person's own too (#335), and is drawn only on their own data: the clinician
   * has a calendar of their own, which draws no mood's colour.
   */
  let {
    data,
    showAverage = false,
    ownData = true,
  }: { data: BackupData; showAverage?: boolean; ownData?: boolean } = $props()

  /* The word and the colour each mood is drawn in: the person's own on their own data, the
     shipped scale otherwise and for any level they left as it was. */
  const words = $derived(MOODS.map((m) => (ownData ? moodWord(m.level, data.moodLabels) : m.label)))
  const palette = $derived(ownMoodColours(ownData ? data.moodColors : undefined))

  /* The distribution's word column is as wide as its longest word, so a person's own name for a
     mood never runs into its bar. A word too long even for the widest column is drawn narrower
     rather than cut: it is still their word, whole. */
  const GLYPH = 8.5
  const wordColumn = $derived(Math.min(240, Math.max(70, Math.ceil(Math.max(...words.map((w) => w.length)) * GLYPH) + 14)))
  const barSpan = $derived(570 - wordColumn)

  const s = $derived(summarize(data))
  let range = $state<RangeDays>(90)
  const moodSeries = $derived(dailyMoodInRange(data, range))
  const assoc = $derived(activityAssociation(data))
  const maxAbsDelta = $derived(Math.max(0.5, ...assoc.map((a) => Math.abs(a.delta))))
  const assessments = $derived(assessmentSeries(data))
  const maxCount = $derived(Math.max(1, ...s.distribution))

  const RANGES: { label: string; value: RangeDays }[] = [
    { label: '30d', value: 30 },
    { label: '90d', value: 90 },
    { label: '1y', value: 365 },
    { label: 'All', value: 'all' },
  ]

  // Normalize an assessment series to its own min/max for a mini trend line.
  function assessPath(points: { score: number }[]): string {
    if (points.length === 0) return ''
    const vals = points.map((p) => p.score)
    const lo = Math.min(...vals)
    const hi = Math.max(...vals)
    const span = hi - lo || 1
    const w = 200
    const h = 34
    return points
      .map((p, i) => {
        const x = points.length === 1 ? w / 2 : (i / (points.length - 1)) * w
        const y = h - ((p.score - lo) / span) * h
        return `${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`
      })
      .join(' ')
  }
</script>

<div class="dash">
  <!-- Mood over time -->
  <details class="card" open>
    <summary>
      <span class="h">Mood over time</span>
      <!--
        What was logged, and no mark on it. The average is here only on the clinician's view of a
        share, named as the PDF report names it, to one decimal as the report prints it. Each
        fragment disappears entirely at zero rather than reading "0 of the last 30 days": a summary
        line is the wrong place to hand someone a figure for the time they were away.
      -->
      <span class="sum faint">{showAverage && s.averageMood !== null
          ? `average of what was logged: ${s.averageMood.toFixed(1)}${s.daysWithEntryLast30 > 0 ? ' · ' : ''}`
          : ''}{s.daysWithEntryLast30 > 0 ? `${s.daysWithEntryLast30} of the last ${WINDOW_DAYS} days` : ''}</span>
    </summary>
    <div class="body">
      <div class="controls" role="group" aria-label="Time range">
        {#each RANGES as r (r.label)}
          <button class:active={range === r.value} aria-pressed={range === r.value} onclick={() => (range = r.value)}>{r.label}</button>
        {/each}
      </div>
      <Sparkline points={moodSeries} label={`Average mood per day (${RANGES.find((r) => r.value === range)?.label})`} />
      <p class="muted range">{formatDate(s.firstEntry)} → {formatDate(s.lastEntry)}</p>

      <svg class="dist" viewBox={`0 0 640 ${MOODS.length * 30}`} role="img" aria-label={`Mood distribution — ${MOODS.map((m, i) => `${words[i]}: ${s.distribution[m.level - 1]}`).join(', ')}`}>
        {#each MOODS as m, i (m.level)}
          {@const count = s.distribution[m.level - 1]}
          {@const cy = i * 30 + 15}
          {@const word = words[i]}
          <text
            x="0"
            y={cy}
            dominant-baseline="central"
            class="lbl mood-word"
            textLength={word.length * GLYPH > wordColumn - 14 ? wordColumn - 14 : undefined}
            lengthAdjust="spacingAndGlyphs">{word}</text>
          <rect x={wordColumn} y={cy - 8} width={barSpan} height="16" rx="8" class="track" />
          <rect
            x={wordColumn}
            y={cy - 8}
            width={Math.max(2, (count / maxCount) * barSpan)}
            height="16"
            rx="8"
            class="mood-mark"
            data-level={m.level}
            style:--mood-fill={ownMoodFill(m.level, palette)} />
          <text x="640" y={cy} dominant-baseline="central" text-anchor="end" class="lbl">{count}</text>
        {/each}
      </svg>
    </div>
  </details>

  <!-- The person's own month (#335): read-only, in their own words and colours, and printable. -->
  {#if ownData}
    <details class="card" open>
      <summary>
        <span class="h">Calendar</span>
      </summary>
      <div class="body">
        <MonthCalendar {data} />
      </div>
    </details>
  {/if}

  <!-- Activities & mood -->
  <details class="card">
    <summary>
      <span class="h">Activities & mood</span>
      <span class="sum faint">{assoc.length} tracked · association, not cause</span>
    </summary>
    <div class="body">
      {#if assoc.length === 0}
        <p class="faint">No tagged activities in this data.</p>
      {:else}
        <p class="faint">Average mood on days with each activity, relative to your overall average. This shows association, <strong>not causation</strong>.</p>
        <ul class="assoc">
          {#each assoc as a (a.activityId)}
            <li>
              <span class="an">{a.name}</span>
              <svg class="delta" viewBox="0 0 200 16" role="img" aria-label={`${a.name}: ${a.delta >= 0 ? '+' : ''}${a.delta} vs average over ${a.count} entries`}>
                <line x1="100" y1="0" x2="100" y2="16" class="axis" />
                <rect x={a.delta >= 0 ? 100 : 100 + (a.delta / maxAbsDelta) * 100} y="4" width={Math.max(2, (Math.abs(a.delta) / maxAbsDelta) * 100)} height="8" rx="4" class="bar" />
              </svg>
              <span class="ad mono">{a.delta >= 0 ? '+' : ''}{a.delta}</span>
              <span class="ac faint">{a.count}×</span>
            </li>
          {/each}
        </ul>
      {/if}
    </div>
  </details>

  <!-- Self-check history -->
  {#if assessments.length > 0}
    <details class="card">
      <summary>
        <span class="h">Self-check history</span>
        <span class="sum faint">{assessments.length} instrument{assessments.length === 1 ? '' : 's'}</span>
      </summary>
      <div class="body">
        <p class="faint">Descriptive trends of your own scores over time — not a diagnosis.</p>
        {#each assessments as a (a.key)}
          <div class="assess">
            <div class="ah"><span class="ak">{a.key}</span><span class="ab faint">latest: {a.latestBand}</span></div>
            <svg viewBox="0 0 200 34" class="trend" role="img" aria-label={`${a.key}: ${a.points.length} results, latest ${a.latestBand}`}>
              <path d={assessPath(a.points)} class="line" />
            </svg>
          </div>
        {/each}
      </div>
    </details>
  {/if}

  <!-- Journal -->
  <details class="card">
    <summary>
      <span class="h">Journal</span>
      <span class="sum faint">{s.journalCount} entr{s.journalCount === 1 ? 'y' : 'ies'}</span>
    </summary>
    <div class="body">
      <JournalReader {data} />
    </div>
  </details>
</div>

<style>
  .dash { display: grid; gap: var(--space-4); }
  details.card { padding: 0; }
  summary {
    cursor: pointer; list-style: none; display: flex; align-items: baseline; justify-content: space-between;
    gap: var(--space-3); padding: var(--space-4); border-radius: var(--radius);
  }
  summary::-webkit-details-marker { display: none; }
  summary:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
  .h { font-family: var(--font-display); font-size: 1.1rem; }
  .body { padding: 0 var(--space-4) var(--space-4); display: flex; flex-direction: column; gap: var(--space-3); }
  .controls { display: flex; gap: var(--space-2); }
  .controls button { padding: var(--space-1) var(--space-3); font-size: 0.85rem; }
  /* STATE, not data: which range the reader has selected is a fact about the interface, so it
     takes the structural accent. aria-pressed carries the same fact without colour. */
  .controls button.active { background: var(--indigo); color: var(--on-accent); border-color: var(--indigo); }
  .range { margin: 0; }
  .dist { width: 100%; height: auto; }
  .dist .lbl { fill: var(--ink-soft); font-size: 14px; font-family: var(--font-text); }
  .dist .track { fill: var(--paper-bg); stroke: var(--hairline); }

  /* DATA — do not "fix" these. Each bar counts a person's own entries at that mood level, and
     the ramp is the legend: an "awful" bar must be the same colour as an "awful" mood anywhere
     else. Every row also prints its word and count as text, and the svg carries an aria-label
     listing both, so the hue is never the sole carrier.
     A bar is a mood mark, and `--mood-fill` is its colour: the shipped ramp, set here per level,
     unless the person chose their own colour for that level, which the bar carries inline and
     which wins (#280). ownMoodColour.tree.test.ts holds `--mood-fill` to mood marks' fills. */
  .dist .mood-mark[data-level='1'] { --mood-fill: var(--mood-1); }
  .dist .mood-mark[data-level='2'] { --mood-fill: var(--mood-2); }
  .dist .mood-mark[data-level='3'] { --mood-fill: var(--mood-3); }
  .dist .mood-mark[data-level='4'] { --mood-fill: var(--mood-4); }
  .dist .mood-mark[data-level='5'] { --mood-fill: var(--mood-5); }
  .dist .mood-mark { fill: var(--mood-fill); }
  .assoc { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-2); }
  .assoc li { display: grid; grid-template-columns: 8rem 1fr 3rem 2.5rem; align-items: center; gap: var(--space-2); }
  .an { color: var(--ink-text); }
  .delta { width: 100%; height: 16px; }
  .delta .axis { stroke: var(--hairline); }

  /* INK, one fill for both sides (#404). The bar is how far the average mood on days with this
     activity sits from the overall average: a difference between two averages, not a mood
     anybody logged, so no mood colour draws it. Filling "above" in the Rad colour, which is
     green, and "below" in the Bad colour would be a verdict on the activity painted in mood
     colours, and a mood colour is the value a person logged, never a status (docs/DESIGN.md).
     Which side of the centre line the bar sits on, and the signed number beside it, say the
     direction; nothing here says whether it is good. dashboardActivities.test.ts holds it, on
     the person's own view and the clinician's alike, since both draw this card. */
  .delta .bar { fill: var(--ink-accent); }
  .ad { text-align: right; color: var(--ink-soft); }
  .ac { text-align: right; }
  .assess { display: grid; gap: var(--space-1); }
  .ah { display: flex; justify-content: space-between; align-items: baseline; }
  .trend { width: 100%; height: 34px; }

  /* DATA — this line plots a person's own self-check scores over time, the same class of mark
     Sparkline draws for mood, and it stays on the data ramp for the same reason: the chart
     layer is where the ramp belongs. It encodes no state and makes no claim about the scores —
     the band is stated in words beside it ("latest: …") and in the svg's aria-label. */
  .trend .line { fill: none; stroke: var(--mood-5); stroke-width: 2; vector-effect: non-scaling-stroke; }
</style>
