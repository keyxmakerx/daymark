<script lang="ts">
  /*
   * THE PRACTICE'S OWN LOG — what the control plane did, and nothing else.
   *
   * ─── WHAT IS IN IT ─────────────────────────────────────────────────────────────────────────
   *
   * Memberships created, accepted, re-roled and ended, plus the refusals: an account attempting an
   * act its role does not carry, recorded because that is the more informative half. Every entry is
   * an event, an identifier and a timestamp. There is no content in this chain because there is no
   * content within reach of the store it is read from.
   *
   * ─── WHAT IS DELIBERATELY NOT IN IT ────────────────────────────────────────────────────────
   *
   * Who opened whose material. That belongs to the patient and is written into the patient's own
   * chain, in a different database, readable by them. The separation is structural rather than a
   * filter: two files, keyed differently, so an administrator reviewing their practice cannot page
   * sideways into somebody's access history even by accident. Said on screen, because "audit log"
   * on an administrator's console reads like an all-seeing one and this is emphatically not that.
   *
   * ─── WHY THE CAVEAT IS A FOOTER AND NOT AN ASIDE ───────────────────────────────────────────
   *
   * "Not provably complete" is the load-bearing sentence about any log a server serves: entries are
   * chained, so a tampered or reordered one is detectable, and a dishonest server can still simply
   * withhold the newest ones. Card's footer strip is full-bleed and hairline-joined precisely so
   * that a qualification reads as part of the claim rather than as a dismissible note beside it.
   */
  import { Callout, Card, Chip, EmptyState } from '../ui'
  import {
    PracticeClient,
    failureSentence,
    formatInstant,
    type OrgAuditEvent,
    type PracticeFailure,
  } from '../../practice/client'
  import {
    orgAuditActionLabel,
    orgAuditActorLabel,
    orgAuditAnnotations,
    orgAuditSubjectLabel,
  } from '../../practice/audit'
  import {
    AUDIT_IS_A_DIFFERENT_CHAIN,
    AUDIT_IS_METADATA_ONLY,
    AUDIT_RETURNED_NOTHING,
    EMPTY_AUDIT_BODY,
    EMPTY_AUDIT_TITLE,
  } from '../../practice/copy'

  let {
    client,
    orgId,
  }: {
    client: PracticeClient
    orgId: string
  } = $props()

  let events = $state<OrgAuditEvent[] | null>(null)
  let nextCursor = $state<number | null>(null)
  let failure = $state<PracticeFailure | null>(null)
  let working = $state(false)

  /**
   * Read a page. `before` is the server's own cursor, fed straight back.
   *
   * The page size is left to the server rather than set here. Its default and its cap are the
   * route's, and a client that named its own would be quietly deciding how much of a practice's
   * history a person sees per press.
   */
  async function read(before?: number) {
    working = true
    failure = null
    const result = await client.audit(orgId, before === undefined ? {} : { before })
    working = false
    if (!result.ok) {
      failure = result.failure
      return
    }
    // Appended, not replaced, when paging further back — otherwise "older" would read as "instead
    // of what you were looking at" and the sequence would be impossible to follow.
    const page = result.value.events ?? []
    events = before === undefined ? page : [...(events ?? []), ...page]
    nextCursor = result.value.nextCursor ?? null
  }
</script>

<Card title="Practice log">
  {#snippet header()}
    {#if events}<Chip tone="neutral">{events.length} entries read</Chip>{/if}
  {/snippet}

  <p class="para">{AUDIT_IS_A_DIFFERENT_CHAIN}</p>

  <div class="controls">
    <button type="button" onclick={() => void read()} disabled={working || orgId === ''}>
      {working ? 'Reading' : 'Read the log'}
    </button>
    {#if nextCursor !== null}
      <button type="button" onclick={() => void read(nextCursor ?? undefined)} disabled={working}>
        Read older entries
      </button>
    {/if}
  </div>

  {#if failure}
    <Callout tone="critical" title="The log was not read">
      <p class="para">{failureSentence(failure)}</p>
    </Callout>
  {/if}

  {#if events === null}
    <EmptyState title={EMPTY_AUDIT_TITLE}>
      <p class="para">{EMPTY_AUDIT_BODY}</p>
    </EmptyState>
  {:else if events.length === 0}
    <EmptyState title="Nothing came back">
      <p class="para">{AUDIT_RETURNED_NOTHING}</p>
    </EmptyState>
  {:else}
    <ol class="entries">
      {#each events as event (event.seq)}
        <li class="entry">
          <p class="entry-head">
            <span class="seq">#{event.seq}</span>
            <span class="when">{formatInstant(event.ts)}</span>
            <span class="action">{orgAuditActionLabel(event.action)}</span>
          </p>
          <p class="actor">{orgAuditActorLabel(event.actor)}</p>

          <dl class="annotations">
            {#if event.objectRef}
              <dt>{orgAuditSubjectLabel(event.action)}</dt>
              <dd class="value">{event.objectRef}</dd>
            {/if}
            {#each orgAuditAnnotations(event) as note (note.key)}
              <dt>{note.label}</dt>
              <dd class="value">{note.value}</dd>
            {/each}
          </dl>

          <!--
            The entry hash is rendered rather than checked. Verifying the chain means recomputing
            hashes, and this page has neither the inputs nor a verifier; showing the value with no
            claim attached is the honest half of that. The admin console has the examiner.
          -->
          <p class="hash">{event.entryHash}</p>
        </li>
      {/each}
    </ol>
  {/if}

  {#snippet footer()}
    {AUDIT_IS_METADATA_ONLY}
  {/snippet}
</Card>

<style>
  .para {
    margin: 0 0 var(--space-3);
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.55;
  }

  .controls {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-bottom: var(--space-4);
  }

  .entries {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .entry {
    padding: var(--space-3) 0;
    border-top: 1px solid var(--hairline);
  }

  .entry:first-child {
    border-top: 0;
    padding-top: 0;
  }

  .entry-head {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-3);
    margin: 0;
  }

  /* Machine facts: sequence and time are read by position, so they are mono with tabular figures
     and the column stays a ruler rather than a ragged edge. */
  .seq,
  .when {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    font-size: 0.75rem;
    color: var(--chrome-soft);
  }

  .action {
    font-weight: 600;
    color: var(--ink-text);
    font-size: 0.9rem;
  }

  .actor {
    margin: var(--space-1) 0 0;
    color: var(--ink-soft);
    font-size: 0.85rem;
  }

  .annotations {
    display: grid;
    grid-template-columns: minmax(9rem, auto) 1fr;
    gap: var(--space-1) var(--space-4);
    margin: var(--space-2) 0 0;
    min-width: 0;
  }

  .annotations dt {
    font-family: var(--font-mono);
    font-size: 0.7rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
    padding-top: 0.15rem;
  }

  .annotations dd {
    margin: 0;
    min-width: 0;
  }

  .value {
    font-family: var(--font-mono);
    font-size: 0.8rem;
    color: var(--ink-text);
    overflow-wrap: anywhere;
  }

  /* Long, and never the subject: it is here so a person can compare it against an export, not so
     they can read it. */
  .hash {
    margin: var(--space-2) 0 0;
    font-family: var(--font-mono);
    font-size: 0.7rem;
    line-height: 1.5;
    color: var(--chrome-soft);
    overflow-wrap: anywhere;
  }

  @media (max-width: 34rem) {
    .annotations {
      grid-template-columns: 1fr;
      gap: 0;
    }
    .annotations dd {
      margin-bottom: var(--space-2);
    }
  }
</style>
