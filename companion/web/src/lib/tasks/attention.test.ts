import { describe, it, expect } from 'vitest'
import { computeAttention, type Trial } from './attention'

const steady = { frameIntervalsMs: Array(200).fill(16.7) } // rock-steady clock
const jittery = { frameIntervalsMs: Array(200).fill(0).map((_, i) => (i % 5 === 0 ? 40 : 16.7)) }

describe('attention metrics', () => {
  it('counts hits, omissions, commissions and accuracy', () => {
    const trials: Trial[] = [
      { isTarget: true, responded: true, rtMs: 300 }, // hit
      { isTarget: true, responded: false, rtMs: null }, // omission
      { isTarget: false, responded: true, rtMs: 250 }, // commission
      { isTarget: false, responded: false, rtMs: null }, // correct rejection
    ]
    const r = computeAttention(trials, steady, 1000)
    expect(r.metrics.targets).toBe(2)
    expect(r.metrics.hits).toBe(1)
    expect(r.metrics.omissions).toBe(1)
    expect(r.metrics.commissions).toBe(1)
    expect(r.metrics.accuracyPct).toBe(50) // 1 hit + 1 correct-rejection of 4
  })

  it('flags ok on a steady clock, lower-precision on a jittery one', () => {
    const trials: Trial[] = [{ isTarget: true, responded: true, rtMs: 320 }]
    expect(computeAttention(trials, steady, 1).timing.flag).toBe('ok')
    expect(computeAttention(trials, jittery, 1).timing.flag).toBe('lower-precision')
  })

  it('reports RT mean and a jitter error bar on variability', () => {
    const trials: Trial[] = [
      { isTarget: true, responded: true, rtMs: 300 },
      { isTarget: true, responded: true, rtMs: 340 },
    ]
    const r = computeAttention(trials, steady, 1)
    expect(r.metrics.rtMeanMs).toBe(320)
    expect(r.metrics.rtVariabilityMs).toBeGreaterThan(0)
    expect(r.metrics.rtVariabilityJitterMs).toBeGreaterThanOrEqual(0)
  })
})
