/*
 * The sign-in form's reading-order check.
 *
 * The bug this pins: an empty submit used to say "Wrapped-key blob is not valid JSON" — the last
 * thing the unlock path happened to parse, and the most obscure field on the page — beneath the
 * button. The check now runs before anything is unwrapped or sent, top to bottom, and names the
 * first gap by the label the person can see.
 *
 * Two of the claims here are about agreement between files: that the order the module checks in
 * is the order LoginGate.svelte draws in, and that every message contains the label fieldHelp.ts
 * draws. Both are asserted by reading the other file, with the detector proved non-vacuous first,
 * for the reason components/invariants.tree.test.ts gives.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  FALLBACK_ORDER,
  FALLBACK_SECTION_NOTE,
  PRIMARY_ORDER,
  WRAPPED_KEY_UNREADABLE,
  checkOrder,
  firstProblem,
  type UnlockFieldId,
  type UnlockShape,
  type UnlockValues,
} from './loginGate'
import { FIELD_HELP } from '../onboarding/fieldHelp'

const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
const GATE_SRC = read('../components/therapist/LoginGate.svelte')

/** The markup a person actually gets: script, style and comments gone. */
const markupOf = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')

const GATE_MARKUP = markupOf(GATE_SRC)

const EMPTY: UnlockValues = {
  totpCode: '',
  readingPassphrase: '',
  serverUrl: '',
  inboxToken: '',
  relRef: '',
  credentialId: '',
  wrappedKeyJson: '',
}

/** Every field filled with something usable. */
const WHOLE: UnlockValues = {
  totpCode: '123456',
  readingPassphrase: 'correct horse battery staple',
  serverUrl: 'https://daymark.example.org',
  inboxToken: 'inbox_abc',
  relRef: 'rel_abc',
  credentialId: 'cred_abc',
  wrappedKeyJson: '{"v":1,"salt":"s","nonce":"n","ct":"c"}',
}

const MANUAL_FOLDED: UnlockShape = { manual: true, askInboxToken: false, fallbackOpen: false }
const MANUAL_OPEN: UnlockShape = { manual: true, askInboxToken: false, fallbackOpen: true }
const STORED: UnlockShape = { manual: false, askInboxToken: false, fallbackOpen: false }
const STORED_NO_TOKEN: UnlockShape = { manual: false, askInboxToken: true, fallbackOpen: false }

/** The `values` key each field is read from. */
const KEY: Record<UnlockFieldId, keyof UnlockValues> = {
  totpCode: 'totpCode',
  readingPassphrase: 'readingPassphrase',
  serverUrl: 'serverUrl',
  inboxToken: 'inboxToken',
  relRef: 'relRef',
  credentialId: 'credentialId',
  wrappedKey: 'wrappedKeyJson',
}

/** The fieldHelp entry each field is labelled by. */
const HELP: Record<UnlockFieldId, keyof typeof FIELD_HELP> = {
  totpCode: 'totpCode',
  readingPassphrase: 'readingPassphrase',
  serverUrl: 'serverUrl',
  inboxToken: 'inboxToken',
  relRef: 'relRef',
  credentialId: 'credentialId',
  wrappedKey: 'wrappedKey',
}

describe('an empty submit names the authenticator code first', () => {
  it('with no record in this browser (the fallback path, folded)', () => {
    const problem = firstProblem(EMPTY, MANUAL_FOLDED)
    expect(problem?.field).toBe('totpCode')
    expect(problem?.message).toBe('Enter your authenticator code.')
    // The first field is not behind the fold, so nothing is opened for it.
    expect(problem?.inFallback).toBe(false)
    expect(problem?.opensFallback).toBe(false)
  })

  it('with a record in this browser (the stored path)', () => {
    expect(firstProblem(EMPTY, STORED)?.message).toBe('Enter your authenticator code.')
    expect(firstProblem(EMPTY, STORED_NO_TOKEN)?.message).toBe('Enter your authenticator code.')
  })

  it('and never the wrapped key, which used to be the first thing said', () => {
    expect(firstProblem(EMPTY, MANUAL_FOLDED)?.field).not.toBe('wrappedKey')
    expect(firstProblem(EMPTY, MANUAL_FOLDED)?.message).not.toMatch(/json/i)
  })
})

