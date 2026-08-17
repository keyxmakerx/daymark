<script lang="ts">
  /*
   * LoginGate — TOTP verify + reading-passphrase key unwrap. On success it produces a live session
   * (from the server) AND the in-memory TherapistKeys (unwrapped locally). Honest copy: this is the
   * weaker TOTP custody path.
   *
   * Inputs the therapist supplies come from the OOB pairing / invite (server + inbox token + pinned
   * owner key + wrapped-key blob). Those provisioning steps are the pairing/auth slice; here we
   * consume them. All secrets stay in memory.
   */
  import { PortalClient, type SessionInfo } from '../../therapist/session'
  import { unwrap, type WrappedKeyBlob } from '../../therapist/keyStore'
  import type { UnlockedContext } from '../../therapist/context'
  import { initAssignmentCrypto, fingerprint } from '../../assignments/crypto'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import { Callout, FieldHelp } from '../ui'
  import { FIELD_HELP } from '../../onboarding/fieldHelp'

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

  // Connection + relationship provisioning (from OOB pairing / invite).
  let serverUrl = $state('')
  let inboxToken = $state('')
  let relRef = $state('')
  let credentialId = $state('')
  let pinnedOwnerSignPubB64 = $state('')
  let ownerBoxPubB64 = $state('')
  let wrappedKeyJson = $state('')

  // Secrets — entered per session, never stored.
  let totpCode = $state('')
  let readingPassphrase = $state('')

  let busy = $state(false)
  let error = $state('')

  async function unlockNow() {
    error = ''
    busy = true
    try {
      const so = await initAssignmentCrypto()

      // 1. Unwrap the reading keys locally (never leaves the browser).
      let blob: WrappedKeyBlob
      try {
        blob = JSON.parse(wrappedKeyJson) as WrappedKeyBlob
      } catch {
        throw new Error('Wrapped-key blob is not valid JSON.')
      }
      const keys = await unwrap(blob, readingPassphrase)

      // 2. Verify the TOTP code with the server (sets the HttpOnly session cookie).
      const client = new PortalClient(serverUrl)
      const login = await client.loginTotp(credentialId, totpCode)
      if (!login.ok || !login.session) throw new Error(login.error ?? 'Login failed.')

      // 3. Bind the relationship routing (inbox token + relRef) into the session.
      const session: SessionInfo = { ...login.session, relRef, inboxToken }

      // 4. Pin the owner keys (OOB-provisioned): Ed25519 (verify) + X25519 (seal to owner).
      const b = so.base64_variants.URLSAFE_NO_PADDING
      const pinnedOwnerSignPub = so.from_base64(pinnedOwnerSignPubB64, b)
      const ownerBoxPub = so.from_base64(ownerBoxPubB64, b)
      const pinnedOwnerSigningFp = fingerprint(pinnedOwnerSignPub)
      const therapistFp = fingerprint(keys.sign.publicKey)

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

  <details class="prov" open>
    <summary>Relationship &amp; connection <em>(from your pairing invite)</em></summary>
    <div class="fields">
      <div class="field">
        <label for="f-serverUrl"><span>{FIELD_HELP.serverUrl.label}</span></label><FieldHelp field="serverUrl" />
        <input id="f-serverUrl" type="url" bind:value={serverUrl} placeholder={FIELD_HELP.serverUrl.placeholder} autocomplete="off" />
      </div>
      <div class="field">
        <label for="f-inboxToken"><span>{FIELD_HELP.inboxToken.label}</span></label><FieldHelp field="inboxToken" />
        <input id="f-inboxToken" type="password" bind:value={inboxToken} placeholder={FIELD_HELP.inboxToken.placeholder} autocomplete="off" />
      </div>
      <div class="field">
        <label for="f-relRef"><span>{FIELD_HELP.relRef.label}</span></label><FieldHelp field="relRef" />
        <input id="f-relRef" type="text" bind:value={relRef} placeholder={FIELD_HELP.relRef.placeholder} autocomplete="off" />
      </div>
      <div class="field">
        <label for="f-credentialId"><span>{FIELD_HELP.credentialId.label}</span></label><FieldHelp field="credentialId" />
        <input id="f-credentialId" type="text" bind:value={credentialId} placeholder={FIELD_HELP.credentialId.placeholder} autocomplete="off" />
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
        <textarea id="f-wrappedKey" bind:value={wrappedKeyJson} rows="3" placeholder={FIELD_HELP.wrappedKey.placeholder} autocomplete="off"></textarea>
      </div>
    </div>
  </details>

  <div class="secrets">
    <div class="field">
        <label for="f-totpCode"><span>{FIELD_HELP.totpCode.label}</span></label><FieldHelp field="totpCode" />
        <input id="f-totpCode" type="text" inputmode="numeric" bind:value={totpCode} placeholder={FIELD_HELP.totpCode.placeholder} autocomplete="one-time-code" />
      </div>
    <div class="field">
        <label for="f-readingPassphrase"><span>{FIELD_HELP.readingPassphrase.label}</span></label><FieldHelp field="readingPassphrase" />
        <input id="f-readingPassphrase" type="password" bind:value={readingPassphrase} placeholder={FIELD_HELP.readingPassphrase.placeholder} autocomplete="off" />
      </div>
  </div>

  <button class="primary" onclick={unlockNow} disabled={busy}>{busy ? 'Unlocking…' : 'Unlock portal'}</button>
  {#if error}<Callout tone="critical">{error}</Callout>{/if}
  <p class="faint note">
    Your reading passphrase is different from your authenticator. It unwraps your keys in this browser
    and is never sent to the server.
  </p>
</section>

<style>
  .gate { display: flex; flex-direction: column; gap: var(--space-4); max-width: 40rem; }
  .gate h2 { margin: 0; }
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
