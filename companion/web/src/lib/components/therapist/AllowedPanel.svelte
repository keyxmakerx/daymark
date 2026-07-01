<script lang="ts">
  /*
   * "What you've allowed" — renders ALL_CAPABILITIES, each shown as granted (with apply mode) or
   * greyed-out/disabled. Read-only: the therapist cannot change a grant. Shows the pinned owner +
   * therapist fingerprints and the grant provenance so the therapist can confirm the owner key.
   */
  import { ALL_CAPABILITIES, type Grant } from '../../assignments/types'
  import { describeCapability } from '../../assignments/describe'
  import { hasCapability, applyModeOf } from '../../therapist/grant'

  let {
    grant,
    ownerSigningFp,
    therapistFp,
  }: { grant: Grant; ownerSigningFp: string; therapistFp: string } = $props()
</script>

<section class="allowed">
  <h3>What you've allowed</h3>
  <p class="faint prov">
    Grant verified against the pinned owner key <code>{ownerSigningFp}</code>. Your pinned identity:
    <code>{therapistFp}</code>.
  </p>

  <ul class="caps" role="list">
    {#each ALL_CAPABILITIES as cap (cap)}
      {@const granted = hasCapability(grant, cap)}
      {@const mode = applyModeOf(grant, cap)}
      {@const copy = describeCapability(cap)}
      <li class="cap" class:granted class:denied={!granted}>
        <div class="head">
          <span class="title">{copy.title}</span>
          {#if granted}
            <span class="pill on">Granted · {mode === 'auto' ? 'auto-applies' : 'proposes'}</span>
          {:else}
            <span class="pill off">Not granted</span>
          {/if}
        </div>
        <p class="desc faint">{copy.desc}</p>
      </li>
    {/each}
  </ul>
</section>

<style>
  .allowed { display: flex; flex-direction: column; gap: var(--space-3); }
  .allowed h3 { margin: 0; }
  .prov { margin: 0; font-size: 0.85rem; }
  .caps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--space-2); }
  .cap { border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .cap.denied { opacity: 0.6; }
  .cap.granted { border-color: var(--mood-5); background: var(--mood-5-wash); }
  .head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
  .title { font-weight: 600; }
  .pill { font-size: 0.75rem; padding: 0.1rem 0.5rem; border-radius: 999px; border: 1px solid var(--border-strong); }
  .pill.on { background: var(--mood-5); color: var(--on-accent); border-color: var(--mood-5); }
  .pill.off { color: var(--ink-soft); }
  .desc { margin: var(--space-1) 0 0; font-size: 0.85rem; }
  code { font-family: var(--font-mono); background: var(--paper-bg); padding: 0 0.25rem; border-radius: 4px; font-size: 0.8em; }
</style>
