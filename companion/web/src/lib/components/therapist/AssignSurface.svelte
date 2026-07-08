<script lang="ts">
  /*
   * Capability-scoped ASSIGN surface. Only sections whose capability is granted are shown; the
   * builders draw from catalog.ts (honesty-gated). Every draft runs validateAssignment as a live
   * pre-flight so the therapist sees the SAME rejection the owner will apply. Publish goes through a
   * step-up confirm.
   */
  import type { Grant, AssignmentType, AssignmentPayload, Cadence } from '../../assignments/types'
  import { hasCapability } from '../../therapist/grant'
  import { assignableQuestionnaires, assignableTasks } from '../../therapist/catalog'
  import { PROVENANCE_LABEL, PROVENANCE_GLYPH } from '../../instruments'
  import { buildAssignment, preflight, publishAssignment } from '../../therapist/assignClient'
  import { describeAssignment } from '../../assignments/describe'
  import type { UnlockedContext } from '../../therapist/context'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'
  import StepUpDialog from './StepUpDialog.svelte'

  let {
    ctx,
    grant,
    ownerBoxPub,
  }: { ctx: UnlockedContext; grant: Grant; ownerBoxPub: Uint8Array } = $props()

  const questionnaires = assignableQuestionnaires()
  const tasks = assignableTasks()

  // Draft state (one active draft at a time for simplicity).
  let draftType = $state<AssignmentType>('questionnaire')
  let selectedInstrument = $state(questionnaires[0]?.id ?? '')
  let selectedTask = $state(tasks[0]?.id ?? '')
  let goalTitle = $state('')
  let cadenceEvery = $state<Cadence['every']>('week')
  let cadenceCount = $state(1)
  let note = $state('')

  let status = $state('')
  let error = $state('')
  let stepUpOpen = $state(false)

  const selectedTier = $derived(questionnaires.find((q) => q.id === selectedInstrument)?.tier)

  function currentPayload(): AssignmentPayload | null {
    switch (draftType) {
      case 'questionnaire': return selectedInstrument ? { instrumentId: selectedInstrument } : null
      case 'task': return selectedTask ? { taskId: selectedTask } : null
      case 'goal': return goalTitle.trim() ? { title: goalTitle.trim() } : null
      case 'reminder': return { every: cadenceEvery, count: cadenceCount }
      default: return null
    }
  }

  const draft = $derived.by(() => {
    const payload = currentPayload()
    if (!payload) return null
    const cadence = draftType === 'questionnaire' || draftType === 'task' ? { every: cadenceEvery, count: cadenceCount } : undefined
    return buildAssignment({ type: draftType, payload, cadence, note: note.trim() || undefined }, ctx.therapistFp)
  })

  const check = $derived(draft ? preflight(draft, grant) : null)
  const previewText = $derived(draft ? describeAssignment(draft) : '')

  async function publish() {
    error = ''
    status = ''
    if (!draft) { error = 'Complete the assignment first.'; return }
    try {
      const res = await publishAssignment(draft, grant, ctx.keys, ownerBoxPub, ctx.client, ctx.session)
      if (res.ok) status = `Published assignment v${res.version}.`
      else error = res.error ?? 'Assignment was rejected.'
    } catch (e) {
      error = e instanceof Error ? e.message : 'Publish failed.'
    }
  }
</script>

