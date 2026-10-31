<script lang="ts">
  /*
   * THE OWNER'S OWN SIGN-IN, INSIDE THE CONSOLE (#324). Two faces, never both:
   *
   * - While the console is connected with the shared access token and the server has no account
   *   yet, it offers "Make your own sign-in": a name, an authenticator key drawn here and shown
   *   once, and six digits proving the authenticator took it. Success hands the console a session
   *   in place of the token, and the token stops signing anybody in.
   * - While the console is signed in to the account, it lists the command-line writer's
   *   credentials, makes one (shown once, then held nowhere) and revokes one.
   *
   * The seed is zeroed when the account is made, and a minted credential is dropped from the page
   * the moment the person dismisses it. No refusal repeats a name, a code, a seed or a credential
   * (owner/account.ts).
   */
  import { Callout } from '../ui'
  import { base32, groupSecret, newSeed, otpauthUri, validName, validTotp } from '../../admin/claim'
  import { qrDrawing } from '../../phones/qr'
  import { REVOKE_CAVEAT } from '../../pairing/copy'
  import {
    ACCOUNT_MADE,
    CLI_LEDE,
    CLI_REVOKED,
    CLI_TITLE,
    MAKE_ACCOUNT_LEDE,
    MAKE_ACCOUNT_SEED,
    MAKE_ACCOUNT_TITLE,
    isSession,
    ownerAccountApi,
    type AccountSession,
    type CliCredential,
    type OwnerAccountApi,
    type OwnerCredential,
  } from '../../owner/account'

  let {
    credential,
    baseUrl = '',
    api = ownerAccountApi(baseUrl),
    onsignedin,
    onsignedout,
  }: {
    credential: OwnerCredential
    baseUrl?: string
    api?: OwnerAccountApi
    onsignedin: (session: AccountSession) => void
    onsignedout: () => void
  } = $props()

  /* ── Make your own sign-in ─────────────────────────────────────────────────────────────── */

  let offerAccount = $state(false)
  let name = $state('')
  let digits = $state('')
  let seed = $state<Uint8Array | null>(null)
  let made = $state(false)
  let problem = $state('')
  let busy = $state(false)

  const host = typeof location === 'undefined' ? '' : location.host
  const secret = $derived(seed ? base32(seed) : '')
  const qr = $derived(secret ? qrDrawing(otpauthUri(secret, host, 'owner')) : null)
  const createReady = $derived(validName(name) && validTotp(digits) && seed !== null)

  $effect(() => {
    // Only a token-connected console is offered the account; a session already is one.
    if (isSession(credential) || credential === '') {
      offerAccount = false
      return
    }
    void api.status().then((out) => {
      offerAccount = out.ok && !out.value.exists && out.value.tokenAccepted
      if (offerAccount && seed === null) seed = newSeed()
    })
  })

  async function makeAccount(event: SubmitEvent) {
    event.preventDefault()
    if (isSession(credential) || !seed || !createReady || busy) return
    busy = true
    problem = ''
    try {
      const out = await api.create(credential, { name, seed, totpCode: digits })
      if (!out.ok) {
        problem = out.message
        return
      }
      seed.fill(0)
      seed = null
      digits = ''
      made = true
      offerAccount = false
      onsignedin({ csrf: out.value.csrf })
    } finally {
      busy = false
    }
  }

  /* ── Command-line credentials ──────────────────────────────────────────────────────────── */

  let creds = $state<CliCredential[]>([])
  let label = $state('')
  let minted = $state<string | null>(null)
  let cliNote = $state('')
  let cliProblem = $state('')

  async function refresh() {
    const out = await api.listCli()
    if (out.ok) creds = out.value
    else cliProblem = out.message
  }

  $effect(() => {
    if (isSession(credential)) void refresh()
  })

  async function mint(event: SubmitEvent) {
    event.preventDefault()
    if (!isSession(credential) || busy) return
    busy = true
    cliProblem = ''
    cliNote = ''
    try {
      const out = await api.mintCli(credential, label)
      if (!out.ok) {
        cliProblem = out.message
        return
      }
      minted = out.value.token
      label = ''
      await refresh()
    } finally {
      busy = false
    }
  }

  async function revoke(credentialId: string) {
    if (!isSession(credential) || busy) return
    busy = true
    cliProblem = ''
    cliNote = ''
    try {
      const out = await api.revokeCli(credential, credentialId)
      if (out.ok) cliNote = CLI_REVOKED
      else cliProblem = out.message
      await refresh()
    } finally {
      busy = false
    }
  }

  async function signOut() {
    if (!isSession(credential)) return
    await api.signOut(credential)
    minted = null
    onsignedout()
  }

  const when = (s: number) => new Date(s * 1000).toLocaleString()
