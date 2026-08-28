<script lang="ts">
  /*
   * THE PRACTICE CONSOLE — the first interface the practice shape has ever had.
   *
   * ─── WHAT IT IS, HONESTLY ──────────────────────────────────────────────────────────────────
   *
   * A fourth surface, beside the owner's report viewer, the clinician portal and the server admin
   * console, and it is neither of the other three: it belongs to whoever administers a practice.
   * The server underneath it is built and tested — `/v1/orgs` with a roster, membership, roles,
   * removal and an audit chain. These screens are a first pass over it, and the parts that do not
   * exist are drawn as marked placeholders rather than omitted or faked. CONSOLE_BUILD_STATE says
   * so on the page, because a maintainer clicking through to decide whether this works needs to
   * know which half they are looking at.
   *
   * ─── THE ONE THING THIS CONSOLE MUST NOT IMPLY ─────────────────────────────────────────────
   *
   * That administering a practice is a way of getting at what people wrote. It is not, and not by
   * this interface's restraint: there is no route under `/v1/orgs` that returns key material,
   * ciphertext or a grant, so there is no sequence of requests this page could make that ends with
   * a sentence a patient wrote. Membership says what a person may DO. Reading comes from a grant a
   * patient signs on their own device. The two are different objects and the screens keep saying
   * so, at the points where an administrative interface would otherwise imply the opposite.
   *
   * ─── WHY THE PRACTICE ID IS TYPED IN ───────────────────────────────────────────────────────
   *
   * Because there is no route that lists the practices a person belongs to, and inventing a picker
   * would mean inventing its contents. Typed, it is honest and it is clickable; a placeholder on
   * the limits screen says what a picker would need.
   *
   * ─── WHY READS AND WRITES ARE GATED DIFFERENTLY ────────────────────────────────────────────
   *
   * The session cookie is HttpOnly and rides on its own, so the roster and the log can be read as
   * soon as a session exists in this browser — including after a reload of this page. The
   * anti-forgery token cannot be read back from the cookie and is held in memory here, so every
   * write needs a sign-in in THIS page's lifetime. Rather than hide the read-only state, the panels
   * render it: the roster shows its rows and says which controls need a sign-in.
   */
  import { AppShell, Callout, Card, Chip, NavRail, PageHeader, type NavGroup } from '../ui'
  import AddMemberPanel from './AddMemberPanel.svelte'
  import AuditPanel from './AuditPanel.svelte'
  import CreatePracticePanel from './CreatePracticePanel.svelte'
  import Placeholder from './Placeholder.svelte'
  import PracticeSignIn from './PracticeSignIn.svelte'
  import RosterPanel from './RosterPanel.svelte'
  import { PracticeClient, isPracticeId } from '../../practice/client'
  import {
    CONSOLE_BUILD_STATE,
    CONSOLE_LEDE,
    CONSOLE_TITLE,
    NO_PATIENT_LIST,
    PLACEHOLDERS,
  } from '../../practice/copy'
  import { rolesThatMintReads } from '../../practice/orgRoles'

  let {
    /**
     * Origin prefix for every request. Empty by default, because this page is served by the server
     * it talks to and a relative path reaches it from wherever it is mounted.
     */
    baseUrl = '',
    fetchImpl,
  }: {
    baseUrl?: string
    fetchImpl?: typeof fetch
  } = $props()

  type View = 'practice' | 'roster' | 'add' | 'audit' | 'limits'

  let view = $state<View>('practice')
  let orgId = $state('')
  let session = $state<{ memberId: string; csrf: string } | null>(null)

  /*
   * One client for the page. Rebuilt only if the base changes, which it does not at runtime — but
   * derived rather than constructed once at the top so that a harness mounting this with a
   * different base gets a client pointed at it.
   */
  let client = $derived(new PracticeClient(baseUrl, fetchImpl))

  /** The practice id as the server's charset would accept it, so the console can say so early. */
  let idLooksUsable = $derived(orgId !== '' && isPracticeId(orgId))

  const NAV: NavGroup[] = [
    {
      items: [
        { id: 'practice', label: 'Practice' },
        { id: 'roster', label: 'Roster' },
        { id: 'add', label: 'Add member' },
        { id: 'audit', label: 'Practice log' },
      ],
    },
    {
      label: 'Honest limits',
      items: [{ id: 'limits', label: 'What is not built' }],
    },
  ]

  const TITLES: Record<View, string> = {
    practice: 'Practice',
    roster: 'Roster',
    add: 'Add member',
    audit: 'Practice log',
    limits: 'What is not built',
  }

  /*
   * The one role in the whole catalog whose preset can authorize somebody else to read — computed,
   * not named. The point being made on the limits screen is that the answer is a role a practice
   * cannot seat, and a hand-typed "only the patient" would go on saying so if that ever stopped
   * being true.
   */
  const MINTERS = rolesThatMintReads()
