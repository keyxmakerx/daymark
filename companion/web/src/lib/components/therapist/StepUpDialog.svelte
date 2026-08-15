<script lang="ts">
  /*
   * Reusable step-up sign-off prompt for share.open / assignment publish / gameplan publish. This
   * is a CLIENT-SIDE confirmation ceremony bound to the live session (a fresh, explicit confirm
   * before any signing/publish). The server does not (in this build) issue per-action assertions,
   * so this is defense-in-depth against accidental publish, not a server-verified assertion. The
   * WebAuthn assertion path is out of scope (server returns 501).
   *
   * KEYBOARD. The dialog opens over the page but focus used to stay on the trigger behind the
   * scrim, so a keyboard or screen-reader user tabbed through the obscured page instead of the
   * confirm. Focus now moves to Cancel on open — the non-destructive choice, never the publish —
   * and Escape cancels. Both are part of "a fresh, explicit confirm": a ceremony you cannot reach
   * without a mouse is not a ceremony.
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

  let cancelEl = $state<HTMLButtonElement | null>(null)

  $effect(() => {
    if (open) cancelEl?.focus()
  })

  function onkeydown(e: KeyboardEvent) {
    if (open && e.key === 'Escape') {
      e.preventDefault()
      oncancel()
    }
  }
</script>

<svelte:window {onkeydown} />

{#if open}
  <div class="scrim" role="dialog" aria-modal="true" aria-label="Confirm action">
    <div class="card">
      <h3>Confirm: {action}</h3>
      <p class="faint">
        This will sign the item with your key and publish it. Only proceed if you intend to send it
        now. Your keys are used in memory only.
      </p>
      <div class="row">
        <button class="ghost" bind:this={cancelEl} onclick={oncancel}>Cancel</button>
        <button class="primary" onclick={onconfirm}>Sign &amp; publish</button>
      </div>
    </div>
  </div>
{/if}

<style>
  /*
   * THE SCRIM WAS A LIE DETECTOR FOR THIS FILE. It read `var(--scrim, rgba(0, 0, 0, 0.5))`, and
   * --scrim is defined nowhere in the codebase — so the hardcoded fallback was the live value,
   * an un-themeable black that no token could reach.
   *
   * It is now expressed as a FILTER rather than a colour, because there is no semantic token
   * that stays dark in both themes: every role in the contract flips with the palette, so any
   * `color-mix` of one would dim the page in daylight and flash it white at midnight — the worst
   * possible behaviour in this product at 2am. brightness() darkens whatever is behind it, in
   * either theme, with no colour literal anywhere. Where backdrop-filter is unavailable the
   * @supports block falls back to a chrome tint: this overlay is chrome (the machine asking you
   * to confirm), so chrome is the honest family for it.
   */
  .scrim {
    position: fixed;
    inset: 0;
    backdrop-filter: brightness(0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    padding: var(--space-4);
    z-index: 50;
  }

  @supports not (backdrop-filter: brightness(0.45)) {
    .scrim {
      background: color-mix(in srgb, var(--chrome-ink) 60%, transparent);
    }
  }

  /*
   * Deliberately NOT ui/Card. Card is the warm --paper-sheet the "cool chrome, warm content"
   * rule reserves for a person's own material; a confirm dialog is the machine asking a
   * question about itself, and giving it the content surface would blur the one distinction
   * the whole palette exists to make.
   */
  .card { background: var(--paper-bg); border: 1px solid var(--border-strong); border-radius: var(--radius); padding: var(--space-4); max-width: 26rem; display: flex; flex-direction: column; gap: var(--space-3); }
  .card h3 { margin: 0; }
  .card p { margin: 0; }
  .row { display: flex; justify-content: flex-end; gap: var(--space-2); }
  .primary { background: var(--ink-accent); color: var(--on-accent); border-color: var(--ink-accent); }
</style>
