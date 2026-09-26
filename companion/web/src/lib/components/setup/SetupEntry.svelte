<script lang="ts">
  /*
   * THE FIRST-RUN ENTRY. Explain what this machine is, then ask what it is for.
   *
   * WHAT IT REPLACES. Nothing — that is the point. index.html opened straight onto the
   * six-destination menu, and no screen anywhere let a person say what this deployment was for.
   * The three shapes (docs/COMPANION_ARCHITECTURE.md §2) are one product in three shapes, not
   * three sizes of it; Practice inverts the arrangement the other two exist to offer. A question
   * that is never asked gets answered by whichever screen someone happens to click first.
   *
   * SEEN ONCE, SO IT IS ALLOWED TO EXPLAIN ITSELF. This is the one screen in the product with a
   * licence to spend words, and it is still held to a budget: under seventy of them before the
   * choices, asserted in shape.test.ts rather than left to judgement. Everything past the choices
   * — what the browser remembers, what configuration would have said — is folded away, because
   * the reader's question is "which of these three am I" and nothing else on this screen helps
   * answer it.
   *
   * A RETURNING PERSON NEVER SEES THIS. App.svelte renders it only while `resolveSetup` says
   * `ask`; a stored or configured answer routes straight to the surface for that shape. The
   * distinction between the two states is the whole design of this slice.
   *
   * THIN BY CONSTRUCTION. Every sentence comes from lib/setup/shape.ts, headings and button
   * labels included. That is not tidiness: shape.test.ts walks that module's strings and asserts
   * the register over all of them — no cheer, no claim of health, no figure, no score. A heading
   * authored in this file would be the one phrase on the screen outside every one of those
   * checks, which is exactly how a planted "All good — you are all set" once passed the
   * orientation's entire suite by living in markup.
   *
   * NO COMPLETION, NO TICK, NO PROGRESS. Answering this question routes a page. It does not
   * verify a deployment, confirm a backup, or establish that anything works, and a screen that
   * celebrated the answer would be claiming otherwise (app.css invariant 2).
   *
   * WHAT IT DOES NOT DO: no fetch, no storage, no timers. App.svelte owns the configuration probe
   * and the localStorage write, and hands the resolved decision down. This file renders.
   */
  import { Callout, Card, Chip } from '../ui'
  import {
    ASK_REASON_NOTE,
    CHOICE_HEADING,
    CHOICE_IS_REVERSIBLE,
    CONFIG_READING,
    LABELS,
    NO_SHAPE_PUBLISHED,
    SETUP_LEDE,
    SETUP_LIMITS,
    SETUP_TITLE,
    SHAPES,
    STORAGE_REFUSED,
    WHAT_IS_REMEMBERED,
    configuredUnrecognised,
    type ConfigState,
    type SetupDecision,
    type ShapeId,
  } from '../../setup/shape'

  let {
    /** What `resolveSetup` decided. This component renders only `reading` and `ask`. */
    decision,
    /** What GET /v1/config said, so the screen can state why it is asking at all. */
    config,
    /** The answer. App.svelte persists it and re-resolves. */
    onchoose,
    /**
     * Set when the browser refused to keep the answer. The choice still stands for this visit —
     * see STORAGE_REFUSED — so this is a statement, never a blocker.
     */
    storageRefused = false,
  }: {
    decision: SetupDecision
    config: ConfigState
    onchoose: (id: ShapeId) => void
    storageRefused?: boolean
  } = $props()

  /*
   * State to tone, and there is no reassuring one. A shape that is wired end to end gets the
   * NEUTRAL chip rather than a warmer one, because "this part is built" is the resting condition
   * and not an achievement; only the gap gets a tone of its own.
   */
  const BUILD_TONE = { built: 'neutral', 'separate-page': 'warn' } as const
  const BUILD_WORD = { built: LABELS.builtToday, 'separate-page': LABELS.separatePage } as const

  const askNote = $derived(decision.state === 'ask' ? ASK_REASON_NOTE[decision.reason] : null)

  /*
   * Prefix for the ids the three cards hand to aria-labelledby and aria-describedby. Generated
   * rather than typed, the way Callout.svelte does it, so the ids stay unique even if this
   * screen is ever mounted twice on one page.
   */
  const uid = $props.id()
</script>

<!--
  AN h2, NOT PageHeader, for the reason Orientation.svelte states: PageHeader always emits an
  <h1>, and App.svelte already renders `<h1>Daymark Companion</h1>` in its topbar with this
  mounted beneath it. Two top-level headings one element apart make the outline ambiguous to
  navigate by exactly the landmark meant to orient someone.
