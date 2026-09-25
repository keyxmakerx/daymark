<script lang="ts">
  import { buildInbox, fetchInbox, goneItemLine, type InboxItem, type GoneItem, type Decision } from '../../assignments/inbox'
  import AssignmentCard from './AssignmentCard.svelte'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'
  import type { OwnerSession, PinnedTherapist } from './session'
  import { PortalClient } from '../../sync/portal'
  import { Callout, EmptyState } from '../ui'

  let {
    session,
    client,
  }: {
    session: OwnerSession
    client: PortalClient | null
  } = $props()

  let items = $state<InboxItem[]>([])
  let gone = $state<GoneItem[]>([])
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
      // Item by item: an item the server no longer keeps becomes one line, not a failed inbox.
      const fetched = await fetchInbox(client, session.pinned)
      items = buildInbox(fetched.blobs, session.pinned as PinnedTherapist[], session.ownerBox)
      gone = fetched.gone
      loaded = true
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not load the inbox.'
    } finally {
      busy = false
    }
  }

  function decide(idx: number, decision: Decision) {
    items = items.map((it, i) => (i === idx ? { ...it, decision } : it))
    // NB: persisting an owner-signed acknowledgement blob is handled by the ack lineage; the ack
    // PUT is not built: #234.
  }
</script>

<section class="inbox">
  <NonDiagnosticBanner />

  <div class="bar">
    <h3>Assignment inbox</h3>
    <button onclick={refresh} disabled={busy}>{busy ? 'Loading…' : 'Refresh'}</button>
  </div>

  {#if error}
    <Callout tone="critical">{error}</Callout>
  {/if}

  {#if !loaded && !busy}
    <EmptyState title="Refresh to fetch assignments from your therapists." />
  {:else if loaded && items.length === 0 && gone.length === 0}
    <EmptyState title="No assignments to review." />
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
      {#each gone as g (g.lineage + ':' + g.version)}
        <p class="gone">{goneItemLine(g.therapistName, new Date(g.sentAt).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' }))}</p>
      {/each}
    </div>
  {/if}
</section>

<style>
  .inbox { display: flex; flex-direction: column; gap: var(--space-4); }
  .bar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
  .bar h3 { margin: 0; }
  .cards { display: flex; flex-direction: column; gap: var(--space-3); }
  .gone { margin: 0; color: var(--ink-text); font-size: 0.9rem; }
</style>
