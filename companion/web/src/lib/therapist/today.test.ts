import { describe, it, expect, afterEach, vi } from 'vitest'
import {
  buildToday,
  deriveExceptions,
  buildRoster,
  assessCoverage,
  DEFAULT_RANGE_DAYS,
  FIRST_WEEK_DAYS,
} from './today'
import type { TrackedAssignment, RosterRow } from './today'
import type { BackupData } from '../backup'

/*
 * WHAT THIS SUITE IS FOR.
 *
 * Two thirds of it asserts that something is ABSENT — no verdict word, no score, no target, no
 * clock read, no exception. An absence assertion is the easiest kind of test to write badly:
 * `expect(text).not.toContain('worsened')` passes just as happily against an empty string, a
 * renamed field, or a function that silently returned nothing. So every absence check here is
 * paired with a POSITIVE CONTROL that proves the detector fires when the thing it looks for is
 * really there. Where the control is a separate `it`, it says so; where it is inline, the
 * comment says which line is the control.
 */

const DAY = 24 * 60 * 60 * 1000
const NOW = Date.UTC(2026, 7, 16, 12, 0, 0) // fixed instant; nothing here reads a clock
const ago = (days: number) => NOW - days * DAY

/** An empty, well-formed bundle. Overridden per test so each fixture states only what it means. */
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

function entry(id: number, daysAgo: number, moodLevel = 3) {
  return { id, dateTime: ago(daysAgo), moodLevel, note: '', photoPath: null }
}

function run(id: number, key: string, daysAgo: number, bandLabel: string, score = 0) {
  return { id, key, dateTime: ago(daysAgo), score, bandLabel }
}

/*
 * THE VERDICT DETECTOR.
 *
 * The product rule this pins: an exception states a fact and never grades it. The word list is
 * the vocabulary a well-meaning developer reaches for when they think they are being helpful —
 * "improved", "declined further", "back on track", "missed" — every one of which turns a fact
 * about a record into a judgement of a person.
 *
 * 'declined' is here as a stem of "decline" and is safe to ban in exception text because a
 * declined assignment never becomes an exception at all (see the lifecycle test below): a
 * decision the person made and communicated is not something the software escalates.
 */
const VERDICT =
  /\b(worse|worsen\w*|improv\w*|better|deteriorat\w*|regress\w*|progress\w*|success\w*|fail\w*|missed|lapse\w*|streak\w*|complian\w*|grade[ds]?|declin\w*|slipp\w*|on track|off track|well done|congratulat\w*|keep it up)\b/i

describe('the detectors this suite depends on actually detect', () => {
  it('the verdict detector fires on verdict language and not on the phrasing this module uses', () => {
    // Without this test, every "no verdict word" assertion below could be passing because the
    // regex is broken rather than because the copy is clean.
    expect(VERDICT.test('Their mood improved this fortnight.')).toBe(true)
    expect(VERDICT.test('Sleep has worsened.')).toBe(true)
    expect(VERDICT.test('Missed 3 of 5 check-ins.')).toBe(true)
    expect(VERDICT.test('Compliance 60%.')).toBe(true)
    expect(VERDICT.test('Back on track this week.')).toBe(true)
    expect(VERDICT.test('Previous run: Moderate. Most recent run: Severe.')).toBe(false)
    expect(VERDICT.test('It is marked No response.')).toBe(false)
  })

  it('the leak detectors find what they look for when it is really there', () => {
    // The control for every "this never reaches the roster" assertion further down. Text and key
    // names are checked by substring over the serialised rows:
    const present = JSON.stringify([{ id: 'x', note: 'private words' }])
    expect(present.includes('private words')).toBe(true)
    expect(JSON.stringify([{ id: 'x' }]).includes('private words')).toBe(false)

    // Numbers are checked by VALUE instead, because a digit search over serialised rows collides
    // with the epoch millis every row carries — "17" is a substring of any 2026 timestamp, so a
    // substring check on a score would fail for a reason that has nothing to do with scores.
    expect(String(Date.UTC(2026, 7, 16))).toContain('17')
    expect([1, 17, 3]).toContain(17)
    expect([1, 3]).not.toContain(17)
  })
})

