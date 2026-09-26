<script lang="ts">
  /*
   * THE ASSIGNMENT INBOX. Fetches each clinician's items, opens and checks each one
   * (assignments/inbox.ts), and keeps the owner's accept or decline in the owner's lane on their own
   * server (#234, #345): a decision is written there before the card shows it, and every Refresh
   * reads the lanes back and puts each decision on the item it answers. Which decisions are kept,
   * and how one finds its item again, is assignments/inboxLane.ts's; this component holds only what
   * is on screen.
   */
  import { buildInbox, fetchInbox, goneItemLine, type InboxItem, type GoneItem, type Decision } from '../../assignments/inbox'
  import { applyDecisions, decisionRecordFor } from '../../assignments/inboxLane'
  import AssignmentCard from './AssignmentCard.svelte'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'
  import type { OwnerSession, PinnedTherapist } from './session'
  import { PortalClient } from '../../sync/portal'
  import { LaneWriteError, type OwnerLane } from '../../lane/lane'
  import { DECISIONS_UNREAD, LANE_FAULT_TEXT, SOME_DECISIONS_UNOPENED } from '../../lane/copy'
  import { Callout, EmptyState } from '../ui'

  let {
    session,
    client,
    lane,
  }: {
    session: OwnerSession
    client: PortalClient | null
    /** The owner's lane: where an accept or a decline is kept, and read back from on every Refresh. */
    lane: OwnerLane | null
  } = $props()

  let items = $state<InboxItem[]>([])
  let gone = $state<GoneItem[]>([])
  let busy = $state(false)
  let error = $state('')
  let loaded = $state(false)
  /**
   * What the lane said last: a decision that was not saved, or not confirmed, or decisions that could
   * not be read. A refusal is drawn as one; the rest are warnings.
   */
  let laneNote = $state<{ text: string; tone: 'warn' | 'critical' } | null>(null)
  /** The item whose decision is being saved; its buttons wait, and so does Refresh. */
  let saving = $state<string | null>(null)

  const keyOf = (item: InboxItem) => `${item.raw.lineage}:${item.raw.version}`

  async function refresh() {
    if (!client) {
      error = 'No server configured — nothing to fetch.'
      return
    }
    error = ''
    laneNote = null
    busy = true
    try {
      // Item by item: an item the server no longer keeps becomes one line, not a failed inbox.
      const fetched = await fetchInbox(client, session.pinned)
      let built = buildInbox(fetched.blobs, session.pinned as PinnedTherapist[], session.ownerBox)
      // The decisions kept in the owner's lane, back on the items they answer.
      if (lane) {
        try {
          const reading = await lane.read()
          built = applyDecisions(built, reading.records)
          if (reading.unopened.length > 0) laneNote = { text: SOME_DECISIONS_UNOPENED, tone: 'warn' }
        } catch {
          laneNote = { text: DECISIONS_UNREAD, tone: 'warn' }
        }
      }
      items = built
      gone = fetched.gone
      loaded = true
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not load the inbox.'
    } finally {
      busy = false
    }
  }

  /**
   * A kept decision is written to the lane first, and shown only once the server has taken it; one
   * that is not saved leaves the item undecided, with the lane's own words saying so. A snooze, and
   * a decision about an item that did not verify, are this session's only (inboxLane.ts).
   */
  async function decide(item: InboxItem, decision: Decision) {
    if (saving) return
    laneNote = null
    const record = decisionRecordFor(item, decision)
    if (record) {
      if (!lane) {
        laneNote = { text: LANE_FAULT_TEXT.notConnected, tone: 'critical' }
        return
      }
      saving = keyOf(item)
      try {
        await lane.add(record)
      } catch (e) {
        const fault = e instanceof LaneWriteError ? e.fault : 'refused'
        laneNote = { text: LANE_FAULT_TEXT[fault], tone: fault === 'unconfirmed' ? 'warn' : 'critical' }
        return
      } finally {
        saving = null
      }
    }
    items = items.map((it) => (keyOf(it) === keyOf(item) ? { ...it, decision } : it))
  }
</script>

<section class="inbox">
  <NonDiagnosticBanner />

  <div class="bar">
    <h3>Assignment inbox</h3>
    <button onclick={refresh} disabled={busy || saving !== null}>{busy ? 'Loading…' : 'Refresh'}</button>
  </div>

  {#if error}
    <Callout tone="critical">{error}</Callout>
  {/if}
  {#if laneNote}
    <Callout tone={laneNote.tone}>{laneNote.text}</Callout>
  {/if}

  {#if !loaded && !busy}
    <EmptyState title="Refresh to fetch assignments from your clinicians." />
  {:else if loaded && items.length === 0 && gone.length === 0}
    <EmptyState title="No assignments to review." />
  {:else}
    <div class="cards">
      {#each items as item (item.raw.lineage + ':' + item.raw.version)}
        <AssignmentCard
          {item}
          saving={saving === keyOf(item)}
          onaccept={() => decide(item, 'accepted')}
          ondecline={() => decide(item, 'declined')}
          onsnooze={() => decide(item, 'snoozed')}
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
