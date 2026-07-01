<script lang="ts">
  import type { Capability, CapabilityGrant, ApplyMode } from '../../assignments/types'
  import { describeCapability } from '../../assignments/describe'

  let {
    capability,
    grant,
    onchange,
  }: {
    capability: Capability
    grant: CapabilityGrant
    onchange: (granted: boolean, apply: ApplyMode) => void
  } = $props()

  const copy = $derived(describeCapability(capability))
  // suggest.setting is ALWAYS propose/accept — the auto option is hard-disabled.
  const autoDisabled = $derived(capability === 'suggest.setting')

  function toggleGranted(e: Event) {
    onchange((e.currentTarget as HTMLInputElement).checked, grant.apply)
  }
  function setMode(mode: ApplyMode) {
    if (mode === 'auto' && autoDisabled) return
    onchange(grant.granted, mode)
  }
</script>

<div class="row" class:on={grant.granted}>
  <div class="text">
    <div class="head">
      <span class="title">{copy.title}</span>
      <span class="capid">{capability}</span>
    </div>
    <p class="desc">{copy.desc}</p>
  </div>

  <div class="controls">
    <label class="switch">
      <input type="checkbox" checked={grant.granted} onchange={toggleGranted} aria-label="Grant {copy.title}" />
      <span class="switch-label">{grant.granted ? 'Granted' : 'Off'}</span>
    </label>

    <div class="segmented" role="group" aria-label="Apply mode for {copy.title}">
      <button
        type="button"
        class:active={grant.apply === 'propose'}
        aria-pressed={grant.apply === 'propose'}
        disabled={!grant.granted}
        onclick={() => setMode('propose')}
      >Propose</button>
      <button
        type="button"
        class:active={grant.apply === 'auto'}
        aria-pressed={grant.apply === 'auto'}
        disabled={!grant.granted || autoDisabled}
        title={autoDisabled ? 'Settings always require your acceptance — never automatic.' : ''}
        onclick={() => setMode('auto')}
      >Auto</button>
    </div>
  </div>
</div>

<style>
  .row {
    display: flex;
    gap: var(--space-4);
    align-items: flex-start;
    justify-content: space-between;
    padding: var(--space-3) 0;
    border-bottom: 1px solid var(--hairline);
  }
  .row.on { background: var(--mood-5-wash); border-radius: var(--radius-sm); padding-left: var(--space-3); padding-right: var(--space-3); }
  .text { flex: 1; min-width: 0; }
  .head { display: flex; align-items: baseline; gap: var(--space-2); flex-wrap: wrap; }
  .title { font-weight: 600; }
  .capid { font-family: var(--font-mono); font-size: 0.75rem; color: var(--ink-faint); }
  .desc { margin: var(--space-1) 0 0; color: var(--ink-soft); font-size: 0.85rem; }
  .controls { display: flex; flex-direction: column; gap: var(--space-2); align-items: flex-end; flex: 0 0 auto; }
  .switch { display: flex; align-items: center; gap: var(--space-2); font-size: 0.85rem; }
  .segmented { display: inline-flex; border: 1px solid var(--border-strong); border-radius: var(--radius-sm); overflow: hidden; }
  .segmented button { border: none; border-radius: 0; padding: var(--space-1) var(--space-3); font-size: 0.8rem; background: var(--paper-bg); }
  .segmented button.active { background: var(--ink-accent); color: var(--on-accent); }
  .segmented button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