describe('the module never reads a clock', () => {
  afterEach(() => vi.restoreAllMocks())

  /*
   * The bug this exists for shipped once already, in TherapistPortal.svelte: a $derived called a
   * function that read Date.now() internally, so the reactive graph had nothing to track and the
   * guard never fired again after its first evaluation. A pure module that takes `now` as an
   * argument is the fix, and this is the only kind of test that can hold it — a reviewer reading
   * for `Date.now()` will eventually miss one.
   */
  it('a function that reads the clock throws under the stub, and buildToday does not', () => {
    vi.spyOn(Date, 'now').mockImplementation(() => {
      throw new Error('clock read')
    })

    // The positive control: prove the stub really does trip a clock read.
    expect(() => Date.now()).toThrow('clock read')

    const data = bundle({
      entries: [entry(1, 30), entry(2, 2)],
      assessments: [run(1, 'wellbeing-selfcheck', 20, 'Moderate'), run(2, 'wellbeing-selfcheck', 3, 'Elevated')],
      goals: [{ id: 1, title: 'Walk', activityId: 1, targetPerWeek: 3, createdAt: ago(40), archived: false }],
    })
    expect(() => buildToday({ data, now: NOW })).not.toThrow()
  })
})

describe('the attention strip carries exceptions only', () => {
  it('an ordinary bundle produces an empty list — not a placeholder, not an all-clear', () => {
    const data = bundle({
      exportedAt: ago(1),
      entries: [entry(1, 20), entry(2, 2)],
      assessments: [run(1, 'wellbeing-selfcheck', 9, 'Moderate'), run(2, 'wellbeing-selfcheck', 2, 'Moderate')],
    })
    const out = deriveExceptions({ data, now: NOW })
    expect(out).toEqual([])

    // The positive control for the assertion above: THE SAME bundle, exported before the range,
    // produces exactly one exception. So the empty result is a decision about this data, not a
    // function that returns [] whatever it is handed.
    const stale = deriveExceptions({ data: { ...data, exportedAt: ago(90) }, now: NOW })
    expect(stale).toHaveLength(1)
    expect(stale[0]!.kind).toBe('bundleOlderThanRange')
  })

  it('no exception is phrased as a verdict, whatever kind it is', () => {
    const data = bundle({
      exportedAt: 0,
      assessments: [run(1, 'wellbeing-selfcheck', 10, 'Moderate'), run(2, 'wellbeing-selfcheck', 1, 'Severe')],
    })
    const assignments: TrackedAssignment[] = [
      { assignmentId: 'a1', title: 'Daily wellbeing self-check', status: 'noResponse', dueAt: ago(4) },
    ]
    const out = deriveExceptions({ data, now: NOW, assignments })
    expect(out.length).toBeGreaterThan(1) // several kinds are in play, so this is not one lucky string

    for (const e of out) {
      expect(VERDICT.test(e.headline), e.headline).toBe(false)
      expect(VERDICT.test(e.detail), e.detail).toBe(false)
    }
  })

  it('a band change is stated as two labels and nothing else', () => {
    const data = bundle({
      assessments: [run(1, 'wellbeing-selfcheck', 14, 'Moderate'), run(2, 'wellbeing-selfcheck', 2, 'Severe')],
    })
    const out = deriveExceptions({ data, now: NOW })
    expect(out).toHaveLength(1)
    const e = out[0]!
    expect(e.kind).toBe('bandChange')
    expect(e.detail).toContain('Moderate')
    expect(e.detail).toContain('Severe')
    expect(e.at).toBe(ago(2))
    // Named by its instrument, not by a raw key, when this build knows the instrument.
    expect(e.headline).toContain('Daily wellbeing self-check')
    // Direction is exactly what is missing: no arrow, no rank, no ordering claim.
    expect(e.detail).not.toMatch(/[↑↓]|higher|lower|up |down /i)
  })

  it('a band change is never rendered at alarm severity', () => {
    // Tone is a product decision, not styling: the alarm hue on a person's band movement would
    // deliver the verdict the words are careful to withhold. Control below proves some kind DOES
    // reach 'critical', so this is not passing because nothing is ever critical.
    const changed = deriveExceptions({
      data: bundle({
        assessments: [run(1, 'k', 14, 'Moderate'), run(2, 'k', 2, 'Severe')],
      }),
      now: NOW,
    })
    expect(changed[0]!.tone).toBe('info')

    const overdue = deriveExceptions({
      data: bundle(),
      now: NOW,
      assignments: [{ assignmentId: 'a1', title: 'Sleep diary', status: 'delivered', dueAt: ago(3) }],
    })
    expect(overdue[0]!.tone).toBe('critical')
  })

  it('an unchanged band is not an exception, and only the two most recent runs are compared', () => {
    const same = deriveExceptions({
      data: bundle({ assessments: [run(1, 'k', 10, 'Moderate'), run(2, 'k', 2, 'Moderate')] }),
      now: NOW,
    })
    expect(same).toEqual([])

    // Three runs: the older PAIR differs, the most recent pair does not. Nothing to say.
    const settled = deriveExceptions({
      data: bundle({
        assessments: [run(1, 'k', 20, 'Mild'), run(2, 'k', 10, 'Severe'), run(3, 'k', 2, 'Severe')],
      }),
      now: NOW,
    })
    expect(settled).toEqual([])

    // Control: the same three runs with a different final band do produce one exception, so the
    // two assertions above are about the comparison rule and not about a dead code path.
    const moved = deriveExceptions({
      data: bundle({
        assessments: [run(1, 'k', 20, 'Mild'), run(2, 'k', 10, 'Severe'), run(3, 'k', 2, 'Mild')],
      }),
      now: NOW,
    })
    expect(moved).toHaveLength(1)
    expect(moved[0]!.detail).toBe('Previous run: Severe. Most recent run: Mild.')
  })

  it('a single run says nothing, because there is nothing to compare it with', () => {
    const out = deriveExceptions({ data: bundle({ assessments: [run(1, 'k', 2, 'Severe')] }), now: NOW })
    expect(out).toEqual([])
  })

  it('a band change older than the range on screen stays out of the strip', () => {
    const old = deriveExceptions({
      data: bundle({ assessments: [run(1, 'k', 200, 'Mild'), run(2, 'k', 190, 'Severe')] }),
      now: NOW,
    })
    expect(old.filter((e) => e.kind === 'bandChange')).toEqual([])

    // Control: widen the range the clinician is looking at and the same pair surfaces. The rule
    // is "outside the window", not "this pair can never produce an exception".
    const wide = deriveExceptions({
      data: bundle({ assessments: [run(1, 'k', 200, 'Mild'), run(2, 'k', 190, 'Severe')] }),
      now: NOW,
      rangeDays: 365,
    })
    expect(wide.filter((e) => e.kind === 'bandChange')).toHaveLength(1)
  })

  it('an empty or missing band label is not reported as a change', () => {
    const out = deriveExceptions({
      data: bundle({ assessments: [run(1, 'k', 10, ''), run(2, 'k', 2, 'Severe')] }),
      now: NOW,
    })
    expect(out).toEqual([])
  })
})

