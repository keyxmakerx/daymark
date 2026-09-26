import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { parse } from 'svelte/compiler'
import ts from 'typescript'
import { PRINT_ATTRIBUTE, PRINT_ROOT_ATTRIBUTE, PRINT_VALUE } from '../../calendar/print'

/**
 * THE PERSON'S OWN MONTH, AS DRAWN (#335).
 *
 * calendar/ownMonth.test.ts holds what each day is; this holds what the components do with it,
 * structurally over their source, because these tests have no DOM:
 *
 *   · a day is drawn the same whatever it holds — the day's button, its attributes and its
 *     stylesheet never depend on what the day holds, only its marks do;
 *   · the month describes and never grades: no average, best or worst, count, arrow or verdict;
 *   · on paper, the month alone, ink on white, every square keeping its word;
 *   · the month is drawn only on the person's own data, and the clinician's calendar draws no
 *     mood's colour.
 *
 * Every detector is shown a breach planted into a copy of the real file before it is believed.
 */

const here = (path: string) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8')

const MONTH = here('./MonthCalendar.svelte')
const MOOD_MARK = here('./MoodMark.svelte')
const CALENDAR_MARK = here('./CalendarMark.svelte')
const DASHBOARD = here('../Dashboard.svelte')
const CLINICIAN_VIEW = here('../therapist/SharedDataView.svelte')
const CLINICIAN_CALENDAR = here('../therapist/CalendarScreen.svelte')
const OWN_MONTH_MODULE = here('../../calendar/ownMonth.ts')

/* ═══════════════════════════════════════════════════════════════════════════
   Reading a component.
   ═══════════════════════════════════════════════════════════════════════════ */

const blank = (m: string) => m.replace(/[^\n]/g, ' ')