-->
<section class="setup" aria-labelledby="setup-heading">
  <h2 id="setup-heading">{SETUP_TITLE}</h2>

  <p class="lede">{SETUP_LEDE}</p>
  <!--
    The limits are body text at the same weight as the lede, not small print under it. They are
    the other half of the same explanation: what arrives here, and what would happen to it. A
    smaller face would say they were optional reading, and they are the part somebody will wish
    they had read.
  -->
  <p class="limits">{SETUP_LIMITS}</p>

  {#if decision.state === 'reading'}
    <!--
      The gap between load and the configuration answer. Labelled rather than left blank: a page
      that shows a question and then rearranges itself under the reader when a probe lands is
      worse than a moment of stated waiting. App.svelte bounds it, so this state cannot persist.
    -->
    <Callout tone="info" title={LABELS.reading}>
      <p class="para">{CONFIG_READING}</p>
    </Callout>
  {:else if decision.state === 'ask'}
    {#if askNote}
      <!-- Only for the two reasons that are not "you have not been here before". -->
      <Callout tone="info" title={LABELS.whyAskingAgain}>
        <p class="para">{askNote}</p>
      </Callout>
    {/if}

    {#if config.kind === 'unrecognised'}
      <!--
        Somebody set the value and it is not one of the three. Warn rather than info: a question
        being asked that an operator believes they already answered is a real fault in the
        deployment, and the value is quoted back so the typo is visible.
      -->
      <Callout tone="warn" title={LABELS.configuredBadValue}>
        <p class="para">{configuredUnrecognised(config.value)}</p>
      </Callout>
    {/if}

    <section class="choices" aria-labelledby="choice-heading">
      <h3 id="choice-heading">{CHOICE_HEADING}</h3>
      <p class="reversible">{CHOICE_IS_REVERSIBLE}</p>

      <ul class="shapes">
        {#each SHAPES as shape (shape.id)}
          <!--
            The whole card is the target, as in Orientation's route list: a small "choose" button
            beside a block of text makes the text look like reading and the button look like the
            decision, when the text IS the decision.

            HOW THE CARD IS NAMED. This was one <button> wrapping every sentence, so its accessible
            name was the entire card — some eighty words read out before a screen reader user
            could tell one option from the next, and the same eighty words again on the second
            card. The name is now the card's heading, via aria-labelledby, and everything else
            hangs off aria-describedby: a description is announced after the name and can be cut
            short, a name cannot. The heading is a real <h4> under the question's <h3>, so heading
            navigation lands on each option by name.

            WHY THE BUTTON IS AN OVERLAY. A heading is not allowed inside a <button> (phrasing
            content only, and a button's descendants are presentational to assistive technology
            regardless), so the card is the <li> and the control is a transparent button laid over
            it. Same border, same hover, same focus ring, same click target: nothing visible
            changed, only what is announced.
          -->
          <li class="shape">
            <div class="shape-head">
              <h4 class="shape-label" id="{uid}-{shape.id}-name">{shape.label}</h4>
              <Chip tone={BUILD_TONE[shape.buildState]}>{BUILD_WORD[shape.buildState]}</Chip>
            </div>
            <div class="shape-about" id="{uid}-{shape.id}-about">
              <p class="shape-summary">{shape.summary}</p>
              <p class="shape-arrangement">{shape.arrangement}</p>
              <!--
                The ranking is the sentence that stops someone standing up a clinic server for
                themselves, so it is emphasised rather than folded in with the rest.
              -->
              <p class="shape-ranking">{shape.ranking}</p>
              <!--
                A fact with its own expiry, on its own line: for Practice, that no real patient's
                data belongs on a practice server yet (#333). Plain ink, no tone — information.
              -->
              {#if shape.holdNote}<p class="shape-hold">{shape.holdNote}</p>{/if}
              <!--
                And the honesty line: what is actually built for this shape, at the point of
                choosing it rather than after arriving somewhere empty.
              -->
              <p class="shape-built">{shape.buildNote}</p>
            </div>
            <button
              class="choose"
              type="button"
              aria-labelledby="{uid}-{shape.id}-name"
              aria-describedby="{uid}-{shape.id}-about"
              onclick={() => onchoose(shape.id)}
            ></button>
          </li>
        {/each}
      </ul>
    </section>

    {#if storageRefused}
      <Callout tone="warn" title={LABELS.storageRefused}>
        <p class="para">{STORAGE_REFUSED}</p>
      </Callout>
    {/if}

    <!--
      Everything below is true, worth having, and not what the reader is here for. A native
      <details> rather than a scripted disclosure: keyboard-reachable, exposed to screen readers
      as a real expandable, findable by in-page search while closed, and working before any
      JavaScript runs.
    -->
    <Card title={LABELS.stored} tone="quiet">
      <p class="para">{WHAT_IS_REMEMBERED}</p>

      {#if config.kind === 'absent' || config.kind === 'unreachable'}
        <details class="why">
          <summary>{LABELS.configurationSays}</summary>
          <!--
            WHY THIS PAGE ASKED: no setup mode reached it, so the answer is this browser's, and the
            setting that makes it the server's is named. Folded away because a person answering
            for their own browser does not need it, and named in full because an operator who set
            it, and is still being asked, needs to know where to look.
          -->
          <p class="para">{NO_SHAPE_PUBLISHED}</p>
        </details>
      {/if}
    </Card>
  {/if}
</section>

<style>
  .setup {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
  }

  h2 {
    margin: 0;
    font-family: var(--font-display);
    font-size: 1.35rem;
    font-weight: 560;
    line-height: 1.25;
    color: var(--ink-text);
  }

  h3 {
    margin: 0 0 var(--space-2);
    font-family: var(--font-display);
    font-size: 1.1rem;
    font-weight: 560;
    line-height: 1.25;
    color: var(--ink-text);
  }

  /* The lede and the limits are one statement in two paragraphs, so they share a measure and a
     weight. The limits take the softer ink only because the lede is the sentence the eye should
     land on first, not because they matter less. */
  .lede,
  .limits {
    margin: 0;
    max-width: 44rem;
    font-size: 1rem;
    line-height: 1.6;
  }

  .lede {
    color: var(--ink-text);
  }

  .limits {
    color: var(--ink-soft);
  }

  .reversible {
    margin: 0 0 var(--space-3);
    max-width: 44rem;
    color: var(--text-subtle);
    font-size: 0.85rem;
    line-height: 1.55;
  }

  .para {
    margin: 0;
    line-height: 1.6;
  }

  .choices {
    min-width: 0;
  }

  .shapes {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  /*
   * The choice card. Structural accent on hover and focus, never the mood ramp: which option the
   * pointer is over is interface state, and the ramp encodes a person's reported experience. The
   * border is --border-strong rather than --hairline because this element's identity as a control
   * depends on it (design system §2.3.7).
   *
   * The card is the <li>; the control is `.choose`, laid over it (see the markup note). `:hover`
   * on the card still fires while the pointer is on the overlay, because the overlay is inside it.
   */
  .shape {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    width: 100%;
    text-align: left;
    padding: var(--space-4);
    background: var(--paper-sheet);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    color: var(--ink-text);
    cursor: pointer;
  }

  .shape:hover {
    border-color: var(--indigo);
    background: var(--indigo-wash);
  }

  /* The overlay. `inset: -1px` reaches over the card's own border so the focus ring is drawn
     where it always was — two pixels outside the card — rather than a pixel inside it. No
     background, no border, no padding: the card underneath supplies all of that. */
  .choose {
    position: absolute;
    inset: -1px;
    margin: 0;
    padding: 0;
    background: transparent;
    border: 0;
    border-radius: var(--radius);
    cursor: pointer;
  }

  .choose:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  .shape-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }

  /* An h4 now, so the browser's heading margins and weight are reset to what the span had. */
  .shape-label {
    margin: 0;
    font-family: var(--font-display);
    font-size: 1.05rem;
    font-weight: 560;
    line-height: 1.3;
    color: var(--ink-text);
  }

  /* The description: one element, so aria-describedby can point at it. Same column and gap as
     the card itself, so the four lines sit exactly where they did as direct children. */
  .shape-about {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  .shape-about p {
    margin: 0;
  }

  .shape-summary {
    font-size: 0.95rem;
    line-height: 1.55;
    color: var(--ink-text);
  }

  .shape-arrangement {
    font-size: 0.9rem;
    line-height: 1.55;
    color: var(--ink-soft);
  }

  /* Emphasis by weight, not by hue: "most people want this" is guidance, not a severity, and
     spending a tone on it would leave nothing for the ones that are. */
  .shape-ranking {
    font-size: 0.9rem;
    font-weight: 600;
    line-height: 1.55;
    color: var(--ink-text);
  }

  .shape-hold {
    font-size: 0.9rem;
    line-height: 1.55;
    color: var(--ink-text);
  }

  /* The build state sits on the chrome ground, because it is the machine talking about itself
     rather than anything about the person or their data. */
  .shape-built {
    font-size: 0.8rem;
    line-height: 1.5;
    color: var(--text-subtle);
    background: var(--chrome);
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
  }

  details {
    margin: var(--space-3) 0 0;
  }

  summary {
    cursor: pointer;
    color: var(--link);
    font-size: 0.9em;
    border-radius: var(--radius-sm);
  }

  summary:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  details[open] > summary {
    margin-bottom: var(--space-2);
  }
</style>