describe('assignments past their due date', () => {
  const at = (status: TrackedAssignment['status'], dueAt: number): TrackedAssignment => ({
    assignmentId: `a-${status}`,
    title: 'Sleep diary',
    status,
    dueAt,
  })

  it('surfaces one that is past due and unanswered, carrying the one lifecycle spelling', () => {
    const out = deriveExceptions({ data: bundle(), now: NOW, assignments: [at('noResponse', ago(5))] })
    expect(out).toHaveLength(1)
    expect(out[0]!.kind).toBe('assignmentPastDue')
    expect(out[0]!.at).toBe(ago(5))
    // STATUS_LABEL's spelling, so this line and the pill a reader sees cannot drift apart.
    expect(out[0]!.detail).toBe('It is marked No response.')
  })

  it('leaves completed, declined and not-yet-due assignments alone', () => {
    const assignments = [at('completed', ago(5)), at('declined', ago(5)), at('accepted', NOW + DAY)]
    expect(deriveExceptions({ data: bundle(), now: NOW, assignments })).toEqual([])

    // Control: the same three due dates with a status that IS unanswered produce three
    // exceptions, proving the filter is about status and dueness rather than about the fixture.
    const unanswered: TrackedAssignment[] = [
      { assignmentId: 'x1', title: 'A', status: 'delivered', dueAt: ago(5) },
      { assignmentId: 'x2', title: 'B', status: 'scheduled', dueAt: ago(5) },
      { assignmentId: 'x3', title: 'C', status: 'overdue', dueAt: ago(5) },
    ]
    expect(deriveExceptions({ data: bundle(), now: NOW, assignments: unanswered })).toHaveLength(3)
  })

  it('cannot occur at all when the caller supplies nothing', () => {
    // The backup carries no assignment lifecycle, so this kind is never invented from it.
    const out = deriveExceptions({ data: bundle({ entries: [entry(1, 2)] }), now: NOW })
    expect(out.filter((e) => e.kind === 'assignmentPastDue')).toEqual([])
  })
})

