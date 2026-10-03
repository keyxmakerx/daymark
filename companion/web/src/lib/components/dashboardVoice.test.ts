import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/*
 * THE DASHBOARD SAYS "YOUR" ONLY TO THE PERSON WHOSE DATA IT IS (#421).
 *
 * One Dashboard serves a person reading their own data (the owner console, a backup opened in the
 * viewer) and a clinician reading a share, which mounts it with `ownData={false}` (#280). A
 * sentence that says "your" on the clinician's view addresses the clinician: "relative to your
 * overall average" reads as though the average were theirs. On a screen about someone else's
 * mental health, that confusion matters. So each sentence that says "your" has a second fixed
 * form, and `{#if ownData}` picks one: "your" on the person's own data, "their" on a share.
 *
 * Structural, because these tests have no DOM. The markup is read as each view draws it, with every
 * `{#if ownData}` block reduced to the branch that view draws and everything else kept, and every
 * absence is asserted after the same reading has seen a planted breach.
 */

const DASHBOARD = readFileSync(fileURLToPath(new URL('./Dashboard.svelte', import.meta.url)), 'utf8')
const CLINICIAN_VIEW = readFileSync(fileURLToPath(new URL('./therapist/SharedDataView.svelte', import.meta.url)), 'utf8')

/** Each sentence that says "your" on the person's own data, and its form on the clinician's view. */
const FORMS: { own: string; theirs: string }[] = [
  {
    own: 'Average mood on days with each activity, relative to your overall average. This shows association, not causation.',
    theirs: 'Average mood on days with each activity, relative to their overall average. This shows association, not causation.',
  },
  {
    own: 'Descriptive trends of your own scores over time — not a diagnosis.',
    theirs: 'Descriptive trends of their own scores over time — not a diagnosis.',
  },
]

/** "You" in any of its forms, as a word. */
const YOU = /\byou(?:r|rs|rself)?\b/i

/** The markup: script, style and comments removed. */
const markupOf = (src: string) =>
  src
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')
    .replace(/<!--[\s\S]*?-->/g, '')

type Block = { tags: string[]; branches: Part[][]; close: string }
type Part = string | Block

/** The markup as a tree of Svelte blocks: `{#…}` opens one, `{:…}` starts its next branch, `{/…}` closes it. */
function blocksOf(markup: string): Part[] {
  const root: Part[] = []
  const open: Block[] = []
  let into = root
  let from = 0
  for (const m of markup.matchAll(/\{([#:/])[^}]*\}/g)) {
    into.push(markup.slice(from, m.index))
    from = m.index! + m[0].length
    if (m[1] === '#') {
      const block: Block = { tags: [m[0]], branches: [[]], close: '' }
      into.push(block)
      open.push(block)
      into = block.branches[0]!
    } else {
      const block = open.at(-1)
      if (!block) throw new Error(`${m[0]} is outside any block`)
      if (m[1] === ':') {
        block.tags.push(m[0])
        block.branches.push([])
        into = block.branches.at(-1)!
      } else {
        block.close = m[0]
        open.pop()
        into = open.at(-1)?.branches.at(-1) ?? root
      }
    }
  }
  into.push(markup.slice(from))
  if (open.length > 0) throw new Error(`${open.at(-1)!.tags[0]} is never closed`)
  return root
}

/**
 * The markup one view draws: each `{#if ownData}` (or `{#if !ownData}`) block reduced to the
 * branches that view can draw, and every other block kept whole, tags and all, because whether it
 * draws depends on the data rather than on the view.
 */
function asSeenBy(parts: Part[], own: boolean): string {
  return parts
    .map((part) => {
      if (typeof part === 'string') return part
      const onView = /^\{#if\s+(!?)\s*ownData\s*\}$/.exec(part.tags[0]!)
      if (!onView) return part.branches.map((branch, i) => part.tags[i] + asSeenBy(branch, own)).join('') + part.close
      const drawsFirst = (onView[1] === '') === own
      return (drawsFirst ? part.branches.slice(0, 1) : part.branches.slice(1)).map((branch) => asSeenBy(branch, own)).join('')
    })
    .join('')
}

/** The words a person reads: tags removed without a space, as `<strong>` renders inline, and whitespace folded. */
const proseOf = (markup: string) => markup.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ')

/** What the clinician's view draws of a Dashboard source. */
const clinicianSees = (src: string) => asSeenBy(blocksOf(markupOf(src)), false)

const OWN_VIEW = asSeenBy(blocksOf(markupOf(DASHBOARD)), true)
const THEIR_VIEW = clinicianSees(DASHBOARD)

describe('the Dashboard says "your" only to the person whose data it is (#421)', () => {
  it('has a subject: the prop, the clinician’s mount, and a reading that tells the two views apart', () => {
    // On unless a mount says otherwise, and the clinician's view says otherwise.
    expect(DASHBOARD).toMatch(/\bownData = true\b/)
    expect(CLINICIAN_VIEW).toMatch(/<Dashboard\b[^>]*\bownData=\{false\}/)
    // The reading drops what only the person's own view draws, the month calendar (#335)…
    expect(OWN_VIEW).toContain('<MonthCalendar')
    expect(THEIR_VIEW).not.toContain('<MonthCalendar')
    // …and keeps what both draw, the blocks that depend on the data included.
    for (const view of [OWN_VIEW, THEIR_VIEW]) {
      expect(view).toContain('{#if assessments.length > 0}')
      expect(view).toContain('<JournalReader')
    }
  })

  it('on the person’s own data, each sentence says "your", as it always has', () => {
    for (const { own, theirs } of FORMS) {
      expect(proseOf(OWN_VIEW)).toContain(own)
      expect(proseOf(OWN_VIEW)).not.toContain(theirs)
    }
  })

  it('on the clinician’s view, each sentence speaks about the person instead', () => {
    for (const { own, theirs } of FORMS) {
      expect(proseOf(THEIR_VIEW)).toContain(theirs)
      expect(proseOf(THEIR_VIEW), own).not.toContain(own)
    }
  })

  it('nothing the clinician’s view draws says "you" or "your", in its words or in its attributes', () => {
    expect(THEIR_VIEW.match(new RegExp(YOU.source, 'gi')) ?? []).toEqual([])
    // Non-vacuity: the person's own view says it, once in each sentence that has two forms.
    expect(OWN_VIEW.match(new RegExp(YOU.source, 'gi'))?.length).toBe(FORMS.length)
  })

  it('sees the person’s words on the clinician’s view, wherever they are put back (positive control)', () => {
    const plants: [what: string, planted: string][] = [
      [
        'one sentence for both views, as the Dashboard had it',
        DASHBOARD.replace(/\{#if ownData\}\s*(<p class="faint">Descriptive trends of your own scores[^\n]*<\/p>)[\s\S]*?\{\/if\}/, '$1'),
      ],
      ['"your" in the clinician’s branch', DASHBOARD.replace('relative to their overall average', 'relative to your overall average')],
      ['"your" in an attribute both views draw', DASHBOARD.replace('aria-label="Time range"', 'aria-label="Your time range"')],
    ]
    for (const [what, planted] of plants) {
      expect(planted, what).not.toBe(DASHBOARD)
      expect(YOU.test(clinicianSees(planted)), what).toBe(true)
    }
    // And each of the person's sentences is seen when it is drawn on the clinician's view.
    expect(proseOf(clinicianSees(plants[0]![1]))).toContain(FORMS[1]!.own)
    expect(proseOf(clinicianSees(plants[1]![1]))).toContain(FORMS[0]!.own)
  })
})
