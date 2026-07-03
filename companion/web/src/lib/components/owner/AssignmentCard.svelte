<script lang="ts">
  import type { InboxItem } from '../../assignments/inbox'
  import { formatDate } from '../../stats'

  let {
    item,
    onaccept,
    ondecline,
    onsnooze,
  }: {
    item: InboxItem
    onaccept: () => void
    ondecline: () => void
    onsnooze: () => void
  } = $props()

  const verdictLabel: Record<InboxItem['verdict'], string> = {
    VERIFIED: 'Verified',
    REJECTED: 'Rejected',
    UNTRUSTED_KEY: 'Untrusted key',
    OPEN_FAILED: 'Could not open',
  }
  const applyable = $derived(item.verdict === 'VERIFIED')
  const authorFp = $derived(item.assignment?.authorFingerprint ?? '')
</script>

<article class="card item" class:bad={!applyable}>
  <header class="head">
    <span class="badge {item.verdict.toLowerCase()}">{verdictLabel[item.verdict]}</span>
    <span class="from">from {item.therapistName}</span>
    {#if item.assignment}
      <span class="when faint">{formatDate(item.assignment.issuedAt)}</span>
    {/if}
  </header>

  <p class="preview">{item.preview}</p>

  {#if item.assignment?.note}
    <p class="note">“{item.assignment.note}”</p>
  {/if}

  {#if item.requiresAccept && applyable}
    <p class="requires">Requires your acceptance{item.assignment?.capability === 'suggest.setting' ? ' (settings are never automatic)' : ''}.</p>
  {/if}

  {#if item.errors.length > 0}
    <ul class="errors" aria-label="Why this was rejected">
      {#each item.errors as e (e)}<li>{e}</li>{/each}
    </ul>
  {/if}

  {#if authorFp}
    <p class="fp faint">author: <code>{authorFp}</code> · {item.raw.lineage} v{item.raw.version}</p>
  {/if}

  <footer class="actions">
    {#if item.decision}
      <span class="decided">{item.decision}</span>
    {:else}
      <button class="primary" onclick={onaccept} disabled={!applyable} title={applyable ? '' : 'Cannot accept an unverified or rejected item.'}>Accept</button>
      <button onclick={onsnooze} disabled={!applyable}>Snooze</button>
      <button class="danger" onclick={ondecline}>Decline</button>
    {/if}
  </footer>
</article>

<style>
  .item { display: flex; flex-direction: column; gap: var(--space-2); }
  .item.bad { border-color: var(--mood-1); }
  .head { display: flex; align-items: baseline; gap: var(--space-2); flex-wrap: wrap; }
  .badge { font-size: 0.7rem; font-weight: 600; padding: 0.1rem var(--space-2); border-radius: 999px; text-transform: uppercase; letter-spacing: 0.03em; }
  .badge.verified { background: var(--mood-5-wash); color: var(--mood-5); border: 1px solid var(--mood-5); }
  .badge.rejected, .badge.untrusted_key, .badge.open_failed { background: var(--mood-1-wash); color: var(--mood-1); border: 1px solid var(--mood-1); }
  .from { font-size: 0.85rem; color: var(--ink-soft); }
  .when { font-size: 0.8rem; }
  .preview { margin: 0; font-weight: 500; }
  .note { margin: 0; color: var(--ink-soft); font-style: italic; }
  .requires { margin: 0; font-size: 0.8rem; color: var(--ink-accent); }
  .errors { margin: 0; padding-left: var(--space-4); color: var(--mood-1); font-size: 0.8rem; }
  .fp { margin: 0; font-size: 0.75rem; }
  .fp code { font-family: var(--font-mono); }
  .actions { display: flex; gap: var(--space-2); }
  .danger { border-color: var(--mood-1); color: var(--mood-1); }
  .decided { text-transform: capitalize; color: var(--ink-soft); font-size: 0.85rem; }
</style>
