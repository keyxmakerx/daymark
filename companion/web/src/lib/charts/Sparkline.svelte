<script lang="ts">
  /* Hand-rolled SVG sparkline — no charting library. Renders daily average mood. */
  import type { DailyMood } from '../stats'

  let { points, label }: { points: DailyMood[]; label: string } = $props()

  const W = 720
  const H = 120
  const PAD = 8

  const path = $derived(buildPath(points))

  function buildPath(pts: DailyMood[]): string {
    if (pts.length === 0) return ''
    if (pts.length === 1) {
      const y = yFor(pts[0].avg)
      return `M ${PAD} ${y} L ${W - PAD} ${y}`
    }
    const minDay = pts[0].day
    const maxDay = pts[pts.length - 1].day
    const span = Math.max(1, maxDay - minDay)
    return pts
      .map((p, i) => {
        const x = PAD + ((p.day - minDay) / span) * (W - 2 * PAD)
        const y = yFor(p.avg)
        return `${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`
      })
      .join(' ')
  }

  // Mood range is 1..5; invert so higher mood is higher on screen.
  function yFor(avg: number): number {
    const t = (avg - 1) / 4
    return H - PAD - t * (H - 2 * PAD)
  }
</script>

{#if points.length === 0}
  <p class="faint">No mood entries to chart yet.</p>
{:else}
  <figure class="spark" role="group" aria-label={label}>
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
      <!-- Meh baseline (mood 3) for visual reference -->
      <line x1={PAD} x2={W - PAD} y1={yFor(3)} y2={yFor(3)} class="baseline" />
      <path d={path} class="line" />
    </svg>
    <figcaption class="visually-hidden">
      {label}: {points.length} days, from an average of
      {points[0].avg.toFixed(1)} to {points[points.length - 1].avg.toFixed(1)} (scale 1–5).
    </figcaption>
  </figure>
{/if}

<style>
  .spark { margin: 0; }
  svg { width: 100%; height: 120px; display: block; }
  .line {
    fill: none;
    stroke: var(--mood-5);
    stroke-width: 2;
    stroke-linejoin: round;
    stroke-linecap: round;
    vector-effect: non-scaling-stroke;
  }
  .baseline {
    stroke: var(--hairline);
    stroke-width: 1;
    stroke-dasharray: 3 4;
    vector-effect: non-scaling-stroke;
  }
</style>
