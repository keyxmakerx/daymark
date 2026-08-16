/*
 * CLIENT RECORD — one person's self-check runs, placed on the instrument's OWN bands.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * THE ONE RULE THIS MODULE EXISTS TO ENFORCE: THERE IS NO NUMERIC AXIS, AND NO NUMBER TO PUT
 * ON ONE.
 *
 * A questionnaire total is a real number and a misleading one. Written on a y-axis it invites a
 * reader to interpolate — to treat 13 and 14 as a step of known size, to notice that a line
 * crossed some horizontal, to infer a cut-off at a granularity the instrument does not define.
 * The instruments in this product are descriptive by construction: they have bands and no
 * clinical threshold at all (instruments/types.ts, `Band` — "Ordered, non-overlapping,
 * DESCRIPTIVE bands. Never a clinical cutoff."). So the axis here is the ordered list of the
 * scale's own band labels, and a run is placed ON one of those labels.
 *
 * The score is read exactly once, inside {@link stepForScore}, to decide which band a run falls
 * in when the file did not record one — and it is then discarded. It appears nowhere in
 * {@link RunPoint}, nowhere in {@link BandAxis}, and nowhere a renderer could reach it. That is
 * deliberate and it is pinned by a test: a view model that carries the number will eventually
 * have the number drawn.
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * WHY THE BAND METADATA IS BORROWED AND NEVER RESTATED. The bands, their order and their wording
 * belong to the instrument definition (lib/instruments/catalog/*). A second band table here would
 * be a copy that drifts, and the direction it would drift in is the dangerous one: this screen
 * would start showing a reader a band boundary that the questionnaire they filled in did not use.
 * {@link stepForScore} therefore delegates to `bandFor` — the SAME function the runner scored
 * with — so an off-by-one at a band edge is impossible between the two by construction rather
 * than by vigilance.
 *
 * WHY tone IS DROPPED FROM THE AXIS. `Band.tone` is 'neutral' | 'attention' | 'positive'. Carried
 * into a view model it becomes, within one refactor, a coloured lane — a red row and a good row —
 * and the screen starts grading a person's answers on their behalf. The axis needs an order and
 * some words; it does not need a valence, and the safest way to guarantee it never grows one is
 * for the valence never to arrive. {@link BandStep} is `{ index, label }` and a test asserts it.
 *
 * WHY A RECORDED BAND IS NEVER OVERRULED. `BackupAssessment.bandLabel` is what the person was
 * shown on their own phone, under whatever version of the definition was installed that day. If
 * this build's catalog has since re-worded or re-cut its bands, recomputing the label from the
 * stored score would silently rewrite that person's history under a definition they never saw. So
 * a recorded label is authoritative: it is placed on the axis when the axis has it, and when the
 * axis does not, the run is listed with its recorded band and is NOT plotted. Saying "this run
 * carries a band this build cannot place" is honest; moving it to the nearest band we do have is
 * not. The score is used only when there is no recorded label at all, and that case is marked
 * `derived` so the renderer can say where the band came from.
 *
 * WHY AN INSTRUMENT WITH NO RUNS PRODUCES NO POINTS. A series builder that emits a zero at the
 * origin for an unrun instrument draws a person as sitting in the lowest band on a day nobody
 * assessed them. That is the single worst thing this screen could do, it is the default behaviour
 * of most charting code, and it is pinned by a test that first proves a genuine lowest-band run
 * IS visible — otherwise "no point at the origin" would pass against a builder that emits nothing
 * for anything.
 *
 * WHY GAPS ARE ARITHMETIC AND NOTHING ELSE. {@link RunPoint.fraction} spaces runs across the span
 * of that instrument's own record, so three months without a run is three months of empty track.
 * Nothing interpolates across it, nothing is drawn in it, and no count of missing weeks is
 * derived from it. A person who was not assessed for three months may have been having the worst
 * quarter of their life; the honest rendering of that is empty space.
 *
 * WHY `now` IS AN ARGUMENT. Nothing in this file reads a clock. A `$derived` in Svelte 5
 * re-evaluates only when a TRACKED dependency changes, and a `Date.now()` inside a callee is
 * invisible to it — the bug that shipped in TherapistPortal.svelte, whose idle guard therefore
 * never fired again. Time enters as a value the caller passes, so a component can tick a `$state`
 * and get the reactivity it expects. The test suite pins it by stubbing `Date.now()` to throw.
 *
 * NO SVELTE, NO DOM. This module is plain TypeScript so vitest can hold every rule above.
 */

