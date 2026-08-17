<script lang="ts">
  /*
   * THE FIRST SCREEN. Who each surface is for, and where this person belongs.
   *
   * WHAT IT REPLACES. Six flat tabs — "Open a backup file", "Connect to sync", "Self-checks",
   * "Build a tool", "Owner console", "Recover access" — with no hierarchy and no statement of
   * audience. A clinician landing here had nothing telling them their portal is a different page;
   * an owner had six equal-weight verbs and no way to tell which two they had come for.
   *
   * THIN BY CONSTRUCTION. Every sentence on this screen comes from lib/onboarding/audience.ts,
   * which is pure and tested — including the headings and the button labels, which live in LABELS
   * there rather than here. That is not tidiness: audience.test.ts walks that module's strings and
   * asserts the register over all of them (no cheer, no claim of health, no figure, no
   * configuration subject the server keeps to itself). A heading authored in this file would be
   * the one phrase on the page outside every one of those checks — the hole lib/admin/health.ts
   * found when its chip words were still living in AdminConsole.svelte. This file fetches two
   * URLs, holds five pieces of state, and renders.
   *
   * WHAT IT ASKS THE SERVER, AND WHAT IT REFUSES TO. Exactly GET /healthz and GET /readyz, via
   * orientationEndpoints(). Both are unauthenticated and answer anyone who can reach the app, so
   * restating them discloses nothing new — which is the whole test, because index.html has no
   * sign-in and admin.html has no credential to hide anything behind. Nothing under /v1 is
   * touched: with sync configured those paths run AuthGuard, and an anonymous request records a
   * failed attempt against the caller's source, so a probe on page load could walk the owner into
   * their own lockout. The reasoning is written out beside ORIENTATION_PROBES.
   *
   * WHY THERE IS NO TICK AND NO "ALL GOOD". A server that answers both probes gets a neutral
   * statement of what was observed, paired with what it does not establish. Two endpoints
   * answering says nothing about whether sync is set up, whether a token still works, or whether
   * anything sent would be kept — and this page cannot find out without asking something it is
   * not allowed to ask. Reassurance here is the absence of a callout, not a friendlier one
   * (app.css invariant 2).
   *
   * WHY `now` IS A PROP WITH A TICKING FALLBACK, NEVER A Date.now() INSIDE A DERIVATION.
   * `$derived` re-evaluates on tracked dependency change only, and a clock read inside a callee is
   * invisible to it — the bug that shipped in this codebase's therapist portal, where an idle
   * guard silently stopped ticking. The staleness line reads `clock`, which is either the caller's
   * `now` or a $state an interval advances, and describeLastChecked takes it as an argument. The
   * interval only runs when there is a reading to age, so the first screen does not hold a timer
   * for nothing.
   *
   * WHAT IT WRITES TO THE BROWSER. One entry, holding one integer: the orientation version that
   * was dismissed. Not the audience, not a name, not a date. The reasoning, the comparison with
   * lib/therapist/pinStore.ts, and why this one fails OPEN where that one fails closed are in
   * audience.ts; WHAT_IS_STORED says the same thing to the person, on the page.
   */
  import { untrack } from 'svelte'
  import { Callout, Card, Chip } from '../ui'
  import { describeLastChecked, readProbe, type ProbeReading } from '../../admin/health'
  import {
    AUDIENCES,
    COMPACT_SUMMARY,
    LABELS,
    NEEDS_LABEL,
    ORIENTATION_LEDE,
    ORIENTATION_TITLE,
    PROBES_ARE_PUBLIC,
    REACH_WORD,
    STORAGE_REFUSED,
    WHAT_IS_STORED,
    SERVED_BY_THE_SERVER,
    WHAT_THIS_PAGE_IS,
    WHY_SO_FEW_CHECKS,
    defaultOrientationStorage,
    forgetDismissal,
    orientationEndpoints,
    orientationView,
    rankOwnerRoutes,
    readDismissedVersion,
    readServerReach,
    rememberDismissal,
    routeNoteFor,
    showsReachWhenCompact,
    type OrientationStorage,
    type OrientationView,
    type OwnerRouteId,
    type ReachState,
  } from '../../onboarding/audience'

  let {
    /**
     * Origin prefix for the two probes. Both are registered at the server root rather than under
     * DAYMARK_BASE_PATH (Application.kt), so the default of "same origin, absolute path" is right
     * unless this screen is served from elsewhere.
     */
    baseUrl = '',
    fetchImpl = typeof fetch === 'function' ? fetch.bind(globalThis) : undefined,
    /** Injectable so tests and non-browser hosts do not reach for localStorage. */
    storage = defaultOrientationStorage(),
    /** The clock, when the host has one. Left undefined, this ticks its own. See the header note. */
    now = undefined,
    /** The owner route currently open, so the chosen one carries aria-pressed. */
    selected = undefined,
    /** How the host switches surface. Absent, the routes render as inert description. */
    onchoose = undefined,
    /**
     * Whether the operator console is linked or only named. It holds no credential on this build,
     * so a deployment that does not want it one click from a public page can turn the link off
     * without the screen going quiet about the console's existence.
     */
    adminLink = false,
  }: {
    baseUrl?: string
    fetchImpl?: typeof fetch
    storage?: OrientationStorage | null
    now?: number
    selected?: OwnerRouteId
    onchoose?: (id: OwnerRouteId) => void
    adminLink?: boolean
  } = $props()

  let readings = $state<ProbeReading[]>([])
  let lastCheckedAt = $state<number | null>(null)
  let ticked = $state(Date.now())
  /*
   * Read ONCE, at mount, and `untrack` says so rather than leaving it to be inferred. This is
   * mutable state a person edits with the two buttons below, so it cannot be a `$derived` of
   * storage — and without the marker the compiler warns, correctly, that a later change to the
   * `storage` prop would not be picked up. It should not be: re-reading storage mid-session would
   * reopen a panel someone had just closed.
   */
  let view = $state<OrientationView>(
    untrack(() => orientationView(readDismissedVersion(storage))),
  )
  let storageRefused = $state(false)

  const CLOCK_MS = 15_000

  /* Only while there is a reading to age. See the header note on `now`. */
  $effect(() => {
    if (now !== undefined || lastCheckedAt === null) return
    const id = setInterval(() => (ticked = Date.now()), CLOCK_MS)
    return () => clearInterval(id)
  })

  $effect(() => {
    if (!fetchImpl) return
    void check()
  })

  async function check() {
    if (!fetchImpl) return
    readings = await Promise.all(
      orientationEndpoints().map(async (ep): Promise<ProbeReading> => {
        try {
          const res = await fetchImpl(`${baseUrl}${ep.path}`, {
            headers: { accept: 'application/json' },
            cache: 'no-store',
          })
          return readProbe(ep.id, { kind: 'response', status: res.status, body: await res.text() })
        } catch (e) {
          return readProbe(ep.id, {
            kind: 'transport',
            error: e instanceof Error ? `${e.name}: ${e.message}` : String(e),
          })
        }
      }),
    )
    lastCheckedAt = Date.now()
  }

  function dismiss() {
    // The panel collapses either way. Whether the browser kept the note only decides whether the
    // person is told it will be back.
    storageRefused = !rememberDismissal(storage)
    view = 'compact'
  }

  function reopen() {
    // `forgetDismissal` returns false when the browser refused the removal, and it was built to
    // return that specifically "so the caller can say the record is still there rather than
    // claiming a deletion that did not happen". Discarding it — and then clearing the warning —
    // did claim it: WHAT_IS_STORED promises "choosing to show the orientation again removes it",
    // and a private-mode or storage-disabled browser would have left the entry in place while the
    // screen said it was gone. The one honest signal the module produces is now the one the screen
    // shows.
    storageRefused = !forgetDismissal(storage)
    view = 'full'
  }

  const groups = rankOwnerRoutes()

  const clock = $derived(now ?? ticked)
  const staleness = $derived(describeLastChecked(lastCheckedAt, clock))
  const reach = $derived(readServerReach(readings))
  /* Bad news survives a dismissal; the two uninteresting states collapse with the panel. */
  const showReach = $derived(view === 'full' || showsReachWhenCompact(reach))

  /*
   * State to tone. Neutral is the resting tone AND the answered tone — the absence of something to
   * say is the only reassurance this system offers, so "answered" gets the same chip as "not
   * checked" rather than a warmer one.
   */
  const REACH_TONE: Record<ReachState, 'neutral' | 'warn' | 'critical'> = {
    'not-checked': 'neutral',
    answering: 'neutral',
    refusing: 'critical',
    'no-answer': 'warn',
    undocumented: 'warn',
  }

  const REACH_CALLOUT: Record<ReachState, 'info' | 'warn' | 'critical'> = {
    'not-checked': 'info',
    answering: 'info',
    refusing: 'critical',
    'no-answer': 'warn',
    undocumented: 'warn',
  }
