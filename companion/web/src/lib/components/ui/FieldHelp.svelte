<script lang="ts">
  /*
   * The "what is this field?" affordance, next to a label.
   *
   * WHY NOT A HOVER TOOLTIP. The request was "hover over i icons next to the titles of each
   * field", and hover is the one interaction that cannot be the answer: it does not exist on a
   * touch screen, it is unreachable from the keyboard, and `title=` is announced inconsistently or
   * not at all by screen readers. A clinician on a tablet — the likeliest reader of the screen
   * that needed this most — would get nothing.
   *
   * So it is a real button that toggles a real panel: clickable, tappable, focusable, and
   * announced. It keeps the small round `i` the request asked for, because that part was right;
   * only the trigger changed.
   *
   * WHY NOT <details>/<summary> HERE, when the orientation screen uses exactly that. A <summary>
   * is a block-level heading for its own content, and this has to sit INLINE beside a label
   * without becoming part of it — clicking a <label> focuses its input, so a nested summary
   * fights the label for the same click. An explicit button with `aria-expanded` and
   * `aria-controls` says the same thing to assistive technology and composes where this needs to.
   */
  import { FIELD_HELP, helpPanelId, type FieldId } from '../../onboarding/fieldHelp'

  let { field }: { field: FieldId } = $props()

  const help = $derived(FIELD_HELP[field])
  const panel = $derived(helpPanelId(field))

  let open = $state(false)
</script>

<button
  type="button"
  class="trigger"
  aria-expanded={open}
  aria-controls={panel}
  onclick={() => (open = !open)}
>
  <!--
    The visible glyph is decorative; the accessible name is a whole question, because "i" read
    aloud is a letter. `aria-label` rather than visually-hidden text keeps the button perfectly
    round without a layout hack.
  -->
  <span aria-hidden="true">i</span>
  <span class="sr-only">What is “{help.label}”?</span>
</button>

<!--
  Rendered only while open rather than hidden with CSS, so a screen reader walking the form does
  not encounter the explanation for every field at once — which is the same wall of text this work
  exists to remove, just delivered aurally.
-->
{#if open}
  <div class="panel" id={panel}>
    <p class="what">{help.what}</p>
    <p class="where">{help.where}</p>
  </div>
{/if}

<style>
  .trigger {
    /* Sized in ems so it tracks the label it sits beside rather than a fixed pixel size. */
    inline-size: 1.35em;
    block-size: 1.35em;
    padding: 0;
    margin-inline-start: var(--space-1);
    border: 1px solid var(--border-strong);
    border-radius: 50%;
    background: var(--paper-sheet);
    color: var(--ink-soft);
    font-family: var(--font-display);
    font-size: 0.85em;
    line-height: 1;
    cursor: pointer;
    vertical-align: middle;
  }

  .trigger:hover {
    color: var(--ink-text);
    border-color: var(--ink-soft);
  }

  .trigger:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  .trigger[aria-expanded='true'] {
    background: var(--indigo-wash);
    border-color: var(--indigo);
    color: var(--indigo-deep);
  }

  .panel {
    margin: var(--space-2) 0 var(--space-3);
    padding: var(--space-2) var(--space-3);
    border-inline-start: 2px solid var(--hairline);
    color: var(--ink-soft);
    font-size: 0.9em;
  }

  .panel p {
    margin: 0;
  }

  /* The "where" line is the load-bearing half — see fieldHelp.ts — so it is not dimmed further. */
  .where {
    margin-top: var(--space-1);
    color: var(--ink-text);
  }

  .sr-only {
    position: absolute;
    inline-size: 1px;
    block-size: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
    border: 0;
  }
</style>
