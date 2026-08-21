<script lang="ts">
  /*
   * SEATING SOMEBODY IN THE PRACTICE.
   *
   * ─── THE THING THIS FORM MUST NOT IMPLY ────────────────────────────────────────────────────
   *
   * That it hands somebody access. It does not, and the shape of the screen is the argument: the
   * role picker below carries the two cells from the access-control document's own role table, and
   * the standing correction sits above the fields rather than under the button, because a note read
   * after the decision has not informed it.
   *
   * What this request creates is a MEMBERSHIP: a row saying what a person may DO in this practice.
   * Reading anybody's material takes a grant — a key wrapped on that patient's device, for one
   * named person — and there is no route on this server that mints one from here.
   *
   * ─── WHY THE FORM DOES NOT CHECK WHETHER THE PERSON EXISTS ─────────────────────────────────
   *
   * Because the server does not, deliberately: checking a typed id against the credential table
   * would hand every administrator on the deployment an oracle for whether a given person has an
   * account anywhere on it. So an id that does not correspond to anyone is accepted and sits there
   * as an offer nobody will ever take. That is a real outcome of using this form and it is stated
   * on the form, rather than left to be discovered by an administrator wondering why a colleague
   * never appeared.
   *
   * ─── WHY THE AUTHENTICATOR CODE IS ASKED FOR HERE AND NOT AT REMOVAL ───────────────────────
   *
   * Adding a member changes who may be OFFERED access, which is where the design's annoyance budget
   * puts friction. Removing one is the safe direction and is deliberately free. The asymmetry is
   * the control, so the screen says why it is asking rather than presenting the code box as a
   * routine obstacle.
   */
  import { Callout, Card } from '../ui'
  import RolePicker from './RolePicker.svelte'
  import {
    PracticeClient,
    failureSentence,
    memberIdProblem,
    nothingChanged,
    type Member,
    type PracticeFailure,
  } from '../../practice/client'
  import { roleLabel, type OrgRoleWire } from '../../practice/orgRoles'
  import { SEAT_IS_AN_OFFER, WHY_SOME_ACTS_COST_MORE } from '../../practice/copy'

  let {
    client,
    orgId,
    session,
    onadded,
  }: {
    client: PracticeClient
    orgId: string
    session: { memberId: string; csrf: string } | null
    onadded?: () => void
  } = $props()

  let memberId = $state('')
  let role = $state<OrgRoleWire>('clinician')
  let stepUpCode = $state('')
  let working = $state(false)
  let seated = $state<Member | null>(null)
  let failure = $state<PracticeFailure | null>(null)
  /**
   * A problem this page found before sending anything, kept separate from `failure` on purpose.
   *
   * Routing it through the server-failure channel would print "the server refused the request" over
   * a request the server never saw — a small lie, and exactly the kind that sends somebody looking
   * at server logs for an event that does not exist.
   */
  let notSent = $state('')

  let idProblem = $derived(memberId === '' ? null : memberIdProblem(memberId))

  async function add() {
    if (!session) return
    failure = null
    notSent = ''
    seated = null
    const problem = memberIdProblem(memberId)
    if (problem) {
      notSent = problem
      return
    }
    working = true
    const result = await client.addMember(orgId, memberId.trim(), role, {
      csrf: session.csrf,
      stepUpCode,
    })
    working = false
    // Single-use on the server whether it was accepted or not, so it is cleared either way rather
    // than left to be resubmitted into a refusal.
    stepUpCode = ''
    if (result.ok) {
      seated = result.value
      memberId = ''
      onadded?.()
    } else {
      failure = result.failure
    }
  }
</script>

<Card title="Add a member">
  {#if !session}
    <p class="para">Sign in to add a member. The roster and the log can be read without signing in.</p>
  {/if}

  {#if orgId === ''}
    <p class="para">Name a practice first. This form seats somebody in one named practice.</p>
  {/if}

  <form
    class="form"
    onsubmit={(e) => {
      e.preventDefault()
      void add()
    }}
  >
    <label class="field" for="practice-new-member">
      <span class="field-label">
        Member id — the id this person signs in with. The server does not check that it belongs to
        anyone, so a mistyped id becomes an offer nobody can accept.
      </span>
      <input
        id="practice-new-member"
        class="text-input"
        type="text"
        spellcheck="false"
        bind:value={memberId}
      />
      {#if idProblem}<span class="problem">{idProblem}</span>{/if}
    </label>

    <RolePicker
      value={role}
      onpick={(r) => (role = r)}
      group="practice-role-add"
      legend="Role in this practice"
    />

    <p class="para">{WHY_SOME_ACTS_COST_MORE}</p>

    <label class="field" for="practice-add-stepup">
      <span class="field-label">Current code from your authenticator</span>
      <input
        id="practice-add-stepup"
        class="text-input"
        type="text"
        inputmode="numeric"
        autocomplete="one-time-code"
        spellcheck="false"
        bind:value={stepUpCode}
      />
    </label>

    <div class="controls">
      <button class="primary" type="submit" disabled={working || !session || orgId === ''}>
        {working ? 'Adding' : 'Add member'}
      </button>
    </div>
  </form>

  {#if notSent}
    <Callout tone="critical" title="Nothing was sent">
      <p class="para">{notSent}</p>
    </Callout>
  {/if}

  {#if failure}
    <!--
      The heading is the outcome, so it changes with what is known. Saying "not added" over a
      failure that is not evidence of anything would be this page guessing about a row that may
      well exist — see nothingChanged in the client.
    -->
    <Callout
      tone="critical"
      title={nothingChanged(failure)
        ? 'The member was not added'
        : 'Whether the member was added is not known from here'}
    >
      <p class="para">{failureSentence(failure)}</p>
      {#if !nothingChanged(failure)}
        <p class="para">
          Read the roster before trying again. Adding the same person twice is refused by the
          server, but a second attempt spends another authenticator code either way.
        </p>
      {/if}
    </Callout>
  {/if}

  {#if seated}
    <!--
      A statement of what the server recorded, in its own terms: an id, a role, and the fact that
      the seat is not yet a membership. No tick and no tone — see the house rules.
    -->
    <div class="seated">
      <p class="seated-head">
        {seated.memberId} is seated in this practice as {roleLabel(seated.role)}.
      </p>
      <p class="para">{SEAT_IS_AN_OFFER}</p>
      <p class="para">
        Nothing was sent to them by this request, and nothing was fetched for them. Whether they can
        read anybody's material is not decided here or by anyone in this practice.
      </p>
    </div>
  {/if}
</Card>

<style>
  .para {
    margin: 0 0 var(--space-3);
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

  /* --border-strong: a control whose affordance depends on its border is held to a 3:1 boundary. */
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

  .seated {
    margin-top: var(--space-4);
    padding-top: var(--space-3);
    border-top: 1px solid var(--hairline);
  }

  .seated-head {
    margin: 0 0 var(--space-2);
    color: var(--ink-text);
    font-size: 0.95rem;
    font-weight: 600;
    line-height: 1.5;
    overflow-wrap: anywhere;
  }
</style>
