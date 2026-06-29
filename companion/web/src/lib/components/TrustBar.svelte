<script lang="ts">
  /*
   * The honest "Trust strip". In this Phase-0 viewer the guarantee is strong and
   * simple: nothing is uploaded — the file is read in your browser. We deliberately
   * do NOT claim runtime exfiltration protection (a tampered page could not be
   * trusted to police itself); we point at integrity verification instead. This
   * mirrors the posture in COMPANION_SECURITY.md / COMPANION_UX.md.
   */
  let { online }: { online: boolean } = $props()
</script>

<aside class="trust" aria-label="Privacy and trust">
  <span class="dot {online ? 'warn' : 'ok'}" aria-hidden="true"></span>
  <p>
    <strong>On-device only.</strong> This viewer reads your backup in the browser and
    makes no network requests. Your data never leaves this device. For a trustworthy
    offline copy, verify the build hash rather than relying on the page to police itself.
  </p>
</aside>

<style>
  .trust {
    display: flex;
    gap: var(--space-3);
    align-items: flex-start;
    background: var(--mood-5-wash);
    border: 1px solid var(--hairline);
    border-radius: var(--radius);
    padding: var(--space-3) var(--space-4);
    font-size: 0.9rem;
  }
  .trust p { margin: 0; color: var(--ink-soft); }
  .dot { width: 0.6rem; height: 0.6rem; border-radius: 999px; margin-top: 0.35rem; flex: 0 0 auto; }
  .dot.ok { background: var(--mood-5); }
  .dot.warn { background: var(--mood-3); }
</style>
