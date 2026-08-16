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
  import { Callout } from '../ui'

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
      <label><span>Server URL</span><input type="url" bind:value={serverUrl} placeholder="https://daymark.example.com" autocomplete="off" /></label>
      <label><span>Inbox token</span><input type="password" bind:value={inboxToken} autocomplete="off" /></label>
      <label><span>Relationship id (relRef)</span><input type="text" bind:value={relRef} autocomplete="off" /></label>
      <label><span>Credential id</span><input type="text" bind:value={credentialId} autocomplete="off" /></label>
      <label><span>Pinned owner signing key (base64url)</span><input type="text" bind:value={pinnedOwnerSignPubB64} autocomplete="off" /></label>
      <label><span>Owner box key (base64url)</span><input type="text" bind:value={ownerBoxPubB64} autocomplete="off" /></label>
      <label class="wide"><span>Wrapped reading-key blob (JSON)</span><textarea bind:value={wrappedKeyJson} rows="3" autocomplete="off"></textarea></label>
    </div>
  </details>

  <div class="secrets">
    <label><span>Authenticator code (TOTP)</span><input type="text" inputmode="numeric" bind:value={totpCode} autocomplete="one-time-code" /></label>
    <label><span>Reading passphrase</span><input type="password" bind:value={readingPassphrase} autocomplete="off" /></label>
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
  label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  label span { color: var(--ink-soft); }
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
