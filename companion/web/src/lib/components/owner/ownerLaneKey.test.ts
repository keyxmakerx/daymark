/*
 * THE CONSOLE'S LANE KEY, ASSERTED OVER ITS SOURCE (#345).
 *
 * The owner console reads and adds to the owner's lane with the sync key of the master it opened,
 * and with the ETag of the key document it opened it from (lane/lane.ts). That key has to come from
 * the same unlock, or the same set-up, that gives the console its identity, from nowhere else, and
 * has to go when the console locks. The behaviour is node-tested where it lives (owner/unlock,
 * recovery/serverKey); what is held here is that the screens hand it on, and only from there.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const codeOf = (rel: string) =>
  readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')

const unlock = codeOf('./OwnerUnlock.svelte')
const consoleCode = codeOf('./OwnerConsole.svelte')
const setUpForm = codeOf('../recovery/KeySetup.svelte')
const newCode = codeOf('../recovery/NewCodeFlow.svelte')

describe('the door hands the console the lane key of the master it opened', () => {
  it('sets the key only where the identity is set, from the same unlock or set-up', () => {
    expect(unlock.match(/\blaneKey = [^\n]+/g)).toEqual(['laneKey = setUpLane', 'laneKey = { syncKey: out.syncKey, keyDocumentEtag }'])
    expect(unlock.match(/\bsetUpLane = [^\n]+/g)).toEqual([
      'setUpLane = stored.lane',
      'setUpLane = { syncKey: back.syncKey, keyDocumentEtag: out.etag }',
      'setUpLane = null',
    ])
    // Each beside the identity it belongs with: the unlock's right after opened(out.identity)...
    expect(unlock).toMatch(/opened\(out\.identity\)\s*laneKey = \{ syncKey: out\.syncKey, keyDocumentEtag \}/)
    // ...a set-up's with its identity, and the read-back's with the identity that read-back opened.
    expect(unlock).toMatch(/setUpIdentity = stored\.identity\s*setUpLane = stored\.lane/)
    expect(unlock).toMatch(/setUpIdentity = back\.identity\s*setUpLane = \{ syncKey: back\.syncKey, keyDocumentEtag: out\.etag \}/)
    expect(unlock).toMatch(/opened\(setUpIdentity\)\s*laneKey = setUpLane/)
  })

  it('names the ETag of the document the unlock opened, taken before anything is awaited', () => {
    const body = unlock.slice(unlock.indexOf('async function unlock()'), unlock.indexOf('async function addTherapist()'))
    const named = body.indexOf('const keyDocumentEtag = held.etag')
    expect(named).toBeGreaterThan(body.indexOf("if (!held || held.kind !== 'wrapped') return"))
    expect(named).toBeLessThan(body.indexOf('await'))
  })

  it('opens the console only with the key beside the identity, and hands it over in the session', () => {
    const enter = unlock.slice(unlock.indexOf('function enter()'))
    expect(enter).toContain('if (!ownerIdentity || !readWith || !laneKey) return')
    expect(enter).toContain('lane: laneKey }, readWith)')
  })

  it('the set-up form hands the key on with the identity, and the Recovery code screen, which keeps no console, wipes it at once', () => {
    expect(setUpForm).toContain('onstored({ recoveryCode: out.recoveryCode, identity: out.identity, lane: out.lane })')
    const stored = newCode.slice(newCode.indexOf('async function keyStored('), newCode.indexOf('function keyUnread('))
    expect(stored).toContain('stored.lane.syncKey.fill(0)')
  })
})

describe('the console drops the key when it locks', () => {
  it('wipes the sync key before it lets the session go', () => {
    const lock = consoleCode.slice(consoleCode.indexOf('function lock()'), consoleCode.indexOf('async function connect()'))
    const wipe = lock.indexOf('session?.lane.syncKey.fill(0)')
    expect(wipe).toBeGreaterThan(-1)
    expect(wipe).toBeLessThan(lock.indexOf('session = null'))
  })
})