describe('staleness is a fact about the file', () => {
  it('a bundle exported before the range says so, in terms of the file', () => {
    const out = deriveExceptions({ data: bundle({ exportedAt: ago(120) }), now: NOW })
    expect(out).toHaveLength(1)
    expect(out[0]!.kind).toBe('bundleOlderThanRange')
    expect(out[0]!.at).toBe(ago(120))
    expect(out[0]!.detail).toContain('not about the person')
  })

  it('a bundle with no export date is reported as undated rather than assumed current', () => {
    const out = deriveExceptions({ data: bundle({ exportedAt: 0 }), now: NOW })
    expect(out).toHaveLength(1)
    expect(out[0]!.kind).toBe('exportDateUnknown')
    expect(out[0]!.at).toBeNull()
  })

  it('the range the clinician chose is the range that decides', () => {
    const data = bundle({ exportedAt: ago(45) })
    expect(deriveExceptions({ data, now: NOW, rangeDays: 30 })).toHaveLength(1)
    expect(deriveExceptions({ data, now: NOW, rangeDays: 90 })).toEqual([])
  })
})

describe('the strip is ordered, not ranked', () => {
  it('file facts first, then assignments, then bands — deterministically', () => {
    const data = bundle({
      exportedAt: ago(120),
      assessments: [run(1, 'k', 10, 'Mild'), run(2, 'k', 2, 'Severe')],
    })
    const assignments: TrackedAssignment[] = [
      { assignmentId: 'a2', title: 'B', status: 'noResponse', dueAt: ago(2) },
      { assignmentId: 'a1', title: 'A', status: 'noResponse', dueAt: ago(9) },
    ]
    const out = deriveExceptions({ data, now: NOW, assignments })
    expect(out.map((e) => e.id)).toEqual(['bundle:stale', 'assignment:a2', 'assignment:a1', 'band:k'])

    // Same inputs in a different arrival order produce the same reading order.
    const shuffled = deriveExceptions({ data, now: NOW, assignments: [...assignments].reverse() })
    expect(shuffled.map((e) => e.id)).toEqual(out.map((e) => e.id))
  })
})

