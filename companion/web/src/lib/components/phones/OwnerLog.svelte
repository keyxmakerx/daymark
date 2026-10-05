<script lang="ts">
  /*
   * The owner's own log (#434 item 4), at the foot of the Phones section, read with the connection the
   * sync card proved in this visit. Nothing is fetched until the person asks: the log is a record to
   * look up, not a feed. Every word is lib/phones/ownerLog.ts.
   */
  import type { OwnerConnection } from '../../owner/recoveryEmail'
  import { OWNER_LOG_COPY as C, OwnerLogError, fetchOwnerLog, ownerLogLines, type OwnerLogLine } from '../../phones/ownerLog'

  let { connection }: { connection: OwnerConnection } = $props()

  let lines = $state<OwnerLogLine[]>([])
  let cursor = $state<number | null>(null)
  let loaded = $state(false)
  let busy = $state(false)
  let fault = $state('')

  async function load(more: boolean) {
    if (busy) return
    busy = true
    fault = ''
    try {
      const page = await fetchOwnerLog(connection.serverUrl, connection.token, more ? cursor : null)
      const next = ownerLogLines(page.events)
      lines = more ? [...lines, ...next] : next
      cursor = page.nextCursor
      loaded = true
    } catch (e) {
      fault = e instanceof OwnerLogError ? C.faults[e.fault] : C.faults.unreachable
    } finally {
      busy = false
    }
  }

  function when(ts: number): string {
    return new Date(ts * 1000).toLocaleString()
  }
</script>

<section class="owner-log">
  <div class="bar">
    <h3>{C.heading}</h3>
    <button onclick={() => load(false)} disabled={busy}>{busy ? C.loading : loaded ? C.refresh : C.show}</button>
  </div>
  <p class="lede">{C.lede}</p>
  {#if fault}<p class="fault" role="status">{fault}</p>{/if}
  {#if loaded && lines.length === 0}
    <p class="lede">{C.empty}</p>
  {:else if loaded}
    <ul>
      {#each lines as line (line.seq)}
        <li><span class="what">{line.what}</span><time>{when(line.ts)}</time></li>
      {/each}
    </ul>
    {#if cursor !== null}
      <button class="more" onclick={() => load(true)} disabled={busy}>{C.more}</button>
    {/if}
  {/if}
</section>

<style>
  .owner-log { display: flex; flex-direction: column; gap: var(--space-3); border-top: 1px solid var(--hairline); padding-top: var(--space-4); }
  .bar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
  .bar h3 { margin: 0; }
  .lede, .fault { margin: 0; color: var(--ink-soft); }
  .fault { color: var(--ink-text); }
  ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-2); }
  li { display: flex; align-items: baseline; gap: var(--space-3); padding: var(--space-2) var(--space-3); border: 1px solid var(--hairline); border-radius: var(--radius-sm); }
  .what { flex: 1; min-width: 0; }
  time { color: var(--text-subtle); font-size: 0.85rem; white-space: nowrap; font-variant-numeric: tabular-nums; }
  .more { align-self: flex-start; }
  button { transition: none; }
</style>
