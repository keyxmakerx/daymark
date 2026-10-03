/*
 * OPENING THE CONSOLE: the order, and what is left behind.
 *
 * The happy path here is one line and is the least interesting thing on the page. What these tests
 * are for is the parts a screen cannot be trusted to get right on its own: that the master is wiped
 * AFTER the identity exists rather than before or never, that it is never handed back, that a
 * mistyped recovery code is refused without spending three seconds on Argon2id, and that every
 * failure says one thing and does not guess at a cause it cannot know.
 */
import { describe, it, expect, beforeAll, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import _sodium from 'libsodium-wrappers-sumo'

/*
 * The wipe is observable only from inside dataKey.ts, so zeroizeDataKey is wrapped — not replaced —
 * and every array it is handed is kept. The real one still runs, so the assertions below are about
 * what actually happened rather than about a stand-in.
 */
const spy = vi.hoisted(() => ({ wiped: [] as Uint8Array[] }))
vi.mock('../recovery/dataKey', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../recovery/dataKey')>()
  return {
    ...actual,
    zeroizeDataKey: (key: Uint8Array | null) => {
      if (key) spy.wiped.push(key)
      actual.zeroizeDataKey(key)
    },
  }
})

import { unlockFromBlob, UNLOCK_FAULT_TEXT } from './unlock'
import { ownerIdentityFromMaster } from './identity'
import { createRecoverableDataKey } from '../recovery/dataKey'
import { initCrypto } from '../sync/crypto'
import type { RecoverableDataKey } from '../recovery/dataKey'
import type { RecoveryCode } from '../recovery/recoveryCode'

const FAST = { alg: 'argon2id' as const, memMiB: 256, ops: 3 }
const PASSPHRASE = 'the passphrase that opens this key'
const b64 = (u: Uint8Array) => Buffer.from(u).toString('base64')

let blob: RecoverableDataKey
let code: RecoveryCode
let expectedSignPub: string

