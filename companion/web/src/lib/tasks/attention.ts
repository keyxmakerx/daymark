/*
 * "Steady Attention" — original CPT-style sustained-attention task (NOT TOVA/Conners/CAARS).
 * Respond to the frequent target, withhold on the rare non-target. Reported metrics are
 * count-based (robust) plus a caveated RT mean/variability. The task is honest about its own
 * browser timing precision (frame jitter → a "lower-precision" flag). See COMPANION_FEATURES.md §3.
 *
 * This module is the pure, testable core: it turns recorded trials + frame timing into a
 * TaskResult. The rendering/timing loop lives in AttentionTask.svelte.
 */
import type { TaskResult } from '../instruments/types'

export const ATTENTION_TASK = {
  taskId: 'steady-attention',
  taskVersion: '1.0.0',
  title: 'Steady Attention',
  trials: 40,
  targetProbability: 0.75,
  stimulusMs: 250,
  isiMs: 1500,
  isiJitterMs: 250,
  // Above this frame-jitter SD, RT variability is unreliable → flag lower-precision.
  jitterFlagMs: 4,
}

export interface Trial {
  isTarget: boolean
  responded: boolean
  rtMs: number | null // RT of the response, if any
}

export interface FrameTiming {
  frameIntervalsMs: number[] // measured intervals between animation frames during the run
}

function mean(xs: number[]): number {
  return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0
}
function sd(xs: number[]): number {
  if (xs.length < 2) return 0
  const m = mean(xs)
  return Math.sqrt(mean(xs.map((x) => (x - m) ** 2)))
}
function median(xs: number[]): number {
  if (!xs.length) return 0
  const s = [...xs].sort((a, b) => a - b)
  const mid = Math.floor(s.length / 2)
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2
}
const round1 = (n: number) => Math.round(n * 10) / 10

export interface AttentionOutcome extends TaskResult {
  metrics: {
    targets: number
    nonTargets: number
    hits: number
    omissions: number // missed targets
    commissions: number // responded on non-targets
    accuracyPct: number
    rtMeanMs: number
    rtVariabilityMs: number
    rtVariabilityJitterMs: number // instrument-jitter error bar on the variability number
  }
}

export function computeAttention(trials: Trial[], timing: FrameTiming, takenAt: number): AttentionOutcome {
  const targets = trials.filter((t) => t.isTarget)
  const nonTargets = trials.filter((t) => !t.isTarget)
  const hitTrials = targets.filter((t) => t.responded && t.rtMs != null)
  const hits = hitTrials.length
  const omissions = targets.length - hits
  const commissions = nonTargets.filter((t) => t.responded).length
  const correct = hits + (nonTargets.length - commissions)
  const accuracyPct = trials.length ? (correct / trials.length) * 100 : 0

  const rts = hitTrials.map((t) => t.rtMs as number)
  const refreshMs = median(timing.frameIntervalsMs) || 16.7
  const frameJitterMs = sd(timing.frameIntervalsMs)
  const droppedFrames = timing.frameIntervalsMs.filter((i) => i > 1.5 * refreshMs).length
  // Don't default to "ok" when there's too little timing evidence to judge precision.
  const tooFewFrames = timing.frameIntervalsMs.length < 20
  const flag: 'ok' | 'lower-precision' =
    tooFewFrames || frameJitterMs > ATTENTION_TASK.jitterFlagMs || droppedFrames > 3 ? 'lower-precision' : 'ok'

  return {
    kind: 'task',
    taskId: ATTENTION_TASK.taskId,
    taskVersion: ATTENTION_TASK.taskVersion,
    takenAt,
    timing: { flag, frameJitterMs: round1(frameJitterMs), droppedFrames, refreshMs: round1(refreshMs) },
    metrics: {
      targets: targets.length,
      nonTargets: nonTargets.length,
      hits,
      omissions,
      commissions,
      accuracyPct: round1(accuracyPct),
      rtMeanMs: Math.round(mean(rts)),
      rtVariabilityMs: Math.round(sd(rts)),
      // The variability error bar grows with measured frame jitter (the clock's own spread).
      rtVariabilityJitterMs: Math.round(frameJitterMs),
    },
  }
}