</script>

{#if made}
  <Callout tone="info">{ACCOUNT_MADE}</Callout>
{/if}

{#if offerAccount}
  <form class="account" onsubmit={makeAccount}>
    <h3>{MAKE_ACCOUNT_TITLE}</h3>
    <p class="hint">{MAKE_ACCOUNT_LEDE}</p>

    <label class="field">
      <span class="label">Your name, as you will type it to sign in</span>
      <input class="input" type="text" autocomplete="username" maxlength="64" bind:value={name} />
    </label>

    <fieldset class="field auth">
      <legend class="label">Your authenticator</legend>
      <p class="hint">{MAKE_ACCOUNT_SEED}</p>
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
      <input class="input digits" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" bind:value={digits} />
    </label>

    {#if problem}<Callout tone="warn">{problem}</Callout>{/if}
    <button type="submit" disabled={!createReady || busy}>Make my sign-in</button>
  </form>
{/if}

{#if isSession(credential)}
  <section class="cli">
    <h3>{CLI_TITLE}</h3>
    <p class="hint">{CLI_LEDE}</p>

    {#if minted}
      <div class="minted">
        <p class="hint">Copy it now. This console does not show it again.</p>
        <code class="token">{minted}</code>
        <button type="button" onclick={() => (minted = null)}>I have copied it</button>
      </div>
    {/if}

    <form class="mint" onsubmit={mint}>
      <label class="field">
        <span class="label">A label, so you know which computer holds it</span>
        <input class="input" type="text" maxlength="64" bind:value={label} />
      </label>
      <button type="submit" disabled={!validName(label) || busy}>Make a credential</button>
    </form>

    {#if creds.length}
      <ul class="creds">
        {#each creds as c (c.credentialId)}
          <li>
            <span class="clabel">{c.label}</span>
            <span class="cwhen">made {when(c.createdAt)}{#if c.revokedAt !== null}, revoked {when(c.revokedAt)}{/if}</span>
            {#if c.revokedAt === null}
              <button type="button" onclick={() => revoke(c.credentialId)} disabled={busy}>Revoke</button>
            {/if}
          </li>
        {/each}
      </ul>
      <p class="hint">{REVOKE_CAVEAT}</p>
    {/if}

    {#if cliNote}<Callout tone="info">{cliNote}</Callout>{/if}
    {#if cliProblem}<Callout tone="warn">{cliProblem}</Callout>{/if}

    <button type="button" class="signout" onclick={signOut}>Sign out</button>
  </section>
{/if}

<style>
  .account,
  .cli {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    margin-top: var(--space-4);
  }
  h3 { margin: 0; font-size: 1.1rem; }
  .hint { margin: 0; color: var(--ink-soft); }
  .field { display: flex; flex-direction: column; gap: var(--space-1); border: 0; padding: 0; margin: 0; }
  .seed { display: flex; flex-wrap: wrap; align-items: flex-start; gap: var(--space-4); }
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
  .key { flex: 1 1 10rem; min-width: 0; display: flex; flex-wrap: wrap; gap: var(--space-1) var(--space-2); }
  .group, .token, .digits { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
  .token { overflow-wrap: anywhere; }
  .minted { display: flex; flex-direction: column; gap: var(--space-2); }
  .creds { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: var(--space-2); }
  .creds li { display: flex; flex-wrap: wrap; gap: var(--space-2); align-items: baseline; }
  .cwhen { color: var(--ink-soft); }
  .signout { align-self: flex-start; }
</style>