beforeAll(async () => {
  await initCrypto()
  await _sodium.ready
  const made = await createRecoverableDataKey(PASSPHRASE, FAST)
  blob = made.blob
  code = made.recoveryCode
  expectedSignPub = b64(ownerIdentityFromMaster(made.dataKey).ed25519.publicKey)
}, 60_000)

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) Both secrets reach the same identity.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) the server’s locked key plus either secret opens the console', () => {
  it('opens with the passphrase', async () => {
    const out = await unlockFromBlob(blob, PASSPHRASE, 'passphrase')
    expect(out.ok).toBe(true)
    if (out.ok) expect(b64(out.identity.ed25519.publicKey)).toBe(expectedSignPub)
  }, 30_000)

  it('opens with the recovery code, to the same identity', async () => {
    // The path someone uses on a machine that has never seen their passphrase. A different identity
    // here would show their clinician a stranger at exactly that moment.
    const out = await unlockFromBlob(blob, code.canonical, 'recovery')
    expect(out.ok).toBe(true)
    if (out.ok) expect(b64(out.identity.ed25519.publicKey)).toBe(expectedSignPub)
  }, 30_000)

  it('accepts a recovery code the way a person types it, spacing and case included', async () => {
    const typed = ` ${code.canonical.toLowerCase().replace(/-/g, ' ')} `
    const out = await unlockFromBlob(blob, typed, 'recovery')
    expect(out.ok).toBe(true)
  }, 30_000)
})
/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) The master is wiped, after it has been used and never before.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) what is left behind', () => {
  it('wipes the master and still returns a usable identity', async () => {
    spy.wiped.length = 0
    const out = await unlockFromBlob(blob, PASSPHRASE, 'passphrase')

    expect(spy.wiped.length).toBe(1)
    expect(spy.wiped[0].every((byte) => byte === 0)).toBe(true)
    // Both halves of the order matter. Wiped, AND the identity is right — a wipe that ran first
    // would leave the all-zero master deriving a perfectly valid identity belonging to nobody,
    // which is why (f) in identity.test.ts pins a vector that is not the zero master.
    expect(out.ok).toBe(true)
    if (out.ok) expect(b64(out.identity.ed25519.publicKey)).toBe(expectedSignPub)
  }, 30_000)

  it('hands back no master by any route', () => {
    // Structural, because the property is about the module's shape rather than one call: there is
    // no field to read it from, so no component can hold it by accident.
    const source = readFileSync(fileURLToPath(new URL('./unlock.ts', import.meta.url)), 'utf8')
    const code_ = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')
    expect(code_).not.toMatch(/master\s*[,}]/)
    expect(code_).toContain('zeroizeDataKey(master)')
    // And the wipe is in a finally, so it also runs when deriving throws — the one path the
    // behavioural test above cannot reach without mocking the derivation itself.
    expect(code_).toMatch(/}\s*finally\s*\{[^}]*zeroizeDataKey\(master\)/)
  })

  it('the finally detector detects', () => {
    // The assertion above is an absence-shaped claim about a shape; shown catching a planted
    // example, and shown NOT firing on the same call outside a finally.
    const planted = 'try { x() } finally {\n    zeroizeDataKey(master)\n  }'
    expect(planted).toMatch(/}\s*finally\s*\{[^}]*zeroizeDataKey\(master\)/)
    expect('zeroizeDataKey(master)\n  return out').not.toMatch(/}\s*finally\s*\{[^}]*zeroizeDataKey\(master\)/)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) One diagnosis per failure, and none of them guesses.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(c) what it says when it does not open', () => {
  it('refuses an empty secret before any derivation', async () => {
    expect(await unlockFromBlob(blob, '  ', 'passphrase')).toEqual({ ok: false, fault: 'noSecret' })
    expect(await unlockFromBlob(blob, '', 'recovery')).toEqual({ ok: false, fault: 'noSecret' })
  })

  it('refuses a mistyped recovery code before spending any Argon2id on it', async () => {
    // Change one symbol so the check symbol no longer agrees. The replacement is derived from the
    // symbol it replaces, so the change is never a no-op (CLAUDE.md §5). This must come back
    // positioned and instantly; reporting it as "did not open" would cost three seconds to say
    // something less useful, and would send someone to look for the wrong problem.
    const symbols = code.canonical.split('')
    symbols[2] = symbols[2] === 'K' ? 'M' : 'K'
    const mistyped = symbols.join('')
    expect(mistyped).not.toBe(code.canonical)
    const started = performance.now()
    const out = await unlockFromBlob(blob, mistyped, 'recovery')
    const elapsed = performance.now() - started

    expect(out).toMatchObject({ ok: false, fault: 'checksum' })
    // Generous by three orders of magnitude against the Argon2id floor (256 MiB, 3 passes), which
    // takes seconds. This asserts "no derivation ran", not a performance budget.
    expect(elapsed).toBeLessThan(1_000)
  })

  it('names a consequence when the secret is simply wrong, and does not guess which input was', async () => {
    const out = await unlockFromBlob(blob, 'not the passphrase', 'passphrase')
    expect(out).toEqual({ ok: false, fault: 'didNotOpen' })
    // The wrong passphrase and a key edited on the server are one outcome here, not two: the
    // sentence names the key it did not open and points at the one input, and guesses at neither.
    expect(UNLOCK_FAULT_TEXT.didNotOpen).toContain('did not open the key this server holds')
    expect(UNLOCK_FAULT_TEXT.didNotOpen).toContain('what you typed')
    expect(UNLOCK_FAULT_TEXT.didNotOpen).not.toMatch(/wrong|incorrect/i)
  }, 30_000)

  it('refuses a key whose settings are outside the accepted range in its own words, never as a typing problem (#418)', async () => {
    const SETTINGS_NOT_ACCEPTED =
      'The key this server holds asks for settings this console does not accept, so it was not opened. Nothing has changed.'
    expect(UNLOCK_FAULT_TEXT.settingsNotAccepted).toBe(SETTINGS_NOT_ACCEPTED)
    // The sentence must not point at the typing. The detector is shown seeing didNotOpen do exactly that.
    const POINTS_AT_TYPING = /what you typed|try again/i
    expect(POINTS_AT_TYPING.test(UNLOCK_FAULT_TEXT.didNotOpen)).toBe(true)
    expect(POINTS_AT_TYPING.test(SETTINGS_NOT_ACCEPTED)).toBe(false)

    // One lock moved outside the range, on each side of it, in memory and in passes. The secrets
    // offered are the RIGHT ones — the key as made opens with both, section (a) — so the settings
    // are the only thing refused here, and a sentence about the typing would be false.
    const outside: [what: string, kind: 'passphrase' | 'recovery', kdf: Partial<typeof FAST>][] = [
      ['the passphrase lock at 513 MiB', 'passphrase', { memMiB: 513 }],
      ['the passphrase lock at 128 MiB', 'passphrase', { memMiB: 128 }],
      ['the recovery code lock at 9 passes', 'recovery', { ops: 9 }],
      ['the recovery code lock at 2 passes', 'recovery', { ops: 2 }],
    ]
    for (const [what, kind, kdf] of outside) {
      const moved: RecoverableDataKey = {
        v: 1,
        slots: blob.slots.map((s) => (s.kind === kind ? { ...s, kdf: { ...s.kdf, ...kdf } } : s)),
      }
      // The move is real: that lock's settings are no longer the ones it was made with.
      expect(moved.slots.find((s) => s.kind === kind)!.kdf, what).not.toEqual(blob.slots.find((s) => s.kind === kind)!.kdf)
      for (const [secret, offered] of [[PASSPHRASE, 'passphrase'], [code.canonical, 'recovery']] as const) {
        const out = await unlockFromBlob(moved, secret, offered)
        expect(out, `${what}, offered the ${offered}`).toEqual({ ok: false, fault: 'settingsNotAccepted' })
        if (!out.ok) expect(UNLOCK_FAULT_TEXT[out.fault], what).not.toBe(UNLOCK_FAULT_TEXT.didNotOpen)
      }
    }
  })

  it('says which lock the server’s key does not have, by the secret that was offered, and names the other', async () => {
    const passphraseOnly: RecoverableDataKey = { v: 1, slots: blob.slots.filter((s) => s.kind === 'passphrase') }
    const recoveryOnly: RecoverableDataKey = { v: 1, slots: blob.slots.filter((s) => s.kind === 'recovery') }
    expect(await unlockFromBlob(passphraseOnly, code.canonical, 'recovery')).toEqual({ ok: false, fault: 'noRecoveryLock' })
    expect(await unlockFromBlob(recoveryOnly, 'any passphrase at all', 'passphrase')).toEqual({ ok: false, fault: 'noPassphraseLock' })
    // Each sentence sends the person to the secret that is left.
    expect(UNLOCK_FAULT_TEXT.noRecoveryLock).toMatch(/Use your passphrase\.$/)
    expect(UNLOCK_FAULT_TEXT.noPassphraseLock).toMatch(/Use your recovery code\.$/)
    expect('noSlotOfThatKind' in UNLOCK_FAULT_TEXT).toBe(false)
  })

  it('has no file to be handed any more, and no words for one', () => {
    // The key file is retired (#258): the console opens the server's copy. Neither the entry point
    // that read a file nor a fault about one is left.
    const source = readFileSync(fileURLToPath(new URL('./unlock.ts', import.meta.url)), 'utf8')
    const code_ = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')
    const FILE = /\bunlockOwnerIdentity\b|\bfileText\b|decodeWrappedKeyFile|\bnoFile\b|\bnotJson\b/
    expect(FILE.test('export async function unlockOwnerIdentity(fileText: string')).toBe(true)
    expect(FILE.test(code_)).toBe(false)
    expect(Object.values(UNLOCK_FAULT_TEXT).filter((t) => /\bfile\b/i.test(t))).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (d) The copy obeys the rules this product's refusals obey.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(d) the fault sentences', () => {
  const sentences = Object.entries(UNLOCK_FAULT_TEXT)

  it('every fault has one', () => {
    for (const [fault, text] of sentences) {
      expect(text.trim().length, fault).toBeGreaterThan(0)
    }
  })

  it('none of them congratulates, scolds, or claims the data is gone', () => {
    const FORBIDDEN = [
      { name: 'a success register', pattern: /\b(success|succeeded|great|well done|congratulat)/i, planted: 'Success! Your key is open.' },
      { name: 'blame', pattern: /\byou (?:did|entered|typed) (?:it |that )?wrong\b|\bincorrect\b|\binvalid\b/i, planted: 'Invalid passphrase.' },
      { name: 'a claim the data is gone', pattern: /\b(lost forever|gone forever|destroyed|unrecoverable)\b/i, planted: 'Your journal is gone forever.' },
    ]
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
      for (const [fault, text] of sentences) {
        expect(pattern.test(text), `${fault}: ${name}`).toBe(false)
      }
    }
  })

  it('none of them echoes what was typed', () => {
    // A fault sentence is composed without the input, so there is no template for one to arrive in.
    const source = readFileSync(fileURLToPath(new URL('./unlock.ts', import.meta.url)), 'utf8')
    const start = source.indexOf('export const UNLOCK_FAULT_TEXT')
    const end = source.indexOf("The server's locked key + one secret")
    expect(start).toBeGreaterThan(-1)
    expect(end).toBeGreaterThan(start)
    const table = source.slice(start, end)
    expect(table).toContain('didNotOpen:')
    expect(table).not.toMatch(/\$\{/)
  })
})
