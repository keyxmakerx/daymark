/*
 * A CLINICIAN LEAVING (issue #91), and the claim the issue says to attack before pushing:
 * self-leave cannot destroy anything belonging to the owner.
 *
 * That claim is proved here two ways. Behaviourally, by handing the module three ports and showing
 * that nothing else is reachable from it. Structurally, by reading the source and showing that no
 * owner-side call appears in it — with every dangerous call planted first, because an absence
 * nothing can catch is an absence nobody has checked.
 *
 * The module grew from two operations to three when the server route that closes a clinician's
 * credential arrived. The "exactly N operations, checkable" property is the point of the module, so
 * it is asserted as exactly three: the port interface is pinned in order, and the same seven
 * owner-side calls are still planted and still shown caught.
 */
import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import {
  leaveRelationship,
  LEAVE_DOES_NOT_REACH_COPIES,
  LEAVE_IS_NOT_UNDONE,
  LEAVE_TOUCHES_NOTHING_OF_THEIRS,
  TELL_THEM_YOURSELF,
  LEAVE_SERVER_REFUSED,
  NO_KEYS_IN_THIS_BROWSER,
} from './leave'
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
  it('ends it on the server, forgets this relationship, and leaves every other one alone', async () => {
    const s = storageOf([record('rel-a'), record('rel-b')])
    const serverLeave = vi.fn(async () => {})
    const logout = vi.fn(async () => {})

    const out = await leaveRelationship('rel-a', { serverLeave, storage: s.port, logout })

    expect(out).toEqual({ ok: true, signedOut: true })
    const kept = JSON.parse(s.box.value) as KeyRecord[]
    expect(kept.map((r) => r.relRef)).toEqual(['rel-b'])
    expect(serverLeave).toHaveBeenCalledTimes(1)
    expect(logout).toHaveBeenCalledTimes(1)
  })

  it('touches exactly one storage key and calls exactly two network ports', async () => {
    // The behavioural half of "nothing of the owner's is destroyed": the only ports it has are a
    // storage box, an ending call and a logout, and it reaches for nothing outside them.
    const s = storageOf([record('rel-a')])
    await leaveRelationship('rel-a', { serverLeave: async () => {}, storage: s.port, logout: async () => {} })
    expect(new Set(s.calls.map((c) => c.split(':')[1]))).toEqual(new Set([KEY_RECORD_STORAGE_KEY]))
  })

  it('ends it on the server before it forgets anything locally', async () => {
    // The server call is the only step whose failure can be reported honestly, because nothing
    // local has happened yet. Doing it last would mean forgetting the keys and THEN failing to
    // close the door — the clinician can no longer read anything and is also not out.
    const s = storageOf([record('rel-a')])
    let recordStillHereAtServerCall = false
    const serverLeave = async () => {
      recordStillHereAtServerCall = JSON.parse(s.box.value).some((r: KeyRecord) => r.relRef === 'rel-a')
    }
    await leaveRelationship('rel-a', { serverLeave, storage: s.port, logout: async () => {} })
    expect(recordStillHereAtServerCall).toBe(true)
  })

  it('forgets before it signs out', async () => {
    // The other order leaves somebody looking at a signed-out screen with their keys still on the
    // device, which is the state most likely to be read as "done".
    const s = storageOf([record('rel-a')])
    let recordGoneWhenLoggingOut = false
    const logout = async () => {
      recordGoneWhenLoggingOut = !JSON.parse(s.box.value).some((r: KeyRecord) => r.relRef === 'rel-a')
    }
    await leaveRelationship('rel-a', { serverLeave: async () => {}, storage: s.port, logout })
    expect(recordGoneWhenLoggingOut).toBe(true)
  })

  it('runs the three operations in the pinned order', async () => {
    // ORDER is the property this module exists to make a node test, so it is asserted as a
    // sequence rather than inferred from three separate before/after checks.
    const s = storageOf([record('rel-a')])
    const seen: string[] = []
    await leaveRelationship('rel-a', {
      serverLeave: async () => {
        seen.push('serverLeave')
      },
      storage: {
        getItem: s.port.getItem,
        setItem: (k: string, v: string) => {
          seen.push('forget')
          s.port.setItem(k, v)
        },
      },
      logout: async () => {
        seen.push('logout')
      },
    })
    expect(seen).toEqual(['serverLeave', 'forget', 'logout'])
  })
})

