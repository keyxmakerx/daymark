<script lang="ts">
  /*
   * THE STEP THAT MAKES THE CONFIRMATION MEAN SOMETHING.
   *
   * The code is not on screen while this is rendered. That is not a detail of the arrangement, it
   * is the mechanism: a type-back check with the code still visible is a typing exercise, passed
   * perfectly by exactly the person it exists to catch. confirmation.ts sets out the rest of the
   * reasoning — why two groups rather than one or six, why they are drawn at random, and why there
   * is no score, no attempt count and no penalty anywhere in it.
   *
   * WHAT THIS COMPONENT ADDS TO THAT MODULE. Only the surface: two boxes labelled with the group
   * numbers that were drawn, a sentence when one does not match, and a way back to seeing the code.
   * The selection is drawn once, when this component first renders, and does not change while the
   * person is answering — re-drawing it on every keystroke or every failed attempt would mean
   * somebody who genuinely wrote the code down could be asked about a third and fourth group for
   * having mistyped the first, which reads as the software moving the goalposts.
   *
   * WHY THE WAY BACK IS OFFERED PROMINENTLY, AND WITHOUT COMMENT. A person who cannot answer has
   * discovered something useful — their paper is missing or wrong — and the correct response is to
   * show them the code again, not to make them feel caught. There is no attempt counter here and no
   * language of failure: SHOWING_AGAIN_IS_FINE says so in as many words, because a person who
   * believes they have used up a chance will guess rather than ask.
   *
   * ACCESSIBILITY. The mismatch sentence lives in a live region and is referenced by the fields it
   * concerns, so it is announced when it appears rather than only found by someone tabbing back.
   */
  import { Callout } from '../ui'
  import {
    chooseConfirmationGroups,
    checkWrittenDown,
    confirmationIsComplete,
    firstMismatch,
  } from './confirmation'
  import { normalizeGroup } from './groups'
  import { GROUP_SIZE } from '../../recovery/recoveryCode'
  import {
    CONFIRMATION_MISMATCH,
    IF_BOTH_ARE_LOST,
    SHOWING_AGAIN_IS_FINE,
    WHY_TYPE_IT_BACK,
    groupDoesNotMatch,
  } from './copy'

  let {
    canonical,
    onconfirmed,
    onshowagain,
  }: {
    canonical: string
    onconfirmed: () => void
    onshowagain: () => void
  } = $props()

  /*
   * Drawn once, at construction. See the header note: a selection that changed under the person
   * would turn a mistyped character into a new pair of questions.
   */
  const asked = chooseConfirmationGroups()

  let answers = $state(asked.map(() => ''))
  /** The group named by the last check, or null when nothing has been checked yet. */
  let missed = $state<number | null>(null)

  const messageId = $props.id()

  function check() {
    const results = checkWrittenDown(canonical, asked, answers)
    if (confirmationIsComplete(results)) {
      missed = null
      onconfirmed()
      return
    }
    missed = firstMismatch(results)
  }

  function update(index: number, value: string) {
    answers[index] = normalizeGroup(value).slice(0, GROUP_SIZE)
    /* The complaint is about what was typed a moment ago; the moment it is edited it is stale. */
    missed = null
  }
</script>

<div class="check">
  <p class="lede">{WHY_TYPE_IT_BACK}</p>

  <div class="boxes">
    {#each asked as group, i}
      <div class="box">
        <label class="u-label" for={`confirm-group-${group}`}>Group {group}</label>
        <input
          id={`confirm-group-${group}`}
          class="field"
          type="text"
          inputmode="text"
          value={answers[i]}
          maxlength={GROUP_SIZE}
          size={GROUP_SIZE}
          autocomplete="off"
          autocapitalize="characters"
          autocorrect="off"
          spellcheck="false"
          aria-invalid={missed === group}
          aria-describedby={missed === group ? messageId : undefined}
          oninput={(e) => update(i, (e.target as HTMLInputElement).value)}
        />
      </div>
    {/each}
  </div>

  <!--
    The live region exists whether or not it has anything in it, so a sentence appearing in it is
    announced. A region added to the document at the same moment as its text is not reliably read.
  -->
  <div class="message" id={messageId} role="status" aria-live="polite">
    {#if missed !== null}
      <Callout tone="warn" title={groupDoesNotMatch(missed)}>
        <p class="para">{CONFIRMATION_MISMATCH}</p>
      </Callout>
    {/if}
  </div>

  <div class="actions">
    <button type="button" class="primary" onclick={check}>Check what I wrote down</button>
    <button type="button" onclick={onshowagain}>Show the code again</button>
  </div>

  <p class="note">{SHOWING_AGAIN_IS_FINE}</p>
  <p class="cost">{IF_BOTH_ARE_LOST}</p>
</div>

<style>
  .check {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .lede,
  .note,
  .cost,
  .para {
    margin: 0;
    max-width: 44rem;
    line-height: 1.55;
  }

  .lede {
    font-size: 0.9rem;
    color: var(--ink-text);
  }

  .note {
    font-size: 0.85rem;
    color: var(--ink-soft);
  }

  .cost {
    font-size: 0.9rem;
    color: var(--ink-text);
    border-top: 1px solid var(--hairline);
    padding-top: var(--space-3);
  }

  .boxes {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-4);
  }

  .box {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .field {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    font-size: 1.15rem;
    inline-size: 6.5rem;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--paper-bg);
    color: var(--ink-text);
  }

  /* Amber rather than clay: a group that does not match is not an alarm and nothing has gone
     wrong with the software. The callout beside it carries the same tone and the words. */
  .field[aria-invalid='true'] {
    border-color: var(--amber);
    background: var(--amber-wash);
  }

  .field:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  /* Empty until there is something to say, and it holds no height when empty. */
  .message:empty {
    display: none;
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  @media print {
    .check {
      display: none;
    }
  }
</style>
