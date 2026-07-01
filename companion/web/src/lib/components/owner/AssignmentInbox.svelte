<script lang="ts">
  import { buildInbox, type InboxItem, type RawAssignmentBlob, type Decision } from '../../assignments/inbox'
  import AssignmentCard from './AssignmentCard.svelte'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'
  import type { OwnerSession, PinnedTherapist } from './session'
  import { PortalClient } from '../../sync/portal'

  let {
    session,
    client,
  }: {
    session: OwnerSession
    client: PortalClient | null
  } = $props()

  let items = $state<InboxItem[]>([])
  let busy = $state(false)
  let error = $state('')
  let loaded = $state(false)

  async function refresh() {
    if (!client) {
      error = 'No server configured — nothing to fetch.'
      return
    }
    error = ''
    busy = true
    try {
      const blobs: RawAssignmentBlob[] = []
      for (const t of session.pinned) {
        const lineages = await client.listLineages(t.inboxToken, 'assignments').catch(() => [])
        for (const lineage of lineages) {
          const versions = await client.listVersions(t.inboxToken, 'assignments', lineage)
          // Only surface the head of each lineage (append-only supersede).
          const head = versions.reduce((a, b) => (b.version > a.version ? b : a), versions[0])
          if (!head) continue
          const bytes = await client.getBlob(t.inboxToken, 'assignments', lineage, head.version)
          blobs.push({ therapistId: t.id, lineage, version: head.version, bytes })
        }
      }
      items = buildInbox(blobs, session.pinned as PinnedTherapist[], session.ownerBox)
      loaded = true
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not load the inbox.'
    } finally {
      busy = false
    }
  }

  function decide(idx: number, decision: Decision) {
    items = items.map((it, i) => (i === idx ? { ...it, decision } : it))
    // NB: persisting an owner-signed acknowledgement blob is handled by the ack lineage; wiring
    // the ack PUT is deferred to the therapist-receipt slice (see slice unverifiableHere note).
  }
</script>

<section class="inbox">
  <NonDiagnosticBanner />

  <div class="bar">
    <h3>Assignment inbox</h3>
    <button onclick={refresh} disabled={busy}>{busy ? 'Loading…' : 'Refresh'}</button>
  </div>

  {#if error}
    <p class="error" role="alert">{error}</p>
  {/if}

  {#if !loaded && !busy}
    <p class="empty faint">Refresh to fetch assignments from your therapists.</p>
  {:else if loaded && items.length === 0}
    <p class="empty faint">No assignments to review.</p>
  {:else}
    <div class="cards">
      {#each items as item, idx (item.raw.lineage + ':' + item.raw.version)}
        <AssignmentCard
          {item}
          onaccept={() => decide(idx, 'accepted')}
          ondecline={() => decide(idx, 'declined')}
          onsnooze={() => decide(idx, 'snoozed')}
        />
      {/each}
    </div>
  {/if}
</section>

<style>
  .inbox { display: flex; flex-direction: column; gap: var(--space-4); }
  .bar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
  .bar h3 { margin: 0; }
  .cards { display: flex; flex-direction: column; gap: var(--space-3); }
  .empty { margin: 0; }
  .error { color: var(--mood-1); background: var(--mood-1-wash); border: 1px solid var(--mood-1); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); margin: 0; }
</style>