import type { BackupAssessment, BackupData } from '../backup'
import { bandFor, getInstrument } from '../instruments'
import type { Band, InstrumentDefinition, ProvenanceTier, Scale } from '../instruments/types'

const DAY_MS = 24 * 60 * 60 * 1000

/* ── Inputs ─────────────────────────────────────────────────────────────────────────────── */

/**
 * How an instrument key is resolved to a definition.
 *
 * Defaults to the shipped catalog. It is a parameter so the boundary behaviour can be tested
 * against instruments with deliberately awkward band tables — a scale with a gap between two
 * bands, an instrument with two scales — without adding either to the catalog a person can be
 * handed. Injection here is a test seam, not an extension point: nothing in this module can
 * introduce a definition, and a caller passing a lookup cannot make a non-catalog tool
 * assignable (that gate is therapist/catalog.ts and it is untouched by this).
 */
export type InstrumentLookup = (key: string) => InstrumentDefinition | undefined

export type ClientRecordInput = {
  /** The decrypted bundle. Read only. */
  data: BackupData
  /** Epoch millis, supplied by the caller. Never read from a clock inside this module. */
  now: number
  /** Defaults to the shipped catalog. See {@link InstrumentLookup}. */
  lookup?: InstrumentLookup
}

/* ── Provenance ─────────────────────────────────────────────────────────────────────────── */

/**
 * Where a row's measure came from — the assessment-carrying subset of the vocabulary
 * therapist/today.ts uses for its roster. Deliberately narrower than that type: an assessment row
 * is always either a catalog instrument or a key this build does not know, and it is never a
 * self-report or a row with no measure at all, so those two states are unrepresentable here.
 *
 * `unknown` is a real state and not a tidy fallback. An assessment key that is not in this
 * build's catalog is a measure whose paperwork cannot be vouched for, and a badge saying so is
 * more honest than printing the raw key as though it were a known instrument.
 */
export type MeasureProvenance = { kind: 'instrument'; tier: ProvenanceTier } | { kind: 'unknown' }

/* ── The axis ───────────────────────────────────────────────────────────────────────────── */

/**
 * One position on the band axis.
 *
 * `{ index, label }` and nothing else — see the header note on why `tone` is not here.
 * `index` is the band's position in the scale's OWN declared order, so 0 is the first band the
 * instrument's author wrote down. It is an ordinal, and specifically not a value: the distance
 * between step 0 and step 1 is not claimed to equal the distance between step 1 and step 2,
 * because the instrument makes no such claim.
 */
export type BandStep = { index: number; label: string }

/**
 * The y-axis: an instrument scale's bands, in the scale's own order, with the scale's own
 * non-diagnostic framing sentence attached so a renderer can print it under the chart without
 * writing one of its own.
 */
export type BandAxis = {
  scaleId: string
  /** Ascending in the scale's declared order — `steps[0]` is the first band declared. */
  steps: BandStep[]
  /** The scale's own `bandFraming`. Fixed copy, written by a human, carried verbatim. */
  framing: string
}

/**
 * Why an instrument has no axis. Each is a fact about what this build knows, stated so the
 * renderer can print a premade sentence rather than a generic failure.
 */
export type AxisAbsence =
  /** The bundle's key is not in this build's catalog, so its bands and their ORDER are unknown. */
  | 'instrumentNotInCatalog'
  /** A definition that scores nothing — a written exercise. There are no bands to draw. */
  | 'instrumentScoresNothing'
  /** More than one scale, and the file records one score per run without saying which scale. */
  | 'moreThanOneScale'

/* ── Placement ──────────────────────────────────────────────────────────────────────────── */

/** Why a run could not be put on the axis. */
export type UnplacedReason =
  /** There is no axis for this instrument at all — see {@link AxisAbsence}. */
  | 'noAxis'
  /** The file recorded a band this build's definition does not contain. History is not rewritten. */
  | 'bandNotOnAxis'
  /** No band was recorded, and the stored score falls in a gap between two declared bands. */
  | 'scoreBetweenBands'

