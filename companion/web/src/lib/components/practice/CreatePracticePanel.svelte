<script lang="ts">
  /*
   * NAMING A PRACTICE, AND SEATING ITS FIRST ADMINISTRATOR — the one act on this console that is
   * not performed by a member of a practice.
   *
   * ─── WHOSE CREDENTIAL THIS IS ──────────────────────────────────────────────────────────────
   *
   * `POST /v1/orgs` is gated on the deployment's bearer token, not on a practice session, because
   * before this request runs there is no practice to be a member of. In the access-control
   * document's vocabulary that is the platform plane: whoever runs the server, who by the role
   * catalog reads no clinical content by design. In this build the same token is also the owner's
   * own credential, because a self-hosted server has exactly one of each — a conflation inherited
   * from the deployment model rather than introduced here, and named on the screen so that nobody
   * reads this panel as evidence that a practice administrator is also a reader.
   *
   * ─── WHY THE PRACTICE ID IS NOT A FIELD ────────────────────────────────────────────────────
   *
   * The server mints it. A caller-chosen id could collide with something else's, and the id is what
   * a whole audit chain is keyed on. So this form has two fields and the identifier arrives in the
   * answer — which is also why the answer is rendered and kept on screen rather than flashed: it is
   * the only copy of a value the person now needs, and there is no route that lists practices for
   * them to find it again.
   *
   * ─── WHY THE FIRST ADMINISTRATOR IS SEATED IN THE SAME BREATH ──────────────────────────────
   *
   * Adding members is the thing administrators do, so a practice created without one would be a
   * permanently stuck row that only the operator's token could unstick. The server does the two in
   * one transaction; this form asks for both because it has to.
   */
  import { Callout, Card } from '../ui'
  import {
    PracticeClient,
    failureSentence,
    formatInstant,
    memberIdProblem,
    nothingChanged,
    practiceNameProblem,
    type Practice,
    type PracticeFailure,
  } from '../../practice/client'
  import { CREATE_USES_THE_SERVER_TOKEN, SEAT_IS_AN_OFFER } from '../../practice/copy'

  let {
    client,
    onusepractice,
  }: {
    client: PracticeClient
    /** Hands the new practice's id up to the console, so the next screen is already pointed at it. */
    onusepractice: (orgId: string) => void
  } = $props()

  let name = $state('')
  let adminMemberId = $state('')
  let token = $state('')
  let working = $state(false)
  let created = $state<Practice | null>(null)
  let failure = $state<PracticeFailure | null>(null)
  /**
   * A problem found before anything was sent, reported separately from a server refusal.
   *
   * The two look the same to a person filling in a form and are not the same event: printing "the
   * server refused the request" over a request that was never made is the kind of small lie that
   * sends somebody reading server logs for something that never happened.
   */
  let notSent = $state('')

  let nameProblem = $derived(name === '' ? null : practiceNameProblem(name))
  let idProblem = $derived(adminMemberId === '' ? null : memberIdProblem(adminMemberId))

  async function create() {
    failure = null
    notSent = ''
    created = null
    const problem = practiceNameProblem(name) ?? memberIdProblem(adminMemberId)
    if (problem) {
      notSent = problem
      return
    }
    working = true
    const result = await client.createPractice(name.trim(), adminMemberId.trim(), token.trim())
    working = false
    if (result.ok) {
      created = result.value
      // The token is dropped as soon as it has been spent. It is the deployment's credential and
      // there is no reason for it to sit in a field behind whatever this browser does next.
      token = ''
    } else {
      failure = result.failure
    }
  }
</script>

