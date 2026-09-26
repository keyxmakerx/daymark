<script lang="ts">
  /*
   * The owner's email, recovery half (COMPANION_SECURITY.md §6, "Owner notifications and
   * server-access recovery"): the unauthenticated owner-access-token recovery flow. This
   * recovers SERVER ACCESS ONLY — the server is zero-knowledge and can never reset the PIN or
   * E2EE passphrase; a recovered token still cannot decrypt anything. Deliberately requires no
   * login: that is the point of a recovery path.
   *
   * AND, ON A SOLO SERVER, THE ONE PLACE TO REGISTER THE ADDRESS IT SENDS TO (#330). The address
   * is set through the owner route the owner console's Notifications tab uses, and a solo shape
   * withholds that console while still serving the route. So where the console is withheld this
   * card holds the setting — with the access token the sync card proved, never a second prompt for
   * it — and where the console is offered, it points there. Every word and every branch comes from
   * lib/owner/recoveryEmail.ts, where each state is a node test.
   */
  import { requestAccessRecovery, confirmAccessRecovery, PortalClient } from '../../sync/portal'
  import type { OwnerRouteId } from '../../onboarding/audience'
  import {
    BEFORE_YOU_NEED_IT,
    EMAIL_LABEL,
    EMAIL_STORED_NOTE,
    EMAIL_MISSING,
    LOAD_FAILED,
    REGISTER,
    REMOVE,
    SAVE_FAILED,
    recoverCardView,
    registerRecoveryEmail,
    registeredStatement,
    type OwnerConnection,
  } from '../../owner/recoveryEmail'
  import { Card, Callout } from '../ui'

  let {
    /** Whether the owner's page offers the owner console (`offersRoute('owner', …)`). */
    ownerConsoleOffered = true,
    /** The server address and access token a sync fetch proved this visit, or null. */
    connection = null,
    /** Opens another of the owner's entry points: the sync card, or the owner console. */
    onopen = undefined,
  }: {
    ownerConsoleOffered?: boolean
    connection?: OwnerConnection | null
    onopen?: (route: OwnerRouteId) => void
  } = $props()

  let serverUrl = $state('')
  let email = $state('')
  let confirmToken = $state('')
  let newToken = $state('')
  let requestStatus = $state('')
  let confirmStatus = $state('')
  let busy = $state(false)
  let error = $state('')

  /* The registration half. `registered` is what the server last said, never what was typed. */
  let registered = $state<string | null>(null)
  let draft = $state('')
  let setupBusy = $state(false)
  let setupError = $state('')

  const view = $derived(recoverCardView({ ownerConsoleOffered, connected: connection !== null, registered }))

  const uid = $props.id()

  /*
   * Read the registered address once the card holds the setting and has a token to ask with. The
   * effect reads only the two props; what it writes is state it never reads, so the write cannot
   * re-run it, and a connection that changes mid-flight cancels the answer meant for the old one.
   */
  $effect(() => {
    if (ownerConsoleOffered || !connection) return
    const c = connection
    let cancelled = false
    void (async () => {
      try {
        const settings = await new PortalClient(c.serverUrl, c.token).getNotificationSettings()
        if (!cancelled) registered = settings.email
      } catch {
        if (!cancelled) setupError = LOAD_FAILED
      }
    })()
    return () => {
      cancelled = true
    }
  })

  /** Register the typed address, or remove the registered one with null. */
  async function save(address: string | null) {
    if (!connection) return
    setupError = ''
    setupBusy = true
    try {
      registered = await registerRecoveryEmail(new PortalClient(connection.serverUrl, connection.token), address)
      draft = ''
    } catch {
      setupError = SAVE_FAILED
    } finally {
      setupBusy = false
    }
  }

  // Progressive enhancement: a confirm link lands on this page as `#t=<token>`; prefill it.
  if (typeof window !== 'undefined') {
    const match = /(?:^|[#&])t=([^&]+)/.exec(window.location.hash)
    if (match) confirmToken = decodeURIComponent(match[1])
  }

  async function request() {
    error = ''
    requestStatus = ''
    if (!email.trim()) {
      error = EMAIL_MISSING
      return
    }
    busy = true
    try {
      // requestAccessRecovery never inspects the server's response status (the server always
      // replies 202 regardless of match, by design) — the only way this throws is a genuine
      // network-level failure (unreachable server, bad URL), which is safe to surface distinctly
      // without leaking anything about whether the email matched.
      await requestAccessRecovery(serverUrl, email.trim())
      requestStatus = 'If that email is registered, a recovery link was sent. Check your inbox.'
    } catch {
      error = 'Could not reach that server. Check the server URL and try again.'
    } finally {
      busy = false
    }
  }

  async function confirm() {
    error = ''
    confirmStatus = ''
    newToken = ''
    if (!confirmToken.trim()) {
      error = 'Paste the confirmation token from your recovery email link.'
      return
    }
    busy = true
    try {
      const result = await confirmAccessRecovery(serverUrl, confirmToken.trim())
      newToken = result.newToken
      confirmStatus = 'Your access token was re-issued. Copy it now — it is shown only once.'
    } catch (e) {
      error = e instanceof Error ? e.message : 'That recovery link is invalid, expired, or already used.'
    } finally {
      busy = false
    }
  }

  async function copyToken() {
    if (!newToken) return
    try {
      await navigator.clipboard.writeText(newToken)
    } catch {
      /* clipboard access denied; the token is still selectable/visible in the field */
    }
  }
</script>

<div class="recover">
  <Card title="Recover server access">
    <div class="stack">
      <!-- The card's lead, on a solo server only: where the console holds the setting, it has none. -->
      {#if view.summary}<p class="hint">{view.summary}</p>{/if}

      <section class="part" aria-labelledby="{uid}-before">
        <h3 id="{uid}-before">{BEFORE_YOU_NEED_IT}</h3>
        {#if view.setup.kind === 'register'}
          <!-- Solo, with a token: this card holds the setting. -->
          <p class="para">{view.setup.lede}</p>
          {#if view.setup.registered}<p class="status">{registeredStatement(view.setup.registered)}</p>{/if}
          <label class="field">
            <span>{EMAIL_LABEL} <em>({EMAIL_STORED_NOTE})</em></span>
            <input type="email" bind:value={draft} placeholder="you@example.com" autocomplete="email" />
          </label>
          <div class="row">
            <button class="primary" type="button" onclick={() => save(draft.trim())} disabled={setupBusy || !draft.trim()}>{REGISTER}</button>
            {#if view.setup.canRemove}
              <button type="button" onclick={() => save(null)} disabled={setupBusy}>{REMOVE}</button>
            {/if}
          </div>
        {:else}
          <!--
            A pointer and a link: to the sync card, where a solo server's token is proved, or to the
            owner console, which holds the setting wherever it is offered. The view decides which, so
            a solo page never names the console.
          -->
          <p class="para">{view.setup.text}</p>
          {#if onopen}
            {@const link = view.setup.link}
            <button class="go" type="button" onclick={() => onopen?.(link.route)}>{link.label}</button>
          {/if}
        {/if}
        {#if setupError}<Callout tone="critical">{setupError}</Callout>{/if}
      </section>

      <section class="part" aria-labelledby="{uid}-lost">
        <h3 id="{uid}-lost">{view.lostHeading}</h3>
        {#each view.lostLede as line (line)}<p class="para">{line}</p>{/each}
        <p class="hint">
          This recovers <strong>server access only</strong> — it cannot restore your PIN or your
          end-to-end-encryption passphrase, and it cannot decrypt anything on its own.
        </p>

        <label class="field">
          <span>Server URL <em>(blank = this server)</em></span>
          <input type="url" bind:value={serverUrl} placeholder="https://daymark.example.com" autocomplete="off" />
        </label>

        <fieldset class="step">
          <legend>1. Request a recovery link</legend>
          <label class="field">
            <span>Registered email</span>
            <input type="email" bind:value={email} placeholder="you@example.com" autocomplete="off" />
          </label>
          <button class="primary" onclick={request} disabled={busy}>{busy ? 'Requesting…' : 'Send recovery link'}</button>
          {#if requestStatus}<p class="status">{requestStatus}</p>{/if}
        </fieldset>

        <fieldset class="step">
          <legend>2. Confirm the link</legend>
          <label class="field">
            <span>Confirmation token <em>(from the link in your email)</em></span>
            <input type="text" bind:value={confirmToken} autocomplete="off" />
          </label>
          <button class="primary" onclick={confirm} disabled={busy}>{busy ? 'Confirming…' : 'Confirm and re-issue'}</button>
          {#if confirmStatus}<p class="status">{confirmStatus}</p>{/if}
          {#if newToken}
            <label class="field">
              <span>New owner access token <em>(shown once — save it now)</em></span>
              <input type="text" readonly value={newToken} aria-label="New owner access token" />
            </label>
            <button onclick={copyToken}>Copy token</button>
          {/if}
        </fieldset>
      </section>

      {#if error}<Callout tone="critical">{error}</Callout>{/if}
    </div>
  </Card>
</div>

<style>
  .recover { max-width: 34rem; }
  .stack { display: flex; flex-direction: column; gap: var(--space-4); }
  .hint { margin: 0; color: var(--ink-soft); font-size: 0.9rem; }
  .part { display: flex; flex-direction: column; gap: var(--space-3); min-width: 0; }
  .part h3 { margin: 0; font-size: 1rem; color: var(--ink-text); }
  .para { margin: 0; font-size: 0.9rem; line-height: 1.55; color: var(--ink-text); }
  .row { display: flex; flex-wrap: wrap; gap: var(--space-2); }
  .field { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .field em { color: var(--text-subtle); font-style: normal; }
  input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  input[readonly] { font-family: var(--font-mono); font-size: 0.8rem; }
  .step { display: flex; flex-direction: column; gap: var(--space-3); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .step legend { padding: 0 var(--space-2); color: var(--ink-soft); font-size: 0.85rem; }
  /* Confirmation, never green: solid-ink family only. A recovery that worked is stated in
     words — the product has no success hue to spend here. */
  .status { margin: 0; font-size: 0.85rem; color: var(--ink-soft); }
  /* A link to another entry point on this page, drawn as the orientation draws its links: it moves
     the page, it does not submit anything. */
  .go {
    align-self: flex-start;
    padding: 0;
    background: none;
    border: 0;
    color: var(--link);
    font: inherit;
    font-size: 0.9rem;
    text-decoration: underline;
    cursor: pointer;
  }
  .go:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
</style>
