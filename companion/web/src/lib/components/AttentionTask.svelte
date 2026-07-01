<script lang="ts">
  import { onDestroy } from 'svelte'
  import { ATTENTION_TASK, computeAttention, type Trial, type AttentionOutcome } from '../tasks/attention'
  import type { TaskResult } from '../instruments/types'

  let { onDone }: { onDone?: (r: TaskResult) => void } = $props()

  let phase = $state<'intro' | 'running' | 'done'>('intro')
  let stimulusVisible = $state(false)
  let currentIsTarget = $state(false)
  let progress = $state(0)
  let outcome = $state<AttentionOutcome | null>(null)

  let trials: Trial[] = []
  let idx = 0
  let onset = 0
  let responded = false
  let frameIntervals: number[] = []
  let lastFrame = 0
  let rafId = 0
  let timers: ReturnType<typeof setTimeout>[] = []

  function clearTimers() {
    timers.forEach(clearTimeout)
    timers = []
  }

  function sampleFrames(t: number) {
    if (lastFrame) frameIntervals.push(t - lastFrame)
    lastFrame = t
    if (phase === 'running') rafId = requestAnimationFrame(sampleFrames)
  }

  function start() {
    trials = Array.from({ length: ATTENTION_TASK.trials }, () => ({
      isTarget: Math.random() < ATTENTION_TASK.targetProbability,
      responded: false,
      rtMs: null,
    }))
    idx = 0
    frameIntervals = []
    lastFrame = 0
    progress = 0
    phase = 'running'
    rafId = requestAnimationFrame(sampleFrames)
    nextTrial()
  }

  function nextTrial() {
    if (idx >= trials.length) return finish()
    responded = false
    currentIsTarget = trials[idx].isTarget
    stimulusVisible = true
    progress = Math.round((idx / trials.length) * 100)
    onset = performance.now()
    timers.push(setTimeout(() => (stimulusVisible = false), ATTENTION_TASK.stimulusMs))
    const isi = ATTENTION_TASK.isiMs + (Math.random() * 2 - 1) * ATTENTION_TASK.isiJitterMs
    timers.push(
      setTimeout(() => {
        idx++
        nextTrial()
      }, ATTENTION_TASK.stimulusMs + isi),
    )
  }

  function respond() {
    if (phase !== 'running' || responded) return
    responded = true
    trials[idx].responded = true
    trials[idx].rtMs = performance.now() - onset
  }

  function finish() {
    phase = 'done'
    cancelAnimationFrame(rafId)
    clearTimers()
    outcome = computeAttention(trials, { frameIntervalsMs: frameIntervals }, Date.now())
    onDone?.(outcome)
  }

  function onKey(e: KeyboardEvent) {
    if (e.repeat) return // ignore auto-repeat so a held key isn't counted as many responses
    if (e.code === 'Space') {
      e.preventDefault()
      respond()
    }
  }

  onDestroy(() => {
    cancelAnimationFrame(rafId)
    clearTimers()
  })
</script>

<svelte:window onkeydown={onKey} />

<div class="task card">
  {#if phase === 'intro'}
    <h2>{ATTENTION_TASK.title}</h2>
    <p class="muted">
      Shapes will flash one at a time. <strong>Respond</strong> — press <kbd>Space</kbd> or
      tap — as fast as you can whenever you see a <span class="target">●</span>, and
      <strong>do nothing</strong> when you see a <span class="nontarget">■</span>. About
      {Math.round((ATTENTION_TASK.trials * (ATTENTION_TASK.stimulusMs + ATTENTION_TASK.isiMs)) / 1000)} seconds.
    </p>
    <p class="faint">
      This is a self-observation exercise, <strong>not a diagnostic test</strong>. Browser
      timing is imperfect; the results say how precise this run’s clock was.
    </p>
    <button class="primary" onclick={start}>Start</button>
  {:else if phase === 'running'}
    <!-- SVG width attribute (not inline style) keeps this CSP-clean under style-src 'self'. -->
    <svg class="progress" viewBox="0 0 100 2" preserveAspectRatio="none" aria-hidden="true">
      <rect x="0" y="0" width="100" height="2" class="track" />
      <rect x="0" y="0" width={progress} height="2" class="fill" />
    </svg>
    <button class="stage" onclick={respond} aria-label="Respond">
      {#if stimulusVisible}
        <span class={currentIsTarget ? 'target' : 'nontarget'}>{currentIsTarget ? '●' : '■'}</span>
      {/if}
    </button>
    <p class="hint faint">Press <kbd>Space</kbd> or tap for ● only.</p>
  {:else if outcome}
    <h2>{ATTENTION_TASK.title} — this run</h2>
    {#if outcome.timing.flag === 'lower-precision'}
      <p class="warn">⚠ Lower-precision run (frame jitter {outcome.timing.frameJitterMs} ms, {outcome.timing.droppedFrames} dropped frames). Reaction-time spread is unreliable here; the counts below are still meaningful.</p>
    {/if}
    <ul class="metrics">
      <li><b>{outcome.metrics.omissions}</b> missed targets (omissions)</li>
      <li><b>{outcome.metrics.commissions}</b> responses to ■ (commissions)</li>
      <li><b>{outcome.metrics.accuracyPct}%</b> accuracy</li>
      <li>
        Reaction time <b>{outcome.metrics.rtMeanMs} ms</b>
        {#if outcome.timing.flag === 'ok' && outcome.metrics.hits >= 3}
          · variability {outcome.metrics.rtVariabilityMs} ms ± ~{outcome.metrics.rtVariabilityJitterMs} ms clock jitter
        {/if}
      </li>
    </ul>
    <p class="faint">
      Counts (omissions/commissions/accuracy) are robust; reaction-time figures are
      within-session, same-machine only, and are not comparable across devices or clinical.
    </p>
    <button onclick={() => (phase = 'intro')}>Run again</button>
  {/if}
</div>

<style>
  .task { display: flex; flex-direction: column; gap: var(--space-4); max-width: 40rem; align-items: flex-start; }
  .stage {
    width: 100%; height: 16rem; display: flex; align-items: center; justify-content: center;
    background: var(--paper-bg); border: 1px solid var(--hairline); border-radius: var(--radius);
    font-size: 5rem; line-height: 1; cursor: pointer;
  }
  .target { color: var(--mood-5); }
  .nontarget { color: var(--ink-soft); }
  .progress { width: 100%; height: 0.4rem; display: block; }
  .progress .track { fill: var(--paper-bg); }
  .progress .fill { fill: var(--mood-4); }
  .hint { margin: 0; }
  kbd { font-family: var(--font-mono); background: var(--paper-bg); border: 1px solid var(--border-strong); border-radius: 4px; padding: 0 0.3rem; }
  .metrics { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-2); }
  .warn { background: var(--mood-3-wash); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); margin: 0; color: var(--ink-soft); }
  button.primary, button:not(.stage) { align-self: flex-start; }
</style>
