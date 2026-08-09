<script lang="ts">
  /*
   * The honest "Trust strip".
   *
   * WHAT THIS USED TO SAY, AND WHY IT WAS WRONG. Until 2026-08-09 this component asserted
   * "This viewer reads your backup in the browser and makes no network requests. Your data
   * never leaves this device." — unconditionally, on every surface, because App.svelte
   * renders the strip outside the tab switch. That sentence was true on the "Open a backup
   * file" tab and false on three others: "Connect to sync" fetches from your server,
   * "Owner console" talks to it, and "Recover access" POSTs the email address you type into
   * the form directly beneath the claim. Worse, App.svelte lands you on "Recover access" by
   * default when you arrive from an emailed recovery link — so the user most likely to read
   * it was the one it was most wrong for.
   *
   * It also painted itself GREEN whenever navigator.onLine was false. Being offline at this
   * instant says nothing about whether the page would call out; COMPANION_UX.md 10.1 rules
   * that out explicitly ("the Phase-0 offline viewer is not painted --trust-locked (green)
   * ... a served page handling the master passphrase cannot provide a zero-knowledge
   * guarantee — so green would overclaim"). That reviewer must-fix was recorded as applied
   * and never reached the code.
   *
   * So: no green, and no self-asserted "makes no network requests" — a tampered page could
   * not be trusted to police itself, which is why the copy points at integrity verification
   * instead. The strip now states the posture of the surface you are actually on.
   */
  // Kept inline rather than exported: a type exported from a Svelte 5 instance script is not
  // importable by consumers (that needs `<script module>`), and one shared union is not worth
  // a module for. App.svelte declares the same three values where it derives them.
  //   local   — nothing leaves: open-a-file, self-checks, tool builder
  //   sync    — ciphertext leaves and returns; the passphrase does not
  //   account — identifiers, and on recovery an email address, leave
  let { surface = 'local' }: { surface?: 'local' | 'sync' | 'account' } = $props()
</script>

<aside class="trust" aria-label="Privacy and trust">
  <span class="dot" aria-hidden="true"></span>
  <p>
    {#if surface === 'local'}
      <strong>Meant to run offline.</strong> This tab reads your backup in the browser and
      sends nothing — but a page cannot prove that about itself. Verify this build's integrity
      before you unlock an encrypted backup.
    {:else if surface === 'sync'}
      <strong>This tab talks to your server.</strong> Your passphrase and the decrypted
      entries stay in this browser; what crosses the network is ciphertext your server cannot
      read. It can still see that you synced, and when.
    {:else}
      <strong>This tab sends data to your server.</strong> Account actions here transmit
      identifiers, and account recovery transmits the email address you type. Your passphrase
      and your entries are not involved and never leave this browser.
    {/if}
  </p>
</aside>

<style>
  .trust {
    display: flex;
    gap: var(--space-3);
    align-items: flex-start;
    /* Caution, never the green wash. Per COMPANION_UX.md 10.1 there is no green state for
       any of these surfaces: served portal JS is lower-assurance regardless of what the
       network is doing this second. */
    background: var(--mood-3-wash);
    border: 1px solid var(--hairline);
    border-radius: var(--radius);
    padding: var(--space-3) var(--space-4);
    font-size: 0.9rem;
  }
  .trust p { margin: 0; color: var(--ink-soft); }
  .dot {
    width: 0.6rem;
    height: 0.6rem;
    border-radius: 999px;
    margin-top: 0.35rem;
    flex: 0 0 auto;
    background: var(--mood-3);
  }
</style>
