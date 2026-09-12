/*
 * WHAT IS PUBLISHED, COMPARED AGAINST WHAT THIS CONSOLE HOLDS.
 *
 * The server's owner_keys table is insert-only by primary key, so the POST that publishes a key
 * answers 409 for any second attempt — and that one status code covers two situations a person
 * needs told apart: the same key is already there (nothing to do), or a DIFFERENT key is already
 * there and this clinician can never verify this owner again. Everything below is about not
 * blurring those two.
 */
import { describe, it, expect } from 'vitest'
import {
  classifyPublishedKey,
  CANNOT_BE_REPLACED,
  DIFFERENT_KEY_PUBLISHED,
  ALREADY_YOURS,
  PUBLISH_LEDE,
  PUBLISH_ACTION,
  COULD_NOT_CHECK,
} from './publishKey'

const mine = { signPubB64: 'SIGN-MINE', boxPubB64: 'BOX-MINE' }

describe('(a) what the server holds, relative to what this console derived', () => {
  it('nothing published is an ordinary state, not a fault', () => {
    expect(classifyPublishedKey(null, mine)).toBe('absent')
  })

  it('both halves matching is mine', () => {
    expect(classifyPublishedKey({ ...mine }, mine)).toBe('mine')
  })

  it('one half matching is NOT mine', () => {
    // The assertion this function exists for. A record carrying my signing key and somebody else's
    // encryption key is not a near-miss: calling it "mine" would tell an owner their connection is
    // fine while half of what the clinician pins belongs to someone else.
    expect(classifyPublishedKey({ signPubB64: 'SIGN-MINE', boxPubB64: 'BOX-OTHER' }, mine)).toBe('different')
    expect(classifyPublishedKey({ signPubB64: 'SIGN-OTHER', boxPubB64: 'BOX-MINE' }, mine)).toBe('different')
  })

  it('neither half matching is different', () => {
    expect(classifyPublishedKey({ signPubB64: 'SIGN-OTHER', boxPubB64: 'BOX-OTHER' }, mine)).toBe('different')
  })

  it('compares exactly, with no normalisation', () => {
    // Keys are re-encoded server-side from validated bytes precisely so there is one spelling. If
    // this function started trimming or case-folding, it would be papering over a disagreement that
    // means something.
    expect(classifyPublishedKey({ signPubB64: ' SIGN-MINE', boxPubB64: 'BOX-MINE' }, mine)).toBe('different')
    expect(classifyPublishedKey({ signPubB64: 'sign-mine', boxPubB64: 'BOX-MINE' }, mine)).toBe('different')
  })
})

describe('(b) the sentence before an act that cannot be undone', () => {
  it('names the consequence and what it costs', () => {
    expect(CANNOT_BE_REPLACED).toContain('cannot be replaced')
    expect(CANNOT_BE_REPLACED).toContain('passphrase')
    expect(CANNOT_BE_REPLACED).toContain('recovery code')
    expect(CANNOT_BE_REPLACED).toContain('invite them again')
  })

  it('does not claim the journal is gone, because that is a different loss', () => {
    // Conflating the two would make this screen carry a warning that belongs elsewhere, and would
    // frighten someone out of an act they need to perform.
    expect(CANNOT_BE_REPLACED).not.toMatch(/journal|entries|your data/i)
  })

  it('tells someone pinned to a stranger key what the way out actually is', () => {
    // Nothing can repair it, so the copy must not imply a button would.
    expect(DIFFERENT_KEY_PUBLISHED).toContain('cannot be replaced')
    expect(DIFFERENT_KEY_PUBLISHED).toContain('new inbox token')
    expect(DIFFERENT_KEY_PUBLISHED).not.toMatch(/try again|retry|re-?send/i)
  })

  it('says plainly when it does not know, rather than guessing', () => {
    expect(COULD_NOT_CHECK).toContain('cannot say')
    expect(COULD_NOT_CHECK).toContain('Nothing has been sent')
  })

  it('none of it congratulates, and none of it blames', () => {
    const FORBIDDEN = [
      { name: 'a success register', pattern: /\b(success|succeeded|great|congratulat|all set|done!)/i, planted: 'Success! Your key is published.' },
      { name: 'a tick', pattern: /✓|✔/, planted: '✓ Published' },
      { name: 'blame', pattern: /\byou (?:should have|failed|forgot)\b/i, planted: 'You should have saved it.' },
    ]
    const all = [CANNOT_BE_REPLACED, DIFFERENT_KEY_PUBLISHED, ALREADY_YOURS, PUBLISH_LEDE, PUBLISH_ACTION, COULD_NOT_CHECK]
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
      for (const text of all) expect(pattern.test(text), `${name}: ${text.slice(0, 40)}`).toBe(false)
    }
  })
})
