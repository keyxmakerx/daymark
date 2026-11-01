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
  import { verifyGrantBlob, hasCapability, GrantAddressError } from '../../therapist/grant'
  import { isLive, touch } from '../../therapist/session'
  import { zeroize } from '../../therapist/keyStore'
  import type { UnlockedContext } from '../../therapist/context'
  import LoginGate from './LoginGate.svelte'
  import AllowedPanel from './AllowedPanel.svelte'
  import LeaveRelationship from './LeaveRelationship.svelte'
  import AssignSurface from './AssignSurface.svelte'
  import GamePlanAuthor from './GamePlanAuthor.svelte'
  import SharedDataView from './SharedDataView.svelte'
  import SignInScreen from './SignInScreen.svelte'
  import TodayScreen from './TodayScreen.svelte'
  import CalendarScreen from './CalendarScreen.svelte'
  import ClientRecordScreen from './ClientRecordScreen.svelte'
  import TimersScreen from '../timers/TimersScreen.svelte'
  import NotesScreen from '../notes/NotesScreen.svelte'
  import { PRESETS } from '../../timers/timers'
  import {
    DISCIPLINES, PROFILES, defaultDisciplineStorage, loadDiscipline, presetsFor, saveDiscipline,
    type Discipline,
  } from '../../therapist/discipline'
  import type { BackupData } from '../../backup'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import { AppShell, Callout, NavRail, PageHeader, type NavGroup, type NavItem } from '../ui'

  type Tab = 'allowed' | 'today' | 'calendar' | 'record' | 'assign' | 'gameplan' | 'shared' | 'timers' | 'notes'

  let ctx = $state<UnlockedContext | null>(null)
  let grant = $state<Grant | null>(null)
  let grantError = $state('')
  let tab = $state<Tab>('allowed')

  /*
   * Which kind of clinician is at this console. It reorders the read tabs and leads the timers with this discipline's own preset; it gates nothing (discipline.ts).
   */
  const disciplineStore = defaultDisciplineStorage()
  let discipline = $state<Discipline>(loadDiscipline(disciplineStore))
  const profile = $derived(PROFILES[discipline])
  function chooseDiscipline(d: Discipline) {
    discipline = d
    saveDiscipline(disciplineStore, d)
  }

  /*
   * THE RAIL (the calmer layout from the signed-off design). The console sits in the same frame as
   * the practice and admin consoles: a rail of places, the page's own title above, one thing on the
   * screen at a time. What the rail offers is still decided only by the verified grant: a section
   * the grant does not reach is left off, because a tab that can only ever be empty for this
   * clinician would advertise a surface they do not have.
   */
  const TITLES: Record<Tab, string> = {
    allowed: 'What you are allowed',
    today: 'Today',
    calendar: 'Calendar',
    record: 'Record',
    shared: 'Shared data',
    assign: 'Assign',
    gameplan: 'Game plan',
    timers: 'Timers',
    notes: 'Your notes',
  }
  const READ_LABEL: Record<'today' | 'calendar' | 'record' | 'shared', string> = {
    today: 'Today',
    calendar: 'Calendar',
    record: 'Record',
    shared: 'Shared data',
  }
  const nav = $derived.by((): NavGroup[] => {
    const groups: NavGroup[] = []
    if (grant && hasCapability(grant, 'read.share')) {
      // All four read the same bundle, so all four sit behind the same capability.
      groups.push({ label: 'Their share', items: profile.tabOrder.map((id) => ({ id, label: READ_LABEL[id] })) })
    }
    const send: NavItem[] = []
    if (canAssign) send.push({ id: 'assign', label: 'Assign' })
    if (grant && hasCapability(grant, 'authorGamePlan')) send.push({ id: 'gameplan', label: 'Game plan' })
    if (send.length) groups.push({ label: 'Send', items: send })
    // Not behind any capability: neither reads anything of the client's, and both stay in this
    // browser. Notes are the clinician's own writing, locked to their key (notes/vault.ts).
    groups.push({ label: 'Tools', items: [{ id: 'notes', label: 'Notes' }, { id: 'timers', label: 'Timers' }] })
    groups.push({ label: 'This browser', items: [{ id: 'allowed', label: 'Allowed' }] })
    return groups
  })
  /* Changing place only changes what is drawn. It never fetches: opening a share is a deliberate
     act on the Shared data page. */
  const go = (id: string) => (tab = id as Tab)

  /* The clinician's locked notebook on the server (#548): the session's own relationship, nothing else. */
  const notesRemote = $derived(
    ctx
      ? {
          read: () => ctx!.client.readNotes(ctx!.session),
          write: (basedOn: number, blob: string) => ctx!.client.writeNotes(ctx!.session, basedOn, blob),
        }
      : null,
  )

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
    // A lock notice describes the session that just ended; this is a new one.
    locked = false
    // Whatever the last leave said is stale the moment somebody signs in again — on this machine
    // that is a different relationship, since the one that was left cannot be signed into at all.
    leftNotice = ''
    // Fetch + verify the owner-signed grant before rendering any granted UI.
    try {
      const current = await c.client.getCurrent(c.session, 'grants', 'grant')
      if (!current) {
        grantError = 'No grant has been published for you. The owner grants capabilities from their console.'
        return
      }
      grant = verifyGrantBlob(current.bytes, c.pinnedOwnerSignPub, c.therapistFp)
    } catch (e) {
      // Refuse to trust: an unverifiable grant, or one written for someone else, yields no
      // capabilities.
      grant = null
      grantError =
        e instanceof GrantAddressError
          ? 'This grant names a different clinician key than the one on this device, so none of it is shown. The owner can publish a grant for this key from their console.'
          : 'Refused to trust the grant — it did not verify against the pinned owner key.'
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
    // The person's own Log out says nothing afterwards: they know what they did.
    locked = false
  }

  /*
   * THE AUTOMATIC LOCK SAYS WHAT HAPPENED (#262).
   *
   * Everything logout() does, and then one flag for the sign-in screen, which states the rule and
   * that nothing else changed (SCREEN_COPY.lockedNotice). Without it the page simply returned to
   * sign-in, which reads as something having gone wrong, or as someone else having done it. The
   * flag is component state and nothing more: no storage call, so it cannot outlive this tab or
   * tell anyone else that a session was here.
   */
  let locked = $state(false)
  function lock() {
    logout()
    locked = true
  }

  /*
   * The clinician ended the relationship (issue #91), and this is what happens to the screen.
   *
   * Everything logout() does, minus the network call: the leave module has already signed out, and
   * a second attempt on a session the server deleted would be a request that can only fail. The
   * keys are still zeroized, because they are in memory here and their owner has just said they are
   * finished with them.
   *
   * THE NOTICE SURVIVES THE TEARDOWN, which is the reason this is not just logout(). Dropping `ctx`
   * unmounts the panel the sentence was rendered in, so a clinician would confirm something
   * irreversible and land on a blank sign-in screen. It is carried out here and rendered on the
   * screen they arrive at instead.
   */
  let leftNotice = $state('')
  function onLeft(notice: string) {
    if (ctx) zeroize(ctx.keys)
    ctx = null
    grant = null
    grantError = ''
    tab = 'allowed'
    shared = null
    leftNotice = notice
    locked = false
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
    if (ctx && !live) lock()
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
  {#if leftNotice}
    <!--
      What just happened, on the screen the clinician lands on. A statement, not a confirmation:
      no tone, no tick, no colour. Nothing went wrong and nothing is being celebrated — they asked
      for something and this is what it did.
    -->
    <p class="left-notice" role="status">{leftNotice}</p>
  {/if}
  <SignInScreen {locked}>
    {#snippet credentials()}
      <LoginGate onunlock={onUnlock} standalone={false} />
    {/snippet}
  </SignInScreen>
{:else}
  <AppShell>
    {#snippet rail()}
      <NavRail groups={nav} active={tab} onselect={go} title="Daymark" subtitle="Clinician console">
        {#snippet footer()}
          <div class="discipline">
            <label class="d-label" for="discipline">I work as a</label>
            <select id="discipline" value={discipline} onchange={(e) => chooseDiscipline(e.currentTarget.value as Discipline)} aria-describedby="discipline-note">
              {#each DISCIPLINES as d (d)}
                <option value={d}>{PROFILES[d].label}</option>
              {/each}
            </select>
            <span class="d-note" id="discipline-note">{profile.leadsWith} It arranges this console and never changes what you can read.</span>
          </div>
          <div class="topline">
            <button class="lock" onclick={logout}>Log out</button>
          </div>
        {/snippet}
      </NavRail>
    {/snippet}

    {#snippet header()}
      <PageHeader title={TITLES[tab]} />
    {/snippet}

    <div class="page">
      <LowerAssuranceBanner />

      {#if grantError}
        <Callout tone="critical">{grantError}</Callout>
      {/if}

      <!--
        Kept mounted while the console is signed in, and only hidden on other pages, so a running
        session timer still ends on time, and its banner still shows, while the clinician reads the
        record.
      -->
      <TimersScreen open={tab === 'timers'} heading={false} presets={presetsFor(discipline, PRESETS)} />

      {#if tab === 'notes'}
        <NotesScreen
          boxPrivateKey={ctx.keys.box.privateKey}
          therapistFp={ctx.therapistFp}
          kind={profile.noteKind}
          sections={profile.noteSections}
          {now}
          remote={notesRemote}
        />
      {:else if grant && tab !== 'timers'}
        {#if tab === 'allowed'}
          <AllowedPanel {grant} ownerSigningFp={ctx.pinnedOwnerSigningFp} therapistFp={ctx.therapistFp} />
          <!--
            At the FOOT of this page, and deliberately not in the rail beside Log out: two
            adjacent controls that both end a session, one of them permanently, is a misclick with
            no recovery. See LeaveRelationship.svelte for the rest of the reasoning.
          -->
          <LeaveRelationship {ctx} onleft={onLeft} />
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
          <SharedDataView {ctx} onopen={(d) => (shared = d)} onended={lock} />
        {/if}
      {/if}
    </div>
  </AppShell>
{/if}

{#snippet needShare()}
  <!--
    Not an error and not styled as one. Nothing has gone wrong: the share simply has not been
    opened in this session yet, and opening it is a deliberate act rather than something navigating
    to a tab should do behind the person's back.
  -->
  <Callout title="No shared data open">
    <p>
      Open the share on the <strong>Shared data</strong> page and this one will have something to
      draw. Nothing is fetched by moving between pages.
    </p>
  </Callout>
{/snippet}

<style>
  /* One column, capped at the reading measure, inside the shell's body. */
  .page {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    max-width: var(--maxw);
    padding-top: var(--space-4);
  }
  .left-notice {
    max-width: var(--maxw);
    margin: var(--space-5) auto 0;
    padding: 0 var(--space-4);
    color: var(--ink-text);
    font-size: 0.9rem;
    line-height: 1.55;
  }
  .topline { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
  .discipline { display: flex; flex-direction: column; align-items: flex-start; gap: var(--space-2); margin-bottom: var(--space-3); }
  .d-label { font-size: 0.72rem; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase; color: var(--chrome-soft); }
  .d-note { font-family: var(--font-text); font-size: 0.8rem; color: var(--chrome-soft); line-height: 1.4; }
  .discipline select { align-self: stretch; font: inherit; font-family: var(--font-text); font-size: 0.9rem; min-height: 2.25rem; padding: 0 var(--space-2); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-sheet); color: var(--ink-text); }

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
