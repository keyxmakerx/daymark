<script lang="ts">
  import { parseBackup, BackupParseError, type BackupData } from './lib/backup'
  import { formatDate } from './lib/stats'
  import Dropzone from './lib/components/Dropzone.svelte'
  import Overview from './lib/components/Overview.svelte'
  import JournalReader from './lib/components/JournalReader.svelte'
  import TrustBar from './lib/components/TrustBar.svelte'

  type Tab = 'overview' | 'journal'

  let data = $state<BackupData | null>(null)
  let fileName = $state('')
  let error = $state('')
  let tab = $state<Tab>('overview')

  const online = typeof navigator !== 'undefined' ? navigator.onLine : false

  function load(text: string, name: string) {
    error = ''
    try {
      data = parseBackup(text)
      fileName = name
      tab = 'overview'
    } catch (e) {
      data = null
      error = e instanceof BackupParseError ? e.message : 'Could not read that backup.'
    }
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
        <Dropzone onload={load} onerror={(m) => (error = m)} />
        {#if error}
          <p class="error" role="alert">{error}</p>
        {/if}
        <p class="faint note">
          Non-diagnostic: Daymark is a self-tracking and journaling tool. Nothing here
          is a medical assessment. Export a backup from the app via
          <em>Settings → Export backup</em>, then drop the <code>.json</code> file above.
        </p>
      </section>
    {:else}
      <section class="loaded">
        <p class="muted filemeta">
          <strong>{fileName}</strong> · backup v{data.version} · exported {formatDate(data.exportedAt)}
        </p>

        <nav class="tabs" aria-label="Report sections">
          <button class:active={tab === 'overview'} aria-pressed={tab === 'overview'} onclick={() => (tab = 'overview')}>Overview</button>
          <button class:active={tab === 'journal'} aria-pressed={tab === 'journal'} onclick={() => (tab = 'journal')}>Journal</button>
        </nav>

        {#if tab === 'overview'}
          <Overview {data} />
        {:else}
          <JournalReader {data} />
        {/if}
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