export type RunPlacement =
  /** The band the file recorded is a step on this axis. The common, quiet case. */
  | { kind: 'recorded'; step: number }
  /** The file recorded no band; this build's own bands place the stored score. Said out loud. */
  | { kind: 'derived'; step: number }
  | { kind: 'unplaced'; reason: UnplacedReason }

/**
 * One run of one instrument.
 *
 * Note what is absent: no score, no numeric level, no delta against the previous run, no
 * direction. The band's own words and the instant it was taken are the whole payload.
 */
export type RunPoint = {
  id: string
  /** Epoch millis. Formatting is the renderer's job — a calendar is not this module's to pick. */
  at: number
  /**
   * The band's own words: the recorded label where the file has one, otherwise the label of the
   * band this build's definition places the score in. `null` when neither exists.
   */
  label: string | null
  placement: RunPlacement
  /**
   * Horizontal position in [0,1] across the span of THIS instrument's runs, so the gaps between
   * runs are drawn to scale. A single run — or several sharing one instant — sits at 0.5 rather
   * than pinned to an edge, because an edge would read as the start or end of something.
   */
  fraction: number
  provenance: MeasureProvenance
}

/* ── Per instrument ─────────────────────────────────────────────────────────────────────── */

/**
 * A type alias rather than an interface: an interface has no implicit index signature, so
 * `RunPoint[]` would not be assignable to the `Record<string, unknown>[]` ui/DataTable declares
 * and the renderer would need a cast to paper over it. Same reason as therapist/today.ts.
 */
export type InstrumentRecord = {
  /** The bundle's own key for this instrument. */
  key: string
  /** The catalog title where this build knows the instrument, else the raw key — never a guess. */
  title: string
  axis: BandAxis | null
  /** Set exactly when `axis` is null, and explains which of the three reasons applies. */
  axisAbsence: AxisAbsence | null
  /**
   * True when [axis] was recovered from the runs rather than read from a declared scale.
   *
   * The renderer must say so. A derived axis shows only the bands this person has actually landed
   * in, so presenting it as though it were the instrument's own scale would imply the absent bands
   * do not exist. See {@link bandAxisFromRuns}.
   */
  axisDerived: boolean
  provenance: MeasureProvenance
  /** Oldest first. Never empty inside {@link ClientRecordView} — see the note on `instruments`. */
  runs: RunPoint[]
  firstAt: number | null
  lastAt: number | null
  /** Runs this axis cannot show. Stated so the renderer can say so, never quietly dropped. */
  unplacedCount: number
}

/** One lane of the trend: a band, and the runs that landed on it. */
export type BandLane = { step: BandStep; runs: RunPoint[] }

/* ── The whole screen ───────────────────────────────────────────────────────────────────── */

/**
 * The record's own edges, as facts rather than as prose. The sentences that surround these
 * numbers are fixed copy in the component; what belongs here is only what has to be counted.
 */
export type RecordScope = {
  /** When the bundle says it was exported; null when it does not say. */
  exportedAt: number | null
  /**
   * Whole days between the export and the instant the reader is looking at it. Null when the
   * bundle is undated. NOT clamped: a negative value means the bundle's stamp is later than the
   * reading instant — two clocks disagreeing — and hiding that would be a small lie about the
   * one thing this field exists to state.
   */
  ageDays: number | null
  /** Instruments with at least one run in this bundle. */
  instrumentCount: number
  runCount: number
  /** True when the bundle carries no assessment runs at all. */
  empty: boolean
}

export type ClientRecordView = {
  /**
   * One entry per instrument that has AT LEAST ONE run in this bundle, ordered by title.
   *
   * Catalog instruments with no runs are deliberately absent rather than listed empty. A list of
   * every tool the product ships, most of them showing nothing, is a checklist of things a person
   * has not done, and it reads as one however carefully it is worded. The same rule as the Today
   * roster.
   */
  instruments: InstrumentRecord[]
  scope: RecordScope
}

/* ── Derivation ─────────────────────────────────────────────────────────────────────────── */

