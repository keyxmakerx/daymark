<script lang="ts">
  import { PortalClient, type AuditEvent } from '../../sync/portal'
  import type { PinnedTherapist } from './session'
  import AuditCaveat from './AuditCaveat.svelte'
  import { auditActionLabel, auditActorLabel } from './auditLabels'

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
      error = 'Pin a therapist to see their access log.'
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
    <p class="error" role="alert">{error}</p>
  {/if}

  {#if !loaded && !busy}
    <p class="empty faint">Refresh to fetch this therapist's access log.</p>
  {:else if loaded && events.length === 0}
    <p class="empty faint">No access events recorded yet.</p>
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
  .when { color: var(--ink-faint); font-size: 0.85rem; white-space: nowrap; }
  .more { align-self: flex-start; }
  .empty { margin: 0; }
  .error { color: var(--mood-1); background: var(--mood-1-wash); border: 1px solid var(--mood-1); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); margin: 0; }
</style>
