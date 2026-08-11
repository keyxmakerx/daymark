<script lang="ts">
  /*
   * The left rail — the one place that says what this application is made of.
   *
   * WHY IT EXISTS. Every screen had been reached by its own bespoke tab strip or link row, so
   * the shape of the product changed depending on where you were standing and there was no
   * single answer to "what else is in here?". The rail is that answer, and it is identical on
   * every screen: same order, same labels, same position. Navigation that moves is navigation
   * that has to be re-read.
   *
   * WHY IT IS CHROME AND NOT PAPER. The design rule that generated this system is "cool chrome,
   * warm content" (app.css). The rail is the machine talking about itself — it is furniture,
   * not subject — so it sits on the cool --chrome ground with mono micro-labels, and the warm
   * paper sheet is reserved for a person's material. A reader should be able to tell at a
   * glance, without reading a word, which half of the screen is theirs.
   *
   * WHY DISABLED ITEMS ARE DIMMED AND NOT DELETED. See nav.ts for the long version. A rail that
   * silently omits what a role cannot reach teaches every reader a different, smaller shape of
   * the application, and makes "feature does not exist", "not granted to me" and "quietly
   * removed" indistinguishable. So a boundary is drawn rather than hidden: the item stays in
   * place, dimmed, marked `aria-disabled` — which keeps it in the tab order and lets a screen
   * reader announce it as unavailable, unlike the `disabled` attribute, which would remove it
   * from the page's keyboard reality altogether and reproduce the invisibility by other means.
   * The click is refused in the handler, not by the DOM.
   *
   * WHY THE ACTIVE ITEM IS NOT SIGNALLED BY COLOUR. Three signals, only one of which is a hue:
   *   1. `aria-current="page"`, which is what assistive tech actually reads;
   *   2. a 2px rule down the left edge and a solid --chrome-2 fill — form and value, both of
   *      which survive greyscale, a projector and any colour vision;
   *   3. only then --indigo-deep text.
   * Every item declares the same transparent 2px left border whether or not it is active, so
   * selection never nudges the labels sideways.
   *
   * WHY INDIGO AND NEVER A MOOD TOKEN. Which page you are on is interface state. The mood ramp
   * encodes a person's reported experience and nothing else (app.css, invariant 1); painting
   * the selected nav item with a wash from it would make the legend lie — a tint a reader has
   * learned to read as "a rough day" would suddenly mean "you are here". Structure gets the
   * structural accent. The ramp's token names appear nowhere in this file, comments included,
   * so the "only BandTag references the ramp" grep stays a simple one.
   *
   * WHY THERE IS NO GREEN COUNT. A count is either ordinary or it needs a human, and only the
   * second gets a colour — the single alarm hue, spent sparingly enough that it still means
   * something (app.css, invariant 2). There is deliberately no reassuring variant: a rail
   * cannot certify that a queue is fine, and an empty queue is already rendered as `0`.
   * Critical counts do not lean on the hue alone either — they are boxed, so they read as
   * marked on a monochrome printout, and they carry a visually hidden "needing attention" so a
   * screen reader is told what the box is for. The hue is the third signal, not the first.
   *
   * WHY THE COUNTS ARE MONO AND tabular-nums. A rail is scanned vertically. In proportional
   * digits 9, 12 and 118 set at three different widths, so the eye compares glyph runs instead
   * of magnitudes, and a number that ticks up makes its row twitch. Same reasoning as the
   * numeric columns in DataTable; here it also keeps the right edge of the rail still.
   */
  import type { Snippet } from 'svelte'
  import type { NavGroup, NavItem } from './nav'

  let {
    groups,
    active,
    onselect,
    title,
    subtitle,
    footer,
  }: {
    groups: NavGroup[]
    active: string
    onselect: (id: string) => void
    title: string
    subtitle: string
    footer?: Snippet
  } = $props()

  /* Unique per instance, so two rails on one page cannot collide their group labels. */
  const uid = $props.id()

  /*
   * The refusal lives here rather than on the element, because the element has to stay
   * focusable to do its job (see the note above). A disabled item that a keyboard user can
   * reach and read, but that does nothing when pressed, is the honest rendering of "this
   * exists and is not yours".
   */
  function select(item: NavItem): void {
    if (item.disabled) return
    onselect(item.id)
  }
