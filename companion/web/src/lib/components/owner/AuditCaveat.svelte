<script lang="ts">
  /*
   * Fixed, non-server-supplied banner (COMPANION_SECURITY.md §9, R12). Entries are hash-chained
   * so a stored entry's tampering/reordering is detectable, but that chain is server-computed,
   * not therapist-signed — it does NOT prove completeness. A hostile server could still simply
   * never append an event, or withhold the newest entries entirely.
   *
   * SUBSTRATE: ui/Callout, tone 'info'. Callout exists to be the ground the fixed honesty
   * banners sit on, and 'info' is documented as the interface explaining what it does not
   * know — which is exactly this claim. The copy below is passed through as children, so
   * Callout never touches a word of it.
   *
   * The banner previously drew --mood-3-wash with a --mood-3 dot: caution borrowed from the
   * middle of a person's mood ramp. The ramp is DATA only (app.css, invariant 1), and an
   * incompleteness caveat about a server-computed log is interface state, so it moves off it.
   * Not clay and not amber either — nothing here is wrong or degraded; the log is always
   * exactly this incomplete, and dressing a permanent property as an alarm would cry wolf.
   * The dot is gone because Callout's left rule carries the same "attached to the claim"
   * reading with a form signal that survives greyscale; the dot was decorative and aria-hidden.
   *
   * The <aside> stays so the banner keeps its named landmark for landmark navigation.
   */
  import { Callout } from '../ui'
</script>

<aside aria-label="Audit log caveat">
  <Callout tone="info">
    <p>
    <strong>Metadata only, and not provably complete.</strong> This log shows event types and
    timestamps — never message content. Entries are chained so a tampered or reordered entry is
    detectable, but a dishonest server could still withhold the newest entries entirely; there is
    no proof this list is exhaustive. IP address is not recorded unless the operator explicitly
    enabled it.
    </p>
  </Callout>
</aside>

<style>
  /* Callout owns the substrate. All that is left is zeroing the paragraph margin so the copy
     sits flush inside it — the prose lines above are byte-for-byte what they were. */
  p {
    margin: 0;
  }
</style>
