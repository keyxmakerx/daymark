<script lang="ts">
  import type { BackupData } from '../../backup'
  import Dashboard from '../Dashboard.svelte'
  import OwnerUnlock from './OwnerUnlock.svelte'
  import GrantManager from './GrantManager.svelte'
  import AssignmentInbox from './AssignmentInbox.svelte'
  import ShareBuilder from './ShareBuilder.svelte'
  import AuditList from './AuditList.svelte'
  import PinnedTherapistPicker from './PinnedTherapistPicker.svelte'
  import NotificationSettings from './NotificationSettings.svelte'
  import PinRecord from './PinRecord.svelte'
  import TherapistKeyIntake from './TherapistKeyIntake.svelte'
  import { withGrant, type OwnerSession } from './session'
  import type { PinnedTherapist } from './session'
  import PairingPanel from './PairingPanel.svelte'
  import OwnerKeyPublish from './OwnerKeyPublish.svelte'
  import SharingStrip from './SharingStrip.svelte'
  import { emptyGrant } from '../../assignments/grant'
  import { fingerprint } from '../../assignments/crypto'
  import { sasWords } from '../../share/pairing'
  import { PortalClient } from '../../sync/portal'
  import type { OwnerEndpoint } from '../../owner/therapistKeys'
  import type { Grant } from '../../assignments/types'

  let { data }: { data: BackupData | null } = $props()

  // 'pins' is whole-browser rather than per-therapist — it shows the record of every key this
  // browser has written down, including therapists whose keys are not entered in this session —
  // so it renders beside 'notify' rather than under the therapist picker.
  //
  // 'published-keys' is the other half of that pair and is per-therapist, which is why it sits
  // inside the picker's scope: it reads what ONE therapist published for ONE relationship and
  // offers it for pinning. Two tabs about keys is deliberate rather than a split that wants
  // merging — "what they published" comes from the server and is not trusted yet, "pinned keys" is
  // what this browser decided to remember and is what every seal is checked against.
  type Sub = 'review' | 'grants' | 'inbox' | 'published-keys' | 'share' | 'access-log' | 'notify' | 'pins'

  let session = $state<OwnerSession | null>(null)
  let sub = $state<Sub>('grants')
  let selectedId = $state<string | null>(null)
  /*
   * The pairing screen outlives the moment it stops being needed. `keysArrived` flips keysPending
   * the instant the owner approves, which is correct — the keys ARE pinned then — but the therapist
   * has still to enrol, and the owner's "approved, waiting for them" state (with its take-it-back
   * button) is the only place that says so. So the share tab keeps showing the pairing screen until
   * the owner says they are done with it.
   */
  let pairingOpen = $state(false)

  // Server connection for the portal blob/invite calls (owner bearer token).
  let serverUrl = $state('')
  let token = $state('')
  let smtpEnabled = $state(false)
  let client = $state<PortalClient | null>(null)
  /*
   * The same base URL and token as `client`, captured at connect time for the owner calls that are
   * plain module functions rather than PortalClient methods. Captured rather than read live from
   * the two fields above, so editing the connection form without reconnecting cannot leave one
   * surface talking to a different server than the rest of the console.
   */
  let endpoint = $state<OwnerEndpoint | null>(null)
  let connectStatus = $state('')

  const selected = $derived(session?.pinned.find((t) => t.id === selectedId) ?? null)

  function unlock(s: OwnerSession) {
    session = s
    selectedId = s.pinned[0]?.id ?? null
  }

  function lock() {
    session = null
    client = null
    endpoint = null
    selectedId = null
  }

  async function connect() {
    connectStatus = ''
    if (!token) { connectStatus = 'Enter your owner access token.'; return }
    const c = new PortalClient(serverUrl, token)
    const e: OwnerEndpoint = { baseUrl: serverUrl, token }
    try {
      const cfg = await c.getConfig()
      smtpEnabled = cfg.smtpEnabled
      client = c
      endpoint = e
      connectStatus = 'Connected.'
    } catch {
      client = c // still usable for blob calls; config probe is best-effort
      endpoint = e
      connectStatus = 'Connected (config probe failed; email invites hidden).'
    }
  }

  /*
   * A pending clinician's keys arrived — confirmed against the read-aloud check, never merely
   * fetched. The entry is REBUILT rather than mutated: its id is the signing-key fingerprint and
   * its grant is bound to that id, so a pending entry (whose id was a placeholder) cannot keep
   * either. Name, inbox token and pinnedAt survive; the SAS words are computed now that there are
   * finally two identities to compute them over.
   */
  function keysArrived(record: { signPub: Uint8Array; boxPub: Uint8Array }) {
    if (!session || !selectedId) return
    const cur = session.pinned.find((t) => t.id === selectedId)
    if (!cur) return
    const signPub = record.signPub
    const boxPub = record.boxPub
    const id = fingerprint(signPub)
    const words = sasWords(
      { x25519Pub: session.ownerBox.publicKey, ed25519Pub: session.ownerSign.publicKey },
      { x25519Pub: boxPub, ed25519Pub: signPub },
    ).join(' ')
    const filled: PinnedTherapist = {
      ...cur,
      id,
      signPub,
      boxPub,
      grant: cur.keysPending ? emptyGrant(id) : cur.grant,
      fingerprintWords: words,
      keysPending: false,
    }
    session = { ...session, pinned: session.pinned.map((t) => (t.id === cur.id ? filled : t)) }
    selectedId = id
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
        <button class:active={sub === 'published-keys'} aria-pressed={sub === 'published-keys'} onclick={() => (sub = 'published-keys')}>Published keys</button>
        <button class:active={sub === 'share'} aria-pressed={sub === 'share'} onclick={() => (sub = 'share')}>Share</button>
        <button class:active={sub === 'access-log'} aria-pressed={sub === 'access-log'} onclick={() => (sub = 'access-log')}>Access log</button>
        <button class:active={sub === 'notify'} aria-pressed={sub === 'notify'} onclick={() => (sub = 'notify')}>Notifications</button>
        <button class:active={sub === 'pins'} aria-pressed={sub === 'pins'} onclick={() => (sub = 'pins')}>Pinned keys</button>
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

    <!--
      ABOVE THE TAB CONTENT, so it is on every owner screen rather than on the one screen about
      sharing (issue #105). It is rendered per pinned relationship, not per selected one: the
      Review, Notifications and Pinned-keys tabs have no selected therapist, and a standing notice
      that disappears when you change tab is not standing. Each strip renders nothing at all unless
      that relationship actually has a live share lineage.
    -->
    {#each session.pinned.filter((t) => t.inboxToken) as t (t.id)}
      <SharingStrip name={t.displayName} inboxToken={t.inboxToken} {client} />
    {/each}

    {#if sub === 'review'}
      {#if data}
        <Dashboard {data} />
      {:else}
        <p class="empty faint">No backup loaded. Open a backup or connect to sync to review your own data here.</p>
      {/if}
    {:else if sub === 'notify'}
      <NotificationSettings {client} />
    {:else if sub === 'pins'}
      <PinRecord {session} />
    {:else}
      <div class="who">
        <PinnedTherapistPicker therapists={session.pinned} {selectedId} onselect={(id) => (selectedId = id)} />
      </div>

      {#if !selected}
        <p class="empty faint">Add a clinician in the unlock step to grant, review, or share.</p>
      {:else if selected.keysPending && (sub === 'grants' || sub === 'inbox' || sub === 'access-log')}
        <!--
          Everything that grants, opens or verifies needs the clinician's keys, and a pending entry
          has none yet — by design, not by omission. Saying which step is missing beats disabling
          three tabs into grey mysteries.
        -->
        <p class="empty faint">
          {selected.displayName} has not published keys. Send the invitation from the Share tab;
          once they accept, their keys appear under Published keys for you to check and record.
        </p>
      {:else if sub === 'grants'}
        <GrantManager {session} therapist={selected} {client} {onGrantChange} />
      {:else if sub === 'inbox'}
        <AssignmentInbox {session} {client} />
      {:else if sub === 'published-keys'}
        <TherapistKeyIntake therapist={selected} {endpoint} onkeys={keysArrived} />
      {:else if sub === 'share'}
        <!-- Above both, because the clinician can verify nothing until this key is with the server,
             and that is as true for an established connection as for one being set up. -->
        <OwnerKeyPublish
          therapist={selected}
          {client}
          signPub={session.ownerSign.publicKey}
          boxPub={session.ownerBox.publicKey}
        />
        {#if selected.keysPending || pairingOpen}
          <!-- The invitation is mintable the moment a relationship has a token; sealing is not.
               ShareBuilder would offer both, so a pending clinician gets the half that exists. -->
          <PairingPanel
            therapist={selected}
            {client}
            baseUrl={serverUrl}
            {token}
            {smtpEnabled}
            scope={['read.share']}
            ownerSignPub={session.ownerSign.publicKey}
            ownerBoxPub={session.ownerBox.publicKey}
            onpaired={(keys) => { pairingOpen = true; keysArrived(keys) }}
            ondone={() => (pairingOpen = false)}
          />
        {:else}
          <ShareBuilder {session} therapist={selected} {data} {client} {smtpEnabled} />
        {/if}
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
  /* The active tab is structure, not content: --indigo is the system's one accent for "where
     you are" (active nav, links, focus ring). --ink-accent is the content ink and reads as
     emphasis on a person's material, which a section tab is not. aria-pressed already carries
     the selection for assistive tech, so the fill is never the only signal. */
  .tabs button.active { background: var(--indigo); color: var(--on-accent); border-color: var(--indigo); }
  .conn { border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); }
  .conn summary { cursor: pointer; font-size: 0.9rem; color: var(--ink-soft); }
  .conn-body { display: flex; flex-direction: column; gap: var(--space-2); margin-top: var(--space-3); }
  .conn-body label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .conn-body label span { color: var(--ink-soft); }
  .conn-body em { color: var(--text-subtle); font-style: normal; }
  input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .cstatus { font-size: 0.8rem; color: var(--ink-soft); }
  .who { padding-bottom: var(--space-2); }
  .empty { margin: 0; }
</style>
