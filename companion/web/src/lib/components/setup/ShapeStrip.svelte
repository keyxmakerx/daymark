<script lang="ts">
  /*
   * THE RETURNING-PERSON STRIP. What this machine was set up as, in one line, above everything.
   *
   * WHY IT IS A STRIP AND NOT A SCREEN. The second half of this slice's design is that a person
   * who has already answered never meets the question again — they get the surface for their
   * shape and nothing between them and it. So what survives the first visit is one line stating
   * the answer, a folded explanation, and the control that changes it. Anything more would be the
   * first-run screen wearing a smaller font.
   *
   * WHY THE CONFIGURED CASE IS LOUDER RATHER THAN QUIETER. A deployment that pins the shape in
   * configuration is not asked — and a screen that silently skips a question leaves the operator
   * wondering why they were never asked, on a page that gives them nowhere to look. So that state
   * spends the space the choice would have taken on naming the endpoint, the field, the value and
   * the exact setting to remove. It is the one thing on this strip that is not folded away.
   *
   * WHY THERE IS NO CHANGE BUTTON WHEN CONFIGURATION DECIDED. `isChangeable` is false for a
   * configured deployment, and the button is not rendered rather than rendered disabled: a
   * control that looked like it would change the shape and then lost to the same flag on the next
   * load is worse than no control, and the sentence beside it already says where the change is
   * made.
   *
   * WHY IT SAYS WHERE THE PAGE OPENS. The shape routes the first surface — a backup file for
   * Solo, the owner console for Paired, the marked placeholder for Practice — and routing that
   * happens without explanation reads as the page having decided something on its own.
   *
   * NO TICK, NO "SET UP CORRECTLY", NO COMPLETION. This strip states an answer. It does not
   * establish that the deployment works, and nothing here is permitted to imply it does.
   */
  import { Callout, Chip } from '../ui'
  import {
    CONFIGURATION_IS_NOT_RE_READ,
    FORGET_REFUSED,
    LABELS,
    RECOVERY_LINK_BYPASS,
    STORAGE_REFUSED,
    configuredHowToChange,
    configuredStatement,
    isChangeable,
    opensOnStatement,
    shapeById,
    type SetupDecision,
  } from '../../setup/shape'

  let {
    /** The resolved decision. Rendered for `chosen` and `configured`; `ask` only when bypassed. */
    decision,
    /** Reopens the first-run screen. Absent — or with a configured decision — no control shows. */
    onchange = undefined,
    /** Opens the practice placeholder. Only meaningful while the shape is Practice. */
    onopenpractice = undefined,
    /** The browser would not keep the last answer. */
    storageRefused = false,
    /** The browser would not remove the stored answer, so claiming it is gone would be a lie. */
    forgetRefused = false,
    /**
     * Set when this visit arrived on a recovery link. The setup question is deferred, not
     * answered, and the strip says which of those it is rather than leaving the machine looking
     * set up when it is not.
     */
    bypassed = false,
  }: {
    decision: SetupDecision
    onchange?: () => void
    onopenpractice?: () => void
    storageRefused?: boolean
    forgetRefused?: boolean
    bypassed?: boolean
  } = $props()

  const shape = $derived(
    decision.state === 'chosen' || decision.state === 'configured'
      ? shapeById(decision.shape)
      : null,
  )
  const changeable = $derived(isChangeable(decision) && onchange !== undefined)
</script>