</script>

<nav class="rail" aria-label={title}>
  <div class="brand">
    <!-- Not an <h1>: the rail is furniture on every screen, and the page's own subject owns
         the document's top heading. A product name promoted to h1 would displace it in the
         outline that heading-navigation users rely on. -->
    <p class="brand-title">{title}</p>
    <p class="brand-subtitle">{subtitle}</p>
  </div>

  <div class="groups">
    {#each groups as group, groupIndex (group.label ?? groupIndex)}
      {@const labelId = `${uid}-group-${groupIndex}`}
      <div class="group">
        {#if group.label}
          <p class="group-label" id={labelId}>{group.label}</p>
        {/if}

        <!-- A real list, so assistive tech can announce "3 items" before reading them and a
             reader can skip the group wholesale. Labelled by its own heading where it has
             one. -->
        <ul class="items" aria-labelledby={group.label ? labelId : undefined}>
          {#each group.items as item (item.id)}
            <li>
              <button
                type="button"
                class="item"
                class:active={item.id === active}
                aria-current={item.id === active ? 'page' : undefined}
                aria-disabled={item.disabled ? 'true' : undefined}
                onclick={() => select(item)}
              >
                <span class="item-label">{item.label}</span>

                <!-- `undefined` means "this destination does not report a quantity"; 0 means
                     "we counted, and nothing is waiting". Both are true statements and they
                     are not the same one, so 0 renders. -->
                {#if item.count !== undefined}
                  <span class="count" class:critical={item.critical}>
                    {item.count}{#if item.critical}<span class="visually-hidden">
                        needing attention</span
                      >{/if}
                  </span>
                {/if}
              </button>
            </li>
          {/each}
        </ul>
      </div>
    {/each}
  </div>

  {#if footer}
    <div class="footer">{@render footer()}</div>
  {/if}
</nav>

<style>
  /* 196px is a measured width, not a round one: wide enough for the longest destination label
     in the product to sit on one line, narrow enough that it never competes with the content
     column for attention. The rail stretches to its container's height (AppShell makes the
     rail slot a flex container for exactly this), so the chrome ground runs the full side of
     the window rather than stopping under the last item. */
  .rail {
    display: flex;
    flex-direction: column;
    width: 196px;
    flex: none;
    background: var(--chrome);
    border-right: 1px solid var(--chrome-hair);
    color: var(--chrome-ink);
  }

  .brand {
    padding: var(--space-4) var(--space-4) var(--space-3);
  }

  /* The display serif, which is otherwise the content voice. The product name is the one piece
     of chrome allowed to use it — it is a name, not a machine label, and it is what tells a
     reader which application they are in. */
  .brand-title {
    margin: 0;
    font-family: var(--font-display);
    font-size: 1.05rem;
    font-weight: 560;
    line-height: 1.2;
    color: var(--chrome-ink);
    overflow-wrap: anywhere;
  }

  /* The chrome micro-label, matching .u-label in app.css. Deployment, role, build — the
     machine's own annotation of what this instance is. */
  .brand-subtitle,
  .group-label {
    margin: 0;
    font-family: var(--font-mono);
    font-size: 10px;
    font-weight: 500;
    line-height: 1.4;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--chrome-soft);
    overflow-wrap: anywhere;
  }

  .brand-subtitle {
    margin-top: var(--space-1);
  }

  .groups {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    padding: var(--space-2) 0 var(--space-4);
    min-width: 0;
  }

  .group-label {
    /* Aligned with the item labels, allowing for the items' 2px selection rule, so the group
       heading and the words it heads share one left edge. */
    padding: 0 var(--space-4) var(--space-1) calc(var(--space-4) + 2px);
  }

  .items {
    list-style: none;
    margin: 0;
    padding: 0;
    min-width: 0;
  }

  /*
   * Resets the global `button` rule in app.css wholesale — that rule dresses a button as a
   * paper-layer control (sheet fill, strong border, radius), which is the wrong layer here.
   * The 2px transparent left border is declared for every item, active or not, so selection
   * changes colour and never geometry.
   */
  .item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
    width: 100%;
    border: 0;
    border-left: 2px solid transparent;
    border-radius: 0;
    background: transparent;
    color: var(--chrome-ink);
    font-family: var(--font-text);
    font-size: 0.875rem;
    line-height: 1.4;
    text-align: left;
    padding: var(--space-2) var(--space-4);
    cursor: pointer;
    transition: background 120ms ease, color 120ms ease;
  }

  .item-label {
    min-width: 0;
    overflow-wrap: anywhere;
  }

  /* Guarded on `hover: hover` so a touch device does not leave the last-tapped item painted,
     and on the disabled state so a boundary does not offer feedback it will not honour. Mixed
     toward transparent rather than used neat, so hover stays legibly weaker than the solid
     --chrome-2 of the active item. */
  @media (hover: hover) {
    .item:not([aria-disabled='true']):hover {
      background: color-mix(in srgb, var(--chrome-2) 60%, transparent);
    }
  }

  .item:active:not([aria-disabled='true']) {
    background: var(--chrome-2);
  }

  /* Signal 2 and 3 of the three described in the script block; signal 1 is aria-current. */
  .item.active {
    background: var(--chrome-2);
    border-left-color: var(--indigo);
    color: var(--indigo-deep);
    font-weight: 600;
  }

  /* Reachable, readable, refused. Not `cursor: not-allowed`, which reads as an error the
     reader caused; this is a boundary, and the plain cursor states it without scolding. */
  .item[aria-disabled='true'] {
    opacity: 0.5;
    cursor: default;
  }

  /* Inset, because the rail can scroll inside its own box on a short window and an outward
     ring would be clipped at the edge exactly when a keyboard user needs it. */
  .item:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: -2px;
  }

  /*
   * Counts are data, so they get the readable --chrome-ink rather than the muted micro-label
   * tone. Both variants carry the same border and padding so a count that turns critical
   * cannot shift the label beside it.
   */
  .count {
    flex: none;
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    font-size: 11px;
    line-height: 1.45;
    color: var(--chrome-ink);
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    padding: 0 var(--space-1);
  }

  /* The single alarm hue — something behind this destination needs a human. The box is the
     load-bearing signal: it survives greyscale and a printout, where the hue does not, and the
     visually hidden "needing attention" in the markup gives a screen reader the same fact. */
  .count.critical {
    color: var(--clay);
    background: var(--clay-wash);
    border-color: var(--clay);
    font-weight: 600;
  }

  .item.active .count {
    color: inherit;
  }
  .item.active .count.critical {
    color: var(--clay);
  }

  /*
   * The key/value strip — session, keys held, grants in force. Pushed to the bottom by the
   * auto margin so it sits on the window's edge rather than trailing the last nav item, and
   * separated by the chrome hairline: it is a statement about this session, not another
   * destination, and it must not be mistaken for one.
   */
  .footer {
    margin-top: auto;
    padding: var(--space-3) var(--space-4);
    border-top: 1px solid var(--chrome-hair);
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    font-size: 11px;
    line-height: 1.5;
    color: var(--chrome-soft);
    min-width: 0;
  }

  /*
   * Narrow: the rail gives up its column and becomes a band above the content rather than
   * squeezing to a width where the labels truncate. A truncated destination list is worse than
   * no list — it is a list that lies about what it contains. The items flow as a wrapping row
   * so the band costs a couple of lines instead of a screenful; the 2px selection rule still
   * marks the active one, and disabled items are still visible, which is the whole point.
   */
  @media (max-width: 52rem) {
    .rail {
      width: 100%;
      flex: initial;
      border-right: 0;
      border-bottom: 1px solid var(--chrome-hair);
    }

    .groups {
      gap: var(--space-3);
      padding-bottom: var(--space-3);
    }

    .group-label {
      padding-left: var(--space-4);
    }

    .items {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1);
      padding: 0 var(--space-4);
    }

    .item {
      width: auto;
      padding: var(--space-1) var(--space-2);
    }

    .footer {
      margin-top: 0;
    }
  }

  /* The active item can change under the reader (a route resolves, a poll lands). For anyone
     who has asked for less motion, that change is instant rather than a crossfade. */
  @media (prefers-reduced-motion: reduce) {
    .item {
      transition: none;
    }
  }
</style>
