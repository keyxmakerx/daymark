<script lang="ts">
  import type { Component } from 'svelte'
  import { parseBackup, type BackupData } from '../backup'
  import type { OwnerConnection } from '../owner/recoveryEmail'
  import PhonesSection from './phones/PhonesSection.svelte'

  let {
    onload,
    /**
     * Handed the server address and access token once the server has accepted them: a pull with
     * them succeeded, or the server answered the phone list with them (see [proveToken]). The
     * "Recover access" card registers the recovery email with them without asking for the token
     * again (#330), and the Phones section below pairs with them (#431). Never called with a token
     * the server has not accepted.
     */
    onconnected = undefined,
    /** The connection this visit has proved, from whichever card proved it; null until one has. */
    connection = null,
  }: {
    onload: (data: BackupData, source: string) => void
    onconnected?: (connection: OwnerConnection) => void
    connection?: OwnerConnection | null
  } = $props()

  // Default to the same origin (this portal is served by the companion). Users behind a
  // separate URL can override.
  let serverUrl = $state('')
  let token = $state('')
  let lineage = $state('laptop')
  let passphrase = $state('')
  let busy = $state(false)
  let error = $state('')
  /** A plain line about the connection, never a fault: what Connect, or a pull with no passphrase, found. */
  let note = $state('')

  /*
   * A TOKEN IS PROVED BY THE SERVER TAKING IT, NOT BY A SNAPSHOT OPENING (#431). A pull proves the
   * address and token only when there is a snapshot and the passphrase opens it, and the first phone
   * is paired before anything has been synced: that server holds no key and no snapshot, so the pull
   * stops at "holds no key" and, proved that way alone, the Phones section could never appear. So
   * where the pull did not open a snapshot for any reason but the server refusing the token, and
   * where no passphrase was given, the phone list is asked with the same address and token: it is an
   * owner route that needs the token and nothing else, and an answer in its shape proves them. A
   * refused token is not asked twice, since each refusal counts toward the address's lockout.
   */
  async function proveToken(tried: OwnerConnection): Promise<boolean> {
    try {
      const { devicesApi } = await import('../phones/devices')
      await devicesApi(tried.serverUrl, tried.token).listDevices()
      onconnected?.(tried)
      return true
    } catch {
      /* not proved: nothing is handed up, and the card's own sentence stands */
      return false
    }
  }

  /*
   * CONNECT PROVES THE ADDRESS AND TOKEN AND NOTHING ELSE (#434 item 1). The first phone pairs before
   * anything has been synced, so a server with nothing on it is the ordinary first visit, not a fault:
   * connecting needs no passphrase, and what it finds is said in plain ink rather than as an error.
   */
  async function connect() {
    error = ''
    note = ''
    if (!token) { error = 'Enter your server access token.'; return }
    busy = true
    try {
      note = (await proveToken({ serverUrl, token }))
        ? 'Connected. Your phones are below. To read a copy here, enter your sync passphrase and fetch it.'
        : 'Not connected: the server did not accept this address and access token, or did not answer. Nothing has changed.'
    } finally {
      busy = false
    }
  }

  async function fetchAndDecrypt() {
    error = ''
    note = ''
    if (!token) { error = 'Enter your server access token.'; return }
    busy = true
    // The fields as they were when the button was pressed: what an answer proves.
    const tried: OwnerConnection = { serverUrl, token }
    try {
      if (!passphrase) {
        // Still proves the connection, so a first visit never reads as a failure (#434).
        if (await proveToken(tried)) {
          note = 'Connected. Your phones are below. To read a copy here, enter your sync passphrase and fetch it.'
        } else {
          error = 'Enter your sync passphrase.'
        }
        return
      }
      // Lazy-load the crypto client so the offline viewer never pays for libsodium.
      const { SyncClient, SyncError } = await import('../sync/client')
      const client = new SyncClient(tried.serverUrl, tried.token)
      let pulled: { version: number; plaintext: Uint8Array }
      try {
        pulled = await client.pullLatest(lineage, passphrase)
      } catch (e) {
        error = e instanceof Error ? e.message : 'Could not fetch and decrypt.'
        const refused = e instanceof SyncError && (e.status === 401 || e.status === 403 || e.status === 429)
        if (!refused) await proveToken(tried)
        return
      }
      // The server accepted the token and returned this person's snapshot, so the token is proved.
      // Handed up before `onload`, which replaces this card with the dashboard.
      onconnected?.(tried)
      const text = new TextDecoder().decode(pulled.plaintext)
      const data = parseBackup(text)
      onload(data, `sync · ${lineage} v${pulled.version}`)
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not fetch and decrypt.'
    } finally {
      busy = false
    }
  }

  /*
   * THE RECOVERY-CODE SCREEN, REACHED FROM HERE AND LOADED ON DEMAND.
   *
   * WHY IT HANGS OFF THIS PANEL. This is the screen about the sync server, and the sync server is
   * the answer to "my phone is gone, how do I get the past years of my life back"
   * (COMPANION_ARCHITECTURE.md §1). A recovery code is the second half of that same
   * question — the half that applies when what was lost is the passphrase rather than the handset —
   * so it belongs beside the passphrase field rather than behind a separate route nobody visits
   * until it is too late to be useful.
   *
   * WHY IT IS DYNAMICALLY IMPORTED. Same reason the sync client above is: the recovery screen
   * reaches libsodium (the code generator draws from the CSPRNG, and the wrapping is Argon2id plus
   * XChaCha20-Poly1305), and a static import would put the whole panel plus a WASM crypto library
   * in the entry chunk for a screen most visitors never open.
   *
   * Worth being exact about what that does and does not buy, since the sentence above this one is
   * the kind that gets believed later: the entry chunk ALREADY reaches libsodium today, statically,
   * through OwnerConsole -> sync/portal.ts. So this keeps the recovery panel's own weight out of it
   * and does not on its own restore the "the offline viewer never pays for libsodium" property that
   * the lazy import below the passphrase field was written for. That property is somebody else's
   * file to repair; this one is careful not to make it worse.
   *
   * Held as a component value rather than behind an `{#await}` so that opening it is one decision
   * with one loading state, and so it stays mounted once it is there.
   *
   * IT IS HANDED THIS CARD'S ADDRESS AND TOKEN (#258). The key the recovery code opens is kept on the
   * server, locked, and both of the screen's flows read and write it there. The fields for reaching
   * that server are this card's, so the screen uses them rather than asking for them a second time.
   */
  let RecoveryPanel = $state<Component<{ serverUrl?: string; token?: string }> | null>(null)
  let loadingRecovery = $state(false)
  let recoveryError = $state('')

  async function openRecovery() {
    recoveryError = ''
    loadingRecovery = true
    try {
      RecoveryPanel = (await import('./recovery/RecoveryPanel.svelte')).default
    } catch {
      // The chunk is served by this same origin, so the realistic causes are a stale page against a
      // redeployed server or an offline tab. Neither is worth a technical sentence.
      recoveryError = 'That screen could not be loaded. Reload the page and try again.'
    } finally {
      loadingRecovery = false
    }
  }