<section class="assign">
  <NonDiagnosticBanner />
  <h3>Assign a self-check, task, goal, or reminder</h3>

  <div class="tabs types" role="group" aria-label="Assignment type">
    {#if hasCapability(grant, 'assign.questionnaire')}
      <button class:active={draftType === 'questionnaire'} aria-pressed={draftType === 'questionnaire'} onclick={() => (draftType = 'questionnaire')}>Self-check</button>
    {/if}
    {#if hasCapability(grant, 'assign.task')}
      <button class:active={draftType === 'task'} aria-pressed={draftType === 'task'} onclick={() => (draftType = 'task')}>Task</button>
    {/if}
    {#if hasCapability(grant, 'assign.goal')}
      <button class:active={draftType === 'goal'} aria-pressed={draftType === 'goal'} onclick={() => (draftType = 'goal')}>Goal</button>
    {/if}
    {#if hasCapability(grant, 'assign.reminder')}
      <button class:active={draftType === 'reminder'} aria-pressed={draftType === 'reminder'} onclick={() => (draftType = 'reminder')}>Reminder</button>
    {/if}
  </div>

  {#if draftType === 'questionnaire'}
    {#if hasCapability(grant, 'assign.questionnaire')}
      <label class="field"><span>Self-check</span>
        <select bind:value={selectedInstrument}>
          {#each questionnaires as q (q.id)}<option value={q.id}>{q.title}{q.tier ? ' · ' + PROVENANCE_LABEL[q.tier] : ''}</option>{/each}
        </select>
      </label>
      {#if selectedTier}<p class="tiernote faint">{PROVENANCE_GLYPH[selectedTier]} {PROVENANCE_LABEL[selectedTier]} — the person sees this label when they take it.</p>{/if}
    {:else}
      <p class="denied faint">You do not have the "Assign self-checks" capability.</p>
    {/if}
  {:else if draftType === 'task'}
    {#if hasCapability(grant, 'assign.task')}
      <label class="field"><span>Task</span>
        <select bind:value={selectedTask}>
          {#each tasks as t (t.id)}<option value={t.id}>{t.title}</option>{/each}
        </select>
      </label>
    {:else}
      <p class="denied faint">You do not have the "Assign tasks" capability.</p>
    {/if}
  {:else if draftType === 'goal'}
    {#if hasCapability(grant, 'assign.goal')}
      <label class="field"><span>Goal title</span><input type="text" bind:value={goalTitle} placeholder="e.g. A short walk after lunch" /></label>
    {:else}
      <p class="denied faint">You do not have the "Suggest goals" capability.</p>
    {/if}
  {:else if draftType === 'reminder'}
    {#if !hasCapability(grant, 'assign.reminder')}
      <p class="denied faint">You do not have the "Suggest reminders" capability.</p>
    {/if}
  {/if}

  {#if draftType === 'questionnaire' || draftType === 'task' || draftType === 'reminder'}
    <div class="cadence">
      <label class="field"><span>Every</span>
        <select bind:value={cadenceEvery}>
          <option value="day">day</option><option value="week">week</option><option value="month">month</option>
        </select>
      </label>
      <label class="field"><span>Count</span><input type="number" min="1" bind:value={cadenceCount} /></label>
    </div>
  {/if}

  <label class="field"><span>Note (optional)</span><input type="text" bind:value={note} placeholder="Short context for this person" /></label>

  {#if draft}
    <div class="preview" class:bad={check && !check.ok}>
      <p class="pv-line">{previewText}</p>
      {#if check && !check.ok}
        <ul class="errs">{#each check.errors as e (e)}<li>{e}</li>{/each}</ul>
      {/if}
    </div>
  {/if}

  <button class="primary" onclick={() => (stepUpOpen = true)} disabled={!draft || (check ? !check.ok : true)}>Publish assignment</button>
  {#if status}<p class="ok" role="status">{status}</p>{/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</section>

<StepUpDialog open={stepUpOpen} action="Publish assignment" onconfirm={() => { stepUpOpen = false; publish() }} oncancel={() => (stepUpOpen = false)} />

<style>
  .assign { display: flex; flex-direction: column; gap: var(--space-3); }
  .assign h3 { margin: 0; }
  .tabs { display: flex; gap: var(--space-2); flex-wrap: wrap; }
  .tabs button.active { background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }
  .field { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; max-width: 28rem; }
  .field span { color: var(--ink-soft); }
  .cadence { display: flex; gap: var(--space-3); }
  select, input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .preview { border: 1px solid var(--mood-5); background: var(--mood-5-wash); border-radius: var(--radius-sm); padding: var(--space-3); }
  .preview.bad { border-color: var(--mood-1); background: var(--mood-1-wash); }
  .pv-line { margin: 0; }
  .errs { margin: var(--space-2) 0 0; padding-left: var(--space-4); font-size: 0.85rem; color: var(--mood-1); }
  .denied { margin: 0; }
  .primary { align-self: flex-start; background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }
  .ok { margin: 0; color: var(--mood-5); }
  .error { margin: 0; color: var(--mood-1); }
</style>
