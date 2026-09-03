import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { SHAPES, type DeploymentShape } from '../../setup/shape'

/*
 * How the three first-run cards are NAMED.
 *
 * Each card was a single <button> wrapping every sentence on it, so its accessible name — the
 * thing a screen reader announces on focus, and the thing a voice-control user has to say to
 * press it — was the whole card: some eighty words, before the next card's eighty. The fix names
 * the button from a heading (aria-labelledby) and hangs the rest off aria-describedby, which is
 * announced after the name and can be cut short.
 *
 * There is no component-rendering harness in this project (vite.config.ts sets `environment:
 * 'node'`), so this reads the source, the same way shape.test.ts §7 does. The accessible-name
 * computation is emulated over the markup rather than asserted by grep alone: find the id the
 * button points at, find the element that carries it, and read what that element renders. That
 * is the step the browser performs, and it is the step a careless edit — a renamed id, the label
 * moved out of the heading — would break while every string on the screen stayed the same.
 */
const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
const SOURCE = read('./SetupEntry.svelte')
const STYLE = SOURCE.match(/<style>[\s\S]*?<\/style>/)?.[0] ?? ''
const MARKUP = SOURCE.replace(/<script[\s\S]*?<\/script>/g, '')
  .replace(/<style>[\s\S]*?<\/style>/g, '')
  .replace(/<!--[\s\S]*?-->/g, '')

/** The one card template: everything between the catalog loop's open and close. */
const EACH_OPEN = '{#each SHAPES as shape (shape.id)}'
const CARD = MARKUP.slice(
  MARKUP.indexOf(EACH_OPEN) + EACH_OPEN.length,
  MARKUP.indexOf('{/each}', MARKUP.indexOf(EACH_OPEN)),
)

/**
 * A button's opening tag. Mustache-aware, because `onclick={() => …}` carries a `>` of its own
 * and a naive `[^>]*` would stop there and read the tag as still open.
 */
