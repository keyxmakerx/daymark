<script lang="ts">
  import type { PinnedTherapist } from './session'

  let {
    therapists,
    selectedId,
    onselect,
  }: {
    therapists: PinnedTherapist[]
    selectedId: string | null
    onselect: (id: string) => void
  } = $props()
</script>

{#if therapists.length === 0}
  <p class="none faint">
    No pinned therapists yet. Pin a therapist (verify their fingerprint out-of-band) before you
    can grant capabilities or share data — the console refuses to seal to an unpinned key.
  </p>
{:else}
  <div class="picker" role="group" aria-label="Select a pinned therapist">
    {#each therapists as t (t.id)}
      <button
        type="button"
        class:active={t.id === selectedId}
        aria-pressed={t.id === selectedId}
        onclick={() => onselect(t.id)}
      >
        <span class="name">{t.displayName}</span>
        <span class="sas">{t.fingerprintWords}</span>
      </button>
    {/each}
  </div>
{/if}

<style>
  .picker { display: flex; flex-wrap: wrap; gap: var(--space-2); }
  .picker button { display: flex; flex-direction: column; align-items: flex-start; gap: 0.15rem; text-align: left; padding: var(--space-2) var(--space-3); }
  .picker button.active { background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }
  .name { font-weight: 600; }
  .sas { font-family: var(--font-mono); font-size: 0.7rem; opacity: 0.85; }
  .none { margin: 0; }
</style>
