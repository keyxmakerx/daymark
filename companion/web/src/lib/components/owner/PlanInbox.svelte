<script lang="ts">
  /*
   * GAME PLANS FROM THE OWNER'S CLINICIANS (#231). Fetches each clinician's plans, opens and checks
   * each one (gameplans/planInbox.ts), and keeps the owner's accept or decline in the owner's lane,
   * written there before the card shows it, as the assignment inbox does. Every rule is
   * planInbox.ts's; this component holds only what is on screen.
   */
  import { fetchInbox, goneItemLine, type GoneItem } from '../../assignments/inbox'
  import {
    applyPlanDecisions,
    buildPlans,
    canDecide,
    kindLabel,
    perWeek,
    planDecisionRecordFor,
    planHeading,
    planRefusal,
    PLAN_ACCEPTED,
    PLAN_DECLINED,
    PLAN_WITHDRAWN,
    PLANS_EMPTY,
    PLANS_EMPTY_BEFORE,
    PLANS_FRAMING,
    PLANS_HEADING,
    type PlanDecision,
    type PlanItem,
  } from '../../gameplans/planInbox'
  import type { OwnerSession, PinnedTherapist } from './session'
  import { PortalClient } from '../../sync/portal'
  import { pause } from '../../sync/paced'
  import { LaneWriteError, type OwnerLane } from '../../lane/lane'
  import { DECISIONS_UNREAD, LANE_FAULT_TEXT, SOME_DECISIONS_UNOPENED } from '../../lane/copy'
  import { formatDate } from '../../stats'
  import { Callout, EmptyState } from '../ui'

  let {
    session,
    client,
    lane,
  }: {
    session: OwnerSession
    client: PortalClient | null
    lane: OwnerLane | null
  } = $props()

  let items = $state<PlanItem[]>([])
  let gone = $state<GoneItem[]>([])
  let busy = $state(false)
  let error = $state('')
  let loaded = $state(false)
  let laneNote = $state<{ text: string; tone: 'warn' | 'critical' } | null>(null)
  let saving = $state<string | null>(null)

  const keyOf = (item: PlanItem) => `${item.therapistId}:${item.raw.lineage}:${item.raw.version}`

  async function refresh() {
    if (!client) {
      error = 'No server configured — nothing to fetch.'
      return
    }
    error = ''
    laneNote = null
    busy = true
    try {
      const fetched = await fetchInbox(client, session.pinned, pause, 'gameplans')
      let built = buildPlans(fetched.blobs, session.pinned as PinnedTherapist[], session.ownerBox)
      if (lane) {
        try {
          const reading = await lane.read()
          built = applyPlanDecisions(built, reading.records)
          if (reading.unopened.length > 0) laneNote = { text: SOME_DECISIONS_UNOPENED, tone: 'warn' }
        } catch {
          laneNote = { text: DECISIONS_UNREAD, tone: 'warn' }
        }
      }
      items = built
      gone = fetched.gone
      loaded = true
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not load the game plans.'
    } finally {
      busy = false
    }
  }

  /** A decision is written to the lane first, and shown only once the server has taken it. */
  async function decide(item: PlanItem, decision: PlanDecision) {
    if (saving) return
    laneNote = null
    const record = planDecisionRecordFor(item, decision)
    if (!record) return
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
    items = items.map((it) => (keyOf(it) === keyOf(item) ? { ...it, decision } : it))
  }
</script>

<section class="plans">
  <div class="bar">
    <h3>{PLANS_HEADING}</h3>
    <button onclick={refresh} disabled={busy || saving !== null}>{busy ? 'Loading…' : 'Refresh'}</button>
  </div>
  <p class="framing">{PLANS_FRAMING}</p>

  {#if error}
    <Callout tone="critical">{error}</Callout>
  {/if}
  {#if laneNote}
    <Callout tone={laneNote.tone}>{laneNote.text}</Callout>
  {/if}

  {#if !loaded && !busy}
    <EmptyState title={PLANS_EMPTY_BEFORE} />
  {:else if loaded && items.length === 0 && gone.length === 0}
    <EmptyState title={PLANS_EMPTY} />
  {:else}
    <div class="cards">
      {#each items as item (keyOf(item))}
        <article class="card plan" class:bad={item.verdict !== 'VERIFIED'}>
          <header class="head">
            <span class="from">{planHeading(item)}</span>
            {#if item.plan}<span class="when faint">{formatDate(item.plan.issuedAt)}</span>{/if}
          </header>

          {#if item.verdict !== 'VERIFIED'}
            <p class="refusal">{planRefusal(item)}</p>
          {:else if item.plan}
            {#if item.plan.status === 'withdrawn'}
              <p class="quiet">{PLAN_WITHDRAWN}</p>
            {/if}
            <ul class="items">
              {#each item.plan.items as it (it.itemRef)}
                <li>
                  <span class="kind">{kindLabel(it.kind)}</span>
                  <span class="title">{it.title}</span>
                  {#if it.detail}<span class="detail">{it.detail}</span>{/if}
                  {#if it.targetPerWeek}<span class="detail">{perWeek(it.targetPerWeek)}</span>{/if}
                </li>
              {/each}
            </ul>
          {/if}

          {#if canDecide(item)}
            <footer class="actions">
              {#if item.decision === 'accepted'}
                <span class="quiet">{PLAN_ACCEPTED}</span>
              {:else if item.decision === 'declined'}
                <span class="quiet">{PLAN_DECLINED}</span>
              {:else}
                <button class="primary" onclick={() => decide(item, 'accepted')} disabled={saving !== null}>Accept</button>
                <button onclick={() => decide(item, 'declined')} disabled={saving !== null}>Decline</button>
              {/if}
            </footer>
          {/if}
        </article>
      {/each}
      {#each gone as g (g.lineage + ':' + g.version)}
        <p class="gone">{goneItemLine(g.therapistName, new Date(g.sentAt).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' }))}</p>
      {/each}
    </div>
  {/if}
</section>

<style>
  .plans { display: flex; flex-direction: column; gap: var(--space-4); }
  .bar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
  .bar h3 { margin: 0; }
  .framing { margin: 0; color: var(--ink-soft); }
  .cards { display: flex; flex-direction: column; gap: var(--space-3); }
  .plan { display: flex; flex-direction: column; gap: var(--space-2); }
  /* A plan that failed a check is a needs-a-human state: the single alarm hue (AssignmentCard). */
  .plan.bad { border-color: var(--clay); }
  .refusal { margin: 0; color: var(--clay); }
  .head { display: flex; align-items: baseline; gap: var(--space-2); flex-wrap: wrap; }
  .from { font-weight: 500; }
  .when { font-size: 0.8rem; }
  .items { margin: 0; padding-left: var(--space-4); display: flex; flex-direction: column; gap: var(--space-1); }
  .kind { font-size: 0.8rem; color: var(--ink-soft); margin-right: var(--space-2); }
  .detail { display: block; font-size: 0.85rem; color: var(--ink-soft); }
  .quiet { margin: 0; color: var(--ink-soft); font-size: 0.9rem; }
  .actions { display: flex; gap: var(--space-2); }
  .gone { margin: 0; color: var(--ink-text); font-size: 0.9rem; }
</style>
