<script lang="ts">
  /*
   * THE RECOVERY CODE SURFACE — both flows, and where the key they work on is.
   *
   * ─── WHY WHERE THE KEY IS IS SAID FIRST, ABOVE BOTH FLOWS ─────────────────────────────────────
   *
   * The order of this panel is an argument. A person arrives to do one of two things — get a code,
   * or use one — and either task ends with a piece of paper whose worth depends entirely on what it
   * is attached to. The key is kept on their server, locked under the passphrase and again under
   * the code (#258; docs/SYNC_PROTOCOL.md §1.2), and both flows read and write it there with the
   * address and token of the sync card above. That is said at the top, before the choice, where it
   * changes what the reader thinks they are about to do rather than what they think they have just
   * done.
   *
   * ─── WHY BOTH FLOWS ARE HERE AND NOT ON SEPARATE SCREENS ──────────────────────────────────────
   *
   * They are two moments in a person's life, years apart, and both begin at the same place: the
   * server that keeps the key, reached from the card that connects to it. Getting a code is a set-up
   * the owner console's door can also do (KeySetup.svelte is shared); using one is here only, and it
   * ends by storing a new passphrase.
   *
   * ─── WHAT IS DELIBERATELY NOT ON THIS PANEL ───────────────────────────────────────────────────
   *
   * No status line saying whether this person has a recovery code until the server has been read,
   * and then only what the server said. No date of last rotation, no count of codes issued. Every
   * one of those would have to be invented, and an invented status on a security surface is
   * indistinguishable from a bug — worse, it is indistinguishable from a bug that says everything is
   * fine.
   */
  import { Callout } from '../ui'
  import NewCodeFlow from './NewCodeFlow.svelte'
  import UseCodeFlow from './UseCodeFlow.svelte'
  import Placeholder from './Placeholder.svelte'
  import { PANEL_LEDE, PANEL_TITLE, PLACEHOLDERS, WHERE_THE_KEY_IS } from './copy'

  type Flow = 'new' | 'use'

  let flow = $state<Flow>('new')

  /* What is still not built, listed once at the foot. Replacing a code is also shown where a
     person would reach for it, on "Get a code" when the server already holds a key. */
  const notBuilt = PLACEHOLDERS

  let {
    /** The sync card's server address, as typed; blank means this page's own server. */
    serverUrl = '',
    /** The sync card's access token, as typed. */
    token = '',
  }: {
    serverUrl?: string
    token?: string
  } = $props()

  const panelId = $props.id()
</script>

<section class="panel" aria-labelledby={`${panelId}-title`}>
  <header class="head">
    <h2 class="title" id={`${panelId}-title`}>{PANEL_TITLE}</h2>
    <p class="lede">{PANEL_LEDE}</p>
  </header>

  <!--
    Info, not warn: this is the interface saying how it is arranged, and nothing about it is a
    warning. Spending the amber here would blunt it for the refusals that need it.
  -->
  <Callout tone="info" title="Where your key is">
    <p class="para">{WHERE_THE_KEY_IS}</p>
  </Callout>

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
      <NewCodeFlow {serverUrl} {token} />
    {:else}
      <UseCodeFlow {serverUrl} {token} />
    {/if}
  </div>

  <div class="rest">
    {#each notBuilt as note (note.id)}
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
     sheet plus the sentences that must travel with it. */
  @media print {
    .tabs,
    .head,
    .rest {
      display: none;
    }
  }
</style>
