<script lang="ts">
  import { CATALOG, getInstrument, PROVENANCE_LABEL, PROVENANCE_GLYPH } from '../instruments'
  import type { InstrumentResult, TaskResult } from '../instruments/types'
  import { ATTENTION_TASK } from '../tasks/attention'
  import QuestionnaireRunner from './QuestionnaireRunner.svelte'
  import AttentionTask from './AttentionTask.svelte'

  type Selection = { kind: 'instrument'; id: string } | { kind: 'task' } | null
  let selection = $state<Selection>(null)
  let lastResult = $state<InstrumentResult | TaskResult | null>(null)

  const def = $derived(selection?.kind === 'instrument' ? getInstrument(selection.id) : undefined)

  function back() {
    selection = null
    lastResult = null
  }

  function record(r: InstrumentResult | TaskResult) {
    lastResult = r
  }

  // Results stay on this device. Export mirrors the planned BackupData v14 shape so they can
  // later fold into the encrypted snapshot and sync symmetrically (COMPANION_FEATURES.md §5).
  function download() {
    if (!lastResult) return
    const blob = new Blob([JSON.stringify(lastResult, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `daymark-${lastResult.kind === 'instrument' ? lastResult.instrumentId : lastResult.taskId}-result.json`
    a.click()
    URL.revokeObjectURL(url)
  }
</script>

<div class="assess">
  {#if !selection}
    <p class="muted lead">
      Sit-down self-checks and a focus exercise for a big screen. Everything runs and stays
      on this device — <strong>non-diagnostic</strong>, license-clean, and never uploaded.
    </p>
    <ul class="menu">
      {#each CATALOG as inst (inst.instrumentId)}
        <li>
          <button onclick={() => (selection = { kind: 'instrument', id: inst.instrumentId })}>
            <span class="t">{inst.title}</span>
            <span class="s faint">Questionnaire · ~{inst.estimatedMinutes ?? 3} min · <span class="prov prov-{inst.provenance.tier}">{PROVENANCE_GLYPH[inst.provenance.tier]} {PROVENANCE_LABEL[inst.provenance.tier]}</span></span>
          </button>
        </li>
      {/each}
      <li>
        <button onclick={() => (selection = { kind: 'task' })}>
          <span class="t">{ATTENTION_TASK.title}</span>
          <span class="s faint">Focus & timing task · ~1 min</span>
        </button>
      </li>
    </ul>
  {:else}
    <div class="runbar">
      <button onclick={back}>← All self-checks</button>
      {#if lastResult}<button onclick={download}>Download result (JSON)</button>{/if}
    </div>

    {#if selection.kind === 'instrument' && def}
      <QuestionnaireRunner {def} onDone={record} />
    {:else if selection.kind === 'task'}
      <AttentionTask onDone={record} />
    {/if}
  {/if}
</div>

<style>
  .assess { display: flex; flex-direction: column; gap: var(--space-4); }
  .lead { margin: 0; max-width: 42rem; }
  .menu { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-3); max-width: 34rem; }
  .menu button { width: 100%; display: flex; flex-direction: column; align-items: flex-start; gap: 0.15rem; padding: var(--space-3) var(--space-4); text-align: left; }
  .menu .t { font-family: var(--font-display); font-size: 1.05rem; }
  .menu .s { font-size: 0.85rem; }
  .prov { font-weight: 600; }
  .prov-validated { color: var(--mood-5); }
  .prov-custom { color: var(--mood-2); }
  .runbar { display: flex; gap: var(--space-3); }
</style>
