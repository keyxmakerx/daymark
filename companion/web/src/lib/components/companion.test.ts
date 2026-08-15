import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/*
 * Source assertions on the companion component.
 *
 * There is no component-rendering harness in this project (vite.config.ts sets `environment:
 * 'node'`), so this reads the source — the same approach trustbar.test.ts takes and for the same
 * reason: the realistic regression here is a person re-typing markup, not a subtle runtime path.
 * The conversation's behaviour is tested properly in companion/walk.test.ts, which executes it.
 *
 * Each rule below is a decision from docs/DECISIONS_2026-08.md §D1b that would be easy to undo by
 * accident while "improving" the UI.
 */
const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
const SOURCE = read('./Companion.svelte')
const MARKUP = SOURCE.replace(/<script[\s\S]*?<\/script>/g, '').replace(/<style>[\s\S]*?<\/style>/g, '')

describe('the source is what we think it is', () => {
  it('was read, and has both a script and markup', () => {
    // Guards every assertion below: a stripper that ate the file would make them all pass.
    expect(SOURCE.length).toBeGreaterThan(1000)
    expect(SOURCE).toContain('<script lang="ts">')
    expect(MARKUP).toContain('<button')
    expect(MARKUP).not.toContain('<script')
  })
})

describe('fixed choices, never a text box', () => {
  // A chat field implies it will parse what you type. It cannot, and someone in a bad moment
  // typing something real into a thing that cannot answer is the failure this prevents.
  it('renders no text input of any kind', () => {
    expect(MARKUP).not.toMatch(/<textarea/i)
    expect(MARKUP).not.toMatch(/<input/i)
    expect(MARKUP).not.toMatch(/contenteditable/i)
  })

  it('the check is not vacuous — it catches a text box added to this markup', () => {
    const mutated = MARKUP + '\n<textarea rows="3"></textarea>'
    expect(/<textarea/i.test(mutated)).toBe(true)
  })

  it('offers its choices as real buttons, so they are keyboard-operable', () => {
    expect(MARKUP).toMatch(/<button class="opt"/)
  })
})

describe('hidden means hidden', () => {
  it('exposes a hide control that tells the host, rather than deciding to return by itself', () => {
    expect(SOURCE).toContain('onhide')
    expect(MARKUP).toContain('onclick={hide}')
  })

  it('contains no timer that could bring it back', () => {
    // setTimeout/setInterval here would be the shape of "reappears to say it missed you".
    expect(SOURCE).not.toMatch(/setTimeout|setInterval/)
  })
})

describe('it is not the crisis path', () => {
  it('hard-codes no crisis copy, number, or safety-plan authoring of its own', () => {
    // It may POINT at a plan the person already wrote — that is a destination resolved in content.ts
    // — but it must not carry crisis wording itself or offer to become one.
    expect(SOURCE).not.toMatch(/\b988\b|hotline|crisis line|emergency services/i)
    expect(SOURCE).not.toMatch(/create (a|your) safety plan|write a safety plan/i)
  })
})

describe('the conversation is not reimplemented here', () => {
  it('delegates to the pure walk rather than branching in the component', () => {
    expect(SOURCE).toContain("from '../companion/walk'")
    expect(SOURCE).toMatch(/\bstart\b/)
    expect(SOURCE).toMatch(/\bchoose\b/)
  })

  it('does not evaluate predicates itself', () => {
    // A second copy of the branching logic is how the UI and the content start to disagree.
    expect(SOURCE).not.toContain('evalPredicate')
    expect(SOURCE).not.toMatch(/hardDaysLast7|checkInsLast7|daysSinceLastOpen/)
  })

  it('resolves an ending destination from the walk, not from its own conditions', () => {
    expect(SOURCE).toContain('walk.destination')
  })
})

describe('it asks no permission to answer someone who opened it', () => {
  it('does not consult an arbiter on open', () => {
    // §D1b: opening it is the person speaking first. The gate is only for surfacing unprompted,
    // which this does not do.
    expect(SOURCE).not.toMatch(/InterruptionBudget|shouldInterrupt|mayInterrupt/)
  })
})

describe('it styles through tokens and respects reduced motion', () => {
  it('hardcodes no colour literal', () => {
    const styles = SOURCE.match(/<style>[\s\S]*?<\/style>/)?.[0] ?? ''
    expect(styles.length).toBeGreaterThan(200)
    expect(styles).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(styles).not.toMatch(/\brgba?\(\s*\d/)
  })

  it('uses no mood token — the companion is interface, not a person’s data', () => {
    // The pattern is assembled from parts on purpose. Spelling the token out here would put its
    // name in this file, and the tree-wide guard in invariants.tree.test.ts greps for exactly that
    // — it would have flagged this test as a violation of the rule it exists to check. That guard
    // is right to be literal, so the fix belongs on this side.
    const moodToken = new RegExp('var\\(--' + 'mood-')
    expect(moodToken.test('color: var(--' + 'mood-4)')).toBe(true) // the pattern works
    expect(moodToken.test(SOURCE)).toBe(false)
  })

  it('turns its animation off under prefers-reduced-motion', () => {
    expect(SOURCE).toContain('prefers-reduced-motion')
  })
})
