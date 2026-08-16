import { describe, it, expect, afterEach, vi } from 'vitest'
import {
  buildClientRecord,
  buildInstrumentRecord,
  buildSeries,
  bandAxisFor,
  bandLanes,
  placeRun,
  primaryScale,
  stepForScore,
} from './clientRecord'
import type { BandAxis, InstrumentLookup, RunPoint } from './clientRecord'
import { CATALOG, getInstrument } from '../instruments'
import type { Band, InstrumentDefinition, Scale } from '../instruments/types'
import type { BackupAssessment, BackupData } from '../backup'

/*
 * WHAT THIS SUITE IS FOR.
 *
 * Three properties, and two of them are ABSENCES — the hardest kind of test to write honestly:
 *
 *   1. A score maps to the right band AT EVERY EDGE. Off-by-one on a band boundary is the bug
 *      that matters here, because it is invisible: the chart still draws, the label still reads
 *      like a band, and a clinician has no way to tell that a run one point below a boundary was
 *      shown one lane too high.
 *   2. An instrument with no runs yields NO POINTS — not a zero at the origin, which would draw a
 *      person sitting in the lowest band on a day nobody assessed them.
 *   3. No number reaches the view model, so no renderer can put one on an axis.
 *
 * Every absence assertion below is paired with a POSITIVE CONTROL that proves the check can see
 * the thing when the thing is really there. Where the control is a separate `it`, it says so;
 * where it is inline, a comment names the line that is doing the controlling. A test asserting
 * "X is absent" that has never once observed X present is not a test.
 */

const DAY = 24 * 60 * 60 * 1000
const NOW = Date.UTC(2026, 7, 16, 12, 0, 0) // a fixed instant; nothing here reads a clock
const ago = (days: number) => NOW - days * DAY

const WELLBEING = 'wellbeing-selfcheck'
const FOCUS = 'focus-selfcheck'
/** A catalog definition that scores nothing — `scoring.scales` is deliberately empty. */
const EXERCISE = 'compassion-hard-moment'

/** The wellbeing scale's own band words, quoted so a catalog re-wording fails loudly here. */
const WELLBEING_BANDS = [
  'Lower than your own usual, by today’s answers',
  'Around your own middle today',
  'Brighter than your own usual today',
]

function bundle(over: Partial<BackupData> = {}): BackupData {
  return {
    version: 14,
    exportedAt: ago(1),
    entries: [],
    activities: [],
    refs: [],
    journal: [],
    goals: [],
    sleepLogs: [],
    assessments: [],
    moodLabels: {},
    moodColors: {},
    ...over,
  }
}

function run(id: number, key: string, daysAgo: number, bandLabel: string, score = 0): BackupAssessment {
  return { id, key, dateTime: ago(daysAgo), score, bandLabel }
}

/* ── Synthetic definitions, for band tables the catalog deliberately does not contain ──────── */

function definition(over: Partial<InstrumentDefinition> & { scoring: { scales: Scale[] } }): InstrumentDefinition {
  return {
    instrumentId: 'synthetic',
    instrumentVersion: '1.0.0',
    title: 'Synthetic',
    license: 'test fixture',
    provenance: { tier: 'custom' },
    nonDiagnostic: true,
    noScreeningFlag: true,
    items: [],
    framing: { intro: 'test fixture' },
    ...over,
  }
}

function scale(bands: Band[], id = 's'): Scale {
  return { id, method: 'sum', items: [], bands, bandFraming: 'Descriptive only.' }
}

/** Three contiguous integer bands, 0–4 / 5–9 / 10+. Every edge in this suite is one of these. */
const CONTIGUOUS: Band[] = [
  { max: 4, label: 'Low band', tone: 'neutral' },
  { min: 5, max: 9, label: 'Middle band', tone: 'neutral' },
  { min: 10, label: 'High band', tone: 'attention' },
]