<Card title="Create a practice">
  <p class="para">{CREATE_USES_THE_SERVER_TOKEN}</p>

  <form
    class="form"
    onsubmit={(e) => {
      e.preventDefault()
      void create()
    }}
  >
    <label class="field" for="practice-name">
      <span class="field-label">Practice name — shown to its members</span>
      <input id="practice-name" class="text-input" type="text" bind:value={name} />
      {#if nameProblem}<span class="problem">{nameProblem}</span>{/if}
    </label>

    <label class="field" for="practice-first-admin">
      <span class="field-label">
        First administrator — the member id of the person who will manage membership
      </span>
      <input
        id="practice-first-admin"
        class="text-input"
        type="text"
        spellcheck="false"
        bind:value={adminMemberId}
      />
      {#if idProblem}<span class="problem">{idProblem}</span>{/if}
    </label>

    <label class="field" for="practice-bearer">
      <span class="field-label">The server’s provisioning token</span>
      <input
        id="practice-bearer"
        class="text-input"
        type="password"
        autocomplete="off"
        spellcheck="false"
        bind:value={token}
      />
    </label>

    <div class="controls">
      <button class="primary" type="submit" disabled={working}>
        {working ? 'Creating' : 'Create practice'}
      </button>
    </div>
  </form>

  {#if notSent}
    <Callout tone="critical" title="Nothing was sent">
      <p class="para">{notSent}</p>
    </Callout>
  {/if}

  {#if failure}
    <!-- Same reasoning as the add form: the heading is the outcome, and one outcome is not knowing. -->
    <Callout
      tone="critical"
      title={nothingChanged(failure)
        ? 'The practice was not created'
        : 'Whether the practice was created is not known from here'}
    >
      <p class="para">{failureSentence(failure)}</p>
      {#if !nothingChanged(failure)}
        <p class="para">
          A second attempt would create a SECOND practice if the first one landed, because the
          server mints a fresh id every time and has nothing to match this request against. There is
          no route that lists practices, so the safe order is to look for the first one before
          trying again.
        </p>
      {/if}
    </Callout>
  {/if}

  {#if created}
    <!--
      Kept on screen rather than announced and cleared. The identifier is minted by the server and
      nothing lists it afterwards, so this rendering is the only place it exists for its reader.
    -->
    <div class="created">
      <p class="created-head">The server created a practice and seated its first administrator.</p>
      <dl class="facts">
        <dt>Practice id</dt>
        <dd class="value">{created.orgId}</dd>
        <dt>Name</dt>
        <dd class="value">{created.name}</dd>
        <dt>Created</dt>
        <dd class="value">{formatInstant(created.createdAt)}</dd>
      </dl>
      <p class="para">{SEAT_IS_AN_OFFER}</p>
      <button type="button" onclick={() => created && onusepractice(created.orgId)}>
        Work in this practice
      </button>
    </div>
  {/if}

  {#snippet footer()}
    Write the practice id down. The server mints it, nothing on this server lists the practices a
    person belongs to, and every other screen here asks you to type it.
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

  /* --border-strong: see the note in PracticeSignIn. A control's boundary is what identifies it. */
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

  .created {
    margin-top: var(--space-4);
    padding: var(--space-4);
    border: 1px solid var(--hairline);
    border-radius: var(--radius);
    background: var(--paper-sheet);
  }

  .created-head {
    margin: 0 0 var(--space-3);
    font-weight: 600;
    color: var(--ink-text);
    font-size: 0.95rem;
  }

  .facts {
    display: grid;
    grid-template-columns: minmax(7rem, auto) 1fr;
    gap: var(--space-1) var(--space-4);
    margin: 0 0 var(--space-4);
  }

  .facts dt {
    font-family: var(--font-mono);
    font-size: 0.7rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
    padding-top: 0.2rem;
  }

  .facts dd {
    margin: 0;
    min-width: 0;
  }

  /* Machine text, and selectable: this is a value somebody has to copy out. */
  .value {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    font-size: 0.85rem;
    color: var(--ink-text);
    overflow-wrap: anywhere;
  }

  @media (max-width: 34rem) {
    .facts {
      grid-template-columns: 1fr;
      gap: 0;
    }
    .facts dd {
      margin-bottom: var(--space-2);
    }
  }
</style>
