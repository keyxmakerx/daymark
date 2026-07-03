<script lang="ts">
  import { parseBackup, BackupParseError, type BackupData } from './lib/backup'
  import { formatDate } from './lib/stats'
  import Dropzone from './lib/components/Dropzone.svelte'
  import Dashboard from './lib/components/Dashboard.svelte'
  import TrustBar from './lib/components/TrustBar.svelte'
  import SyncPanel from './lib/components/SyncPanel.svelte'
  import Assessments from './lib/components/Assessments.svelte'
  import OwnerConsole from './lib/components/owner/OwnerConsole.svelte'
  import RecoverAccess from './lib/components/owner/RecoverAccess.svelte'

  type Source = 'file' | 'sync' | 'assess' | 'owner' | 'recover'

  let data = $state<BackupData | null>(null)
  let fileName = $state('')
  let error = $state('')
  let source = $state<Source>('file')

  const online = typeof navigator !== 'undefined' ? navigator.onLine : false

  function load(text: string, name: string) {
    error = ''
    try {
      data = parseBackup(text)
      fileName = name
    } catch (e) {
      data = null
      error = e instanceof BackupParseError ? e.message : 'Could not read that backup.'
    }
  }

  function loadData(parsed: BackupData, name: string) {
    error = ''
    data = parsed
    fileName = name
  }

  function reset() {
    data = null
    fileName = ''
    error = ''
  }
</script>

<div class="shell">
  <header class="topbar">
    <div class="brand">
      <span class="mark" aria-hidden="true"></span>
      <div>
        <h1>Daymark Companion</h1>
        <p class="muted tagline">Offline report viewer</p>
      </div>
    </div>
    {#if data}
      <button onclick={reset}>Open another backup</button>
    {/if}
  </header>

  <main>
    <TrustBar {online} />

    {#if !data}
      <section class="intro">
        <nav class="tabs source" aria-label="Data source">
          <button class:active={source === 'file'} aria-pressed={source === 'file'} onclick={() => (source = 'file')}>Open a backup file</button>
          <button class:active={source === 'sync'} aria-pressed={source === 'sync'} onclick={() => (source = 'sync')}>Connect to sync</button>
          <button class:active={source === 'assess'} aria-pressed={source === 'assess'} onclick={() => (source = 'assess')}>Self-checks</button>
          <button class:active={source === 'owner'} aria-pressed={source === 'owner'} onclick={() => (source = 'owner')}>Owner console</button>
          <button class:active={source === 'recover'} aria-pressed={source === 'recover'} onclick={() => (source = 'recover')}>Recover access</button>
        </nav>

        {#if source === 'file'}
          <Dropzone onload={load} onerror={(m) => (error = m)} />
        {:else if source === 'sync'}
          <SyncPanel onload={loadData} />
        {:else if source === 'assess'}
          <Assessments />
        {:else if source === 'recover'}
          <RecoverAccess />
        {:else}
          <OwnerConsole data={null} />
        {/if}

        {#if error}
          <p class="error" role="alert">{error}</p>
        {/if}
        {#if source !== 'assess' && source !== 'owner' && source !== 'recover'}
          <p class="faint note">
            Non-diagnostic: Daymark is a self-tracking and journaling tool. Nothing here
            is a medical assessment. Export a backup from the app via
            <em>Settings → Export backup</em>, then drop the <code>.json</code> file above —
            or pull your latest encrypted snapshot from your own sync server.
          </p>
        {/if}
      </section>
    {:else}
      <section class="loaded">
        <p class="muted filemeta">
          <strong>{fileName}</strong> · backup v{data.version} · exported {formatDate(data.exportedAt)}
        </p>

        <Dashboard {data} />
      </section>
    {/if}
  </main>

  <footer class="foot faint">
    <p>
      Daymark Companion · Phase-0 viewer · GPL-3.0 · runs entirely on your device.
      <span class="status">design-stage scaffold</span>
    </p>
  </footer>
</div>

<style>
  .shell { max-width: var(--maxw); margin: 0 auto; padding: var(--space-5) var(--space-4) var(--space-8); display: flex; flex-direction: column; gap: var(--space-5); min-height: 100vh; }
  .topbar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-4); }
  .brand { display: flex; align-items: center; gap: var(--space-3); }
  .mark { width: 2rem; height: 2rem; border-radius: 0.5rem; background: linear-gradient(135deg, var(--mood-4), var(--mood-5)); box-shadow: var(--elevation); }
  .tagline { margin: 0; font-size: 0.9rem; }
  main { display: flex; flex-direction: column; gap: var(--space-5); flex: 1; }
  .intro { display: flex; flex-direction: column; gap: var(--space-4); }
  .note { max-width: 42rem; }
  .error { color: var(--mood-1); background: var(--mood-1-wash); border: 1px solid var(--mood-1); border-radius: var(--radius-sm); padding: var(--space-3) var(--space-4); margin: 0; }
  .filemeta { margin: 0; }
  .tabs { display: flex; gap: var(--space-2); }
  .tabs button.active { background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }
  .foot { border-top: 1px solid var(--hairline); padding-top: var(--space-4); font-size: 0.85rem; }
  .status { font-style: italic; }
  code { font-family: var(--font-mono); background: var(--paper-bg); padding: 0 0.25rem; border-radius: 4px; }
</style>
