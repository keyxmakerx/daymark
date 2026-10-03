import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { parse } from 'svelte/compiler'

/**
 * EVERY WAY OUT OF A DIALOG SAYS WHAT IT DOES (#400).
 *
 * docs/COMPANION_UX.md §10.3: "Name what the button does: Keep sharing, Keep access, Not now — not
 * Cancel." In a dialog that publishes something signed, or removes somebody from a practice,
 * "Cancel" does not say what stays as it was. Three dialogs said it:
 *
 *   the clinician's publish dialog   Not now            beside Sign & publish
 *   the practice's removal confirm   Keep their seat    beside Remove from practice
 *   the practice's role editor       Keep their role    beside Change role
 *
 * Each is pinned below, and no button anywhere in the tree may be labelled "Cancel". The labels are
 * read with Svelte's own parser — a button's text is its children, and a regex over markup cannot
 * tell where an opening tag whose handler holds an arrow (`=>`) ends. Every absence is asserted
 * after the same reading has found a planted "Cancel".
 */

/** This file is at src/lib/components/, so two levels up is src/. */
const SRC = fileURLToPath(new URL('../../', import.meta.url))
const rel = (abs: string) => 'src/' + abs.slice(SRC.length)

function svelteFiles(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = dir + entry.name
    if (entry.isDirectory()) out.push(...svelteFiles(path + '/'))
    else if (entry.name.endsWith('.svelte')) out.push(path)
  }
  return out.sort()
}

const TREE = new Map(svelteFiles(SRC).map((f) => [rel(f), readFileSync(f, 'utf8')]))
const read = (path: string) => {
  const src = TREE.get(path)
  if (src === undefined) throw new Error(`${path} is not in the tree`)
  return src
}

const STEP_UP = 'src/lib/components/therapist/StepUpDialog.svelte'
const ROSTER = 'src/lib/components/practice/RosterPanel.svelte'

interface AstNode {
  type: string
  name?: string
  data?: string
  attributes?: AstNode[]
  fragment?: { nodes: AstNode[] }
  expression?: { type: string; name?: string }
  value?: unknown
  [key: string]: unknown
}

interface Button {
  /** The label as a person reads it: its text, spaces collapsed; an interpolation reads as `{…}`. */
  label: string
  /** The attribute and directive names on the opening tag, and each handler's own name where it has one. */
  attributes: string[]
}

/** Every <button> in a file's markup, in source order. */
function buttonsOf(src: string): Button[] {
  const root = parse(src, { modern: true }) as unknown as { fragment: AstNode }
  const out: Button[] = []
  const SKIP = new Set(['attributes', 'expression', 'metadata', 'loc', 'value'])
  const visit = (value: unknown) => {
    if (Array.isArray(value)) return value.forEach(visit)
    if (typeof value !== 'object' || value === null) return
    const node = value as AstNode
    if (node.type === 'RegularElement' && node.name === 'button') {
      const label = (node.fragment?.nodes ?? [])
        .map((c) => (c.type === 'Text' ? (c.data ?? '') : c.type === 'Comment' ? '' : '{…}'))
        .join('')
        .replace(/\s+/g, ' ')
        .trim()
      const attributes = (node.attributes ?? []).map((a) => {
        // A directive holds its expression itself; an attribute holds it in its value's tag.
        const v = a.value as AstNode | AstNode[] | true | undefined
        const expr = a.expression ?? (!Array.isArray(v) && typeof v === 'object' ? v.expression : undefined)
        const name = a.type === 'BindDirective' ? `bind:${a.name}` : `${a.name}`
        return expr?.type === 'Identifier' ? `${name}=${expr.name}` : name
      })
      out.push({ label, attributes })
    }
    for (const [key, child] of Object.entries(node)) if (!SKIP.has(key)) visit(child)
  }
  visit(root.fragment)
  return out
}

const labelsOf = (src: string) => buttonsOf(src).map((b) => b.label)
const CANCEL = /^cancel$/i