/** Everything the screen renders, from one bundle and one instant. */
export function buildClientRecord(input: ClientRecordInput): ClientRecordView {
  const lookup = input.lookup ?? getInstrument
  const runs = input.data.assessments ?? []

  const instruments = [...groupByKey(runs)]
    .map(([key, list]) => buildInstrumentRecord(key, list, lookup))
    .sort(compareRecords)

  const exportedAt = input.data.exportedAt ? input.data.exportedAt : null

  return {
    instruments,
    scope: {
      exportedAt,
      ageDays: exportedAt === null ? null : Math.floor((input.now - exportedAt) / DAY_MS),
      instrumentCount: instruments.length,
      runCount: runs.length,
      empty: runs.length === 0,
    },
  }
}

/**
 * One instrument's record.
 *
 * Exported separately because the empty case is the one worth testing directly: given no runs
 * this returns `runs: []` and null edges, and specifically does not manufacture a point.
 */
export function buildInstrumentRecord(
  key: string,
  runs: BackupAssessment[],
  lookup: InstrumentLookup = getInstrument,
): InstrumentRecord {
  const def = lookup(key)
  const scale = def ? primaryScale(def) : null
  // The catalog first; then the runs themselves. On every bundle a therapist can actually open
  // today the catalog misses, because the phone writes `phq9`/`gad7`/`who5` while the shipped web
  // catalog holds `wellbeing-selfcheck` and its three siblings — so a catalog-only axis meant the
  // band trend, which is the entire point of this screen, never rendered on real data.
  const axis = scale ? bandAxisFor(scale) : bandAxisFromRuns(key, runs)
  const provenance = provenanceFor(def)
  const points = buildSeries(key, runs, axis, scale?.bands ?? null, provenance)

  return {
    key,
    title: def?.title ?? key,
    axis,
    axisAbsence: axis ? null : axisAbsenceFor(def),
    axisDerived: axis !== null && scale === null,
    provenance,
    runs: points,
    firstAt: points.length ? points[0]!.at : null,
    lastAt: points.length ? points[points.length - 1]!.at : null,
    unplacedCount: points.filter((p) => p.placement.kind === 'unplaced').length,
  }
}

/**
 * The series: one point per run, oldest first.
 *
 * An empty `runs` yields an empty array. It does not yield a point at step 0, at the origin, or
 * at any other position, because there is no observation to place — see the header note.
 */
export function buildSeries(
  key: string,
  runs: BackupAssessment[],
  axis: BandAxis | null,
  bands: Band[] | null,
  provenance: MeasureProvenance,
): RunPoint[] {
  const ordered = [...runs].sort((a, b) => a.dateTime - b.dateTime)
  if (!ordered.length) return []

  const first = ordered[0]!.dateTime
  const span = ordered[ordered.length - 1]!.dateTime - first

  return ordered.map((run, index) => {
    const placement = placeRun(run, axis, bands)
    return {
      id: `${key}#${index}`,
      at: run.dateTime,
      label: labelFor(run, placement, axis),
      placement,
      // A zero span means every run shares one instant; there is no spacing to express, so they
      // stack mid-track rather than piling against an edge that would read as a boundary.
      fraction: span === 0 ? 0.5 : (run.dateTime - first) / span,
      provenance,
    }
  })
}

/**
 * Where one run sits on the axis.
 *
 * The precedence is the whole design: recorded label first (it is what the person was shown),
 * stored score only as a fallback when no label was recorded, and no third guess. A recorded
 * label this build does not have is `unplaced` and stays visible in the table — never quietly
 * moved to the nearest band we happen to define.
 */
export function placeRun(
  run: BackupAssessment,
  axis: BandAxis | null,
  bands: Band[] | null,
): RunPlacement {
  if (!axis) return { kind: 'unplaced', reason: 'noAxis' }

  const recorded = normalise(run.bandLabel)
  if (recorded) {
    const step = axis.steps.findIndex((s) => normalise(s.label) === recorded)
    return step >= 0
      ? { kind: 'recorded', step }
      : { kind: 'unplaced', reason: 'bandNotOnAxis' }
  }

  const derived = bands ? stepForScore(run.score, bands) : null
  return derived === null
    ? { kind: 'unplaced', reason: 'scoreBetweenBands' }
    : { kind: 'derived', step: derived }
}

