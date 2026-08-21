<script lang="ts">
  /*
   * WHERE THE OWNER'S VIEWER SENDS SOMEBODY WHO SAID A CLINIC RUNS THIS MACHINE.
   *
   * WHY THERE IS A PANEL HERE AT ALL, RATHER THAN A JUMP. Administering a practice is a different
   * job from reading your own journal, and this bundle already splits surfaces on that line: the
   * clinician's portal and the server console are separate documents. The practice console is the
   * fourth, at practice.html. So the honest thing for THIS page to do with the practice shape is
   * to say what the server underneath has, point at the page where the administration happens,
   * and hand over the two facts about the model a person standing one up is least likely to know.
   *
   * The two dishonest alternatives were both available. Hiding the shape leaves an operator who
   * genuinely runs a clinic with no way to say what this machine is, and quietly turns a
   * three-answer question into a two-answer one. Rendering a roster with no rows — or a member
   * count of zero, or a "no activity yet" line — is indistinguishable from a console that failed
   * to load, and the person trying to work out whether this deployment works cannot tell which
   * they are looking at. That is the failure mode the whole product is written against.
   *
   * NOTHING ON THIS PANEL IS DATA, and it says that in those words. Not because it is careful
   * with what it fetched, but because it fetches nothing: no client, no token, no request. A
   * fabricated name reads as a real name and a fabricated zero reads as a real zero.
   *
   * AND IT MAKES NO CLAIM ABOUT THE CONSOLE'S COMPLETENESS. Where that page is, is a fact about
   * this build. What it can do is a claim about somebody else's screen, which that screen states
   * for itself — and a second-hand version of it here would go stale without anyone noticing.
   */
  import { Callout, Card, Chip } from '../ui'
  import {
    LABELS,
    PRACTICE_CONSOLE_ELSEWHERE,
    PRACTICE_MISSING,
    PRACTICE_OPEN_QUESTION,
    PRACTICE_ROLE_NOTE,
    PRACTICE_SERVER_HAS,
    PRACTICE_SERVER_INTRO,
  } from '../../setup/shape'
</script>

<Card title={LABELS.practiceTitle}>
  {#snippet header()}
    <!-- Warn, not neutral, and the word is the plain one: this panel is a placeholder, the chip
         is the first thing the eye reaches on the card, and it should say so before the prose
         does. -->
    <Chip tone="warn">{LABELS.placeholder}</Chip>
  {/snippet}

  <!--
    The placeholder statement leads. It is not a footnote to a screen that otherwise looks like a
    console — there is no console above it to footnote.
  -->
  <Callout tone="warn" title={LABELS.practiceWhatIsMissing}>
    <p class="para">{PRACTICE_MISSING}</p>
  </Callout>

  <!--
    The pointer comes before the inventory: somebody who chose this shape is trying to get
    somewhere, and the list of what the server has is context for that, not a substitute.
  -->
  <section class="block">
    <h3>{LABELS.practiceWhereItHappens}</h3>
    <p class="para">{PRACTICE_CONSOLE_ELSEWHERE}</p>
    <p class="para">
      <!-- Relative and sibling-scoped, the same convention lib/onboarding/audience.ts uses for
           the therapist portal and the server console: all four pages ship from one bundle and
           may be served under a base path, so `./practice.html` resolves wherever this page is. -->
      <a class="go" href={LABELS.practiceConsoleHref}>{LABELS.openPractice}</a>
    </p>
  </section>

  <section class="block">
    <h3>{LABELS.practiceWhatExists}</h3>
    <p class="para">{PRACTICE_SERVER_INTRO}</p>
    <!--
      A list of OPERATIONS the server implements, which is a fact about this build. It is not a
      list of anything that happened, and there is deliberately no number beside any line.
    -->
    <ul class="ops">
      {#each PRACTICE_SERVER_HAS as op (op)}
        <li>{op}</li>
      {/each}
    </ul>
  </section>

  <section class="block">
    <h3>{LABELS.practiceOpenQuestion}</h3>
    <p class="para">{PRACTICE_OPEN_QUESTION}</p>
  </section>

  {#snippet footer()}
    <!-- The rule that makes the whole clinical layer coherent, on the surface where somebody is
         deciding whether to run one. -->
    {PRACTICE_ROLE_NOTE}
  {/snippet}
</Card>

<style>
  .block {
    margin-top: var(--space-5);
    min-width: 0;
  }

  h3 {
    margin: 0 0 var(--space-2);
    font-family: var(--font-mono);
    font-size: 0.7rem;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
  }

  .para {
    margin: 0;
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.6;
    max-width: 44rem;
  }

  /* The one link off this page. Same treatment Orientation gives its cross-surface links, so
     "this goes to another page in this bundle" looks the same wherever it appears. */
  .go {
    color: var(--link);
    font-size: 0.9rem;
  }

  /* A plain list, deliberately without counts, badges or status marks beside the rows: every one
     of those would read as a measurement of a deployment nothing here has looked at. */
  .ops {
    margin: var(--space-2) 0 0;
    padding-left: var(--space-5);
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.7;
    max-width: 44rem;
  }
</style>
