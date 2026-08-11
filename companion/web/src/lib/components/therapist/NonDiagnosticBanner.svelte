<script lang="ts">
  /* Fixed non-diagnostic framing. Present on every writer + shared-data surface. Copy is constant. */
</script>

<aside class="banner" aria-label="Non-diagnostic notice">
  <p>
    <strong>Non-diagnostic.</strong> Self-checks are self-tracking tools, not medical assessments.
    Scores and bands are descriptive, not clinical thresholds. Anything you assign, plan, or read
    here is guidance between you and this person — never a diagnosis.
  </p>
</aside>

<style>
  /*
   * WHY CHROME AND NOT A COLOURED BANNER. This strip used to sit on --mood-5-wash — the top
   * of the DATA ramp, the green a person's best-reported day is painted in. Nothing here is
   * that person's data: it is the instrument stating what it is and is not, which is the
   * definition of the chrome layer ("the machine talking about itself"). Borrowing the ramp
   * made the legend lie, and picked the one step a reader has learned to read as "good news"
   * for a sentence that is neither good nor bad news.
   *
   * WHY NOT AMBER, AND WHY NO COLOURED LEFT RAIL. Non-diagnostic framing is INFORMATION, not
   * a warning — nothing has gone wrong, this is simply what the tool is. Amber is warn
   * severity only (LowerAssuranceBanner is the genuine warning on these screens), and a rail
   * would make a permanent, every-screen caveat read as an alert until it read as nothing at
   * all. Quiet chrome ground, hairline, no rail: part of the instrument, not a complaint
   * about it. This is also why ui/Callout is deliberately NOT adopted here — all three of its
   * tones carry a coloured edge.
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
