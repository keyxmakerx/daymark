<script lang="ts">
  /*
   * The companion — a presence you can open, talk to through fixed choices, and hide.
   *
   * A THIN RENDERER ON PURPOSE. Everything with behaviour lives in `companion/walk.ts`, which is
   * pure and executed against the real dialogue under thousands of signal combinations in
   * walk.test.ts. This project has no DOM harness (vite.config.ts sets `environment: 'node'`), so
   * anything that lived in here could only be source-asserted. Keeping the conversation out of the
   * component is what makes it testable at all.
   *
   * THE FOUR RULES IT EXISTS TO KEEP (docs/DECISIONS_2026-08.md §D1b), none of which is mine —
   * they are the maintainer's own design, written down so nobody "improves" them later:
   *
   *  1. FIXED CHOICES, NO TEXT INPUT. There is deliberately no <input> and no <textarea> here. A
   *     chat box implies it will parse what you type; this cannot, and the predictable failure is
   *     someone in a bad moment typing something real into a thing that cannot answer.
   *  2. REFLECT, NEVER LABEL. Lines hand back what the person logged ("three of your last seven
   *     check-ins were on the harder end"). Nothing concludes anything about them. Enforced by
   *     regex over the content in walk.test.ts, not by good intentions here.
   *  3. HIDDEN MEANS HIDDEN. Dismissing is one press, it does not come back on its own, and it
   *     never reappears to say it missed you. Un-hiding is a setting the person goes and finds.
   *  4. NOT THE CRISIS PATH. It may point at a safety plan the person already wrote. It never
   *     becomes one, and it never offers to be one.
   *
   * The arbiter is not consulted here. Opening this is the person speaking first, and you do not
   * ask a permission gate for consent to answer someone who spoke to you (§D1b). The arbiter is
   * only involved if this ever surfaces itself unprompted, which it does not do today.
   */
  import { COMPANION_DIALOGUE, type CompanionDestination } from '../companion/content'
  import type { DialogueSignals } from '../companion/dialogue'
  import { start, view, choose, type WalkState } from '../companion/walk'

  let {
    signals = {},
    onopen,
    onhide,
  }: {
    /** The eight facts the companion may know. Absent is normal — a new install has none. */
    signals?: DialogueSignals
    /** Where an ending wants the host to go. Null endings open nothing, which is the common case. */
    onopen?: (destination: CompanionDestination) => void
    /** The person hid it. The host persists that; this component does not decide to come back. */
    onhide?: () => void
  } = $props()

  let open = $state(false)
  let walk = $state<WalkState>(start(COMPANION_DIALOGUE))
  /** Off by default: the reason a line was chosen is available, not in the way. */
  let showWhy = $state(false)

  const current = $derived(view(walk, COMPANION_DIALOGUE, signals))

  function begin() {
    walk = start(COMPANION_DIALOGUE)
    open = true
  }

  function pick(index: number) {
    walk = choose(walk, index, COMPANION_DIALOGUE, signals)
    // An ending may want the host to open something. Resolved from the line that actually fired,
    // never re-derived from conditions that could have changed since.
    if (walk.ended && walk.destination && onopen) onopen(walk.destination)
  }

  function close() {
    open = false
    walk = start(COMPANION_DIALOGUE)
  }

  function hide() {
    open = false
    onhide?.()
  }
</script>

