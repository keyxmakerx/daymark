<script lang="ts">
  /*
   * Fixed, non-server-supplied banner (COMPANION_SECURITY.md §9, R12). Entries are hash-chained
   * so a stored entry's tampering/reordering is detectable, but that chain is server-computed,
   * not therapist-signed — it does NOT prove completeness. A hostile server could still simply
   * never append an event, or withhold the newest entries entirely.
   */
</script>

<aside class="banner" aria-label="Audit log caveat">
  <span class="dot" aria-hidden="true"></span>
  <p>
    <strong>Metadata only, and not provably complete.</strong> This log shows event types and
    timestamps — never message content. Entries are chained so a tampered or reordered entry is
    detectable, but a dishonest server could still withhold the newest entries entirely; there is
    no proof this list is exhaustive. IP address is not recorded unless the operator explicitly
    enabled it.
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
