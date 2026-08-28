<script lang="ts">
  /*
   * THE ROSTER: who is in this practice, what role they hold, and whether they have taken the seat.
   *
   * ─── WHAT A ROSTER IS AND IS NOT ───────────────────────────────────────────────────────────
   *
   * It is membership metadata: an id, a role, a timestamp, and whether the person accepted. There
   * is no column for what anybody can read, because there is no such fact in the control plane —
   * read capability is a key wrapped on a patient's device for one named person, and this server
   * cannot see one, let alone list it. A "has access to" column would be the single most damaging
   * thing this screen could grow, so the honest framing is stated in the card's footer rather than
   * left for a reader to infer from a table that looks like an access-control list.
   *
   * There is likewise no patient list beside it, and that absence is the design rather than a gap
   * — see NO_PATIENT_LIST, which is rendered on the console's own limits screen.
   *
   * ─── WHY `accepted` IS A COLUMN AND NOT A DETAIL ───────────────────────────────────────────
   *
   * A seat is an OFFER until the person takes it from their own session, and until then it carries
   * nothing: withdrawing it cuts no sessions, and the practice has no standing over the credential
   * at all. A roster that rendered an offer identically to a colleague would be showing an
   * administrator a staff list that includes people who have never heard of them.
   *
   * ─── WHY REMOVAL IS TWO CLICKS AND CARRIES A PARAGRAPH ─────────────────────────────────────
   *
   * Not because it is dangerous — the server charges nothing for it on purpose, since revocation
   * must never be the expensive direction — but because it is easy to believe it did more than it
   * did. It ends a membership. It does not disable a credential, withdraw a grant, or un-read
   * anything. An administrator who thinks the button cut somebody's access off will stop there,
   * when the thing that actually ends access is the patient withdrawing the share. So the second
   * click carries that sentence, at the moment it is relevant.
   *
   * ─── WHY EVERY OUTCOME IS RE-READ RATHER THAN ASSUMED ──────────────────────────────────────
   *
   * After any write, this panel reads the roster again rather than patching its local copy. Two
   * reasons, and the second is the important one: another administrator may have changed something
   * in between, and — for the failures where [nothingChanged] is false — this page genuinely does
   * not know whether the write landed. Re-reading is the only answer that is true in both cases,
   * and it means no row on this screen is ever this page's guess about server state.
   */
  import { Callout, Card, Chip, DataTable, EmptyState, type Column } from '../ui'
  import RolePicker from './RolePicker.svelte'
  import {
    PracticeClient,
    failureSentence,
    formatInstant,
    nothingChanged,
    type Member,
    type PracticeFailure,
  } from '../../practice/client'
  import { SEATABLE_ROLE_WIRES, roleLabel, type OrgRoleWire } from '../../practice/orgRoles'
  import {
    EMPTY_ROSTER_BODY,
    EMPTY_ROSTER_TITLE,
    MEMBERSHIP_IS_NOT_READ_ACCESS,
    REMOVAL_ENDS_A_MEMBERSHIP,
    SESSIONS_CUT_MEANS,
    WHY_SOME_ACTS_COST_MORE,
  } from '../../practice/copy'

  let {
    client,
    orgId,
    session,
    onchanged,
  }: {
    client: PracticeClient
    orgId: string
    /** Null until somebody signs in. Reads work without it; writes do not. */
    session: { memberId: string; csrf: string } | null
    /** Fired after any successful write, so the console can note that the log has moved on. */
    onchanged?: () => void
  } = $props()

  let members = $state<Member[] | null>(null)
  let failure = $state<PracticeFailure | null>(null)
  /** A plain statement of what the last write did. Never a congratulation; see the house rules. */
  let outcome = $state('')
  /**
   * Whether the last write was a removal, which decides one thing: whether the sentence explaining
   * what a session count is gets rendered. It is qualifying a number, so it appears only where the
   * number does — a caveat that turns up after unrelated acts is one people learn to skip.
   */
  let outcomeWasRemoval = $state(false)
  let working = $state(false)

  /** The member whose role is being changed, and the role chosen for them. */
  let editing = $state<string | null>(null)
  let chosenRole = $state<OrgRoleWire>('clinician')
  let stepUpCode = $state('')

  /** The member a removal has been proposed for. Cleared on any other action. */
  let confirming = $state<string | null>(null)

  const columns: Column[] = [
    { key: 'memberId', label: 'Member', width: '20ch' },
    { key: 'role', label: 'Role' },
    { key: 'seat', label: 'Seat' },
    { key: 'addedAt', label: 'Seated', numeric: true, width: '20ch' },
    { key: 'actions', label: 'Membership' },
  ]

  let rows = $derived(
    (members ?? []).map((m) => ({
      memberId: m.memberId,
      role: m.role,
      seat: m.accepted,
      addedAt: m.addedAt,
      actions: m.memberId,
    })),
  )

  async function read() {
    working = true
    failure = null
    const result = await client.roster(orgId)
    working = false
    if (result.ok) {
      members = result.value
      // Not cleared on failure: a stale list plus an error is a confusing pair, but an emptied
      // list plus an error reads as "the practice has no members", which is a claim this page has
      // no evidence for.
    } else {
      failure = result.failure
    }
  }

  function beginEdit(member: Member) {
    confirming = null
    editing = member.memberId
    // Seeded with what the person holds now, so the picker opens on the truth rather than on
    // whichever role happens to be first in the catalog.
    chosenRole = seatable(member.role) ?? 'clinician'
    stepUpCode = ''
  }

  /*
   * The member's current role as a wire value this build can offer, or null.
   *
   * Read from the catalog rather than from a set typed out here: a second list of the six wire
   * values is a second thing to keep in step with the server, and this one would be wrong in the
   * quietest possible way — seeding the picker on the wrong role, on a screen about who may do
   * what. Null when the server sent a role this build does not model, which is a real possibility
   * and is why the caller falls back rather than trusting a cast.
   */
  function seatable(role: string): OrgRoleWire | null {
    return SEATABLE_ROLE_WIRES.find((w) => w === role) ?? null
  }

  async function applyRole(memberId: string) {
    if (!session) return
    working = true
    failure = null
    outcome = ''
    const result = await client.changeRole(orgId, memberId, chosenRole, {
      csrf: session.csrf,
      stepUpCode,
    })
    working = false
    outcomeWasRemoval = false
    // Spent or refused, the code is gone either way: a TOTP step is single-use on the server, so
    // leaving it in the field only invites a second attempt that cannot succeed.
    stepUpCode = ''
    if (result.ok) {
      editing = null
      outcome = `${result.value.memberId} now holds the role ${roleLabel(result.value.role)} in this practice.`
      onchanged?.()
      await read()
    } else {
      failure = result.failure
      if (!nothingChanged(result.failure)) await read()
    }
  }

  async function remove(memberId: string) {
    if (!session) return
    working = true
    failure = null
    outcome = ''
    const result = await client.removeMember(orgId, memberId, { csrf: session.csrf })
    working = false
    confirming = null
    outcomeWasRemoval = true
    if (result.ok) {
      const cut = result.value.sessionsCut
      // The count is reported as what it is — portal sessions — and never as access ended. The
      // sentence that says so is rendered directly beneath it.
      outcome = `${result.value.memberId} is no longer a member of this practice. Portal sessions ended: ${cut}.`
      onchanged?.()
      await read()
    } else {
      failure = result.failure
      if (!nothingChanged(result.failure)) await read()
    }
  }

  async function acceptOwnSeat() {
    if (!session) return
    working = true
    failure = null
    outcome = ''
    const result = await client.acceptOwnSeat(orgId, { csrf: session.csrf })
    working = false
    outcomeWasRemoval = false
    if (result.ok) {
      outcome = 'You accepted your own seat in this practice.'
      onchanged?.()
      await read()
    } else {
      failure = result.failure
      if (!nothingChanged(result.failure)) await read()
    }
  }

  let editingMember = $derived((members ?? []).find((m) => m.memberId === editing) ?? null)
  let myUnacceptedSeat = $derived(
    session ? ((members ?? []).find((m) => m.memberId === session.memberId && !m.accepted) ?? null) : null,
  )
