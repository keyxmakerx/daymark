<script lang="ts">
  /*
   * "What you've allowed" — renders ALL_CAPABILITIES, each shown as granted (with apply mode) or
   * greyed-out/disabled. Read-only: the therapist cannot change a grant. Shows the pinned owner +
   * therapist fingerprints and the grant provenance so the therapist can confirm the owner key.
   */
  import { ALL_CAPABILITIES, type Grant } from '../../assignments/types'
  import { describeCapability } from '../../assignments/describe'
  import { hasCapability, applyModeOf } from '../../therapist/grant'
  import { Chip } from '../ui'

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
      <!-- `class:denied={!granted}` used to ride along here for `.cap.denied { opacity: 0.6 }`.
           That rule is gone (see the style block), and nothing — here or in app.css — styles
           .denied any more, so the directive was inert markup implying a treatment that no
           longer exists. `class:granted` stays: it still selects a live rule. -->
      <li class="cap" class:granted>
        <div class="head">
          <span class="title">{copy.title}</span>
          {#if granted}
            <Chip tone="accent">Granted · {mode === 'auto' ? 'auto-applies' : 'proposes'}</Chip>
          {:else}
            <Chip>Not granted</Chip>
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

  /*
   * WHY INDIGO AND NOT THE GREEN END OF THE MOOD RAMP. A granted row used to be painted
   * --mood-5 / --mood-5-wash. That is STATE, not data: it describes what the owner has
   * permitted this therapist to do, which is a fact about the interface's arrangement and
   * has nothing to do with anyone's reported experience. It was also, in effect, the green
   * tick this system forbids — "Granted" is not a reassurance that anything is going well.
   * Indigo is the structural accent: it marks how the interface is arranged, which is
   * exactly what a capability grant is.
   *
   * The tint is never the only signal. Every row states "Granted · auto-applies",
   * "Granted · proposes" or "Not granted" in words, on a Chip whose tone follows the same
   * split. The former `opacity: 0.6` on denied rows is gone: it dimmed real prose below a
   * readable contrast ratio to repeat information the label already carried.
   */
  .cap.granted { border-color: var(--indigo); background: var(--indigo-wash); }
  .head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
  .title { font-weight: 600; }
  .desc { margin: var(--space-1) 0 0; font-size: 0.85rem; }
  code { font-family: var(--font-mono); background: var(--paper-bg); padding: 0 0.25rem; border-radius: 4px; font-size: 0.8em; }
</style>
