<script lang="ts">
  /*
   * TODAY — a thin renderer over lib/therapist/today.ts.
   *
   * WHAT THIS FILE IS ALLOWED TO DECIDE. Almost nothing. Every rule that could be got wrong in a
   * way that matters — what counts as an exception, how a band change is worded, when a bundle
   * is too short to describe — lives in the pure module, where vitest can hold it. What is left
   * here is arrangement and one thing the module deliberately refuses to do: turn an instant
   * into a date, which means picking a calendar, which is a rendering decision.
   *
   * WHY THE STRIP CAN BE ABSENT AND NOTHING TAKES ITS PLACE. When `exceptions` is empty this
   * component renders no strip at all — no "nothing needs attention", no tick, no reassuring
   * line. Reassurance would be a claim, and the claim would be false: the portal knows what is
   * in one exported file and nothing else. The fixed caveat in the roster's footer says exactly
   * that, permanently, so a reader is never left to infer it from an empty space.
   *
   * WHY A GAP IS NEVER PAINTED. A row with no records inside the range shows a count of 0 and
   * the date it was last written to, in the same ink as every other row. No warning tint, no
   * icon, no chip. Someone who logged nothing for three weeks may have been having the worst
   * month of their life, and a surface that highlights the gap has decided otherwise on no
   * evidence and put that in front of a clinician.
   *
   * WHY THERE IS NO MOOD COLOUR ON THIS SCREEN. The check-in row carries the person's most
   * recent mood as the scale's own WORD ("Good", "Meh") and never as a hue. The mood ramp is
   * licensed to one component in this system, a roster row is not it, and a ramp that turns up
   * outside its licence stops being a legend a reader can trust. The word carries the whole
   * meaning anyway; that is why the scale has words.
   *
   * WHY `now` IS A PROP AND NOT A CLOCK. `$derived` re-evaluates only when a tracked dependency
   * changes, and a `Date.now()` inside a callee is invisible to it — the bug that shipped in
   * TherapistPortal.svelte, whose idle guard therefore never fired. `now` arrives as a value, so
   * a parent that wants this screen to age can tick a `$state` and get exactly that.
   */
  import { Card, PageHeader, EmptyState, DataTable, Callout, Chip, ProvenanceBadge } from '../ui'
  import type { Column } from '../ui'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'
  import type { BackupData } from '../../backup'
  import { buildToday } from '../../therapist/today'
  import type { Exception, RosterRow, TrackedAssignment } from '../../therapist/today'

  let {
    data,
    now,
    assignments = [],
    rangeDays,
  }: {
    /** The decrypted bundle. Read only — this screen writes nothing, anywhere. */
    data: BackupData
    /** Epoch millis, supplied by the parent. See the note above: never read from a clock here. */
    now: number
    /** Assignment lifecycle the caller holds; the backup carries none and none is invented. */
    assignments?: TrackedAssignment[]
    /** The window the clinician is looking at, in days. Defaults inside the module. */
    rangeDays?: number
  } = $props()

  const view = $derived(buildToday({ data, now, assignments, rangeDays }))

  /* Columns are fixed. "In range" is deliberately a count of records and never a proportion of
     anything: the moment it is divided by a target it becomes a compliance score. */
  const COLUMNS: Column[] = [
    { key: 'label', label: 'Tracked' },
    { key: 'count', label: 'In range', numeric: true, width: '9ch' },
    { key: 'lastAt', label: 'Last recorded', numeric: true, width: '15ch' },
    { key: 'measure', label: 'Most recent' },
    { key: 'provenance', label: 'Source' },
  ]

  const DAY_FORMAT = new Intl.DateTimeFormat(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  })

  function day(ms: number): string {
    return DAY_FORMAT.format(new Date(ms))
  }

  /*
   * What the date under an exception means. Premade constants, one per kind — nothing here is
   * generated, inferred, or phrased by anything other than a person who wrote it down.
   */
  function whenLabel(e: Exception): string {
    if (e.at === null) return ''
    switch (e.kind) {
      case 'bundleOlderThanRange':
        return `Exported ${day(e.at)}`
      case 'assignmentPastDue':
        return `Due ${day(e.at)}`
      case 'bandChange':
        return `Most recent run ${day(e.at)}`
      case 'exportDateUnknown':
        return ''
    }
  }

  const SOURCE_UNKNOWN = 'Source not in catalog'
  const SOURCE_SELF = 'Self-report'
</script>

