<script lang="ts">
  /*
   * The owner's pairing screen: one invitation, one code, one decision.
   *
   * ALL THE ORDERING LIVES IN lib/pairing/ownerCeremony.ts, and this component is the rendering of
   * its phases plus the buttons that drive them. That split is not tidiness: the property worth
   * proving here is that keys are pinned before a ticket is forwarded and that nothing retries by
   * itself, and those are node tests over the module rather than claims about markup.
   *
   * WHAT THIS FILE IS RESPONSIBLE FOR. That the code appears where a person can read it aloud and
   * nowhere a machine will carry it: it is rendered into an aria-live output and is never the value
   * of an input, never inside a link, and never reachable by the email path (the mail form sends the
   * link, and there is no field it could put a code in). copy.ts holds the sentences.
   */
  import { Card, Callout } from '../ui'
  import { PortalClient, relRefOf, type InviteResponse } from '../../sync/portal'
  import type { PinnedTherapist } from './session'
  import { OWNER_COPY } from '../../pairing/copy'
  import {
    abandonApproval,
    approve,
    checkForReply,
    defaultFreshCode,
    keepInvitation,
    newCode,
    openRun,
    restore,
    startInvitation,
    stopInvitation,
    type InviteSummary,
    type OwnerCeremony,
    type OwnerCeremonyPorts,
  } from '../../pairing/ownerCeremony'
  import {
    ownerApprovePairing,
    ownerCancelPairing,
    ownerCollectPairing,
    ownerOpenPairing,
  } from '../../pairing/relay'
  import { defaultOwnerRunStorage } from '../../pairing/ownerRunStore'
  import { checkOffer, identityFromOffer, pinFromPairing, groupFingerprint } from '../../owner/therapistKeys'
  import { loadPins, savePins } from '../../therapist/pinStore'
  import { fingerprint } from '../../assignments/crypto'
  import type { TherapistOffer } from '../../pairing/payloads'

  let {
    therapist,
    others = [],
    client,
    baseUrl,
    token,
    smtpEnabled,
    scope,
    onpaired,
    ondone,
  }: {
    therapist: PinnedTherapist
    /**
     * The console's OTHER relationships, so an offer carrying keys already recorded for one of them
     * can be refused. That is the one ambiguity a pairing code cannot settle: two people filed
     * under one key means a share meant for either can be opened by the other.
     */
    others?: PinnedTherapist[]
    client: PortalClient | null
    baseUrl: string
    token: string
    smtpEnabled: boolean
    scope: string[]
    /** The offer's keys, once approved, so the console can fill in the pending entry. */
    onpaired?: (keys: { boxPub: Uint8Array; signPub: Uint8Array }) => void
    /** The owner is finished with this screen. The console stops holding it open. */
    ondone?: () => void
  } = $props()

  let ceremony = $state<OwnerCeremony>({ phase: 'idle' })
  let busy = $state(false)
  let error = $state('')
  let email = $state('')
  let copied = $state(false)
  let started = $state(false)

  async function relRef(): Promise<string> {
    return relRefOf(therapist.inboxToken)
  }

  /**
   * What this console holds for THIS relationship right now, or null while it holds nothing.
   *
   * A pending entry carries empty key arrays rather than absent ones, so "has this person got keys
   * on file" is `keysPending`, never a truthiness check on the bytes.
   */
  const held = $derived(
    therapist.keysPending ? null : { boxPub: therapist.boxPub, signPub: therapist.signPub },
  )

  /** The same question for everyone else, with the owner's own name for each of them attached. */
  const otherHeld = $derived(
    others
      .filter((t) => !t.keysPending && t.id !== therapist.id)
      .map((t) => ({ displayName: t.displayName, boxPub: t.boxPub, signPub: t.signPub })),
  )

  const ports: OwnerCeremonyPorts = {
    async mintInvite(): Promise<InviteResponse> {
      if (!client) throw new Error('No server configured.')
      return client.mintInvite(await relRef(), scope, undefined, undefined)
    },
    async listInvites(): Promise<InviteSummary[]> {
      const res = await fetch(`${baseUrl.replace(/\/+$/, '')}/v1/relations/${encodeURIComponent(await relRef())}/invites`, {
        headers: { authorization: `Bearer ${token}` },
      })
      if (!res.ok) return []
      return (await res.json()) as InviteSummary[]
    },
    openRun: async (inviteId, code) =>
      ownerOpenPairing({ relRef: await relRef(), inviteId, code: code.canonical, bearerToken: token, baseUrl }),
    readRun: async (run) => ownerCollectPairing({ relRef: await relRef(), bearerToken: token, pairing: run, baseUrl }),
    approveRun: async (exchangeId, enrolTicketB64) =>
      ownerApprovePairing({ relRef: await relRef(), bearerToken: token, exchangeId, enrolTicketB64, baseUrl }),
    cancelRun: async (exchangeId) => ownerCancelPairing({ relRef: await relRef(), bearerToken: token, exchangeId, baseUrl }),
    reportInvite: async (inviteId) => {
      if (!client) throw new Error('No server configured.')
      await client.reportInvite(inviteId)
    },
    inspectOffer: (offer: TherapistOffer) => checkOffer(offer, held, otherHeld),
    pinOffer(offer: TherapistOffer) {
      const peer = identityFromOffer(offer)
      if (!peer) return 'unreadable'
      const pins = loadPins()
      const outcome = pinFromPairing(pins, peer)
      // Nothing to write when the record already says exactly this; every other outcome appended.
      if (outcome !== 'already-pinned') savePins(pins)
      return outcome
    },
    freshCode: defaultFreshCode,
    get displayName() {
      return therapist.displayName
    },
    storage: defaultOwnerRunStorage(),
    now: () => Date.now(),
  }

  /** Every button goes through here, so a refusal always lands in one place and never as a throw. */
  async function step(fn: () => Promise<OwnerCeremony>) {
    error = ''
    busy = true
    try {
      ceremony = await fn()
    } catch (e) {
      error = e instanceof Error ? e.message : 'That did not work.'
    } finally {
      busy = false
    }
  }

  async function begin() {
    started = true
    await step(async () => {
      const found = await restore(ports)
      return found.phase === 'idle' ? found : found
    })
  }

  async function approveNow() {
    const before = ceremony
    await step(() => approve(ports, before))
    if (ceremony.phase === 'approved') {
      const peer = identityFromOffer(ceremony.offer)
      if (peer) onpaired?.({ boxPub: peer.x25519Pub, signPub: peer.ed25519Pub })
    }
  }

  async function copyLink() {
    if (ceremony.phase === 'idle' || !ceremony.invite.link) return
    try {
      await navigator.clipboard.writeText(ceremony.invite.link)
      copied = true
      setTimeout(() => (copied = false), 2000)
    } catch {
      copied = false
    }
  }

  async function sendEmail() {
    if (!client || ceremony.phase === 'idle' || !email) return
    // The link, and only the link. There is deliberately no field here a code could go in.
    await step(async () => {
      await client.mintInvite(await relRef(), scope, undefined, email)
      return restore(ports)
    })
  }

  const offerFingerprints = $derived.by(() => {
    if (ceremony.phase !== 'answered' && ceremony.phase !== 'approved') return null
    const peer = identityFromOffer(ceremony.offer)
    if (!peer) return null
    return {
      box: groupFingerprint(fingerprint(peer.x25519Pub)).join(' '),
      sign: groupFingerprint(fingerprint(peer.ed25519Pub)).join(' '),
    }
  })
