import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/*
 * THE DASHBOARD'S CARDS THAT ARE NOT ABOUT A MOOD DRAW NO MOOD COLOUR: ACTIVITIES & MOOD (#404) AND
 * SELF-CHECK HISTORY (#420).
 *
 * The Activities & mood card draws, for each activity, how far the average mood on days with it
 * sits from the overall average: a bar either side of a centre line. It used to fill a bar above
 * the line with the Rad colour, --mood-5, which is green, and one below with the Bad colour,
 * --mood-2: a verdict on the activity, drawn in mood colours. docs/DESIGN.md: "A mood colour is
 * data, the value the person logged. It is never a status, success or warning colour." #358 takes
 * the same colouring out of the phone's Insights.
 *
 * The Self-check history card draws a line of each self-check's scores over time. It used to be
 * stroked in --mood-5 too, and a self-check score is not a mood, so that was a status colour on
 * the scores. It is drawn in ink, the ink the clinician's Record page draws self-checks in.
 *
 * The tree-wide colour check cannot see either card by itself: Dashboard.svelte is allowed to name
 * mood tokens, because it draws a person's own moods elsewhere on the page. invariants.tree.test.ts
 * holds that allowance to the mood distribution's rules; this suite reads each card itself. The
 * Dashboard is the same component on a person's own view and on the clinician's view of a share,
 * so one reading covers both.
 *
 * Structural, because these tests have no DOM. Every absence is asserted after the same reading
 * has seen a planted breach.
 */

const DASHBOARD = readFileSync(fileURLToPath(new URL('./Dashboard.svelte', import.meta.url)), 'utf8')
const CLINICIAN_VIEW = readFileSync(fileURLToPath(new URL('./therapist/SharedDataView.svelte', import.meta.url)), 'utf8')

/** A card's markup: from its opening comment to the next card's, comments removed. */
function cardBetween(src: string, opening: string, next: string): string {
  const at = src.indexOf(opening)
  const end = src.indexOf(next, at)
  if (at < 0 || end < 0) return ''
  return src.slice(at, end).replace(/<!--[\s\S]*?-->/g, '')
}

const cardOf = (src: string) => cardBetween(src, '<!-- Activities & mood -->', '<!-- Self-check history -->')
const selfCheckCardOf = (src: string) => cardBetween(src, '<!-- Self-check history -->', '<!-- Journal -->')

/** Every rule in the component's stylesheet whose selector names one of a card's classes, comments removed. */
function rulesNaming(src: string, classes: RegExp): { selector: string; body: string }[] {
  const css = [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]).join('\n')
  return [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .map((m) => ({ selector: m[1]!.trim(), body: m[2]!.trim() }))
    .filter((r) => classes.test(r.selector))
}

/** Every rule that styles the Activities & mood card's bars. */
const barRules = (src: string) => rulesNaming(src, /\.delta\b|\.assoc\b/)
/** Every rule that styles the Self-check history card's trends. */
const trendRules = (src: string) => rulesNaming(src, /\.trend\b|\.assess\b|\.line\b/)

/**
 * The class attribute of the card's bar, as written. The tag is cut at its own `/>` rather than
 * matched with `[^>]*`, because its position expressions hold `>=`.
 */
function barClass(card: string): string | null {
  const at = card.indexOf('<rect', card.indexOf('<svg class="delta"'))
  if (at < 0) return null
  const tag = card.slice(at, card.indexOf('/>', at))
  return /\bclass=(\{[^}]*\}|"[^"]*")/.exec(tag)?.[1] ?? null
}

const MOOD = /--mood-|ownMoodFill|mood-mark/

const CARD = cardOf(DASHBOARD)
const RULES = barRules(DASHBOARD)

