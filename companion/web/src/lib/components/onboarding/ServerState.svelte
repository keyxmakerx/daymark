<script lang="ts">
  /*
   * "Is a server involved here, and is it answering?" — the first-run panel.
   *
   * THIN BY CONSTRUCTION. Every sentence on this panel comes from lib/onboarding/serverState.ts,
   * which is pure and whose test suite walks every string it can emit and asserts that none of them
   * names a withheld subject. This file does four things: it decides whether there is a server to
   * ask, it makes three requests, it hands the responses to that module, and it renders what comes
   * back. If a word needs changing it changes there, where a test is watching it.
   *
   * WHY IT ASKS ONLY THREE URLS, AND WHY IT DOES NOT POLL. /healthz, /readyz and /v1/config are the
   * whole of what this server tells an anonymous caller. /readyz touches the disk on the server
   * (Readiness.kt caches the result for five seconds and single-flights it), and this is a panel a
   * person reads once while setting things up, not a monitor. So: one read when it mounts, and a
   * button. An open tab does not become a background load on someone's server.
   *
   * WHY IT DOES NOT PROBE A SYNC ROUTE. The module's header sets this out in full. In short: on a
   * server where sync IS configured, an unauthenticated request to any route that would answer the
   * question is recorded by AuthGuard as a failed attempt against the caller's source identity. A
   * page anyone can open must not consume those. The question is left open and said to be.
   *
   * WHY IT REFUSES TO FETCH FROM A `file:` PAGE. index.html promises that opening the viewer from
   * disk makes zero network requests, and that promise is the Phase-0 offline story. It is also
   * simply correct: a relative fetch from a file has no target, and reporting its failure as "the
   * server is not answering" would send someone to debug a machine that is fine. `classifyOrigin`
   * decides this, the module's copy states it, and the request is never made.
   *
   * WHY THE EFFECT UNTRACKS ITS OWN CALL. `read()` reads `busy` and flips it, and `$effect` tracks
   * whatever is read synchronously inside it — so a naive `$effect(() => void read())` would take a
   * dependency on the flag it writes and re-fire itself forever. The dependencies are therefore
   * named explicitly (the address we would ask) and the call is untracked.
   *
   * WHY `now` IS A PROP AND A TICKING $state, NOT A Date.now() INSIDE A DERIVATION. `$derived`
   * re-evaluates on tracked dependency change only; a clock read inside a callee is invisible to it,
   * and that exact bug shipped in this codebase's therapist portal, where an idle guard silently
   * stopped ticking. `nowMs` is a prop so a caller (or a test) can drive the clock; when it is not
   * supplied an interval advances a $state, which IS tracked. The staleness line matters here
   * because the panel does not poll: a reading left on screen for an hour must not read as current.
   *
   * NO GREEN, NO TICK. `answered` maps to the neutral chip and the reading's own "nothing here
   * contradicts a working setup" maps to Callout's info tone. There is no reassuring tone in this
   * system, here or anywhere — see Callout's own note. Reassurance is the absence of a callout.
   */
  import { Callout, Card, Chip } from '../ui'
  import {
    AUDIENCE_LABEL,
    BUSY_WORD,
    PANEL_FOOTER,
    PANEL_LEDE,
    PROBE_ORDER,
    PROBE_PATH,
    VERDICT_WORD,
    classifyOrigin,
    describeLastChecked,
    interpretServerState,
    type ProbeResponse,
    type ProbeSet,
    type ProbeVerdict,
  } from '../../onboarding/serverState'
  import { untrack } from 'svelte'

  let {
    /** The panel heading. A prop so the shell can place this under its own hierarchy. */
    title = 'Server checks',
    /**
     * `window.location.protocol`, verbatim. Injected rather than read here so the decision that
     * governs whether any request happens at all is testable without a browser.
     */
    protocol = typeof location !== 'undefined' ? location.protocol : '',
    /**
     * Origin prefix for the three probes. All three are registered at the server ROOT in
     * Application.kt, outside DAYMARK_BASE_PATH, so the default of "same origin, absolute path" is
     * right unless this panel is rendered somewhere other than the page the server served.
     */
    baseUrl = '',
    fetchImpl = typeof fetch === 'function' ? fetch.bind(globalThis) : undefined,
    /** A request that never returns must not leave the panel reading "Reading…" forever. */
    timeoutMs = 8_000,
    /** See the header note. Omit to let this component run its own clock. */
    nowMs = undefined,
  }: {
    title?: string
    protocol?: string
    baseUrl?: string
    fetchImpl?: typeof fetch
    timeoutMs?: number
    nowMs?: number
  } = $props()

  let probes = $state<ProbeSet | null>(null)
  let lastCheckedAt = $state<number | null>(null)
  let busy = $state(false)
  let tick = $state(Date.now())

  const origin = $derived(classifyOrigin(protocol))
  const reading = $derived(interpretServerState({ protocol, probes }))
  const staleness = $derived(describeLastChecked(lastCheckedAt, nowMs ?? tick))
  const canAsk = $derived(origin === 'served' && fetchImpl !== undefined)

  /* The clock the staleness line reads. Skipped entirely when a caller drives it. */
  $effect(() => {
    if (nowMs !== undefined) return
    const id = setInterval(() => (tick = Date.now()), 1_000)
    return () => clearInterval(id)
  })

  /* One read per address. See the header note on why the call is untracked. */
  $effect(() => {
    void protocol
    void baseUrl
    untrack(() => void read())
  })

  async function read() {
    if (!canAsk || busy) return
    busy = true
    try {
      const entries = await Promise.all(
        PROBE_ORDER.map(async (id) => [id, await ask(`${baseUrl}${PROBE_PATH[id]}`)] as const),
      )
      probes = Object.fromEntries(entries) as ProbeSet
      lastCheckedAt = Date.now()
    } finally {
      busy = false
    }
  }

  /**
   * One request to one path.
   *
   * Never throws: a transport failure is a reading, not an error, and the module has copy for it.
   * The caught message is the one thing on this panel this codebase did not author, and it is
   * carried in the line's `detail` field and nowhere else.
   */
  async function ask(url: string): Promise<ProbeResponse> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    try {
      const res = await fetchImpl!(url, {
        headers: { accept: 'application/json' },
        cache: 'no-store',
        signal: controller.signal,
      })
      return { kind: 'response', status: res.status, body: await res.text() }
    } catch (e) {
      return { kind: 'transport', error: e instanceof Error ? `${e.name}: ${e.message}` : String(e) }
    } finally {
      clearTimeout(timer)
    }
  }

  /* Reading tone to Callout tone. `neutral` maps to info — the interface explaining itself — and
     never to a fourth, reassuring tone, because there is not one. */
  const CALLOUT_TONE: Record<'neutral' | 'warn' | 'critical', 'info' | 'warn' | 'critical'> = {
    neutral: 'info',
    warn: 'warn',
    critical: 'critical',
  }

  /* An endpoint that answered gets the chrome chip, not a congratulatory one: it answered, which
     is a fact about one request and not a verdict on the deployment. */
  const VERDICT_TONE: Record<ProbeVerdict, 'neutral' | 'warn' | 'critical'> = {
    answered: 'neutral',
    refused: 'critical',
    'no-answer': 'critical',
    undocumented: 'warn',
  }
