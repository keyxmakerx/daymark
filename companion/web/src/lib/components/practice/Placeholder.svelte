<script lang="ts">
  /*
   * A THING THAT IS NOT BUILT, DRAWN AS A THING THAT IS NOT BUILT.
   *
   * ─── WHY THIS EXISTS RATHER THAN AN OMISSION ───────────────────────────────────────────────
   *
   * The practice shape is specified in full and implemented in part. Anything unimplemented has
   * three possible renderings and two of them are worse than useless:
   *
   *   OMITTED — indistinguishable from a feature that was cut, or from one the reader failed to
   *   find. Somebody evaluating whether this product does what they need cannot tell "not built"
   *   from "not here", and will conclude whichever they already expected.
   *
   *   FAKED — a plausible name, a date, a count, a status. This is the worst one by a distance,
   *   because it is indistinguishable from a bug: a maintainer clicking through to decide whether
   *   the thing works will spend their afternoon on why the roster shows a clinician nobody
   *   seated. Invented data on a screen about who can read a person's clinical notes is not a
   *   placeholder, it is a lie with a UI.
   *
   *   MARKED — this component. It says the word, in the interface, in plain language, and then
   *   says what would be there instead.
   *
   * ─── HOW IT IS MARKED, AND WHY NOT ONLY BY COLOUR ──────────────────────────────────────────
   *
   * Three signals, in this order: the word "Placeholder" in a chip, a dashed outline all the way
   * round, and the chrome ground. The dashed edge is the one that survives greyscale, a
   * screenshot, a projector and any colour vision — and screenshots are exactly how a screen like
   * this travels. The chrome ground is the second: this is the machine talking about itself, not a
   * person's material, so it belongs in the cool layer rather than on the warm paper a person's
   * data sits on.
   *
   * It carries no severity tone. A placeholder is neither a warning nor an alarm — nothing is
   * wrong, something is absent — and spending the amber or the clay on absence would blunt both
   * hues for the cases that need them.
   */
  import type { Snippet } from 'svelte'
  import { Chip } from '../ui'
  import { PLACEHOLDER_WORD } from '../../practice/copy'

  let {
    title,
    /** Where the real thing is specified, so a reader can go and check rather than take this on faith. */
    specifiedAt,
    children,
  }: {
    title: string
    specifiedAt?: string
    children: Snippet
  } = $props()
</script>

<div class="placeholder">
  <p class="head">
    <Chip tone="neutral">{PLACEHOLDER_WORD}</Chip>
    <span class="title">{title}</span>
  </p>

  <!-- Bare container: the call site's markup passes through untouched, the same arrangement
       Callout uses, so a fixed sentence cannot be reworded by a style change here. -->
  <div class="body">{@render children()}</div>

  {#if specifiedAt}
    <p class="where">{specifiedAt}</p>
  {/if}
</div>

<style>
  /* The dashed edge is the load-bearing signal — see the header note. All four sides, so the shape
     reads as unfinished from any crop. */
  .placeholder {
    border: 1px dashed var(--chrome-hair);
    border-radius: var(--radius);
    background: var(--chrome);
    padding: var(--space-4);
    color: var(--chrome-ink);
  }

  .head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin: 0 0 var(--space-2);
  }

  .title {
    font-weight: 600;
    font-size: 0.95rem;
    color: var(--chrome-ink);
    overflow-wrap: anywhere;
  }

  .body {
    max-width: 44rem;
    font-size: 0.875rem;
    line-height: 1.55;
    color: var(--chrome-ink);
    min-width: 0;
  }

  .where {
    margin: var(--space-2) 0 0;
    font-family: var(--font-mono);
    font-size: 0.7rem;
    line-height: 1.6;
    color: var(--chrome-soft);
    overflow-wrap: anywhere;
  }
</style>
