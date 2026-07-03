<script lang="ts">
  /*
   * Track T2 (email Option A): the unauthenticated owner-access-token recovery flow. This
   * recovers SERVER ACCESS ONLY — the server is zero-knowledge and can never reset the PIN or
   * E2EE passphrase; a recovered token still cannot decrypt anything. Deliberately requires no
   * login: that is the point of a recovery path.
   */
  import { requestAccessRecovery, confirmAccessRecovery } from '../../sync/portal'

  let serverUrl = $state('')
  let email = $state('')
  let confirmToken = $state('')
  let newToken = $state('')
  let requestStatus = $state('')
  let confirmStatus = $state('')
  let busy = $state(false)
  let error = $state('')

  // Progressive enhancement: a confirm link lands on this page as `#t=<token>`; prefill it.
  if (typeof window !== 'undefined') {
    const match = /(?:^|[#&])t=([^&]+)/.exec(window.location.hash)
    if (match) confirmToken = decodeURIComponent(match[1])
  }

  async function request() {
    error = ''
    requestStatus = ''
    if (!email.trim()) {
      error = 'Enter the email you registered for notifications.'
      return
    }
    busy = true
    try {
      // requestAccessRecovery never inspects the server's response status (the server always
      // replies 202 regardless of match, by design) — the only way this throws is a genuine
      // network-level failure (unreachable server, bad URL), which is safe to surface distinctly
      // without leaking anything about whether the email matched.
      await requestAccessRecovery(serverUrl, email.trim())
      requestStatus = 'If that email is registered, a recovery link was sent. Check your inbox.'
    } catch {
      error = 'Could not reach that server. Check the server URL and try again.'
    } finally {
      busy = false
    }
  }

  async function confirm() {
    error = ''
    confirmStatus = ''
    newToken = ''
    if (!confirmToken.trim()) {
      error = 'Paste the confirmation token from your recovery email link.'
      return
    }
    busy = true
    try {
      const result = await confirmAccessRecovery(serverUrl, confirmToken.trim())
      newToken = result.newToken
      confirmStatus = 'Your access token was re-issued. Copy it now — it is shown only once.'
    } catch (e) {
      error = e instanceof Error ? e.message : 'That recovery link is invalid, expired, or already used.'
    } finally {
      busy = false
    }
  }

  async function copyToken() {
    if (!newToken) return
    try {
      await navigator.clipboard.writeText(newToken)
    } catch {
      /* clipboard access denied; the token is still selectable/visible in the field */
    }
  }
</script>

<section class="recover card">
  <h2>Recover server access</h2>
  <p class="hint">
    Lost your owner access token? If you registered a notification email, you can request a new
    one here. This recovers <strong>server access only</strong> — it cannot restore your PIN or
    your end-to-end-encryption passphrase, and it cannot decrypt anything on its own.
  </p>

  <label class="field">
    <span>Server URL <em>(blank = this server)</em></span>
    <input type="url" bind:value={serverUrl} placeholder="https://daymark.example.com" autocomplete="off" />
  </label>

  <fieldset class="step">
    <legend>1. Request a recovery link</legend>
    <label class="field">
      <span>Registered email</span>
      <input type="email" bind:value={email} placeholder="you@example.com" autocomplete="off" />
    </label>
    <button class="primary" onclick={request} disabled={busy}>{busy ? 'Requesting…' : 'Send recovery link'}</button>
    {#if requestStatus}<p class="status">{requestStatus}</p>{/if}
  </fieldset>

  <fieldset class="step">
    <legend>2. Confirm the link</legend>
    <label class="field">
      <span>Confirmation token <em>(from the link in your email)</em></span>
      <input type="text" bind:value={confirmToken} autocomplete="off" />
    </label>
    <button class="primary" onclick={confirm} disabled={busy}>{busy ? 'Confirming…' : 'Confirm and re-issue'}</button>
    {#if confirmStatus}<p class="status">{confirmStatus}</p>{/if}
    {#if newToken}
      <label class="field">
        <span>New owner access token <em>(shown once — save it now)</em></span>
        <input type="text" readonly value={newToken} aria-label="New owner access token" />
      </label>
      <button onclick={copyToken}>Copy token</button>
    {/if}
  </fieldset>

  {#if error}<p class="error" role="alert">{error}</p>{/if}
</section>

<style>
  .recover { display: flex; flex-direction: column; gap: var(--space-4); max-width: 34rem; }
  .hint { margin: 0; color: var(--ink-soft); font-size: 0.9rem; }
  .field { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .field em { color: var(--ink-faint); font-style: normal; }
  input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  input[readonly] { font-family: var(--font-mono); font-size: 0.8rem; }
  .step { display: flex; flex-direction: column; gap: var(--space-3); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .step legend { padding: 0 var(--space-2); color: var(--ink-soft); font-size: 0.85rem; }
  .status { margin: 0; font-size: 0.85rem; color: var(--ink-soft); }
  .error { color: var(--mood-1); margin: 0; font-size: 0.85rem; }
</style>
