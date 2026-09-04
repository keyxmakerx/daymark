import { beforeAll, describe, expect, it } from 'vitest'
import { cpaceStart, initCpace } from './cpace'
import { AD_OWNER, channelIdentifier, type OwnerPairingState } from './relay'
import { newPairingCode } from './pairingCode'
import {
  OWNER_RUN_STORAGE_KEY,
  forgetOwnerRun,
  loadOwnerRun,
  ownerRunFromState,
  ownerStateFromRun,
  saveOwnerRun,
  type OwnerRunStorage,
} from './ownerRunStore'

/*
 * The one property that matters here is the negative one: whatever the store writes, the code is
 * not in it. The run is built from a REAL code through the real cpaceStart, serialised, and then
 * grepped for that code in every encoding relay.test.ts checks on the wire, with a planted control
 * so a blind grep cannot pass.
 */

function memoryStorage(): OwnerRunStorage & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return {
    data,
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, v),
    removeItem: (k) => void data.delete(k),
  }
}

function encodingsOf(code: string): string[] {
  const bytes = new TextEncoder().encode(code)
  let raw = ''
  for (const b of bytes) raw += String.fromCharCode(b)
  const std = btoa(raw)
  return [
    code,
    code.toLowerCase(),
    encodeURIComponent(code),
    std,
    std.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''),
    Array.from(bytes)
      .map((b) => b.toString(16).padStart(2, '0'))
      .join(''),
  ]
}

let code: string
let state: OwnerPairingState

beforeAll(async () => {
  await initCpace()
  code = (await newPairingCode()).canonical
  const sid = new Uint8Array(16).map((_, i) => i + 1)
  const start = cpaceStart(
    { prs: new TextEncoder().encode(code), ci: channelIdentifier('rel-1', 'inv-1'), sid },
    AD_OWNER,
  )
  state = {
    exchangeId: 'ex-1',
    inviteId: 'inv-1',
    sidB64: Buffer.from(sid).toString('base64url'),
    start,
  }
})

describe('the stored run', () => {
  it('round-trips the scalar and the opening message byte for byte', () => {
    const storage = memoryStorage()
    saveOwnerRun(storage, ownerRunFromState('rel-1', state, 1_700_000_000_000))
    const loaded = loadOwnerRun(storage)
    expect(loaded).not.toBeNull()
    const restored = ownerStateFromRun(loaded!)
    expect(restored.exchangeId).toBe('ex-1')
    expect(restored.inviteId).toBe('inv-1')
    expect(restored.sidB64).toBe(state.sidB64)
    expect(Buffer.from(restored.start.ya)).toEqual(Buffer.from(state.start.ya))
    expect(Buffer.from(restored.start.msgA)).toEqual(Buffer.from(state.start.msgA))
    expect(restored.finished).toBeUndefined()
    expect(restored.pinnedMsgBB64).toBeUndefined()
  })

  it('carries the pinned reply across a reload, so one run still derives one key', () => {
    const storage = memoryStorage()
    saveOwnerRun(storage, ownerRunFromState('rel-1', { ...state, pinnedMsgBB64: 'reply-bytes' }, 1))
    expect(ownerStateFromRun(loadOwnerRun(storage)!).pinnedMsgBB64).toBe('reply-bytes')
  })

  it('never contains the code, in any encoding — and the detector can see a planted one', () => {
    const storage = memoryStorage()
    saveOwnerRun(storage, ownerRunFromState('rel-1', { ...state, pinnedMsgBB64: 'reply' }, 1))
    const stored = storage.data.get(OWNER_RUN_STORAGE_KEY)!
    expect(stored.length).toBeGreaterThan(100)
    for (const planted of encodingsOf(code)) expect((stored + planted).includes(planted)).toBe(true)
    for (const encoding of encodingsOf(code)) {
      expect(stored.includes(encoding), `code stored as: ${encoding}`).toBe(false)
    }
  })

  it('has no field for the code or the key: the record type cannot hold them', () => {
    const record = ownerRunFromState('rel-1', { ...state, finished: { msgBB64: 'r', isk: new Uint8Array(64).fill(7) } }, 1)
    const keys = Object.keys(record).sort()
    expect(keys).toEqual(['createdAt', 'exchangeId', 'inviteId', 'msgAB64', 'relRef', 'sidB64', 'v', 'yaB64'])
    expect(JSON.stringify(record)).not.toContain(Buffer.from(new Uint8Array(64).fill(7)).toString('base64url'))
  })
})

describe('fails open, refuses what it cannot vouch for', () => {
  it('a missing or refusing storage is a null port, not an error', () => {
    expect(loadOwnerRun(null)).toBeNull()
    expect(() => saveOwnerRun(null, ownerRunFromState('rel-1', state, 1))).not.toThrow()
    expect(() => forgetOwnerRun(null)).not.toThrow()
    const refusing: OwnerRunStorage = {
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
    }
    expect(loadOwnerRun(refusing)).toBeNull()
    expect(() => saveOwnerRun(refusing, ownerRunFromState('rel-1', state, 1))).not.toThrow()
  })

  it('treats anything that is not exactly a v1 record as absent', () => {
    const good = ownerRunFromState('rel-1', state, 1)
    const bad: unknown[] = [
      'not json',
      '[]',
      JSON.stringify({ ...good, v: 2 }),
      JSON.stringify({ ...good, yaB64: undefined }),
      JSON.stringify({ ...good, yaB64: 'AAAA' }), // decodes, wrong length
      JSON.stringify({ ...good, yaB64: '!!!' }),
      JSON.stringify({ ...good, exchangeId: '' }),
      JSON.stringify({ ...good, createdAt: 'yesterday' }),
      JSON.stringify({ ...good, pinnedMsgBB64: 5 }),
    ]
    for (const value of bad) {
      const storage = memoryStorage()
      storage.data.set(OWNER_RUN_STORAGE_KEY, value as string)
      expect(loadOwnerRun(storage), `should refuse: ${value}`).toBeNull()
    }
    // Control: the good record loads through the same path.
    const storage = memoryStorage()
    storage.data.set(OWNER_RUN_STORAGE_KEY, JSON.stringify(good))
    expect(loadOwnerRun(storage)).not.toBeNull()
  })

  it('forgets on request', () => {
    const storage = memoryStorage()
    saveOwnerRun(storage, ownerRunFromState('rel-1', state, 1))
    forgetOwnerRun(storage)
    expect(loadOwnerRun(storage)).toBeNull()
  })
})
