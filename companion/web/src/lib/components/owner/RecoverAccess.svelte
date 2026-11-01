<script lang="ts">
  /*
   * THE RECOVER CARD (COMPANION_SECURITY.md §5.9): getting back in with the recovery code, and the
   * email that is told when someone does.
   *
   * GETTING BACK IN (#325). The owner types their recovery code, a new name and a new authenticator.
   * owner/recoverSignIn.ts asks for a challenge, opens the wrapped key's recovery slot with the code,
   * signs the challenge for this page's own origin and sends only the signature. The server replaces
   * the sign-in and signs out every earlier one. No URL field: the proof is bound to this origin,
   * and the session it starts is a cookie for this origin alone. The code is checked group by group
   * before anything is fetched (components/recovery/groups.ts), and cleared, with the authenticator
   * key zeroed, once the sign-in is replaced.
   *
   * THE EMAIL (#330). On a solo server, the one place to register the address notices go to: the
   * address is set through the owner route the owner console's Notifications tab uses, and a solo
   * shape withholds that console while still serving the route. So where the console is withheld this
   * card holds the setting — with the connection the sync card proved, never a second prompt for it —
   * and where the console is offered, it points there. Every word and every branch comes from
   * lib/owner/recoveryEmail.ts, where each state is a node test.
   */
  import { PortalClient } from '../../sync/portal'
  import type { OwnerRouteId } from '../../onboarding/audience'
  import {
    BACK_IN,
    BEFORE_YOU_NEED_IT,
    CODE_STAYS_HERE,
    DIGITS_LABEL,
    EMAIL_LABEL,
    EMAIL_STORED_NOTE,
    GET_BACK_IN,
    GETTING_BACK_IN,
    LOAD_FAILED,
    NEW_AUTHENTICATOR,
    NEW_AUTHENTICATOR_LEDE,
    NEW_NAME_LABEL,
    REGISTER,
    REMOVE,
    SAVE_FAILED,
    recoverCardView,
    registerRecoveryEmail,
    registeredStatement,
    type OwnerConnection,
  } from '../../owner/recoveryEmail'
  import { ownerAccountApi, type OwnerAccountApi } from '../../owner/account'
  import { proveRecovery } from '../../owner/recoveryKey'
  import { recoverSignIn } from '../../owner/recoverSignIn'
  import { base32, groupSecret, newSeed, otpauthUri, validName, validTotp } from '../../admin/claim'
  import { qrDrawing } from '../../phones/qr'
  import GroupEntry from '../recovery/GroupEntry.svelte'
  import { emptyGroups, firstGroupProblem, groupsToTyped, type GroupProblem } from '../recovery/groups'
  import { HOW_ENTRY_WORKS } from '../recovery/copy'
  import { Card, Callout } from '../ui'

  let {
    /** Whether the owner's page offers the owner console (`offersRoute('owner', …)`). */
    ownerConsoleOffered = true,
    /** The server address and credential a sync fetch proved this visit, or null. */
    connection = null,
    /** Opens another of the owner's entry points: the sync card, or the owner console. */
    onopen = undefined,
    /** This page's own server's account routes; a test or a story may hand in another. */
    api = ownerAccountApi(),
  }: {
    ownerConsoleOffered?: boolean
    connection?: OwnerConnection | null
    onopen?: (route: OwnerRouteId) => void
    api?: OwnerAccountApi
  } = $props()

  /* The registration half. `registered` is what the server last said, never what was typed. */
  let registered = $state<string | null>(null)
  let draft = $state('')
  let setupBusy = $state(false)
  let setupError = $state('')

  const view = $derived(recoverCardView({ ownerConsoleOffered, connected: connection !== null, registered }))

  const uid = $props.id()

  /*
   * Read the registered address once the card holds the setting and has a connection to ask with.
   * The effect reads only the two props; what it writes is state it never reads, so the write cannot
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

  /* The getting-back-in half. */
  let groups = $state(emptyGroups())
  let problem = $state<GroupProblem | null>(null)
  let name = $state('')
  let digits = $state('')
  let seed = $state<Uint8Array | null>(newSeed())
  let busy = $state(false)
  let fault = $state('')
  let backIn = $state(false)

  const host = typeof location === 'undefined' ? '' : location.host
  const secret = $derived(seed ? base32(seed) : '')
  const qr = $derived(secret ? qrDrawing(otpauthUri(secret, host, 'owner')) : null)
  const ready = $derived(validName(name) && validTotp(digits) && seed !== null)

  function updateGroups(next: string[]) {
    groups = next
    problem = null
    fault = ''
  }

  async function getBackIn(event: SubmitEvent) {
    event.preventDefault()
    if (!seed || !ready || busy) return
    const found = firstGroupProblem(groups)
    if (found) {
      problem = found
      return
    }
    problem = null
    fault = ''
    busy = true
    try {
      const out = await recoverSignIn(
        { recoveryChallenge: () => api.recoveryChallenge(), prove: proveRecovery, recover: (req) => api.recover(req) },
        { typedCode: groupsToTyped(groups), address: location.origin, name, seed, totpCode: digits },
      )
      if (!out.ok) {
        fault = out.message
        return
      }
      seed.fill(0)
      seed = null
      groups = emptyGroups()
      digits = ''
      backIn = true
    } finally {
      busy = false
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
          <!-- Solo, with a connection: this card holds the setting. -->
          <p class="para">{view.setup.lede}</p>
          <p class="hint">{view.setup.note}</p>
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
            A pointer and a link: to the sync card, where a solo server's connection is proved, or to
            the owner console, which holds the setting wherever it is offered. The view decides which,
            so a solo page never names the console.
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

        {#if backIn}
          <p class="status" role="status">{BACK_IN}</p>
          {#if onopen}
            {@const after = view.afterRecovery}
            <button class="go" type="button" onclick={() => onopen?.(after.route)}>{after.label}</button>
          {/if}
        {:else}
          <form class="part" onsubmit={getBackIn}>
            <p class="hint">{HOW_ENTRY_WORKS}</p>
            <GroupEntry
              {groups}
              onchange={updateGroups}
              problemGroup={problem?.group ?? null}
              describedBy="{uid}-code-message"
              disabled={busy}
              legend="The six groups of your recovery code"
            />
            <p class="hint">{CODE_STAYS_HERE}</p>
            <div id="{uid}-code-message" role="status" aria-live="polite">
              {#if problem}
                <Callout tone="warn" title={problem.message}>
                  {#if problem.detail}<p class="para">{problem.detail}</p>{/if}
                </Callout>
              {/if}
            </div>

            <label class="field">
              <span>{NEW_NAME_LABEL}</span>
              <input type="text" autocomplete="username" maxlength="64" bind:value={name} />
            </label>

            <fieldset class="step">
              <legend>{NEW_AUTHENTICATOR}</legend>
              <p class="hint">{NEW_AUTHENTICATOR_LEDE}</p>
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
              <span>{DIGITS_LABEL}</span>
              <input class="digits" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" bind:value={digits} />
            </label>

            {#if fault}<Callout tone="warn">{fault}</Callout>{/if}
            <button class="primary" type="submit" disabled={!ready || busy}>{busy ? GETTING_BACK_IN : GET_BACK_IN}</button>
          </form>
        {/if}
      </section>
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
  .step { display: flex; flex-direction: column; gap: var(--space-3); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .step legend { padding: 0 var(--space-2); color: var(--ink-soft); font-size: 0.85rem; }
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
  .group, .digits { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
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
