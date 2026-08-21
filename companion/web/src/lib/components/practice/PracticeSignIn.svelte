<script lang="ts">
  /*
   * SIGNING IN TO A PRACTICE — which is signing in to the server, and then naming a practice.
   *
   * ─── WHY THERE IS NO PRACTICE-SPECIFIC CREDENTIAL ──────────────────────────────────────────
   *
   * The org routes take the acting person's identity from the session cookie: `resolveActor` reads
   * `session.credentialId` and looks up a membership for THAT id in the practice named in the path.
   * So a member id is a portal credential id — the same one a clinician signs in with — and there
   * is no separate practice account to create. Two consequences worth stating on the screen rather
   * than discovering:
   *
   *   Standing is per practice, looked up fresh on every request. Somebody who administers one
   *   practice is a stranger at every other practice on the same server, and there is no global
   *   administrator anywhere in this design.
   *
   *   Being signed in is not being a member. The sign-in below can succeed while every request this
   *   console makes comes back "not found", because the credential is real and the membership is
   *   not. That is not a bug and it is not this page guessing — see the client's 404 sentence.
   *
   * ─── WHY IT REUSES PortalClient RATHER THAN POSTING TO /v1/totp/verify ─────────────────────
   *
   * There is one authentication path in this product and it is `PortalClient.loginTotp`. A second
   * implementation of the same three lines would be a second thing to audit, and the first one to
   * fall behind when the session contract changes. This component holds no credential of its own:
   * the code is read from the field, handed to the client, and never stored anywhere.
   *
   * ─── WHAT IS HELD, AND FOR HOW LONG ────────────────────────────────────────────────────────
   *
   * The anti-forgery token, in memory, for as long as this page is open. The session cookie itself
   * is HttpOnly and this page cannot read it, which produces one asymmetry the copy names: after a
   * reload the cookie is still there, so the roster and the log can still be read, while anything
   * that changes something needs the token and therefore a fresh sign-in. Better said than
   * discovered halfway through removing somebody.
   */
  import { Callout, Card } from '../ui'
  import { PortalClient } from '../../therapist/session'
  import { SIGN_IN_IS_THE_PORTAL_CREDENTIAL } from '../../practice/copy'
  import { memberIdProblem } from '../../practice/client'

  let {
    baseUrl = '',
    fetchImpl,
    onsignedin,
  }: {
    baseUrl?: string
    fetchImpl?: typeof fetch
    onsignedin: (session: { memberId: string; csrf: string }) => void
  } = $props()

  let memberId = $state('')
  let code = $state('')
  let refusal = $state('')
  let working = $state(false)

  let idProblem = $derived(memberId === '' ? null : memberIdProblem(memberId))

  async function signIn() {
    refusal = ''
    const problem = memberIdProblem(memberId)
    if (problem) {
      refusal = problem
      return
    }
    working = true
    try {
      const client = new PortalClient(baseUrl, fetchImpl ?? fetch)
      const result = await client.loginTotp(memberId.trim(), code.trim())
      // The code is cleared whatever happened. A TOTP step is single-use on the server, so a code
      // left in the field after a refusal is only ever going to be refused again — and leaving it
      // on screen invites somebody to press the button twice and blame the server.
      code = ''
      if (!result.ok || !result.session) {
        refusal = result.error ?? 'The sign-in was not accepted.'
        return
      }
      onsignedin({ memberId: memberId.trim(), csrf: result.session.csrf })
    } catch (e) {
      // A rejected fetch here is unambiguous in the direction that matters: no session was
      // established on this page, whatever happened at the other end.
      refusal = `No answer from the server (${e instanceof Error ? e.message : String(e)}).`
    } finally {
      working = false
    }
  }
</script>

<Card title="Sign in">
  <p class="para">{SIGN_IN_IS_THE_PORTAL_CREDENTIAL}</p>

  <form
    class="form"
    onsubmit={(e) => {
      e.preventDefault()
      void signIn()
    }}
  >
    <label class="field" for="practice-member-id">
      <span class="field-label">Your member id — the id you sign in to the portal with</span>
      <input
        id="practice-member-id"
        class="text-input"
        type="text"
        autocomplete="username"
        spellcheck="false"
        bind:value={memberId}
      />
      {#if idProblem}<span class="problem">{idProblem}</span>{/if}
    </label>

    <label class="field" for="practice-totp">
      <span class="field-label">Current code from your authenticator</span>
      <input
        id="practice-totp"
        class="text-input"
        type="text"
        inputmode="numeric"
        autocomplete="one-time-code"
        spellcheck="false"
        bind:value={code}
      />
    </label>

    <div class="controls">
      <button class="primary" type="submit" disabled={working}>
        {working ? 'Signing in' : 'Sign in'}
      </button>
    </div>
  </form>

  {#if refusal}
    <Callout tone="critical" title="Not signed in">
      <p class="para">{refusal}</p>
    </Callout>
  {/if}

  {#snippet footer()}
    This page keeps the anti-forgery token in memory only. Reloading it leaves the session cookie in
    place — the roster and the log can still be read — but anything that changes something needs a
    fresh sign-in.
  {/snippet}
</Card>

<style>
  .para {
    margin: 0 0 var(--space-4);
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.55;
  }

  .form {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    margin-bottom: var(--space-4);
  }

  .field {
    display: block;
    max-width: 32rem;
  }

  .field-label {
    display: block;
    margin-bottom: var(--space-1);
    color: var(--ink-soft);
    font-size: 0.85rem;
    line-height: 1.5;
  }

  /* --border-strong rather than --hairline: a control whose affordance depends on its border is the
     one case the design system holds to a 3:1 boundary. */
  .text-input {
    width: 100%;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--paper-sheet);
    color: var(--ink-text);
    font: inherit;
    font-family: var(--font-mono);
    font-size: 0.9rem;
  }

  .problem {
    display: block;
    margin-top: var(--space-1);
    color: var(--clay);
    font-size: 0.8rem;
    line-height: 1.5;
  }

  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
</style>