<section class="today">
  <PageHeader title="Today">
    <Chip>Last {view.rangeDays} days</Chip>
  </PageHeader>

  <NonDiagnosticBanner />

  <!--
    Exceptions only. The section is a landmark so it can be jumped to, and it exists in the
    document only while it holds something — an empty labelled region announces itself and then
    has nothing to say, which is worse than not being there.
  -->
  {#if view.exceptions.length > 0}
    <section class="strip" aria-label="Attention">
      {#each view.exceptions as e (e.id)}
        <Callout tone={e.tone} title={e.headline}>
          <p class="detail">{e.detail}</p>
          {#if e.at !== null}<p class="when u-mono">{whenLabel(e)}</p>{/if}
        </Callout>
      {/each}
    </section>
  {/if}

  <Card title="Tracked">
    <!--
      Gated on the ROSTER being empty, not on `coverage.recordCount`.

      The two disagree by design: `assessCoverage` excludes goals from `recordCount` because a goal
      is a declaration rather than a record, while `buildRoster` does emit a goal row. Gating on the
      count meant a bundle carrying goals and nothing else rendered "This bundle holds no records …
      no part of it describes the person whose bundle this is" over a roster that had a row in it.
      That sentence was false, and the screen was the only thing that knew.
    -->
    {#if view.roster.length === 0}
      <EmptyState title="This bundle holds no records">
        <p>
          There is nothing here to list. That is a statement about the file and about nothing
          else — no part of it describes the person whose bundle this is.
        </p>
      </EmptyState>
    {:else}
      <!--
        A short record is NOTED, never used to withhold the roster.

        This used to swap the whole table for "Too little here to describe" whenever the bundle's
        span was under a week. But the module's own rule is that span, not volume, decides — and
        the roster carries no trend: it is counts and dates, which are exactly as true on day two
        as on day two hundred. Someone who checked in sixty times in two days was shown nothing.
        The note says what the span is; the reader still gets the rows.
      -->
      {#if view.coverage.sparse}
        <p class="span-note">
          These records span {view.coverage.spanDays}
          {view.coverage.spanDays === 1 ? 'day' : 'days'}. Counts and dates are listed; no trend is
          drawn from a stretch this short. That is a fact about the bundle, not about the person.
        </p>
      {/if}
      <DataTable columns={COLUMNS} rows={view.roster} caption="What this bundle tracks">
        {#snippet cell(key, row)}
          {@const r = row as RosterRow}
          {#if key === 'label'}
            {r.label}
          {:else if key === 'count'}
            {r.count}
          {:else if key === 'lastAt'}
            {#if r.lastAt === null}
              <span class="absent">No record</span>
            {:else}
              {day(r.lastAt)}
            {/if}
          {:else if key === 'measure'}
            {#if r.measure !== null}{r.measure}{/if}
          {:else if key === 'provenance'}
            {@const p = r.provenance}
            {#if p !== null}
              {#if p.kind === 'instrument'}
                <ProvenanceBadge tier={p.tier} />
              {:else if p.kind === 'selfReport'}
                <Chip>{SOURCE_SELF}</Chip>
              {:else}
                <Chip>{SOURCE_UNKNOWN}</Chip>
              {/if}
            {/if}
          {/if}
        {/snippet}
      </DataTable>
    {/if}

    {#snippet footer()}
      This screen states what the bundle contains. It does not score, rank, or compare one period
      of a person's life against another, and a stream with nothing in the range is shown as
      recorded, never as a shortfall. When the attention strip above is absent it means nothing in
      the bundle matched an exception rule — not that anything has been checked and found well.
    {/snippet}
  </Card>
</section>

<style>
  .today {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    max-width: var(--maxw);
  }

  /* One callout per exception, stacked. No container of its own: a box around the strip would
     give it a permanent frame that stays on the page as a shape even when it is empty. */
  .strip {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .detail {
    margin: 0;
  }

  /* The instant the fact is anchored to. Machine text — mono, faint, subordinate to the
     sentence above it, and never the thing a reader notices first. */
  .when {
    margin: var(--space-1) 0 0;
    color: var(--text-subtle);
    font-size: 0.75rem;
  }

  /*
   * A cell with nothing in it. Faint and in words, because "no record" is a fact about the file
   * that a reader is entitled to distinguish from a cell that failed to render. It is not tinted
   * and carries no icon: an absence is not a severity, and the moment it is painted it starts
   * reading as a person's failure rather than as a fact about a stream.
   */
  /* The short-span note: quieter than body text, because it is a caveat and not a finding. */
  .span-note {
    margin: 0 0 var(--space-3);
    color: var(--ink-soft);
    font-size: 0.9em;
  }

  .absent {
    color: var(--text-subtle);
  }
</style>
