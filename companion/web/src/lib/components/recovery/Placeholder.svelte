<script lang="ts">
  /*
   * A THING THAT IS NOT BUILT, DRAWN AS A THING THAT IS NOT BUILT.
   *
   * ─── WHY THIS EXISTS RATHER THAN AN OMISSION ───────────────────────────────────────────────
   *
   * Recovery is specified in full and implemented in part: the crypto is finished, the storage
   * between the crypto and a server does not exist at all. Anything unimplemented has three
   * possible renderings and two of them are worse than useless:
   *
   *   OMITTED — indistinguishable from a feature that was cut, or from one the reader failed to
   *   find. Somebody deciding whether this product does what they need cannot tell "not built"
   *   from "not here", and will conclude whichever they already expected.
   *
   *   FAKED — a plausible date, a plausible count, a screen that says the code has been kept
   *   somewhere. On this surface that is not merely a bug-shaped lie, it is the specific lie the
   *   whole feature is built to prevent: somebody files a piece of paper on the strength of it and
   *   finds out years later that there was never anything for the paper to open.
   *
   *   MARKED — this component. It says the word, in the interface, in plain language, and then
   *   says what would be there instead and where the real thing is specified.
   *
   * ─── WHY A SECOND COPY OF THE PRACTICE CONSOLE'S PLACEHOLDER ──────────────────────────────────
   *
   * components/practice/Placeholder.svelte is the same idea and reads its marker word out of
   * lib/practice/copy.ts — a module about seats, roles and rosters, which this surface has nothing
   * to do with. Importing it would tie every recovery screen to the practice shape's vocabulary so
   * that two unrelated surfaces could share nine lines of markup. The marker word and the visual
   * treatment are deliberately identical, because a reader who has seen one should recognise the
   * other instantly; the coupling is what is not wanted.
   *
   * ─── HOW IT IS MARKED, AND WHY NOT ONLY BY COLOUR ──────────────────────────────────────────
   *
   * Three signals: the word "Placeholder" in a chip, a dashed outline all the way round, and the
   * chrome ground. The dashed edge is the one that survives greyscale, a screenshot, a projector
   * and any colour vision — and a screenshot is exactly how a screen like this travels. The chrome
   * ground is the second: this is the machine talking about itself rather than a person's material,
   * so it belongs in the cool layer rather than on the warm paper a person's data sits on.
   *
   * It carries no severity tone. A placeholder is neither a warning nor an alarm — nothing is
   * wrong, something is absent — and spending the amber or the clay on absence would blunt both
   * hues for the cases that need them.
   */
  import type { Snippet } from 'svelte'
  import { Chip } from '../ui'
  import { PLACEHOLDER_WORD } from './copy'

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

  /* Deliberately NOT hidden when printing. The first instinct is to strip scaffolding off a page
     somebody is going to keep — and it is exactly backwards here. A sheet printed from this build
     carries a code that opens nothing, and the piece of paper is the artefact most likely to
     outlive the build, be found in a drawer, and be trusted. The dashed box saying so is the most
     useful thing on it. `print-color-adjust` because the ground and the dashed edge are two of the
     three signals, and a browser dropping backgrounds would leave a bare paragraph. */
  @media print {
    .placeholder {
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
    }
  }
</style>
