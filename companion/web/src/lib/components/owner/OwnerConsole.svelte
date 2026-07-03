<script lang="ts">
  import type { BackupData } from '../../backup'
  import Dashboard from '../Dashboard.svelte'
  import OwnerUnlock from './OwnerUnlock.svelte'
  import GrantManager from './GrantManager.svelte'
  import AssignmentInbox from './AssignmentInbox.svelte'
  import ShareBuilder from './ShareBuilder.svelte'
  import AuditList from './AuditList.svelte'
  import PinnedTherapistPicker from './PinnedTherapistPicker.svelte'
  import { withGrant, type OwnerSession } from './session'
  import { PortalClient } from '../../sync/portal'
  import type { Grant } from '../../assignments/types'

  let { data }: { data: BackupData | null } = $props()

  type Sub = 'review' | 'grants' | 'inbox' | 'share' | 'access-log'

  let session = $state<OwnerSession | null>(null)
  let sub = $state<Sub>('grants')
  let selectedId = $state<string | null>(null)

  // Server connection for the portal blob/invite calls (owner bearer token).
  let serverUrl = $state('')
  let token = $state('')
  let smtpEnabled = $state(false)
  let client = $state<PortalClient | null>(null)
  let connectStatus = $state('')

  const selected = $derived(session?.pinned.find((t) => t.id === selectedId) ?? null)

  function unlock(s: OwnerSession) {
    session = s
    selectedId = s.pinned[0]?.id ?? null
  }

  function lock() {
    session = null
    client = null
    selectedId = null
  }

  async function connect() {
    connectStatus = ''
    if (!token) { connectStatus = 'Enter your owner access token.'; return }
    const c = new PortalClient(serverUrl, token)
    try {
      const cfg = await c.getConfig()
      smtpEnabled = cfg.smtpEnabled
      client = c
      connectStatus = 'Connected.'
    } catch {
      client = c // still usable for blob calls; config probe is best-effort
      connectStatus = 'Connected (config probe failed; email invites hidden).'
    }
  }

  function onGrantChange(grant: Grant) {
    if (!session || !selectedId) return
    session = withGrant(session, selectedId, grant)
  }
</script>

{#if !session}
  <OwnerUnlock onunlock={unlock} />
{:else}
  <section class="console">
    <div class="topline">
      <nav class="tabs subnav" aria-label="Owner console section">
        <button class:active={sub === 'review'} aria-pressed={sub === 'review'} onclick={() => (sub = 'review')}>Review</button>
        <button class:active={sub === 'grants'} aria-pressed={sub === 'grants'} onclick={() => (sub = 'grants')}>Grants</button>
        <button class:active={sub === 'inbox'} aria-pressed={sub === 'inbox'} onclick={() => (sub = 'inbox')}>Inbox</button>
        <button class:active={sub === 'share'} aria-pressed={sub === 'share'} onclick={() => (sub = 'share')}>Share</button>
        <button class:active={sub === 'access-log'} aria-pressed={sub === 'access-log'} onclick={() => (sub = 'access-log')}>Access log</button>
      </nav>
      <button class="lock" onclick={lock}>Lock console</button>
    </div>

    <details class="conn">
      <summary>Server connection {client ? '· connected' : '· not connected'}</summary>
      <div class="conn-body">
        <label><span>Server URL <em>(blank = this server)</em></span><input type="url" bind:value={serverUrl} placeholder="https://daymark.example.com" autocomplete="off" /></label>
        <label><span>Owner access token</span><input type="password" bind:value={token} autocomplete="off" /></label>
        <button onclick={connect}>Connect</button>
        {#if connectStatus}<span class="cstatus">{connectStatus}</span>{/if}
      </div>
    </details>

    {#if sub === 'review'}
      {#if data}
        <Dashboard {data} />
      {:else}
        <p class="empty faint">No backup loaded. Open a backup or connect to sync to review your own data here.</p>
      {/if}
    {:else}
      <div class="who">
        <PinnedTherapistPicker therapists={session.pinned} {selectedId} onselect={(id) => (selectedId = id)} />
      </div>

      {#if !selected}
        <p class="empty faint">Pin a therapist in the unlock step to grant, review, or share.</p>
      {:else if sub === 'grants'}
        <GrantManager {session} therapist={selected} {client} {onGrantChange} />
      {:else if sub === 'inbox'}
        <AssignmentInbox {session} {client} />
      {:else if sub === 'share'}
        <ShareBuilder {session} therapist={selected} {data} {client} {smtpEnabled} />
      {:else if sub === 'access-log'}
        <AuditList therapist={selected} {client} />
      {/if}
    {/if}
  </section>
{/if}

<style>
  .console { display: flex; flex-direction: column; gap: var(--space-4); }
  .topline { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); flex-wrap: wrap; }
  .tabs { display: flex; gap: var(--space-2); }
  .tabs button.active { background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }
  .conn { border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); }
  .conn summary { cursor: pointer; font-size: 0.9rem; color: var(--ink-soft); }
  .conn-body { display: flex; flex-direction: column; gap: var(--space-2); margin-top: var(--space-3); }
  .conn-body label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .conn-body label span { color: var(--ink-soft); }
  .conn-body em { color: var(--ink-faint); font-style: normal; }
  input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .cstatus { font-size: 0.8rem; color: var(--ink-soft); }
  .who { padding-bottom: var(--space-2); }
  .empty { margin: 0; }
</style>
