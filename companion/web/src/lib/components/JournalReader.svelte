<script lang="ts">
  import type { BackupData } from '../backup'
  import { formatDate } from '../stats'

  let { data }: { data: BackupData } = $props()

  const entries = $derived([...(data.journal ?? [])].sort((a, b) => b.dateTime - a.dateTime))
</script>

<div class="card">
  <h2>Journal</h2>
  {#if entries.length === 0}
    <p class="faint">No journal entries in this backup.</p>
  {:else}
    <ol class="journal">
      {#each entries as j (j.id)}
        <li>
          <p class="meta muted">{formatDate(j.dateTime)}</p>
          {#if j.title}<h3>{j.title}</h3>{/if}
          <p class="body">{j.body}</p>
        </li>
      {/each}
    </ol>
  {/if}
</div>

<style>
  .journal { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-5); }
  .journal li { border-left: 3px solid var(--hairline); padding-left: var(--space-4); }
  .meta { margin: 0 0 var(--space-1); font-size: 0.85rem; }
  .body { margin: var(--space-1) 0 0; white-space: pre-wrap; }
</style>