describe('the check walks the form top to bottom and stops at the first gap', () => {
  it('on the fallback path, in the order the fields are drawn', () => {
    const values = { ...EMPTY }
    const seen: UnlockFieldId[] = []
    for (;;) {
      const problem = firstProblem(values, MANUAL_OPEN)
      if (!problem) break
      seen.push(problem.field)
      values[KEY[problem.field]] = WHOLE[KEY[problem.field]]
      if (seen.length > 20) throw new Error('the check never ran out of complaints')
    }
    expect(seen).toEqual([...PRIMARY_ORDER, ...FALLBACK_ORDER])
    expect(seen[0]).toBe('totpCode')
    expect(seen[1]).toBe('readingPassphrase')
  })

  it('on the stored path, the two secrets and then only what the record is missing', () => {
    expect(checkOrder(STORED)).toEqual(['totpCode', 'readingPassphrase'])
    expect(checkOrder(STORED_NO_TOKEN)).toEqual(['totpCode', 'readingPassphrase', 'inboxToken'])

    const secrets = { ...EMPTY, totpCode: WHOLE.totpCode, readingPassphrase: WHOLE.readingPassphrase }
    expect(firstProblem(secrets, STORED)).toBeNull()
    expect(firstProblem(secrets, STORED_NO_TOKEN)?.field).toBe('inboxToken')
    expect(firstProblem(secrets, STORED_NO_TOKEN)?.inFallback).toBe(false)
  })

  it('a whole form has nothing to say', () => {
    expect(firstProblem(WHOLE, MANUAL_FOLDED)).toBeNull()
    expect(firstProblem(WHOLE, STORED_NO_TOKEN)).toBeNull()
  })

  it('whitespace is not a value', () => {
    expect(firstProblem({ ...WHOLE, totpCode: '   ' }, STORED)?.field).toBe('totpCode')
    expect(firstProblem({ ...WHOLE, readingPassphrase: '\t' }, STORED)?.field).toBe('readingPassphrase')
  })
})

describe('a gap behind the fold says so, and asks for the fold to open', () => {
  const secretsOnly = { ...EMPTY, totpCode: WHOLE.totpCode, readingPassphrase: WHOLE.readingPassphrase }

  it('when the fallback is folded', () => {
    const problem = firstProblem(secretsOnly, MANUAL_FOLDED)
    expect(problem?.field).toBe('serverUrl')
    expect(problem?.inFallback).toBe(true)
    expect(problem?.opensFallback).toBe(true)
    expect(problem?.message).toBe(`Enter the server address. ${FALLBACK_SECTION_NOTE}`)
  })

  it('and plainly, without the aside, once it is open', () => {
    const problem = firstProblem(secretsOnly, MANUAL_OPEN)
    expect(problem?.field).toBe('serverUrl')
    expect(problem?.inFallback).toBe(true)
    expect(problem?.opensFallback).toBe(false)
    expect(problem?.message).toBe('Enter the server address.')
    expect(problem?.message).not.toContain(FALLBACK_SECTION_NOTE)
  })

  it('the two secrets are never described as behind the fold', () => {
    for (const shape of [MANUAL_FOLDED, MANUAL_OPEN, STORED, STORED_NO_TOKEN]) {
      for (const field of PRIMARY_ORDER) {
        const problem = firstProblem({ ...WHOLE, [KEY[field]]: '' }, shape)
        expect(problem?.field).toBe(field)
        expect(problem?.inFallback).toBe(false)
        expect(problem?.opensFallback).toBe(false)
      }
    }
  })
})

describe('a value that is present but unusable is named by its field, not its encoding', () => {
  it('the wrapped key', () => {
    for (const broken of ['{', 'not json', '[]', '"a string"', 'null', '42']) {
      const problem = firstProblem({ ...WHOLE, wrappedKeyJson: broken }, MANUAL_OPEN)
      expect(problem?.field, broken).toBe('wrappedKey')
      expect(problem?.message, broken).toBe(WRAPPED_KEY_UNREADABLE)
    }
    expect(WRAPPED_KEY_UNREADABLE).toContain(FIELD_HELP.wrappedKey.label)
    expect(WRAPPED_KEY_UNREADABLE).not.toMatch(/json|blob/i)
  })

  it('the server address', () => {
    for (const broken of ['daymark', 'daymark.example.org', 'mailto:someone@example.org']) {
      const problem = firstProblem({ ...WHOLE, serverUrl: broken }, MANUAL_OPEN)
      expect(problem?.field, broken).toBe('serverUrl')
      expect(problem?.message, broken).toContain('server address')
      expect(problem?.message, broken).toContain('https://')
    }
    expect(firstProblem({ ...WHOLE, serverUrl: 'http://localhost:8080' }, MANUAL_OPEN)).toBeNull()
  })

  it('and it is still reported behind the fold when the fold is closed', () => {
    const problem = firstProblem({ ...WHOLE, wrappedKeyJson: '{' }, MANUAL_FOLDED)
    expect(problem?.opensFallback).toBe(true)
    expect(problem?.message).toBe(`${WRAPPED_KEY_UNREADABLE} ${FALLBACK_SECTION_NOTE}`)
  })
})