</script>

<AppShell>
  {#snippet rail()}
    <NavRail
      groups={NAV}
      active={view}
      onselect={(id) => (view = id as View)}
      title="Daymark"
      subtitle={CONSOLE_TITLE}
    />
  {/snippet}

  {#snippet header()}
    <PageHeader title={TITLES[view]}>
      <Chip tone="neutral">{orgId === '' ? 'No practice named' : orgId}</Chip>
      <Chip tone="neutral">{session ? `Signed in as ${session.memberId}` : 'Not signed in'}</Chip>
    </PageHeader>
  {/snippet}

  <div class="page">
    {#if view === 'practice'}
      <p class="lede">{CONSOLE_LEDE}</p>

      <Callout tone="info" title="First pass">
        <p class="para">{CONSOLE_BUILD_STATE}</p>
      </Callout>

      <Card title="Which practice">
        <label class="field" for="practice-org-id">
          <span class="field-label">
            Practice id. The server mints it when a practice is created, and nothing lists the
            practices you belong to, so it has to be typed here.
          </span>
          <input
            id="practice-org-id"
            class="text-input"
            type="text"
            spellcheck="false"
            bind:value={orgId}
          />
          {#if orgId !== '' && !idLooksUsable}
            <span class="problem">
              Letters, digits, hyphens and underscores only — the server will refuse anything else.
            </span>
          {/if}
        </label>

        <p class="para">
          Your standing is looked up in this practice, on every request. A role held in one practice
          is nothing in another, and there is no administrator of all of them.
        </p>
      </Card>

      {#if session}
        <Card title="Signed in">
          <p class="para">
            Signed in as <span class="id">{session.memberId}</span>. That is a credential, not a
            membership: this console can be signed in and still be a stranger to the practice named
            above, in which case every request comes back as though the practice did not exist.
          </p>
          <div class="controls">
            <button type="button" onclick={() => (session = null)}>
              Forget the token on this page
            </button>
          </div>
        </Card>
      {:else}
        <PracticeSignIn {baseUrl} {fetchImpl} onsignedin={(s) => (session = s)} />
      {/if}

      <CreatePracticePanel
        {client}
        onusepractice={(id) => {
          orgId = id
          view = 'roster'
        }}
      />
    {:else if view === 'roster'}
      <RosterPanel {client} {orgId} {session} />
    {:else if view === 'add'}
      <AddMemberPanel {client} {orgId} {session} />
    {:else if view === 'audit'}
      <AuditPanel {client} {orgId} />
    {:else}
      <p class="lede">
        What this console does not do, split into two kinds: things that are absent by design and
        will not arrive, and things that are not built yet.
      </p>

      <Card title="Absent by design">
        <p class="para">{NO_PATIENT_LIST}</p>
        <p class="para">
          There is no screen here for reading anybody's material, and no request this page can make
          would return any. The one thing in the whole role catalog that can authorize a person to
          read is held by {MINTERS.length === 1 ? 'one role' : `${MINTERS.length} roles`}:
          {MINTERS.map((r) => r.label).join(', ')} — and a practice cannot seat
          {MINTERS.length === 1 ? 'it' : 'them'}, because consent belongs to the person whose data
          it is.
        </p>
      </Card>

      <Card title="Not built">
        <div class="stack">
          {#each PLACEHOLDERS as note (note.id)}
            <Placeholder title={note.title} specifiedAt={note.specifiedAt}>
              <p class="para">{note.body}</p>
            </Placeholder>
          {/each}
        </div>
      </Card>
    {/if}
  </div>
</AppShell>

<style>
  .page {
    display: flex;
    flex-direction: column;
    gap: var(--space-5);
    padding: var(--space-5);
    max-width: var(--maxw);
    min-width: 0;
  }

  .lede {
    margin: 0;
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.95rem;
    line-height: 1.6;
  }

  .para {
    margin: 0 0 var(--space-3);
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.55;
  }

  .para:last-child {
    margin-bottom: 0;
  }

  .stack {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .field {
    display: block;
    max-width: 32rem;
    margin-bottom: var(--space-4);
  }

  .field-label {
    display: block;
    margin-bottom: var(--space-1);
    color: var(--ink-soft);
    font-size: 0.85rem;
    line-height: 1.5;
  }

  /* --border-strong: the boundary is the only thing that identifies a control on this ground. */
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

  .id {
    font-family: var(--font-mono);
    font-size: 0.85rem;
    color: var(--ink-text);
    overflow-wrap: anywhere;
  }

  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
</style>
