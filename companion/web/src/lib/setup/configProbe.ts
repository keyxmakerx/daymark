/*
 * READING GET /v1/config, AND — THE HALF THIS FILE EXISTS FOR — NOT READING IT.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THIS IS A MODULE AND NOT SIX LINES INSIDE App.svelte
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * It was six lines inside App.svelte, and that is how it became wrong without anybody noticing.
 * The `$effect` holding them read no reactive state, so it fired on every load of index.html —
 * every visit, decided or not, on the offline surface as readily as on the sync one. The strip
 * rendered directly above it says, on the "Open a backup file" tab, "This tab reads your backup
 * in the browser and sends nothing". A page that contacts the server before that sentence is
 * painted is a page whose own promise is untrue, and the server learns the address and the hour
 * of every time the offline viewer was opened — the same metadata the sync posture discloses in
 * so many words ("It can still see that you synced, and when").
 *
 * Nothing caught it because nothing could. The suite runs in node with no component harness, so a
 * behaviour that lived only in a `.svelte` effect was reachable only by grepping the file, and a
 * grep cannot answer "does this load make a request?". Moving the decision and the request into a
 * module makes that the plainest possible test: hand it a fetch that counts its calls and assert
 * the count is zero. configProbe.test.ts does exactly that, and would have failed on the version
 * this replaces.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHY IT IS NOT IN shape.ts
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * That module opens by declaring itself pure — "No fetch, no DOM, no timers, no Svelte" — and
 * that claim is load-bearing: it is why every rule in it is a plain function a test can call with
 * three arguments. A fetch and a timer in there would falsify the header the same way the probe
 * falsified the strip. So the RULE ([shouldReadConfiguration]) stays there, beside the precedence
 * it mirrors, and the I/O lives here.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHAT IS SENT, WHICH IS AS LITTLE AS AN HTTP REQUEST CAN CARRY
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * One GET, no credentials, no body, no query, no header that names anything about the person or
 * their journal. The endpoint is unauthenticated and answers anyone who can reach the page, which
 * is what makes restating its answer on screen a disclosure of nothing new (audience.ts's rule).
 * What a request nonetheless reveals is that somebody at this address opened this page at this
 * time — unavoidable, and exactly why the load that does not need to make it does not make it.
 */
import {
  CONFIG_PATH,
  readSetupMode,
  shouldReadConfiguration,
  type ConfigState,
  type SetupInputs,
} from './shape'

/**
 * A CAP ON THE WAIT, because the question is answerable with no server at all.
 *
 * This page is also opened from a file, and from a laptop whose server is off. An unbounded probe
 * would hold the first screen blank in exactly the case where nothing on it needs a server:
 * choosing a shape is a local decision. Three seconds is long enough for a machine on the same
 * network and short enough that nobody wonders whether the page is broken.
 */
export const CONFIG_TIMEOUT_MS = 3000

/** What the caller may swap out. Present so this is testable in node, where there is no page. */
export interface ConfigReadDeps {
  /**
   * Defaults to the platform fetch. `undefined` — a `file:` origin on an old runtime, a build
   * with the global stripped — is not an error here: it means the same thing every other failure
   * means to this screen, which is that configuration did not answer.
   */
  fetch?: typeof globalThis.fetch | undefined
  /** Overridden only by tests; the product uses [CONFIG_TIMEOUT_MS]. */
  timeoutMs?: number
}

/**
 * Start the read, or decline to. Returns the canceller, so a Svelte `$effect` can return it
 * directly as its cleanup and an aborted probe cannot land on a page that has moved on.
 *
 * THE DECLINE IS THE POINT. When [shouldReadConfiguration] says the question already has an
 * answer here, this makes no request and reports nothing — `onresult` is not called at all, and
 * the caller's state stays at `reading`, which is the state that means "nothing has been read"
 * and which a resolved local answer walks straight past ([resolveSetup] rule 4). Reporting a
 * fabricated `unreachable` instead would tell the screen the server was unreachable when nobody
 * had asked it anything.
 *
 * EVERY FAILURE IS THE SAME FAILURE. A refused connection, a `file:` origin, a CORS wall, a 500,
 * an unparseable body, the timeout — all of them mean configuration did not answer, and the
 * person will be asked. None of them is worth a distinct message on a screen whose question does
 * not need a server in the first place.
 */
export function startConfigurationRead(
  inputs: Omit<SetupInputs, 'config'>,
  onresult: (state: ConfigState) => void,
  deps: ConfigReadDeps = {},
): () => void {
  const noop = () => {}
  if (!shouldReadConfiguration(inputs)) return noop

  const platformFetch =
    'fetch' in deps
      ? deps.fetch
      : typeof globalThis.fetch === 'function'
        ? globalThis.fetch.bind(globalThis)
        : undefined
  if (typeof platformFetch !== 'function') {
    onresult({ kind: 'unreachable' })
    return noop
  }

  let cancelled = false
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), deps.timeoutMs ?? CONFIG_TIMEOUT_MS)

  void (async () => {
    try {
      // Registered at the server root rather than under DAYMARK_BASE_PATH (Application.kt), so an
      // absolute-from-origin path is right even when this page is served under a prefix.
      const res = await platformFetch(CONFIG_PATH, {
        headers: { accept: 'application/json' },
        cache: 'no-store',
        signal: controller.signal,
      })
      const body = res.ok ? await res.text() : null
      if (!cancelled) onresult(readSetupMode(body))
    } catch {
      if (!cancelled) onresult({ kind: 'unreachable' })
    } finally {
      clearTimeout(timer)
    }
  })()

  return () => {
    cancelled = true
    clearTimeout(timer)
    controller.abort()
  }
}
