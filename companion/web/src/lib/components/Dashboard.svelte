<script lang="ts">
  import type { BackupData } from '../backup'
  import { summarize, dailyMoodInRange, activityAssociation, assessmentSeries, formatDate, type RangeDays } from '../stats'
  import { MOODS } from '../mood'
  import Sparkline from '../charts/Sparkline.svelte'
  import JournalReader from './JournalReader.svelte'

  let { data }: { data: BackupData } = $props()

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
      <span class="sum faint">avg {s.averageMood?.toFixed(2) ?? '—'} · {s.currentStreakDays}-day streak</span>
    </summary>
    <div class="body">
      <div class="controls" role="group" aria-label="Time range">
        {#each RANGES as r (r.label)}
          <button class:active={range === r.value} aria-pressed={range === r.value} onclick={() => (range = r.value)}>{r.label}</button>
        {/each}
      </div>
      <Sparkline points={moodSeries} label={`Average mood per day (${RANGES.find((r) => r.value === range)?.label})`} />
      <p class="muted range">{formatDate(s.firstEntry)} → {formatDate(s.lastEntry)}</p>

      <svg class="dist" viewBox={`0 0 640 ${MOODS.length * 30}`} role="img" aria-label={`Mood distribution — ${MOODS.map((m) => `${m.label}: ${s.distribution[m.level - 1]}`).join(', ')}`}>
        {#each MOODS as m, i (m.level)}
          {@const count = s.distribution[m.level - 1]}
          {@const cy = i * 30 + 15}
          <text x="0" y={cy} dominant-baseline="central" class="lbl">{m.label}</text>
          <rect x="70" y={cy - 8} width="500" height="16" rx="8" class="track" />
          <rect x="70" y={cy - 8} width={Math.max(2, (count / maxCount) * 500)} height="16" rx="8" class={`m${m.level}`} />
          <text x="640" y={cy} dominant-baseline="central" text-anchor="end" class="lbl">{count}</text>
        {/each}
      </svg>
    </div>
  </details>

  <!-- Activities & mood -->
  <details class="card">
    <summary>
      <span class="h">Activities & mood</span>
      <span class="sum faint">{assoc.length} tracked · association, not cause</span>
    </summary>
    <div class="body">
      {#if assoc.length === 0}
        <p class="faint">No tagged activities in this data yet.</p>
      {:else}
        <p class="faint">Average mood on days with each activity, relative to your overall average. This shows association, <strong>not causation</strong>.</p>
        <ul class="assoc">
          {#each assoc as a (a.activityId)}
            <li>
              <span class="an">{a.name}</span>
              <svg class="delta" viewBox="0 0 200 16" role="img" aria-label={`${a.name}: ${a.delta >= 0 ? '+' : ''}${a.delta} vs average over ${a.count} entries`}>
                <line x1="100" y1="0" x2="100" y2="16" class="axis" />
                <rect x={a.delta >= 0 ? 100 : 100 + (a.delta / maxAbsDelta) * 100} y="4" width={Math.max(2, (Math.abs(a.delta) / maxAbsDelta) * 100)} height="8" rx="4" class={a.delta >= 0 ? 'pos' : 'neg'} />
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
  .controls button.active { background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }
  .range { margin: 0; }
  .dist { width: 100%; height: auto; }
  .dist .lbl { fill: var(--ink-soft); font-size: 14px; font-family: var(--font-text); }
  .dist .track { fill: var(--paper-bg); stroke: var(--hairline); }
  .m1 { fill: var(--mood-1); } .m2 { fill: var(--mood-2); } .m3 { fill: var(--mood-3); } .m4 { fill: var(--mood-4); } .m5 { fill: var(--mood-5); }
  .assoc { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-2); }
  .assoc li { display: grid; grid-template-columns: 8rem 1fr 3rem 2.5rem; align-items: center; gap: var(--space-2); }
  .an { color: var(--ink-text); }
  .delta { width: 100%; height: 16px; }
  .delta .axis { stroke: var(--hairline); }
  .delta .pos { fill: var(--mood-5); }
  .delta .neg { fill: var(--mood-2); }
  .ad { text-align: right; color: var(--ink-soft); }
  .ac { text-align: right; }
  .assess { display: grid; gap: var(--space-1); }
  .ah { display: flex; justify-content: space-between; align-items: baseline; }
  .trend { width: 100%; height: 34px; }
  .trend .line { fill: none; stroke: var(--mood-5); stroke-width: 2; vector-effect: non-scaling-stroke; }
</style>
