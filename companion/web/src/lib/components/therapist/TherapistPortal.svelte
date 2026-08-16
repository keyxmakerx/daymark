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
  import SignInScreen from './SignInScreen.svelte'
  import TodayScreen from './TodayScreen.svelte'
  import CalendarScreen from './CalendarScreen.svelte'
  import ClientRecordScreen from './ClientRecordScreen.svelte'
  import type { BackupData } from '../../backup'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import { Callout } from '../ui'

  type Tab = 'allowed' | 'today' | 'calendar' | 'record' | 'assign' | 'gameplan' | 'shared'

  let ctx = $state<UnlockedContext | null>(null)
  let grant = $state<Grant | null>(null)
  let grantError = $state('')
  let tab = $state<Tab>('allowed')

  /*
   * The opened share, held HERE rather than fetched per tab.
   *
   * Four surfaces read the same bundle — Today, Calendar, Client record and Shared data — and a
   * fetch inside each would decrypt the same payload four times and, worse, make "open the share"
   * an ambient side effect of navigating. It stays one deliberate act: the person clicks Open in
   * Shared data, and the other surfaces then have something to draw. Until then they say so.
   *
   * Not a module-level store, deliberately. This is decrypted personal data; scoping it to the
   * component that owns the session means logout below is the only lifetime it can have.
   */
  let shared = $state<BackupData | null>(null)

  /*
   * The clock the dated screens read. `$derived` tracks only what it reads, and a Date.now() inside
   * a callee is invisible to it — the bug that shipped in this very file's idle guard. So the
   * screens take `now` as a value and this ticks it.
   */
  const CLOCK_MS = 60_000
  let now = $state(Date.now())
  $effect(() => {
    if (!ctx) return
    const id = setInterval(() => (now = Date.now()), CLOCK_MS)
    return () => clearInterval(id)
  })

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
    // The decrypted bundle goes with the session. Leaving it behind would keep a person's records
    // in memory on a machine whose therapist has just said they were finished with it.
    shared = null
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
  <!--
    The contract wraps the gate; it does not replace it. LoginGate owns the only authentication
    path in this product, and a sign-in screen that reimplemented it would be a second path to
    audit. `standalone={false}` because SignInScreen already renders the fixed lower-assurance notice as
    part of the contract, and its own headings — the gate should add neither a second copy of the
    notice nor a third "sign in" heading.
  -->
  <SignInScreen>
    {#snippet credentials()}
      <LoginGate onunlock={onUnlock} standalone={false} />
    {/snippet}
  </SignInScreen>
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
          <!--
            All four read the same bundle, so all four sit behind the same capability. Showing a
            Calendar tab to someone with no read.share would advertise a surface that can only ever
            be empty for them.
          -->
          <button class:active={tab === 'today'} aria-pressed={tab === 'today'} onclick={() => (tab = 'today')}>Today</button>
          <button class:active={tab === 'calendar'} aria-pressed={tab === 'calendar'} onclick={() => (tab = 'calendar')}>Calendar</button>
          <button class:active={tab === 'record'} aria-pressed={tab === 'record'} onclick={() => (tab = 'record')}>Record</button>
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
      {:else if tab === 'today'}
        {#if shared}<TodayScreen data={shared} {now} />{:else}{@render needShare()}{/if}
      {:else if tab === 'calendar'}
        {#if shared}<CalendarScreen data={shared} {now} />{:else}{@render needShare()}{/if}
      {:else if tab === 'record'}
        {#if shared}<ClientRecordScreen data={shared} {now} />{:else}{@render needShare()}{/if}
      {:else if tab === 'shared'}
        <SharedDataView {ctx} onopen={(d) => (shared = d)} />
      {/if}
    {/if}
  </section>
{/if}

{#snippet needShare()}
  <!--
    Not an error and not styled as one. Nothing has gone wrong: the share simply has not been
    opened in this session yet, and opening it is a deliberate act rather than something navigating
    to a tab should do behind the person's back.
  -->
  <Callout title="No shared data open yet">
    <p>
      Open the share on the <strong>Shared data</strong> tab and this surface will have something to
      draw. Nothing is fetched by moving between tabs.
    </p>
  </Callout>
{/snippet}

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
