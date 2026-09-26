/*
 * THE OWNER'S KEY ON THE SERVER, AS A SEQUENCE A TEST CAN WATCH (#258).
 *
 * serverKey.ts owns an order: the passphrase is proved before anything is written, a create names
 * the state it was read against, and a read-back is opened before any identity is derived. Each is
 * asserted here over a log the stand-in server writes as it is called, with the identity derivation
 * wrapped (not replaced) so its place in that log is observable too.
 *
 * Every test pays Argon2id at the 256 MiB floor, because dataKey.ts refuses anything below it and a
 * suite that ran under it would be testing a different system. The expensive set-ups run once and
 * several assertions read them.
 */
import { describe, it, expect, beforeAll, vi } from 'vitest'
import _sodium from 'libsodium-wrappers-sumo'

const spy = vi.hoisted(() => ({ log: [] as string[], lockARandomMaster: false }))

vi.mock('../owner/identity', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../owner/identity')>()
  return {
    ...actual,
    ownerIdentityFromMaster: (master: Uint8Array) => {
      spy.log.push('identity')
      return actual.ownerIdentityFromMaster(master)
    },
  }
})

/*
 * For the check before upload: with the flag set, enrolExistingOwner locks a random master instead
 * of the one the passphrase derives — the failure that check exists for — and says nothing.
 */
vi.mock('./migration', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./migration')>()
  const { wrapExistingDataKey, newDataKey } = await import('./dataKey')
  return {
    ...actual,
    enrolExistingOwner: async (...args: Parameters<typeof actual.enrolExistingOwner>) => {
      if (!spy.lockARandomMaster) return actual.enrolExistingOwner(...args)
      const master = await newDataKey()
      return { ...(await wrapExistingDataKey(master, args[0], args[2])), master }
    },
  }
})

import {
  enrolmentCheck,
  replacePassphraseOnServer,
  serverKeyPorts,
  setUp,
  type ServerKeyPorts,
  type SnapshotRef,
} from './serverKey'
import { SyncClient, type CreateAgainst, type KeyDocument, type KeyParams } from '../sync/client'
import { DEFAULT_KDF, decryptSnapshot, deriveKeys, encryptSnapshot, initCrypto, newSalt, toBase64 } from '../sync/crypto'
import { createRecoverableDataKey, unwrapWithPassphrase, unwrapWithRecoveryCode, type RecoverableDataKey } from './dataKey'
import { masterFromPassphrase, subkeysFromMaster } from './migration'
import { ownerIdentityFromMaster } from '../owner/identity'

const PASS = 'the passphrase this archive was written under'
const b64 = (u: Uint8Array) => Buffer.from(u).toString('base64')

interface Stored {
  ref: SnapshotRef
  bytes: Uint8Array
  createdAt: number
}

/**
 * The server's key-document rules over ports, logging each call into the shared log. `beforeCreate`
 * runs just ahead of a create's check, so "another device" can act between a read and a create.
 */
function fakeServer(init: { keyparams?: KeyParams; wrapped?: RecoverableDataKey[]; snapshots?: Stored[] } = {}) {
  const state = {
    keyparams: init.keyparams ?? null,
    wrapped: [...(init.wrapped ?? [])],
    snapshots: [...(init.snapshots ?? [])],
    beforeCreate: null as (() => void) | null,
    /** When set, a read after a create hands back this instead of what was stored. */
    readBackAs: null as KeyDocument | null,
    created: [] as { wrapped: RecoverableDataKey; against: CreateAgainst }[],
  }
  const keyparamsEtag = '"etag of these key parameters: 7f"'
  const current = (): KeyDocument => {
    const n = state.wrapped.length
    if (n > 0) return { kind: 'wrapped', wrapped: state.wrapped[n - 1]!, version: n, etag: `"v${n}"` }
    if (state.keyparams) return { kind: 'keyparams', params: state.keyparams, etag: keyparamsEtag }
    return { kind: 'none' }
  }
  const ports: ServerKeyPorts = {
    async read() {
      spy.log.push('read')
      return state.readBackAs && state.created.length > 0 ? state.readBackAs : current()
    },
    async create(wrapped, against) {
      spy.log.push(against.kind === 'firstRun' ? 'create firstRun' : `create enrolment ${against.etag}`)
      state.beforeCreate?.()
      const holds =
        against.kind === 'firstRun'
          ? state.keyparams === null && state.wrapped.length === 0
          : state.wrapped.length === 0 && state.keyparams !== null && against.etag === keyparamsEtag
      if (!holds) return 'moved'
      state.created.push({ wrapped, against })
      state.wrapped.push(wrapped)
      return 'created'
    },
    async replace(version, wrapped) {
      spy.log.push(`replace ${version}`)
      if (state.wrapped.length === 0 || version !== state.wrapped.length + 1) return 'moved'
      state.wrapped.push(wrapped)
      return 'written'
    },
    async newestSnapshot() {
      spy.log.push('newestSnapshot')
      const newest = [...state.snapshots].sort((a, b) => b.createdAt - a.createdAt)[0]
      return newest ? newest.ref : null
    },
    async snapshot(ref) {
      spy.log.push(`snapshot ${ref.lineage}/${ref.version}`)
      return state.snapshots.find((s) => s.ref.lineage === ref.lineage && s.ref.version === ref.version)!.bytes
    },
  }
  return { state, ports, keyparamsEtag }
}

