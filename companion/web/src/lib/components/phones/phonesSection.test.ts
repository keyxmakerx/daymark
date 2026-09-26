import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

/*
 * THE PHONES SECTION, READ AS SOURCE (#431). Its states, transitions and words are node tests in
 * lib/phones; what is left to hold here is that the component adds nothing of its own: no word the
 * view did not give it, no server text, no request behind a button that must not make one, and no
 * announcement but the one.
 */

const SOURCE = readFileSync(new URL('./PhonesSection.svelte', import.meta.url), 'utf8')

const withoutComments = (src: string) => src.replace(/<!--[\s\S]*?-->/g, '')
const scriptOf = (src: string) => /<script[^>]*>([\s\S]*?)<\/script>/.exec(src)?.[1] ?? ''
const markupOf = (src: string) =>
  withoutComments(src).replace(/<script[\s\S]*?<\/script>/g, '').replace(/<style[\s\S]*?<\/style>/g, '')

/**
 * The words the markup itself would put on screen: tags and `{…}` expressions removed, whitespace
 * collapsed. Expressions go first, so a `>` inside one cannot end a tag early. Anything left is a
 * literal nobody wrote in copy.ts.
 */
function literalProse(src: string): string {
  let text = markupOf(src)
  for (;;) {
    const next = text.replace(/\{[^{}]*\}/g, '')
    if (next === text) break
    text = next
  }
  return text.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim()
}

describe('the Phones section says only what the view gives it', () => {
  it('its markup holds no words of its own', () => {
    expect(markupOf(SOURCE)).toContain('view.lede')
    expect(literalProse(SOURCE)).toBe('')
  })

  it('the check sees a literal sentence planted in the markup (positive control)', () => {
    const planted = SOURCE.replace('<p class="lede">{view.lede}</p>', '<p class="lede">Scan the code below.</p>')
    expect(planted).not.toBe(SOURCE)
    expect(literalProse(planted)).toBe('Scan the code below.')
  })

  it("never renders an error's own message, or raw HTML", () => {
    const MESSAGE = /\.message\b|\{@html/
    expect(MESSAGE.test(withoutComments(SOURCE))).toBe(false)
    // Positive control: the pattern sees both.
    expect(MESSAGE.test('{error.message}')).toBe(true)
    expect(MESSAGE.test('{@html view.lede}')).toBe(true)
  })
})

describe('the buttons that must not ask the server, do not', () => {
  // A button that asks goes through `act(…)`; one that only changes what is on screen through `set(…)`.
  const handlerOf = (label: string) => new RegExp(`onclick=\\{\\(\\) => (set|act)\\(([^\\n]*?)\\}[^>]*>\\s*\\{${label.replace(/\./g, '\\.')}\\}`)

  it('"The words don\'t match" and "Discard this code" only change what is on screen', () => {
    expect(handlerOf('area.mismatch').exec(SOURCE)?.slice(1, 3)).toEqual(['set', 'wordsDontMatch(phones))'])
    expect(handlerOf('area.discard').exec(SOURCE)?.slice(1, 3)).toEqual(['set', 'discard(phones))'])
  })

  it('"Pair this phone" is the button that asks, and confirms through the ceremony', () => {
    expect(handlerOf('area.pair').exec(SOURCE)?.slice(1, 3)).toEqual(['act', 'pairThisPhone)'])
  })

  it('"Back to phones" only changes what is on screen', () => {
    expect(/if \(id === 'back'\) set\(back\(phones\)\)/.test(SOURCE)).toBe(true)
  })

  it('the reading sees a handler that asks (positive control)', () => {
    const asks = SOURCE.replace('set(wordsDontMatch(phones))', 'act(wordsDontMatch)')
    expect(asks).not.toBe(SOURCE)
    expect(handlerOf('area.mismatch').exec(asks)?.[1]).toBe('act')
  })
})

describe('time on screen', () => {
  it('every clock sits in a silent region, and the one polite region carries only the announcement', () => {
    const markup = markupOf(SOURCE)
    const clocks = [...markup.matchAll(/<p[^>]*>(?:(?!<\/p>)[\s\S])*?<span class="clock">/g)].map((m) => m[0])
    expect(clocks.length).toBe(2)
    for (const c of clocks) expect(c).toMatch(/^<p[^>]*aria-live="off"/)
    const polite = [...markup.matchAll(/<[^>]*aria-live="polite"[^>]*>([^<]*)</g)].map((m) => m[1]!.trim())
    expect(polite).toEqual(['{view.announcement}'])
    expect(markup).not.toMatch(/aria-live="assertive"|role="timer"|role="progressbar"|<progress/)
  })

  it('polls on the ceremony\'s cadence and stops when the code leaves the screen', () => {
    const script = scriptOf(SOURCE)
    expect(script).toMatch(/setTimeout\([\s\S]*?POLL_EVERY_MS\)/)
    expect(script).toMatch(/codeOnScreen = \$derived\(phones\.pairing\.step === 'waiting'/)
    expect(script).toMatch(/if \(codeOnScreen === null\) return/)
    expect(script).toMatch(/stopped = true\s*\n\s*clearTimeout\(timer\)/)
    // No animation anywhere in the section's styles, read without their comments.
    const style = (/<style>([\s\S]*?)<\/style>/.exec(SOURCE)?.[1] ?? '').replace(/\/\*[\s\S]*?\*\//g, '')
    const MOTION = /@keyframes|animation\s*:|transition\s*:/
    expect(style).toContain('.clock')
    expect(MOTION.test(style)).toBe(false)
    // Positive control: a transition written into the styles is seen.
    expect(MOTION.test(`${style} .code { transition: opacity 1s; }`)).toBe(true)
  })
})
