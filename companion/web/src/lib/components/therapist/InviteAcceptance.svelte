<script lang="ts">
  /*
   * THE INVITE ACCEPTANCE PAGE — the therapist's front door, which until now did not exist.
   *
   * WHAT WAS BROKEN. The server emails `{base}/portal/invite#id=…&s=…` (TherapistAuthRoutes.kt,
   * buildInviteLink). Nothing routed that path, so the link every invited clinician received was a
   * 404, and the only other way in — LoginGate — asked for nine values that no flow in the product
   * produced. The therapist half of this product could not be used at all. This screen is the
   * missing path: it takes the link, and asks a person for a passphrase they choose and the code
   * their own authenticator shows. Two things typed. Everything else is generated here or exchanged
   * with the server.
   *
   * WHY THE LOGIC IS NOT IN THIS FILE. Everything that could be got wrong quietly — the fragment
   * rule, the ordering of the ceremony, the insert-only refusals, the proof that the wrapped blob
   * reopens — lives in therapist/inviteLink.ts and therapist/inviteAccept.ts, where the node test
   * suite can hold it. This component chooses which of four things to draw and hands what a person
   * typed to those modules. That split is deliberate: a security property enforced in a Svelte
   * component is a security property no test in this repository can see.
   *
   * WHAT THIS SCREEN HOLDS IN MEMORY AND WHAT IT NEVER KEEPS. It holds the freshly generated secret
   * keys for as long as it is mounted, and zeroizes them when it goes away. It never stores the
   * passphrase and never stores unwrapped keys; the reference to the passphrase is dropped as soon
   * as the wrap is done, which is as much as a language with immutable strings allows — the bytes
   * themselves cannot be overwritten, and pretending otherwise would be the sort of claim the
   * lower-assurance banner exists to prevent.
   *
   * WHY THERE IS NO CONFIRMATION TICK ANYWHERE ON IT. The last step shows the clinician their own
   * key fingerprints and asks them to READ THEM ALOUD to the person who invited them. It
   * deliberately does not say "check they match the ones on their screen": both screens are drawn
   * by the same server, so a server that substituted a key would draw its substitute in both places
   * and the comparison would agree. A voice is the one channel it cannot redraw. The wording of
   * that step is fixed in inviteAccept.ts and asserted by a test, because it is the security
   * control.
   *
   * WHY THE LAST STEP SHOWS TWO FINGERPRINTS. It showed one — the signing key — and the owner's
   * console, correctly, will not pin a therapist without hearing both: the encryption key is the
   * one their shares are sealed to, the signing key is the one assignments are checked against,
   * and the two travel to them in a single answer from a single machine, so a check on either
   * alone leaves the other free to be swapped. With only one of them displayed anywhere on this
   * side, the second field on the owner's screen had exactly one value that would fit it — the one
   * their own console had just drawn from the record the server supplied. An owner who typed that
   * back would have been confirming the server against itself, which is the precise tautology this
   * whole exchange exists to remove. Two keys made here, two fingerprints read out.
   *
   * WHY THIS SCREEN CAN ALSO FINISH SOMETHING IT DID NOT START. The ceremony's last two steps are a
   * sign-in and a key registration, and a tab that closed between them used to end the
   * relationship: the enrolment had committed and cannot be repeated, the invitation was spent and
   * a fresh one would die at the same insert-only enrolment, and the owner's console would read
   * "nothing published yet" for the life of the relationship. Everything needed to finish is in
   * this browser's own record, so the screen offers it — a passphrase and one authenticator code,
   * the same two things the first attempt asked for.
   */
  import { onDestroy } from 'svelte'
  import { PortalClient } from '../../therapist/session'
  import {
    INVITE_FAULT_TEXT,
    apiBaseFrom,
    parseInviteFragment,
    parseInviteLink,
    withoutFragment,
    type InviteCredential,
    type InviteFault,
  } from '../../therapist/inviteLink'
  import {
    AcceptError,
    KEY_CHECK_COPY,
    MIN_PASSPHRASE_CHARS,
    beginAcceptance,
    checkPassphrase,
    completeAcceptance,
    findKeyRecord,
    groupForReading,
    portsFor,
    resumeAcceptance,
    unfinishedKeyRecords,
    type Acceptance,
    type AcceptancePorts,
    type Enrolment,
    type KeyRecord,
    type Resumption,
  } from '../../therapist/inviteAccept'
  import { zeroize } from '../../therapist/keyStore'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import { Callout, Card } from '../ui'

  let {
    fragment = typeof window !== 'undefined' ? window.location.hash : '',
    href = typeof window !== 'undefined' ? window.location.href : '',
    origin = typeof window !== 'undefined' ? window.location.origin : '',
    pathname = typeof window !== 'undefined' ? window.location.pathname : '',
    host = typeof window !== 'undefined' ? window.location.host : '',
  }: {
    /** The URL fragment carrying the invitation. A prop so the page is drawable without a window. */
    fragment?: string
    href?: string
    origin?: string
    pathname?: string
    host?: string
  } = $props()

  /*
   * Read once, at construction, rather than in an effect. The fragment is an argument this page was
   * launched with, not a value that changes while somebody is looking at it — and re-reading it
   * later would mean re-reading it after `history.replaceState` has deliberately removed it. The
   * ignore below says exactly that: capturing the initial value is the intent, not an oversight of
   * the kind the warning usually catches.
   */
  // svelte-ignore state_referenced_locally
  const initial = parseInviteFragment(fragment)
  let invite = $state<InviteCredential | null>(initial.ok ? initial.invite : null)
  let fault = $state<InviteFault | null>(initial.ok ? null : initial.fault)
  let pasted = $state('')

  let passphrase = $state('')
  let confirmation = $state('')
  let problems = $state<string[]>([])

  let enrolment = $state<Enrolment | null>(null)
  let code = $state('')
  let outcome = $state<Acceptance | null>(null)

  let busy = $state(false)
  let error = $state('')

  /*
   * THE RESCUE PATH'S STATE, kept separate from the acceptance's rather than folded into it.
   *
   * The two flows can be on screen at the same time — a passphrase card for the invitation in the
   * address bar, a finish-it card for a relationship this browser started weeks ago — and they mean
   * entirely different things by the same words. One passphrase field bound to both would offer the
   * new-passphrase rules to somebody typing an old passphrase, and one `error` would put a refusal
   * from either under whichever card the person happened to be reading. So the fields and the
   * message are per-flow.
   *
   * `busy` is deliberately NOT per-flow. It gates every button on the page, and while either of
   * these is in flight the other must not start: they reach the same server for the same
   * relationship, and a second ceremony racing a first is how this file's insert-only rules get
   * tested in production.
   */
  let unfinished = $state<KeyRecord[]>([])
  let storageFault = $state('')
  let resumeRelRef = $state('')
  let resumePassphrase = $state('')
  let resumeCode = $state('')
  let resumed = $state<Resumption | null>(null)
  let resumeError = $state('')

  /**
   * Which relationships this browser could still finish.
   *
   * Read at construction and again after anything that could change the answer, rather than in a
   * derived: `unfinishedKeyRecords` touches localStorage, a derived would re-read it on every
   * keystroke in the fields below, and an unreadable store has to be reported once rather than
   * thrown from inside the render. The throw is caught and shown, not swallowed — a browser that
   * will not say what it is holding is a fact the person needs, and reporting "nothing to finish"
   * in that situation would be the reassurance the record cannot support.
   */
  function refreshUnfinished() {
    try {
      unfinished = unfinishedKeyRecords()
      // Point the picker at something real. A bound <select> whose value matches no option renders
      // blank, which reads as "there is nothing here" on the one screen whose whole job is to say
      // that there is. Only moved when the current choice has stopped existing.
      if (!unfinished.some((r) => r.relRef === resumeRelRef)) resumeRelRef = unfinished[0]?.relRef ?? ''
      storageFault = ''
    } catch (e) {
      unfinished = []
      storageFault = e instanceof Error ? e.message : 'This browser would not say what keys it is holding.'
    }
  }
  refreshUnfinished()

  /** The record the finish-it form acts on. Falls back to the first, so nothing has to be picked. */
  const resumeRecord = $derived(unfinished.find((r) => r.relRef === resumeRelRef) ?? unfinished[0] ?? null)

  /** Built once and shared by both halves of the ceremony: it holds the sodium handle and storage. */
  let ports: AcceptancePorts | null = null
  // Same reasoning as the fragment above: the address this document was served from is an argument,
  // fixed for the life of the page, and every call in the ceremony has to go to the same one.
  // svelte-ignore state_referenced_locally
  const apiBase = apiBaseFrom(origin, pathname)

  async function portsOnce(): Promise<AcceptancePorts> {
    if (!ports) ports = await portsFor(new PortalClient(apiBase))
    return ports
  }

  function usePastedLink() {
    error = ''
    const parsed = parseInviteLink(pasted)
    if (parsed.ok) {
      invite = parsed.invite
      fault = null
      pasted = ''
    } else {
      fault = parsed.fault
    }
  }

  /**
   * Take the spent secret back out of the address bar.
   *
   * Called once the invitation has been redeemed — successfully or not — because at that point the
   * secret in the URL is either consumed or dead, and leaving it there keeps it in the address bar
   * of a machine that is often in a shared room, in whatever gets copied when somebody shares "the
   * page I'm on", and in the browser's own history and session restore. Not called before the
   * redeem: until then the fragment is the only copy of the invitation this tab has, and a reload
   * has to still work.
   */
  function dropSpentFragment() {
    if (typeof window === 'undefined' || typeof history === 'undefined') return
    try {
      history.replaceState(null, '', withoutFragment(href || window.location.href))
    } catch {
      // Some embedded and file: contexts refuse replaceState. Nothing downstream depends on it.
    }
  }

  async function accept() {
    error = ''
    problems = checkPassphrase(passphrase, confirmation)
    if (problems.length > 0 || !invite) return
    busy = true
    try {
      const p = await portsOnce()
      enrolment = await beginAcceptance(p, {
        inviteId: invite.inviteId,
        secret: invite.secret,
        passphrase,
        host,
      })
      dropSpentFragment()
      /*
       * The passphrase has done its one job. Dropping the reference is not a wipe — a JavaScript
       * string cannot be overwritten, and this one also passed through an input element — but it
       * takes the value out of this component's state and out of the field a person walked away
       * from, which is the part that is actually in reach.
       */
      passphrase = ''
      confirmation = ''
    } catch (e) {
      // Everything past the redeem has spent the invitation, so the link in the address bar is dead
      // whether this succeeded or failed. `step` is what tells the two apart.
      if (e instanceof AcceptError && e.step !== 'redeem') dropSpentFragment()
      error = e instanceof Error ? e.message : 'This invitation could not be accepted.'
    } finally {
      busy = false
    }
  }

  async function finish() {
    error = ''
    if (!enrolment) return
    busy = true
    try {
      const p = await portsOnce()
      outcome = await completeAcceptance(p, enrolment, code)
      code = ''
      // The record this ceremony just completed is no longer one of the unfinished ones, and the
      // offer to finish it has to go away with it.
      refreshUnfinished()
    } catch (e) {
      error = e instanceof Error ? e.message : 'That code was not accepted.'
    } finally {
      busy = false
    }
  }

  /**
   * Send the public keys for a relationship this browser started and never finished.
   *
   * The passphrase is dropped as soon as the call returns for the same reason and with the same
   * caveat as the acceptance path's: a JavaScript string cannot be overwritten and this one passed
   * through an input element, so this takes it out of the component's state and out of the field
   * somebody walked away from, which is the part that is in reach.
   */
  async function finishStarted() {
    resumeError = ''
    if (!resumeRecord) return
    busy = true
    try {
      const p = await portsOnce()
      resumed = await resumeAcceptance(p, resumeRecord, resumePassphrase, resumeCode)
      resumePassphrase = ''
      resumeCode = ''
      refreshUnfinished()
    } catch (e) {
      resumeError = e instanceof Error ? e.message : 'That could not be finished.'
    } finally {
      busy = false
    }
  }

  /** The wrapped blob this browser stored, offered as something a person can keep elsewhere. */
  const portalCard = $derived.by(() => {
    if (!outcome || !enrolment || !ports) return ''
    const record = findKeyRecord(enrolment.relRef, ports.storage)
    if (!record) return ''
    return JSON.stringify(
      { serverUrl: apiBase, relRef: record.relRef, credentialId: record.credentialId, wrappedKey: record.wrapped },
      null,
      2,
    )
  })

  /*
   * The keys exist in this tab and nowhere else that is not wrapped. When this screen goes away —
   * navigation, a closed tab, a route change — they go with it. Same contract as the portal's
   * logout path, which is where the equivalent omission was once a live bug. Both sets: a rescued
   * ceremony unwrapped a second copy of somebody's secret keys to read their public halves out of,
   * and that copy has exactly the same claim on being wiped as the freshly generated one.
   */
  onDestroy(() => {
    if (enrolment) zeroize(enrolment.keys)
    if (resumed) zeroize(resumed.keys)
  })