describe('the Activities & mood card draws its bars in ink, never in a mood colour (#404)', () => {
  it('has a subject: the card, its bar and the rules that style it are found', () => {
    expect(CARD).toContain('<svg class="delta"')
    expect(CARD).toContain('<rect')
    expect(RULES.map((r) => r.selector)).toContain('.delta .bar')
    // Both views draw this card: the clinician's view of a share mounts the same Dashboard.
    expect(CLINICIAN_VIEW).toMatch(/<Dashboard\b/)
  })

  it('draws every bar with one class, whichever side of the line it sits', () => {
    // Control: a class chosen by the bar's side is read as written, not as one plain class.
    const bySide = `<svg class="delta"><rect x={a.delta >= 0 ? 100 : 0} class={a.delta >= 0 ? 'pos' : 'neg'} /></svg>`
    expect(barClass(bySide)).toBe("{a.delta >= 0 ? 'pos' : 'neg'}")
    expect(barClass(CARD)).toBe('"bar"')
  })

  it('fills the bars with ink, and no rule that styles them names a mood colour', () => {
    const moodRules = (src: string) => barRules(src).filter((r) => MOOD.test(r.body)).map((r) => r.selector)
    // Control: the old rules, planted back into the stylesheet, are seen.
    const planted = DASHBOARD.replace('</style>', '  .delta .pos { fill: var(--mood-5); }\n  .delta .neg { fill: var(--mood-2); }\n</style>')
    expect(planted).not.toBe(DASHBOARD)
    expect(moodRules(planted)).toEqual([...moodRules(DASHBOARD), '.delta .pos', '.delta .neg'])
    expect(moodRules(DASHBOARD)).toEqual([])
    expect(RULES.find((r) => r.selector === '.delta .bar')?.body).toBe('fill: var(--ink-accent);')
  })

  it('carries no mood colour in the card’s markup either', () => {
    // Control: an inline mood fill on the bar is seen.
    const inline = CARD.replace('<rect', '<rect style:--mood-fill={ownMoodFill(5, palette)}')
    expect(inline).not.toBe(CARD)
    expect(MOOD.test(inline)).toBe(true)
    expect(MOOD.test(CARD)).toBe(false)
  })

  it('says association and claims no cause', () => {
    // #358's register for the phone: the card describes what was logged alongside what.
    expect(CARD).toContain('association, not cause')
    const CAUSE = /lifts you|weighs you|makes you|improves|boosts|worsens|\bhelps\b/i
    expect(CAUSE.test('Walking lifts your mood')).toBe(true)
    expect(CAUSE.test(CARD)).toBe(false)
  })
})

/** The class attribute of the self-check card's line, as written, cut at the tag's own `/>`. */
function lineClass(card: string): string | null {
  const at = card.indexOf('<path', card.indexOf('class="trend"'))
  if (at < 0) return null
  const tag = card.slice(at, card.indexOf('/>', at))
  return /\bclass=(\{[^}]*\}|"[^"]*")/.exec(tag)?.[1] ?? null
}

const SELF_CHECK_CARD = selfCheckCardOf(DASHBOARD)
const TREND_RULES = trendRules(DASHBOARD)
const INK_LINE = 'fill: none; stroke: var(--ink-accent); stroke-width: 2; vector-effect: non-scaling-stroke;'

describe('the Self-check history card draws its line in ink, never in a mood colour (#420)', () => {
  it('has a subject: the card, its line and the rule that styles it are found', () => {
    expect(SELF_CHECK_CARD).toContain('Self-check history')
    expect(SELF_CHECK_CARD).toMatch(/<svg [^>]*class="trend"/)
    expect(SELF_CHECK_CARD).toContain('<path d={assessPath(a.points)}')
    expect(TREND_RULES.map((r) => r.selector)).toContain('.trend .line')
    // Both views draw this card: the clinician's view of a share mounts the same Dashboard.
    expect(CLINICIAN_VIEW).toMatch(/<Dashboard\b/)
  })

  it('draws the line with one plain class, whichever view it is on', () => {
    // Control: a class chosen by the view is read as written, not as one plain class.
    const byView = `<svg class="trend"><path d={assessPath(a.points)} class={ownData ? 'line own' : 'line'} /></svg>`
    expect(lineClass(byView)).toBe("{ownData ? 'line own' : 'line'}")
    expect(lineClass(SELF_CHECK_CARD)).toBe('"line"')
  })

  it('strokes the line with ink, and no rule that styles the card names a mood colour', () => {
    const moodRules = (src: string) => trendRules(src).filter((r) => MOOD.test(r.body)).map((r) => r.selector)
    // Control: the line as it was, on the Rad colour, planted back into the stylesheet, is seen.
    const planted = DASHBOARD.replace(`.trend .line { ${INK_LINE} }`, `.trend .line { ${INK_LINE.replace('var(--ink-accent)', 'var(--mood-5)')} }`)
    expect(planted).not.toBe(DASHBOARD)
    expect(moodRules(planted)).toEqual(['.trend .line'])
    // And a rule for one view only, added beside the ink one, is seen as well.
    const oneView = DASHBOARD.replace('</style>', '  .own .trend .line { stroke: var(--mood-5); }\n</style>')
    expect(oneView).not.toBe(DASHBOARD)
    expect(moodRules(oneView)).toEqual(['.own .trend .line'])
    expect(moodRules(DASHBOARD)).toEqual([])
    expect(TREND_RULES.find((r) => r.selector === '.trend .line')?.body).toBe(INK_LINE)
  })

  it('carries no mood colour in the card’s markup either', () => {
    // Control: an inline mood stroke on the line is seen.
    const inline = SELF_CHECK_CARD.replace('<path d={assessPath(a.points)}', '<path style:stroke={ownMoodFill(5, palette)} d={assessPath(a.points)}')
    expect(inline).not.toBe(SELF_CHECK_CARD)
    expect(MOOD.test(inline)).toBe(true)
    expect(MOOD.test(SELF_CHECK_CARD)).toBe(false)
  })
})
