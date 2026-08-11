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
  import ToolBuilder from './lib/components/ToolBuilder.svelte'
  import type { InstrumentDefinition } from './lib/instruments/types'

  type Source = 'file' | 'sync' | 'assess' | 'build' | 'owner' | 'recover'

  let data = $state<BackupData | null>(null)
  let fileName = $state('')
  let error = $state('')
  // An emailed access-token recovery link lands here as `#t=<token>` (see RecoverAccess.svelte);
  // land the owner straight on the recovery tab instead of the default "open a backup" tab.
  const startedOnRecoveryLink = typeof window !== 'undefined' && /(?:^|[#&])t=/.test(window.location.hash)
  let source = $state<Source>(startedOnRecoveryLink ? 'recover' : 'file')

  // Which posture the trust strip should state. Derived from the tab, NOT from
  // navigator.onLine — being offline this instant says nothing about whether the surface
  // would call out, and the strip used to go green on exactly that reasoning. Once a
  // backup is loaded the strip describes the tab that loaded it, which is why `data`
  // does not reset this.
  const trustSurface: 'local' | 'sync' | 'account' = $derived(
    source === 'sync' ? 'sync' : source === 'owner' || source === 'recover' ? 'account' : 'local',
  )

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

  // A built tool is exported as a validated instrument-definition JSON; it can later be shipped
  // in the catalog or delivered via the assignment channel (that plumbing is a separate slice).
  function publishTool(def: InstrumentDefinition) {
    const blob = new Blob([JSON.stringify(def, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `daymark-tool-${def.instrumentId || 'draft'}.json`
    a.click()
    URL.revokeObjectURL(url)
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
    <TrustBar surface={trustSurface} />

    {#if !data}
      <section class="intro">
        <nav class="tabs source" aria-label="Data source">
          <button class:active={source === 'file'} aria-pressed={source === 'file'} onclick={() => (source = 'file')}>Open a backup file</button>
          <button class:active={source === 'sync'} aria-pressed={source === 'sync'} onclick={() => (source = 'sync')}>Connect to sync</button>
          <button class:active={source === 'assess'} aria-pressed={source === 'assess'} onclick={() => (source = 'assess')}>Self-checks</button>
          <button class:active={source === 'build'} aria-pressed={source === 'build'} onclick={() => (source = 'build')}>Build a tool</button>
          <button class:active={source === 'owner'} aria-pressed={source === 'owner'} onclick={() => (source = 'owner')}>Owner console</button>
          <button class:active={source === 'recover'} aria-pressed={source === 'recover'} onclick={() => (source = 'recover')}>Recover access</button>
        </nav>

        {#if source === 'file'}
          <Dropzone onload={load} onerror={(m) => (error = m)} />
        {:else if source === 'sync'}
          <SyncPanel onload={loadData} />
        {:else if source === 'assess'}
          <Assessments />
        {:else if source === 'build'}
          <ToolBuilder onPublish={publishTool} />
        {:else if source === 'recover'}
          <RecoverAccess />
        {:else}
          <OwnerConsole data={null} />
        {/if}

        {#if error}
          <p class="error" role="alert">{error}</p>
        {/if}
        {#if source !== 'assess' && source !== 'build' && source !== 'owner' && source !== 'recover'}
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
  /* The brand mark is structural, not a reading: it says "Daymark", not "this person had a good
     day". It was a --mood-4 → --mood-5 gradient, which spent the top two steps of a person's
     reported-experience ramp on a logo. Structural accent instead. */
  .mark { width: 2rem; height: 2rem; border-radius: 0.5rem; background: linear-gradient(135deg, var(--indigo), var(--indigo-deep)); box-shadow: var(--elevation); }
  .tagline { margin: 0; font-size: 0.9rem; }
  main { display: flex; flex-direction: column; gap: var(--space-5); flex: 1; }
  .intro { display: flex; flex-direction: column; gap: var(--space-4); }
  .note { max-width: 42rem; }
  /* Failure is the single alarm hue. It was --mood-1, the step meaning "this person reported an
     awful day" — interface state wearing a person's data. */
  .error { color: var(--clay); background: var(--clay-wash); border: 1px solid var(--clay); border-radius: var(--radius-sm); padding: var(--space-3) var(--space-4); margin: 0; }
  .filemeta { margin: 0; }
  .tabs { display: flex; gap: var(--space-2); }
  /* The selected source is STRUCTURE — "where am I" — so it takes the structural accent, the
     same as every other tab bar in the product (OwnerConsole, TherapistPortal, AssignSurface,
     PinnedTherapistPicker, CapabilityRow, Dashboard's range control). This one site was still
     on --ink-accent, which is content ink and reads as emphasis on a person's material; solid
     ink stays reserved for the primary ACTION on a screen. aria-pressed already carries the
     selection, so the fill is never the only signal. */
  .tabs button.active { background: var(--indigo); color: var(--on-accent); border-color: var(--indigo); }
  .foot { border-top: 1px solid var(--hairline); padding-top: var(--space-4); font-size: 0.85rem; }
  .status { font-style: italic; }
  code { font-family: var(--font-mono); background: var(--paper-bg); padding: 0 0.25rem; border-radius: 4px; }
</style>
