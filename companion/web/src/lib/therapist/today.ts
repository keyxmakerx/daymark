/*
 * TODAY — the attention strip and the quiet roster, as plain data.
 *
 * WHAT THIS SCREEN IS. Two objects, and the relationship between them is the whole design.
 * The STRIP surfaces exceptions and nothing else: the few things in this bundle that a human
 * would want to notice, each stated as a fact with no verdict attached. The ROSTER is one row
 * per thing the person is tracking, with no ranking, no scoring, and no colour-coding of
 * quality. Neither object is a dashboard, a summary, or a count of how the week went.
 *
 * WHY AN EMPTY STRIP IS THE POINT AND NOT A BUG. `deriveExceptions` returning `[]` means the
 * strip renders nothing at all — not an "all clear", not a tick, not a reassuring line. This
 * product cannot know that things are fine; it can only know what is in a file that someone
 * chose to export. A reassurance drawn from the absence of an exception would be the software
 * grading a person on the strength of what it happens to have been given, and it would be wrong
 * on exactly the weeks where being wrong costs the most.
 *
 * WHAT MAY AND MAY NOT BECOME AN EXCEPTION. An exception is a fact about an ASSIGNMENT (it
 * passed the date it was due) or about the DATA (a band label changed between two runs; this
 * bundle was exported before the range on screen). What is deliberately impossible to express
 * here: "has not logged in N days", a low mood value on its own, a habit target that was not
 * met, or anything else that reads as a person failing. A gap in a record is not an event. A
 * person who logged nothing for three weeks may have been having the worst month of their life,
 * and the correct rendering of that is silence in the strip and an honest "last recorded" date
 * in the roster.
 *
 * WHY THE BAND CHANGE NEVER CARRIES A DIRECTION. `bandChange` states the two labels — previous
 * run, most recent run — and stops. It never says worsened, improved, up, down, or better. The
 * instruments in this product band descriptively and have no clinical cutoff (instruments/
 * types.ts), so a direction word would be inventing a scale the instrument does not have, and
 * putting it in front of a clinician as if the software had computed it. The change is worth
 * noticing; what it means is the clinician's to decide, out loud, with the person.
 *
 * WHY `now` IS A PARAMETER. Every function here is pure: no Date.now(), no `new Date()`, no
 * clock of any kind. This is not tidiness. A `$derived` in Svelte 5 re-evaluates only when a
 * TRACKED dependency changes, and a clock read inside a callee is invisible to it — that exact
 * bug shipped in TherapistPortal.svelte, where the idle guard called a function that read
 * Date.now() internally and therefore never fired. Time enters this module as a value the
 * caller passes, so a component can pass a ticked `$state` and get the reactivity it expects.
 * today.test.ts pins this by stubbing Date.now() to throw.
 *
 * WHY THE WINDOW IS A DURATION AND NOT A CALENDAR. `rangeDays` is multiplied out to
 * milliseconds and compared against instants. Nothing here converts an instant into anybody's
 * calendar day, which keeps the module free of the timezone-as-place reasoning this product
 * does not do, and keeps the results identical wherever the file is opened.
 *
 * WHY ASSIGNMENTS ARRIVE AS AN ARGUMENT. BackupData carries no assignment lifecycle — the
 * backup is the person's own record, and what a therapist issued lives on the therapist's side.
 * So the caller supplies them, in `TrackedAssignment`, typed against the ONE lifecycle
 * vocabulary (components/ui/status.ts) so this module and the pill a reader sees cannot drift
 * into two spellings of the same state. When the caller passes nothing, that exception kind
 * simply never occurs; it is never faked from the backup.
 *
 * NO SVELTE, NO DOM, NO IMPORTS THAT REACH EITHER. The ui barrel is not imported here even for
 * STATUS_LABEL — the label map lives in a plain .ts beside the component precisely so a pure
 * module can use the vocabulary without pulling a component graph into a node test.
 */

import type { BackupData, BackupAssessment } from '../backup'
import { MOODS } from '../mood'
import { getInstrument } from '../instruments'
import type { ProvenanceTier } from '../instruments/types'
import { STATUS_LABEL, type AssignmentStatus } from '../components/ui/status'

const DAY_MS = 24 * 60 * 60 * 1000

/** The range a clinician is looking at, in days, when the caller does not say. */
export const DEFAULT_RANGE_DAYS = 30

/**
 * How much span a bundle needs before the roster claims to describe anything. Below this the
 * screen says so plainly instead — see {@link Coverage.sparse}.
 */
