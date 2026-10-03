<script lang="ts">
  /*
   * The door to the server console (#322): claim a new server with its setup code, or sign in as
   * its administrator, and only then the console.
   *
   * WHAT DECIDES WHICH SCREEN. The server's own answer, read fresh on every open: an unclaimed
   * server with a live code shows the claim; anything else asks the overview, and a session that
   * opens it shows the console while one that does not shows sign-in. Nothing is remembered in the
   * browser: the session is an HttpOnly cookie this page cannot read, and its anti-CSRF token lives
   * in this component's state and nowhere else.
   *
   * THE SEED IS MADE HERE AND SHOWN ONCE. A fresh 160-bit seed is drawn when the claim screen
   * opens, drawn as a QR code and as groups of four for typing by hand, and sent once with the six
   * digits that prove the authenticator took it. It is zeroed when the claim succeeds. A seed drawn
   * for a claim that never happens is simply dropped with the page.
   *
   * THE SETUP CODE IS NEVER SHOWN BACK. Its field is a password field, and no refusal quotes it;
   * the words come from lib/admin/claim.ts, mapped from the status, never from the server's text.
   *
   * THIN BY CONSTRUCTION, as the console is: every sentence and every request lives in
   * lib/admin/claim.ts, where a test watches it.
   */
  import { Callout } from '../ui'
  import AdminConsole from './AdminConsole.svelte'
  import {
    CLAIM_INTRO,
    RESET_HINT,
    SEED_INSTRUCTION,
    SIGN_IN_INTRO,
    adminApi,
    base32,
    groupSecret,
    newSeed,
    otpauthUri,
    plausibleSetupCode,
    validName,
    validTotp,
  } from '../../admin/claim'
  import { qrDrawing } from '../../phones/qr'

  let {
    baseUrl = '',
    fetchImpl = typeof fetch === 'function' ? fetch.bind(globalThis) : undefined,
  }: {
    baseUrl?: string
    fetchImpl?: typeof fetch
  } = $props()

  const api = $derived(fetchImpl ? adminApi(baseUrl, fetchImpl) : null)

  type Screen = 'reading' | 'claim' | 'sign-in' | 'console' | 'unreachable'
  let screen = $state<Screen>('reading')
  let claimOpen = $state(false)
  let problem = $state('')
  let busy = $state(false)

  let signedInAs = $state('')
  let csrfToken = $state('')

  let setupCode = $state('')
  let name = $state('')
  let digits = $state('')
  let seed = $state<Uint8Array | null>(null)

  const secret = $derived(seed ? base32(seed) : '')
  const host = typeof location === 'undefined' ? '' : location.host
  const qr = $derived(secret ? qrDrawing(otpauthUri(secret, host)) : null)

  /* Pin the surface dark, as the console does, so the door and the room match. */
  $effect(() => {
    const root = document.documentElement
    const previous = root.getAttribute('data-theme')
    root.setAttribute('data-theme', 'dark')
    return () => {
      if (previous === null) root.removeAttribute('data-theme')
      else root.setAttribute('data-theme', previous)
    }
  })

  $effect(() => {
    void decide()
  })

  async function decide() {
    if (!api) return
    problem = ''
    const status = await api.status()
    if (!status.ok) {
      problem = status.message
      screen = 'unreachable'
      return
    }
    claimOpen = status.value.claimOpen
    if (!status.value.claimed && claimOpen) {
      openClaim()
      return
    }
    const overview = await api.overview()
    if (overview.ok) {
      signedInAs = overview.value.name
      csrfToken = overview.value.csrfToken
      screen = 'console'
    } else if (overview.status === 401) {
      screen = 'sign-in'
    } else {
      problem = overview.message
      screen = 'unreachable'
    }
  }

  function openClaim() {
    seed = newSeed()
    problem = ''
    digits = ''
    screen = 'claim'
  }

  const claimReady = $derived(
    plausibleSetupCode(setupCode) && validName(name) && validTotp(digits) && seed !== null,
  )
  const signInReady = $derived(validName(name) && validTotp(digits))

  async function claim(e: SubmitEvent) {
    e.preventDefault()
    if (!api || !seed || !claimReady || busy) return
    busy = true
    try {
      const out = await api.claim({ setupCode, name, seed, totpCode: digits })
      digits = ''
      if (!out.ok) {
        problem = out.message
        return
      }
      seed.fill(0)
      seed = null
      setupCode = ''
      enter(out.value.name, out.value.csrfToken)
    } finally {
      busy = false
    }
  }

  async function signIn(e: SubmitEvent) {
    e.preventDefault()
    if (!api || !signInReady || busy) return
    busy = true
    try {
      const out = await api.signIn(name, digits)
      digits = ''
      if (!out.ok) {
        problem = out.message
        return
      }
      enter(out.value.name, out.value.csrfToken)
    } finally {
      busy = false
    }
  }

  function enter(who: string, csrf: string) {
    signedInAs = who
    csrfToken = csrf
    problem = ''
    screen = 'console'
  }

  async function signOut() {
    if (api && csrfToken) await api.signOut(csrfToken)
    csrfToken = ''
    signedInAs = ''
    name = ''
    screen = 'sign-in'
  }
