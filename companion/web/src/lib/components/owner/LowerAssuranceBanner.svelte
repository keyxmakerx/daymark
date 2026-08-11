<script lang="ts">
  /*
   * Fixed, non-server-supplied banner. The owner console handles the owner's private keys in the
   * browser to open sealed assignments and sign grants/shares — this is the LOWER-ASSURANCE path
   * (COMPANION_SECURITY.md §5 / COMPANION_THERAPIST.md §11). We do NOT claim zero-knowledge
   * against a malicious server here; keys are held in memory only and dropped on lock.
   *
   * SUBSTRATE: ui/Callout, tone 'warn'. This one IS a genuine warning — a degraded assurance
   * path, which is exactly what amber is reserved for — so unlike the non-diagnostic framing it
   * earns a severity hue. Callout passes the copy through as children untouched; the wording is
   * fixed and quoted in COMPANION_SECURITY.md.
   *
   * It previously drew --mood-3-wash with a --mood-3 dot, i.e. caution borrowed from the middle
   * of a person's mood ramp. The ramp is DATA only (app.css, invariant 1) and this is interface
   * state. Not clay: clay is the single alarm hue and nothing has failed here — the path is
   * weaker by design, not broken. The dot is gone; Callout's dashed left rule carries "warn" as
   * a FORM signal that survives greyscale, and adds a visually hidden "Warning:" prefix, so the
   * severity no longer rests on hue alone. The dot was decorative and aria-hidden.
   *
   * The <aside> stays so the banner keeps its named landmark for landmark navigation.
   */
  import { Callout } from '../ui'
</script>

<aside aria-label="Assurance notice">
  <Callout tone="warn">
    <p>
    <strong>Lower-assurance path.</strong> The owner console holds your private keys in this
    browser to open sealed items and sign grants. This is a convenience path — the page is served
    by the server it talks to, so a tampered page could misbehave. Keys stay in memory and are
    dropped when you lock. Verify the released image digest; your phone remains the trusted,
    secret-handling path.
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