export const FIRST_WEEK_DAYS = 7

/* ── Inputs ─────────────────────────────────────────────────────────────────────────────── */

/**
 * One assignment the therapist is tracking, reduced to what an exception can be derived from.
 *
 * `title` is the caller's text (assignments/describe.ts produces it) rather than something
 * re-derived here: the preview the owner accepted and the line the therapist reads should be
 * the same sentence.
 */
export type TrackedAssignment = {
  assignmentId: string
  title: string
  status: AssignmentStatus
  /** Epoch millis. */
  dueAt: number
}

export type TodayInput = {
  data: BackupData
  /** Epoch millis, supplied by the caller. Never read from a clock inside this module. */
  now: number
  /** Lifecycle records the caller holds; absent means this exception kind cannot occur. */
  assignments?: TrackedAssignment[]
  rangeDays?: number
  firstWeekDays?: number
}

/* ── Exceptions ─────────────────────────────────────────────────────────────────────────── */

export type ExceptionKind =
  /** The share bundle was exported before the range on screen. A fact about the file. */
  | 'bundleOlderThanRange'
  /** The bundle does not say when it was exported, so its coverage cannot be stated. */
  | 'exportDateUnknown'
  /** An assignment passed its due date and has not been completed or declined. */
  | 'assignmentPastDue'
  /** The two most recent runs of one instrument carry different band labels. */
  | 'bandChange'

/**
 * Presentation severity, decided here rather than at the call site so the mapping is one
 * reviewable product decision instead of four scattered ones.
 *
 * The rule: severity describes the DATA or an ARTEFACT of the working relationship, never the
 * person. Staleness and an undated bundle are degraded provenance, which is what warn means in
 * this system. An assignment past its due date is the "overdue" case the alarm hue exists for.
 * A band change is `info` — a fact the interface is surfacing, structural rather than severe —
 * because painting a person's band movement in the alarm hue would be the colour delivering a
 * verdict the words carefully refuse to deliver. today.test.ts pins that.
 */
export type ExceptionTone = 'info' | 'warn' | 'critical'

export type Exception = {
  id: string
  kind: ExceptionKind
  tone: ExceptionTone
  /** One short factual line. Never a recommendation, never a direction, never a grade. */
  headline: string
  /** A sentence or two of supporting fact. */
  detail: string
  /**
   * The instant the fact is anchored to (a due date, a run, an export), or null when the fact
   * has no instant. Formatting is the renderer's job: this module states no dates, because
   * formatting one means picking a calendar, and picking a calendar is not this module's call.
   */
  at: number | null
}

/**
 * Reading order for the strip. Facts about the file come first because they qualify everything
 * underneath them — a bundle that predates the range changes how every other row should be
 * read. This is an order, not a ranking: nothing here is scored against anything else.
 */
const KIND_ORDER: ExceptionKind[] = [
  'bundleOlderThanRange',
  'exportDateUnknown',
  'assignmentPastDue',
  'bandChange',
]

/* ── Roster ─────────────────────────────────────────────────────────────────────────────── */

export type RosterKind = 'checkins' | 'assessment' | 'sleep' | 'journal' | 'goal'

/**
 * What a row can say about where its measure came from.
 *
 * `null` is the common case and means the row carries no measure at all — a journal row counts
 * entries and states nothing about them, so there is no provenance to show. The house rule is
 * that provenance is shown per row WHERE A ROW CARRIES A MEASURE, and the inverse matters just
 * as much: a badge on a row that measures nothing would be decoration, and provenance stops
 * being read the moment it becomes decoration.
 *
 * `unknown` is a real state, not a fallback for tidiness: an assessment key that is not in this
 * build's catalog is a measure whose paperwork we cannot vouch for, and saying so is more
 * honest than quietly showing the raw key as if it were a known instrument.
 */
export type RowProvenance =
  | { kind: 'instrument'; tier: ProvenanceTier }
  | { kind: 'selfReport' }
  | { kind: 'unknown' }
  | null

/**
 * A type alias rather than an interface on purpose: an interface has no implicit index
 * signature, so `RosterRow[]` would not be assignable to the `Record<string, unknown>[]` that
 * ui/DataTable declares, and the renderer would need a cast to paper over it.
 */
