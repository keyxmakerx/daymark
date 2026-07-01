<script lang="ts">
  /*
   * Fixed, non-server-supplied banner. The owner console handles the owner's private keys in the
   * browser to open sealed assignments and sign grants/shares — this is the LOWER-ASSURANCE path
   * (COMPANION_SECURITY.md §5 / COMPANION_THERAPIST.md §11). We do NOT claim zero-knowledge
   * against a malicious server here; keys are held in memory only and dropped on lock.
   */
</script>

<aside class="banner" aria-label="Assurance notice">
  <span class="dot" aria-hidden="true"></span>
  <p>
    <strong>Lower-assurance path.</strong> The owner console holds your private keys in this
    browser to open sealed items and sign grants. This is a convenience path — the page is served
    by the server it talks to, so a tampered page could misbehave. Keys stay in memory and are
    dropped when you lock. Verify the released image digest; your phone remains the trusted,
    secret-handling path.
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