</script>

<div class="sync card">
  <h2>Connect to your sync server</h2>
  <p class="warn-banner">
    <strong>Lower-assurance path.</strong> Decrypting in the browser is convenient but the
    page is served by the server it talks to; a malicious server could tamper with it. Your
    phone (the future Sync flavor) is the trusted, secret-handling path. Use a passphrase
    you are comfortable entering here.
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

  <div class="sync-actions">
    <button onclick={connect} disabled={busy}>Connect</button>
    <button class="primary" onclick={fetchAndDecrypt} disabled={busy}>
      {busy ? 'Fetching & decrypting…' : 'Fetch & decrypt latest'}
    </button>
  </div>

  {#if error}
    <p class="error" role="alert">{error}</p>
  {:else if note}
    <p class="note" role="status">{note}</p>
  {/if}

  <!-- At the card's foot: the phones pair with the address and token this card proved (#431). -->
  <PhonesSection {connection} />
</div>

<!--
  Deliberately outside the card above, and deliberately below it. The card is the task somebody came
  here for; this is the thing they will need on the day that task stops working, and it is only
  useful if it is set up long before then.
-->
<section class="recovery">
  {#if RecoveryPanel}
    <RecoveryPanel {serverUrl} {token} />
  {:else}
    <h2 class="recovery-title">Recovery code</h2>
    <p class="recovery-lede">
      Your snapshots open with the passphrase above, or with your recovery code once you have made
      one.
    </p>
    <button type="button" onclick={openRecovery} disabled={loadingRecovery}>
      {loadingRecovery ? 'Loading' : 'Open the recovery code screen'}
    </button>
    {#if recoveryError}
      <p class="error" role="alert">{recoveryError}</p>
    {/if}
  {/if}
</section>

<style>
  .sync { display: flex; flex-direction: column; gap: var(--space-3); max-width: 34rem; }
  .warn-banner {
    margin: 0;
    /* The lower-assurance banner is a genuine warning — the page is served by the server it
       talks to — so it takes warn severity. It was --mood-3-wash inside a plain hairline: a
       person's mid mood step, reading as neither information nor warning. The prose is
       untouched. */
    background: var(--amber-wash);
    border: 1px solid var(--amber);
    border-radius: var(--radius-sm);
    padding: var(--space-3);
    font-size: 0.9rem;
    color: var(--ink-soft);
  }
  label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.9rem; }
  label span { color: var(--ink-soft); }
  label em { color: var(--text-subtle); font-style: normal; }
  input {
    font: inherit;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--paper-bg);
    color: var(--ink-text);
  }
  button { align-self: flex-start; }
  .sync-actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
  .note { margin: 0; color: var(--ink-text); }
  /* Failure takes the single alarm hue; it was --mood-1, a person's worst reported day. */
  .error { color: var(--clay); background: var(--clay-wash); border: 1px solid var(--clay); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); margin: 0; }

  /* The recovery entry point. Wider than the sync card once the panel is open, because the code
     sheet needs the room to draw thirty characters large enough to copy accurately. Until then it
     is two sentences and a button, and stays within the same reading measure as everything above. */
  .recovery {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
    margin-top: var(--space-6);
    padding-top: var(--space-5);
    border-top: 1px solid var(--hairline);
  }
  .recovery-title { font-size: 1.3rem; margin: 0; }
  .recovery-lede { margin: 0; max-width: 34rem; font-size: 0.9rem; color: var(--ink-soft); }
</style>