export type RosterRow = {
  id: string
  kind: RosterKind
  /** What this stream is called. An instrument title where there is one, else the raw key. */
  label: string
  /**
   * Records of this stream inside the range. A count of records is a fact about the file; it is
   * never divided by a target, compared to another period, or turned into a percentage.
   */
  count: number
  /**
   * The most recent record of this stream ANYWHERE in the bundle — deliberately not clamped to
   * the range. A stream with nothing in the last month still shows when it was last written to,
   * which renders absence as absence rather than as a blank that looks like a failure.
   */
  lastAt: number | null
  /**
   * The most recent measure this row carries, already reduced to a LABEL: a band's own name, a
   * mood scale's own word. Never a score, never a number, never a colour. `null` where the row
   * carries no measure.
   */
  measure: string | null
  provenance: RowProvenance
}

/** Reading order for the roster. Fixed, so no row can rise or fall by being busy. */
const ROSTER_KIND_ORDER: RosterKind[] = ['checkins', 'assessment', 'sleep', 'journal', 'goal']

/* ── Coverage ───────────────────────────────────────────────────────────────────────────── */

export type Coverage = {
  /** Records across every typed stream. Goals are excluded: a goal is a declaration, not a record. */
  recordCount: number
  firstRecordAt: number | null
  lastRecordAt: number | null
  /** Whole days between the first and last record. Zero for a single record. */
  spanDays: number
  /**
   * True when the bundle is too short to describe — the first-week case. This is a statement
   * about the FILE. It is not a prompt to log more, and the screen that renders it must not
   * turn it into one.
   */
  sparse: boolean
}

export type TodayView = {
  exceptions: Exception[]
  roster: RosterRow[]
  coverage: Coverage
  rangeDays: number
  /** Epoch millis of the range's opening edge, exposed so a renderer can label the window. */
  rangeStart: number
}

/* ── Derivation ─────────────────────────────────────────────────────────────────────────── */

/** Everything the screen renders, from one bundle and one instant. */
export function buildToday(input: TodayInput): TodayView {
  const rangeDays = input.rangeDays ?? DEFAULT_RANGE_DAYS
  return {
    exceptions: deriveExceptions(input),
    roster: buildRoster(input),
    coverage: assessCoverage(input),
    rangeDays,
    rangeStart: input.now - rangeDays * DAY_MS,
  }
}

/**
 * The strip's contents. An empty array is a legitimate, common and correct result: it means
 * nothing in this bundle met an exception rule, which is not the same claim as "everything is
 * fine" and must never be rendered as one.
 */
export function deriveExceptions(input: TodayInput): Exception[] {
  const { data, now } = input
  const rangeDays = input.rangeDays ?? DEFAULT_RANGE_DAYS
  const rangeStart = now - rangeDays * DAY_MS
  const out: Exception[] = []

  /* The bundle's own coverage, first, because it qualifies everything else on the screen. */
  if (!data.exportedAt) {
    out.push({
      id: 'bundle:undated',
      kind: 'exportDateUnknown',
      tone: 'warn',
      headline: 'This bundle does not record when it was exported',
      detail:
        'Its coverage cannot be stated, so nothing on this screen should be read as current. The rows below describe only what the file contains.',
      at: null,
    })
  } else if (data.exportedAt < rangeStart) {
    out.push({
      id: 'bundle:stale',
      kind: 'bundleOlderThanRange',
      tone: 'warn',
      headline: 'This bundle was exported before the range on screen',
      detail: `Nothing recorded in the last ${rangeDays} days can be in this file. This is a fact about the file, not about the person.`,
      at: data.exportedAt,
    })
  }

  /* Assignments. Past the date, and neither finished nor refused. */
  for (const a of input.assignments ?? []) {
    if (a.dueAt >= now) continue
    // 'completed' is done and 'declined' is a decision the person made and communicated —
    // neither wants a clinician's attention. Silence ('noResponse') is NOT refusal, which is
    // why it stays in: it is a thing to ask about, not a thing to record as a refusal.
    if (a.status === 'completed' || a.status === 'declined') continue
    out.push({
      id: `assignment:${a.assignmentId}`,
      kind: 'assignmentPastDue',
      tone: 'critical',
      headline: `${a.title} passed its due date`,
      detail: `It is marked ${STATUS_LABEL[a.status]}.`,
      at: a.dueAt,
    })
  }

  /* Band changes, per instrument, between the two most recent runs only. */
  for (const [key, runs] of groupAssessments(data.assessments ?? [])) {
    if (runs.length < 2) continue
    const latest = runs[runs.length - 1]!
    const previous = runs[runs.length - 2]!
    // A change observed months ago is history, not attention: the strip would fill with old
    // movement and stop being scannable. Anchored on the LATEST run, so a pair of runs that
    // straddles the range's opening edge still surfaces while the newer one is in view.
    if (latest.dateTime < rangeStart) continue
    const from = (previous.bandLabel ?? '').trim()
    const to = (latest.bandLabel ?? '').trim()
    if (!from || !to || from === to) continue
    out.push({
      id: `band:${key}`,
      kind: 'bandChange',
      tone: 'info',
      headline: `${titleFor(key)}: the band label changed between the two most recent runs`,
      detail: `Previous run: ${from}. Most recent run: ${to}.`,
      at: latest.dateTime,
    })
  }

  return out.sort(compareExceptions)
}