describe('the first-week case', () => {
  it('is detected when the record spans less than a week', () => {
    const c = assessCoverage({ data: bundle({ entries: [entry(1, 3), entry(2, 1)] }), now: NOW })
    expect(c.sparse).toBe(true)
    expect(c.spanDays).toBe(2)
    expect(c.recordCount).toBe(2)
  })

  it('is not claimed once the record spans longer, however thin it is', () => {
    // Two records, thirty days apart. Volume is not the question and must not become one.
    const c = assessCoverage({ data: bundle({ entries: [entry(1, 30), entry(2, 0)] }), now: NOW })
    expect(c.sparse).toBe(false)
    expect(c.spanDays).toBe(30)
    expect(c.recordCount).toBe(2)
  })

  it('holds at the boundary in both directions', () => {
    const under = assessCoverage({ data: bundle({ entries: [entry(1, 6), entry(2, 0)] }), now: NOW })
    const on = assessCoverage({ data: bundle({ entries: [entry(1, 7), entry(2, 0)] }), now: NOW })
    expect(under.spanDays).toBe(FIRST_WEEK_DAYS - 1)
    expect(under.sparse).toBe(true)
    expect(on.spanDays).toBe(FIRST_WEEK_DAYS)
    expect(on.sparse).toBe(false)
  })

  it('treats an empty bundle as sparse with nothing inferred from it', () => {
    const c = assessCoverage({ data: bundle(), now: NOW })
    expect(c).toEqual({
      recordCount: 0,
      firstRecordAt: null,
      lastRecordAt: null,
      spanDays: 0,
      sparse: true,
    })
  })

  it('counts every typed stream, and counts a goal as a declaration rather than a record', () => {
    const goalOnly = assessCoverage({
      data: bundle({ goals: [{ id: 1, title: 'Walk', activityId: 1, targetPerWeek: 3, createdAt: ago(60), archived: false }] }),
      now: NOW,
    })
    expect(goalOnly.recordCount).toBe(0) // a stated intention is not a record of anything

    // Control: the other four streams each move the count, so the zero above is about goals.
    const all = assessCoverage({
      data: bundle({
        entries: [entry(1, 30)],
        journal: [{ id: 1, dateTime: ago(20), title: 't', body: 'b' }],
        sleepLogs: [{ id: 1, night: ago(10), bedTime: 0, wakeTime: 0, sleepLatencyMin: 0, awakeMin: 0, quality: 2, note: '' }],
        assessments: [run(1, 'k', 5, 'Mild')],
      }),
      now: NOW,
    })
    expect(all.recordCount).toBe(4)
    expect(all.firstRecordAt).toBe(ago(30))
    expect(all.lastRecordAt).toBe(ago(5))
  })
})

