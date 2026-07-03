<script lang="ts">
  /*
   * Therapist portal root. Renders LoginGate until unlocked; then verifies the owner-signed Grant
   * against the pinned owner key and shows capability-gated tabs (Allowed / Assign / Game plan /
   * Shared data). Every writer + shared-data surface carries the fixed LowerAssurance +
   * NonDiagnostic banners.
   *
   * If the grant fails to verify, NO granted UI is rendered — the portal treats the server's grant
   * blob as untrusted until the owner signature checks against the pinned key.
   */
  import type { Grant } from '../../assignments/types'
  import { verifyGrantBlob, hasCapability } from '../../therapist/grant'
  import { isLive } from '../../therapist/session'
  import { zeroize } from '../../therapist/keyStore'
  import type { UnlockedContext } from '../../therapist/context'
  import LoginGate from './LoginGate.svelte'
  import AllowedPanel from './AllowedPanel.svelte'
  import AssignSurface from './AssignSurface.svelte'
  import GamePlanAuthor from './GamePlanAuthor.svelte'
  import SharedDataView from './SharedDataView.svelte'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'

  type Tab = 'allowed' | 'assign' | 'gameplan' | 'shared'

  let ctx = $state<UnlockedContext | null>(null)
  let grant = $state<Grant | null>(null)
  let grantError = $state('')
  let tab = $state<Tab>('allowed')

  async function onUnlock(c: UnlockedContext) {
    ctx = c
    grant = null
    grantError = ''
    // Fetch + verify the owner-signed grant before rendering any granted UI.
    try {
      const current = await c.client.getCurrent(c.session, 'grants', 'grant')
      if (!current) {
        grantError = 'No grant has been published for you yet — ask the owner to grant capabilities.'
        return
      }
      grant = verifyGrantBlob(current.bytes, c.pinnedOwnerSignPub)
    } catch {
      // Refuse to trust: an unverifiable grant yields no capabilities.
      grant = null
      grantError = 'Refused to trust the grant — it did not verify against the pinned owner key.'
    }
  }

  function logout() {
    if (ctx) {
      ctx.client.logout(ctx.session.csrf)
      zeroize(ctx.keys)
    }
    ctx = null
    grant = null
    grantError = ''
    tab = 'allowed'
  }

  const canAssign = $derived(
    !!grant &&
      (hasCapability(grant, 'assign.questionnaire') ||
        hasCapability(grant, 'assign.task') ||
        hasCapability(grant, 'assign.goal') ||
        hasCapability(grant, 'assign.reminder') ||
        hasCapability(grant, 'assign.largeAssessment')),
  )

  // Idle/absolute guard: lock (and zeroize) once the session is no longer live.
  const live = $derived(ctx ? isLive(ctx.session) : false)
  $effect(() => {
    if (ctx && !live) logout()
  })
</script>

{#if !ctx}
  <LoginGate onunlock={onUnlock} />
{:else}
  <section class="portal">
    <LowerAssuranceBanner />
    <div class="topline">
      <nav class="tabs" aria-label="Therapist portal section">
        <button class:active={tab === 'allowed'} aria-pressed={tab === 'allowed'} onclick={() => (tab = 'allowed')}>Allowed</button>
        {#if canAssign}
          <button class:active={tab === 'assign'} aria-pressed={tab === 'assign'} onclick={() => (tab = 'assign')}>Assign</button>
        {/if}
        {#if grant && hasCapability(grant, 'authorGamePlan')}
          <button class:active={tab === 'gameplan'} aria-pressed={tab === 'gameplan'} onclick={() => (tab = 'gameplan')}>Game plan</button>
        {/if}
        {#if grant && hasCapability(grant, 'read.share')}
          <button class:active={tab === 'shared'} aria-pressed={tab === 'shared'} onclick={() => (tab = 'shared')}>Shared data</button>
        {/if}
      </nav>
      <button class="lock" onclick={logout}>Log out</button>
    </div>

    {#if grantError}
      <p class="warn" role="alert">{grantError}</p>
    {/if}

    {#if grant}
      {#if tab === 'allowed'}
        <AllowedPanel {grant} ownerSigningFp={ctx.pinnedOwnerSigningFp} therapistFp={ctx.therapistFp} />
      {:else if tab === 'assign'}
        <AssignSurface {ctx} {grant} ownerBoxPub={ctx.ownerBoxPub} />
      {:else if tab === 'gameplan'}
        <GamePlanAuthor {ctx} ownerBoxPub={ctx.ownerBoxPub} />
      {:else if tab === 'shared'}
        <SharedDataView {ctx} />
      {/if}
    {/if}
  </section>
{/if}

<style>
  .portal { display: flex; flex-direction: column; gap: var(--space-4); }
  .topline { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); flex-wrap: wrap; }
  .tabs { display: flex; gap: var(--space-2); flex-wrap: wrap; }
  .tabs button.active { background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }
  .warn { color: var(--mood-2); background: var(--mood-2-wash); border: 1px solid var(--mood-2); border-radius: var(--radius-sm); padding: var(--space-3); margin: 0; }
</style>
