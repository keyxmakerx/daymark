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

  it('"Disconnect" and "Keep it paired" only open and close the confirm; only "Disconnect this phone" asks', () => {
    expect(handlerOf('DISCONNECT').exec(SOURCE)?.slice(1, 3)).toEqual(['set', 'askDisconnect(phones, row.keyId))'])
    expect(handlerOf('row.confirm.keep').exec(SOURCE)?.slice(1, 3)).toEqual(['set', 'keepPaired(phones))'])
    expect(handlerOf('row.confirm.disconnect').exec(SOURCE)?.slice(1, 3)).toEqual(['act', '(p, s) => disconnect(p, s, row.keyId))'])
  })

  it('the reading sees a handler that asks (positive control)', () => {
    const asks = SOURCE.replace('set(wordsDontMatch(phones))', 'act(wordsDontMatch)')
    expect(asks).not.toBe(SOURCE)
    expect(handlerOf('area.mismatch').exec(asks)?.[1]).toBe('act')
  })
})

describe('colour', () => {
  /** Each rule of the style block, comments removed, as [selector, body]. */
  const rules = (src = SOURCE) =>
    [...(/<style>([\s\S]*?)<\/style>/.exec(src)?.[1] ?? '').replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(
      (m) => [m[1]!.trim(), m[2]!] as const,
    )
  const clayOn = (src?: string) => rules(src).filter(([, body]) => /--clay/.test(body)).map(([selector]) => selector)

  it('clay is spent on the Disconnect confirm and nowhere else', () => {
    const clay = clayOn()
    expect(clay.length).toBeGreaterThan(0)
    expect(clay.every((s) => s.startsWith('.confirm'))).toBe(true)
    // Positive control: a refusal painted clay is seen.
    const planted = SOURCE.replace('.ended { color: var(--ink-text); }', '.ended { color: var(--clay); }')
    expect(planted).not.toBe(SOURCE)
    expect(clayOn(planted)).toContain('.ended')
  })

  it('indigo is the structure: the code, the words, and the confirm button', () => {
    const indigo = rules().filter(([, body]) => /--indigo/.test(body)).map(([selector]) => selector)
    expect(indigo.sort()).toEqual(['.code', '.words', 'button.indigo'])
  })
})

describe('the QR code', () => {
  it("draws the view's qrText and nothing else, once, hidden from assistive technology", () => {
    const script = scriptOf(SOURCE)
    expect(script).toMatch(/const qrText = \$derived\(view\.area\?\.kind === 'code' \? view\.area\.qrText : null\)/)
    expect(script.match(/qrDrawing\(/g)).toHaveLength(1)
    expect(script).toMatch(/qrDrawing\(qrText\)/)
    const svg = /<svg class="qr"[^>]*>/.exec(markupOf(SOURCE))?.[0] ?? ''
    expect(svg).toContain('aria-hidden="true"')
    // Positive control: the count sees a second drawing of anything else.
    expect(`${script} qrDrawing(view.lede)`.match(/qrDrawing\(/g)).toHaveLength(2)
  })

  it('is day ink on day paper in every theme', () => {
    const css = readFileSync(new URL('../../../app.css', import.meta.url), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
    const darkBlocks = (text: string) =>
      [...text.matchAll(/:root:not\(\[data-theme="light"\]\)\s*\{[^}]*\}|:root\[data-theme="dark"\]\s*\{[^}]*\}/g)].map((m) => m[0])
    // Both dark palettes, and the colour-scheme line beside them.
    expect(darkBlocks(css).filter((b) => b.includes('--paper-bg'))).toHaveLength(2)
    expect(css).toMatch(/--scan-ink:\s*var\(--c-ink\);/)
    expect(css).toMatch(/--scan-paper:\s*var\(--c-sheet\);/)
    for (const block of darkBlocks(css)) expect(block).not.toMatch(/--scan-/)
    // Positive control: a themed value planted in a dark block is seen.
    const planted = css.replace(':root[data-theme="dark"] {', ':root[data-theme="dark"] {\n  --scan-ink: var(--ink-text);')
    expect(planted).not.toBe(css)
    expect(darkBlocks(planted).some((b) => /--scan-/.test(b))).toBe(true)
    // And the tile paints with them and nothing themed.
    const style = (/<style>([\s\S]*?)<\/style>/.exec(SOURCE)?.[1] ?? '').replace(/\/\*[\s\S]*?\*\//g, '')
    expect(style).toMatch(/\.qr-ink \{ fill: var\(--scan-ink\); \}/)
    expect(style).toMatch(/\.qr-paper \{ fill: var\(--scan-paper\); \}/)
  })
})

describe('time on screen', () => {
  it('every clock sits in a silent region, and the one polite region carries only the announcement', () => {
    const markup = markupOf(SOURCE)
    // `<p` and a space or `>`, so an SVG `<path>` is not a paragraph.
    const clocks = [...markup.matchAll(/<p(?=[\s>])[^>]*>(?:(?!<\/p>)[\s\S])*?<span class="clock">/g)].map((m) => m[0])
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