describe('(b) it verifies rather than trusts', () => {
  it('a refused server call is a leave that did not happen, and nothing local moved', async () => {
    // Nothing irreversible has occurred, so this is the one failure that can honestly offer a
    // retry — and it must not have forgotten the keys or ended the session on the way to saying so.
    const s = storageOf([record('rel-a')])
    const logout = vi.fn(async () => {})

    const out = await leaveRelationship('rel-a', {
      serverLeave: async () => {
        throw new Error('network')
      },
      storage: s.port,
      logout,
    })

    expect(out).toEqual({ ok: false, reason: 'serverRefused' })
    expect(JSON.parse(s.box.value).map((r: KeyRecord) => r.relRef)).toEqual(['rel-a'])
    expect(logout, 'still signed in, because nothing happened').not.toHaveBeenCalled()
  })

  it('reports the record remaining instead of claiming the keys are gone', async () => {
    // forgetKeyRecord swallows a refused write by design — its one other caller is a rollback on a
    // path that is already failing. Here the clinician ASKED for this, so a swallowed write would
    // mean telling somebody their keys are gone while they sit in the browser they are looking at.
    const s = storageOf([record('rel-a')])
    s.port.setItem = () => {
      /* a browser refusing the write, e.g. private mode with storage disabled */
    }
    const logout = vi.fn(async () => {})

    const out = await leaveRelationship('rel-a', { serverLeave: async () => {}, storage: s.port, logout })

    expect(out).toEqual({ ok: false, reason: 'recordRemains' })
    expect(logout, 'nothing further is claimed or ended from here').not.toHaveBeenCalled()
  })

  it('says so when there was nothing here to forget, and ends nothing on the server', async () => {
    // A browser holding no record for this relationship is not the browser the invitation was
    // accepted in. Ending somebody's relationship from a screen that was never theirs is not an act
    // this module may perform on a stray click, so the server is not called at all.
    const s = storageOf([record('rel-b')])
    const serverLeave = vi.fn(async () => {})
    expect(
      await leaveRelationship('rel-a', { serverLeave, storage: s.port, logout: async () => {} }),
    ).toEqual({ ok: false, reason: 'noRecord' })
    expect(serverLeave).not.toHaveBeenCalled()
  })

  it('a failed logout is not a failed leave', async () => {
    // By then the server has closed the sign-in and cut every session, so the last call is
    // housekeeping. Reporting the whole thing as failed would invite a retry of something finished.
    const s = storageOf([record('rel-a')])
    const out = await leaveRelationship('rel-a', {
      serverLeave: async () => {},
      storage: s.port,
      logout: async () => {
        throw new Error('network')
      },
    })
    expect(out).toEqual({ ok: true, signedOut: false })
    expect(JSON.parse(s.box.value)).toEqual([])
  })

  it('does nothing at all without storage', async () => {
    const serverLeave = vi.fn(async () => {})
    const logout = vi.fn(async () => {})
    expect(await leaveRelationship('rel-a', { serverLeave, storage: null, logout })).toEqual({
      ok: false,
      reason: 'noRecord',
    })
    expect(serverLeave).not.toHaveBeenCalled()
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
    expect(OWNER_SIDE.some((o) => o.pattern.test('await ports.serverLeave()'))).toBe(false)
  })

  it('names none of them', () => {
    expect(OWNER_SIDE.filter((o) => o.pattern.test(code)).map((o) => o.name)).toEqual([])
  })

  it('has exactly three ports, in the order they run, and they are the three it is allowed', () => {
    // A fourth port is how this would grow a way to touch something else, so the interface is
    // asserted rather than described — and asserted in ORDER, because the order is the contract.
    const iface = code.slice(code.indexOf('export interface LeavePorts'), code.indexOf('export type LeaveOutcome'))
    expect([...iface.matchAll(/readonly (\w+)/g)].map((m) => m[1])).toEqual(['serverLeave', 'storage', 'logout'])
  })

  it('the ending call is the only server route it can name, and it names it by port', () => {
    // The module reaches the server through a port it is handed, never through a URL of its own.
    // So there is no path in this file that can be pointed at a different route by editing a
    // string — which is what "exactly three operations" is worth anything for.
    expect(code).not.toMatch(/\/v1\//)
    // Control: the same search fires on a module that did build its own URL.
    expect("await this.req('/v1/relations/x/ending')").toMatch(/\/v1\//)
  })
})

describe('(d) the words', () => {
  it('does not reuse the owner’s caveat, which is about a different act', () => {
    // "Revoking does not un-send what was already read" is about a reader who already has
    // something. From this side there is no such reader.
    expect(LEAVE_DOES_NOT_REACH_COPIES).not.toBe(REVOKE_CAVEAT)
    expect(LEAVE_DOES_NOT_REACH_COPIES).not.toContain('un-send')
    // But it carries the honest equivalent, including the part a clinician assumes away.
    expect(LEAVE_DOES_NOT_REACH_COPIES).toContain('does not reach copies')
    expect(LEAVE_DOES_NOT_REACH_COPIES).toContain('saved copy of your key record')
  })

  it('no longer claims leaving reaches only this browser, because it does not', () => {
    // The server route closes the credential everywhere. The sentence that used to lead this
    // constant was true when leaving was per-browser and became false the moment it stopped being.
    const all = [LEAVE_DOES_NOT_REACH_COPIES, LEAVE_IS_NOT_UNDONE, LEAVE_TOUCHES_NOTHING_OF_THEIRS].join(' ')
    expect(all).not.toMatch(/only this browser/i)
    // Control: the search does fire on the wording it is keeping out.
    expect('Leaving reaches only this browser.').toMatch(/only this browser/i)
  })

  it('leads with irreversibility, and says the closure reaches every device', () => {
    expect(LEAVE_IS_NOT_UNDONE).toContain('every device')
    expect(LEAVE_IS_NOT_UNDONE).toContain('cannot be reopened')
    expect(LEAVE_IS_NOT_UNDONE).toContain('fresh invitation')
  })

  it('says nothing of theirs changes, without implying anything of theirs was at risk', () => {
    expect(LEAVE_TOUCHES_NOTHING_OF_THEIRS).toContain('Nothing of theirs changes')
    expect(LEAVE_TOUCHES_NOTHING_OF_THEIRS).not.toMatch(/delete|remove|lose/i)
  })

  it('the nudge is a reminder, not an instruction, and is honest about the silence', () => {
    // The owner does meet this in their own console. What does not happen is a message.
    expect(TELL_THEM_YOURSELF).toContain('tell them yourself')
    expect(TELL_THEM_YOURSELF).toContain('send them a message')
    expect(TELL_THEM_YOURSELF).not.toMatch(/must|should|please/i)
  })

  it('the server refusal names a consequence and no cause it cannot know', () => {
    // An unreachable server, a refusal, and an answer that never arrived are indistinguishable from
    // this browser. The sentence says what is true of all three instead of picking one.
    expect(LEAVE_SERVER_REFUSED).toContain('Nothing has changed')
    expect(LEAVE_SERVER_REFUSED).toContain('try again')
    expect(LEAVE_SERVER_REFUSED).not.toMatch(/offline|unreachable|server error|timed out|refused the/i)
    // Control: the same search fires on a sentence that does guess at the cause.
    expect('The server is unreachable.').toMatch(/offline|unreachable|server error|timed out|refused the/i)
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
    const all = [
      LEAVE_DOES_NOT_REACH_COPIES,
      LEAVE_IS_NOT_UNDONE,
      LEAVE_TOUCHES_NOTHING_OF_THEIRS,
      TELL_THEM_YOURSELF,
      LEAVE_SERVER_REFUSED,
      NO_KEYS_IN_THIS_BROWSER,
    ].join(' ')
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
      expect(pattern.test(all), name).toBe(false)
    }
  })
})
