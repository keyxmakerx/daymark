/*
 * THE TOKEN THAT GUARDS A JOURNAL, AND THE FOUR THINGS THAT MUST BE TRUE OF IT.
 *
 * Issue #126's finding was not that the token was weak. It was that nothing generated one at all:
 * the value came from a text box validated only for emptiness, so `"a"` was accepted, while
 * docs/COMPANION_SECURITY.md described a 256-bit CSPRNG. What follows pins the four properties that
 * make that row true — the size, the shape, the source, and the one the issue asked to be asserted
 * rather than guarded: that a duplicate cannot be made.
 *
 * Every absence here carries a planted positive control. A shape check that cannot reject a typed
 * token proves only that it is blind, and a typed token is precisely what this file exists to make
 * impossible.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  INBOX_TOKEN_BYTES,
  INBOX_TOKEN_CHARS,
  INBOX_TOKEN_SHAPE,
  mintInboxToken,
} from './inboxToken'
import { initAssignmentCrypto } from '../assignments/crypto'

const source = readFileSync(fileURLToPath(new URL('./inboxToken.ts', import.meta.url)), 'utf8')

beforeAll(async () => {
  await initAssignmentCrypto()
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) The size and the shape the security document claims.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) what a minted token is', () => {
  it('is 32 bytes, rendered as 43 characters of base64url', () => {
    // 32 bytes is ceil(32 * 4 / 3) = 43 base64 characters with the padding removed. Both numbers
    // are exported rather than written twice, so the arithmetic is stated once and checked here.
    expect(INBOX_TOKEN_BYTES).toBe(32)
    expect(INBOX_TOKEN_CHARS).toBe(43)
    expect(Math.ceil((INBOX_TOKEN_BYTES * 4) / 3)).toBe(INBOX_TOKEN_CHARS)
  })

  it('the shape check can tell a minted token from the values the old field accepted', () => {
    /*
     * The detector, proved in both directions before it is trusted below. The rejected list is the
     * real history of this field: a single character, a remembered phrase, the fixture every server
     * test used and called 256-bit, a padded encoding, and a truncated paste.
     */
    for (const bad of [
      'a',
      'clinic token 2024',
      'inbox-token-256-bit-example-xyz',
      'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==',
      'kQ7bXm2pR9tYw4vZ1nL6sH8jF3dG5aC0eB7uI9oP2x',
      'kQ7bXm2pR9tYw4vZ1nL6sH8jF3dG5aC0eB7uI9oP2xMM',
    ]) {
      expect(INBOX_TOKEN_SHAPE.test(bad), `shape check accepted "${bad}"`).toBe(false)
    }
    expect(INBOX_TOKEN_SHAPE.test('kQ7bXm2pR9tYw4vZ1nL6sH8jF3dG5aC0eB7uI9oP2xM')).toBe(true)
  })

  it('mints something that shape accepts', async () => {
    const token = await mintInboxToken()
    expect(token).toHaveLength(INBOX_TOKEN_CHARS)
    expect(INBOX_TOKEN_SHAPE.test(token), `minted "${token}"`).toBe(true)
    // No prefix, no separator, nothing a reader could mistake for structure. The old placeholder
    // promised an `inbox_` prefix that no token has ever carried.
    expect(token).not.toContain('_inbox')
    expect(token.startsWith('inbox')).toBe(false)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) Collisions are arithmetic, not a case to defend against.

   The issue asked for this to be ASSERTED rather than guarded: two clinicians sharing a token
   share a relationship reference and can read each other's material, and the old console hid it
   because the list index made two identical tokens render as two distinct rows. A duplicate check
   would now be unreachable code; what replaces it is this.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) two clinicians cannot be given one token', () => {
  it('mints a different token every time', async () => {
    const minted = await Promise.all(Array.from({ length: 512 }, () => mintInboxToken()))
    expect(new Set(minted).size).toBe(minted.length)
  })

  it('the sameness detector is not blind — the old typed path fails it', () => {
    /*
     * The control this file would be vacuous without. `new Set(...).size === length` passes for any
     * list of distinct strings, including one produced by a broken generator that happened to
     * differ. So the same check is shown FAILING on what the console used to do: one owner, one
     * remembered string, typed twice for two clinicians.
     */
    const typedTwice = ['clinic token 2024', 'clinic token 2024']
    expect(new Set(typedTwice).size).not.toBe(typedTwice.length)
  })

  it('no two minted tokens share even a long prefix', async () => {
    // The pending id used to carry the first eight characters of the token. Nothing depends on that
    // now, but a generator whose output varied only in its tail would still be a defect worth
    // seeing, and a per-run agreement on 8 characters would be a 2^-48 coincidence.
    const minted = await Promise.all(Array.from({ length: 256 }, () => mintInboxToken()))
    expect(new Set(minted.map((t) => t.slice(0, 8))).size).toBe(minted.length)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) Where the bytes come from.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(c) the source is a CSPRNG, and nothing else', () => {
  const WEAK: { name: string; pattern: RegExp; planted: string }[] = [
    { name: 'Math.random', pattern: /Math\s*\.\s*random\b/, planted: 'const x = Math.random()' },
    { name: 'the clock', pattern: /Date\s*\.\s*now\s*\(/, planted: 'const x = Date.now()' },
    { name: 'a counter', pattern: /\+\+\s*\w*[Ss]eq\b/, planted: 'const id = ++tokenSeq' },
    { name: 'a typed value', pattern: /\bprompt\s*\(|bind:value/, planted: '<input bind:value={t} />' },
  ]

  it('the detectors detect', () => {
    for (const { name, pattern, planted } of WEAK) {
      expect(pattern.test(planted), name).toBe(true)
    }
    expect(WEAK.some((w) => w.pattern.test('sodium.randombytes_buf(32)'))).toBe(false)
  })

  it('names none of them', () => {
    // Comments are stripped: this module's header discusses the typed path it replaced, by name.
    const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')
    expect(WEAK.filter((w) => w.pattern.test(code)).map((w) => w.name)).toEqual([])
  })

  it('draws from libsodium and asks for exactly the declared number of bytes', () => {
    expect(source).toMatch(/randombytes_buf\(INBOX_TOKEN_BYTES\)/)
  })
})
