/*
 * A CLINICIAN LEAVING (issue #91), and the claim the issue says to attack before pushing:
 * self-leave cannot destroy anything belonging to the owner.
 *
 * That claim is proved here two ways. Behaviourally, by handing the module a storage port and a
 * logout port and showing that nothing else is reachable from it. Structurally, by reading the
 * source and showing that no owner-side call appears in it — with every dangerous call planted
 * first, because an absence nothing can catch is an absence nobody has checked.
 */
import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { leaveRelationship, LEAVE_REACHES_ONLY_THIS_BROWSER, LEAVE_IS_NOT_UNDONE, NO_KEYS_IN_THIS_BROWSER } from './leave'
import { KEY_RECORD_STORAGE_KEY, type KeyRecord } from './inviteAccept'
import { REVOKE_CAVEAT } from '../pairing/copy'

const record = (relRef: string): KeyRecord => ({
  v: 1,
  relRef,
  credentialId: `cred-${relRef}`,
  wrapped: { v: 1, kdf: { alg: 'argon2id', memMiB: 256, ops: 3 }, saltB64: 's', nonceB64: 'n', ctB64: 'c' },
  createdAt: 1,
})

/** A storage port that records every call, so "what did it touch" is inspectable. */
function storageOf(records: KeyRecord[]) {
  const box = { value: JSON.stringify(records) }
  const calls: string[] = []
  return {
    calls,
    box,
    port: {
      getItem: (k: string) => {
        calls.push(`get:${k}`)
        return k === KEY_RECORD_STORAGE_KEY ? box.value : null
      },
      setItem: (k: string, v: string) => {
        calls.push(`set:${k}`)
        if (k === KEY_RECORD_STORAGE_KEY) box.value = v
      },
    },
  }
}

describe('(a) what leaving does, and stops at', () => {
  it('forgets this relationship and leaves every other one alone', async () => {
    const s = storageOf([record('rel-a'), record('rel-b')])
    const logout = vi.fn(async () => {})

    const out = await leaveRelationship('rel-a', { storage: s.port, logout })

    expect(out).toEqual({ ok: true, signedOut: true })
    const kept = JSON.parse(s.box.value) as KeyRecord[]
    expect(kept.map((r) => r.relRef)).toEqual(['rel-b'])
    expect(logout).toHaveBeenCalledTimes(1)
  })

  it('touches exactly one storage key and calls exactly one network port', async () => {
    // The behavioural half of "nothing of the owner's is destroyed": the only ports it has are a
    // storage box and a logout, and it reaches for nothing outside them.
    const s = storageOf([record('rel-a')])
    const logout = vi.fn(async () => {})
    await leaveRelationship('rel-a', { storage: s.port, logout })
    expect(new Set(s.calls.map((c) => c.split(':')[1]))).toEqual(new Set([KEY_RECORD_STORAGE_KEY]))
  })

  it('forgets before it signs out', async () => {
    // The other order leaves somebody looking at a signed-out screen with their keys still on the
    // device, which is the state most likely to be read as "done".
    const s = storageOf([record('rel-a')])
    let recordGoneWhenLoggingOut = false
    const logout = async () => {
      recordGoneWhenLoggingOut = !JSON.parse(s.box.value).some((r: KeyRecord) => r.relRef === 'rel-a')
    }
    await leaveRelationship('rel-a', { storage: s.port, logout })
    expect(recordGoneWhenLoggingOut).toBe(true)
  })
})

describe('(b) it verifies rather than trusts', () => {
  it('reports the record remaining instead of claiming the keys are gone', async () => {
    // forgetKeyRecord swallows a refused write by design — its one other caller is a rollback on a
    // path that is already failing. Here the clinician ASKED for this, so a swallowed write would
    // mean telling somebody their keys are gone while they sit in the browser they are looking at.
    const s = storageOf([record('rel-a')])
    s.port.setItem = () => {
      /* a browser refusing the write, e.g. private mode with storage disabled */
    }
    const logout = vi.fn(async () => {})

    const out = await leaveRelationship('rel-a', { storage: s.port, logout })

    expect(out).toEqual({ ok: false, reason: 'recordRemains' })
    expect(logout, 'nothing is claimed and nothing is ended').not.toHaveBeenCalled()
  })

  it('says so when there was nothing here to forget', async () => {
    const s = storageOf([record('rel-b')])
    expect(await leaveRelationship('rel-a', { storage: s.port, logout: async () => {} })).toEqual({
      ok: false,
      reason: 'noRecord',
    })
  })

  it('a failed logout is not a failed leave', async () => {
    // Once the record is gone the irreversible half has happened. Reporting the whole thing as
    // failed would invite a retry of something that cannot be retried.
    const s = storageOf([record('rel-a')])
    const out = await leaveRelationship('rel-a', {
      storage: s.port,
      logout: async () => {
        throw new Error('network')
      },
    })
    expect(out).toEqual({ ok: true, signedOut: false })
    expect(JSON.parse(s.box.value)).toEqual([])
  })

  it('does nothing at all without storage', async () => {
    const logout = vi.fn(async () => {})
    expect(await leaveRelationship('rel-a', { storage: null, logout })).toEqual({ ok: false, reason: 'noRecord' })
    expect(logout).not.toHaveBeenCalled()
  })
})

