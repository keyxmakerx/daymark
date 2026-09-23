<script lang="ts">
  /*
   * The server operator's console. A separate route, a separate audience, and a deliberately
   * narrow subject: operational health, authentication pressure, and audit-chain integrity.
   *
   * WHAT IT IS NOT. It is not the owner's console. The relationship audit log — who opened which
   * share, when — is owner-readable, and the only route that serves it demands the relationship
   * inbox token and the owner bearer token together, neither of which a server administrator
   * holds. That is a boundary worth keeping rather than a gap worth routing around, so this
   * screen names it and stops there. Nothing here renders who shared what with whom.
   *
   * THE ONE PANEL THAT ASKS FOR A CREDENTIAL is the server-side chain check, and the credential
   * is the OWNER's bearer token, typed by the person who holds it and kept only in its field.
   * The console still presents nothing of its own; the panel exists because on a self-hosted box
   * the operator and the owner are usually the same person, and the head digest that check
   * returns is the value worth writing down (lib/admin/chainHead.ts says why, at length). The
   * response carries counts, sequence extents and one hash — never an entry — so this screen
   * still renders nothing of what the log records.
   *
   * WHY THERE IS NO NUMBER AT THE TOP. No overall figure, no percentage, no shield. The reasoning
   * is rendered on the page (NO_SINGLE_FIGURE) rather than hidden in this comment, because an
   * operator who expects a dashboard number and does not find one will go looking for it.
   *
   * WHY THE BAD NEWS IS AT THE TOP. Two facts about this build are worse than anything the probes
   * can report — passkey sign-in is a 501 stub, and the TOTP seed is stored in the clear — and
   * both are conditions of the deployment rather than events. Putting them under the fold, or in
   * a changelog, would mean an operator learns them from an incident. They sit above the
   * readings, they do not clear, and they are shown every time this page is opened. Each one is
   * a <details> that renders OPEN and can be folded: the fact stays on the page — title,
   * severity and all — and only its explanation can be put away by someone who has read it.
   * Nothing here remembers the fold, so the next open of the page shows everything again.
   *
   * WHY THE FACTS ARE FLAT AND NOT BUBBLES. The design notes define a callout as an accent
   * keyline on the left plus a faint tint — flat and factual, not a rounded banner. A red
   * heading on a red-brown fill reads as an alarm going off, and these are not events; they are
   * conditions. So the panel is a 2px keyline in the severity hue (dashed for warn, solid for
   * critical, so the difference survives greyscale), the matching wash as the tint, and the
   * ordinary ink for the heading. A visually hidden severity word says the same thing to a
   * screen reader that the keyline says to a sighted one, so the hue is never the only signal.
   *
   * WHY THERE IS AN INDEX. The page is several screens of prose and the only control at the
   * top was "Re-read probes"; an operator looking for the chain check had to scroll for it. The
   * section list is a plain <nav> of anchors to the six sections: under the title on a narrow
   * window, a sticky rail beside the content on a wide one. It does not track scroll position
   * — that would be motion, and a guess — it is a table of contents and nothing more.
   *
   * WHY IT IS DARK IN BOTH THEMES. app.css remaps the palette at `:root[data-theme="dark"]`, so
   * the only way to pin one surface dark without adding tokens is to pin the attribute on the
   * document element — which is correct here precisely because this is a SEPARATE ENTRY POINT,
   * not a tab inside another app. The effect restores whatever was there on teardown, so mounting
   * this component inside a test harness or a portal cannot leave the attribute behind. The
   * route's own HTML should carry `data-theme="dark"` as well, so the first paint is already dark
   * rather than flashing light and correcting itself.
   *
   * WHY `now` IS A TICKING $state AND NOT A Date.now() INSIDE A DERIVATION. `$derived`
   * re-evaluates on tracked dependency change only; a clock read inside a callee is invisible to
   * it, and that exact bug shipped in this codebase's therapist portal, where an idle guard
   * silently stopped ticking. The staleness line reads a $state clock that an interval advances,
   * and describeLastChecked takes it as an argument.
   *
   * THIN BY CONSTRUCTION. Every sentence on this page comes from lib/admin/health.ts or
   * lib/admin/chainHead.ts, both pure and tested. This file fetches four URLs, holds state and
   * renders. If a word needs changing, it changes there, where a test is watching it.
   */
  import { Callout, Card, Chip, EmptyState, PageHeader } from '../ui'
  import {
    AUTH_PRESSURE,
    AUTH_PRESSURE_HEADLINE,
    CHAIN_NOT_ADMIN_READABLE,
    CHAIN_VERDICT_WORD,
    CONSOLE_COVERS,
    CONSOLE_WITHHELD,
    CREDENTIAL_POSTURE,
    ENDPOINTS,
    NO_SINGLE_FIGURE,
    PROBE_STATE_WORD,
    STANDING_FACTS,
    STANDING_FACTS_HEADLINE,
    chainUnavailable,
    describeLastChecked,
    parseChainExport,
    readProbe,
    verifyChainRun,
    type ChainReport,
    type ProbeReading,
    type StandingFact,
  } from '../../admin/health'
  import {
    CHAIN_HEAD_GATE,
    fetchChainHead,
    readChainHead,
    type ChainHeadView,
  } from '../../admin/chainHead'
  import { sha256Hex } from '../../admin/sha256'

  let {
    /**
     * Origin prefix for the probes. All three live at the server root — /healthz and /readyz are
     * registered outside the base path on purpose, and /v1/config alongside them — so the default
     * of "same origin, absolute path" is right unless the console is served from elsewhere.
     */
    baseUrl = '',
    pollMs = 30_000,
    fetchImpl = typeof fetch === 'function' ? fetch.bind(globalThis) : undefined,
    /**
     * Synchronous lowercase-hex SHA-256 for the chain examiner. The default is the real thing
     * (task #16): lib/admin/sha256.ts, proven against the published vectors and node:crypto in
     * its own suite, so a pasted run has its entry hashes actually recomputed. The prop remains
     * injectable for harnesses; whatever is supplied, the report still lists what was and was
     * not established, so the words track the check rather than assuming it.
     */
    digest = sha256Hex,
  }: {
    baseUrl?: string
    pollMs?: number
    fetchImpl?: typeof fetch
    digest?: (input: string) => string
  } = $props()

  let readings = $state<ProbeReading[]>([])
  let lastCheckedAt = $state<number | null>(null)
  let now = $state(Date.now())

  let pasted = $state('')
  let relRef = $state('')
  let parseProblem = $state('')
  let chain = $state<ChainReport>(chainUnavailable(CHAIN_NOT_ADMIN_READABLE))

  /*
   * The server-side chain check. The token lives in this state for exactly as long as the page
   * is open and goes exactly one place: the Authorization header of the one request
   * fetchChainHead makes. It is never persisted, never logged, and never rendered back.
   */
  let ownerToken = $state('')
  let headRelRef = $state('')
  let head = $state<ChainHeadView | null>(null)
  let fetchingHead = $state(false)

  /* Pin the surface dark, and put back whatever was there when this unmounts. */
  $effect(() => {
    const root = document.documentElement
    const previous = root.getAttribute('data-theme')
    root.setAttribute('data-theme', 'dark')
    return () => {
      if (previous === null) root.removeAttribute('data-theme')
      else root.setAttribute('data-theme', previous)
    }
  })

  /* The clock the staleness line reads. See the header note. */
  $effect(() => {
    const id = setInterval(() => (now = Date.now()), 1_000)
    return () => clearInterval(id)
  })

  $effect(() => {
    if (!fetchImpl) return
    void check()
    const id = setInterval(() => void check(), pollMs)
    return () => clearInterval(id)
  })

  async function check() {
    if (!fetchImpl) return
    readings = await Promise.all(
      ENDPOINTS.map(async (ep): Promise<ProbeReading> => {
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

  function examine() {
    const parsed = parseChainExport(pasted)
    if (parsed.kind === 'unreadable') {
      parseProblem = parsed.problem
      chain = chainUnavailable(CHAIN_NOT_ADMIN_READABLE)
      return
    }
    parseProblem = ''
    chain = verifyChainRun({
      entries: parsed.entries,
      relRef: relRef.trim() === '' ? undefined : relRef.trim(),
      digest,
    })
  }

  function clearRun() {
    pasted = ''
    parseProblem = ''
    chain = chainUnavailable(CHAIN_NOT_ADMIN_READABLE)
  }

  /* One request, one view. fetchChainHead never throws — a transport failure is a value the
     reader renders like any other answer — so there is no error path here to forget. */
  async function fetchHead() {
    if (!fetchImpl || fetchingHead) return
    fetchingHead = true
    try {
      head = readChainHead(await fetchChainHead({ baseUrl, token: ownerToken }, headRelRef.trim(), fetchImpl))
    } finally {
      fetchingHead = false
    }
  }

  function clearHead() {
    ownerToken = ''
    headRelRef = ''
    head = null
  }

  /*
   * State to chip tone. No tone means "fine" here — `responding` is neutral, because the absence
   * of something to say is the only reassurance this system offers.
   */
  const STATE_TONE: Record<ProbeReading['state'], 'neutral' | 'warn' | 'critical'> = {
    responding: 'neutral',
    refusing: 'critical',
    unreachable: 'critical',
    unexpected: 'warn',
  }

  const VERDICT_TONE: Record<ChainReport['verdict'], 'neutral' | 'warn' | 'critical'> = {
    'internally-consistent': 'neutral',
    'incomplete-run': 'warn',
    contradicted: 'critical',
    'nothing-returned': 'neutral',
    unavailable: 'neutral',
  }

  /* Same rule as the two maps above: a break and a dead transport alarm, a refusal warns, and
     everything the server answered as documented — the no-break report included — stays neutral,
     because the absence of something to say is the only reassurance on this screen. */
  const HEAD_TONE: Record<ChainHeadView['verdict'], 'neutral' | 'warn' | 'critical'> = {
    'reported-consistent': 'neutral',
    'break-reported': 'critical',
    'nothing-recorded': 'neutral',
    'not-configured': 'neutral',
    refused: 'warn',
    unreachable: 'critical',
    unexpected: 'warn',
  }


  /*
   * The in-page index: one entry per section, in page order. The ids are the anchors the
   * sections below carry, and the labels are the section headings, shortened where a heading is
   * a sentence. AdminConsole.test.ts checks that every id here lands on a section in the markup.
   */
  const SECTIONS: readonly { id: string; label: string }[] = [
    { id: 'standing-facts', label: 'Standing facts' },
    { id: 'operational-health', label: 'Operational health' },
    { id: 'authentication-pressure', label: 'Authentication pressure' },
    { id: 'audit-chain-integrity', label: 'Audit-chain integrity' },
    { id: 'server-chain-check', label: 'The server’s own chain check' },
    { id: 'scope', label: 'What this console covers' },
  ]

  /* Announced to assistive tech, never drawn — the same words Callout uses, so a fact folded
     into a <details> loses nothing a screen reader was told when it was a Callout. */
  const FACT_SEVERITY: Record<StandingFact['tone'], string> = {
    warn: 'Warning',
    critical: 'Needs attention',
  }

  let staleness = $derived(describeLastChecked(lastCheckedAt, now))
</script>

<div class="console">
  <div class="page">
    <!-- The shared masthead, as App.svelte and SignInScreen render it. The wordmark is a <p>
         rather than the <h1> App.svelte uses, because PageHeader below already emits this
         page's <h1>: two top-level headings one element apart make the outline ambiguous to
         navigate by exactly the landmark meant to orient someone (Orientation.svelte). -->
    <div class="brand">
      <span class="mark" aria-hidden="true"></span>
      <div>
        <p class="wordmark">Daymark Companion</p>
        <p class="muted tagline">Server operations</p>
      </div>
    </div>

    <PageHeader title="Admin console">
      <Chip tone="neutral">{staleness}</Chip>
      {#snippet trailing()}
        <button class="action" type="button" onclick={() => void check()} disabled={!fetchImpl}>
          Re-read probes
        </button>
      {/snippet}
    </PageHeader>

    <div class="layout">
      <!-- The index. Under the title on a narrow window, a sticky rail on a wide one; the
           visible micro-label repeats the landmark's name, so it is hidden from the reader
           who already gets it from aria-label. -->
      <nav class="sections" aria-label="Sections">
        <p class="sections-label" aria-hidden="true">Sections</p>
        <ul>
          {#each SECTIONS as section (section.id)}
            <li><a href={`#${section.id}`}>{section.label}</a></li>
          {/each}
        </ul>
      </nav>

      <div class="main">
      <p class="lede">{NO_SINGLE_FIGURE}</p>

      <!-- The known-bad news, above the readings rather than under them. Each fact is open on
           first render and can be folded; the title and severity stay on the page either way. -->
      <section class="block" id="standing-facts" aria-label="Standing facts">
        <h2 class="section-title">Standing facts about this deployment</h2>
        <p class="section-lede">{STANDING_FACTS_HEADLINE}</p>
        <div class="stack">
          {#each STANDING_FACTS as fact (fact.id)}
            <details class="fact" data-tone={fact.tone} open>
              <summary class="fact-summary">
                <span class="visually-hidden">{FACT_SEVERITY[fact.tone]}:</span>
                <h3 class="fact-title">{fact.title}</h3>
              </summary>
              <div class="fact-body">
                <p class="para">{fact.body}</p>
                <p class="para">{fact.consequence}</p>
                <p class="evidence">Read it at: {fact.evidence}</p>
              </div>
            </details>
          {/each}
        </div>
      </section>

      <section class="block" id="operational-health" aria-label="Operational health">
        <Card title="Operational health">
          {#snippet header()}
            <Chip tone="neutral">{ENDPOINTS.length} endpoints</Chip>
          {/snippet}

          {#if readings.length === 0}
            <EmptyState title="No readings">
              {#if fetchImpl}
                <p class="para">
                  The probes have not answered. This panel is drawn from the three
                  endpoints as each responds, and re-reads every {Math.round(pollMs / 1000)} seconds.
                </p>
              {:else}
                <p class="para">
                  This console was mounted without a way to make requests, so no endpoint was asked.
                  Nothing on this panel is a statement about the server.
                </p>
              {/if}
            </EmptyState>
          {:else}
            <ul class="readings">
              {#each readings as reading (reading.id)}
                <li class="reading">
                  <div class="reading-head">
                    <span class="reading-label">{reading.label}</span>
                    <code class="endpoint">{reading.endpoint}</code>
                    <Chip tone={STATE_TONE[reading.state]}>{PROBE_STATE_WORD[reading.state]}</Chip>
                  </div>
                  <p class="observed">{reading.observed}</p>
                  {#if reading.machineDetail}
                    <pre class="machine">{reading.machineDetail}</pre>
                  {/if}
                  <dl class="limits">
                    <dt>Establishes</dt>
                    <dd>{reading.answers}</dd>
                    <dt>Does not establish</dt>
                    <dd>{reading.silentOn}</dd>
                  </dl>
                </li>
              {/each}
            </ul>
          {/if}

          {#snippet footer()}
            Every endpoint on this panel is unauthenticated and answers anyone who can reach the app.
          {/snippet}
        </Card>
      </section>

      <section class="block" id="authentication-pressure" aria-label="Authentication pressure">
        <Card title="Authentication pressure">
          <p class="para">{AUTH_PRESSURE_HEADLINE}</p>
          <ul class="gaps">
            {#each AUTH_PRESSURE as gap (gap.id)}
              <li class="gap">
                <span class="gap-subject">{gap.subject}</span>
                <p class="gap-statement">{gap.statement}</p>
                <p class="gap-held">Where the number lives: {gap.heldAt}</p>
              </li>
            {/each}
          </ul>
        </Card>
      </section>

      <section class="block" id="audit-chain-integrity" aria-label="Audit-chain integrity">
        <Card title="Audit-chain integrity">
          <Callout tone="info" title="Not readable from here">
            {CHAIN_NOT_ADMIN_READABLE}
          </Callout>

          <div class="examiner">
            <label class="field" for="admin-chain-run">
              <span class="field-label">A run of audit entries</span>
              <textarea
                id="admin-chain-run"
                class="paste"
                rows="6"
                spellcheck="false"
                placeholder={'{"events": [ … ]}  or  [ … ]'}
                bind:value={pasted}
              ></textarea>
            </label>

            <!-- No longer conditional on a digest being supplied: the console carries a real one
                 by default now (lib/admin/sha256.ts), so the reference field always has a job. -->
            <label class="field" for="admin-chain-relref">
              <span class="field-label">
                Relationship reference — part of the hashed content, so entry hashes cannot be
                recomputed without it
              </span>
              <input id="admin-chain-relref" class="text-input" type="text" bind:value={relRef} />
            </label>

            <div class="controls">
              <button class="action" type="button" onclick={examine}>Examine run</button>
              <button class="action" type="button" onclick={clearRun}>Clear</button>
            </div>

            {#if parseProblem}
              <Callout tone="critical" title="The run could not be read">
                <p class="para">{parseProblem}</p>
                <p class="para">
                  Nothing was examined. A run that cannot be parsed is refused rather than partially
                  read, so that no finding on this page is ever an artefact of a bad paste.
                </p>
              </Callout>
            {/if}
          </div>

          <div class="verdict">
            <div class="verdict-head">
              <Chip tone={VERDICT_TONE[chain.verdict]}>{CHAIN_VERDICT_WORD[chain.verdict]}</Chip>
              {#if chain.entriesExamined > 0}
                <span class="verdict-count">
                  {chain.entriesExamined} entries, sequence {chain.oldestSeq} to {chain.newestSeq}
                </span>
              {/if}
            </div>
            <p class="verdict-headline">{chain.headline}</p>

            <!-- Body text under the verdict, not small print beside it. -->
            <Callout tone="info" title="What this verdict does not say">
              {chain.caveat}
            </Callout>

            {#if chain.findings.length > 0}
              <h3 class="list-title">Findings</h3>
              <ul class="findings">
                {#each chain.findings as finding, i (`${finding.seq}-${finding.problem}-${i}`)}
                  <li>
                    <span class="finding-seq">Entry {finding.seq}</span>
                    <span class="finding-detail">{finding.detail}</span>
                  </li>
                {/each}
              </ul>
            {/if}

            {#if chain.checked.length > 0}
              <h3 class="list-title">Established by this run</h3>
              <ul class="notes">
                {#each chain.checked as note, i (i)}<li>{note}</li>{/each}
              </ul>
            {/if}

            <h3 class="list-title">Not established by this run</h3>
            <ul class="notes">
              {#each chain.notChecked as note, i (i)}<li>{note}</li>{/each}
            </ul>
          </div>

          {#snippet footer()}
            A run pasted here is examined in this browser tab and is not sent anywhere.
          {/snippet}
        </Card>
      </section>

      <section class="block" id="server-chain-check" aria-label="Server chain check">
        <Card title="The server’s own chain check">
          <!-- Whose token this is and what the gate protects, said BEFORE the fields that ask. -->
          <p class="para">{CHAIN_HEAD_GATE}</p>

          <div class="examiner">
            <label class="field" for="admin-head-relref">
              <span class="field-label">Relationship reference</span>
              <input
                id="admin-head-relref"
                class="text-input"
                type="text"
                spellcheck="false"
                autocomplete="off"
                bind:value={headRelRef}
              />
            </label>

            <label class="field" for="admin-head-token">
              <span class="field-label">
                Owner bearer token — sent once, to this server’s own route, and kept only in
                this field while the page is open
              </span>
              <input
                id="admin-head-token"
                class="text-input"
                type="password"
                autocomplete="off"
                bind:value={ownerToken}
              />
            </label>

            <div class="controls">
              <button
                class="action"
                type="button"
                onclick={() => void fetchHead()}
                disabled={!fetchImpl || fetchingHead || headRelRef.trim() === '' || ownerToken === ''}
              >
                Read the head
              </button>
              <button class="action" type="button" onclick={clearHead}>Clear</button>
            </div>
          </div>

          {#if head}
            <div class="verdict">
              <div class="verdict-head">
                <Chip tone={HEAD_TONE[head.verdict]}>{head.word}</Chip>
                {#if head.entryCount !== null && head.entryCount > 0}
                  <span class="verdict-count">
                    {head.entryCount} entries, sequence {head.oldestSeq} to {head.headSeq}
                  </span>
                {/if}
              </div>
              <p class="verdict-headline">{head.headline}</p>

              {#if head.headGroups.length > 0}
                <!-- The digest in reading groups, for copying down by hand — the same
                     four-character chunks the key-fingerprint ceremonies read aloud. -->
                <div class="digest" aria-label="Chain head digest, in reading groups">
                  {#each head.headGroups as group, i (i)}<span class="digest-group">{group}</span>{/each}
                </div>
              {/if}
              {#if head.headNote}
                <p class="para">{head.headNote}</p>
              {/if}

              {#if head.machineDetail}
                <pre class="machine">{head.machineDetail}</pre>
              {/if}

              <!-- Body text under the verdict, exactly as on the examiner above: the
                   qualification has to be as legible as the claim it qualifies. -->
              <Callout tone="info" title="What this verdict does not say">
                {head.caveat}
              </Callout>

              {#if head.notes.length > 0}
                <ul class="notes">
                  {#each head.notes as note, i (i)}<li>{note}</li>{/each}
                </ul>
              {/if}
            </div>
          {/if}

          {#snippet footer()}
            The response carries counts, sequence extents and one hash — never an audit entry.
          {/snippet}
        </Card>
      </section>

      <section class="block" id="scope" aria-label="Scope">
        <Card title="What this console covers, and what it declines to show" tone="quiet">
          <ul class="notes">
            {#each CONSOLE_COVERS as line, i (i)}<li>{line}</li>{/each}
          </ul>
          <h3 class="list-title">Withheld</h3>
          <ul class="gaps">
            {#each CONSOLE_WITHHELD as item (item.subject)}
              <li class="gap">
                <span class="gap-subject">{item.subject}</span>
                <p class="gap-held">{item.reason}</p>
              </li>
            {/each}
          </ul>
          <Callout tone="warn" title="This console holds no credential">
            {CREDENTIAL_POSTURE}
          </Callout>
        </Card>
      </section>
      </div>
    </div>
  </div>
</div>

<style>
  /* The page ground is painted explicitly rather than inherited: this route is pinned dark at the
     document element, and a transparent body would borrow whatever the host page was. */
  .console {
    min-height: 100vh;
    background: var(--paper-bg);
    color: var(--ink-text);
    font-family: var(--font-text);
  }

  .page {
    max-width: var(--maxw);
    margin: 0 auto;
    padding: var(--space-6) var(--space-5) var(--space-8);
  }

  /* ---- Masthead ---------------------------------------------------------- */

  .brand {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    margin-bottom: var(--space-5);
  }

  /* Structural accent, as in App.svelte: the mark says "Daymark", never anything about a day. */
  .mark {
    width: 2rem;
    height: 2rem;
    border-radius: 0.5rem;
    background: linear-gradient(135deg, var(--indigo), var(--indigo-deep));
    box-shadow: var(--elevation);
    flex: none;
  }

  /* The wordmark takes the serif (COMPANION_DESIGN_SYSTEM.md §2.2, "Where the serif goes"); the
     page title keeps the display face through PageHeader, which is the content voice naming its
     subject. */
  .wordmark {
    margin: 0;
    font-family: var(--font-display);
    font-weight: 560;
    font-size: 1.1rem;
    line-height: 1.2;
    color: var(--ink-text);
  }

  .tagline {
    margin: 0;
    font-size: 0.9rem;
  }

  /* ---- The index and the two-column layout ------------------------------- */

  .layout {
    display: flex;
    flex-direction: column;
    gap: var(--space-5);
  }

  .main {
    min-width: 0;
  }

  /* The chrome micro-label: a field name, not prose. */
  .sections-label {
    margin: 0 0 var(--space-2);
    font-family: var(--font-mono);
    font-size: 0.7rem;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
  }

  /* Narrow: a wrapped row of links under the title, so the index costs one or two lines. */
  .sections ul {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1) var(--space-4);
  }

  .sections a {
    color: var(--link);
    font-size: 0.85rem;
    line-height: 1.5;
    text-decoration: underline;
    text-underline-offset: 0.15em;
  }

  .sections a:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  /* Wide: a rail beside the content, sticky so it stays reachable through five screens of
     prose. Nothing here transitions or animates, so there is no motion to reduce. */
  @media (min-width: 60rem) {
    .layout {
      display: grid;
      grid-template-columns: 11rem minmax(0, 1fr);
      gap: var(--space-6);
      align-items: start;
    }

    .sections {
      position: sticky;
      top: var(--space-4);
    }

    .sections ul {
      flex-direction: column;
      gap: 0;
      border-left: 1px solid var(--hairline);
    }

    /* The same transparent 2px left edge on every item as NavRail draws, so a hovered link
       never nudges its neighbours sideways. */
    .sections a {
      display: block;
      margin-left: -1px;
      padding: var(--space-1) var(--space-3);
      border-left: 2px solid transparent;
      text-decoration: none;
    }

    .sections a:hover {
      border-left-color: var(--indigo);
      text-decoration: underline;
    }

    /* Inset, as on NavRail: an outward ring at the page edge is the one a keyboard user would
       have clipped. */
    .sections a:focus-visible {
      outline-offset: -2px;
    }
  }

  .lede {
    margin: 0 0 var(--space-6);
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.95rem;
    line-height: 1.6;
  }

  .block {
    margin-bottom: var(--space-6);
  }

  .section-title {
    font-family: var(--font-display);
    font-size: 1.1rem;
    font-weight: 560;
    line-height: 1.25;
    margin: 0 0 var(--space-2);
    color: var(--ink-text);
  }

  .section-lede {
    margin: 0 0 var(--space-4);
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.55;
  }

  .stack {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .para {
    margin: 0 0 var(--space-2);
  }
  .para:last-child {
    margin-bottom: 0;
  }

  /* ---- Standing facts ---------------------------------------------------- */

  /* The callout as the design notes define it: a 2px keyline on the left in the severity hue,
     the matching wash as a faint tint, square corners, ordinary ink for the heading. The warn
     edge is dashed and the critical edge solid, so the two read differently in greyscale; the
     hidden severity word in the summary says the same thing to a screen reader. */
  .fact {
    border-left: 2px dashed var(--amber);
    background: var(--amber-wash);
    padding: var(--space-3) var(--space-4);
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.55;
    max-width: 44rem;
  }

  .fact[data-tone='critical'] {
    border-left-style: solid;
    border-left-color: var(--danger);
    background: var(--danger-wash);
  }

  /* The summary is the fold control. The UA's disclosure marker stays: it is the one signal
     that this heading can be pressed, and hiding it would make the fold a secret. */
  .fact-summary {
    cursor: pointer;
    color: var(--ink-text);
  }

  .fact-summary:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  /* Inline so the marker and the title share a line in every engine; text face, not the
     display serif, because this is the machine describing its own condition (see Callout). */
  .fact-title {
    display: inline;
    margin: 0;
    font-family: var(--font-text);
    font-size: 0.95rem;
    font-weight: 600;
    line-height: 1.4;
    color: var(--ink-text);
    overflow-wrap: anywhere;
  }

  .fact-body {
    margin-top: var(--space-2);
  }

  /* Where to go and read the fact for yourself. Mono because it is a path, not prose.
     --ink-soft rather than --text-subtle: on the two washes this page always renders (the dark
     palette), --text-subtle measured 4.34:1 on --danger-wash and 4.13:1 on --amber-wash, under
     AA for small text; --ink-soft measures 6.14:1 and 5.84:1. AdminConsole.test.ts recomputes
     this from app.css. */
  .evidence {
    margin: var(--space-2) 0 0;
    font-family: var(--font-mono);
    font-size: 0.75rem;
    line-height: 1.5;
    color: var(--ink-soft);
    overflow-wrap: anywhere;
  }

  /* ---- Readings ---------------------------------------------------------- */

  .readings,
  .gaps,
  .findings,
  .notes {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .reading + .reading {
    margin-top: var(--space-4);
    padding-top: var(--space-4);
    border-top: 1px solid var(--hairline);
  }

  .reading-head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-3);
  }

  .reading-label {
    font-weight: 600;
    color: var(--ink-text);
  }

  .endpoint {
    font-family: var(--font-mono);
    font-size: 0.75rem;
    padding: 0.125rem var(--space-2);
    border-radius: var(--radius-sm);
    background: var(--chrome);
    border: 1px solid var(--chrome-hair);
    color: var(--chrome-ink);
    white-space: nowrap;
  }

  .observed {
    margin: var(--space-2) 0 0;
    color: var(--ink-text);
    font-size: 0.95rem;
    line-height: 1.55;
    max-width: 44rem;
  }

  /* Bytes the server sent, quoted rather than paraphrased. Scrolls inside its own box so a long
     upstream error cannot push the page sideways. */
  .machine {
    margin: var(--space-2) 0 0;
    padding: var(--space-2) var(--space-3);
    background: var(--chrome-2);
    border: 1px solid var(--chrome-hair);
    border-radius: var(--radius-sm);
    color: var(--chrome-ink);
    font-family: var(--font-mono);
    font-size: 0.75rem;
    line-height: 1.5;
    overflow-x: auto;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }

  /* "Establishes" and "does not establish" sit in one list on purpose: an operator who reads one
     without the other has learned the wrong thing, and the limitation is not an aside. */
  .limits {
    margin: var(--space-3) 0 0;
    display: grid;
    grid-template-columns: minmax(9rem, auto) 1fr;
    gap: var(--space-1) var(--space-4);
    font-size: 0.85rem;
    line-height: 1.55;
    max-width: 48rem;
  }

  .limits dt {
    font-family: var(--font-mono);
    font-size: 0.7rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
    padding-top: 0.15rem;
  }

  .limits dd {
    margin: 0;
    color: var(--ink-soft);
  }

  @media (max-width: 34rem) {
    .limits {
      grid-template-columns: 1fr;
      gap: 0;
    }
    .limits dd {
      margin-bottom: var(--space-2);
    }
  }

  /* ---- Stated gaps ------------------------------------------------------- */

  .gap {
    padding: var(--space-3) 0;
    border-top: 1px solid var(--hairline);
    max-width: 48rem;
  }

  .gap-subject {
    display: block;
    font-weight: 600;
    color: var(--ink-text);
    font-size: 0.95rem;
  }

  /* The statement is the point of the row, so it is set at reading weight rather than as a
     caption under the subject. */
  .gap-statement {
    margin: var(--space-1) 0 0;
    color: var(--ink-text);
    font-size: 0.9rem;
    line-height: 1.55;
  }

  .gap-held {
    margin: var(--space-1) 0 0;
    color: var(--ink-soft);
    font-size: 0.85rem;
    line-height: 1.55;
  }

  /* ---- The chain examiner ------------------------------------------------ */

  .examiner {
    margin: var(--space-4) 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .field {
    display: block;
  }

  .field-label {
    display: block;
    margin-bottom: var(--space-1);
    color: var(--ink-soft);
    font-size: 0.85rem;
    line-height: 1.5;
    max-width: 44rem;
  }

  /* --border-strong, not --hairline: a control whose affordance depends on its border is the one
     case the design system holds to a 3:1 boundary. */
  .paste,
  .text-input {
    width: 100%;
    box-sizing: border-box;
    padding: var(--space-2) var(--space-3);
    background: var(--paper-sheet);
    color: var(--ink-text);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    font-family: var(--font-mono);
    font-size: 0.8rem;
    line-height: 1.5;
  }

  .paste {
    resize: vertical;
    min-height: 6rem;
  }

  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
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

  .paste:focus-visible,
  .text-input:focus-visible,
  .action:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  /* ---- The verdict ------------------------------------------------------- */

  .verdict {
    margin-top: var(--space-4);
    padding-top: var(--space-4);
    border-top: 1px solid var(--hairline);
  }

  .verdict-head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-3);
  }

  .verdict-count {
    font-family: var(--font-mono);
    font-size: 0.75rem;
    color: var(--chrome-soft);
  }

  /* The verdict sentence is set at reading size, and the caveat under it is a Callout rather than
     a caption — the qualification has to be as legible as the claim it qualifies. */
  .verdict-headline {
    margin: var(--space-2) 0 var(--space-3);
    max-width: 44rem;
    color: var(--ink-text);
    font-size: 1rem;
    line-height: 1.55;
  }

  /* The head digest, four characters to a group. Mono and boxed per group so a person copying
     it down by hand has the same chunks to check off that the fingerprint ceremonies read
     aloud; the gaps are layout, not content — the value is the groups joined back together. */
  .digest {
    margin: var(--space-3) 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1) var(--space-2);
  }

  .digest-group {
    font-family: var(--font-mono);
    font-size: 0.85rem;
    letter-spacing: 0.08em;
    padding: 0.125rem var(--space-2);
    background: var(--chrome-2);
    border: 1px solid var(--chrome-hair);
    border-radius: var(--radius-sm);
    color: var(--chrome-ink);
    white-space: nowrap;
  }

  .list-title {
    margin: var(--space-4) 0 var(--space-2);
    font-family: var(--font-mono);
    font-size: 0.7rem;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
  }

  .findings li,
  .notes li {
    padding: var(--space-2) 0;
    border-top: 1px solid var(--hairline);
    color: var(--ink-soft);
    font-size: 0.88rem;
    line-height: 1.55;
    max-width: 48rem;
  }

  .finding-seq {
    display: inline-block;
    margin-right: var(--space-2);
    font-family: var(--font-mono);
    font-size: 0.75rem;
    color: var(--ink-text);
    white-space: nowrap;
  }

  .finding-detail {
    color: var(--ink-text);
  }
</style>
