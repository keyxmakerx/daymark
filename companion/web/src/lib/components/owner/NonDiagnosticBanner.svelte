<script lang="ts">
  /* Fixed non-diagnostic framing. Present on every assignment/share surface. Copy is constant. */
</script>

<aside class="banner" aria-label="Non-diagnostic notice">
  <p>
    <strong>Non-diagnostic.</strong> Self-checks are self-tracking tools, not medical
    assessments. Scores and bands are descriptive, not clinical thresholds. Anything a therapist
    assigns or shares here is guidance from your real clinician — never a diagnosis.
  </p>
</aside>

<style>
  /*
   * WHY CHROME AND NOT A SEVERITY HUE. This banner is INFORMATION, not a warning: it states
   * what the instrument is, and it is present on every assignment and share surface. So it
   * takes the quiet chrome ground — the machine describing itself — and carries no coloured
   * rail. It must read as "this is part of the instrument", never as "something is wrong".
   *
   * It was --mood-5-wash, which painted a fixed, product-supplied caveat in the top step of a
   * person's mood ramp. The ramp encodes a person's reported experience and nothing else
   * (app.css, invariant 1); this is interface, and STATE may not wear DATA's colours.
   *
   * NOT AMBER, on purpose. Amber is warn severity. Spending it on boilerplate that appears on
   * every surface would wear it out until nothing meant "warning" — which is the reason the
   * lower-assurance banner, a genuine warning, can still afford it. And nothing green-adjacent:
   * there is no success hue in this system (app.css, invariant 2).
   *
   * WHY NOT ui/Callout. Considered and rejected as a poor fit rather than adopted at a cost:
   * all three of its tones paint a coloured left rail (indigo / amber / clay) and it has no
   * quiet or chrome tone. A rail is precisely what this banner is required not to have.
   */
  .banner {
    background: var(--chrome);
    border: 1px solid var(--chrome-hair);
    border-radius: var(--radius);
    padding: var(--space-3) var(--space-4);
    font-size: 0.85rem;
  }
  .banner p { margin: 0; color: var(--chrome-ink); }
</style>
