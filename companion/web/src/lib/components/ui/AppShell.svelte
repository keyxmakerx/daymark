<script lang="ts">
  /*
   * The frame every screen is hung in: rail beside, header above, body below.
   *
   * WHY IT EXISTS. Each screen had been laying out its own page — its own idea of where the
   * navigation went, how much air sat above the title, whether the content column was capped.
   * The result was that moving between two screens felt like moving between two products, and
   * the furniture kept stealing attention from the material. One frame means the only thing
   * that changes between screens is the thing that is supposed to change.
   *
   * WHY THE STACKING CONTEXTS ARE NOT DECORATION. The three declarations below — rail
   * `z-index: 7`, header `z-index: 6`, body `z-index: 1` with `isolation: isolate` — are a
   * fixed bug, not a style choice, and they must not be "cleaned up".
   *
   *   A calendar cell lifts itself with z-index on hover so its shadow can rise above its
   *   neighbours. Without a stacking context of their own, the rail and the header are
   *   unpositioned and lose to any positioned descendant of the body, whatever its z-index —
   *   so the hovered cell painted its shadow straight across the navigation and the page
   *   title. Giving the rail and header positions and z-indices puts them in the same stacking
   *   game the cell is playing, and `isolation: isolate` on the body is what makes it a fair
   *   one: it forces every z-index inside the body to be resolved against the body's own root,
   *   so no descendant can ever escape it — not a cell at 10, not a dropdown at 9999. The body
   *   as a whole sits at 1, under the header at 6 and the rail at 7, and that ordering now
   *   holds no matter what a screen does inside it.
   *
   *   This re-breaks silently if any of the three is removed: it only shows while hovering one
   *   kind of cell on one screen, so nothing catches it but a person looking at it.
   *   (COMPANION_WEB_REDESIGN_PLAN.md §1d: topbar 6, rail 7, body 1 + isolate.)
   *
   * WHY THE RAIL SLOT IS A FLEX CONTAINER. So the rail stretches to the full height of the
   * window rather than stopping under its last item and leaving the chrome ground half-drawn
   * down the side of the page. The rail keeps its own width; this only gives it the height.
   *
   * WHY NARROW STACKS INSTEAD OF SHRINKING. Below the breakpoint the grid drops to a single
   * column and the rail becomes a band above the content. The alternative — letting the rail
   * squeeze — truncates destination labels, and a truncated navigation list is worse than none
   * because it misrepresents what the application contains. Nothing is hidden on a small
   * window; it is only re-ordered.
   *
   * WHY NO COLOUR HERE AT ALL. This file paints one thing, the page ground, and otherwise
   * carries no hue: it is pure structure, so it has no status to report, nothing to warn
   * about, and nothing to reassure anyone of. The mood ramp encodes a person's reported
   * experience and is not reachable from here; the layout has no opinion about their data.
   */
  import type { Snippet } from 'svelte'

  let {
    rail,
    header,
    children,
  }: {
    rail: Snippet
    header: Snippet
    children: Snippet
  } = $props()
</script>

<div class="shell">
  <div class="rail">{@render rail()}</div>

  <div class="main">
    <div class="header">{@render header()}</div>
    <main class="body">{@render children()}</main>
  </div>
</div>

<style>
  /* `auto` for the rail so it keeps whatever width it declares for itself; minmax(0, 1fr) for
     the content, because a bare 1fr has an automatic minimum size and a wide table inside it
     would push the whole grid — and the rail — off the side of the window instead of scrolling
     in its own box. */
  .shell {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    min-height: 100vh;
    min-height: 100dvh;
    background: var(--paper-bg);
    color: var(--ink-text);
  }

  /* Load-bearing — see the note in the script block. Do not remove.
     The flex display is what stretches the rail to full height. */
  .rail {
    position: relative;
    z-index: 7;
    display: flex;
    min-width: 0;
  }

  /* Header over body. minmax(0, 1fr) on the body row so it takes the remaining height and can
     still shrink below its content's intrinsic size. */
  .main {
    display: grid;
    grid-template-rows: auto minmax(0, 1fr);
    min-width: 0;
  }

  /* Load-bearing — see the note in the script block. Do not remove. */
  .header {
    position: relative;
    z-index: 6;
    padding: var(--space-5) var(--space-6) 0;
    min-width: 0;
  }

  /* Load-bearing — see the note in the script block. Do not remove. `isolation: isolate` is
     the half that cannot be replaced by a larger z-index elsewhere. */
  .body {
    position: relative;
    z-index: 1;
    isolation: isolate;
    padding: 0 var(--space-6) var(--space-8);
    min-width: 0;
  }

  /* No max-width is imposed on the body. Some screens are prose and want the --maxw measure,
     others are wide tables that would be crippled by it; the screen knows which it is and this
     frame does not. Capping here would silently narrow every table in the product. */

  @media (max-width: 52rem) {
    .shell {
      grid-template-columns: minmax(0, 1fr);
    }

    .header {
      padding: var(--space-4) var(--space-4) 0;
    }

    .body {
      padding: 0 var(--space-4) var(--space-6);
    }
  }
</style>