/**
 * Which band a score falls in, as a position in the scale's own band array.
 *
 * Delegates to `bandFor` — the exact function instruments/scoring.ts scored the run with — so the
 * edges cannot disagree between what the person was shown and what this screen draws. `null` when
 * the score falls in a gap between two declared bands: the catalog's bands are integer-contiguous,
 * so this happens for a fractional score, and snapping it to a neighbour would invent a placement
 * the instrument does not define.
 */
export function stepForScore(score: number, bands: Band[]): number | null {
  const band = bandFor(score, bands)
  if (!band) return null
  // bandFor returns the array's own object, so identity search is exact.
  const index = bands.indexOf(band)
  return index < 0 ? null : index
}

/**
 * The scale a single recorded score can be attributed to.
 *
 * `BackupAssessment` carries one score and one band label per run and does not say which scale
 * they belong to. With exactly one scale there is nothing to guess. With none there is nothing to
 * measure, and with two or more, picking the first would be a guess presented to a clinician as a
 * fact — so both return null and the caller states which case it is.
 */
export function primaryScale(def: InstrumentDefinition): Scale | null {
  const scales = def.scoring.scales
  return scales.length === 1 ? scales[0]! : null
}

/** The axis for one scale: its bands, in its own order, with its own framing sentence. */
export function bandAxisFor(scale: Scale): BandAxis {
  return {
    scaleId: scale.id,
    steps: scale.bands.map((band, index) => ({ index, label: band.label })),
    framing: scale.bandFraming,
  }
}

/**
 * An axis recovered from the runs themselves, for an instrument this build has no definition for.
 *
 * ## Why this exists at all
 *
 * `getInstrument` matches on `InstrumentDefinition.instrumentId`, and the shipped web catalog holds
 * four ids, none of which the phone ever writes. `AssessmentResult.key` is `phq9`, `gad7` or
 * `who5`, carried verbatim through the backup and the share into `assessments[].key`. So for every
 * bundle a therapist can open, the catalog lookup misses and a catalog-only axis is null — and the
 * band trend, which is the whole reason this screen exists, drew nothing at all.
 *
 * ## What it may and may not infer
 *
 * The bands are the labels the APP ITSELF recorded on each run (`bandLabel`), not labels invented
 * here. Their ORDER is taken from the scores that produced them: a band whose observed scores are
 * lower sits lower. That is a fact about this person's own runs rather than a claim about the
 * instrument — which matters, because guessing that "Moderate" outranks "Mild" from the words
 * alone would be this software asserting a clinical ordering it has not been given.
 *
 * It therefore describes only the bands actually observed. An instrument with five bands where the
 * person has only ever landed in two yields a two-lane axis, and the screen must not imply the
 * other three do not exist. `scaleId` is prefixed `derived:` so a renderer can say where the axis
 * came from, and no caller can mistake it for a declared scale.
 *
 * Returns null when nothing can be recovered — no runs, or no run carrying a band label — because
 * an axis with no lanes is not an axis, and manufacturing one would be the invention this whole
 * comment is about avoiding.
 */
export function bandAxisFromRuns(key: string, runs: BackupAssessment[]): BandAxis | null {
  const lowestScoreFor = new Map<string, { lo: number; hi: number }>()
  for (const r of runs) {
    const label = (r.bandLabel ?? '').trim()
    // A run with no label, or no usable score, contributes nothing. A label without a score cannot
    // be ordered, and ordering it by its WORDS is the invention this deliberately refuses.
    if (!label || !Number.isFinite(r.score)) continue
    const seen = lowestScoreFor.get(label)
    if (seen === undefined) lowestScoreFor.set(label, { lo: r.score, hi: r.score })
    else {
      seen.lo = Math.min(seen.lo, r.score)
      seen.hi = Math.max(seen.hi, r.score)
    }
  }
  // Two distinct bands at minimum. One band establishes no ordering — there is nothing to order —
  // and a single-lane axis would imply that lane is the whole scale, which is precisely the
  // over-claim this derivation exists to avoid.
  if (lowestScoreFor.size < 2) return null

  const ordered = [...lowestScoreFor.entries()].sort((a, b) =>
    a[1].lo === b[1].lo ? a[0].localeCompare(b[0]) : a[1].lo - b[1].lo,
  )

  // The order must be IN the data, not asserted over it. If two bands' observed score ranges
  // overlap, no ordering of those bands follows from this file — the same score sat in two
  // different bands — and any order chosen here would be this software's opinion rather than the
  // instrument's. Refuse instead: the screen already knows how to say it has no axis, and saying
  // so is honest where a guess would read as a clinical claim.
  for (let i = 1; i < ordered.length; i++) {
    if (ordered[i]![1].lo <= ordered[i - 1]![1].hi) return null
  }

  return {
    scaleId: `derived:${key}`,
    steps: ordered.map(([label], index) => ({ index, label })),
    framing: DERIVED_AXIS_FRAMING,
  }
}

