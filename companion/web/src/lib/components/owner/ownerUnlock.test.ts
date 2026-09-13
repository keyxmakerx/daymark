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

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (e) The inbox token is minted here, and there is nowhere to type one.

   Issue #126's headline finding. The token is the second factor on every route that serves
   relationship content, and until 2026-09-12 the only way one entered the system was a text box on
   this screen whose entire validation was "not empty". The properties below are what replaced it.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(e) the inbox token', () => {
  it('has no field to be typed into', () => {
    /*
     * The absence this whole issue is about, with the deleted markup as its control. A screen that
     * mints a token AND still accepts a typed one has fixed nothing: the weak path is the one a
     * hurried person takes.
     */
    const TYPED = /bind:value=\{tInboxToken\}|\btInboxToken\b/
    expect(TYPED.test('<label><span>Inbox token (OOB)</span><input bind:value={tInboxToken} /></label>'))
      .toBe(true)
    expect(TYPED.test(code)).toBe(false)
    // And no other password field crept in to take its place — the form asks for a name and two
    // public keys, none of which is a secret.
    const passwordInputs = (code.match(/<input[^>]*type="password"[^>]*>/g) ?? []).filter(
      (tag) => !tag.includes('passphrase'),
    )
    expect(passwordInputs).toEqual([])
  })

  it('gets the token from the minting module and from nowhere else', () => {
    expect(code).toContain('mintInboxToken()')
    // One assignment into the pinned entry, and it is the minted value.
    const assigned = code.match(/inboxToken:\s*[^\n,]+/g) ?? []
    expect(assigned).toEqual(['inboxToken: token'])
  })

  it('puts no part of the token into the pending id, or into a grant', () => {
    /*
     * The latent hazard in the issue's last section. `emptyGrant(id).therapistFingerprint` is
     * emitted by encodeSignedGrant as plain JSON — signed, not encrypted — and stored under the
     * token's own digest, which would make it an offline oracle for the rest of the token. Nothing
     * reaches it today only because the Grants tab is replaced by a message while an entry is
     * pending. The id no longer carries the token, so the tab condition is no longer what is
     * holding the leak shut.
     */
    const PREFIXED = /slice\(\s*0\s*,\s*8\s*\)/
    expect(PREFIXED.test('id: `pending:${tInboxToken.trim().slice(0, 8)}:${pinned.length}`')).toBe(true)
    expect(PREFIXED.test(code)).toBe(false)

    // Every id built for a pending entry, and what goes into it. A counter, and nothing else.
    const pendingIds = code.match(/`pending:[^`]*`/g) ?? []
    expect(pendingIds).toEqual(['`pending:${++pendingSeq}`'])

    // The grant is handed the id, never a value derived from the token.
    expect(code.match(/emptyGrant\([^)]*\)/g) ?? []).toEqual(['emptyGrant(id)'])
  })

  it('shows it as an output rather than a form control, and never in a refusal', () => {
    // Same choice PairingPanel makes for the pairing code: a form control is something a browser
    // saves and an autofill carries into the next form that looks like this one.
    expect(code).toMatch(/<output[^>]*>\{mintedToken\}<\/output>/)
    expect(code).not.toMatch(/<input[^>]*\bmintedToken\b/)

    const IN_A_MESSAGE = /error = [^\n]*\$\{[^\n]*(token|mintedToken)/
    expect(IN_A_MESSAGE.test('error = `That token is taken: ${token}`')).toBe(true)
    expect(IN_A_MESSAGE.test(code)).toBe(false)
  })

  it('says it is the only sighting, and which channel it must not travel by', () => {
    for (const name of ['ADDING_MINTS_A_TOKEN', 'INBOX_TOKEN_TWO_CHANNELS', 'INBOX_TOKEN_SHOWN_ONCE']) {
      expect(code, name).toContain(`{${name}}`)
    }
    // The correction the audit asked for, in the words a person actually reads.
    expect(unlockCopy.INBOX_TOKEN_TWO_CHANNELS).toMatch(/not in the invitation/i)
    expect(unlockCopy.INBOX_TOKEN_SHOWN_ONCE).toMatch(/here and nowhere else/i)
    // "Shown once" without a way forward reads as a threat, so it names what to do instead.
    expect(unlockCopy.INBOX_TOKEN_SHOWN_ONCE).toMatch(/adding this clinician again/i)
  })

  it('refuses without naming a cause it cannot know', () => {
    /*
     * CLAUDE.md §4: a refusal names a consequence. "libsodium is not ready" or "your browser
     * blocked randomness" are guesses this screen cannot support; "this clinician has not been
     * added" is the fact the owner needs.
     */
    const refusals = code.match(/error = '[^']*'/g) ?? []
    expect(refusals.length).toBeGreaterThan(2)
    const BLAME = /\b(you typed|your fault|invalid input|try harder|browser is broken)\b/i
    expect(BLAME.test("error = 'Invalid input — you typed it wrong.'")).toBe(true)
    for (const r of refusals) expect(BLAME.test(r), r).toBe(false)
    expect(refusals).toContain("error = 'No token could be made, so this clinician has not been added.'")
  })
})