/** Comments blanked where they are comments, so offsets still point at the source. */
function blankComments(src: string): string {
  const block = (code: string) => code.replace(/\/\*[\s\S]*?\*\//g, blank)
  return src
    .replace(/<!--[\s\S]*?-->/g, blank)
    .replace(/(<script[^>]*>)([\s\S]*?)(<\/script>)/g, (_, o: string, b: string, c: string) => o + block(b).replace(/(?<![:'"`\\])\/\/[^\n]*/g, blank) + c)
    .replace(/(<style[^>]*>)([\s\S]*?)(<\/style>)/g, (_, o: string, b: string, c: string) => o + block(b) + c)
}

/** What renders: the markup, with the script, the style and comments removed. */
const markupOf = (src: string) =>
  blankComments(src)
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')

/** The words a person reads: markup text with every tag and expression removed. */
const proseOf = (src: string) =>
  markupOf(src)
    .replace(/<[^>]*>/g, ' ')
    .replace(/\{[^{}]*\}/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()

const styleOf = (src: string) => (/<style[^>]*>([\s\S]*?)<\/style>/.exec(src)?.[1] ?? '').replace(/\/\*[\s\S]*?\*\//g, '')

/** The body of every `@media print` block in a stylesheet. */
function printBlocks(css: string): string[] {
  const out: string[] = []
  for (const m of css.matchAll(/@media\s+print\s*\{/g)) {
    let depth = 0
    for (let k = m.index! + m[0].length - 1; k < css.length; k++) {
      if (css[k] === '{') depth++
      else if (css[k] === '}' && --depth === 0) {
        out.push(css.slice(m.index! + m[0].length, k))
        break
      }
    }
  }
  return out
}

interface AstNode {
  type: string
  name?: string
  start: number
  end: number
  attributes?: AstNode[]
  fragment?: { nodes: AstNode[] }
  expression?: { start: number; end: number }
  body?: { nodes: AstNode[] }
  value?: unknown
  data?: string
  [key: string]: unknown
}

/** Every node of a component's markup, depth first. */
function nodesOf(src: string): AstNode[] {
  const root = parse(blankComments(src), { modern: true }) as unknown as { fragment: { nodes: AstNode[] } }
  const out: AstNode[] = []
  const SKIP = new Set(['expression', 'attributes', 'value', 'metadata', 'loc', 'context', 'key', 'index', 'test'])
  const visit = (value: unknown) => {
    if (Array.isArray(value)) return value.forEach(visit)
    if (typeof value !== 'object' || value === null) return
    const node = value as AstNode
    if (node.type !== 'Fragment' && typeof node.type === 'string') out.push(node)
    for (const [key, child] of Object.entries(node)) if (!SKIP.has(key)) visit(child)
  }
  visit(root.fragment)
  return out
}

const classesOf = (el: AstNode) => {
  const cls = el.attributes?.find((a) => a.type === 'Attribute' && a.name === 'class')
  return Array.isArray(cls?.value) ? (cls!.value as AstNode[]).map((p) => p.data ?? '').join(' ').split(/\s+/) : []
}

const meaningful = (nodes: AstNode[] = []) => nodes.filter((n) => !(n.type === 'Text' && !(n.data ?? '').trim()))

/* ═══════════════════════════════════════════════════════════════════════════
   A day is drawn the same whatever it holds.
   ═══════════════════════════════════════════════════════════════════════════ */

/** What holds a day's contents: its marks, its records, anything counted or tested for emptiness. */
const HOLDS = /marks|records|bucket|events|length|empty|count|nothing/i

/** The ways a day could be drawn differently for what it holds. Empty when there are none. */
function dayProblems(src: string): string[] {
  const code = blankComments(src)
  let nodes: AstNode[]
  try {
    nodes = nodesOf(src)
  } catch (e) {
    return [`the month does not parse, so nothing about its days can be vouched for: ${(e as Error).message}`]
  }
  const days = nodes.filter((n) => n.type === 'RegularElement' && n.name === 'button' && classesOf(n).includes('day'))
  if (days.length !== 1) return [`expected one day button, found ${days.length}`]
  const day = days[0]!
  const problems: string[] = []
  for (const a of day.attributes ?? []) {
    const text = code.slice(a.start, a.end)
    if (HOLDS.test(text)) problems.push(`the day's ${a.name ?? a.type} depends on what it holds: ${text}`)
  }
  // Inside: the number, and the marks — and the marks are one each block over the day's marks.
  const inside = meaningful(day.fragment?.nodes)
  const num = inside.find((n) => classesOf(n).includes('num'))
  const marks = inside.find((n) => classesOf(n).includes('marks'))
  if (inside.length !== 2 || !num || !marks) problems.push(`the day holds more than its number and its marks: ${inside.map((n) => n.name ?? n.type).join(', ')}`)
  if (num && code.slice(num.start, num.end).replace(/\s+/g, ' ') !== '<span class="num" aria-hidden="true">{cell.dayOfMonth}</span>') {
    problems.push(`the day's number is drawn as more than the number: ${code.slice(num.start, num.end)}`)
  }
  if (marks) {
    const eachs = meaningful(marks.fragment?.nodes)
    const each = eachs[0]
    if (eachs.length !== 1 || each?.type !== 'EachBlock' || code.slice(each.expression!.start, each.expression!.end) !== 'cell.marks') {
      problems.push('the marks are drawn from something other than the day’s own marks')
    }
  }
  // And no stylesheet rule picks a day out by what it holds.
  const EMPTINESS = /:empty|\.empty|\[data-(empty|count|marks)|:has\(/
  for (const m of styleOf(src).matchAll(/([^{}]+)\{/g)) {
    const selector = m[1]!.trim()
    if (/\.day\b|\.marks\b|\.num\b/.test(selector) && EMPTINESS.test(selector)) problems.push(`"${selector}" picks a day out by what it holds`)
  }
  return problems
}

describe('a day is drawn the same whatever it holds', () => {
  it('the day’s button, its number and its stylesheet never depend on what it holds', () => {
    expect(dayProblems(MONTH)).toEqual([])
  })

  it('sees a day drawn differently for being empty (positive control)', () => {
    const DAY = '<button\n                        type="button"\n                        class="day"'
    expect(MONTH).toContain(DAY)
    const plants = [
      // A class for the empty day. (Named for the plant alone, so it cannot collide with an
      // attribute the file already has.)
      MONTH.replace(DAY, `${DAY}\n                        class:planted-unmarked={cell.marks.length === 0}`),
      // A dimmed day.
      MONTH.replace(DAY, `${DAY}\n                        style:--planted-fade={cell.marks.length ? 1 : 0.5}`),
      // A dash where the marks would be.
      MONTH.replace('<span class="marks" aria-hidden="true">', '{#if cell.marks.length === 0}<span class="dash">–</span>{/if}\n<span class="marks" aria-hidden="true">'),
      // A count beside the number.
      MONTH.replace('{cell.dayOfMonth}</span>', '{cell.dayOfMonth} ({cell.marks.length})</span>'),
      // A stylesheet rule that fades an empty day.
      MONTH.replace('</style>', '.day:not(:has(.mood)) { opacity: 0.5; }\n</style>'),
    ]
    for (const planted of plants) {
      expect(planted).not.toBe(MONTH)
      expect(dayProblems(planted).length).toBeGreaterThan(0)
    }
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   Describe, never grade.
   ═══════════════════════════════════════════════════════════════════════════ */

/** A grade, a figure, a verdict or an arrow, in words a person reads. */
const GRADES =
  /average|\bmean\b|\bbest\b|\bworst\b|\bmost\b|\bleast\b|streak|score|total|missed|progress|\bgoal|great|well done|congrat|success|complete|[↑↓→←▲▼✓✔]|%/i

/** Every string literal a TypeScript module can put in front of a person. */
function stringsOf(code: string): string[] {
  const sf = ts.createSourceFile('module.ts', code, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const out: string[] = []
  const visit = (n: ts.Node) => {
    if (ts.isImportDeclaration(n) || ts.isExportDeclaration(n)) return
    if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) out.push(n.text)
    else if (ts.isTemplateExpression(n)) out.push(n.head.text + n.templateSpans.map((s) => ' ' + s.literal.text).join(''))
    n.forEachChild(visit)
  }
  visit(sf)
  return out
}

describe('the month describes and never grades', () => {
  it('no word on it grades, totals or points a direction', () => {
    const prose = proseOf(MONTH)
    // Witness: the prose extractor reads the month's own words.
    expect(prose).toContain('Print this month')
    expect(prose).toContain('Choose a day to see what was recorded on it.')
    expect(prose).not.toMatch(GRADES)
    expect(stringsOf(OWN_MONTH_MODULE).filter((s) => GRADES.test(s))).toEqual([])
    expect(stringsOf(OWN_MONTH_MODULE)).toContain(', today') // the extractor reads the module
  })

  it('no figure is drawn but a day’s own date', () => {
    // Every expression the markup renders as text, and none of them a length or a sum.
    const rendered = [...markupOf(MONTH).matchAll(/>\s*\{([^{}#/:@][^{}]*)\}/g)].map((m) => m[1]!.trim())
    expect(rendered).toContain('cell.dayOfMonth')
    expect(rendered.filter((e) => /length|count|size|total|reduce|\+|Math\./.test(e))).toEqual([])
    // Nothing on the month reaches for the clinician's measure, where a self-check's total lives.
    expect(blankComments(MONTH)).not.toMatch(/\.measure\b|\.score\b/)
  })

  it('sees a grade, a count or a total planted into the month (positive control)', () => {
    const plants = [
      MONTH.replace('<h3 class="title"', '<p>Best day this month</p>\n    <h3 class="title"'),
      MONTH.replace('<h3 class="title"', '<p>↑ more than last month</p>\n    <h3 class="title"'),
      MONTH.replace('<span class="kind">{KIND_LABEL[record.kind]}</span>', '<span class="kind">{KIND_LABEL[record.kind]}</span><span>{records.length}</span>'),
      MONTH.replace('{#if record.text}', '{#if record.score}<span>{record.score}</span>{/if}\n                {#if record.text}'),
    ]
    const caught = (planted: string) =>
      GRADES.test(proseOf(planted)) ||
      [...markupOf(planted).matchAll(/>\s*\{([^{}#/:@][^{}]*)\}/g)].some((m) => /length|count|size|total|reduce|\+|Math\./.test(m[1]!)) ||
      /\.measure\b|\.score\b/.test(blankComments(planted))
    for (const planted of plants) {
      expect(planted).not.toBe(MONTH)
      expect(caught(planted)).toBe(true)
    }
    expect(caught(MONTH)).toBe(false)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   On paper.
   ═══════════════════════════════════════════════════════════════════════════ */

/** What a component's print blocks leave off the paper. */
const hiddenOnPaper = (src: string) =>
  printBlocks(styleOf(src)).flatMap((block) =>
    [...block.matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter((m) => /display:\s*none/.test(m[2]!)).flatMap((m) => m[1]!.split(',').map((s) => s.trim())),
  )

describe('“Print this month” puts the month on paper as it is', () => {
  it('leaves the day panel and the controls off the paper', () => {
    const hidden = hiddenOnPaper(MONTH)
    expect(hidden).toEqual(expect.arrayContaining(['.controls', '.panel']))
  })

  it('keeps the month itself, and every square’s word, on the paper', () => {
    const KEEP = /\.grid|\.day|\.num|\.marks|\.legend|\.mood|\.mark|\.title|\.own-month/
    for (const [name, src] of [['MonthCalendar', MONTH], ['MoodMark', MOOD_MARK], ['CalendarMark', CALENDAR_MARK]] as const) {
      expect(hiddenOnPaper(src).filter((s) => KEEP.test(s)), name).toEqual([])
    }
    // The squares are backgrounds, which a browser leaves off paper unless told: both mark
    // components tell it, so a square prints beside its word.
    expect(printBlocks(styleOf(MOOD_MARK)).join('\n')).toMatch(/\.mood-mark\s*\{[^}]*print-color-adjust:\s*exact/)
    expect(printBlocks(styleOf(CALENDAR_MARK)).join('\n')).toMatch(/\.mark,\s*\.mark::before\s*\{[^}]*print-color-adjust:\s*exact/)
  })

  it('marks the month as the print root, and leaves the rest of the page off while it prints', () => {
    const root = nodesOf(MONTH).find((n) => n.type === 'RegularElement')!
    expect(classesOf(root)).toContain('own-month')
    const attr = root.attributes?.find((a) => a.name === PRINT_ROOT_ATTRIBUTE)
    expect(Array.isArray(attr?.value) ? (attr!.value as AstNode[])[0]!.data : null).toBe(PRINT_VALUE)
    const css = styleOf(MONTH)
    expect(css).toContain(`:global(html[${PRINT_ATTRIBUTE}='${PRINT_VALUE}'] body *:not([${PRINT_ROOT_ATTRIBUTE}], [${PRINT_ROOT_ATTRIBUTE}] *, :has([${PRINT_ROOT_ATTRIBUTE}])))`)
    // Both isolation rules sit inside a print block, so the screen is never affected.
    expect(printBlocks(css).join('\n')).toContain(`html[${PRINT_ATTRIBUTE}='${PRINT_VALUE}']`)
    expect(css.replace(/@media\s+print\s*\{[\s\S]*$/, '')).not.toContain(PRINT_ATTRIBUTE)
  })

  it('sees the panel printed, or a word left off the paper (positive control)', () => {
    // Written whole, so the control tests the reader whatever the month's stylesheet holds today.
    expect(hiddenOnPaper('<style>\n@media print {\n  .controls,\n  .panel { display: none; }\n}\n</style>')).toEqual(['.controls', '.panel'])
    expect(hiddenOnPaper('<style>\n@media print {\n  .controls { display: none; }\n}\n.panel { display: none; }\n</style>')).toEqual(['.controls'])
    const wordLeftOff = MOOD_MARK.replace('@media print {', '@media print {\n    .mood-word { display: none; }')
    expect(wordLeftOff).not.toBe(MOOD_MARK)
    expect(hiddenOnPaper(wordLeftOff).filter((s) => /\.mood/.test(s))).toEqual(['.mood-word'])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   Whose month it is.
   ═══════════════════════════════════════════════════════════════════════════ */

/** Where a file mounts the month, and whether each mount sits inside `{#if ownData}`. */
function mounts(src: string): { inside: boolean }[] {
  const markup = markupOf(src)
  const out: { inside: boolean }[] = []
  for (const m of markup.matchAll(/<MonthCalendar\b/g)) {
    const before = markup.slice(0, m.index)
    const opened = before.lastIndexOf('{#if ownData}')
    const closed = before.lastIndexOf('{/if}')
    out.push({ inside: opened !== -1 && opened > closed })
  }
  return out
}

describe('the month is the person’s own', () => {
  it('is drawn only by the Dashboard, and only on the person’s own data', () => {
    expect(mounts(DASHBOARD)).toEqual([{ inside: true }])
    // The clinician's view mounts the Dashboard and says the records are not the person's own.
    expect(CLINICIAN_VIEW).toMatch(/<Dashboard [^>]*ownData=\{false\}/)
    expect(mounts(CLINICIAN_VIEW)).toEqual([])
  })

  it('sees a month mounted outside the gate, or in the clinician’s view (positive control)', () => {
    // Written whole, so the control tests the detector whatever the Dashboard holds today.
    expect(mounts('{#if ownData}<MonthCalendar {data} />{/if}')).toEqual([{ inside: true }])
    expect(mounts('<MonthCalendar {data} />')).toEqual([{ inside: false }])
    expect(mounts('{#if true}<MonthCalendar {data} />{/if}')).toEqual([{ inside: false }])
    expect(mounts('{#if ownData}{/if}<MonthCalendar {data} />')).toEqual([{ inside: false }])
    const planted = CLINICIAN_VIEW.replace('<Dashboard {data}', '<MonthCalendar {data} />\n    <Dashboard {data}')
    expect(planted).not.toBe(CLINICIAN_VIEW)
    expect(mounts(planted).length).toBe(1)
  })

  it('the clinician’s calendar draws no mood’s colour: its marks are the shared ink shapes', () => {
    const DRAWS_A_MOOD = /MoodMark|ownMoodColours|ownMoodFill|--mood-/
    expect(blankComments(CLINICIAN_CALENDAR)).not.toMatch(DRAWS_A_MOOD)
    expect(markupOf(CLINICIAN_CALENDAR).match(/<CalendarMark\b/g)?.length).toBe(4)
    // Control: the same detector sees a mood mark imported into it.
    const planted = CLINICIAN_CALENDAR.replace("import CalendarMark from '../calendar/CalendarMark.svelte'", "import CalendarMark from '../calendar/CalendarMark.svelte'\n  import MoodMark from '../calendar/MoodMark.svelte'")
    expect(planted).not.toBe(CLINICIAN_CALENDAR)
    expect(blankComments(planted)).toMatch(DRAWS_A_MOOD)
  })

  it('every mood square on the month is given its word', () => {
    const moodMarks = [...markupOf(MONTH).matchAll(/<MoodMark\b[^>]*>/g)].map((m) => m[0])
    expect(moodMarks.length).toBe(2) // the day's squares and the panel's
    for (const tag of moodMarks) expect(tag).toMatch(/\bword=\{[^}]+\}/)
    // MoodMark draws the word it is given, beside the square, always.
    expect(markupOf(MOOD_MARK)).toMatch(/<span class="mood-mark"[^>]*><\/span><span class="mood-word">\{word\}<\/span>/)
  })
})
