<script lang="ts">
  /*
   * LoginGate — TOTP verify + reading-passphrase key unwrap. On success it produces a live session
   * (from the server) AND the in-memory TherapistKeys (unwrapped locally). Honest copy: this is the
   * weaker TOTP custody path.
   *
   * THIS FORM USED TO ASK FOR NINE VALUES, and that was not a usability failure — it was a missing
   * feature wearing a form. Seven of the nine were bytes the product had no way to move, so a human
   * carried them: the relationship id, the credential id and the wrapped key came from the
   * acceptance ceremony, and the owner's two public keys had no route at all until
   * /v1/relations/{relRef}/owner-keys existed. Each field was a person standing in for an API call.
   *
   * Now the browser that accepted the invitation kept a record (therapist/inviteAccept.ts), the
   * owner publishes their keys, and the server this page was served BY is the server to talk to. So
   * a returning clinician types the two things that are genuinely theirs and genuinely secret: the
   * code from their authenticator, and the passphrase that unwraps their keys here.
   *
   * The nine fields survive as a FALLBACK, folded away, for the case they were always really for —
   * signing in from a browser that never accepted the invitation, which has no record to read. That
   * path is honest about what it is rather than being the default everybody meets.
   *
   * WHY THE TWO SECRETS ARE DRAWN FIRST, EVEN WITH NO RECORD. The fallback used to open itself
   * whenever this browser had nothing stored, and the two fields a clinician was actually looking
   * for landed beneath nine they had never seen. Now the form always starts with the code and the
   * passphrase, and the fallback is a closed disclosure whose summary reads as an offer. A person
   * who needs it opens it; a person who does not is never asked to read it.
   *
   * WHY NOTHING IS SENT UNTIL THE FORM IS WHOLE. An empty submit used to report the last thing the
   * unlock path happened to parse — the wrapped-key blob, the most obscure field on the page. What
   * is missing is now judged first, top to bottom in reading order, by therapist/loginGate.ts, and
   * the first gap is named by its label. If that gap is behind the fold, the sentence says so and
   * the fold opens. The alert sits above the button, and the field it is about is marked and
   * focused.
   *
   * ON TRUSTING WHAT COMES BACK: the owner's keys arrive from a server that does not vouch for
   * them. A compromised one can hand back keys it controls, and every forged share would then
   * verify. What catches that is the clinician comparing the fingerprint against what the owner
   * reads aloud, so the fingerprint is SHOWN on unlock rather than quietly accepted.
   */
  import { tick } from 'svelte'
  import { PortalClient, type SessionInfo } from '../../therapist/session'
  import { unwrap, type WrappedKeyBlob } from '../../therapist/keyStore'
  import type { UnlockedContext } from '../../therapist/context'
  import { initAssignmentCrypto, fingerprint } from '../../assignments/crypto'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import { Callout, FieldHelp } from '../ui'
  import { FIELD_HELP } from '../../onboarding/fieldHelp'
  import {
    chooseOwnerKeys,
    groupForReading,
    loadKeyRecords,
    saveKeyRecord,
    OWNER_KEY_MISMATCH,
    type KeyRecord,
  } from '../../therapist/inviteAccept'
  import { firstProblem, WRAPPED_KEY_UNREADABLE, type UnlockFieldId } from '../../therapist/loginGate'

  let {
    onunlock,
    standalone = true,
  }: {
    onunlock: (ctx: UnlockedContext) => void
    /**
     * Whether this gate is mounted on its own rather than inside a screen that already frames it.
     *
     * One prop rather than two, because the banner and the heading exist for the same reason: a
     * gate mounted alone has to introduce itself and state the assurance level, and a gate mounted
     * inside SignInScreen must do neither — that screen already renders the fixed notice as part of
     * the contract, and its own headings. Two copies of a fixed honesty notice on one page is how
     * the notice stops being read, and a third "sign in" heading is noise to anyone navigating by
     * headings. Defaults true, so mounting this alone stays safe.
     */
    standalone?: boolean
  } = $props()

  /*
   * What this browser already knows, read once at mount.
   *
   * `untrack` is not needed here because this is a plain read in module-ish setup rather than a
   * derivation — but the reason it is read ONCE is the same: a returning clinician must not have
   * the form change under them because storage was rewritten by another tab mid-sign-in.
   */
  const records: KeyRecord[] = loadKeyRecords()
  let chosen = $state<KeyRecord | null>(records[0] ?? null)

  /**
   * Whether the nine-field path is the one in use. Defaults to whether there is anything to use
   * instead, so a browser that HAS a record never meets it. A browser that does not still starts
   * on the two fields that are always its own; the fallback is offered beneath them, closed.
   */
  let manual = $state(records.length === 0)

  /** Whether the fallback disclosure is open. Closed until a person opens it or a check needs it. */
  let provOpen = $state(false)

  /*
   * The server is the one that served this page. It was a typed field, which is a question with
   * exactly one correct answer that the page already knows — and getting it wrong silently pointed
   * the whole session at somebody else's machine.
   */
  const sameOrigin = typeof window !== 'undefined' ? window.location.origin : ''

  // Connection + relationship provisioning — the fallback path only.
  let serverUrl = $state('')
  let inboxToken = $state('')
  let relRef = $state('')
  let credentialId = $state('')
  let pinnedOwnerSignPubB64 = $state('')
  let ownerBoxPubB64 = $state('')
  let wrappedKeyJson = $state('')

  /** Shown after a successful unlock so the clinician can read it back to the owner. */
  let ownerFpGroups = $state<string[] | null>(null)

  // Secrets — entered per session, never stored.
  let totpCode = $state('')
  let readingPassphrase = $state('')

  let busy = $state(false)
  let error = $state('')

  /** The field the current alert is about, so it can be marked and described by the alert. */
  let invalidField = $state<UnlockFieldId | null>(null)
  const errorId = $props.id()

  /** The stored path is missing its inbox token, so that one field is asked for. */
  const askInboxToken = $derived(!manual && !!chosen && !chosen.inboxToken)

  function describedBy(field: UnlockFieldId): string | undefined {
    return invalidField === field ? errorId : undefined
  }

  function useManual(on: boolean) {
    manual = on
    // A person who asked for the fallback should not then have to find and open it.
    if (on) provOpen = true
  }

  function onSubmit(event: SubmitEvent) {
    event.preventDefault()
    void unlockNow()
  }

  async function unlockNow() {
    error = ''
    invalidField = null
    ownerFpGroups = null

    /*
     * The form is judged before anything is unwrapped or sent. The first gap in reading order is
     * the one reported; nothing further down is looked at until it is filled.
     */
    const problem = firstProblem(
      { totpCode, readingPassphrase, serverUrl, inboxToken, relRef, credentialId, wrappedKeyJson },
      { manual, askInboxToken, fallbackOpen: provOpen },
    )
    if (problem) {
      error = problem.message
      invalidField = problem.field
      if (problem.opensFallback) provOpen = true
      // The field is rendered (and, if it was folded away, revealed) before focus moves to it.
      await tick()
      if (typeof document !== 'undefined') document.getElementById(`f-${problem.field}`)?.focus()
      return
    }

    busy = true
    try {
      const so = await initAssignmentCrypto()
      const b = so.base64_variants.URLSAFE_NO_PADDING

      /*
       * Where each value comes from. The stored path reads what the acceptance ceremony kept in
       * this browser; the manual path reads what a person typed. Nothing else differs below, which
       * is deliberate — one unlock path means the fallback cannot quietly diverge into a second,
       * less careful sign-in.
       */
      const rec = manual ? null : chosen
      const origin = rec ? sameOrigin : serverUrl
      const relationship = rec ? rec.relRef : relRef
      const credential = rec ? rec.credentialId : credentialId

      // 1. Unwrap the reading keys locally (never leaves the browser).
      let blob: WrappedKeyBlob
      if (rec) {
        blob = rec.wrapped
      } else {
        try {
          blob = JSON.parse(wrappedKeyJson) as WrappedKeyBlob
        } catch {
          throw new Error(WRAPPED_KEY_UNREADABLE)
        }
      }
      const keys = await unwrap(blob, readingPassphrase)

      // 2. Verify the TOTP code with the server (sets the HttpOnly session cookie).
      const client = new PortalClient(origin)
      const login = await client.loginTotp(credential, totpCode)
      if (!login.ok || !login.session) throw new Error(login.error ?? 'Login failed.')

      // 3. Bind the relationship routing into the session. The inbox token is a second factor on
      //    the relationship channels, so it is remembered per relationship once given rather than
      //    re-typed every visit — it is not a secret this page can derive.
      const token = rec ? (rec.inboxToken ?? inboxToken) : inboxToken
      const session: SessionInfo = { ...login.session, relRef: relationship, inboxToken: token }

      /*
       * 4. The owner's keys.
       *
       * THE SERVER'S COPY IS A CROSS-CHECK, NEVER AN OVERRIDE. This used to assign the published
       * keys over whatever had been typed, with no comparison and no notice (issue #122) — so a
       * server that handed back a key it controlled would have quietly replaced the one this
       * clinician had verified out of band, and every forged share would then have verified.
       * session.ts's own header says the caller must pin on first use and refuse a change; this is
       * the caller doing it.
       *
       * Typed keys win when both are present, because those are the ones a human checked. The
       * server's copy is used only when nothing was typed, which is the honestly-weaker path a
       * manual sign-in takes against a server whose owner has published — and a dead end otherwise,
       * which is a real state rather than an error.
       */
      const published = await client.ownerKeys(session).catch(() => null)
      const choice = chooseOwnerKeys(
        { signPubB64: pinnedOwnerSignPubB64, boxPubB64: ownerBoxPubB64 },
        published,
      )
      if (!choice.ok) {
        throw new Error(
          choice.reason === 'mismatch'
            ? OWNER_KEY_MISMATCH
            : 'This server has no owner keys published for the relationship yet, and none were entered. ' +
              'Ask the person who invited you to publish them from their console.',
        )
      }
      const signB64 = choice.keys.signPubB64
      const boxB64 = choice.keys.boxPubB64
      const pinnedOwnerSignPub = so.from_base64(signB64, b)
      const ownerBoxPub = so.from_base64(boxB64, b)
      const pinnedOwnerSigningFp = fingerprint(pinnedOwnerSignPub)
      const therapistFp = fingerprint(keys.sign.publicKey)

      /*
       * Shown, not swallowed. The server relaying these keys does not vouch for them, so the one
       * thing that catches a substitution is a person reading this back to the owner. Rendering it
       * on unlock is the only moment both people are reliably present.
       */
      ownerFpGroups = groupForReading(pinnedOwnerSigningFp)

      // Remember what made this sign-in work, so the next one asks for less. Only ever additive:
      // the token is the one value a stored record can be missing.
      if (rec && token && !rec.inboxToken) {
        try {
          saveKeyRecord({ ...rec, inboxToken: token })
        } catch {
          // A browser refusing storage costs one re-typed token next time. It must never cost a
          // sign-in that has already succeeded.
        }
      }

      onunlock({ client, session, keys, pinnedOwnerSignPub, pinnedOwnerSigningFp, ownerBoxPub, therapistFp })
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not unlock.'
    } finally {
      busy = false
    }
  }
