<script lang="ts">
  import type { BackupData } from '../backup'
  import { summarize, dailyMood, formatDate } from '../stats'
  import { MOODS } from '../mood'
  import Sparkline from '../charts/Sparkline.svelte'

  let { data }: { data: BackupData } = $props()

  const s = $derived(summarize(data))
  const trend = $derived(dailyMood(data))
  const maxCount = $derived(Math.max(1, ...s.distribution))

  // Geometry for the SVG distribution chart (no inline styles → CSP-clean).
  const ROW_H = 34
  const BAR_H = 16
  const LABEL_W = 70
  const COUNT_W = 44
  const VW = 640
  const trackW = VW - LABEL_W - COUNT_W

  function barW(count: number): number {
    return Math.max(2, (count / maxCount) * trackW)
  }
</script>

<section class="grid" aria-label="Overview">
  <div class="card stat">
    <span class="num">{s.entryCount}</span>
    <span class="muted">mood entries</span>
  </div>
  <div class="card stat">
    <span class="num">{s.averageMood ? s.averageMood.toFixed(2) : '—'}</span>
    <span class="muted">average mood (1–5)</span>
  </div>
  <div class="card stat">
    <span class="num">{s.currentStreakDays}</span>
    <span class="muted">day streak</span>
  </div>
  <div class="card stat">
    <span class="num">{s.journalCount}</span>
    <span class="muted">journal entries</span>
  </div>
</section>

<div class="card">
  <h2>Mood over time</h2>
  <p class="muted range">{formatDate(s.firstEntry)} → {formatDate(s.lastEntry)}</p>
  <Sparkline points={trend} label="Average mood per day" />
</div>

<div class="card">
  <h2>How your moods break down</h2>
  <svg class="dist" viewBox={`0 0 ${VW} ${MOODS.length * ROW_H}`} role="img" aria-label="Mood distribution">
    {#each MOODS as m, i (m.level)}
      {@const count = s.distribution[m.level - 1]}
      {@const cy = i * ROW_H + ROW_H / 2}
      <text x="0" y={cy} dominant-baseline="central" class="d-label">{m.label}</text>
      <rect x={LABEL_W} y={cy - BAR_H / 2} width={trackW} height={BAR_H} rx="8" class="d-track" />
      <rect x={LABEL_W} y={cy - BAR_H / 2} width={barW(count)} height={BAR_H} rx="8" class={`d-fill m${m.level}`} />
      <text x={VW} y={cy} dominant-baseline="central" text-anchor="end" class="d-count">{count}</text>
    {/each}
  </svg>
</div>

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
    gap: var(--space-4);
    margin-bottom: var(--space-5);
  }
  .stat { display: flex; flex-direction: column; gap: var(--space-1); }
  .num { font-family: var(--font-display); font-size: 2rem; line-height: 1; }
  .card + .card { margin-top: var(--space-5); }
  .range { margin-top: 0; }

  .dist { width: 100%; height: auto; display: block; font-family: var(--font-text); }
  .d-label { fill: var(--ink-soft); font-size: 14px; }
  .d-count { fill: var(--ink-soft); font-size: 14px; font-family: var(--font-mono); }
  .d-track { fill: var(--paper-bg); stroke: var(--hairline); stroke-width: 1; }
  .m1 { fill: var(--mood-1); }
  .m2 { fill: var(--mood-2); }
  .m3 { fill: var(--mood-3); }
  .m4 { fill: var(--mood-4); }
  .m5 { fill: var(--mood-5); }
</style>
