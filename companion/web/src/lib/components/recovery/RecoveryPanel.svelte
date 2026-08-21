<script lang="ts">
  /*
   * THE RECOVERY CODE SURFACE — both flows, and the truth about what sits between them.
   *
   * ─── WHY THE MISSING PIECE IS STATED FIRST, ABOVE BOTH FLOWS ──────────────────────────────────
   *
   * The order of this panel is an argument. A person arrives to do one of two things — get a code,
   * or use one — and either task, done in good faith on this build, ends with a piece of paper that
   * opens nothing, because there is no storage for a wrapped key: no wire format, no endpoint, no
   * client call (migration.ts names all three as deliberately unimplemented).
   *
   * That fact cannot be a caveat under the flow that produced the code. By then the person has
   * already written thirty characters down and formed a belief about what they are for, and a
   * correction arriving after the belief has to fight it. So STORAGE_IS_NOT_BUILT is said at the
   * top, before the choice, where it changes what the reader thinks they are about to do rather
   * than what they think they have just done.
   *
   * ─── WHY BOTH FLOWS ARE HERE AND NOT ON SEPARATE SCREENS ──────────────────────────────────────
   *
   * Because the stand-in that joins them is a variable in this page (session.ts), and the point of
   * that stand-in is that the maintainer can click the whole thing end to end: mint a code, hide
   * it, type it back, then walk next door and open the same wrapped key with it. Split across two
   * routes, the hand-off would need storage — which is the thing that does not exist.
   *
   * In a finished build these are two different moments in a person's life, years apart, reached
   * from completely different places, and this panel would not exist in this shape.
   *
   * ─── WHAT IS DELIBERATELY NOT ON THIS PANEL ───────────────────────────────────────────────────
   *
   * No status line saying whether this person has a recovery code, because nothing knows. No date
   * of last rotation, no count of codes issued, no server state of any kind. Every one of those
   * would have to be invented, and an invented status on a security surface is indistinguishable
   * from a bug — worse, it is indistinguishable from a bug that says everything is fine.
   */
  import { Callout } from '../ui'
  import NewCodeFlow from './NewCodeFlow.svelte'
  import UseCodeFlow from './UseCodeFlow.svelte'
  import Placeholder from './Placeholder.svelte'
  import {
    PANEL_BUILD_STATE,
    PANEL_LEDE,
    PANEL_TITLE,
    PLACEHOLDERS,
    STORAGE_IS_NOT_BUILT,
  } from './copy'

  type Flow = 'new' | 'use'

  let flow = $state<Flow>('new')

  const storage = PLACEHOLDERS.find((p) => p.id === 'storage')!
  /* The rest of the catalogue, listed once at the foot. The storage note is above, on its own,
     because it is the one that changes how everything else on the panel should be read. */
  const rest = PLACEHOLDERS.filter((p) => p.id !== 'storage' && p.id !== 'enrolment')

  const panelId = $props.id()
</script>

<section class="panel" aria-labelledby={`${panelId}-title`}>
  <header class="head">
    <h2 class="title" id={`${panelId}-title`}>{PANEL_TITLE}</h2>
    <p class="lede">{PANEL_LEDE}</p>
  </header>

  <!--
    Two banners, in this order and with these tones. The build state is structural — the interface
    explaining how it is arranged — so it is info. The missing storage is a genuine warning about
    what this screen cannot do, so it is warn. Neither is an alarm: nothing has gone wrong, and
    spending the clay here would blunt it for the failures that need it.
  -->
  <Callout tone="info" title="What is built">
    <p class="para">{PANEL_BUILD_STATE}</p>
  </Callout>

  <Callout tone="warn" title="Nothing stores a wrapped key yet">
    <p class="para">{STORAGE_IS_NOT_BUILT}</p>
  </Callout>

  <Placeholder title={storage.title} specifiedAt={storage.specifiedAt}>
    <p class="para">{storage.body}</p>
  </Placeholder>

  <div class="tabs" role="tablist" aria-label="Recovery code">
    <button
      type="button"
      class="tab"
      role="tab"
      id={`${panelId}-tab-new`}
      aria-selected={flow === 'new'}
      aria-controls={`${panelId}-flow`}
      onclick={() => (flow = 'new')}
    >
      Get a code
    </button>
    <button
      type="button"
      class="tab"
      role="tab"
      id={`${panelId}-tab-use`}
      aria-selected={flow === 'use'}
      aria-controls={`${panelId}-flow`}
      onclick={() => (flow = 'use')}
    >
      Use a code
    </button>
  </div>

  <div
    class="body"
    role="tabpanel"
    id={`${panelId}-flow`}
    aria-labelledby={flow === 'new' ? `${panelId}-tab-new` : `${panelId}-tab-use`}
  >
    {#if flow === 'new'}
      <!--
        `onhandoff` walks the person from the end of the first flow into the second one. It is the
        one piece of navigation on this panel, and it exists because the two flows only connect at
        all through this page's memory — following that hand-off is the only way to see the whole
        thing work on this build.
      -->
      <NewCodeFlow onhandoff={() => (flow = 'use')} />
    {:else}
      <UseCodeFlow />
    {/if}
  </div>

  <div class="rest">
    {#each rest as note (note.id)}
      <Placeholder title={note.title} specifiedAt={note.specifiedAt}>
        <p class="para">{note.body}</p>
      </Placeholder>
    {/each}
  </div>
</section>

<style>
  .panel {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    max-width: var(--maxw);
  }

  .head {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  .title {
    font-family: var(--font-display);
    font-size: 1.3rem;
    margin: 0;
    color: var(--ink-text);
  }

  .lede,
  .para {
    margin: 0;
    max-width: 44rem;
    font-size: 0.9rem;
    line-height: 1.55;
  }

  .lede {
    color: var(--ink-soft);
  }

  .tabs {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    border-bottom: 1px solid var(--hairline);
    padding-bottom: var(--space-2);
  }

  /* The selected tab is interface state, so it is painted in the chrome layer — the cool half of
     the system, where the machine talks about itself. It is never the mood ramp, which encodes a
     person's reported experience and would be a lie on a navigation control. */
  .tab[aria-selected='true'] {
    background: var(--chrome-2);
    border-color: var(--chrome-hair);
    color: var(--chrome-ink);
  }

  .rest {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  /* The tabs and the catalogue of unbuilt things are navigation; the printed page is the code
     sheet plus the sentences that must travel with it. The storage banners above deliberately do
     print — see Placeholder's own note on why a sheet from this build has to carry that. */
  @media print {
    .tabs,
    .head {
      display: none;
    }
  }
</style>
