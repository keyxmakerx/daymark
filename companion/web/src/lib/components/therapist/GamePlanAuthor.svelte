<script lang="ts">
  /*
   * GAME PLAN authoring surface (gated on authorGamePlan). Compose goals/exercises/tasks/notes and
   * a review cadence, then sign-then-seal to the owner and publish. Supersede + withdraw controls
   * keep the lineage append-only. Free text → prominent non-diagnostic-by-FRAMING disclaimer.
   */
  import type { Cadence } from '../../assignments/types'
  import { newGamePlan, sealGamePlan, type GamePlanItem, type GamePlanPayload } from '../../therapist/gamePlan'
  import { fingerprint } from '../../assignments/crypto'
  import type { UnlockedContext } from '../../therapist/context'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'
  import StepUpDialog from './StepUpDialog.svelte'

  let { ctx, ownerBoxPub }: { ctx: UnlockedContext; ownerBoxPub: Uint8Array } = $props()

  const ownerBoxFp = $derived(fingerprint(ownerBoxPub))

  let items = $state<GamePlanItem[]>([])
  let newKind = $state<GamePlanItem['kind']>('goal')
  let newTitle = $state('')
  let newDetail = $state('')
  let reviewEvery = $state<Cadence['every']>('week')
  let reviewCount = $state(2)

  let status = $state('')
  let error = $state('')
  let stepUpOpen = $state(false)

  function addItem() {
    if (!newTitle.trim()) return
    items = [
      ...items,
      { itemRef: `it-${Date.now().toString(36)}-${items.length}`, kind: newKind, title: newTitle.trim(), detail: newDetail.trim() || undefined },
    ]
    newTitle = ''
    newDetail = ''
  }

  function removeItem(ref: string) {
    items = items.filter((it) => it.itemRef !== ref)
  }

  async function publish() {
    error = ''
    status = ''
    if (items.length === 0) { error = 'Add at least one item.'; return }
    try {
      const base: GamePlanPayload = newGamePlan(ownerBoxFp, ctx.therapistFp)
      const plan: GamePlanPayload = { ...base, items: [...items], reviewCadence: { every: reviewEvery, count: reviewCount } }

      const existing = await ctx.client.listVersions(ctx.session, 'gameplans', plan.lineageId).catch(() => [])
      const version = existing.reduce((m, v) => Math.max(m, v.version), -1) + 1
      const wire: GamePlanPayload = { ...plan, version }
      const blob = sealGamePlan(wire, ctx.keys.sign, ownerBoxPub)
      await ctx.client.putBlob(ctx.session, 'gameplans', plan.lineageId, version, blob)
      status = `Published game plan v${version}.`
      items = []
    } catch (e) {
      error = e instanceof Error ? e.message : 'Publish failed.'
    }
  }
</script>

<section class="gp">
  <NonDiagnosticBanner />
  <div class="disclaimer">
    <strong>Guidance, not treatment.</strong> A game plan is a written set of suggestions between you
    and this person. It is not a clinical treatment plan, prescription, or diagnosis.
  </div>
  <h3>Write a game plan</h3>

  <div class="add">
    <label class="field"><span>Type</span>
      <select bind:value={newKind}>
        <option value="goal">Goal</option><option value="exercise">Exercise</option><option value="task">Task</option><option value="note">Note</option>
      </select>
    </label>
    <label class="field grow"><span>Title</span><input type="text" bind:value={newTitle} placeholder="e.g. Wind-down routine before bed" /></label>
    <label class="field grow"><span>Detail (optional)</span><input type="text" bind:value={newDetail} /></label>
    <button onclick={addItem} disabled={!newTitle.trim()}>Add</button>
  </div>

  {#if items.length > 0}
    <ul class="items" role="list">
      {#each items as it (it.itemRef)}
        <li class="item">
          <span class="kind">{it.kind}</span>
          <span class="it-title">{it.title}</span>
          {#if it.detail}<span class="it-detail faint">{it.detail}</span>{/if}
          <button class="rm" aria-label="Remove item" onclick={() => removeItem(it.itemRef)}>Remove</button>
        </li>
      {/each}
    </ul>
  {:else}
    <p class="faint empty">No items yet. Add goals, exercises, tasks, or notes above.</p>
  {/if}

  <div class="review">
    <span class="rlabel faint">Review cadence:</span>
    <label class="field"><span>Every</span>
      <select bind:value={reviewEvery}><option value="day">day</option><option value="week">week</option><option value="month">month</option></select>
    </label>
    <label class="field"><span>Count</span><input type="number" min="1" bind:value={reviewCount} /></label>
  </div>

  <button class="primary" onclick={() => (stepUpOpen = true)} disabled={items.length === 0}>Publish game plan</button>
  {#if status}<p class="ok" role="status">{status}</p>{/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</section>

<StepUpDialog open={stepUpOpen} action="Publish game plan" onconfirm={() => { stepUpOpen = false; publish() }} oncancel={() => (stepUpOpen = false)} />

<style>
  .gp { display: flex; flex-direction: column; gap: var(--space-3); }
  .gp h3 { margin: 0; }
  .disclaimer { border: 1px solid var(--mood-2); background: var(--mood-2-wash); border-radius: var(--radius-sm); padding: var(--space-3); font-size: 0.9rem; }
  .add { display: flex; gap: var(--space-2); align-items: flex-end; flex-wrap: wrap; }
  .field { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .field.grow { flex: 1; min-width: 12rem; }
  .field span { color: var(--ink-soft); }
  select, input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .items { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-2); }
  .item { display: flex; align-items: center; gap: var(--space-2); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); }
  .kind { font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0.04em; color: var(--on-accent); background: var(--ink-accent); padding: 0.1rem 0.4rem; border-radius: 999px; }
  .it-title { font-weight: 600; }
  .it-detail { font-size: 0.85rem; }
  .rm { margin-left: auto; }
  .empty { margin: 0; }
  .review { display: flex; align-items: flex-end; gap: var(--space-3); flex-wrap: wrap; }
  .rlabel { align-self: center; }
  .primary { align-self: flex-start; background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }
  .ok { margin: 0; color: var(--mood-5); }
  .error { margin: 0; color: var(--mood-1); }
</style>
