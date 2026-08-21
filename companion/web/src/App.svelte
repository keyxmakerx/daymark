<script lang="ts">
  import { parseBackup, BackupParseError, type BackupData } from './lib/backup'
  import { formatDate } from './lib/stats'
  import Dropzone from './lib/components/Dropzone.svelte'
  import Dashboard from './lib/components/Dashboard.svelte'
  import TrustBar from './lib/components/TrustBar.svelte'
  import SyncPanel from './lib/components/SyncPanel.svelte'
  import Assessments from './lib/components/Assessments.svelte'
  import OwnerConsole from './lib/components/owner/OwnerConsole.svelte'
  import Orientation from './lib/components/onboarding/Orientation.svelte'
  import RecoverAccess from './lib/components/owner/RecoverAccess.svelte'
  import ToolBuilder from './lib/components/ToolBuilder.svelte'
  import SetupEntry from './lib/components/setup/SetupEntry.svelte'
  import ShapeStrip from './lib/components/setup/ShapeStrip.svelte'
  import PracticePlaceholder from './lib/components/setup/PracticePlaceholder.svelte'
  import {
    decidedShape,
    defaultSetupStorage,
    forgetShape,
    readStoredChoice,
    rememberShape,
    resolveSetup,
    shapeById,
    shouldReadConfiguration,
    type ConfigState,
    type ShapeId,
    type StoredChoice,
  } from './lib/setup/shape'
  import { startConfigurationRead } from './lib/setup/configProbe'
  import { trustPostureFor } from './lib/trust/posture'
  import type { InstrumentDefinition } from './lib/instruments/types'

  type Source = 'file' | 'sync' | 'assess' | 'build' | 'owner' | 'recover'

  /*
   * The seventh destination, and why it is not in `Source`.
   *
   * `Source` is the OWNER's six entry points, modelled in lib/onboarding/audience.ts as
   * OWNER_ROUTES and cross-checked against this very declaration by audience.test.ts. The
   * practice surface is not one of them — it belongs to a different deployment shape and appears
   * only when this machine is set up as a clinic's — so it is a separate member of the surface
   * union rather than a seventh tab nobody in Solo or Paired can ever reach.
   */
  type Surface = Source | 'practice'

  let data = $state<BackupData | null>(null)
  let fileName = $state('')
  let error = $state('')
  // An emailed access-token recovery link lands here as `#t=<token>` (see RecoverAccess.svelte);
  // land the owner straight on the recovery tab instead of the default "open a backup" tab.
  const startedOnRecoveryLink = typeof window !== 'undefined' && /(?:^|[#&])t=/.test(window.location.hash)
  let source = $state<Surface>(startedOnRecoveryLink ? 'recover' : 'file')

  /*
   * ═══════════════════════════════════════════════════════════════════════════════════════════
   * FIRST RUN: WHICH OF THE THREE DEPLOYMENT SHAPES THIS MACHINE IS.
   * ═══════════════════════════════════════════════════════════════════════════════════════════
   *
   * docs/PLAN_2026-08-COMPANION-NEXT.md §3.11 names three, and they are not sizes of one product:
   * Solo and Paired put the journal on its owner's own hardware, while Practice inverts that and
   * makes the person a tenant on their clinic's machine. Nothing in this app used to ask, so the
   * answer was whatever screen somebody clicked first.
   *
   * The state below is the whole gate, and it is deliberately small:
   *
   *   config   what GET /v1/config said. Starts as `reading` — which suppresses the question
   *            rather than asking one that configuration may be about to answer — and STAYS
   *            there on the loads that make no request at all, because "nothing has been read"
   *            and "the read is in flight" are the same thing to this screen.
   *   session  a choice made this visit, which stands whether or not the browser kept it.
   *   stored   what this browser had recorded.
   *
   * `resolveSetup` puts them in precedence order — configuration over this visit over this
   * browser — and returns either "ask" or the shape. FAIL OPEN is the rule throughout: every
   * failure of storage or of the probe lands on asking a question again, and none of them can
   * leave somebody unable to reach their own machine. That is why the recovery link below is
   * checked before the gate at all.
   */
  const setupStorage = defaultSetupStorage()

  let config = $state<ConfigState>({ kind: 'reading' })
  let sessionShape = $state<ShapeId | null>(null)
  let stored = $state<StoredChoice | null>(readStoredChoice(setupStorage))
  let storageRefused = $state(false)
  let forgetRefused = $state(false)

  const decision = $derived(resolveSetup({ config, session: sessionShape, stored }))

  /*
   * ═══════════════════════════════════════════════════════════════════════════════════════════
   * THE CONFIGURATION READ, AND THE LOADS THAT DO NOT MAKE ONE.
   * ═══════════════════════════════════════════════════════════════════════════════════════════
   *
   * This was an unconditional probe: an `$effect` reading no reactive state, so it fired once per
   * page load, forever, on every visit. That made index.html reach the server every time it
   * opened — and the strip rendered a few lines below promises, on the "Open a backup file" tab,
   * that this tab sends nothing. It cannot both be true. A server contacted on every load learns
   * the address and the hour of every time somebody opened the offline viewer, which is precisely
   * the metadata the sync posture treats as worth disclosing out loud.
   *
   * So the read is now scoped to the question it answers. `shouldReadConfiguration` is the rule
   * (lib/setup/shape.ts, beside the precedence it mirrors) and `startConfigurationRead` is the
   * request (lib/setup/configProbe.ts, which owns the timeout and the abort). Reading
   * `sessionShape` and `stored` here is deliberate: it makes this effect re-run when the answer
   * changes, so choosing a shape CANCELS an in-flight probe through the cleanup below, and
   * reopening the question starts a fresh one. `config` is written and never read here, so the
   * write cannot feed back into the effect that made it.
   *
   * WHAT THIS COSTS, stated because it is a real trade and not a free win: a browser that has
   * already answered no longer notices a deployment that pins the shape afterwards, until the
   * question is reopened. That is CONFIGURATION_IS_NOT_RE_READ, which the strip shows next to the
   * answer it qualifies rather than leaving an operator to discover a setting that appeared not
   * to work.
   */
  $effect(() =>
    startConfigurationRead({ session: sessionShape, stored }, (state) => {
      config = state
    }),
  )

  /*
   * Land on the shape's own surface, ONCE PER SHAPE.
   *
   * A plain `let` rather than `$state`: this is a latch, nothing renders from it, and making it
   * reactive would have the effect that sets it re-run on its own write. The latch is what keeps
   * this from being a routing rule — a person who chose Paired and then walked over to the
   * self-checks tab stays there, and the next re-render does not drag them back.
   *
   * KEYED BY SHAPE RATHER THAN A BOOLEAN, because the latch has to survive the shape changing
   * under a settled page — a boolean would leave that person looking at the surface for a shape
   * the strip above them says this machine is not.
   *
   * The route that used to produce exactly that is now closed, and saying so is the point of
   * keeping this paragraph rather than deleting it: a stored answer would resolve first and a
   * configured deployment's answer could outrank it a moment later, because configuration was
   * read on every load. It is read only while the question is open now, so a browser that has an
   * answer no longer has one arrive on top of it. The keying stays because it is what makes this
   * latch correct without depending on that, and because `reopenSetupQuestion` clearing it is
   * then a statement about re-landing rather than the only thing holding the latch together.
   */
  let landedShape: ShapeId | null = null

  $effect(() => {
    const shape = decidedShape(decision)
    if (shape === null || shape === landedShape || startedOnRecoveryLink) return
    landedShape = shape
    source = shapeById(shape).primary
  })

  function chooseShape(id: ShapeId) {
    // The choice stands for this visit either way; the return value only decides whether the
    // person is told the browser would not keep it (lib/setup/shape.ts, STORAGE_REFUSED).
    storageRefused = !rememberShape(setupStorage, id)
    forgetRefused = false
    sessionShape = id
    stored = readStoredChoice(setupStorage)
  }

  function reopenSetupQuestion() {
    // `forgetShape` returns false when the browser refused the removal, and discarding that would
    // be claiming a deletion that did not happen — the same failure Orientation.svelte had to
    // grow a message for. Either way the question comes back on screen now.
    forgetRefused = !forgetShape(setupStorage)
    storageRefused = false
    sessionShape = null
    stored = null
    // Cleared so answering the question again re-lands on that shape's surface, even when the
    // same shape is chosen: the latch above would otherwise treat it as already landed.
    landedShape = null
  }

  /*
   * The gate. Open only when there is no answer yet AND this visit is not a recovery link.
   *
   * The recovery bypass is not a convenience. An emailed access-token link is followed by someone
   * who has already lost something, and a first-run question standing between them and their own
   * access would be the one failure this screen must never cause. The question is deferred rather
   * than answered, and ShapeStrip says which of those it is instead of leaving the machine
   * looking set up.
   */
  const setupGateOpen = $derived(
    !startedOnRecoveryLink && (decision.state === 'reading' || decision.state === 'ask'),
  )
  const shapeUndecided = $derived(decidedShape(decision) === null)

  /*
   * WHICH POSTURE THE TRUST STRIP STATES.
   *
   * Derived from the tab AND from whether this load is reading configuration — never from
   * navigator.onLine, because being offline this instant says nothing about whether the surface
   * would call out, and the strip used to go green on exactly that reasoning. Once a backup is
   * loaded the strip describes the tab that loaded it, which is why `data` does not reset this.
   *
   * The mapping itself is `trustPostureFor` in lib/trust/posture.ts rather than a ternary here.
   * It was a ternary here, and that is how it came to promise "sends nothing" over a page that
   * had just made a request: three tabs were named and everything else fell through to `local`,
   * so a request belonging to none of the three named tabs was invisible to it. As a function it
   * is a rule with a test over every surface — a page that is reaching the network is never
   * described as sending nothing.
   *
   * The second argument is the same predicate the effect above uses, so the strip cannot say one
   * thing while the probe does another: they are not two readings of the same intent, they are
   * one expression evaluated twice.
   */
  const readingConfiguration = $derived(
    shouldReadConfiguration({ session: sessionShape, stored }),
  )
  const trustSurface = $derived(trustPostureFor(source, readingConfiguration))

  function load(text: string, name: string) {
    error = ''
    try {
      data = parseBackup(text)
      fileName = name
    } catch (e) {
      data = null
      error = e instanceof BackupParseError ? e.message : 'Could not read that backup.'
    }
  }

  function loadData(parsed: BackupData, name: string) {
    error = ''
    data = parsed
    fileName = name
  }

  function reset() {
    data = null
    fileName = ''
    error = ''
  }

  // A built tool is exported as a validated instrument-definition JSON; it can later be shipped
  // in the catalog or delivered via the assignment channel (that plumbing is a separate slice).
  function publishTool(def: InstrumentDefinition) {
    const blob = new Blob([JSON.stringify(def, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `daymark-tool-${def.instrumentId || 'draft'}.json`
    a.click()
    URL.revokeObjectURL(url)
  }
</script>

<div class="shell">
  <header class="topbar">
    <div class="brand">
      <span class="mark" aria-hidden="true"></span>
      <div>
        <h1>Daymark Companion</h1>
        <p class="muted tagline">Offline report viewer</p>
      </div>
    </div>
    {#if data}
      <button onclick={reset}>Open another backup</button>
    {/if}
  </header>

  <main>
    <TrustBar surface={trustSurface} />

    {#if setupGateOpen}
      <!--
        NOT YET SET UP. The one screen in this product allowed to explain itself, and it is shown
        exactly until the question has an answer — from this browser or from configuration. Note
        that it replaces the destination menu rather than sitting above it: six equal-weight verbs
        under a question about what the machine is for would be the maintainer's original
        complaint ("there's like 10 different buttons.. why") with one more thing on top.
      -->
      <SetupEntry {decision} {config} onchoose={chooseShape} {storageRefused} />
    {:else if !data}
      <section class="intro">
        <!--
          ALREADY SET UP: the strip states the answer and gets out of the way. Everything below it
          is the surface that existed before this slice, unchanged — a returning person meets
          their own destinations, not a setup screen wearing a smaller font.
        -->
        <ShapeStrip
          {decision}
          onchange={reopenSetupQuestion}
          onopenpractice={() => (source = 'practice')}
          {storageRefused}
          {forgetRefused}
          bypassed={startedOnRecoveryLink && shapeUndecided}
        />

        <!--
          THE ORIENTATION IS THE NAVIGATION, not a banner above it.

          This was six flat buttons — "Open a backup file / Connect to sync / Self-checks / Build a
          tool / Owner console / Recover access" — with no statement of who any of them were for
          and no hint that a clinician belongs on a different page entirely. The maintainer's own
          words on landing here: "i am so confused as to what i'm looking at".

          Orientation renders the same six destinations grouped and explained, plus the two other
          surfaces that exist. `onchoose` gives it the same job the buttons had, so nothing is
          added between a returning person and the thing they came to do — after the first visit it
          collapses to a compact header with the routes still in place.

          `adminLink` is left at its default of false: admin.html holds no credential on this
          build, and a deployment should have to choose to advertise it from a public page.
        -->
        <!--
          `selected` is one of the owner's six; the practice surface is not among them and passes
          `undefined` rather than a route id Orientation has no button for.
        -->
        <Orientation
          selected={source === 'practice' ? undefined : source}
          onchoose={(id) => (source = id)}
        />

        {#if source === 'file'}
          <Dropzone onload={load} onerror={(m) => (error = m)} />
        {:else if source === 'sync'}
          <SyncPanel onload={loadData} />
        {:else if source === 'assess'}
          <Assessments />
        {:else if source === 'build'}
          <ToolBuilder onPublish={publishTool} />
        {:else if source === 'recover'}
          <RecoverAccess />
        {:else if source === 'practice'}
          <!--
            The marked placeholder standing where the practice console will be. It is reached only
            from the practice shape, and it says in the interface that it holds no data — see the
            header note in PracticePlaceholder.svelte for why an empty roster was the wrong answer.
          -->
          <PracticePlaceholder />
        {:else}
          <OwnerConsole data={null} />
        {/if}

        {#if error}
          <p class="error" role="alert">{error}</p>
        {/if}
        <!-- 'practice' joins the exclusions: that panel is about a clinic's machine, and the note
             below is instructions for dropping your own backup file on the two tabs that take one. -->
        {#if source !== 'assess' && source !== 'build' && source !== 'owner' && source !== 'recover' && source !== 'practice'}
          <p class="faint note">
            Non-diagnostic: Daymark is a self-tracking and journaling tool. Nothing here
            is a medical assessment. Export a backup from the app via
            <em>Settings → Export backup</em>, then drop the <code>.json</code> file above —
            or pull your latest encrypted snapshot from your own sync server.
          </p>
        {/if}
      </section>
    {:else}
      <section class="loaded">
        <p class="muted filemeta">
          <strong>{fileName}</strong> · backup v{data.version} · exported {formatDate(data.exportedAt)}
        </p>

        <Dashboard {data} />
      </section>
    {/if}
  </main>

  <footer class="foot faint">
    <p>
      Daymark Companion · Phase-0 viewer · GPL-3.0 · runs entirely on your device.
      <span class="status">design-stage scaffold</span>
    </p>
  </footer>
</div>

<style>
  .shell { max-width: var(--maxw); margin: 0 auto; padding: var(--space-5) var(--space-4) var(--space-8); display: flex; flex-direction: column; gap: var(--space-5); min-height: 100vh; }
  .topbar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-4); }
  .brand { display: flex; align-items: center; gap: var(--space-3); }
  /* The brand mark is structural, not a reading: it says "Daymark", not "this person had a good
     day". It was a --mood-4 → --mood-5 gradient, which spent the top two steps of a person's
     reported-experience ramp on a logo. Structural accent instead. */
  .mark { width: 2rem; height: 2rem; border-radius: 0.5rem; background: linear-gradient(135deg, var(--indigo), var(--indigo-deep)); box-shadow: var(--elevation); }
  .tagline { margin: 0; font-size: 0.9rem; }
  main { display: flex; flex-direction: column; gap: var(--space-5); flex: 1; }
  .intro { display: flex; flex-direction: column; gap: var(--space-4); }
  .note { max-width: 42rem; }
  /* Failure is the single alarm hue. It was --mood-1, the step meaning "this person reported an
     awful day" — interface state wearing a person's data. */
  .error { color: var(--clay); background: var(--clay-wash); border: 1px solid var(--clay); border-radius: var(--radius-sm); padding: var(--space-3) var(--space-4); margin: 0; }
  .filemeta { margin: 0; }
  /* The `.tabs` rules that lived here went with the six flat buttons Orientation replaced. The
     reasoning they carried — that a selected surface is STRUCTURE, so it takes the structural
     accent rather than content ink, and that aria-pressed carries the selection so a fill is
     never the only signal — moved with the markup and is restated in Orientation.svelte. */
  .foot { border-top: 1px solid var(--hairline); padding-top: var(--space-4); font-size: 0.85rem; }
  .status { font-style: italic; }
  code { font-family: var(--font-mono); background: var(--paper-bg); padding: 0 0.25rem; border-radius: 4px; }
</style>