describe('every message names the field by the label the form draws', () => {
  it('for an empty field', () => {
    for (const field of [...PRIMARY_ORDER, ...FALLBACK_ORDER]) {
      const problem = firstProblem({ ...WHOLE, [KEY[field]]: '' }, MANUAL_OPEN)
      expect(problem?.field).toBe(field)
      const label = FIELD_HELP[HELP[field]].label.toLowerCase()
      expect(problem!.message.toLowerCase(), `${field}: "${problem!.message}"`).toContain(label)
      expect(problem!.message.endsWith('.'), `${field} is not a sentence`).toBe(true)
    }
  })

  it('and the labels are the ones LoginGate renders (the detector sees real labels)', () => {
    for (const field of [...PRIMARY_ORDER, ...FALLBACK_ORDER]) {
      expect(GATE_MARKUP).toContain(`{FIELD_HELP.${HELP[field]}.label}`)
    }
  })
})

describe('the order the module checks in is the order the form is drawn in', () => {
  /** The ids of the inputs in a slice of markup, in document order. */
  const idsIn = (markup: string) =>
    [...markup.matchAll(/<(?:input|textarea)\b[^>]*\bid="f-([\w-]+)"/g)].map((m) => m[1]!)

  it('the extractor finds the form’s inputs', () => {
    expect(idsIn(GATE_MARKUP).length).toBeGreaterThan(5)
  })

  it('the two secrets are the first inputs, before the fallback disclosure', () => {
    const details = GATE_MARKUP.indexOf('<details')
    expect(details).toBeGreaterThan(-1)
    const before = idsIn(GATE_MARKUP.slice(0, details))
    expect(before.slice(0, 2)).toEqual([...PRIMARY_ORDER])
  })

  it('the fallback fields the module checks appear in the disclosure in the same order', () => {
    const start = GATE_MARKUP.indexOf('<details')
    const end = GATE_MARKUP.indexOf('</details>', start)
    expect(end).toBeGreaterThan(start)
    const drawn = idsIn(GATE_MARKUP.slice(start, end))
    expect(drawn.filter((id) => (FALLBACK_ORDER as readonly string[]).includes(id))).toEqual([
      ...FALLBACK_ORDER,
    ])
    /*
     * The control the line above needs, now that it is an identity rather than a filter.
     *
     * The disclosure used to hold two fields the module does not check — the owner's two public
     * keys, typed in by hand — and their presence was what proved the filter was doing work. Those
     * fields are gone (issue #101: the pairing seals the owner's keys back, so there is nothing to
     * type), and an assertion whose filter removes nothing would pass just as happily over a form
     * that had drifted. So one is planted, and the filter is shown to drop it.
     */
    const planted = idsIn(
      `${GATE_MARKUP.slice(start, end)}<input id="f-somethingElse" type="text" />`,
    )
    expect(planted).toContain('somethingElse')
    expect(planted.filter((id) => (FALLBACK_ORDER as readonly string[]).includes(id))).toEqual([
      ...FALLBACK_ORDER,
    ])
  })

  it('the disclosure asks for nothing the module cannot name a problem with', () => {
    // The other half of the same claim. A field on the form that firstProblem has no entry for is
    // a field that can be empty and silently stop a sign-in with a message about something else —
    // which is exactly what the two owner-key fields did before they were removed.
    const start = GATE_MARKUP.indexOf('<details')
    const end = GATE_MARKUP.indexOf('</details>', start)
    const drawn = idsIn(GATE_MARKUP.slice(start, end))
    expect(drawn).toEqual([...FALLBACK_ORDER])
  })
})

/*
 * THE CARD A CLINICIAN READS AFTER ACCEPTING DESCRIBES THIS FORM (#312).
 *
 * "Signing in from now on" is read once, at the end of the acceptance, and it is what a clinician
 * remembers about getting back in. It described a form that asked for the owner's public keys,
 * which nothing asks for any more, and sent clinicians to the other person for them. It now names
 * the fields this form draws, by the labels fieldHelp.ts gives them, in the order they are drawn —
 * so a field that is added, removed or relabelled here fails the card's test until the card says so.
 */
