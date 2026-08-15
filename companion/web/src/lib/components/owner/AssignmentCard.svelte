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
  /*
   * TOKENS. Every colour on this card was a mood-ramp reference and every one of them was
   * STATE, not DATA: a signature verdict, a rejection list, a destructive button. Nothing here
   * is a person's reported experience, so nothing here may wear the ramp (app.css, invariant 1).
   *
   * The four verdicts are told apart by three things before hue: their written label (always
   * present, never abbreviated), the fill FORM (solid vs. wash), and — for a failure — the
   * card's own outline turning clay. Colour is never carrying this alone.
   *
   * WHY THE BADGE IS NOT ui/Chip. VERIFIED must be solid ink: "verified" is a confirmation, and
   * confirmations in this product are emphatic ink, never green — a green tick here would be a
   * claim the product is not in a position to make. Chip offers neutral / accent / warn /
   * critical and deliberately has no solid-ink tone, so adopting it would mean the three failure
   * verdicts became Chips and VERIFIED stayed local, giving four sibling badges in one row two
   * different sets of box metrics. Kept local and retokened instead.
   */
  .item { display: flex; flex-direction: column; gap: var(--space-2); }

  /* A failed verdict is a needs-a-human state: the single alarm hue, not the bottom of the
     mood ramp. Redundant with the badge and the reason list below it. */
  .item.bad { border-color: var(--clay); }

  .head { display: flex; align-items: baseline; gap: var(--space-2); flex-wrap: wrap; }
  .badge { font-size: 0.7rem; font-weight: 600; padding: 0.1rem var(--space-2); border-radius: 999px; text-transform: uppercase; letter-spacing: 0.03em; border: 1px solid transparent; }

  /* Solid ink, emphatic and claim-free. Was --mood-5 — the "rad" step of a person's mood ramp
     used to mean "this signature checked out", which is both a category error and the closest
     thing to a green tick this palette can produce. */
  .badge.verified { background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }

  /* Refused, untrusted, unopenable — alarm. */
  .badge.rejected, .badge.untrusted_key, .badge.open_failed { background: var(--clay-wash); color: var(--clay); border-color: var(--clay); }

  .from { font-size: 0.85rem; color: var(--ink-soft); }
  .when { font-size: 0.8rem; }
  .preview { margin: 0; font-weight: 500; }
  .note { margin: 0; color: var(--ink-soft); font-style: italic; }
  .requires { margin: 0; font-size: 0.8rem; color: var(--ink-accent); }

  /* The reasons a bundle was refused. */
  .errors { margin: 0; padding-left: var(--space-4); color: var(--clay); font-size: 0.8rem; }

  .fp { margin: 0; font-size: 0.75rem; }
  .fp code { font-family: var(--font-mono); }
  .actions { display: flex; gap: var(--space-2); }

  /* Decline is destructive and irreversible from here — clay, the one hue that means that. */
  .danger { border-color: var(--clay); color: var(--clay); }

  .decided { text-transform: capitalize; color: var(--ink-soft); font-size: 0.85rem; }
</style>