<section class="strip" aria-label={LABELS.shapeStrip}>
  {#if shape}
    <div class="line">
      <span class="lead">{LABELS.shapeStrip}</span>
      <Chip tone="accent">{shape.label}</Chip>
      {#if changeable}
        <button class="action" type="button" onclick={() => onchange?.()}>
          {LABELS.changeShape}
        </button>
      {/if}
      {#if shape.primary === 'practice' && onopenpractice}
        <!--
          Brings the panel back after a returning person has navigated away from it. It says
          "panel" rather than "console" on purpose: the console is a separate page, the panel is
          what this page has, and conflating the two here would promise administration on a
          surface that does none.
        -->
        <button class="action" type="button" onclick={() => onopenpractice?.()}>
          {LABELS.showPracticePanel}
        </button>
      {/if}
    </div>

    {#if decision.state === 'configured'}
      <!--
        Not folded, and not shortened. This is the whole of what an operator needs in order to
        understand why they were never asked, and it names the setting rather than gesturing at
        "your configuration".
      -->
      <Callout tone="info" title={LABELS.configured}>
        <p class="para">{configuredStatement(decision.shape)}</p>
        <p class="para">{configuredHowToChange()}</p>
      </Callout>
    {/if}

    <details class="more">
      <summary>{LABELS.whatThisMeans}</summary>
      <p class="para">{shape.arrangement}</p>
      <p class="para">{shape.summary}</p>
      <p class="para built">{shape.buildNote}</p>
      <p class="para opens">{opensOnStatement(shape.id)}</p>
      {#if decision.state === 'chosen'}
        <!--
          WHAT THE PAGE STOPPED DOING ONCE THIS WAS ANSWERED. The read of GET /v1/config is scoped
          to the question being open, so that a settled machine's offline surface makes no request
          at all — see lib/setup/configProbe.ts for why that mattered enough to change. The price
          is paid by exactly one person, the operator who pins the shape after somebody already
          answered here, and it is invisible to them unless it is written down. Folded in with the
          rest rather than raised: it is a fact about this arrangement, not a fault.

          Only for `chosen`. A configured deployment IS the re-read, and the callout above already
          names the setting and what to remove.
        -->
        <p class="para opens">{CONFIGURATION_IS_NOT_RE_READ}</p>
      {/if}
    </details>
  {:else if bypassed}
    <!--
      A recovery link beats the setup question. Somebody following one is already having a bad
      day, and a first-run screen standing between them and their own access would be the exact
      failure this slice is under instruction to avoid.
    -->
    <Callout tone="info" title={LABELS.shapeStrip}>
      <p class="para">{RECOVERY_LINK_BYPASS}</p>
      {#if onchange}
        <p class="para">
          <button class="action" type="button" onclick={() => onchange?.()}>
            {LABELS.changeShape}
          </button>
        </p>
      {/if}
    </Callout>
  {/if}

  {#if storageRefused}
    <Callout tone="warn" title={LABELS.storageRefused}>
      <p class="para">{STORAGE_REFUSED}</p>
    </Callout>
  {/if}

  {#if forgetRefused}
    <Callout tone="warn" title={LABELS.forgetRefused}>
      <p class="para">{FORGET_REFUSED}</p>
    </Callout>
  {/if}
</section>

<style>
  .strip {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
    background: var(--chrome);
    border: 1px solid var(--chrome-hair);
    border-radius: var(--radius);
  }

  /* The chrome ground is the point: this strip is the machine describing its own arrangement,
     never a reading of anything a person recorded. */
  .line {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }

  .lead {
    font-family: var(--font-mono);
    font-size: 0.7rem;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
  }

  .action {
    margin-left: auto;
    font-size: 0.85rem;
    padding: var(--space-1) var(--space-3);
    background: var(--paper-sheet);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--chrome-ink);
    cursor: pointer;
  }

  /* Two controls on one line: only the first is pushed right, or they separate. */
  .action ~ .action {
    margin-left: 0;
  }

  .action:hover {
    border-color: var(--indigo);
  }

  .action:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  .para {
    margin: 0;
    color: var(--chrome-ink);
    font-size: 0.9rem;
    line-height: 1.6;
    max-width: 44rem;
  }

  .para + .para {
    margin-top: var(--space-2);
  }

  .built,
  .opens {
    color: var(--chrome-soft);
    font-size: 0.85rem;
  }

  details {
    margin: 0;
  }

  summary {
    cursor: pointer;
    color: var(--link);
    font-size: 0.85rem;
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
