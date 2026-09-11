import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { OWNER_COPY } from '../../pairing/copy'

/*
 * Structural assertions over the pairing panel's source, in the style of admin/AdminConsole.test.ts:
 * these tests run in node with no DOM, so what they can check is the composition — which is exactly
 * where the properties here live.
 *
 * THE ONE THAT MATTERS: the code is for a person to read aloud, so it must appear somewhere a
 * machine will not carry it onward. Not in a form control (a browser saves those, a password
 * manager offers them, an autofill moves them), not in a link, and nowhere the email path can
 * reach — because emailing the code beside the link would collapse the two-channel design back to
 * "whoever read the email is in", which is the whole thing the pairing code exists to prevent.
 *
 * Every absence assertion is paired with a control proving the same search finds the thing when it
 * IS present, per this repo's convention: a grep that cannot see a planted example proves only that
 * it is blind.
 */

const SOURCE = readFileSync(new URL('./PairingPanel.svelte', import.meta.url), 'utf8')

/** The markup, with the instance script, the style block and comments removed. */
function markupOf(source: string): string {
  return source
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
}

const MARKUP = markupOf(SOURCE)

describe('the code is rendered for a person, not for a machine', () => {
  it('lives in an output with a live region, not an input', () => {
    expect(MARKUP).toMatch(/<output[^>]*class="code"[^>]*>\{ceremony\.code\.display\}<\/output>/)
    expect(MARKUP).toMatch(/<output[^>]*aria-live=/)
    expect(MARKUP).toMatch(/<output[^>]*aria-labelledby=/)
  })

  it('is never the value of a form control and never inside a link', () => {
    const controls = MARKUP.match(/<(input|textarea|select)[^>]*>/g) ?? []
    expect(controls.length, 'there should be form controls to check against').toBeGreaterThan(0)
    for (const tag of controls) {
      expect(tag.includes('code'), `a form control carries the code: ${tag}`).toBe(false)
    }
    const anchors = MARKUP.match(/<a\b[^>]*>[\s\S]*?<\/a>/g) ?? []
    for (const a of anchors) expect(a.includes('ceremony.code')).toBe(false)
    // Control: the detector sees a planted one.
    expect(markupOf('<input value={ceremony.code.display} />').includes('code')).toBe(true)
  })

  it('the email path sends the link and has no field a code could travel in', () => {
    const emailBlock = MARKUP.slice(MARKUP.indexOf('class="email"'), MARKUP.indexOf('</div>', MARKUP.indexOf('class="email"')))
    expect(emailBlock).toContain('type="email"')
    // Its copy names the rule ("the code is never emailed"); what must not exist is a CONTROL that
    // could carry one, so the check is over the fields rather than over the prose.
    expect(emailBlock).toMatch(/the code is never emailed/i)
    for (const tag of emailBlock.match(/<(input|textarea|select)[^>]*>/g) ?? []) {
      expect(tag.includes('code'), `the email block has a field for a code: ${tag}`).toBe(false)
    }
    // And the handler that sends it mints with an address, never with a code. Comments are
    // stripped first: the handler's own comment says the word, which is the point of it.
    const handler = SOURCE.slice(SOURCE.indexOf('async function sendEmail'), SOURCE.indexOf('const offerFingerprints'))
      .replace(/\/\/[^\n]*/g, '')
    expect(handler).toContain('mintInvite')
    expect(handler).not.toMatch(/\bcode\b/)
    // Control: the stripper leaves real code alone.
    expect('const x = 1 // code'.replace(/\/\/[^\n]*/g, '')).toContain('const x = 1')
  })
})

describe('what the screen says', () => {
  it('states the two-channel rule at the point of the click, not in a footnote', () => {
    expect(MARKUP).toContain('OWNER_COPY.twoChannels')
    expect(OWNER_COPY.twoChannels).toMatch(/some other way/i)
    expect(OWNER_COPY.twoChannels).toMatch(/only the link/i)
  })

  it('carries the lower-assurance line rather than claiming the phone will fix it', () => {
    expect(MARKUP).toContain('OWNER_COPY.browserCaveat')
    expect(OWNER_COPY.browserCaveat).not.toMatch(/phone|app|secure/i)
  })

  it('renders a mismatch as a question with two human choices, never as an attack', () => {
    expect(MARKUP).toContain('OWNER_COPY.mismatchTitle')
    const words = `${OWNER_COPY.mismatchTitle} ${OWNER_COPY.mismatchBody}`
    expect(words).toMatch(/mistyping/i)
    expect(words).toMatch(/stop this invitation/i)
    expect(words).not.toMatch(/attack|intrud|breach|suspicious|threat|danger/i)
    // Control: the pattern fires on the sentence it is meant to catch.
    expect('An attack was detected').toMatch(/attack|intrud|breach|suspicious|threat|danger/i)
  })

  it('says what ending an invitation does and what it does not', () => {
    expect(OWNER_COPY.stopBody).toMatch(/nothing already shared/i)
    expect(OWNER_COPY.stopBody).toMatch(/not told/i)
    expect(OWNER_COPY.stoppedBody).toMatch(/nobody has been told/i)
  })

  it('nothing in the copy reads as a score, a streak, or a congratulation', () => {
    for (const [key, value] of Object.entries(OWNER_COPY)) {
      if (typeof value !== 'string') continue
      expect(value, key).not.toMatch(/\b(success|verified|secure|congratulat|well done|streak|score|perfect)\b/i)
      expect(value, key).not.toContain('!')
    }
    expect('Verified! Well done.').toMatch(/\b(success|verified|secure|congratulat|well done|streak|score|perfect)\b/i)
  })

  it('counts attempts as a number, and wrong links as a count with no verdict', () => {
    expect(OWNER_COPY.attemptsLeft(6)).toBe('6 of 8 tries left on this invitation.')
    expect(OWNER_COPY.wrongLinkTried(1)).toMatch(/^One wrong invitation link/)
    expect(OWNER_COPY.wrongLinkTried(3)).toMatch(/^3 wrong invitation links/)
    expect(OWNER_COPY.wrongLinkAdvice).toMatch(/if that was not your therapist/i)
    expect(OWNER_COPY.wrongLinkAdvice).not.toMatch(/attack|must|immediately/i)
  })
})

describe('the buttons that spend an attempt are the ones a person clicks', () => {
  it('a new code is behind a button, and nothing in the markup opens a run on a timer', () => {
    expect(MARKUP).toContain('OWNER_COPY.newCodeLabel')
    expect(MARKUP).toMatch(/onclick=\{\(\) => step\(\(\) => newCode\(ports, ceremony\)\)\}/)
    expect(SOURCE).not.toMatch(/setInterval|requestAnimationFrame/)
    // The one timer is the copied-to-clipboard flag, which spends nothing.
    const timers = SOURCE.match(/setTimeout\([^)]*\)/g) ?? []
    expect(timers).toHaveLength(1)
    expect(SOURCE.slice(SOURCE.indexOf('setTimeout') - 200, SOURCE.indexOf('setTimeout'))).toContain('copied')
  })

  it('approve is a button, and the panel asks the module rather than approving inline', () => {
    expect(MARKUP).toMatch(/onclick=\{approveNow\}/)
    expect(SOURCE).toContain('approve(ports, before)')
    // The panel never calls the approve route itself; the module sequences pin-then-forward.
    const script = SOURCE.slice(0, SOURCE.indexOf('</script>'))
    const approveCalls = script.match(/ownerApprovePairing\(/g) ?? []
    expect(approveCalls, 'only the port wires the route').toHaveLength(1)
  })
})
