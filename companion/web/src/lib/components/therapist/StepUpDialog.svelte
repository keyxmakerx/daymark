<script lang="ts">
  /*
   * Reusable step-up sign-off prompt for share.open / assignment publish / gameplan publish. This
   * is a CLIENT-SIDE confirmation ceremony bound to the live session (a fresh, explicit confirm
   * before any signing/publish). The server does not (in this build) issue per-action assertions,
   * so this is defense-in-depth against accidental publish, not a server-verified assertion. The
   * WebAuthn assertion path is out of scope (server returns 501).
   */
  let {
    open = false,
    action,
    onconfirm,
    oncancel,
  }: {
    open?: boolean
    action: string
    onconfirm: () => void
    oncancel: () => void
  } = $props()
</script>

{#if open}
  <div class="scrim" role="dialog" aria-modal="true" aria-label="Confirm action">
    <div class="card">
      <h3>Confirm: {action}</h3>
      <p class="faint">
        This will sign the item with your key and publish it. Only proceed if you intend to send it
        now. Your keys are used in memory only.
      </p>
      <div class="row">
        <button class="ghost" onclick={oncancel}>Cancel</button>
        <button class="primary" onclick={onconfirm}>Sign &amp; publish</button>
      </div>
    </div>
  </div>
{/if}

<style>
  .scrim { position: fixed; inset: 0; background: var(--scrim, rgba(0, 0, 0, 0.5)); display: flex; align-items: center; justify-content: center; padding: var(--space-4); z-index: 50; }
  .card { background: var(--paper-bg); border: 1px solid var(--border-strong); border-radius: var(--radius); padding: var(--space-4); max-width: 26rem; display: flex; flex-direction: column; gap: var(--space-3); }
  .card h3 { margin: 0; }
  .card p { margin: 0; }
  .row { display: flex; justify-content: flex-end; gap: var(--space-2); }
  .primary { background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }
</style>