let salt: Uint8Array
let keyparams: KeyParams
/** A snapshot written a year ago under the key parameters, the anchor's subject. */
let archived: Stored
/** The master those key parameters and PASS derive: the one an enrolment must lock. */
let direct: Uint8Array

beforeAll(async () => {
  await initCrypto()
  salt = newSalt()
  keyparams = { v: 1, alg: 'xchacha20poly1305', kdf: DEFAULT_KDF, saltB64: toBase64(salt) }
  archived = {
    ref: { lineage: 'laptop', version: 41 },
    bytes: encryptSnapshot(new TextEncoder().encode('{"a":"year"}'), deriveKeys(PASS, salt, DEFAULT_KDF).syncKey, 'laptop', 41),
    createdAt: 1_000,
  }
  direct = await masterFromPassphrase(PASS, salt, DEFAULT_KDF)
}, 120_000)

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) Nothing on the server: a first run.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) a server holding nothing: a first run', () => {
  let server: ReturnType<typeof fakeServer>
  let result: Awaited<ReturnType<typeof setUp>>
  let log: string[]

  beforeAll(async () => {
    server = fakeServer()
    spy.log.length = 0
    result = await setUp(server.ports, { kind: 'none' }, PASS, PASS)
    log = [...spy.log]
  }, 120_000)

  it('creates with If-None-Match: *, reads back, and derives the identity only after the read-back', () => {
    expect(result.kind).toBe('stored')
    expect(log).toEqual(['newestSnapshot', 'create firstRun', 'read', 'identity'])
  })

  it('stores a document both secrets open to the one master whose identity came back', async () => {
    if (result.kind !== 'stored') throw new Error('not stored')
    const stored = server.state.wrapped[0]!
    const viaPass = await unwrapWithPassphrase(stored, PASS)
    const viaCode = await unwrapWithRecoveryCode(stored, result.recoveryCode.canonical)
    expect(b64(viaCode)).toBe(b64(viaPass))
    expect(b64(ownerIdentityFromMaster(viaPass).ed25519.publicKey)).toBe(b64(result.identity.ed25519.publicKey))
    expect(b64(ownerIdentityFromMaster(viaPass).x25519.publicKey)).toBe(b64(result.identity.x25519.publicKey))
  }, 60_000)

  it('asks for the passphrase twice: once, or two different ones, writes nothing', async () => {
    const s = fakeServer()
    spy.log.length = 0
    expect(await setUp(s.ports, { kind: 'none' }, PASS, null)).toEqual({ kind: 'refused', fault: 'typeItTwice' })
    const other = `${PASS}.`
    expect(other).not.toBe(PASS)
    expect(await setUp(s.ports, { kind: 'none' }, PASS, other)).toEqual({ kind: 'refused', fault: 'passphrasesDiffer' })
    expect(await setUp(s.ports, { kind: 'none' }, '', '')).toEqual({ kind: 'refused', fault: 'noPassphrase' })
    expect(spy.log.filter((l) => l.startsWith('create'))).toEqual([])
    expect(s.state.wrapped).toEqual([])
  })

  it('makes no new master on a server that stores snapshots and no key document', async () => {
    // A master made here would open none of them. The server can show nothing but its answer, so
    // the console refuses rather than lock a key that cannot read what is already there.
    const s = fakeServer({ snapshots: [archived] })
    spy.log.length = 0
    expect(await setUp(s.ports, { kind: 'none' }, PASS, PASS)).toEqual({ kind: 'refused', fault: 'snapshotsWithoutKey' })
    expect(spy.log).toEqual(['newestSnapshot'])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) Key parameters and no locked key: an enrolment.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) key parameters only: an enrolment', () => {
  let server: ReturnType<typeof fakeServer>
  let result: Awaited<ReturnType<typeof setUp>>
  let log: string[]

  beforeAll(async () => {
    server = fakeServer({ keyparams, snapshots: [archived] })
    spy.log.length = 0
    result = await setUp(server.ports, { kind: 'keyparams', params: keyparams, etag: server.keyparamsEtag }, PASS, null)
    log = [...spy.log]
  }, 180_000)

  it('proves the passphrase on the newest snapshot, then names the key parameters it read: If-Match, exactly as received', () => {
    expect(result.kind).toBe('stored')
    expect(log).toEqual([
      'newestSnapshot',
      'snapshot laptop/41',
      `create enrolment ${server.keyparamsEtag}`,
      'read',
      'identity',
    ])
    expect(server.state.created.map((c) => c.against)).toEqual([{ kind: 'enrolment', etag: server.keyparamsEtag }])
  })

  it('locks the master the passphrase already derives, never a random one', async () => {
    if (result.kind !== 'stored') throw new Error('not stored')
    const stored = server.state.wrapped[0]!
    expect(b64(await unwrapWithPassphrase(stored, PASS))).toBe(b64(direct))
    expect(b64(await unwrapWithRecoveryCode(stored, result.recoveryCode.canonical))).toBe(b64(direct))
    // So the identity is the one the archive already had, and a clinician sees no stranger.
    expect(b64(result.identity.ed25519.publicKey)).toBe(b64(ownerIdentityFromMaster(direct).ed25519.publicKey))
  }, 60_000)

  it('the anchor: a passphrase that does not open the newest snapshot is refused, and nothing is written', async () => {
    const s = fakeServer({ keyparams, snapshots: [archived] })
    const wrong = `${PASS}!`
    expect(wrong).not.toBe(PASS)
    spy.log.length = 0
    const out = await setUp(s.ports, { kind: 'keyparams', params: keyparams, etag: s.keyparamsEtag }, wrong, null)
    expect(out).toEqual({ kind: 'refused', fault: 'doesNotOpenNewest' })
    // Non-vacuity: the anchor did look at the snapshot, and then nothing was sent.
    expect(spy.log).toEqual(['newestSnapshot', 'snapshot laptop/41'])
    expect(s.state.wrapped).toEqual([])
  }, 60_000)

  it('the anchor is the newest snapshot of all, across lineages', async () => {
    const older: Stored = { ...archived, ref: { lineage: 'desk', version: 3 }, createdAt: 10 }
    const s = fakeServer({ keyparams, snapshots: [older, archived] })
    spy.log.length = 0
    await setUp(s.ports, { kind: 'keyparams', params: keyparams, etag: s.keyparamsEtag }, `${PASS}?`, null)
    expect(spy.log).toContain('snapshot laptop/41')
    expect(spy.log).not.toContain('snapshot desk/3')
  }, 60_000)

  it('with no snapshot to prove it on, the passphrase is asked for twice before anything is written', async () => {
    const s = fakeServer({ keyparams })
    const held: KeyDocument = { kind: 'keyparams', params: keyparams, etag: s.keyparamsEtag }
    expect(await enrolmentCheck(s.ports)).toBe('typedTwice')
    expect(await enrolmentCheck(fakeServer({ keyparams, snapshots: [archived] }).ports)).toBe('newestSnapshot')

    spy.log.length = 0
    expect(await setUp(s.ports, held, PASS, null)).toEqual({ kind: 'refused', fault: 'typeItTwice' })
    expect(await setUp(s.ports, held, PASS, `${PASS}.`)).toEqual({ kind: 'refused', fault: 'passphrasesDiffer' })
    expect(spy.log.filter((l) => l.startsWith('create'))).toEqual([])

    const out = await setUp(s.ports, held, PASS, PASS)
    expect(out.kind).toBe('stored')
    expect(b64(await unwrapWithPassphrase(s.state.wrapped[0]!, PASS))).toBe(b64(direct))
  }, 180_000)

  it('a document that does not open to the master the passphrase derives is refused before upload', async () => {
    const s = fakeServer({ keyparams, snapshots: [archived] })
    spy.log.length = 0
    spy.lockARandomMaster = true
    try {
      const out = await setUp(s.ports, { kind: 'keyparams', params: keyparams, etag: s.keyparamsEtag }, PASS, null)
      expect(out).toEqual({ kind: 'refused', fault: 'selfCheckFailed' })
    } finally {
      spy.lockARandomMaster = false
    }
    expect(spy.log.filter((l) => l.startsWith('create'))).toEqual([])
    expect(s.state.wrapped).toEqual([])
  }, 120_000)
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) A locked key already there, and a state that moves.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(c) a locked key, and a server whose state moved after it was read', () => {
  let locked: RecoverableDataKey

  beforeAll(async () => {
    locked = (await createRecoverableDataKey('another device chose this one')).blob
  }, 60_000)

  it('a set-up on a server holding a locked key writes nothing: that is an unlock', async () => {
    const s = fakeServer({ wrapped: [locked] })
    spy.log.length = 0
    const held: KeyDocument = { kind: 'wrapped', wrapped: locked, version: 1, etag: '"v1"' }
    expect(await setUp(s.ports, held, PASS, PASS)).toEqual({ kind: 'refused', fault: 'alreadyLocked' })
    expect(spy.log).toEqual([])
  })

  it('a first run another device beat (412) reads again and hands back what is there, having stored nothing', async () => {
    const s = fakeServer()
    s.state.beforeCreate = () => {
      if (s.state.wrapped.length === 0) s.state.wrapped.push(locked)
    }
    spy.log.length = 0
    const out = await setUp(s.ports, { kind: 'none' }, PASS, PASS)
    expect(out).toEqual({ kind: 'moved', now: { kind: 'wrapped', wrapped: locked, version: 1, etag: '"v1"' } })
    expect(spy.log).toEqual(['newestSnapshot', 'create firstRun', 'read'])
    expect(s.state.wrapped).toEqual([locked])
  }, 60_000)

  it('an enrolment whose key parameters were enrolled meanwhile (412) does the same', async () => {
    const s = fakeServer({ keyparams, snapshots: [archived] })
    s.state.beforeCreate = () => {
      if (s.state.wrapped.length === 0) s.state.wrapped.push(locked)
    }
    spy.log.length = 0
    const out = await setUp(s.ports, { kind: 'keyparams', params: keyparams, etag: s.keyparamsEtag }, PASS, null)
    expect(out.kind).toBe('moved')
    expect(out.kind === 'moved' && out.now.kind).toBe('wrapped')
    expect(spy.log.slice(-2)).toEqual([`create enrolment ${s.keyparamsEtag}`, 'read'])
    expect(spy.log).not.toContain('identity')
  }, 180_000)

  it('a create the server took but hands back as something else is "unchecked", and no identity is derived', async () => {
    const s = fakeServer()
    s.state.readBackAs = { kind: 'wrapped', wrapped: locked, version: 1, etag: '"v1"' }
    spy.log.length = 0
    expect(await setUp(s.ports, { kind: 'none' }, PASS, PASS)).toEqual({ kind: 'unchecked' })
    expect(spy.log).toEqual(['newestSnapshot', 'create firstRun', 'read'])
  }, 120_000)
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (d) A new passphrase after the recovery code opened the key.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(d) a new passphrase, stored as the next version', () => {
  const NEW = 'a passphrase chosen after a recovery'
  let made: Awaited<ReturnType<typeof createRecoverableDataKey>>

  beforeAll(async () => {
    made = await createRecoverableDataKey(PASS)
  }, 60_000)

  it('writes version 2, reads it back, and the new passphrase opens the same master; the code still does', async () => {
    const s = fakeServer({ wrapped: [made.blob] })
    const held = { kind: 'wrapped' as const, wrapped: made.blob, version: 1, etag: '"v1"' }
    spy.log.length = 0
    expect(await replacePassphraseOnServer(s.ports, held, made.dataKey, NEW, NEW)).toEqual({ kind: 'written' })
    expect(spy.log).toEqual(['replace 2', 'read'])
    const stored = s.state.wrapped[1]!
    expect(b64(await unwrapWithPassphrase(stored, NEW))).toBe(b64(made.dataKey))
    expect(b64(await unwrapWithRecoveryCode(stored, made.recoveryCode.canonical))).toBe(b64(made.dataKey))
    await expect(unwrapWithPassphrase(stored, PASS)).rejects.toThrow()
  }, 120_000)

  it('a version another device wrote first (409) reads again and hands back what is there, having stored nothing', async () => {
    const s = fakeServer({ wrapped: [made.blob, made.blob] })
    const stale = { kind: 'wrapped' as const, wrapped: made.blob, version: 1, etag: '"v1"' }
    spy.log.length = 0
    const out = await replacePassphraseOnServer(s.ports, stale, made.dataKey, NEW, NEW)
    expect(out.kind).toBe('moved')
    expect(out.kind === 'moved' && out.now.kind === 'wrapped' && out.now.version).toBe(2)
    expect(spy.log).toEqual(['replace 2', 'read'])
    expect(s.state.wrapped).toHaveLength(2)
  }, 60_000)

  it('two different new passphrases, or none, write nothing', async () => {
    const s = fakeServer({ wrapped: [made.blob] })
    const held = { kind: 'wrapped' as const, wrapped: made.blob, version: 1, etag: '"v1"' }
    spy.log.length = 0
    expect(await replacePassphraseOnServer(s.ports, held, made.dataKey, NEW, `${NEW} `)).toEqual({ kind: 'refused', fault: 'passphrasesDiffer' })
    expect(await replacePassphraseOnServer(s.ports, held, made.dataKey, '', '')).toEqual({ kind: 'refused', fault: 'noPassphrase' })
    expect(spy.log).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (e) The ports, over the sync client.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(e) the ports over the sync client', () => {
  it('finds the newest snapshot by when it was stored, across lineages, and asks again after a 429', async () => {
    const asked: string[] = []
    let refusedOnce = false
    const doFetch = (async (input: RequestInfo | URL) => {
      const path = new URL(String(input)).pathname
      asked.push(path)
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
      if (path === '/v1/snapshots') return json({ lineages: ['desk', 'laptop'] })
      if (path === '/v1/snapshots/desk') {
        if (!refusedOnce) {
          refusedOnce = true
          return json({ error: 'rate limited' }, 429)
        }
        return json({ versions: [{ version: 9, size: 1, contentHash: 'x', createdAt: 500 }] })
      }
      if (path === '/v1/snapshots/laptop') {
        return json({ versions: [{ version: 2, size: 1, contentHash: 'x', createdAt: 900 }, { version: 1, size: 1, contentHash: 'x', createdAt: 100 }] })
      }
      return json({ error: 'not found' }, 404)
    }) as typeof fetch
    const waits: number[] = []
    const ports = serverKeyPorts(new SyncClient('http://sync.test', 'token', doFetch), async (ms) => void waits.push(ms))
    expect(await ports.newestSnapshot()).toEqual({ lineage: 'laptop', version: 2 })
    expect(asked).toEqual(['/v1/snapshots', '/v1/snapshots/desk', '/v1/snapshots/desk', '/v1/snapshots/laptop'])
    expect(waits).toHaveLength(1)
  })

  it('a server storing no snapshot has no newest one', async () => {
    const doFetch = (async () => new Response(JSON.stringify({ lineages: [] }), { status: 200 })) as unknown as typeof fetch
    expect(await serverKeyPorts(new SyncClient('http://sync.test', 'token', doFetch)).newestSnapshot()).toBeNull()
  })

  it('keeps asking for no longer than five times', async () => {
    let n = 0
    const doFetch = (async () => {
      n += 1
      return new Response('{}', { status: 429 })
    }) as unknown as typeof fetch
    const ports = serverKeyPorts(new SyncClient('http://sync.test', 'token', doFetch), async () => {})
    await expect(ports.newestSnapshot()).rejects.toThrow()
    expect(n).toBe(5)
  })
})

describe('the fixture is what it claims', () => {
  it('the archived snapshot opens under the master of PASS, and not under another master', () => {
    // The anchor tests are only as good as their subject: a snapshot that opened under any key, or
    // under none, would make "refused" and "stored" mean nothing.
    const opened = decryptSnapshot(archived.bytes, subkeysFromMaster(direct).syncKey, 'laptop', 41)
    expect(new TextDecoder().decode(opened)).toBe('{"a":"year"}')
    const other = _sodium.randombytes_buf(32)
    expect(b64(other)).not.toBe(b64(direct))
    expect(() => decryptSnapshot(archived.bytes, subkeysFromMaster(other).syncKey, 'laptop', 41)).toThrow()
  })
})