describe('the quiet roster', () => {
  const full = () =>
    bundle({
      entries: [entry(1, 40, 2), entry(2, 3, 4), entry(3, 2, 4)],
      activities: [{ id: 7, name: 'Walk', iconKey: 'w', sortOrder: 0, archived: false }],
      refs: [
        { entryId: 1, activityId: 7 },
        { entryId: 2, activityId: 7 },
      ],
      journal: [{ id: 1, dateTime: ago(5), title: 'A hard one', body: 'private words' }],
      sleepLogs: [{ id: 1, night: ago(2), bedTime: 0, wakeTime: 0, sleepLatencyMin: 0, awakeMin: 0, quality: 1, note: 'restless' }],
      assessments: [run(1, 'wellbeing-selfcheck', 12, 'Moderate', 17), run(2, 'wellbeing-selfcheck', 2, 'Severe', 23)],
      goals: [
        { id: 1, title: 'Walk most days', activityId: 7, targetPerWeek: 5, createdAt: ago(50), archived: false },
        { id: 2, title: 'An old goal', activityId: 7, targetPerWeek: 2, createdAt: ago(90), archived: true },
      ],
      moodLabels: { '1': 'Awful', '2': 'Bad', '3': 'Meh', '4': 'Good', '5': 'Rad' },
    })

  const byId = (rows: RosterRow[], id: string) => rows.find((r) => r.id === id)

  it('lists one row per tracked thing, in a fixed order that ignores how busy each is', () => {
    const rows = buildRoster({ data: full(), now: NOW })
    expect(rows.map((r) => r.id)).toEqual([
      'stream:checkins',
      'assessment:wellbeing-selfcheck',
      'stream:sleep',
      'stream:journal',
      'goal:1',
    ])
    // Check-ins is the busiest stream here and sleep the quietest; the order above is by kind,
    // so a busy stream cannot climb and a quiet one cannot sink.
    expect(byId(rows, 'stream:checkins')!.count).toBeGreaterThan(byId(rows, 'stream:sleep')!.count)
  })

  it('omits a stream the person does not use rather than listing it as missing', () => {
    // The failure mode being avoided: a roster that names every feature turns into a checklist
    // of things the person is not doing, which is a gap drawn as a failure.
    const rows = buildRoster({ data: bundle({ entries: [entry(1, 10)] }), now: NOW })
    expect(rows.map((r) => r.kind)).toEqual(['checkins'])

    // Control: each omitted stream appears the moment it holds one record.
    const withJournal = buildRoster({
      data: bundle({ entries: [entry(1, 10)], journal: [{ id: 1, dateTime: ago(9), title: 't', body: 'b' }] }),
      now: NOW,
    })
    expect(withJournal.map((r) => r.kind)).toEqual(['checkins', 'journal'])
  })

  it('counts records inside the range but dates the last record wherever it fell', () => {
    // A stream with nothing recent keeps its row and states when it was last written to. That is
    // absence rendered as absence — no warning, no zero pretending to be a date.
    const rows = buildRoster({
      data: bundle({ entries: [entry(1, 200), entry(2, 190)] }),
      now: NOW,
      rangeDays: DEFAULT_RANGE_DAYS,
    })
    expect(rows).toHaveLength(1)
    expect(rows[0]!.count).toBe(0)
    expect(rows[0]!.lastAt).toBe(ago(190))
  })

  it('names an instrument this build knows, and shows its provenance tier', () => {
    const rows = buildRoster({ data: full(), now: NOW })
    const row = byId(rows, 'assessment:wellbeing-selfcheck')!
    expect(row.label).toBe('Daily wellbeing self-check')
    expect(row.provenance).toEqual({ kind: 'instrument', tier: 'custom' })
    expect(row.measure).toBe('Severe') // the most recent band, as its own label
    expect(row.count).toBe(2)
  })

  it('does not pretend to know an instrument it has never seen', () => {
    const rows = buildRoster({ data: bundle({ assessments: [run(1, 'mystery-scale', 2, 'Band B')] }), now: NOW })
    const row = rows[0]!
    expect(row.label).toBe('mystery-scale')
    expect(row.provenance).toEqual({ kind: 'unknown' })
  })

  it('gives every row that carries a measure a provenance, and every row that does not, none', () => {
    const rows = buildRoster({ data: full(), now: NOW })
    expect(rows.length).toBeGreaterThan(3)
    for (const r of rows) {
      if (r.measure !== null) expect(r.provenance, r.id).not.toBeNull()
      else expect(r.provenance, r.id).toBeNull()
    }
    // Control: at least one row of each side really exists in this fixture.
    expect(rows.some((r) => r.measure !== null)).toBe(true)
    expect(rows.some((r) => r.measure === null)).toBe(true)
  })

  it('renders the mood as the scale’s own word, taking the bundle’s labels first', () => {
    const rows = buildRoster({ data: full(), now: NOW })
    expect(byId(rows, 'stream:checkins')!.measure).toBe('Good') // entry 3, the most recent, level 4

    const renamed = buildRoster({
      data: bundle({ entries: [entry(1, 1, 4)], moodLabels: { '4': 'Steady' } }),
      now: NOW,
    })
    expect(renamed[0]!.measure).toBe('Steady')

    // No labels in the bundle at all: the shipped scale answers instead of a blank.
    const fallback = buildRoster({ data: bundle({ entries: [entry(1, 1, 1)], moodLabels: {} }), now: NOW })
    expect(fallback[0]!.measure).toBe('Awful')

    // Off the scale entirely: null rather than a nearest guess, because a word we invent for a
    // level we do not have is a lie told to a clinician.
    const off = buildRoster({ data: bundle({ entries: [entry(1, 1, 9)], moodLabels: {} }), now: NOW })
    expect(off[0]!.measure).toBeNull()
  })

  it('lists active goals and drops archived ones', () => {
    const rows = buildRoster({ data: full(), now: NOW })
    const goals = rows.filter((r) => r.kind === 'goal')
    expect(goals.map((g) => g.label)).toEqual(['Walk most days'])
    expect(goals[0]!.count).toBe(1) // one of the two linked entries is inside the range
    expect(goals[0]!.lastAt).toBe(ago(3))
  })

  it('gives a goal with no linked activity an honest empty row', () => {
    const rows = buildRoster({
      data: bundle({ goals: [{ id: 3, title: 'Unlinked', activityId: null, targetPerWeek: 4, createdAt: ago(20), archived: false }] }),
      now: NOW,
    })
    expect(rows).toHaveLength(1)
    expect(rows[0]!.count).toBe(0)
    expect(rows[0]!.lastAt).toBeNull()
  })

  it('never carries a target, a score, a sleep rating or a journal’s words', () => {
    /*
     * One serialisation, four absences — each of which is a product rule:
     *   targetPerWeek  → count-against-target is a compliance score with a different noun;
     *   score          → a raw sum invites reading a cut-off these instruments do not have;
     *   sleep quality  → a rating of a night nobody controls is a grade;
     *   journal body   → the roster says someone wrote, never what they wrote.
     * Both detectors are proven to work in the detector suite above, and each value below is
     * proven present in the INPUT first, so a fixture that quietly lost a field cannot make this
     * pass by accident.
     */
    const data = full()
    const input = JSON.stringify(data)
    expect(input).toContain('targetPerWeek')
    expect(input).toContain('"score":17')
    expect(input).toContain('"quality":1')
    expect(input).toContain('private words')

    const rows = buildRoster({ data, now: NOW })
    const serialised = JSON.stringify(rows)
    expect(serialised).not.toContain('targetPerWeek')
    expect(serialised).not.toContain('score')
    expect(serialised).not.toContain('quality')
    expect(serialised).not.toContain('private words')
    expect(serialised).not.toContain('A hard one')

    // The scores by value, since every row carries an epoch that a digit search would trip over.
    const values = rows.flatMap((r) => Object.values(r))
    expect(values).toContain('Severe') // control: the collection is populated and comparable
    expect(values).not.toContain(17)
    expect(values).not.toContain(23)
  })

  it('exposes no percentage, ratio or streak field of any kind', () => {
    const rows = buildRoster({ data: full(), now: NOW })
    const keys = new Set(rows.flatMap((r) => Object.keys(r)))
    expect(keys).toEqual(new Set(['id', 'kind', 'label', 'count', 'lastAt', 'measure', 'provenance']))
    // Control: the assertion above is exact, so adding a `percentComplete` would fail it. Prove
    // the comparison notices an extra key.
    expect(new Set([...keys, 'percentComplete'])).not.toEqual(keys)
  })
})

describe('buildToday assembles the whole screen', () => {
  it('returns the strip, the roster, the coverage and the window it used', () => {
    const view = buildToday({
      data: bundle({
        exportedAt: ago(1),
        entries: [entry(1, 40), entry(2, 1)],
        assessments: [run(1, 'k', 10, 'Mild'), run(2, 'k', 2, 'Severe')],
      }),
      now: NOW,
      rangeDays: 14,
    })
    expect(view.rangeDays).toBe(14)
    expect(view.rangeStart).toBe(ago(14))
    expect(view.exceptions.map((e) => e.kind)).toEqual(['bandChange'])
    expect(view.roster.map((r) => r.kind)).toEqual(['checkins', 'assessment'])
    expect(view.coverage.sparse).toBe(false)
  })

  it('still reports the strip when the bundle is too short to describe', () => {
    // Sparseness suppresses the ROSTER (the screen says so plainly instead); it must not
    // suppress a fact about the file, which is exactly when a reader most needs it.
    const view = buildToday({ data: bundle({ exportedAt: 0, entries: [entry(1, 1)] }), now: NOW })
    expect(view.coverage.sparse).toBe(true)
    expect(view.exceptions.map((e) => e.kind)).toEqual(['exportDateUnknown'])
  })
})
