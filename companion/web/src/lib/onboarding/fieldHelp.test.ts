/*
 * The field help, and the one property that will actually rot: a field on a form with no
 * explanation behind it.
 *
 * Everything else here is copy discipline. This one is a coverage check between two files that
 * are edited months apart — someone adds an input to LoginGate and there is nothing to notice that
 * it arrived without help text, because the screen still renders and every test still passes. So
 * the form's own markup is read and compared against this module.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { FIELD_HELP, FIELD_IDS, helpPanelId, type FieldId } from './fieldHelp'

const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
const LOGIN_GATE = read('../components/therapist/LoginGate.svelte')
const FIELD_HELP_COMPONENT = read('../components/ui/FieldHelp.svelte')

describe('every field on the sign-in form can be explained', () => {
  it('every FieldHelp in LoginGate names an id this module defines', () => {
    const used = [...LOGIN_GATE.matchAll(/<FieldHelp field="(\w+)"/g)].map((m) => m[1]!)
    // The detector: the form really does use this component, so an empty match set below would be
    // a parsing failure rather than a clean bill of health.
    expect(used.length).toBeGreaterThan(5)
    for (const id of used) {
      expect(FIELD_IDS, `LoginGate asks for help on "${id}", which fieldHelp.ts does not define`)
        .toContain(id)
    }
  })

  it('every input and textarea on the form has a help trigger', () => {
    /*
     * The rot this file exists for. Counting is the check: an input added without help leaves the
     * counts unequal, and nothing else in the suite would notice.
     */
    const inputs = [...LOGIN_GATE.matchAll(/<(?:input|textarea)\b[^>]*\bid="f-([\w-]+)"/g)].map(
      (m) => m[1]!,
    )
    const helped = [...LOGIN_GATE.matchAll(/<FieldHelp field="(\w+)"/g)].map((m) => m[1]!)

    expect(inputs.length).toBeGreaterThan(5) // the extractor found real inputs
    expect(new Set(inputs)).toEqual(new Set(helped))
  })

  it('every input carries an example placeholder, and it comes from this module', () => {
    const inputs = [...LOGIN_GATE.matchAll(/<(?:input|textarea)\b[^>]*>/g)].map((m) => m[0])
    const fieldInputs = inputs.filter((t) => t.includes('id="f-'))
    expect(fieldInputs.length).toBeGreaterThan(5)
    for (const tag of fieldInputs) {
      expect(tag, 'an input on this form has no placeholder').toContain('placeholder=')
      // Bound to FIELD_HELP rather than typed inline, so the example and the explanation cannot
      // drift into describing different things.
      expect(tag, 'a placeholder was typed into the markup instead of coming from fieldHelp.ts')
        .toMatch(/placeholder=\{FIELD_HELP\.\w+\.placeholder\}/)
    }
  })
})

