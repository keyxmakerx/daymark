<script lang="ts">
  /*
   * Fixed, non-server-supplied assurance banner for the therapist portal. The TOTP path is the
   * HONESTLY WEAKER custody path (COMPANION_SECURITY.md §5.2/§11): the reading key is unwrapped in
   * this browser under a passphrase, and the page is served by the server it talks to — so a
   * tampered page could capture the passphrase or keys. We do NOT claim CSP/SRI make this
   * zero-knowledge. Keys stay in memory only and are wiped on logout/idle.
   */
</script>

<aside class="banner" aria-label="Assurance notice">
  <span class="dot" aria-hidden="true"></span>
  <p>
    <strong>Lower-assurance path (TOTP).</strong> Your reading key is unwrapped in this browser under
    a passphrase. Because the page is served by the server it talks to, a tampered page could capture
    your passphrase or keys — this is a convenience path, not a zero-knowledge guarantee. Keys are held
    in memory only and wiped when you log out or go idle. Verify the released image digest; a hardware
    passkey (WebAuthn) is the stronger path when available.
  </p>
</aside>

<style>
  .banner {
    display: flex;
    gap: var(--space-3);
    align-items: flex-start;
    background: var(--mood-3-wash);
    border: 1px solid var(--hairline);
    border-radius: var(--radius);
    padding: var(--space-3) var(--space-4);
    font-size: 0.9rem;
  }
  .banner p { margin: 0; color: var(--ink-soft); }
  .dot { width: 0.6rem; height: 0.6rem; border-radius: 999px; margin-top: 0.35rem; flex: 0 0 auto; background: var(--mood-3); }
</style>