{#if !open}
  <button class="fab" type="button" onclick={begin} aria-label="Open the companion">
    <span class="glyph" aria-hidden="true"></span>
  </button>
{:else}
  <section class="panel" aria-label="Companion">
    <header>
      <span class="glyph small" aria-hidden="true"></span>
      <span class="name">Companion</span>
      <button class="link" type="button" onclick={() => (showWhy = !showWhy)} aria-pressed={showWhy}>
        {showWhy ? 'hide why' : 'why this'}
      </button>
      <button class="link" type="button" onclick={hide}>hide</button>
      <button class="x" type="button" onclick={close} aria-label="Close">×</button>
    </header>

    <div class="body">
      {#if current.line}
        <p class="say">{current.line}</p>
      {/if}

      {#if showWhy}
        <p class="why mono">
          {#if current.planned?.fallback}
            nothing matched — this is the line it says when it has nothing to go on
          {:else if current.planned}
            chosen because a condition on this line matched what you logged
          {:else}
            no line
          {/if}
        </p>
      {/if}

      {#if current.ended}
        <p class="closed">That's the end of it. Nothing is queued and nothing follows up.</p>
        <button class="opt" type="button" onclick={close}>Close</button>
      {:else}
        <div class="opts">
          {#each current.options as option, i (option.label)}
            <button class="opt" class:ends={option.ends} type="button" onclick={() => pick(i)}>
              {option.label}
            </button>
          {/each}
        </div>
      {/if}
    </div>
  </section>
{/if}

<style>
  .fab {
    position: fixed;
    right: var(--space-5);
    bottom: var(--space-5);
    width: 3.1rem;
    height: 3.1rem;
    border-radius: 50%;
    border: 1px solid var(--border-strong);
    background: var(--paper-sheet);
    box-shadow: var(--elevation);
    display: grid;
    place-items: center;
    cursor: pointer;
    transition: transform 160ms cubic-bezier(0.2, 0.8, 0.3, 1);
  }
  .fab:hover { transform: translateY(-2px); }

  .glyph {
    width: 1.15rem;
    height: 1.15rem;
    border-radius: 50%;
    border: 1.6px solid var(--indigo);
    position: relative;
  }
  .glyph::after {
    content: '';
    position: absolute;
    left: 50%;
    top: 50%;
    transform: translate(-50%, -50%);
    width: 0.42rem;
    height: 1.6px;
    border-radius: 2px;
    background: var(--indigo);
    box-shadow: 0 -0.25rem 0 var(--indigo), 0 0.25rem 0 var(--indigo);
  }
  .glyph.small { width: 0.9rem; height: 0.9rem; }

  .panel {
    position: fixed;
    right: var(--space-4);
    bottom: var(--space-4);
    width: min(22rem, calc(100vw - 2 * var(--space-4)));
    background: var(--paper-sheet);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    box-shadow: var(--elevation);
    overflow: hidden;
    animation: rise 300ms cubic-bezier(0.2, 0.8, 0.3, 1);
  }
  @keyframes rise {
    from { opacity: 0; transform: translateY(0.75rem); }
    to { opacity: 1; transform: none; }
  }

  header {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    background: var(--chrome);
    border-bottom: 1px solid var(--chrome-hair);
  }
  .name {
    flex: 1;
    font-family: var(--font-mono);
    font-size: 0.65rem;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--chrome-soft);
  }
  .link {
    font: inherit;
    font-size: 0.72rem;
    background: none;
    border: 0;
    color: var(--chrome-soft);
    cursor: pointer;
    padding: 0.15rem 0.3rem;
    border-radius: var(--radius-sm);
  }
  .link:hover { color: var(--chrome-ink); }
  .x {
    font: inherit;
    font-size: 1rem;
    line-height: 1;
    background: none;
    border: 0;
    color: var(--chrome-soft);
    cursor: pointer;
    padding: 0.1rem 0.25rem;
    border-radius: var(--radius-sm);
  }

  .body { padding: var(--space-4) var(--space-3) var(--space-3); }

  .say {
    font-family: var(--font-display);
    font-size: 1.02rem;
    line-height: 1.45;
    margin: 0;
  }

  .why {
    font-size: 0.68rem;
    color: var(--indigo);
    background: var(--indigo-wash);
    border: 1px solid var(--indigo);
    border-radius: var(--radius-sm);
    padding: var(--space-2);
    margin: var(--space-3) 0 0;
    line-height: 1.4;
  }

  .opts {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    margin-top: var(--space-4);
  }
  .opt {
    text-align: left;
    font: inherit;
    font-size: 0.88rem;
    background: var(--paper-bg);
    color: var(--ink-text);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
    cursor: pointer;
    transition: border-color 140ms ease, background 140ms ease;
  }
  .opt:hover { border-color: var(--indigo); background: var(--indigo-wash); }
  /* An ending is drawn quieter than a continuation — leaving is never the loud option. */
  .opt.ends { border-style: dashed; color: var(--ink-soft); }

  .closed {
    font-size: 0.85rem;
    color: var(--text-subtle);
    margin: var(--space-3) 0 var(--space-3);
    padding-top: var(--space-3);
    border-top: 1px solid var(--hairline);
  }

  @media (prefers-reduced-motion: reduce) {
    .panel { animation: none; }
    .fab { transition: none; }
  }
</style>