/** Fixed kind order, then most recent first, then id. Deterministic; not a severity ranking. */
function compareExceptions(a: Exception, b: Exception): number {
  const byKind = KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind)
  if (byKind !== 0) return byKind
  const at = (b.at ?? 0) - (a.at ?? 0)
  if (at !== 0) return at
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

/**
 * The roster: one row per thing being tracked.
 *
 * A stream earns a row by having at least one record SOMEWHERE in the bundle. Listing every
 * stream the app supports whether or not the person uses it would turn the roster into a
 * checklist of things they are not doing, which is the "gap as failure" reading this screen
 * exists to avoid. Goals are the exception and are listed while active regardless of records:
 * a goal is a thing the person declared they are tracking, so its row is theirs by declaration.
 */
export function buildRoster(input: TodayInput): RosterRow[] {
  const { data, now } = input
  const rangeDays = input.rangeDays ?? DEFAULT_RANGE_DAYS
  const rangeStart = now - rangeDays * DAY_MS
  const inRange = (t: number) => t >= rangeStart
  const rows: RosterRow[] = []

  /* Mood check-ins. The measure is the scale's own word for the level — the bundle's own
     moodLabels where it has them, the shipped scale otherwise. The level's colour is not read
     and could not be used here if it were: the mood ramp is licensed to one component, and a
     roster row is not it. The word carries the whole meaning anyway. */
  const entries = [...data.entries].sort(byTimeAsc((e) => e.dateTime))
  if (entries.length) {
    const latest = entries[entries.length - 1]!
    rows.push({
      id: 'stream:checkins',
      kind: 'checkins',
      label: 'Check-ins',
      count: entries.filter((e) => inRange(e.dateTime)).length,
      lastAt: latest.dateTime,
      measure: moodLabel(data, latest.moodLevel),
      provenance: { kind: 'selfReport' },
    })
  }

  /* One row per instrument that has been run. The measure is the band LABEL and never the
     score: a raw sum invites reading a cut-off these instruments do not have. */
  for (const [key, runs] of groupAssessments(data.assessments ?? [])) {
    const latest = runs[runs.length - 1]!
    const band = (latest.bandLabel ?? '').trim()
    const known = getInstrument(key)
    rows.push({
      id: `assessment:${key}`,
      kind: 'assessment',
      label: known?.title ?? key,
      count: runs.filter((r) => inRange(r.dateTime)).length,
      lastAt: latest.dateTime,
      measure: band || null,
      provenance: known ? { kind: 'instrument', tier: known.provenance.tier } : { kind: 'unknown' },
    })
  }

  /* Sleep. Counted and dated, never rated: BackupSleepLog carries a `quality` and this row
     deliberately does not surface it. A 2-out-of-5 night in a roster column is a grade on a
     thing nobody controls, and the row is just as useful as a record of what was written down. */
  const sleep = [...(data.sleepLogs ?? [])].sort(byTimeAsc((s) => s.night))
  if (sleep.length) {
    rows.push({
      id: 'stream:sleep',
      kind: 'sleep',
      label: 'Sleep',
      count: sleep.filter((s) => inRange(s.night)).length,
      lastAt: sleep[sleep.length - 1]!.night,
      measure: null,
      provenance: null,
    })
  }

  /* Journal. Counted and dated only — no title, no excerpt. The roster says a person wrote
     something, not what they wrote. */
  const journal = [...(data.journal ?? [])].sort(byTimeAsc((j) => j.dateTime))
  if (journal.length) {
    rows.push({
      id: 'stream:journal',
      kind: 'journal',
      label: 'Journal',
      count: journal.filter((j) => inRange(j.dateTime)).length,
      lastAt: journal[journal.length - 1]!.dateTime,
      measure: null,
      provenance: null,
    })
  }

  /* Active goals, each against the activity it is linked to. `targetPerWeek` is read nowhere in
     this file, and that is the point: count-against-target is a compliance score wearing a
     different noun, and this product does not ship one. The row states how many records exist
     and when the last one was, which is a fact, and stops there. */
  const times = activityTimes(data)
  for (const goal of data.goals ?? []) {
    if (goal.archived) continue
    const stamps = goal.activityId === null ? [] : (times.get(goal.activityId) ?? [])
    rows.push({
      id: `goal:${goal.id}`,
      kind: 'goal',
      label: goal.title,
      count: stamps.filter(inRange).length,
      lastAt: stamps.length ? stamps[stamps.length - 1]! : null,
      measure: null,
      provenance: null,
    })
  }

  return rows.sort(compareRows)
}