</script>

<!--
  AN h2, NOT PageHeader — because this panel does not own the page.

  PageHeader always emits an <h1>, and App.svelte already renders `<h1>Daymark Companion</h1>` in
  its topbar with this component mounted directly beneath it. Using PageHeader here put the product
  name twice on one screen, one element apart, in the same display serif, and gave a screen-reader
  outline two top-level headings with identical text — so the landmark meant to orient someone was
  the thing making the page ambiguous to navigate.

  The rest of this file already reasons about not skipping h2 → h3; it just assumed it owned the
  h1. It does not, so it starts at h2 and the nesting below stays correct.
-->
<section class="orientation" aria-labelledby="orientation-heading">
  <div class="head">
    <h2 id="orientation-heading">{ORIENTATION_TITLE}</h2>
    <div class="head-side">
      {#if lastCheckedAt !== null}<Chip tone="neutral">{staleness}</Chip>{/if}
      {#if view === 'full'}
        <button class="action" type="button" onclick={dismiss}>{LABELS.dismiss}</button>
      {:else}
        <button class="action" type="button" onclick={reopen}>{LABELS.reopen}</button>
      {/if}
    </div>
  </div>

  {#if view === 'full'}
    <p class="lede">{ORIENTATION_LEDE}</p>

    <!--
      No aria-label on these sections: each already opens with a heading, and a label duplicating
      it would have a screen reader announce the same words twice before the content.
    -->
    <section class="block">
      <h2 class="section-title">{LABELS.audiences}</h2>
      <ul class="audiences">
        {#each AUDIENCES as audience (audience.id)}
          <!--
            THE OPERATOR ENTRY IS WITHHELD WHOLE, NOT JUST ITS LINK.

            `adminLink={false}` used to suppress the anchor and still print the condition beneath
            it — which reads, to any anonymous visitor of an unauthenticated page: "that console
            asks for no credential of its own … so anyone who can reach it can open it". Withholding
            the href while printing the sentence that makes it worth finding is not withholding
            anything; the filename is a vite entry in the public source, one request away.

            So the flag now governs the entry. It is minor as disclosures go — admin.html is
            already served by staticFiles and says the same thing about itself — but a deployment
            should have to choose to advertise an unguarded ops console, which is why the default
            is closed rather than open.
          -->
          {#if audience.id !== 'operator' || adminLink}
            <li class="audience" class:here={audience.href === null}>
              <p class="question">{audience.question}</p>
              <!--
                `who` and `entryCondition` are the honest detail and they are not what someone
                needs in order to choose. Folded, so the three questions read as three questions
                rather than as three paragraphs.
              -->
              <details class="more">
                <summary>{LABELS.whatThisMeans}</summary>
                <p class="who">{audience.who}</p>
                <p class="condition">{audience.entryCondition}</p>
              </details>
              <p class="destination">
                {#if audience.href === null}
                  <Chip tone="accent">{LABELS.youAreHere}</Chip>
                {:else}
                  <a class="go" href={audience.href}>{LABELS.open} {audience.linkLabel}</a>
                {/if}
              </p>
            </li>
          {/if}
        {/each}
      </ul>
    </section>
  {:else}
    <!--
      The compact header. It is not a shorter orientation, it is the one fact the panel existed to
      deliver — there are three pages and this is one of them — with the other two still one click
      away. The routes below stay exactly as they are, because they are the navigation and a
      returning person still has to choose one.
    -->
    <p class="compact">
      {COMPACT_SUMMARY}
      {#each AUDIENCES.filter((a) => a.href !== null) as audience (audience.id)}
        {#if audience.id !== 'operator' || adminLink}
          <a class="go" href={audience.href}>{LABELS.open} {audience.linkLabel}</a>
        {/if}
      {/each}
    </p>
  {/if}

  <!--
    The heading stays in BOTH views. Dropping it in compact saved four words and skipped a heading
    level — h1 straight to the group's h3 — which is a hole in the outline that heading navigation
    relies on. What compact actually saves is the audience cards, the group notes and the closing
    panels; the navigation itself is what a returning person came for.
  -->
  <section class="block">
    <h2 class="section-title">{LABELS.routes}</h2>
    {#each groups as group (group.group)}
      <div class="group">
        <h3 class="group-title">{group.heading}</h3>
        <!-- The group heading already says what the group is; the note is elaboration. -->
        <ul class="routes">
          {#each group.routes as route (route.id)}
            {@const note = routeNoteFor(route, reach)}
            <li>
              <button
                class="route"
                class:active={selected === route.id}
                type="button"
                aria-pressed={selected === route.id}
                disabled={!onchoose}
                onclick={() => onchoose?.(route.id)}
              >
                <span class="route-head">
                  <span class="route-label">{route.label}</span>
                  <Chip tone="neutral">{NEEDS_LABEL[route.needs]}</Chip>
                </span>
                <span class="route-blurb">{route.blurb}</span>
                {#if note}<span class="route-note">{note}</span>{/if}
              </button>
            </li>
          {/each}
        </ul>
      </div>
    {/each}
  </section>

  {#if showReach}
    <section class="block">
      <Card title={LABELS.reach}>
        {#snippet header()}
          <Chip tone={REACH_TONE[reach.state]}>{REACH_WORD[reach.state]}</Chip>
        {/snippet}

        <p class="para">{reach.statement}</p>

        <!-- The limitation is body text under the claim, not small print beside it. -->
        <Callout tone={REACH_CALLOUT[reach.state]} title={LABELS.reachLimits}>
          <p class="para">{reach.silentOn}</p>
        </Callout>

        {#if view === 'full'}
          <details class="why-details">
            <summary>{LABELS.whySoFew}</summary>
            <p class="para why">{WHY_SO_FEW_CHECKS}</p>
          </details>
        {/if}

        <div class="controls">
          <button class="action" type="button" onclick={() => void check()} disabled={!fetchImpl}>
            {LABELS.recheck}
          </button>
        </div>

        {#snippet footer()}
          <!-- Folded with the rest of the explanation: it says why the two readings are safe to
               show, which is worth having and is not worth reading first. -->
          <details class="why-details">
            <summary>{LABELS.whyPublic}</summary>
            <p class="para">{PROBES_ARE_PUBLIC}</p>
          </details>
        {/snippet}
      </Card>
    </section>
  {/if}

  {#if storageRefused}
    <Callout tone="warn" title={LABELS.storageRefused}>
      <p class="para">{STORAGE_REFUSED}</p>
    </Callout>
  {/if}

  {#if view === 'full'}
    <!--
      COLLAPSED BY DEFAULT, and that is the point rather than a detail.

      This was two paragraphs of prose sitting open on the first screen. The maintainer's reaction
      to the result was "SOOO much text it's just so hard to get it all", followed by the fix:
      "more of like a self check, that you can expand ... but this should be like tucked away not
      front and center". None of it was wrong; all of it was in the way.

      A native <details> rather than a scripted disclosure: it is keyboard-reachable, exposed to
      screen readers as a real expandable, findable by in-page search even while closed, and works
      before any JavaScript runs. A hand-rolled version would have to earn all four back.
    -->
    <section class="block">
      <details class="selfcheck">
        <summary>{LABELS.runsHere}</summary>
        <div class="selfcheck-body">
          <p class="para">{WHAT_THIS_PAGE_IS}</p>

          <!--
            The limitation that was nowhere on this page and belongs here more than anything else
            on it: this viewer is delivered BY the server it protects you from. See
            docs/PLAN_2026-08-COMPANION-NEXT.md §1.2.
          -->
          <h3 class="group-title">{LABELS.servedBy}</h3>
          <p class="para">{SERVED_BY_THE_SERVER}</p>

          <h3 class="group-title">{LABELS.stored}</h3>
          <p class="para">{WHAT_IS_STORED}</p>
        </div>
      </details>
    </section>
  {/if}
</section>

<style>
  /* The panel's own header row. Mirrors PageHeader's arrangement without claiming its <h1>. */
  .head {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: space-between;
    gap: var(--space-3);
    margin-bottom: var(--space-3);
  }

  .head h2 {
    margin: 0;
    font-family: var(--font-display);
    color: var(--ink-text);
  }

  .head-side {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .orientation {
    display: flex;
    flex-direction: column;
    gap: var(--space-5);
  }

  .lede,
  .compact {
    margin: 0;
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.95rem;
    line-height: 1.6;
  }

  /* The compact header keeps its links inline with the sentence: after a dismissal the whole
     point is that the other two surfaces are still one reading-line away, not behind a control
     someone has to find. */
  .compact .go {
    margin-left: var(--space-3);
    white-space: nowrap;
  }

  .block {
    min-width: 0;
  }

  .section-title {
    font-family: var(--font-display);
    font-size: 1.1rem;
    font-weight: 560;
    line-height: 1.25;
    margin: 0 0 var(--space-3);
    color: var(--ink-text);
  }

  .group-title {
    font-family: var(--font-mono);
    font-size: 0.7rem;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
    margin: 0 0 var(--space-2);
  }

  /*
   * Disclosures. Deliberately quiet: a summary that looked like a button would pull the eye back
   * to the thing being tucked away, which is the opposite of the point. The marker is the only
   * affordance, and it is native.
   */
  details {
    margin: var(--space-2) 0 0;
  }

  summary {
    cursor: pointer;
    color: var(--link);
    font-size: 0.9em;
    /* A summary is focusable, so it needs a visible focus ring like any other control. */
    border-radius: var(--radius-sm);
  }

  summary:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  details[open] > summary {
    margin-bottom: var(--space-2);
  }

  .selfcheck-body > .para:first-child {
    margin-top: 0;
  }

  .group + .group {
    margin-top: var(--space-5);
  }

  .para {
    margin: 0 0 var(--space-3);
    color: var(--ink-text);
    font-size: 0.95rem;
    line-height: 1.6;
    max-width: 44rem;
  }
  .para:last-child {
    margin-bottom: 0;
  }

  /* The refusal to build a configuration checklist, set as prose rather than as a footnote: a
     reader who came looking for one has to be able to read why there is not one. */
  .why {
    color: var(--ink-soft);
    font-size: 0.88rem;
  }

  /* ---- The three audiences ------------------------------------------------ */

  .audiences,
  .routes {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  /* Three columns where there is room, stacked where there is not. Equal width on purpose: the
     owner's card is emphasised by ink and by being first, not by being wider — a clinician
     reading a visibly demoted card is being told they matter less rather than that they are in
     the wrong place. */
  .audiences {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
    gap: var(--space-3);
  }

  .audience {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    padding: var(--space-4);
    background: var(--paper-sheet);
    border: 1px solid var(--hairline);
    border-radius: var(--radius);
    min-width: 0;
  }

  /* The page you are on. Structural accent, because "where am I" is structure — never ink, which
     stays reserved for the primary action on a screen. */
  .audience.here {
    border-color: var(--indigo);
    box-shadow: var(--elevation);
  }

  .question {
    margin: 0;
    font-family: var(--font-display);
    font-size: 1.05rem;
    font-weight: 560;
    line-height: 1.3;
    color: var(--ink-text);
    overflow-wrap: anywhere;
  }

  .who {
    margin: 0;
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.55;
  }

  /* The entry condition is set at reading weight rather than as a caption. For a clinician it is
     the single most useful sentence on the page — that they cannot get in without an invitation —
     and a caption is exactly what a person skims past. */
  .condition {
    margin: 0;
    color: var(--ink-text);
    font-size: 0.88rem;
    line-height: 1.55;
  }

  .destination {
    margin: auto 0 0;
    padding-top: var(--space-2);
  }


  .go {
    color: var(--link);
    font-size: 0.9rem;
  }

  /* ---- The owner's entry points ------------------------------------------- */

  .routes {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(17rem, 1fr));
    gap: var(--space-3);
  }

  /* A whole card is the target, not a verb inside one. The six used to be tab-sized buttons whose
     labels were all a person had to go on; here the phrase and the sentence under it are both
     part of the thing you click. */
  .route {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
    width: 100%;
    height: 100%;
    padding: var(--space-4);
    text-align: left;
    background: var(--paper-sheet);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius);
    font-family: var(--font-text);
    cursor: pointer;
  }

  .route:hover:not(:disabled) {
    border-color: var(--indigo);
  }

  .route:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  /* Rendered without a host to switch surface: the cards stay readable as description rather than
     pretending to be controls that do nothing. */
  .route:disabled {
    cursor: default;
    border-color: var(--hairline);
  }

  /* The chosen one is STRUCTURE — "where am I" — so it takes the structural accent, matching
     every other selection in this product. aria-pressed carries the same fact without colour. */
  .route.active {
    background: var(--indigo-wash);
    border-color: var(--indigo);
  }

  .route-head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .route-label {
    font-family: var(--font-display);
    font-size: 1rem;
    font-weight: 560;
    line-height: 1.3;
    color: var(--ink-text);
  }

  .route-blurb {
    color: var(--ink-soft);
    font-size: 0.85rem;
    line-height: 1.55;
  }

  /* Only ever present on a route that talks to the server, and only when there is something to
     say. Amber is warn severity: this route may not do what it says right now. It is never a
     badge in the other direction — there is no mark meaning "this one will work". */
  .route-note {
    align-self: stretch;
    margin-top: auto;
    padding: var(--space-2) var(--space-3);
    background: var(--amber-wash);
    border-left: 3px dashed var(--amber);
    border-radius: var(--radius-sm);
    color: var(--amber);
    font-size: 0.8rem;
    line-height: 1.45;
  }

  /* ---- Controls ----------------------------------------------------------- */

  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-top: var(--space-3);
  }

  .action {
    padding: 0.3rem var(--space-3);
    background: var(--chrome-2);
    color: var(--chrome-ink);
    border: 1px solid var(--chrome-hair);
    border-radius: var(--radius-sm);
    font-family: var(--font-text);
    font-size: 0.85rem;
    line-height: 1.5;
    cursor: pointer;
  }

  .action:hover:not(:disabled) {
    background: var(--chrome);
  }

  .action:disabled {
    cursor: not-allowed;
    color: var(--chrome-soft);
  }

  .action:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  @media (max-width: 34rem) {
    .audiences,
    .routes {
      grid-template-columns: 1fr;
    }
    .compact .go {
      display: inline-block;
      margin: var(--space-2) var(--space-3) 0 0;
    }
  }
</style>