</script>

<!--
  The two values that leave this screen through a person, drawn once and used by both endings.

  Grouped in fours because the owner's console groups in fours (owner/therapistKeys.ts,
  `groupFingerprint`), so the two people in the phone call are chunking the same characters the same
  way rather than one reading in fours while the other follows in threes. Lossless: what is spoken
  is the whole value, never a summary of it.
-->
{#snippet readAloud(boxFingerprint: string, signFingerprint: string)}
  <dl class="fps">
    <dt>Encryption key fingerprint</dt>
    <dd>
      <p class="fingerprint">
        {#each groupForReading(boxFingerprint) as chunk, i (i)}<span class="chunk">{chunk}</span>{/each}
      </p>
      <span class="what">what they seal everything they send you to</span>
    </dd>
    <dt>Signing key fingerprint</dt>
    <dd>
      <p class="fingerprint">
        {#each groupForReading(signFingerprint) as chunk, i (i)}<span class="chunk">{chunk}</span>{/each}
      </p>
      <span class="what">what proves an assignment or a game plan came from you</span>
    </dd>
  </dl>
{/snippet}

<section class="accept">
  <LowerAssuranceBanner />

  <header class="intro">
    <h1>Accept an invitation</h1>
    <!--
      The lede states what the released software does and then hands the caveat back to the banner
      above it rather than paraphrasing it. A friendly restatement beside fixed honesty copy is how
      fixed honesty copy gets softened without anyone editing it — see the reasoning in
      therapist/signIn.ts, which rejects a contract clause that reaches for the banner's subjects.
    -->
    <p class="lede">
      Someone has invited you to work with them in Daymark. Setting this up takes one passphrase you
      choose, one code from your authenticator app, and one thing you read out loud to them at the end.
      Your keys are generated here, in this browser, and the released software sends them nowhere —
      which is a statement about the software rather than about the page in front of you, and the
      notice above is what that difference is.
    </p>
  </header>

  {#if resumed}
    <!--
      A ceremony somebody else's crashed tab left half-done, now finished. The fingerprints are the
      point of this card exactly as they are on the acceptance ending: the registration is the part
      the server can see, and the reading-aloud is the part it cannot.
    -->
    <Card title={KEY_CHECK_COPY.title}>
      <div class="stack">
        {#if resumed.acceptance.registration === 'registered'}
          <p>
            Your two public keys are on file for that relationship now. The person who invited you
            can read them back from the server, and the last part of setting up is the part that
            does not happen on a screen.
          </p>
        {:else}
          <!--
            409. On a rescue this is the ORDINARY answer — very often it means the keys this browser
            registered a moment before the tab closed were already there. It can also mean another
            device's keys, or somebody else's, and nothing on this side can tell those three apart.
            The fingerprints below are the only thing that can, which is why this card shows them
            rather than stopping the way a first acceptance does.
          -->
          <p>
            The server already held a pair of public keys for that relationship, so nothing was
            replaced. Most often those are the keys this browser registered just before it closed.
            They might also be another device's, or somebody else's — this page cannot tell, and
            reading the fingerprints below out is what tells you.
          </p>
        {/if}
        <p>{KEY_CHECK_COPY.lede}</p>
        {@render readAloud(resumed.boxFingerprint, resumed.signFingerprint)}
        <Callout title="Why out loud, and not on screen">
          <p>{KEY_CHECK_COPY.why}</p>
          <p>{KEY_CHECK_COPY.both}</p>
          <p>{KEY_CHECK_COPY.mismatch}</p>
        </Callout>
      </div>
    </Card>
  {:else if !invite}
    <!-- A link that could not be read. Not the person's fault, and the copy says so. -->
    <Card title="This link could not be read">
      <div class="stack">
        <Callout tone="critical">{INVITE_FAULT_TEXT[fault ?? 'noFragment']}</Callout>
        <div class="field">
          <label for="f-pasted">Paste the whole invitation link</label>
          <textarea id="f-pasted" bind:value={pasted} rows="3" autocomplete="off"></textarea>
          <p class="hint">
            Everything from <code>https://</code> to the end, including the part after the
            <code>#</code>. That part is what carries the invitation, and it is deliberately never
            sent to the server.
          </p>
        </div>
        <button class="primary" onclick={usePastedLink}>Use this link</button>
      </div>
    </Card>
  {:else if !enrolment}
    <!-- Step one of two things a person types. -->
    <Card title="Choose a reading passphrase">
      <div class="stack">
        <p>
          This passphrase unwraps your keys in this browser, every time you sign in. It is not your
          authenticator, it is not your password to anything else, and the server never receives it —
          which also means nobody can reset it for you. If you lose it, what has been shared with you
          cannot be opened again by anyone.
        </p>
        <p class="hint">
          At least {MIN_PASSPHRASE_CHARS} characters. A few unrelated words you will still recognise in
          six months is the easiest way to get there.
        </p>
        <div class="field">
          <label for="f-pass">Reading passphrase</label>
          <input id="f-pass" type="password" bind:value={passphrase} autocomplete="new-password" />
        </div>
        <div class="field">
          <label for="f-pass2">Type it again</label>
          <input id="f-pass2" type="password" bind:value={confirmation} autocomplete="new-password" />
        </div>
        {#if problems.length > 0}
          <Callout tone="critical">
            <ul class="problems">
              {#each problems as problem (problem)}<li>{problem}</li>{/each}
            </ul>
          </Callout>
        {/if}
        <button class="primary" onclick={accept} disabled={busy}>
          {busy ? 'Setting up…' : 'Accept invitation'}
        </button>
        <p class="hint">
          Setting up takes a few seconds: your keys are wrapped under this passphrase and then opened
          again, here, to prove the wrapping worked before anything depends on it.
        </p>
      </div>
    </Card>
  {:else if !outcome}
    <!-- Step two: the authenticator, and the code that proves it is really set up. -->
    <Card title="Set up your authenticator">
      <div class="stack">
        {#if !enrolment.serverConfirmedEnrolment}
          <!--
            The enrolment request went out and its answer never came back. That is not the same as a
            refusal and must not be dressed as one: the server may well have enrolled this
            credential, in which case the key below is its key and the invitation is spent either
            way. So the page says what it knows, keeps the key rather than throwing it away, and
            lets the code settle it — a code is only ever accepted for a credential that exists.
          -->
          <Callout tone="warn" title="The server did not answer, so this page does not know">
            <p>
              The request to set up your authenticator went out and no answer came back — a dropped
              connection, or something between here and the server. It may have worked. Nothing was
              undone on the guess that it did not, because the key below is the key of the
              credential it would have made, and throwing it away would leave nobody holding it.
            </p>
            <p>
              Add it to your authenticator and type a code. If the code is accepted, the set-up
              worked and this finishes normally. If no code is ever accepted, it did not, and you
              will need a fresh invitation — opened in a browser that has not been through this, or
              after clearing this site's data here.
            </p>
          </Callout>
        {/if}
        <p>
          Add this to the authenticator app you already use. Then type the six digits it shows, which
          is how this page knows your authenticator really works before it finishes.
        </p>
        <p class="secret">
          <span class="visually-hidden">Authenticator setup key: </span>
          {#each groupForReading(enrolment.totpSecretBase32) as chunk, i (i)}<span class="chunk">{chunk}</span>{/each}
        </p>
        <p class="hint">
          <a href={enrolment.otpauthUri}>Open in your authenticator app</a> — or add it by hand, choosing
          time-based, six digits, thirty seconds.
        </p>
        <div class="field">
          <label for="f-code">The six digits your authenticator shows</label>
          <input id="f-code" type="text" inputmode="numeric" bind:value={code} autocomplete="one-time-code" />
        </div>
        <button class="primary" onclick={finish} disabled={busy}>
          {busy ? 'Finishing…' : 'Confirm and finish'}
        </button>
        <p class="hint">
          A code is good for about thirty seconds. If it is refused, wait for the next one rather than
          retyping the same one — each code can only be used once.
        </p>
      </div>
    </Card>
  {:else if outcome.registration === 'already-registered'}
    <!--
      The server already had keys on file for this relationship, so the ones this browser just made
      are NOT the keys the owner will seal to. That is either this same clinician on another device
      or somebody else, and this page cannot tell which — so it says exactly that and stops. It
      deliberately does not show a fingerprint here: a fingerprint on this screen would be read
      aloud as though it were the key on file, and it is not.
    -->
    <Card title="Keys were already registered for this relationship">
      <div class="stack">
        <Callout tone="critical" title="Nothing was replaced, and nothing was overwritten">
          <p>
            The server already holds a pair of public keys for this relationship, and it refuses to
            replace them. The keys this browser just made are not the ones the person who invited you
            will send to.
          </p>
        </Callout>
        <p>
          There are two ways this happens. You may have been through this on another device already,
          in which case sign in there, or with the passphrase you chose then. Or somebody else's keys
          are on file for your relationship — which is worth finding out about, and worth asking about
          on the phone rather than through this server.
        </p>
      </div>
    </Card>
  {:else}
    <!-- Done. The only thing left is the part that happens away from the screen. -->
    <Card title={KEY_CHECK_COPY.title}>
      <div class="stack">
        <p>{KEY_CHECK_COPY.lede}</p>
        {@render readAloud(enrolment.boxFingerprint, enrolment.signFingerprint)}
        <Callout title="Why out loud, and not on screen">
          <p>{KEY_CHECK_COPY.why}</p>
          <p>{KEY_CHECK_COPY.both}</p>
          <p>{KEY_CHECK_COPY.mismatch}</p>
        </Callout>
      </div>
    </Card>

    <Card title="Signing in from now on">
      <div class="stack">
        <p>
          Your authenticator and your reading passphrase are what get you back in. The sign-in screen
          asks for a wrapped key, a relationship id and a credential id: they are the three values in
          the text below, which this browser is now holding for you. It also asks for two of the
          other person's public keys and your inbox token — only they can give you those, and nothing
          on this page can produce them.
        </p>
        <p>
          Clearing site data for this page erases what this browser is holding, and nothing anywhere
          else can rebuild it. If you want a copy that survives that, or you sign in from more than
          one machine, keep the text below wherever you keep passwords. It opens only with your
          reading passphrase, so whoever has both has everything that has been shared with you.
        </p>
        <div class="field">
          <label for="f-card">Your wrapped key, as text</label>
          <textarea id="f-card" readonly rows="8" value={portalCard}></textarea>
        </div>
      </div>
    </Card>
  {/if}

  {#if error}<Callout tone="critical">{error}</Callout>{/if}

  <!--
    THE WAY BACK IN. Offered beside the acceptance rather than hidden behind a failure, because the
    person who needs it arrives here in the worst possible position to go looking: their invitation
    link is spent and answers 410, a fresh one would die at the server's insert-only enrolment, and
    the only thing standing between them and a working relationship is one request their browser can
    still make. It is shown whenever this browser holds a record whose keys it has never seen
    registered, and it disappears the moment one is.
  -->
  {#if !resumed && !enrolment && !outcome && unfinished.length > 0}
    <Card title="Finish a set-up this browser already started">
      <div class="stack">
        <p>
          This browser holds keys for {unfinished.length === 1 ? 'a relationship' : `${unfinished.length} relationships`}
          whose public keys it never saw reach the server. That happens when the page closed, the
          network dropped or the tab went to sleep between signing in and the last step. The person
          who invited you sees nothing published, and will go on seeing nothing until this is sent —
          a new invitation cannot fix it, because your authenticator can only be set up once for a
          relationship.
        </p>
        <p class="hint">
          Nothing here accepts an invitation or makes new keys. It sends the public halves of the
          keys this browser already made, using the passphrase you chose then and a code from the
          authenticator you set up then.
        </p>
        {#if unfinished.length > 1}
          <div class="field">
            <label for="f-which">Which one</label>
            <select id="f-which" bind:value={resumeRelRef}>
              {#each unfinished as record (record.relRef)}
                <!-- Named by the same opaque prefix the authenticator entry uses, and by the day it
                     was started, because those are the two things a person can recognise. Nothing
                     here names a client. -->
                <option value={record.relRef}>
                  {record.relRef.slice(0, 8)} — started {new Date(record.createdAt).toLocaleDateString()}
                </option>
              {/each}
            </select>
          </div>
        {/if}
        <div class="field">
          <label for="f-resume-pass">The reading passphrase you chose</label>
          <input id="f-resume-pass" type="password" bind:value={resumePassphrase} autocomplete="current-password" />
        </div>
        <div class="field">
          <label for="f-resume-code">The six digits your authenticator shows</label>
          <input
            id="f-resume-code"
            type="text"
            inputmode="numeric"
            bind:value={resumeCode}
            autocomplete="one-time-code"
          />
        </div>
        <button class="primary" onclick={finishStarted} disabled={busy}>
          {busy ? 'Sending…' : 'Finish setting up'}
        </button>
        {#if resumeError}<Callout tone="critical">{resumeError}</Callout>{/if}
      </div>
    </Card>
  {/if}

  {#if storageFault && !resumed}
    <!--
      The store threw rather than answering. Reported rather than swallowed: "this browser will not
      tell me what it is holding" and "this browser is holding nothing" are different answers, and
      showing the second in place of the first would hide exactly the relationships this section
      exists to rescue.
    -->
    <Callout tone="warn" title="This browser would not say what keys it is holding">
      {storageFault}
    </Callout>
  {/if}
</section>

<style>
  /*
   * CENTRED, AND WITH ITS OWN PADDING, because this component IS the page.
   *
   * TherapistPortal renders its own chrome around itself; this one mounts straight into
   * #therapist-app, which has no shell of its own. A bare `max-width` therefore left the whole
   * acceptance flow flush against the left edge of the window with no gutter at all — the first
   * thing a clinician ever sees, sitting in the corner of an otherwise empty screen. Found by
   * rendering the page in a real browser, which is the only way this class of thing is found.
   */
  .accept {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    max-width: 44rem;
    margin-inline: auto;
    padding: var(--space-6) var(--space-4) var(--space-8);
  }
  .intro h1 {
    margin: 0 0 var(--space-2);
    font-family: var(--font-display);
    font-size: 1.5rem;
  }
  .lede {
    margin: 0;
    color: var(--ink-soft);
  }
  .stack {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }
  .stack p {
    margin: 0;
    color: var(--ink-soft);
    line-height: 1.55;
  }
  .hint {
    font-size: 0.85rem;
    color: var(--text-subtle);
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    font-size: 0.85rem;
  }
  .field label {
    color: var(--ink-soft);
  }
  input,
  textarea,
  select {
    font: inherit;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--paper-bg);
    color: var(--ink-text);
  }
  textarea {
    font-family: var(--font-mono);
    font-size: 0.8rem;
    resize: vertical;
  }
  code {
    font-family: var(--font-mono);
    font-size: 0.85em;
  }
  .problems {
    margin: 0;
    padding-left: var(--space-4);
  }

  /*
   * The two values a person has to get off this screen and into the physical world: the
   * authenticator key they copy into another app, and the fingerprint they read aloud. Both are
   * shown WHOLE and grouped for reading — the grouping is lossless, so what is spoken is the entire
   * value rather than a summary of it. Monospace and wide tracking because these are read one
   * character at a time, often over a phone, by somebody who has never seen base32 before.
   *
   * Structural indigo rather than any of the severity hues: this is the interface marking out where
   * the important value sits, not a warning and not a verdict about anything.
   */
  .secret,
  .fingerprint {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin: 0;
    padding: var(--space-3);
    border: 1px solid var(--indigo);
    border-radius: var(--radius-sm);
    background: var(--indigo-wash);
    font-family: var(--font-mono);
    font-size: 1.05rem;
    letter-spacing: 0.08em;
    color: var(--ink-text);
    overflow-wrap: anywhere;
    user-select: all;
  }
  .chunk {
    white-space: nowrap;
  }

  /*
   * The two fingerprints as a labelled pair rather than one value with a caption. They are read out
   * one after the other to somebody typing them into two separate fields, so which is which has to
   * survive being said down a phone — hence a real term list, with the same two names the owner's
   * console prints above its own two fields, and a plain-language note under each saying what that
   * key is for. Stacked rather than in columns: the values are long, monospaced and wrap, and a
   * two-column grid puts the second one where a phone will clip it.
   */
  .fps {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    margin: 0;
  }
  .fps dt {
    font-size: 0.85rem;
    color: var(--ink-soft);
    margin-bottom: var(--space-1);
  }
  .fps dd {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    margin: 0 0 var(--space-2);
  }
  .what {
    font-size: 0.75rem;
    color: var(--text-subtle);
  }

  .primary {
    align-self: flex-start;
    background: var(--ink-accent);
    color: var(--on-accent);
    border-color: var(--ink-accent);
  }
</style>
