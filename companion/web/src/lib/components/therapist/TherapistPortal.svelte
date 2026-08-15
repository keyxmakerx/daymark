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
  import { isLive, touch } from '../../therapist/session'
  import { zeroize } from '../../therapist/keyStore'
  import type { UnlockedContext } from '../../therapist/context'
  import LoginGate from './LoginGate.svelte'
  import AllowedPanel from './AllowedPanel.svelte'
  import AssignSurface from './AssignSurface.svelte'
  import GamePlanAuthor from './GamePlanAuthor.svelte'
  import SharedDataView from './SharedDataView.svelte'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import { Callout } from '../ui'

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

  /*
   * IDLE / ABSOLUTE GUARD — lock and zeroize once the session stops being live.
   *
   * This was `$derived(ctx ? isLive(ctx.session) : false)` and it never fired. `isLive` reads
   * `Date.now()` *inside itself*, so the only dependency `$derived` could track was `ctx` — which
   * is written exactly twice, at unlock and at logout. The guard was therefore evaluated once, at
   * unlock, when it is true by construction, and never again. The portal never locked, `zeroize`
   * was never reached on the idle path, and the therapist's unwrapped X25519/Ed25519 reading keys
   * and the decrypted share sat in memory until the tab was closed — on, say, a clinic machine
   * someone walked away from.
   *
   * Time has to be a value the reactive system can see. Hence the tick.
   */
  const TICK_MS = 15_000
  let clock = $state(Date.now())

  $effect(() => {
    if (!ctx) return
    const id = setInterval(() => (clock = Date.now()), TICK_MS)
    return () => clearInterval(id)
  })

  const live = $derived(ctx ? isLive(ctx.session, clock) : false)

  $effect(() => {
    if (ctx && !live) logout()
  })

  /*
   * ...AND THE OTHER HALF, without which the fix above is its own bug.
   *
   * `touch()` — the function that pushes the idle deadline forward on activity — had no production
   * caller anywhere in the tree. `idleExpiresAt` was set once at login and never moved. So a
   * working clock alone would have logged a therapist out fifteen minutes after unlock while they
   * were actively reading, which is not "the guard now works", it is a different broken behaviour
   * that would have been blamed on the lock.
   *
   * Throttled, because this runs on pointer and key events and reassigning the session on every
   * keystroke would churn the reactive graph for no benefit. Only the *idle* deadline moves;
   * `absoluteExpiresAt` is untouched, so activity can never extend a session past the hard cap the
   * server set.
   */
  let lastTouch = 0
  function noteActivity() {
    if (!ctx) return
    const now = Date.now()
    if (now - lastTouch < TICK_MS) return
    lastTouch = now
    ctx = { ...ctx, session: touch(ctx.session, undefined, now) }
  }
</script>

<!--
  Activity is observed at the window, not on the portal section, so it counts wherever focus
  actually is — inside a text field, a dialog, a child component — rather than only on bubbling
  paths that happen to reach one element. Top level because `<svelte:window>` may not sit inside a
  block; `noteActivity` no-ops when there is no session, so this is inert on the login gate.
-->
<svelte:window onpointerdown={noteActivity} onkeydown={noteActivity} onfocus={noteActivity} />

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
      <Callout tone="critical">{grantError}</Callout>
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

  /*
   * The selected section is STRUCTURE — where you are in the interface — so it takes the
   * structural accent rather than solid ink. Ink stays reserved for the primary ACTION on a
   * screen (the publish buttons), which keeps "where am I" and "what will this do" visually
   * distinct instead of both shouting in the same black. aria-pressed carries the same fact
   * without colour.
   */
  .tabs button.active { background: var(--indigo); color: var(--on-accent); border-color: var(--indigo); }

  /*
   * The grant-failure slot used to be washed in --mood-2 / --mood-2-wash — a person's
   * second-worst reported day, borrowed to mean "the software refused something". What it
   * actually renders is "Refused to trust the grant — it did not verify against the pinned
   * owner key", which is the refusal case in the token contract and therefore clay, via
   * ui/Callout's critical tone: full outline, role="alert", and a screen-reader severity
   * prefix. This message is the visible face of the invariant in this file's header — an
   * unverifiable grant yields NO granted UI — so it should read as an alarm, not as a mood.
   */
</style>