/** Every file's button labels, read once. */
const LABELS = new Map([...TREE].map(([path, src]) => [path, labelsOf(src)]))

describe('the reading has a subject', () => {
  it('walks the whole tree and reads button labels, text and interpolation alike', () => {
    expect(TREE.size).toBeGreaterThan(60)
    expect(TREE.has(STEP_UP)).toBe(true)
    expect(TREE.has(ROSTER)).toBe(true)
    // A label written as text, one split over lines, and one that is an interpolation.
    const planted = '<button onclick={() => (x = 1)}>\n  Keep\n  this\n</button><button>{LABEL}</button>'
    expect(labelsOf(planted)).toEqual(['Keep this', '{…}'])
    // And across the tree it finds hundreds of buttons, not none.
    expect([...LABELS.values()].flat().length).toBeGreaterThan(100)
  })
})

describe('the three dialogs name what their way out keeps (#400)', () => {
  it('the clinician’s publish dialog offers "Not now" beside "Sign & publish"', () => {
    const buttons = buttonsOf(read(STEP_UP))
    expect(buttons.map((b) => b.label)).toEqual(['Not now', 'Sign & publish'])
    // It is still the way out: it closes the dialog, and it is where focus lands on open.
    const notNow = buttons[0]!
    expect(notNow.attributes).toContain('onclick=oncancel')
    expect(notNow.attributes).toContain('bind:this=notNowEl')
  })

  it('the practice’s removal confirm offers "Keep their seat" and then "Remove from practice"', () => {
    const labels = labelsOf(read(ROSTER))
    const keep = labels.indexOf('Keep their seat')
    const remove = labels.indexOf('Remove from practice')
    expect(keep).toBeGreaterThan(-1)
    // The way out first, where "Remove" stood, so a second press on that spot keeps the seat.
    expect(remove).toBe(keep + 1)
  })

  it('the practice’s role editor offers "Keep their role" and then "Change role"', () => {
    const labels = labelsOf(read(ROSTER))
    const keep = labels.indexOf('Keep their role')
    expect(keep).toBeGreaterThan(-1)
    expect(labels[keep + 1]).toBe('Change role')
  })

  it('no longer says "Confirm removal", which named the dialog rather than the act', () => {
    // Control: the reading finds the old label when it is put back.
    const planted = read(ROSTER).replace('Remove from practice', 'Confirm removal')
    expect(planted).not.toBe(read(ROSTER))
    expect(labelsOf(planted)).toContain('Confirm removal')
    expect(labelsOf(read(ROSTER))).not.toContain('Confirm removal')
  })
})

describe('no button in the tree is labelled "Cancel" (COMPANION_UX.md §10.3)', () => {
  it('sees a "Cancel" planted in each of the three dialogs', () => {
    // Planted at the block that opens each dialog, not over its label, so this control stays
    // independent of the words the pins above hold.
    const CANCEL_BUTTON = '<button type="button">Cancel</button>'
    const plants: [string, string, string][] = [
      [STEP_UP, '{#if open}', `{#if open}${CANCEL_BUTTON}`],
      [ROSTER, '{#if confirming === id}', `{#if confirming === id}${CANCEL_BUTTON}`],
      [ROSTER, '{#if editingMember}', `{#if editingMember}${CANCEL_BUTTON}`],
    ]
    const cancels = (src: string) => labelsOf(src).filter((l) => CANCEL.test(l)).length
    for (const [path, anchor, withCancel] of plants) {
      const planted = read(path).replace(anchor, withCancel)
      expect(planted, anchor).not.toBe(read(path))
      expect(cancels(planted), anchor).toBe(cancels(read(path)) + 1)
    }
  })

  it('finds none', () => {
    const offenders = [...LABELS].flatMap(([path, labels]) =>
      labels.filter((l) => CANCEL.test(l)).map((l) => `${path}: ${l}`),
    )
    expect(offenders).toEqual([])
  })
})
