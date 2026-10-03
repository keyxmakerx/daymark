<script lang="ts">
  import { PortalClient, type AuditEvent } from '../../sync/portal'
  import type { PinnedTherapist } from './session'
  import AuditCaveat from './AuditCaveat.svelte'
  import { auditActionLabel, auditActorLabel } from './auditLabels'
  import { Callout, Chip, EmptyState } from '../ui'
  import { CHAIN_HEAD_INTRO, readChainHead, type ChainHeadView } from '../../admin/chainHead'

  let {
    therapist,
    client,
  }: {
    therapist: PinnedTherapist | null
    client: PortalClient | null
  } = $props()

  let events = $state<AuditEvent[]>([])
  let cursor = $state<number | null>(null)
  let busy = $state(false)
  let error = $state('')
  let loaded = $state(false)

  async function refresh() {
    error = ''
    if (!therapist) {
      error = 'Pin a clinician to see their access log.'
      return
    }
    if (!client) {
      error = 'No server configured — nothing to fetch.'
      return
    }
    busy = true
    try {
      const page = await client.getAuditLog(therapist.inboxToken)
      events = page.events
      cursor = page.nextCursor
      loaded = true
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not load the access log.'
    } finally {
      busy = false
    }
  }

  async function loadMore() {
    if (!client || !therapist || cursor == null) return
    busy = true
    try {
      const page = await client.getAuditLog(therapist.inboxToken, cursor)
      events = [...events, ...page.events]
      cursor = page.nextCursor
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not load more of the access log.'
    } finally {
      busy = false
    }
  }

  /*
   * The server's own chain check over this relationship's log (lib/admin/chainHead.ts), read with
   * the token this console already holds. It lives here, beside the log it summarises, and not on
   * the server console, which never asks for the owner's token (#322).
   */
  let head = $state<ChainHeadView | null>(null)
  let checking = $state(false)

  async function checkChain() {
    if (!client || !therapist || checking) return
    checking = true
    try {
      head = readChainHead(await client.auditChainHead(therapist.inboxToken))
    } finally {
      checking = false
    }
  }

  /* A break and a dead transport alarm, a refusal warns, and every answer the server gave as
     documented stays neutral: the absence of something to say is the only reassurance here. */
  const HEAD_TONE: Record<ChainHeadView['verdict'], 'neutral' | 'warn' | 'critical'> = {
    'reported-consistent': 'neutral',
    'break-reported': 'critical',
    'nothing-recorded': 'neutral',
    'not-configured': 'neutral',
    refused: 'warn',
    unreachable: 'critical',
    unexpected: 'warn',
  }

  function formatWhen(ts: number): string {
    return new Date(ts * 1000).toLocaleString()
  }
</script>

<section class="audit">
  <AuditCaveat />

  <div class="bar">
    <h3>Access log</h3>
    <button onclick={refresh} disabled={busy}>{busy ? 'Loading…' : 'Refresh'}</button>
  </div>

  {#if error}
    <Callout tone="critical">{error}</Callout>
  {/if}

  {#if !loaded && !busy}
    <EmptyState title="Refresh to fetch this clinician's access log." />
  {:else if loaded && events.length === 0}
    <EmptyState title="No access events recorded." />
  {:else}
    <ul class="entries">
      {#each events as ev (ev.seq)}
        <li>
          <span class="who">{auditActorLabel(ev.actor)}</span>
          <span class="what">{auditActionLabel(ev.action)}</span>
          <time class="when">{formatWhen(ev.ts)}</time>
        </li>
      {/each}
    </ul>
    {#if cursor != null}
      <button class="more" onclick={loadMore} disabled={busy}>Load more</button>
    {/if}
  {/if}

  <div class="chain" id="audit-chain-check">
    <div class="bar">
      <h3>Chain check</h3>
      <button onclick={checkChain} disabled={checking || !client || !therapist}>
        {checking ? 'Checking…' : 'Check the chain'}
      </button>
    </div>
    <p class="intro">{CHAIN_HEAD_INTRO}</p>

    {#if head}
      <div class="verdict">
        <div class="verdict-head">
          <Chip tone={HEAD_TONE[head.verdict]}>{head.word}</Chip>
          {#if head.entryCount !== null && head.entryCount > 0}
            <span class="count">{head.entryCount} entries, sequence {head.oldestSeq} to {head.headSeq}</span>
          {/if}
        </div>
        <p>{head.headline}</p>
        {#if head.headGroups.length > 0}
          <!-- In reading groups, for copying down by hand: the value is the groups joined. -->
          <div class="digest" aria-label="Chain head digest, in reading groups">
            {#each head.headGroups as group, i (i)}<span class="digest-group">{group}</span>{/each}
          </div>
        {/if}
        {#if head.headNote}<p>{head.headNote}</p>{/if}
        {#if head.machineDetail}<pre class="machine">{head.machineDetail}</pre>{/if}
        <Callout tone="info" title="What this does not say">{head.caveat}</Callout>
        {#if head.notes.length > 0}
          <ul class="notes">
            {#each head.notes as note, i (i)}<li>{note}</li>{/each}
          </ul>
        {/if}
      </div>
    {/if}
  </div>
</section>

<style>
  .audit { display: flex; flex-direction: column; gap: var(--space-4); }
  .bar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
  .bar h3 { margin: 0; }
  .entries { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-2); }
  .entries li {
    display: flex;
    align-items: baseline;
    gap: var(--space-3);
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--hairline);
    border-radius: var(--radius-sm);
  }
  .who { font-weight: 600; }
  .what { color: var(--ink-soft); flex: 1; }
  .when { color: var(--text-subtle); font-size: 0.85rem; white-space: nowrap; }
  .more { align-self: flex-start; }
  .chain { display: flex; flex-direction: column; gap: var(--space-3); border-top: 1px solid var(--hairline); padding-top: var(--space-4); }
  .intro { margin: 0; color: var(--ink-soft); }
  .verdict { display: flex; flex-direction: column; gap: var(--space-3); }
  .verdict p { margin: 0; }
  .verdict-head { display: flex; align-items: baseline; flex-wrap: wrap; gap: var(--space-3); }
  .count { color: var(--text-subtle); font-size: 0.85rem; font-variant-numeric: tabular-nums; }
  .digest { display: flex; flex-wrap: wrap; gap: var(--space-1) var(--space-2); }
  .digest-group {
    font-family: var(--font-mono);
    font-size: 0.85rem;
    letter-spacing: 0.08em;
    padding: 0.125rem var(--space-2);
    border: 1px solid var(--hairline);
    border-radius: var(--radius-sm);
    white-space: nowrap;
  }
  .machine { margin: 0; font-family: var(--font-mono); font-size: 0.8rem; white-space: pre-wrap; overflow-wrap: anywhere; }
  .notes { margin: 0; padding-left: var(--space-4); color: var(--ink-soft); }
</style>