/** The same table with 5–9 removed, so 5..9 is a genuine hole rather than a rounding artefact. */
const WITH_A_GAP: Band[] = [
  { max: 4, label: 'Low band', tone: 'neutral' },
  { min: 10, label: 'High band', tone: 'attention' },
]

const lookupOf = (defs: Record<string, InstrumentDefinition>): InstrumentLookup => (key) => defs[key]

/* ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the fixtures this suite reasons from are really what it thinks they are', () => {
  it('the catalog instruments it names exist, and one of them scores nothing', () => {
    // Without this, every assertion below could be passing against `undefined` — a lookup miss
    // produces an unknown-provenance record with no axis, which is a legitimate shape and would
    // quietly satisfy several of the structural checks further down.
    const wellbeing = getInstrument(WELLBEING)
    expect(wellbeing, 'the wellbeing self-check left the catalog').toBeDefined()
    expect(wellbeing!.scoring.scales).toHaveLength(1)
    expect(wellbeing!.scoring.scales[0]!.bands.map((b) => b.label)).toEqual(WELLBEING_BANDS)

    expect(getInstrument(FOCUS)?.scoring.scales).toHaveLength(1)

    const exercise = getInstrument(EXERCISE)
    expect(exercise, 'the unscored exercise left the catalog').toBeDefined()
    expect(exercise!.scoring.scales).toHaveLength(0)

    // And the key used for the "not in this build" case really is not in this build.
    expect(getInstrument('phq-9-not-shipped')).toBeUndefined()
  })

  it('the band tables the boundary tests use are shaped as claimed', () => {
    // CONTIGUOUS must have no hole (or the "every integer lands somewhere" test is vacuous) and
    // WITH_A_GAP must have one (or the "a gap is not snapped shut" test is).
    expect(CONTIGUOUS[0]!.max! + 1).toBe(CONTIGUOUS[1]!.min!)
    expect(CONTIGUOUS[1]!.max! + 1).toBe(CONTIGUOUS[2]!.min!)
    expect(WITH_A_GAP[0]!.max! + 1).toBeLessThan(WITH_A_GAP[1]!.min!)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. The edges.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('a score maps to the right band at every boundary', () => {
  it('lands on the correct step on both sides of every edge in a contiguous table', () => {
    // Stated one integer at a time rather than by loop, because a loop that computed the expected
    // answer the same way the implementation does would agree with any bug it contained.
    expect(stepForScore(-1, CONTIGUOUS)).toBe(0) // first band declares no min
    expect(stepForScore(0, CONTIGUOUS)).toBe(0)
    expect(stepForScore(3, CONTIGUOUS)).toBe(0)
    expect(stepForScore(4, CONTIGUOUS)).toBe(0) // its max, inclusive
    expect(stepForScore(5, CONTIGUOUS)).toBe(1) // the next band's min, inclusive
    expect(stepForScore(6, CONTIGUOUS)).toBe(1)
    expect(stepForScore(9, CONTIGUOUS)).toBe(1) // its max, inclusive
    expect(stepForScore(10, CONTIGUOUS)).toBe(2) // the last band's min, inclusive
    expect(stepForScore(11, CONTIGUOUS)).toBe(2)
    expect(stepForScore(9_999, CONTIGUOUS)).toBe(2) // last band declares no max
  })

  it('holds at the shipped catalog’s own edges', () => {
    // The instrument a clinician will actually be looking at. Quoted literally so a change to the
    // wellbeing bands has to be made here too, deliberately, rather than sliding through.
    const wellbeing = getInstrument(WELLBEING)!.scoring.scales[0]!.bands
    expect(stepForScore(7, wellbeing)).toBe(0)
    expect(stepForScore(8, wellbeing)).toBe(1)
    expect(stepForScore(14, wellbeing)).toBe(1)
    expect(stepForScore(15, wellbeing)).toBe(2)

    const focus = getInstrument(FOCUS)!.scoring.scales[0]!.bands
    expect(stepForScore(9, focus)).toBe(0)
    expect(stepForScore(10, focus)).toBe(1)
    expect(stepForScore(16, focus)).toBe(1)
    expect(stepForScore(17, focus)).toBe(2)
  })

  it('every declared edge in the whole catalog is inclusive on both sides', () => {
    // The sweep, which catches an instrument added later with a band table nobody hand-checked.
    // A sweep can go vacuous by finding no edges at all, so the edges it examined are counted and
    // the count is asserted — the number is the subject of the test as much as the equalities are.
    let edges = 0
    for (const def of CATALOG) {
      for (const s of def.scoring.scales) {
        s.bands.forEach((band, i) => {
          const next = s.bands[i + 1]
          if (band.max === undefined || next?.min === undefined) return
          if (next.min !== band.max + 1) return // non-contiguous by design; covered separately
          edges++
          expect(stepForScore(band.max, s.bands), `${def.instrumentId} at ${band.max}`).toBe(i)
          expect(stepForScore(next.min, s.bands), `${def.instrumentId} at ${next.min}`).toBe(i + 1)
        })
      }
    }
    expect(edges, 'the sweep found no band edges to check').toBeGreaterThanOrEqual(4)
  })

  it('a score in a hole between two bands is unplaceable rather than snapped to a neighbour', () => {
    // The positive control is the first line: the same function DOES place a score that is
    // genuinely inside a band, so a null below means "no band contains this", not "returns null".
    expect(stepForScore(4, WITH_A_GAP)).toBe(0)
    expect(stepForScore(5, WITH_A_GAP)).toBeNull()
    expect(stepForScore(9, WITH_A_GAP)).toBeNull()
    expect(stepForScore(10, WITH_A_GAP)).toBe(1)

    // The same thing arises in the shipped catalog for a fractional score, because its bands are
    // integer-contiguous: 7.5 is above "max 7" and below "min 8" and belongs to neither.
    const wellbeing = getInstrument(WELLBEING)!.scoring.scales[0]!.bands
    expect(stepForScore(7, wellbeing)).toBe(0)
    expect(stepForScore(7.5, wellbeing)).toBeNull()
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. The axis.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the y-axis is the instrument’s bands and carries no number and no valence', () => {
  it('is the scale’s own labels, in the scale’s own order, with the scale’s own framing', () => {
    const s = getInstrument(WELLBEING)!.scoring.scales[0]!
    const axis = bandAxisFor(s)
    expect(axis.scaleId).toBe(s.id)
    expect(axis.steps.map((step) => step.label)).toEqual(WELLBEING_BANDS)
    expect(axis.steps.map((step) => step.index)).toEqual([0, 1, 2])
    // The non-diagnostic sentence travels with the axis rather than being rewritten downstream.
    expect(axis.framing).toBe(s.bandFraming)
    expect(axis.framing).toContain('not a clinical threshold')
  })

  it('a step exposes only its position and its words — no min, no max, no tone', () => {
    // The control: the SOURCE band really does carry all three of the things being excluded, so
    // an empty result here would be the axis dropping them rather than there being none to drop.
    const source = getInstrument(FOCUS)!.scoring.scales[0]!.bands[2]!
    expect(source.min).toBeDefined()
    expect(source.tone).toBe('attention')

    const step = bandAxisFor(getInstrument(FOCUS)!.scoring.scales[0]!).steps[2]!
    expect(Object.keys(step).sort()).toEqual(['index', 'label'])
    // Restated as a value check as well, so a future `tone: undefined` on the step — which would
    // keep the key out of Object.keys in some shapes — still fails.
    expect(JSON.stringify(step)).not.toContain('attention')
  })

  it('an instrument with one scale has an axis; none and several do not', () => {
    const one = definition({ scoring: { scales: [scale(CONTIGUOUS)] } })
    expect(primaryScale(one)).toBe(one.scoring.scales[0])

    // Nothing to measure.
    expect(primaryScale(definition({ scoring: { scales: [] } }))).toBeNull()

    // Two scales and one recorded score per run: which scale it belongs to is not in the file, so
    // picking the first would be a guess handed to a clinician as a fact.
    const two = definition({ scoring: { scales: [scale(CONTIGUOUS, 'a'), scale(CONTIGUOUS, 'b')] } })
    expect(primaryScale(two)).toBeNull()
  })

  it('names which of the three reasons an axis is missing', () => {
    const notInCatalog = buildInstrumentRecord('phq-9-not-shipped', [run(1, 'phq-9-not-shipped', 3, 'Moderate')])
    expect(notInCatalog.axis).toBeNull()
    expect(notInCatalog.axisAbsence).toBe('instrumentNotInCatalog')
    expect(notInCatalog.title).toBe('phq-9-not-shipped') // the raw key, never an invented title

    const unscored = buildInstrumentRecord(EXERCISE, [run(1, EXERCISE, 3, '')])
    expect(unscored.axis).toBeNull()
    expect(unscored.axisAbsence).toBe('instrumentScoresNothing')
    expect(unscored.title).toBe(getInstrument(EXERCISE)!.title)

    const lookup = lookupOf({
      two: definition({ scoring: { scales: [scale(CONTIGUOUS, 'a'), scale(CONTIGUOUS, 'b')] } }),
    })
    const ambiguous = buildInstrumentRecord('two', [run(1, 'two', 3, 'Low band')], lookup)
    expect(ambiguous.axis).toBeNull()
    expect(ambiguous.axisAbsence).toBe('moreThanOneScale')

    // The control for all three: an instrument this build fully understands HAS an axis, so a
    // null above is a stated reason rather than the builder never producing an axis at all.
    const known = buildInstrumentRecord(WELLBEING, [run(1, WELLBEING, 3, WELLBEING_BANDS[1]!)])
    expect(known.axis).not.toBeNull()
    expect(known.axisAbsence).toBeNull()
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. The empty series — the point that must never be drawn.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('an instrument with no runs yields an empty series, not a zero at the origin', () => {
  const axis = bandAxisFor(scale(CONTIGUOUS))

  it('the control: a genuine lowest-band run IS emitted, at step 0', () => {
    // This is the whole basis for the next test. If the builder emitted nothing for anything, the
    // "no point at the origin" assertion below would pass while checking nothing at all. So first
    // prove that a run scoring into the FIRST band — the origin lane, the one a fabricated zero
    // would land in — really does produce a point there.
    const points = buildSeries('k', [run(1, 'k', 5, '', 0)], axis, CONTIGUOUS, { kind: 'unknown' })
    expect(points).toHaveLength(1)
    expect(points[0]!.placement).toEqual({ kind: 'derived', step: 0 })
    expect(points[0]!.label).toBe('Low band')
  })

  it('no runs produces no points', () => {
    expect(buildSeries('k', [], axis, CONTIGUOUS, { kind: 'unknown' })).toEqual([])
  })

  it('and the record around it reports absence rather than a placed observation', () => {
    const record = buildInstrumentRecord(WELLBEING, [])
    expect(record.runs).toEqual([])
    expect(record.firstAt).toBeNull()
    expect(record.lastAt).toBeNull()
    expect(record.unplacedCount).toBe(0)
    // The axis still exists — the instrument is known — but every lane is empty. An empty lane is
    // an empty lane; it is never filled in with a mark at step 0.
    expect(record.axis).not.toBeNull()
    expect(bandLanes(record).map((lane) => lane.runs.length)).toEqual([0, 0, 0])
  })

  it('a bundle with no assessments lists no instruments and invents no rows', () => {
    const view = buildClientRecord({ data: bundle(), now: NOW })
    expect(view.instruments).toEqual([])
    expect(view.scope.empty).toBe(true)
    expect(view.scope.runCount).toBe(0)
    expect(view.scope.instrumentCount).toBe(0)

    // Catalog instruments that were never run stay out entirely. Listing every shipped tool with
    // a row saying nothing turns the record into a checklist of things the person has not done.
    expect(CATALOG.length).toBeGreaterThan(0) // control: there ARE instruments it could have listed
    const view2 = buildClientRecord({
      data: bundle({ assessments: [run(1, WELLBEING, 4, WELLBEING_BANDS[0]!)] }),
      now: NOW,
    })
    expect(view2.instruments.map((i) => i.key)).toEqual([WELLBEING])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. Placement — whose band label wins.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('a band the file recorded is never overruled by a band this build would compute', () => {
  const axis = bandAxisFor(scale(CONTIGUOUS))

  it('the recorded label decides the step even when the stored score disagrees', () => {
    // A run whose score says "High band" but whose recorded label says "Low band" — the shape a
    // re-cut definition produces. The person was shown "Low band"; that is where it goes.
    const disagreeing = run(1, 'k', 2, 'Low band', 99)
    expect(stepForScore(disagreeing.score, CONTIGUOUS)).toBe(2) // control: the score really says 2
    expect(placeRun(disagreeing, axis, CONTIGUOUS)).toEqual({ kind: 'recorded', step: 0 })
  })

  it('matching is insensitive to case and whitespace, and to nothing else', () => {
    expect(placeRun(run(1, 'k', 2, '  middle   BAND '), axis, CONTIGUOUS)).toEqual({
      kind: 'recorded',
      step: 1,
    })
    // Not fuzzy: a different word is a different band, and guessing at near-misses is exactly the
    // silent rewriting this rule exists to prevent.
    expect(placeRun(run(1, 'k', 2, 'Middle bands'), axis, CONTIGUOUS)).toEqual({
      kind: 'unplaced',
      reason: 'bandNotOnAxis',
    })
  })

  it('a recorded band this build does not have is listed and left off the trend', () => {
    const record = buildInstrumentRecord(
      WELLBEING,
      [run(1, WELLBEING, 9, WELLBEING_BANDS[1]!), run(2, WELLBEING, 2, 'Severe')],
      undefined,
    )
    expect(record.runs).toHaveLength(2) // it is not dropped from the record
    expect(record.runs[1]!.label).toBe('Severe') // and its own words are kept, not replaced
    expect(record.runs[1]!.placement).toEqual({ kind: 'unplaced', reason: 'bandNotOnAxis' })
    expect(record.unplacedCount).toBe(1)
    // It appears in no lane, because there is no honest lane to put it in.
    expect(bandLanes(record).flatMap((lane) => lane.runs.map((r) => r.id))).toEqual([record.runs[0]!.id])
  })

  it('an unrecorded band is derived from the score, and says so', () => {
    const placement = placeRun(run(1, 'k', 2, '', 7), axis, CONTIGUOUS)
    expect(placement).toEqual({ kind: 'derived', step: 1 })
  })

  it('an unrecorded band whose score falls in a hole is unplaced, not nudged', () => {
    const gapped = bandAxisFor(scale(WITH_A_GAP))
    expect(placeRun(run(1, 'k', 2, '', 4), gapped, WITH_A_GAP)).toEqual({ kind: 'derived', step: 0 })
    expect(placeRun(run(1, 'k', 2, '', 7), gapped, WITH_A_GAP)).toEqual({
      kind: 'unplaced',
      reason: 'scoreBetweenBands',
    })
  })

  it('with no axis at all, nothing is placed and no axis is improvised from the labels', () => {
    // The tempting bug: build an axis out of whatever distinct labels the file happens to carry.
    // Their ORDER is not in the file, so any axis built that way is invented — and an invented
    // order is a scale a clinician would read as the instrument's own.
    const record = buildInstrumentRecord('phq-9-not-shipped', [
      run(1, 'phq-9-not-shipped', 20, 'Mild'),
      run(2, 'phq-9-not-shipped', 2, 'Moderate'),
    ])
    expect(record.axis).toBeNull()
    expect(bandLanes(record)).toEqual([])
    expect(record.runs.map((r) => r.placement)).toEqual([
      { kind: 'unplaced', reason: 'noAxis' },
      { kind: 'unplaced', reason: 'noAxis' },
    ])
    expect(record.unplacedCount).toBe(2)
    // The labels themselves survive: the table can still say what each run recorded.
    expect(record.runs.map((r) => r.label)).toEqual(['Mild', 'Moderate'])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   5. No number reaches the view model.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the score is read once and never carried', () => {
  it('a run point exposes exactly its instant, its band words, its placement and its source', () => {
    const input = run(1, WELLBEING, 3, WELLBEING_BANDS[2]!, 17)
    // The control: the score really is present on the way in, and it is a distinctive value.
    expect(input.score).toBe(17)

    const point = buildInstrumentRecord(WELLBEING, [input]).runs[0]!
    expect(Object.keys(point).sort()).toEqual(['at', 'fraction', 'id', 'label', 'placement', 'provenance'])
    // A whitelist of keys is the assertion that survives a refactor; the value check below is the
    // one that survives a key being renamed to something innocent like `raw` or `total`.
    expect(Object.values(point)).not.toContain(17)
  })

  it('the axis carries no numeric threshold a reader could read a cut-off off', () => {
    const s = getInstrument(WELLBEING)!.scoring.scales[0]!
    // The control: the SOURCE bands carry the cut points, and they are real numbers.
    expect(s.bands[1]!.min).toBe(8)
    expect(s.bands[1]!.max).toBe(14)

    const axis: BandAxis = bandAxisFor(s)
    const serialised = JSON.stringify(axis.steps)
    expect(serialised).not.toContain('"min"')
    expect(serialised).not.toContain('"max"')
    // 8 and 14 must not appear as values anywhere in the steps. Checked by value rather than by
    // substring, because the band words themselves are free to contain digits one day.
    for (const step of axis.steps) {
      expect(Object.values(step)).not.toContain(8)
      expect(Object.values(step)).not.toContain(14)
    }
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   6. Spacing, lanes and the record as a whole.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('runs are spaced by when they happened, so a gap is drawn as a gap', () => {
  it('the first run sits at 0, the last at 1, and the middle in proportion', () => {
    const record = buildInstrumentRecord(WELLBEING, [
      run(1, WELLBEING, 40, WELLBEING_BANDS[0]!),
      run(2, WELLBEING, 30, WELLBEING_BANDS[1]!),
      run(3, WELLBEING, 0, WELLBEING_BANDS[2]!),
    ])
    expect(record.runs.map((r) => r.fraction)).toEqual([0, 0.25, 1])
    // Which also means the 30-day silence between run 2 and run 3 occupies three quarters of the
    // track. Nothing is drawn in it and nothing is counted about it.
  })

  it('a single run sits mid-track rather than pinned to an edge', () => {
    const record = buildInstrumentRecord(WELLBEING, [run(1, WELLBEING, 5, WELLBEING_BANDS[0]!)])
    expect(record.runs.map((r) => r.fraction)).toEqual([0.5])
  })

  it('runs are ordered oldest first however the file listed them', () => {
    const record = buildInstrumentRecord(WELLBEING, [
      run(2, WELLBEING, 1, WELLBEING_BANDS[2]!),
      run(1, WELLBEING, 50, WELLBEING_BANDS[0]!),
    ])
    expect(record.runs.map((r) => r.at)).toEqual([ago(50), ago(1)])
    expect(record.firstAt).toBe(ago(50))
    expect(record.lastAt).toBe(ago(1))
  })
})

describe('the lanes account for every run exactly once', () => {
  it('run highest band first, and lanes plus unplaced covers the whole series', () => {
    const record = buildInstrumentRecord(WELLBEING, [
      run(1, WELLBEING, 30, WELLBEING_BANDS[0]!),
      run(2, WELLBEING, 20, WELLBEING_BANDS[2]!),
      run(3, WELLBEING, 10, WELLBEING_BANDS[0]!),
      run(4, WELLBEING, 5, 'Severe'), // not on this axis
    ])
    const lanes = bandLanes(record)
    // Top-to-bottom reading order: the last-declared band is the first lane.
    expect(lanes.map((lane) => lane.step.label)).toEqual([...WELLBEING_BANDS].reverse())
    expect(lanes.map((lane) => lane.runs.length)).toEqual([1, 0, 2])

    const inLanes = lanes.flatMap((lane) => lane.runs.map((r) => r.id))
    expect(new Set(inLanes).size).toBe(inLanes.length) // no run appears twice
    expect(inLanes.length + record.unplacedCount).toBe(record.runs.length)
  })
})

describe('the record states its own edges', () => {
  it('reports the export instant, its age, and the counts — and nothing derived from them', () => {
    const view = buildClientRecord({
      data: bundle({
        exportedAt: ago(3),
        assessments: [
          run(1, WELLBEING, 20, WELLBEING_BANDS[1]!),
          run(2, FOCUS, 10, 'A moderate amount noted today'),
          run(3, WELLBEING, 4, WELLBEING_BANDS[2]!),
        ],
      }),
      now: NOW,
    })
    expect(view.scope.exportedAt).toBe(ago(3))
    expect(view.scope.ageDays).toBe(3)
    expect(view.scope.runCount).toBe(3)
    expect(view.scope.instrumentCount).toBe(2)
    expect(view.scope.empty).toBe(false)
  })

  it('an undated bundle reports no export instant and no age, rather than a zero', () => {
    // A zero age would read as "exported today", which is a claim the file does not make.
    const view = buildClientRecord({ data: bundle({ exportedAt: 0 }), now: NOW })
    expect(view.scope.exportedAt).toBeNull()
    expect(view.scope.ageDays).toBeNull()
  })

  it('instruments are ordered by title, not by how often they were run', () => {
    const view = buildClientRecord({
      data: bundle({
        assessments: [
          run(1, WELLBEING, 20, WELLBEING_BANDS[1]!),
          run(2, WELLBEING, 10, WELLBEING_BANDS[1]!),
          run(3, WELLBEING, 5, WELLBEING_BANDS[1]!),
          run(4, FOCUS, 4, 'A moderate amount noted today'),
        ],
      }),
      now: NOW,
    })
    // The busiest instrument does not rise. Titles: "Daily wellbeing self-check" sorts before
    // "Focus & follow-through self-check", and the run counts say the opposite.
    expect(view.instruments.map((i) => i.title)).toEqual([
      getInstrument(WELLBEING)!.title,
      getInstrument(FOCUS)!.title,
    ])
    expect(view.instruments.map((i) => i.runs.length)).toEqual([3, 1])
  })

  it('carries provenance on every run, per row, and marks an unknown key as unknown', () => {
    const view = buildClientRecord({
      data: bundle({
        assessments: [
          run(1, WELLBEING, 20, WELLBEING_BANDS[1]!),
          run(2, WELLBEING, 10, WELLBEING_BANDS[1]!),
          run(3, 'phq-9-not-shipped', 5, 'Moderate'),
        ],
      }),
      now: NOW,
    })
    const byKey = new Map(view.instruments.map((i) => [i.key, i]))
    const known = byKey.get(WELLBEING)!
    // Per ROW, not once per card: the badge is meant to repeat down a table so a reader never has
    // to scroll back up to find out what they are holding.
    expect(known.runs.map((r: RunPoint) => r.provenance)).toEqual([
      { kind: 'instrument', tier: 'custom' },
      { kind: 'instrument', tier: 'custom' },
    ])
    expect(byKey.get('phq-9-not-shipped')!.runs[0]!.provenance).toEqual({ kind: 'unknown' })
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   7. The clock.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the module never reads a clock', () => {
  afterEach(() => vi.restoreAllMocks())

  it('a clock read throws under the stub, and building the record does not', () => {
    // The bug this exists for shipped once already, in TherapistPortal.svelte: a $derived called a
    // function that read Date.now() internally, so the reactive graph had nothing to track and the
    // guard never fired again after its first evaluation. Time is an argument here for that reason,
    // and a reviewer grepping for `Date.now()` will eventually miss one.
    vi.spyOn(Date, 'now').mockImplementation(() => {
      throw new Error('clock read')
    })
    expect(() => Date.now()).toThrow('clock read') // the control: the stub really does trip

    const data = bundle({
      assessments: [
        run(1, WELLBEING, 20, WELLBEING_BANDS[0]!),
        run(2, WELLBEING, 3, WELLBEING_BANDS[2]!),
        run(3, 'phq-9-not-shipped', 1, 'Moderate'),
      ],
    })
    expect(() => buildClientRecord({ data, now: NOW })).not.toThrow()
    expect(buildClientRecord({ data, now: NOW }).scope.runCount).toBe(3)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   The axis recovered from the runs themselves.

   Why this matters more than it looks: `getInstrument` matches on instrumentId,
   the shipped catalog holds four ids, and the phone writes none of them — it
   writes `phq9`, `gad7`, `who5`, carried verbatim into `assessments[].key`. So
   for EVERY bundle a therapist can actually open, the catalog lookup misses. A
   catalog-only axis meant the band trend — the whole reason this screen exists
   — rendered on no real data at all.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('an axis recovered from the runs', () => {
  const scored = (id: number, daysAgo: number, band: string, score: number) =>
    run(id, 'phq9', daysAgo, band, score)

  it('orders bands by the scores that produced them, not by their words', () => {
    // Alphabetically "Mild" < "Moderate" < "Severe" happens to agree with severity here, so the
    // fixture puts the scores in the OPPOSITE order to the labels' alphabetical order to prove the
    // ordering follows the numbers. "Awful" sorts first alphabetically and last by score.
    const record = buildInstrumentRecord('phq9', [
      scored(1, 30, 'Awful', 22),
      scored(2, 20, 'Mild', 6),
      scored(3, 10, 'Moderate', 12),
    ])
    expect(record.axis).not.toBeNull()
    expect(record.axis!.steps.map((st) => st.label)).toEqual(['Mild', 'Moderate', 'Awful'])
    expect(record.axisDerived).toBe(true)
    expect(record.axis!.scaleId).toBe('derived:phq9')
    // The control: a declared instrument still reports a NON-derived axis, so `axisDerived` is
    // tracking something rather than being true everywhere.
    const known = buildInstrumentRecord(WELLBEING, [run(1, WELLBEING, 3, WELLBEING_BANDS[1]!)])
    expect(known.axisDerived).toBe(false)
  })

  it('refuses when the same score sat in two different bands', () => {
    // Then no ordering follows from the file, and picking one would be this software asserting a
    // clinical ordering it was never given.
    const record = buildInstrumentRecord('phq9', [
      scored(1, 30, 'Mild', 10),
      scored(2, 20, 'Moderate', 10),
    ])
    expect(record.axis).toBeNull()
    expect(record.axisAbsence).toBe('instrumentNotInCatalog')
  })

  it('refuses on a single band, and on labels with no scores behind them', () => {
    // One band orders nothing, and a lane on its own would read as the whole scale.
    const one = buildInstrumentRecord('phq9', [scored(1, 30, 'Mild', 4), scored(2, 20, 'Mild', 6)])
    expect(one.axis).toBeNull()
    // The detector: the SAME two runs with distinct bands do produce an axis, so the null above is
    // the single-band rule and not this builder failing to produce an axis at all.
    const two = buildInstrumentRecord('phq9', [scored(1, 30, 'Mild', 4), scored(2, 20, 'Severe', 19)])
    expect(two.axis).not.toBeNull()
  })
})