describe('(c) nothing here can reach the owner', () => {
  const source = readFileSync(fileURLToPath(new URL('./leave.ts', import.meta.url)), 'utf8')
  const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')

  const OWNER_SIDE: { name: string; pattern: RegExp; planted: string }[] = [
    { name: 'revoking a share', pattern: /revokeShare|\/revoke/, planted: 'await client.revokeShare(token)' },
    { name: 'writing a blob', pattern: /putBlob|method:\s*'PUT'/, planted: "await client.putBlob(t, 'shares', 'share', 0, b)" },
    { name: 'deleting anything', pattern: /method:\s*'DELETE'|\bdelete\s+[a-z]/i, planted: "fetch(u, { method: 'DELETE' })" },
    { name: 'the grants channel', pattern: /'grants'|"grants"/, planted: "client.listVersions(t, 'grants', 'g')" },
    { name: 'the shares channel', pattern: /'shares'|"shares"/, planted: "client.listVersions(t, 'shares', 'share')" },
    { name: 'owner keys', pattern: /owner-keys|OwnerKeys/, planted: 'client.publishedOwnerKeys(rel)' },
    { name: 'a bare fetch', pattern: /\bfetch\s*\(/, planted: 'fetch("/v1/anything")' },
  ]

  it('the detectors detect', () => {
    for (const { name, pattern, planted } of OWNER_SIDE) {
      expect(pattern.test(planted), name).toBe(true)
    }
    // And none fires on what this module actually does, or the assertion below is unsatisfiable.
    expect(OWNER_SIDE.some((o) => o.pattern.test('forgetKeyRecord(relRef, ports.storage)'))).toBe(false)
  })

  it('names none of them', () => {
    expect(OWNER_SIDE.filter((o) => o.pattern.test(code)).map((o) => o.name)).toEqual([])
  })

  it('has exactly two ports, and they are the two it is allowed', () => {
    // A third port is how this would grow a way to touch something else, so the interface is
    // asserted rather than described.
    const iface = code.slice(code.indexOf('export interface LeavePorts'), code.indexOf('export type LeaveOutcome'))
    expect([...iface.matchAll(/readonly (\w+)/g)].map((m) => m[1])).toEqual(['storage', 'logout'])
  })
})

describe('(d) the words', () => {
  it('does not reuse the owner’s caveat, which is about a different act', () => {
    // "Revoking does not un-send what was already read" is about a reader who already has
    // something. From this side there is no such reader.
    expect(LEAVE_REACHES_ONLY_THIS_BROWSER).not.toBe(REVOKE_CAVEAT)
    expect(LEAVE_REACHES_ONLY_THIS_BROWSER).not.toContain('un-send')
    // But it carries the honest equivalent, including the part a clinician assumes away.
    expect(LEAVE_REACHES_ONLY_THIS_BROWSER).toContain('only this browser')
    expect(LEAVE_REACHES_ONLY_THIS_BROWSER).toContain('saved copy of your key record')
  })

  it('leads with irreversibility, not with reassurance', () => {
    expect(LEAVE_IS_NOT_UNDONE).toContain('cannot be opened again')
    expect(LEAVE_IS_NOT_UNDONE).toContain('fresh invitation')
  })

  it('never tells somebody why their keys are missing, because it cannot know', () => {
    // A clinician who left and one whose cache was cleared are indistinguishable from that screen.
    expect(NO_KEYS_IN_THIS_BROWSER).not.toMatch(/you left|you removed|cleared|deleted/i)
    expect(NO_KEYS_IN_THIS_BROWSER).toContain('holds no keys')
  })

  it('none of it scolds, congratulates, or reads as an alarm', () => {
    const FORBIDDEN = [
      { name: 'congratulation', pattern: /\b(success|done!|great|all set)\b/i, planted: 'Success! You have left.' },
      { name: 'blame', pattern: /\b(you should|be careful|warning|failed)\b/i, planted: 'Warning: you should be careful.' },
    ]
    const all = [LEAVE_REACHES_ONLY_THIS_BROWSER, LEAVE_IS_NOT_UNDONE, NO_KEYS_IN_THIS_BROWSER].join(' ')
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
      expect(pattern.test(all), name).toBe(false)
    }
  })
})