describe('the copy', () => {
  it('says where the value comes from, not just what it is', () => {
    for (const id of FIELD_IDS) {
      const h = FIELD_HELP[id]
      expect(h.what.trim().length, `${id}.what`).toBeGreaterThan(20)
      expect(h.where.trim().length, `${id}.where`).toBeGreaterThan(20)
      expect(h.what.trim().endsWith('.'), `${id}.what is not a sentence`).toBe(true)
      expect(h.where.trim().endsWith('.'), `${id}.where is not a sentence`).toBe(true)
    }
  })

  it('placeholders are examples, never instructions', () => {
    /*
     * A placeholder reading "Enter your token" is a label that vanishes exactly when it is needed.
     * The detector is checked against planted instruction-shaped text first, so the absence below
     * is a fact about the copy rather than a regex that matches nothing.
     */
    const INSTRUCTION = /^\s*(enter|type|paste|input|provide|fill|choose|select)\b/i
    for (const planted of ['Enter your token', 'paste the key here', 'Type your passphrase']) {
      expect(INSTRUCTION.test(planted), `detector missed "${planted}"`).toBe(true)
    }
    expect(INSTRUCTION.test('https://daymark.example.com')).toBe(false)

    for (const id of FIELD_IDS) {
      expect(INSTRUCTION.test(FIELD_HELP[id].placeholder), `${id} placeholder is an instruction`)
        .toBe(false)
    }
  })

  it('no placeholder could be mistaken for a working value', () => {
    /*
     * A plausible-looking example invites someone to submit it, then debug the rejection.
     *
     * "Contains a space" is the strongest signal available and is not a loophole: none of these
     * fields accepts a value with a space in it — not a URL, not base64url, not a token, not a
     * six-digit code — so anything spaced is self-evidently prose about the value rather than the
     * value. The named patterns cover the unspaced examples.
     */
    const obviouslyFake = (p: string) =>
      p.includes(' ') ||
      p.includes('example.com') ||
      p.includes('XXXX') ||
      p.includes('…') ||
      p === '000000'

    // The detector, proved in both directions: it rejects things that DO look submittable, so a
    // pass below is a fact about the copy rather than a predicate that returns true for anything.
    expect(obviouslyFake('rel_9c1f0b7a5e2d4c3b')).toBe(false)
    expect(obviouslyFake('https://daymark.mydomain.net')).toBe(false)
    expect(obviouslyFake('kQ7bXm2pR9tYw4vZ1nL6sH8jF3dG5aC0eB7uI9oP2xM')).toBe(false)

    for (const id of FIELD_IDS) {
      const p = FIELD_HELP[id].placeholder
      expect(obviouslyFake(p), `${id}: "${p}" could be mistaken for a real value`).toBe(true)
    }
  })

  it('marks the secrets, and the form renders those as passwords', () => {
    // `secret` is data rather than a per-call-site decision precisely so this can be checked.
    expect(FIELD_HELP.inboxToken.secret).toBe(true)
    expect(FIELD_HELP.readingPassphrase.secret).toBe(true)
    expect(FIELD_HELP.serverUrl.secret).toBe(false)

    for (const id of FIELD_IDS) {
      if (!FIELD_HELP[id].secret) continue
      const tag = LOGIN_GATE.match(new RegExp(`<input\\b[^>]*\\bid="f-${id}"[^>]*>`))?.[0]
      if (!tag) continue // not on this form; other screens are checked where they are added
      expect(tag, `${id} is marked secret but is not a password input`).toContain('type="password"')
    }
  })

  it('does not lead with jargon a reader would have to already know', () => {
    // "relRef", "base64url", "TOTP" and "blob" are all correct and all useless as an opening.
    const JARGON = /\b(relref|base64url|totp|blob|b64|pubkey)\b/i
    expect(JARGON.test('Relationship id (relRef)')).toBe(true) // the detector fires
    for (const id of FIELD_IDS) {
      expect(JARGON.test(FIELD_HELP[id].label), `${id} label leads with jargon`).toBe(false)
    }
  })
})

describe('the help panel is announced correctly', () => {
  it('the trigger points at the panel it opens', () => {
    // An aria-controls pointing at nothing is worse than none: a screen reader announces a control
    // that promises to reveal something and then does not.
    expect(FIELD_HELP_COMPONENT).toContain('aria-controls={panel}')
    expect(FIELD_HELP_COMPONENT).toContain('id={panel}')
    expect(FIELD_HELP_COMPONENT).toContain('aria-expanded={open}')
  })

  it('the panel id is derived, so the two spellings cannot drift', () => {
    expect(helpPanelId('relRef' as FieldId)).toBe('fieldhelp-relRef')
    expect(new Set(FIELD_IDS.map(helpPanelId)).size).toBe(FIELD_IDS.length)
  })

  it('is a button rather than a hover target', () => {
    // Hover does not exist on touch, is unreachable by keyboard, and title= is announced
    // inconsistently. A clinician on a tablet is the likeliest reader of this screen.
    expect(FIELD_HELP_COMPONENT).toContain('<button')
    expect(FIELD_HELP_COMPONENT).not.toMatch(/\btitle="/)
  })
})