describe('the card after an acceptance describes this form (#312)', () => {
  const ACCEPT_SRC = read('../components/therapist/InviteAcceptance.svelte')
  const idsIn = (markup: string) =>
    [...markup.matchAll(/<(?:input|textarea)\b[^>]*\bid="f-([\w-]+)"/g)].map((m) => m[1]!)
  const NOW =
    'Your authenticator and your reading passphrase are what get you back in. In the browser that ' +
    'accepted this invitation, the sign-in screen asks for an authenticator code, your reading ' +
    'passphrase, and — once per tab — your inbox token. From a browser with no record of accepting, ' +
    'it asks for the connection by hand: the server address, the inbox token, the relationship id, ' +
    'the credential id and your wrapped reading key. All but the inbox token are in the text below; ' +
    "the inbox token comes from the person who invited you. The other person's public keys are " +
    'fetched for you and never typed.'

  /** The card's first paragraph as it reads. Takes the source so a planted copy runs through it. */
  const cardParagraph = (src: string): string => {
    const markup = markupOf(src)
    const card = markup.indexOf('<Card title="Signing in from now on">')
    if (card < 0) return ''
    const open = markup.indexOf('<p>', card)
    return markup.slice(open + 3, markup.indexOf('</p>', open)).replace(/\s+/g, ' ').trim()
  }

  it('says it whole', () => {
    expect(cardParagraph(ACCEPT_SRC)).toBe(NOW)
  })

  it('names every field the form draws, by its label, in the order it draws them', () => {
    const details = GATE_MARKUP.indexOf('<details')
    const stored = idsIn(GATE_MARKUP.slice(0, details))
    const fallback = idsIn(GATE_MARKUP.slice(details, GATE_MARKUP.indexOf('</details>', details)))
    // The subjects: what LoginGate draws on the stored path, and inside the fallback.
    expect(stored).toEqual([...PRIMARY_ORDER, 'inboxToken'])
    expect(fallback).toEqual([...FALLBACK_ORDER])

    /** Each id's label, found in `text` in order. The index where the search failed, or -1. */
    const missingIn = (text: string, ids: string[]): string | null => {
      let from = 0
      for (const id of ids) {
        const label = FIELD_HELP[HELP[id as UnlockFieldId]].label.toLowerCase()
        const at = text.indexOf(label, from)
        if (at < 0) return label
        from = at + label.length
      }
      return null
    }
    const text = cardParagraph(ACCEPT_SRC).toLowerCase()
    const split = text.indexOf('from a browser with no record')
    expect(split).toBeGreaterThan(0)
    expect(missingIn(text.slice(0, split), stored)).toBeNull()
    expect(missingIn(text.slice(split), fallback)).toBeNull()
    // Control: a card that leaves one field out, or names two out of order, is caught.
    expect(missingIn(text.slice(split).replace('the credential id and ', ''), fallback)).toBe('credential id')
    expect(missingIn('the relationship id, then the server address', ['serverUrl', 'relRef'])).toBe('relationship id')
  })

  it('says exactly which of them the text copy holds', () => {
    // "All but the inbox token" is a claim about `portalCard`, the text this card offers to keep.
    const held = /const portalCard[\s\S]*?JSON\.stringify\(\s*\{([^}]*)\}/.exec(ACCEPT_SRC)?.[1] ?? ''
    const keys = [...held.matchAll(/(\w+):/g)].map((m) => m[1])
    expect(keys).toEqual(FALLBACK_ORDER.filter((id) => id !== 'inboxToken'))
    expect(cardParagraph(ACCEPT_SRC)).toContain(
      'All but the inbox token are in the text below; the inbox token comes from the person who invited you.',
    )
  })

  it('no longer sends the clinician looking for keys nothing asks for', () => {
    const RETIRED = /public keys and your inbox token|the three values in the text|only they can give you those/i
    expect(cardParagraph(ACCEPT_SRC)).not.toMatch(RETIRED)
    // Control: the retired sentences planted back into the real card are seen.
    const planted = ACCEPT_SRC.replace(
      'are what get you back in.',
      'are what get you back in. The sign-in screen asks for a wrapped key, a relationship id and a ' +
        "credential id: they are the three values in the text below. It also asks for two of the other person's " +
        'public keys and your inbox token — only they can give you those.',
    )
    expect(planted).not.toBe(ACCEPT_SRC)
    expect(cardParagraph(planted)).toMatch(RETIRED)
  })
})
