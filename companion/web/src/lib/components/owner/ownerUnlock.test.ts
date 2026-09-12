/*
 * THE FRONT DOOR, ASSERTED OVER ITS SOURCE.
 *
 * Web tests run in node with no renderer, so what can be checked here is what the file DOES and
 * what it SAYS. That is enough for the two properties this screen exists for:
 *
 *   1. there is no path left that invents an identity (issue #121 was exactly such a path, and it
 *      looked entirely reasonable in review — a button labelled "Generate owner keys");
 *   2. neither the passphrase nor the recovery code is written anywhere.
 *
 * The sequencing — check symbol before Argon2id, master wiped after deriving — is asserted properly
 * in owner/unlock.test.ts, where it is a behavioural test rather than a grep.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import * as unlockCopy from '../../owner/unlockCopy'

const source = readFileSync(fileURLToPath(new URL('./OwnerUnlock.svelte', import.meta.url)), 'utf8')

/** What ships, with the commentary removed — this file's header names what it refuses to do. */
const code = source
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(?<!:)\/\/[^\n]*/g, '')

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) The generate path is gone, not hidden.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) nothing here can invent an identity', () => {
  const MINTING: { name: string; pattern: RegExp; planted: string }[] = [
    { name: 'a fresh signing keypair', pattern: /\bnewSignKeyPair\b/, planted: 'const kp = newSignKeyPair()' },
    { name: 'a fresh box keypair', pattern: /\bnewBoxKeyPair\b/, planted: 'const kp = newBoxKeyPair()' },
    { name: 'a fresh identity', pattern: /\bnewIdentity\b/, planted: 'ownerIdentity = newIdentity()' },
    { name: 'raw sodium keypairs', pattern: /crypto_(?:sign|box)_keypair\b/, planted: 'so.crypto_sign_keypair()' },
    { name: 'a generate handler', pattern: /\bgenerateOwner\b/, planted: 'async function generateOwner() {}' },
  ]

  it('the detectors detect', () => {
    // Five absence assertions are five ways to be vacuous. Each pattern is shown catching its own
    // planted line, and shown not firing on the call this screen actually makes.
    for (const { name, pattern, planted } of MINTING) {
      expect(pattern.test(planted), name).toBe(true)
    }
    expect(MINTING.some((m) => m.pattern.test('const out = await unlockOwnerIdentity(f, s, k)'))).toBe(false)
  })

  it('names none of them', () => {
    expect(MINTING.filter((m) => m.pattern.test(code)).map((m) => m.name)).toEqual([])
  })

  it('gets its identity from the owner key and from nowhere else', () => {
    expect(code).toContain('unlockOwnerIdentity')
    // One assignment to ownerIdentity that is not the initial null, and it is the unlock's result.
    const assignments = code.match(/ownerIdentity\s*=\s*[^\n]+/g) ?? []
    expect(assignments.filter((a) => !a.includes('$state'))).toEqual(['ownerIdentity = out.identity'])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) Neither secret is written anywhere.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) the passphrase and the code do not leave the tab', () => {
  const FORBIDDEN: { name: string; pattern: RegExp; planted: string }[] = [
    { name: 'localStorage', pattern: /\blocalStorage\b/, planted: 'localStorage.setItem("p", passphrase)' },
    { name: 'sessionStorage', pattern: /\bsessionStorage\b/, planted: 'sessionStorage.setItem("p", passphrase)' },
    { name: 'indexedDB', pattern: /\bindexedDB\b/i, planted: 'indexedDB.open("keys")' },
    { name: 'cookies', pattern: /document\s*\.\s*cookie/, planted: 'document.cookie = "p=" + passphrase' },
    { name: 'the system clipboard', pattern: /navigator\s*\.\s*clipboard/, planted: 'navigator.clipboard.readText()' },
    { name: 'fetch', pattern: /\bfetch\s*\(/, planted: 'fetch("/v1/x", { body: passphrase })' },
    { name: 'the console', pattern: /\bconsole\s*\.\s*(log|info|warn|error|debug)\b/, planted: 'console.log(passphrase)' },
  ]

  it('the detectors detect', () => {
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
    }
    expect(FORBIDDEN.some((f) => f.pattern.test('keyFileText = await file.text()'))).toBe(false)
  })

  it('names none of them', () => {
    expect(FORBIDDEN.filter((f) => f.pattern.test(code)).map((f) => f.name)).toEqual([])
  })

  it('drops both secrets once they have been used, and when the other is chosen', () => {
    // A secret left in a bound field is a secret sitting in a form for no reason. Both the
    // successful unlock and the swap between the two entry modes clear them.
    const cleared = code.match(/passphrase = ''/g) ?? []
    expect(cleared.length).toBeGreaterThanOrEqual(2)
    expect(code.match(/groups = emptyGroups\(\)/g)?.length ?? 0).toBeGreaterThanOrEqual(2)
  })

  it('never puts a secret in a message', () => {
    // The refusals come from UNLOCK_FAULT_TEXT by key; there is no template for an input to arrive
    // in. A planted interpolation shows the detector is not blind.
    expect(/error = [^\n]*\$\{[^\n]*(passphrase|groups|secret)/.test(code)).toBe(false)
    expect(/error = [^\n]*\$\{[^\n]*(passphrase|groups|secret)/.test('error = `Wrong: ${passphrase}`')).toBe(true)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) The order: nothing expensive runs before the check character.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(c) a mistyped code is refused before any derivation', () => {
  it('runs firstGroupProblem before it imports the unlock module', () => {
    const check = code.indexOf('firstGroupProblem(groups)')
    const derive = code.indexOf("await import('../../owner/unlock')")
    expect(check).toBeGreaterThan(-1)
    expect(derive).toBeGreaterThan(-1)
    expect(check).toBeLessThan(derive)
  })

  it('returns rather than falling through when the code is mistyped', () => {
    // Order alone would not be enough: checking first and continuing anyway would still spend the
    // three seconds. The guard has to leave.
    expect(code).toMatch(/firstGroupProblem\(groups\)[\s\S]{0,120}?return/)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (d) What the screen says.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(d) the words', () => {
  it('renders the lede, the nothing-is-kept sentence and the file description', () => {
    for (const name of ['UNLOCK_LEDE', 'NOTHING_IS_KEPT', 'KEY_FILE_HINT', 'NO_KEY_FILE_YET']) {
      expect(code, name).toContain(`{${name}}`)
    }
  })

  it('says the fingerprint is now stable, beside the fingerprint', () => {
    // The whole user-visible content of this fix, and what makes reading it aloud worth doing.
    const fp = code.indexOf('Your owner fingerprint')
    const stable = code.indexOf('{FINGERPRINT_IS_STABLE}')
    expect(fp).toBeGreaterThan(-1)
    expect(stable).toBeGreaterThan(fp)
    expect(stable - fp).toBeLessThan(400)
  })

  it('tells someone with no key file what making one would cost them', () => {
    // Absence is not a failure, so this is plain text rather than an error — but it must say that a
    // new key is a new identity, because that is the defect this screen was rebuilt to remove.
    expect(unlockCopy.NO_KEY_FILE_YET).toContain('new identity')
    expect(unlockCopy.NO_KEY_FILE_YET).toMatch(/will not match/)
  })

  it('offers the recovery code without promising it does what the recovery screen does', () => {
    expect(unlockCopy.CODE_OPENS_THIS_SESSION).toContain('changes nothing else')
    expect(unlockCopy.CODE_OPENS_THIS_SESSION).toContain('Recovery code screen')
  })

  it('none of it congratulates anybody for opening a door', () => {
    const FORBIDDEN = [
      { name: 'a success register', pattern: /\b(success|succeeded|great|welcome back|congratulat|all set)/i, planted: 'Success — welcome back!' },
      { name: 'a tick or a badge', pattern: /✓|✔|&check;/, planted: '✓ Unlocked' },
    ]
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
      for (const [key, value] of Object.entries(unlockCopy)) {
        if (typeof value === 'string') expect(pattern.test(value), `${key}: ${name}`).toBe(false)
      }
    }
  })

  it('the verb is not "generate"', () => {
    expect(unlockCopy.UNLOCK_ACTION).toBe('Unlock')
    expect(unlockCopy.UNLOCK_ACTION.toLowerCase()).not.toContain('generate')
  })
})