</script>

<Card {title}>
  {#snippet header()}
    {#if busy}<Chip tone="neutral">{BUSY_WORD}</Chip>{/if}
    <Chip tone="neutral">{staleness}</Chip>
    <button class="action" type="button" onclick={() => void read()} disabled={!canAsk || busy}>
      Check again
    </button>
  {/snippet}

  <p class="lede">{PANEL_LEDE}</p>

  <Callout tone={CALLOUT_TONE[reading.tone]} title={reading.word}>
    <p class="para">{reading.observed}</p>
    <p class="para">{reading.means}</p>
  </Callout>

  {#if reading.lookHere.length > 0}
    <h3 class="list-title">Where to look</h3>
    <!-- Each line says who it is addressed to, because the two audiences here are different people
         and a self-hoster is both. That is the whole complaint this panel sits inside. -->
    <dl class="advice">
      {#each reading.lookHere as line, i (i)}
        <dt>{AUDIENCE_LABEL[line.audience]}</dt>
        <dd>{line.text}</dd>
      {/each}
    </dl>
  {/if}

  {#if reading.probes.length > 0}
    <h3 class="list-title">What was asked, and what came back</h3>
    <ul class="readings">
      {#each reading.probes as probe (probe.id)}
        <li class="reading">
          <div class="reading-head">
            <span class="reading-label">{probe.label}</span>
            <code class="endpoint">{probe.endpoint}</code>
            <Chip tone={VERDICT_TONE[probe.verdict]}>{VERDICT_WORD[probe.verdict]}</Chip>
          </div>
          <p class="observed">{probe.observed}</p>
          {#if probe.detail}
            <!-- Bytes this codebase did not author, quoted rather than paraphrased, capped by the
                 module and scrolling inside its own box so an upstream page cannot push the layout
                 sideways. -->
            <pre class="machine">{probe.detail}</pre>
          {/if}
          <p class="silent">
            <span class="silent-key">Does not cover</span>
            {probe.silentOn}
          </p>
        </li>
      {/each}
    </ul>
  {/if}

  {#if reading.mail}
    <div class="aside">
      <Callout tone="info" title="Outbound email">{reading.mail}</Callout>
    </div>
  {/if}

  {#if reading.syncNote}
    <div class="aside">
      <Callout tone="info" title="One thing this panel does not ask">{reading.syncNote}</Callout>
    </div>
  {/if}

  {#snippet footer()}{PANEL_FOOTER}{/snippet}
</Card>

<style>
  .lede {
    margin: 0 0 var(--space-4);
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.6;
  }

  .para {
    margin: 0 0 var(--space-2);
  }
  .para:last-child {
    margin-bottom: 0;
  }

  .list-title {
    margin: var(--space-5) 0 var(--space-2);
    font-family: var(--font-mono);
    font-size: 0.7rem;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
  }

  /* ---- Who each line is for ---------------------------------------------- */

  /* The audience is a field name, not prose: mono and tracked out, so a reader can skip the rows
     addressed to somebody else without reading them. */
  .advice {
    display: grid;
    grid-template-columns: minmax(11rem, auto) 1fr;
    gap: var(--space-2) var(--space-4);
    margin: 0;
    font-size: 0.88rem;
    line-height: 1.55;
    max-width: 48rem;
  }

  .advice dt {
    font-family: var(--font-mono);
    font-size: 0.7rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
    padding-top: 0.2rem;
  }

  .advice dd {
    margin: 0;
    color: var(--ink-soft);
  }

  @media (max-width: 34rem) {
    .advice {
      grid-template-columns: 1fr;
      gap: 0;
    }
    .advice dd {
      margin-bottom: var(--space-3);
    }
  }

  /* ---- The evidence rows -------------------------------------------------- */

  .readings {
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

  /* What the answer does not cover sits directly under what it said, at reading size rather than as
     small print. A reader who takes one without the other has learned the wrong thing. */
  .silent {
    margin: var(--space-2) 0 0;
    color: var(--ink-soft);
    font-size: 0.85rem;
    line-height: 1.55;
    max-width: 48rem;
  }

  .silent-key {
    font-family: var(--font-mono);
    font-size: 0.7rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
    margin-right: var(--space-2);
  }

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

  .aside {
    margin-top: var(--space-4);
  }

  /* ---- The one control ---------------------------------------------------- */

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
</style>