const BUTTON_OPEN = /<button\b(?:[^>{]|\{[^{}]*\})*>/g
const EMPTY_BUTTON = /<button\b(?:[^>{]|\{[^{}]*\})*>\s*<\/button>/

/** The opening tag of the card's control. Exactly one button per card. */
const BUTTON_TAG = CARD.match(BUTTON_OPEN) ?? []

/** An attribute's value on a tag, or null. */
const attr = (tag: string, name: string): string | null =>
  new RegExp(`\\b${name}="([^"]*)"`).exec(tag)?.[1] ?? null

/**
 * What the element with this id renders, as a template: the text between its open and close
 * tags with nested tags removed. Returns null when no element carries the id.
 */
function templateOf(id: string): string | null {
  const open = new RegExp(`<([a-z0-9]+)\\b[^>]*\\bid="${id.replace(/[{}.]/g, '\\$&')}"[^>]*>`).exec(CARD)
  if (!open) return null
  const tag = open[1]!
  const start = open.index + open[0].length
  // Innermost-first close: the element's own closing tag is the first `</tag>` after its open
  // for the heading (which nests nothing), and the LAST before the button for the description
  // (which nests only <p>s, never another div).
  const end = tag === 'div' ? CARD.lastIndexOf(`</${tag}>`, CARD.indexOf('<button')) : CARD.indexOf(`</${tag}>`, start)
  return CARD.slice(start, end).replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim()
}

/** Fill a template's `{shape.<field>}` mustaches from a real catalog entry. */
const render = (template: string, shape: DeploymentShape) =>
  template.replace(/\{shape\.([a-zA-Z]+)\}/g, (_, field: string) => String(shape[field as keyof DeploymentShape]))

const words = (s: string) => s.split(/\s+/).filter((w) => /[a-z]/i.test(w)).length

describe('the source is what we think it is', () => {
  it('was read, and the card template was found with one control in it', () => {
    // Guards every assertion below: an extractor that missed the loop would make them vacuous.
    expect(SOURCE.length).toBeGreaterThan(1000)
    expect(CARD.length).toBeGreaterThan(200)
    expect(CARD).toContain('{shape.label}')
    expect(BUTTON_TAG).toHaveLength(1)
    expect(SHAPES.length).toBe(3)
  })
})

describe('each card is named by its heading, and described by the rest', () => {
  const button = BUTTON_TAG[0] ?? ''

  it('the control takes its name from a heading element, by id', () => {
    const nameId = attr(button, 'aria-labelledby')
    expect(nameId, 'the button has no aria-labelledby').not.toBeNull()
    const heading = new RegExp(`<h([1-6])\\b[^>]*\\bid="${nameId!.replace(/[{}.]/g, '\\$&')}"`).exec(CARD)
    expect(heading, 'the id the button is named from is not on a heading element').not.toBeNull()
    // One level under the question's <h3 id="choice-heading">: no level skipped, none repeated.
    expect(MARKUP).toContain('<h3 id="choice-heading">')
    expect(heading![1]).toBe('4')
    // The name is the label and nothing else — not the label plus the chip's word.
    expect(templateOf(nameId!)).toBe('{shape.label}')
  })

  it('the control carries no name of its own, so the heading is the whole name', () => {
    // Text inside the button, or an aria-label, would either replace or be concatenated with the
    // heading depending on the browser. There must be neither.
    expect(attr(button, 'aria-label')).toBeNull()
    expect(CARD).toMatch(EMPTY_BUTTON)
  })

  it('the description is one element holding every other sentence on the card', () => {
    const aboutId = attr(button, 'aria-describedby')
    expect(aboutId, 'the button has no aria-describedby').not.toBeNull()
    expect(aboutId).not.toContain(' ') // one element, not a list of ids
    const about = templateOf(aboutId!)
    expect(about).not.toBeNull()
    for (const field of ['summary', 'arrangement', 'ranking', 'buildNote']) {
      expect(about, `shape.${field} is not in the description`).toContain(`{shape.${field}}`)
    }
    expect(about).not.toContain('{shape.label}')
  })

  it('the computed name is short for every shape in the catalog, and the description carries the weight', () => {
    const nameId = attr(button, 'aria-labelledby')!
    const aboutId = attr(button, 'aria-describedby')!
    for (const shape of SHAPES) {
      const name = render(templateOf(nameId)!, shape)
      const description = render(templateOf(aboutId)!, shape)
      expect(name, shape.id).toBe(shape.label)
      expect(words(name), `${shape.id}: the name is a paragraph again`).toBeLessThanOrEqual(8)
      expect(words(description), `${shape.id}: the description lost its sentences`).toBeGreaterThan(40)
      // And the name really is a fraction of the card, not the card.
      expect(words(name) * 4, shape.id).toBeLessThan(words(description))
    }
  })

  it('the detector is not vacuous: it would catch the old markup', () => {
    // The shape this used to have — one button wrapping the sentences, named by its content.
    const old =
      '<button class="shape" type="button" onclick={() => onchoose(shape.id)}>' +
      '<span class="shape-label">{shape.label}</span><span>{shape.summary}</span></button>'
    const tag = old.match(BUTTON_OPEN)![0]
    expect(tag).toContain('onchoose(shape.id)}>') // the tag matcher read past the arrow
    expect(attr(tag, 'aria-labelledby')).toBeNull()
    expect(old).not.toMatch(EMPTY_BUTTON)
  })
})

describe('the overlay keeps what the whole-card button had', () => {
  it('the control still covers the card and still shows keyboard focus', () => {
    // The button is laid over the card (see the markup note), so it must be positioned against
    // the card and must draw its own focus ring — the card underneath cannot draw one for it.
    expect(STYLE).toMatch(/\.shape\s*\{[^}]*position:\s*relative/)
    expect(STYLE).toMatch(/\.choose\s*\{[^}]*position:\s*absolute/)
    expect(STYLE).toMatch(/\.choose:focus-visible\s*\{[^}]*outline:/)
  })
})
