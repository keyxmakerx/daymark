<script lang="ts">
  /*
   * The topbar every screen opens with: what am I looking at, how much of it is there, and
   * what can I do to it.
   *
   * WHY IT EXISTS. Six screens had each grown their own title row, and they disagreed — on
   * heading level, on where the action buttons sat, on whether the count was a chip or a
   * sentence. A person moving between "Assignments" and "Shared data" was re-learning the
   * layout each time. One component means the title is always the serif h1, the qualifying
   * chips always sit beside it, and the actions are always in the same corner.
   *
   * WHY THE Z-INDEX IS NOT NEGOTIABLE. `position: relative; z-index: 6` below is a fixed bug,
   * not a style choice. A hovered calendar cell lifts itself with z-index to raise its
   * shadow; a static header has no stacking context of its own to defend with, so the lifted
   * cell painted its shadow straight over the page title. The header has to win, and it wins
   * by being in the same stacking game the cell is playing. Removing that pair re-breaks it,
   * and it re-breaks silently — it only shows up while hovering a specific cell on one
   * screen. (COMPANION_WEB_REDESIGN_PLAN.md §1d: topbar 6, rail 7, body 1 + isolate.)
   *
   * WHY IT WRAPS RATHER THAN TRUNCATES. The trailing slot holds real actions; the chips hold
   * counts a person is entitled to read. Neither may be quietly clipped on a narrow window,
   * so this reflows to stacked rows instead — and below the breakpoint the trailing group
   * drops its auto margin so it aligns under the title rather than stranding itself against
   * the right edge.
   *
   * No colour carries meaning here: this is structure, so it uses --ink-text and the
   * decorative --hairline, never a mood token and never a status hue.
   */
  import type { Snippet } from 'svelte'

  let {
    title,
    children,
    trailing,
  }: {
    title: string
    children?: Snippet
    trailing?: Snippet
  } = $props()
</script>

<header class="page-header">
  <div class="lede">
    <h1 class="title">{title}</h1>
    {#if children}<div class="chips">{@render children()}</div>{/if}
  </div>

  {#if trailing}<div class="trailing">{@render trailing()}</div>{/if}
</header>

<style>
  .page-header {
    /* Load-bearing — see the note in the script block. Do not remove. */
    position: relative;
    z-index: 6;

    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-4);
    padding-bottom: var(--space-3);
    border-bottom: 1px solid var(--hairline);
    margin-bottom: var(--space-5);
  }

  /* Title and its chips travel together, so a wrap never leaves "6 active relationships"
     orphaned on a line above the thing it counts. */
  .lede {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-3);
    min-width: 0;
  }

  .title {
    font-family: var(--font-display);
    font-size: 1.75rem;
    font-weight: 560;
    line-height: 1.2;
    margin: 0;
    color: var(--ink-text);
    /* Long titles wrap rather than force the page to scroll sideways. */
    overflow-wrap: anywhere;
  }

  .chips {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
    min-width: 0;
  }

  /* The flexible gap: auto margin rather than space-between, which would spread the lede's
     own children apart on the rows where everything fits. */
  .trailing {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-left: auto;
  }

  @media (max-width: 34rem) {
    .title {
      font-size: 1.45rem;
    }
    .trailing {
      margin-left: 0;
    }
  }
</style>
