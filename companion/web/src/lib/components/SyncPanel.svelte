<script lang="ts">
  import { parseBackup, type BackupData } from '../backup'

  let { onload }: { onload: (data: BackupData, source: string) => void } = $props()

  // Default to the same origin (this portal is served by the companion). Users behind a
  // separate URL can override.
  let serverUrl = $state('')
  let token = $state('')
  let lineage = $state('laptop')
  let passphrase = $state('')
  let busy = $state(false)
  let error = $state('')

  async function fetchAndDecrypt() {
    error = ''
    if (!token) { error = 'Enter your server access token.'; return }
    if (!passphrase) { error = 'Enter your sync passphrase.'; return }
    busy = true
    try {
      // Lazy-load the crypto client so the offline viewer never pays for libsodium.
      const { SyncClient } = await import('../sync/client')
      const client = new SyncClient(serverUrl, token)
      const { version, plaintext } = await client.pullLatest(lineage, passphrase)
      const text = new TextDecoder().decode(plaintext)
      const data = parseBackup(text)
      onload(data, `sync · ${lineage} v${version}`)
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not fetch and decrypt.'
    } finally {
      busy = false
    }
  }
</script>

<div class="sync card">
  <h2>Connect to your sync server</h2>
  <p class="warn-banner">
    <strong>Lower-assurance path.</strong> Decrypting in the browser is convenient but the
    page is served by the server it talks to; a malicious server could tamper with it. Your
    phone (the future Sync flavor) is the trusted, secret-handling path. Use a passphrase
    you are comfortable entering here, and verify the released image digest.
  </p>

  <label>
    <span>Server URL <em>(blank = this server)</em></span>
    <input type="url" bind:value={serverUrl} placeholder="https://daymark.example.com" autocomplete="off" />
  </label>
  <label>
    <span>Access token</span>
    <input type="password" bind:value={token} autocomplete="off" />
  </label>
  <label>
    <span>Device / lineage</span>
    <input type="text" bind:value={lineage} placeholder="laptop" autocomplete="off" />
  </label>
  <label>
    <span>Sync passphrase <em>(never uploaded)</em></span>
    <input type="password" bind:value={passphrase} autocomplete="off" />
  </label>

  <button class="primary" onclick={fetchAndDecrypt} disabled={busy}>
    {busy ? 'Fetching & decrypting…' : 'Fetch & decrypt latest'}
  </button>

  {#if error}
    <p class="error" role="alert">{error}</p>
  {/if}
</div>

<style>
  .sync { display: flex; flex-direction: column; gap: var(--space-3); max-width: 34rem; }
  .warn-banner {
    margin: 0;
    background: var(--mood-3-wash);
    border: 1px solid var(--hairline);
    border-radius: var(--radius-sm);
    padding: var(--space-3);
    font-size: 0.9rem;
    color: var(--ink-soft);
  }
  label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.9rem; }
  label span { color: var(--ink-soft); }
  label em { color: var(--ink-faint); font-style: normal; }
  input {
    font: inherit;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--paper-bg);
    color: var(--ink-text);
  }
  button { align-self: flex-start; }
  .error { color: var(--mood-1); background: var(--mood-1-wash); border: 1px solid var(--mood-1); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); margin: 0; }
</style>
