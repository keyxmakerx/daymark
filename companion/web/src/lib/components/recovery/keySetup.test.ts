/*
 * THE SET-UP FORM, ASSERTED OVER ITS SOURCE AND ITS WORDS (#258).
 *
 * KeySetup.svelte is the one form that makes or enrols the owner's key on a server that holds no
 * locked key yet, shown by the owner console's door and by "Get a code". Its order lives in
 * recovery/serverKey.ts and is a node test there (serverKey.test.ts); what is left to hold here is
 * what the form does with what comes back — hands the code to its caller, forgets what was typed —
 * and what it says, which has to be true in each of the three ways it can end.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import * as copy from './copy'

const source = readFileSync(fileURLToPath(new URL('./KeySetup.svelte', import.meta.url)), 'utf8')
const code = source
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(?<!:)\/\/[^\n]*/g, '')

describe('what the form does', () => {
  it('sets up through serverKey.ts, with the state it was handed, loaded only when used', () => {
    expect(code).toContain('setUp(ports, held, passphrase, asksTwice ? repeated : null)')
    expect(code).toContain("await import('../../recovery/serverKey')")
    // A value import would put the crypto in the chunk of every page that shows the form.
    expect(code).not.toMatch(/^\s*import \{[^}]*\} from '\.\.\/\.\.\/recovery\/serverKey'/m)
  })

  it('shows no code itself: it hands the code and the identity to its caller', () => {
    expect(code).not.toContain('<CodeSheet')
    expect(code).toContain('onstored({ recoveryCode: out.recoveryCode, identity: out.identity, lane: out.lane })')
  })

  it('forgets a typed passphrase on every way out but a refusal', () => {
    // stored, stored and not read back, moved, not read back as sent, failed, and a throw. A
    // refusal keeps what was typed, so a person told the two passphrases differ can correct one
    // rather than type both again.
    const body = code.slice(code.indexOf('async function setUpKey()'))
    expect(body.match(/forgetTypedPassphrases\(\)/g)).toHaveLength(6)
    const refusedAt = body.indexOf("out.kind === 'refused'")
    const refused = body.slice(refusedAt, body.indexOf('} else', refusedAt))
    expect(refused).toContain('SETUP_FAULT_TEXT[out.fault]')
    expect(refused).not.toContain('forgetTypedPassphrases()')
    const forget = code.slice(code.indexOf('function forgetTypedPassphrases()'), code.indexOf('function setBusy('))
    expect(forget).toContain("passphrase = ''")
    expect(forget).toContain("repeated = ''")
  })

  it('hands on a code whose lock the server took even when it could not be read back (#258)', () => {
    // The lock is on the server either way; a code that is not handed on is a lock nobody holds.
    const branch = (kind: string) => {
      const at = code.indexOf(`out.kind === '${kind}'`)
      expect(at, kind).toBeGreaterThan(-1)
      return code.slice(at, code.indexOf('} else', at))
    }
    expect(branch('storedUnread')).toContain('onunread({ recoveryCode: out.recoveryCode, sent: out.sent })')
    // The mismatch sentence is for a read-back that was read and did not open, and for nothing else.
    expect(branch('unchecked')).toContain('onlost(READ_BACK_DID_NOT_MATCH)')
    expect(code.match(/READ_BACK_DID_NOT_MATCH\)/g)).toHaveLength(1)
    // And a set-up whose outcome is not known says so, whichever way it ended.
    expect(code.match(/onlost\(SETUP_FAILED\)/g)).toHaveLength(2)
  })

  it('asks for the passphrase twice on a first run, and wherever the set-up says it must', () => {
    expect(code).toContain("const asksTwice = $derived(firstRun || check === 'typedTwice' || twiceAfterAll)")
    expect(code).toContain("if (out.fault === 'typeItTwice') twiceAfterAll = true")
  })

  it('never puts what was typed into a message', () => {
    const ECHO = /error = [^\n]*\$\{/
    expect(ECHO.test('error = `That did not open: ${passphrase}`')).toBe(true)
    expect(ECHO.test(code)).toBe(false)
    expect(code).toContain('error = SETUP_FAULT_TEXT[out.fault]')
  })
})

describe('what the form says', () => {
  it('renders what the server holds, and the enrolment says which check it will make', () => {
    for (const name of ['HOLDS_NOTHING', 'HOLDS_KEY_PARAMETERS']) expect(code, name).toContain(`{${name}}`)
    expect(code).toContain('{asksTwice ? ENROL_ASKS_TWICE : ENROL_TRIES_NEWEST_SNAPSHOT}')
  })

  it('says what each answer of the server means before anything is typed', () => {
    // Nothing on the server: a key made here, with only its locks stored.
    expect(copy.HOLDS_NOTHING).toContain('new recovery code')
    expect(copy.HOLDS_NOTHING).toContain('only the two locks')
    // Key parameters: the key the passphrase already opens, and nothing encrypted again.
    expect(copy.HOLDS_KEY_PARAMETERS).toContain('the key your passphrase already opens')
    expect(copy.HOLDS_KEY_PARAMETERS).toContain('Nothing already stored is encrypted again')
    // The enrolment's check, before the passphrase is typed.
    expect(copy.ENROL_TRIES_NEWEST_SNAPSHOT).toContain('nothing is stored')
  })

  it('says nothing was stored wherever that is true, and only there', () => {
    // Every set-up fault is decided before anything is sent (serverKey.ts), so each says so.
    const faults = Object.entries(copy.SETUP_FAULT_TEXT)
    expect(faults.map(([f]) => f).sort()).toEqual(
      ['alreadyLocked', 'doesNotOpenNewest', 'noPassphrase', 'passphrasesDiffer', 'selfCheckFailed', 'snapshotsWithoutKey', 'typeItTwice'],
    )
    for (const [fault, text] of faults) expect(text, fault).toMatch(/nothing has been stored/i)
    // After a create the server took, it is not true, and the sentence does not say it.
    expect(copy.READ_BACK_DID_NOT_MATCH).toContain('The server accepted the new key')
    expect(copy.READ_BACK_DID_NOT_MATCH).not.toMatch(/nothing (has been|was) stored/i)
    // Nor after a failure that may have come after the create.
    expect(copy.SETUP_FAILED).not.toMatch(/nothing (has been|was) stored/i)
  })

  it('names no cause it cannot know when a passphrase does not open the snapshot', () => {
    const BLAME = /\b(wrong|incorrect|invalid|mistyped|you typed)\b/i
    expect(BLAME.test('That passphrase is wrong.')).toBe(true)
    for (const [fault, text] of Object.entries(copy.SETUP_FAULT_TEXT)) expect(BLAME.test(text), fault).toBe(false)
    expect(BLAME.test(copy.KEY_CHANGED_ON_SERVER)).toBe(false)
  })
})