/**
 * The fixed sentence shown under a derived axis. Premade, like every other piece of copy on these
 * screens, and deliberately about the LIMIT rather than the result.
 */
export const DERIVED_AXIS_FRAMING =
  'These bands are the ones this person’s own runs recorded, ordered by the scores that produced ' +
  'them. This build has no definition for this instrument, so bands it has never landed in are ' +
  'not shown and no cut-off is implied.'

/**
 * The trend's lanes, HIGHEST DECLARED BAND FIRST, because that is top-to-bottom reading order and
 * a chart is read downwards.
 *
 * Note what this ordering does NOT assert. The scale's last band is not "good" and its first is
 * not "bad" — for the focus self-check the highest band is "More focus bumps noted today" and for
 * the wellbeing self-check it is "Brighter than your own usual today". Up therefore means "more of
 * whatever this instrument counts" and nothing else, which is exactly why every lane is labelled
 * with the band's own words and no lane is ever painted: the words carry the meaning, and any
 * valence a colour added would be this software's, not the instrument's.
 *
 * Unplaced runs appear in no lane. They are not dropped — `unplacedCount` counts them and the
 * table lists them — but a chart cannot show a point whose position is unknown, and putting it
 * somewhere plausible is the failure mode this whole module is arranged against.
 */
export function bandLanes(record: InstrumentRecord): BandLane[] {
  if (!record.axis) return []
  return [...record.axis.steps].reverse().map((step) => ({
    step,
    runs: record.runs.filter((r) => r.placement.kind !== 'unplaced' && r.placement.step === step.index),
  }))
}

/* ── Helpers ────────────────────────────────────────────────────────────────────────────── */

/** Runs per instrument key. Insertion-ordered, so the grouping itself introduces no ranking. */
function groupByKey(all: BackupAssessment[]): Map<string, BackupAssessment[]> {
  const by = new Map<string, BackupAssessment[]>()
  for (const run of all) {
    const list = by.get(run.key)
    if (list) list.push(run)
    else by.set(run.key, [run])
  }
  return by
}

/** By title, then key. Fixed: no instrument can climb this list by having been run more often. */
function compareRecords(a: InstrumentRecord, b: InstrumentRecord): number {
  if (a.title !== b.title) return a.title < b.title ? -1 : 1
  return a.key < b.key ? -1 : a.key > b.key ? 1 : 0
}

function provenanceFor(def: InstrumentDefinition | undefined): MeasureProvenance {
  return def ? { kind: 'instrument', tier: def.provenance.tier } : { kind: 'unknown' }
}

function axisAbsenceFor(def: InstrumentDefinition | undefined): AxisAbsence {
  if (!def) return 'instrumentNotInCatalog'
  return def.scoring.scales.length === 0 ? 'instrumentScoresNothing' : 'moreThanOneScale'
}

/**
 * The words shown for a run: the file's own label wherever it has one, and only otherwise the
 * label of the band this build derived. `null` when the file recorded nothing and nothing could
 * be derived — rendered as an absence, never as a band.
 */
function labelFor(run: BackupAssessment, placement: RunPlacement, axis: BandAxis | null): string | null {
  const recorded = (run.bandLabel ?? '').trim()
  if (recorded) return recorded
  if (placement.kind === 'derived' && axis) return axis.steps[placement.step]?.label ?? null
  return null
}

/** Whitespace- and case-insensitive comparison of two band labels. Nothing else is normalised. */
function normalise(label: string | undefined): string {
  return (label ?? '').trim().replace(/\s+/g, ' ').toLowerCase()
}