</script>

{#if screen === 'console'}
  <AdminConsole {baseUrl} {fetchImpl} account={{ name: signedInAs, signOut: () => void signOut() }} />
{:else}
  <div class="gate">
    <main class="door">
      <!-- The shared masthead, as the console renders it. -->
      <div class="brand">
        <span class="mark" aria-hidden="true"></span>
        <div>
          <p class="wordmark">Daymark Companion</p>
          <p class="tagline">Server operations</p>
        </div>
      </div>

      {#if screen === 'reading'}
        <p class="lede" aria-live="polite">Asking the server…</p>
      {:else if screen === 'unreachable'}
        <h1>The server did not answer</h1>
        <Callout tone="critical">{problem}</Callout>
        <button class="primary" type="button" onclick={() => void decide()}>Try again</button>
      {:else if screen === 'claim'}
        <h1>Claim this server</h1>
        <p class="lede">{CLAIM_INTRO}</p>

        <form class="form" onsubmit={claim} novalidate>
          <label class="field">
            <span class="label">Setup code, from the server’s log</span>
            <input
              class="input mono"
              type="password"
              autocomplete="one-time-code"
              spellcheck="false"
              placeholder="XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX"
              bind:value={setupCode}
            />
          </label>

          <label class="field">
            <span class="label">Your name as administrator</span>
            <input class="input" type="text" autocomplete="username" maxlength="64" bind:value={name} />
            <span class="hint">Letters, digits, spaces, dots, dashes or underscores, up to 64.</span>
          </label>

          <fieldset class="field auth">
            <legend class="label">Your authenticator</legend>
            <p class="hint">{SEED_INSTRUCTION}</p>
            {#if qr}
              <div class="seed">
                <!-- Hidden from assistive technology: its text alternative is the key printed beside it. -->
                <div class="qr-tile">
                  <svg class="qr" viewBox="0 0 {qr.extent} {qr.extent}" aria-hidden="true" focusable="false" shape-rendering="crispEdges">
                    <rect class="qr-paper" width={qr.extent} height={qr.extent} />
                    <path class="qr-ink" d={qr.path} />
                  </svg>
                </div>
                <div class="key" aria-label="Authenticator key, in groups of four">
                  {#each groupSecret(secret) as group, i (i)}<span class="group">{group}</span>{/each}
                </div>
              </div>
            {/if}
          </fieldset>

          <label class="field">
            <span class="label">The six digits your authenticator shows now</span>
            <input class="input mono digits" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" bind:value={digits} />
          </label>

          {#if problem}<Callout tone="warn">{problem}</Callout>{/if}

          <button class="primary" type="submit" disabled={!claimReady || busy}>
            {busy ? 'Claiming…' : 'Make me the administrator'}
          </button>
        </form>

      {:else if screen === 'sign-in'}
        <h1>Sign in</h1>
        <p class="lede">{SIGN_IN_INTRO}</p>

        <form class="form" onsubmit={signIn} novalidate>
          <label class="field">
            <span class="label">Administrator name</span>
            <input class="input" type="text" autocomplete="username" maxlength="64" bind:value={name} />
          </label>
          <label class="field">
            <span class="label">Six digits from your authenticator</span>
            <input class="input mono digits" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" bind:value={digits} />
          </label>

          {#if problem}<Callout tone="warn">{problem}</Callout>{/if}

          <button class="primary" type="submit" disabled={!signInReady || busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        {#if claimOpen}
          <!-- Only while the operator started the server asking for a fresh code. -->
          <button class="link" type="button" onclick={openClaim}>Make another administrator with a setup code</button>
        {/if}
        <p class="hint reset">{RESET_HINT}</p>
      {/if}
    </main>
  </div>
{/if}

<style>
  .gate {
    min-height: 100vh;
    background: var(--paper-bg);
    color: var(--ink-text);
    font-family: var(--font-text);
    padding: var(--space-6) var(--space-4) var(--space-8);
    box-sizing: border-box;
  }

  .door {
    max-width: 34rem;
    margin: 0 auto;
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
  }

  .brand {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    margin-bottom: var(--space-3);
  }

  .mark {
    width: 2rem;
    height: 2rem;
    border-radius: 0.5rem;
    background: linear-gradient(135deg, var(--indigo), var(--indigo-deep));
    box-shadow: var(--elevation);
    flex: none;
  }

  .wordmark {
    margin: 0;
    font-family: var(--font-display);
    font-weight: 560;
    font-size: 1.1rem;
    line-height: 1.2;
  }

  .tagline {
    margin: 0;
    font-size: 0.9rem;
    color: var(--ink-soft);
  }

  h1 {
    margin: 0;
    font-family: var(--font-display);
    font-weight: 560;
    font-size: 1.6rem;
    line-height: 1.2;
    text-wrap: balance;
  }

  .lede {
    margin: 0;
    color: var(--ink-soft);
    line-height: 1.6;
  }

  .form {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    padding: var(--space-5);
    background: var(--paper-sheet);
    border: 1px solid var(--hairline);
    border-radius: var(--radius);
  }

  .field {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    margin: 0;
    padding: 0;
    border: 0;
    min-width: 0;
  }

  .label {
    font-size: 0.9rem;
    font-weight: 600;
    padding: 0;
  }

  .hint {
    margin: 0;
    font-size: 0.85rem;
    line-height: 1.5;
    color: var(--ink-soft);
  }

  .input {
    width: 100%;
    box-sizing: border-box;
    padding: var(--space-2) var(--space-3);
    background: var(--paper-bg);
    color: var(--ink-text);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    font: inherit;
  }

  .mono {
    font-family: var(--font-mono);
    letter-spacing: 0.04em;
  }

  .digits {
    max-width: 10rem;
    font-variant-numeric: tabular-nums;
  }

  .auth .hint {
    margin-bottom: var(--space-2);
  }

  .seed {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-start;
    gap: var(--space-4);
  }

  .qr-tile {
    max-width: 100%;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--scan-paper);
    line-height: 0;
  }

  .qr { display: block; width: 11rem; height: 11rem; max-width: 100%; }
  .qr-paper { fill: var(--scan-paper); }
  .qr-ink { fill: var(--scan-ink); }

  .key {
    flex: 1 1 10rem;
    min-width: 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1) var(--space-2);
    align-content: flex-start;
  }

  .group {
    font-family: var(--font-mono);
    font-size: 0.9rem;
    letter-spacing: 0.08em;
    padding: 0.125rem var(--space-2);
    border: 1px solid var(--hairline);
    border-radius: var(--radius-sm);
    white-space: nowrap;
  }

  /* The global button.primary carries the colours; only placement lives here. */
  .primary {
    align-self: flex-start;
    font-weight: 600;
  }

  .link {
    align-self: flex-start;
    padding: 0;
    background: none;
    border: 0;
    color: var(--ink-text);
    text-decoration: underline;
    font: inherit;
    cursor: pointer;
  }

  .reset {
    margin-top: var(--space-2);
  }

  .input:focus-visible,
  .link:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }
</style>