</script>

<section class="gate">
  {#if standalone}<LowerAssuranceBanner />{/if}
  {#if standalone}<h2>Therapist portal — sign in</h2>{/if}

  <!--
    A real form, so the Go key on a phone and Enter in the last field submit it. `novalidate`
    because the browser's own bubbles would speak before the reading-order check below does, and
    would name the url field first regardless of where it sits.
  -->
  <form class="form" novalidate onsubmit={onSubmit}>
    {#if !manual}
      {#if records.length > 1}
        <div class="field">
          <label for="f-relationship"><span>Which relationship</span></label>
          <select id="f-relationship" bind:value={chosen}>
            {#each records as r (r.relRef)}
              <option value={r}>{r.relRef}</option>
            {/each}
          </select>
        </div>
      {:else if chosen}
        <p class="faint note">Signing in to the relationship this browser accepted.</p>
      {/if}
    {/if}

    <!--
      THE TWO FIELDS THAT ARE ACTUALLY YOURS, and always first. Everything else this form used to
      ask for is either remembered from the acceptance ceremony, published by the owner, or the
      address of the server that served this page.
    -->
    <div class="secrets">
      <div class="field">
        <label for="f-totpCode"><span>{FIELD_HELP.totpCode.label}</span></label><FieldHelp field="totpCode" />
        <input id="f-totpCode" type="text" inputmode="numeric" bind:value={totpCode} placeholder={FIELD_HELP.totpCode.placeholder} autocomplete="one-time-code" aria-invalid={invalidField === 'totpCode' || undefined} aria-describedby={describedBy('totpCode')} />
      </div>
      <div class="field">
        <label for="f-readingPassphrase"><span>{FIELD_HELP.readingPassphrase.label}</span></label><FieldHelp field="readingPassphrase" />
        <input id="f-readingPassphrase" type="password" bind:value={readingPassphrase} placeholder={FIELD_HELP.readingPassphrase.placeholder} autocomplete="off" aria-invalid={invalidField === 'readingPassphrase' || undefined} aria-describedby={describedBy('readingPassphrase')} />
      </div>
    </div>

    {#if askInboxToken}
      <!--
        The one value nothing can supply. Its digest IS the relationship id, so a server that
        could hand it back would be giving away the thing it authenticates. Asked once, then
        remembered.
      -->
      <div class="known">
        <div class="field">
          <label for="f-inboxToken"><span>{FIELD_HELP.inboxToken.label}</span></label><FieldHelp field="inboxToken" />
          <input id="f-inboxToken" type="password" bind:value={inboxToken} placeholder={FIELD_HELP.inboxToken.placeholder} autocomplete="off" aria-invalid={invalidField === 'inboxToken' || undefined} aria-describedby={describedBy('inboxToken')} />
        </div>
      </div>
    {/if}

    {#if manual}
      <!--
        The fallback, closed by default and offered rather than imposed. Bound to state rather
        than written `open`, so the reading-order check can reveal it when the gap it found is
        inside. The field order here is the order therapist/loginGate.ts checks in; the test
        reads this markup and holds the two together.
      -->
      <details class="prov" bind:open={provOpen}>
        <summary>This browser has no record of your invitation? Enter the connection by hand <em>(from your pairing invite)</em></summary>
        <p class="faint note">
          This browser has no record of accepting an invitation, so these have to be entered by hand.
          Accepting the invitation in the browser you sign in from is the shorter path.
        </p>
        <div class="fields">
          <div class="field">
            <label for="f-serverUrl"><span>{FIELD_HELP.serverUrl.label}</span></label><FieldHelp field="serverUrl" />
            <input id="f-serverUrl" type="url" bind:value={serverUrl} placeholder={FIELD_HELP.serverUrl.placeholder} autocomplete="off" aria-invalid={invalidField === 'serverUrl' || undefined} aria-describedby={describedBy('serverUrl')} />
          </div>
          <div class="field">
            <label for="f-inboxToken"><span>{FIELD_HELP.inboxToken.label}</span></label><FieldHelp field="inboxToken" />
            <input id="f-inboxToken" type="password" bind:value={inboxToken} placeholder={FIELD_HELP.inboxToken.placeholder} autocomplete="off" aria-invalid={invalidField === 'inboxToken' || undefined} aria-describedby={describedBy('inboxToken')} />
          </div>
          <div class="field">
            <label for="f-relRef"><span>{FIELD_HELP.relRef.label}</span></label><FieldHelp field="relRef" />
            <input id="f-relRef" type="text" bind:value={relRef} placeholder={FIELD_HELP.relRef.placeholder} autocomplete="off" aria-invalid={invalidField === 'relRef' || undefined} aria-describedby={describedBy('relRef')} />
          </div>
          <div class="field">
            <label for="f-credentialId"><span>{FIELD_HELP.credentialId.label}</span></label><FieldHelp field="credentialId" />
            <input id="f-credentialId" type="text" bind:value={credentialId} placeholder={FIELD_HELP.credentialId.placeholder} autocomplete="off" aria-invalid={invalidField === 'credentialId' || undefined} aria-describedby={describedBy('credentialId')} />
          </div>
          <div class="field">
            <label for="f-pinnedOwnerSignPub"><span>{FIELD_HELP.pinnedOwnerSignPub.label}</span></label><FieldHelp field="pinnedOwnerSignPub" />
            <input id="f-pinnedOwnerSignPub" type="text" bind:value={pinnedOwnerSignPubB64} placeholder={FIELD_HELP.pinnedOwnerSignPub.placeholder} autocomplete="off" />
          </div>
          <div class="field">
            <label for="f-ownerBoxPub"><span>{FIELD_HELP.ownerBoxPub.label}</span></label><FieldHelp field="ownerBoxPub" />
            <input id="f-ownerBoxPub" type="text" bind:value={ownerBoxPubB64} placeholder={FIELD_HELP.ownerBoxPub.placeholder} autocomplete="off" />
          </div>
          <div class="field wide">
            <label for="f-wrappedKey"><span>{FIELD_HELP.wrappedKey.label}</span></label><FieldHelp field="wrappedKey" />
            <textarea id="f-wrappedKey" bind:value={wrappedKeyJson} rows="3" placeholder={FIELD_HELP.wrappedKey.placeholder} autocomplete="off" aria-invalid={invalidField === 'wrappedKey' || undefined} aria-describedby={describedBy('wrappedKey')}></textarea>
          </div>
        </div>
      </details>
    {/if}

    <!--
      Above the button, not beneath it: the sentence a person needs before they press again
      should not be the thing they have to scroll past the button to find. The wrapper carries
      the id the failing field points at, so the alert is also read as that field's description.
    -->
    {#if error}<div id={errorId}><Callout tone="critical">{error}</Callout></div>{/if}
    <button class="primary" type="submit" disabled={busy}>{busy ? 'Unlocking…' : 'Unlock portal'}</button>
  </form>

  {#if records.length > 0}
    <p class="faint note">
      <button class="linkish" type="button" onclick={() => useManual(!manual)}>
        {manual ? 'Use what this browser remembers' : 'Sign in with details from the invitation instead'}
      </button>
    </p>
  {/if}
  {#if ownerFpGroups}
    <!--
      The out-of-band check, at the only moment both people are reliably present. The server
      relaying these keys does not vouch for them, so this is what catches a substituted one.
    -->
    <Callout tone="info" title="Read this back to the person who invited you">
      <p class="fp">{ownerFpGroups.join(' ')}</p>
      <p class="faint">
        This is their signing key as this server handed it over. If it does not match what they read
        out, stop and tell them — do not compare it on a screen they are not holding.
      </p>
    </Callout>
  {/if}
  <p class="faint note">
    Your reading passphrase is different from your authenticator. It unwraps your keys in this browser
    and is never sent to the server.
  </p>
</section>

<style>
  .known { display: flex; flex-direction: column; gap: var(--space-3); }
  .fp { font-family: var(--font-mono); font-size: 1.05rem; letter-spacing: 0.04em; color: var(--ink-text); margin: 0 0 var(--space-2); }
  .linkish { background: none; border: 0; padding: 0; color: var(--link); font: inherit; cursor: pointer; text-decoration: underline; }
  .linkish:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }

  .gate { display: flex; flex-direction: column; gap: var(--space-4); max-width: 40rem; }
  .gate h2 { margin: 0; }
  .form { display: flex; flex-direction: column; gap: var(--space-4); }
  .prov { border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); }
  .prov summary { cursor: pointer; color: var(--ink-soft); font-size: 0.9rem; }
  .prov em { font-style: normal; color: var(--text-subtle); }
  .fields, .secrets { display: flex; flex-direction: column; gap: var(--space-2); margin-top: var(--space-3); }
  .secrets { margin-top: 0; }
  /*
   * The label and its help trigger sit on one line; the input goes underneath. The label is no
   * longer the input's ancestor — an explicit `for`/`id` pairing instead — because a <button>
   * nested inside a <label> is activated by clicks meant for the label, so the two would fight
   * over the same tap.
   */
  .field { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-1); font-size: 0.85rem; }
  .field > label { display: inline-flex; align-items: center; margin: 0; }
  .field > input, .field > textarea { flex-basis: 100%; }
  label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  label span { color: var(--ink-soft); }
  /* A placeholder is an EXAMPLE, not a label — it must never read as strongly as real input. */
  input::placeholder, textarea::placeholder { color: var(--ink-faint); }
  input, textarea { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  textarea { font-family: var(--font-mono); resize: vertical; }
  /* The field the alert is about. The outline is a second signal beside the sentence that names
     it and the aria-invalid a reader hears; it is never the only one. */
  input[aria-invalid='true'], textarea[aria-invalid='true'] { border-color: var(--clay); }
  .primary { align-self: flex-start; background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }

  /*
   * The unlock-failure slot was --mood-1 / --mood-1-wash — the worst step of a person's own
   * mood ramp, spent on "Wrapped-key blob is not valid JSON." A failed unlock is interface
   * alarm, not anyone's reported experience, so it moves to ui/Callout's critical tone
   * (--clay, closed outline, role="alert"). This is the first screen a therapist ever sees;
   * teaching them here that red means "the software refused" — and never "this person had a
   * bad week" — is what keeps the ramp readable everywhere after it.
   */
  .note { margin: 0; font-size: 0.8rem; }
</style>