</script>

<Card>
  <div class="pairing">
    <h4>{OWNER_COPY.title} · {therapist.displayName}</h4>
    <p class="caveat">{OWNER_COPY.browserCaveat}</p>

    {#if !started}
      <button class="primary" onclick={begin} disabled={busy || !client}>
        {busy ? 'Checking…' : 'Set up an invitation'}
      </button>
    {:else if ceremony.phase === 'idle'}
      <button class="primary" onclick={() => step(() => startInvitation(ports))} disabled={busy}>
        {busy ? 'Creating…' : 'Create an invitation'}
      </button>
    {:else}
      {#if ceremony.invite.link}
        <label class="linkbox">
          <span>The link. Email it, message it, hand it over.</span>
          <input type="text" readonly value={ceremony.invite.link} aria-label="Invitation link" />
        </label>
        <div class="row">
          <button onclick={copyLink}>{copied ? 'Copied' : 'Copy link'}</button>
        </div>
        {#if smtpEnabled}
          <div class="email">
            <label>
              <span>Send the link by email <em>(the code is never emailed)</em></span>
              <input type="email" bind:value={email} placeholder="therapist@example.com" autocomplete="off" />
            </label>
            <button onclick={sendEmail} disabled={busy || !email}>Send</button>
          </div>
        {/if}
      {/if}

      <p class="two-channels">{OWNER_COPY.twoChannels}</p>

      <!--
        Said at MINT, while there is still nothing to undo, rather than only at the moment of the
        click: someone sending this link should know before they send it that whoever answers with
        the code takes the place of the keys already on file. Dropped once the decision has been
        made either way — "until then, nothing changes" is false after an approval.
      -->
      {#if held && ceremony.phase !== 'answered' && ceremony.phase !== 'approved' && ceremony.phase !== 'ended'}
        <Callout tone="info">{OWNER_COPY.replaceAtMint(therapist.displayName)}</Callout>
      {/if}

      {#if ceremony.phase === 'invited'}
        <button class="primary" onclick={() => step(() => openRun(ports, ceremony))} disabled={busy}>
          {busy ? 'Making a code…' : 'Make a code'}
        </button>
      {:else if ceremony.phase === 'waiting'}
        <div class="code-wrap">
          <span class="code-label" id="pairing-code-label">{OWNER_COPY.codeLabel}</span>
          <!--
            An <output> with aria-live, not an input and not a link: the code is for a person to
            read, and putting it in a form control would make it something a browser saves, a
            password manager offers, and an autofill carries somewhere else.
          -->
          <output class="code" aria-labelledby="pairing-code-label" aria-live="polite">{ceremony.code.display}</output>
          <span class="code-hint">{OWNER_COPY.codeHint}</span>
        </div>
        <p class="state">{OWNER_COPY.waiting}</p>
        <div class="row">
          <button class="primary" onclick={() => step(() => checkForReply(ports, ceremony))} disabled={busy}>
            {busy ? 'Looking…' : 'Check for a reply'}
          </button>
          <button onclick={() => step(() => newCode(ports, ceremony))} disabled={busy}>{OWNER_COPY.newCodeLabel}</button>
        </div>
        <!-- What "New code" costs, beside the button rather than on the screen a person reaches
             after something has already gone wrong. -->
        <p class="hint">{OWNER_COPY.newCodeHint}</p>
      {:else if ceremony.phase === 'resumed'}
        <p class="state">{OWNER_COPY.waitingReload}</p>
        <div class="row">
          <button class="primary" onclick={() => step(() => checkForReply(ports, ceremony))} disabled={busy}>
            {busy ? 'Looking…' : 'Check for a reply'}
          </button>
          <button onclick={() => step(() => newCode(ports, ceremony))} disabled={busy}>{OWNER_COPY.newCodeLabel}</button>
        </div>
        <p class="hint">{OWNER_COPY.newCodeHint}</p>
      {:else if ceremony.phase === 'mismatch'}
        <!--
          Issue #112. A notice on this screen and nothing else: no notification, because the owner's
          half is a browser tab and a closed tab cannot raise one honestly. It sits above the count
          and the stop button, which are both in the shared block below and both stay as they were —
          a reply that did not open changes nothing about how many tries are left.

          "Keep it open" asks the server for nothing at all (ownerCeremony.keepInvitation), which is
          also why there is no timer here: a device that started checking more often after a failed
          open would have told the server the code was wrong.
        -->
        <Callout tone="warn" title={OWNER_COPY.mismatchTitle}>
          {OWNER_COPY.mismatchBody(therapist.displayName)}
        </Callout>
        <div class="row">
          <button class="primary" onclick={() => step(async () => keepInvitation(ceremony))} disabled={busy}>
            {OWNER_COPY.keepOpenLabel}
          </button>
        </div>
      {:else if ceremony.phase === 'answered'}
        {#if ceremony.keys.kind === 'supersedes'}
          <!--
            The replacement. Every sentence of it is in copy.ts and none is optional: what it does,
            what it does NOT reach, that no reading-aloud is needed, and what to do if the person
            never asked for this. The refusal it replaced said "reach them another way" and stopped,
            which was a stronger demand than the one that recorded the keys in the first place.
          -->
          <Callout tone="warn" title={OWNER_COPY.replaceTitle(therapist.displayName)}>
            {#each OWNER_COPY.replaceBody(therapist.displayName) as line (line)}
              <p class="state">{line}</p>
            {/each}
          </Callout>
        {:else}
          <h5>{OWNER_COPY.answeredTitle}</h5>
          <p class="state">{OWNER_COPY.answeredBody}</p>
        {/if}
        <dl class="offer">
          <dt>{OWNER_COPY.nameLabel}</dt>
          <dd class="name">{ceremony.offer.displayName}</dd>
          {#if offerFingerprints}
            <dt>{OWNER_COPY.fingerprintsLabel}</dt>
            <dd>
              <span class="fp-label">Encryption key fingerprint</span>
              <span class="fp">{offerFingerprints.box}</span>
              <span class="fp-label">Signing key fingerprint</span>
              <span class="fp">{offerFingerprints.sign}</span>
            </dd>
          {/if}
        </dl>
        {#if ceremony.keys.kind !== 'supersedes'}
          <!-- The replacement copy says this same sentence in its own words; twice would read as
               two different instructions about the same fingerprints. -->
          <p class="hint">{OWNER_COPY.fingerprintsHint}</p>
        {/if}
        <div class="row">
          {#if ceremony.keys.kind === 'supersedes'}
            <button class="primary" onclick={approveNow} disabled={busy}>
              {busy ? 'Approving…' : OWNER_COPY.replaceApproveLabel}
            </button>
            <!-- A dismissal. It calls nothing: no cancel, no report, no new code — the invitation
                 and the run are exactly as they were, and the keys on file are untouched. -->
            <button onclick={() => ondone?.()} disabled={busy}>{OWNER_COPY.replaceDeclineLabel}</button>
          {:else}
            <button class="primary" onclick={approveNow} disabled={busy}>{busy ? 'Approving…' : 'Approve'}</button>
            <button onclick={() => step(() => newCode(ports, ceremony))} disabled={busy}>{OWNER_COPY.newCodeLabel}</button>
          {/if}
        </div>
      {:else if ceremony.phase === 'approved'}
        <h5>{OWNER_COPY.approvedTitle}</h5>
        {#if ceremony.replaced}<p class="state">{OWNER_COPY.replacedBody}</p>{/if}
        <p class="state">{OWNER_COPY.approvedBody}</p>
        <div class="row">
          <button class="primary" onclick={() => ondone?.()}>Done</button>
          <button onclick={() => step(() => abandonApproval(ports, ceremony))} disabled={busy}>
            Take the approval back
          </button>
        </div>
      {:else if ceremony.phase === 'ended'}
        <p class="state">
          {#if ceremony.reason === 'reported'}{OWNER_COPY.stoppedBody}
          {:else if ceremony.reason === 'cancelled'}{OWNER_COPY.endedCancelled}
          {:else if ceremony.reason === 'capped'}{OWNER_COPY.cappedBody}
          {:else}{OWNER_COPY.endedSuperseded}{/if}
        </p>
        <div class="row">
          <button class="primary" onclick={() => step(() => startInvitation(ports))} disabled={busy}>
            {OWNER_COPY.freshLabel}
          </button>
        </div>
      {/if}

      {#if ceremony.phase !== 'ended' && ceremony.phase !== 'approved'}
        {#if ceremony.phase !== 'answered'}
          <p class="attempts">{OWNER_COPY.attemptsLeft(ceremony.attemptsLeft)}</p>
        {/if}
        {#if ceremony.failCount > 0}
          <p class="tried">
            {OWNER_COPY.wrongLinkTried(ceremony.failCount)}
            {OWNER_COPY.wrongLinkAdvice}
          </p>
        {/if}
        <div class="stop">
          <p>{OWNER_COPY.stopBody}</p>
          <button onclick={() => step(() => stopInvitation(ports, ceremony))} disabled={busy}>
            {OWNER_COPY.stopLabel}
          </button>
        </div>
      {/if}
    {/if}

    {#if error}<Callout tone="critical">{error}</Callout>{/if}
  </div>
</Card>

<style>
  .pairing { display: flex; flex-direction: column; gap: var(--space-3); }
  h4, h5 { margin: 0; }
  .caveat, .two-channels, .state, .hint, .attempts, .tried { margin: 0; font-size: 0.85rem; color: var(--ink-soft); }
  .state { color: var(--ink-text); }
  .two-channels { border-left: 2px solid var(--hairline); padding-left: var(--space-3); }
  .code-wrap { display: flex; flex-direction: column; gap: var(--space-1); }
  .code-label { font-size: 0.85rem; color: var(--ink-soft); }
  .code {
    font-family: var(--font-mono);
    font-size: 2rem;
    letter-spacing: 0.12em;
    color: var(--ink-text);
    background: var(--paper-bg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
    align-self: flex-start;
    user-select: all;
  }
  .code-hint { font-size: 0.8rem; color: var(--text-subtle); }
  .linkbox { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .linkbox span { color: var(--ink-soft); }
  input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  input[readonly] { font-family: var(--font-mono); font-size: 0.8rem; }
  .row { display: flex; gap: var(--space-2); flex-wrap: wrap; }
  .email { display: flex; flex-direction: column; gap: var(--space-2); border-top: 1px solid var(--hairline); padding-top: var(--space-3); }
  .email label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .email em { color: var(--text-subtle); font-style: normal; }
  .offer { margin: 0; display: flex; flex-direction: column; gap: var(--space-2); }
  .offer dt { font-size: 0.8rem; color: var(--ink-soft); }
  .offer dd { margin: 0; display: flex; flex-direction: column; gap: var(--space-1); }
  .name { font-size: 1.1rem; color: var(--ink-text); }
  .fp-label { font-size: 0.75rem; color: var(--text-subtle); }
  .fp { font-family: var(--font-mono); font-size: 0.85rem; color: var(--ink-text); }
  .stop { display: flex; flex-direction: column; gap: var(--space-2); border-top: 1px solid var(--hairline); padding-top: var(--space-3); }
  .stop button { align-self: flex-start; }
</style>