/** Fixed kind order, then label. A busy stream cannot climb the list by being busy. */
function compareRows(a: RosterRow, b: RosterRow): number {
  const byKind = ROSTER_KIND_ORDER.indexOf(a.kind) - ROSTER_KIND_ORDER.indexOf(b.kind)
  if (byKind !== 0) return byKind
  if (a.label !== b.label) return a.label < b.label ? -1 : 1
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0
}

/**
 * How much of a record there is to describe.
 *
 * `sparse` is measured as SPAN rather than as volume. Someone who checks in twice a week for a
 * month has a describable record; someone three days into using the app does not, however
 * diligent they have been. Span answers "has enough time passed for this screen to be saying
 * anything", which is the question, and volume answers "has this person done enough", which is
 * not a question this product asks.
 */
export function assessCoverage(input: TodayInput): Coverage {
  const { data } = input
  const firstWeekDays = input.firstWeekDays ?? FIRST_WEEK_DAYS
  const stamps: number[] = [
    ...data.entries.map((e) => e.dateTime),
    ...(data.journal ?? []).map((j) => j.dateTime),
    ...(data.sleepLogs ?? []).map((s) => s.night),
    ...(data.assessments ?? []).map((a) => a.dateTime),
  ].sort((a, b) => a - b)

  if (!stamps.length) {
    return { recordCount: 0, firstRecordAt: null, lastRecordAt: null, spanDays: 0, sparse: true }
  }
  const firstRecordAt = stamps[0]!
  const lastRecordAt = stamps[stamps.length - 1]!
  const spanDays = Math.floor((lastRecordAt - firstRecordAt) / DAY_MS)
  return {
    recordCount: stamps.length,
    firstRecordAt,
    lastRecordAt,
    spanDays,
    sparse: spanDays < firstWeekDays,
  }
}

/* ── Helpers ────────────────────────────────────────────────────────────────────────────── */

/** Runs per instrument key, each list oldest-first. Insertion-ordered, so the roster is stable. */
function groupAssessments(all: BackupAssessment[]): Map<string, BackupAssessment[]> {
  const by = new Map<string, BackupAssessment[]>()
  for (const a of all) {
    const list = by.get(a.key)
    if (list) list.push(a)
    else by.set(a.key, [a])
  }
  for (const list of by.values()) list.sort((x, y) => x.dateTime - y.dateTime)
  return by
}

/** An instrument's title where this build knows it, else the raw key — never a guess. */
function titleFor(key: string): string {
  return getInstrument(key)?.title ?? key
}

/**
 * The scale's own word for a level. The bundle's labels win because they are what the person
 * saw on their own phone; the shipped scale is the fallback, and an off-scale level yields null
 * rather than a nearest guess — inventing a word for a level we do not have is a small lie in
 * front of a clinician.
 */
function moodLabel(data: BackupData, level: number): string | null {
  const fromBundle = data.moodLabels?.[String(level)]
  if (typeof fromBundle === 'string' && fromBundle.trim()) return fromBundle.trim()
  const known = MOODS.find((m) => m.level === level)
  return known ? known.label : null
}

/** Entry timestamps per activity id, oldest-first, for the goal rows. */
function activityTimes(data: BackupData): Map<number, number[]> {
  const entryTime = new Map<number, number>(data.entries.map((e) => [e.id, e.dateTime]))
  const out = new Map<number, number[]>()
  for (const ref of data.refs) {
    const t = entryTime.get(ref.entryId)
    if (t === undefined) continue
    const list = out.get(ref.activityId)
    if (list) list.push(t)
    else out.set(ref.activityId, [t])
  }
  for (const list of out.values()) list.sort((a, b) => a - b)
  return out
}

function byTimeAsc<T>(at: (item: T) => number): (a: T, b: T) => number {
  return (a, b) => at(a) - at(b)
}