</script>

<Card title="Roster">
  {#snippet header()}
    {#if members}<Chip tone="neutral">{members.length} members</Chip>{/if}
  {/snippet}

  <div class="controls">
    <button type="button" onclick={() => void read()} disabled={working || orgId === ''}>
      {working ? 'Reading' : 'Read the roster'}
    </button>
    {#if myUnacceptedSeat}
      <button type="button" onclick={() => void acceptOwnSeat()} disabled={working}>
        Accept my own seat
      </button>
    {/if}
  </div>

  {#if orgId === ''}
    <p class="para">Name a practice above, and its roster can be read here.</p>
  {/if}

  {#if failure}
    <Callout tone="critical" title="The server refused, or could not be reached">
      <p class="para">{failureSentence(failure)}</p>
      {#if !nothingChanged(failure)}
        <p class="para">
          Whether anything changed is not known from here. The roster below was read again after the
          failure, so it shows what the server holds rather than what this page expected.
        </p>
      {/if}
    </Callout>
  {/if}

  {#if outcome}
    <!--
      A statement, not a confirmation. No tick, no tone, no colour: something happened and here is
      what it was. The reassurance in this product is the absence of a callout.
    -->
    <p class="outcome">{outcome}</p>
    {#if outcomeWasRemoval}<p class="para">{SESSIONS_CUT_MEANS}</p>{/if}
  {/if}

  {#if members === null}
    <EmptyState title={EMPTY_ROSTER_TITLE}>
      <p class="para">{EMPTY_ROSTER_BODY}</p>
    </EmptyState>
  {:else if members.length === 0}
    <EmptyState title="The server returned an empty roster">
      <p class="para">
        A practice always keeps at least one administrator, and anybody who can read this roster is
        on it, so an empty list is not a state this page expected. It is shown as it came back
        rather than filled in with anything.
      </p>
    </EmptyState>
  {:else}
    <DataTable {columns} {rows} caption="Members of this practice">
      {#snippet cell(key: string, row: Record<string, unknown>)}
        {#if key === 'memberId'}
          <span class="id">{String(row.memberId)}</span>
        {:else if key === 'role'}
          <span>{roleLabel(String(row.role))}</span>
        {:else if key === 'seat'}
          <!--
            Words, not a mark. "Accepted" and "offer" are different states of a membership and both
            are ordinary; neither is a pass or a failure, so neither gets a tick or a colour.
          -->
          <span class="seat">{row.seat === true ? 'Accepted' : 'Offer, not yet accepted'}</span>
        {:else if key === 'addedAt'}
          <span class="when">{formatInstant(Number(row.addedAt))}</span>
        {:else if key === 'actions'}
          {@const id = String(row.actions)}
          <span class="row-actions">
            {#if session}
              <button
                type="button"
                onclick={() => {
                  const m = (members ?? []).find((x) => x.memberId === id)
                  if (m) beginEdit(m)
                }}
                disabled={working}
              >
                Change role
              </button>
              {#if confirming === id}
                <button type="button" onclick={() => void remove(id)} disabled={working}>
                  Confirm removal
                </button>
                <button type="button" onclick={() => (confirming = null)}>Cancel</button>
              {:else}
                <button type="button" onclick={() => (confirming = id)} disabled={working}>
                  Remove
                </button>
              {/if}
            {:else}
              <span class="needs-session">Sign in to change this</span>
            {/if}
          </span>
        {/if}
      {/snippet}
    </DataTable>
  {/if}

  {#if confirming}
    <Callout tone="warn" title="Removing {confirming} from this practice">
      <p class="para">{REMOVAL_ENDS_A_MEMBERSHIP}</p>
    </Callout>
  {/if}

  {#if editingMember}
    <div class="editor">
      <RolePicker
        value={chosenRole}
        onpick={(r) => (chosenRole = r)}
        group="practice-role-change"
        legend="Change {editingMember.memberId}’s role in this practice"
      />

      <p class="para">{WHY_SOME_ACTS_COST_MORE}</p>

      <label class="field" for="practice-role-stepup">
        <span class="field-label">Current code from your authenticator</span>
        <input
          id="practice-role-stepup"
          class="text-input"
          type="text"
          inputmode="numeric"
          autocomplete="one-time-code"
          spellcheck="false"
          bind:value={stepUpCode}
        />
      </label>

      <div class="controls">
        <button
          class="primary"
          type="button"
          onclick={() => editingMember && void applyRole(editingMember.memberId)}
          disabled={working}
        >
          Change role
        </button>
        <button type="button" onclick={() => (editing = null)}>Cancel</button>
      </div>
    </div>
  {/if}

  {#snippet footer()}
    {MEMBERSHIP_IS_NOT_READ_ACCESS}
  {/snippet}
</Card>

<style>
  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-bottom: var(--space-4);
  }

  .para {
    margin: 0 0 var(--space-3);
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.55;
  }

  /* Plain ink, no ground, no rule. A statement of what happened is not a status. */
  .outcome {
    margin: 0 0 var(--space-2);
    max-width: 44rem;
    color: var(--ink-text);
    font-size: 0.9rem;
    line-height: 1.55;
  }

  /* Machine text: ids and timestamps are read by position, not as prose. */
  .id,
  .when {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    font-size: 0.8rem;
    overflow-wrap: anywhere;
  }

  .seat {
    color: var(--ink-soft);
    font-size: 0.85rem;
  }

  .row-actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .row-actions button {
    padding: 0.15rem var(--space-2);
    font-size: 0.8rem;
  }

  .needs-session {
    color: var(--chrome-soft);
    font-size: 0.8rem;
  }

  .editor {
    margin-top: var(--space-5);
    padding-top: var(--space-4);
    border-top: 1px solid var(--hairline);
  }

  .field {
    display: block;
    max-width: 24rem;
    margin-bottom: var(--space-4);
  }

  .field-label {
    display: block;
    margin-bottom: var(--space-1);
    color: var(--ink-soft);
    font-size: 0.85rem;
    line-height: 1.5;
  }

  /* --border-strong: a control's boundary is the only thing identifying it as one. */
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
</style>
