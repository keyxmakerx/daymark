/*
 * THE READERS AND THE WRITER TAKE THEIR KEY FROM THE KEY DOCUMENT (#258).
 *
 * docs/SYNC_PROTOCOL.md §2 and §3: `GET /v1/keydoc` answers with the owner's wrapped key when one
 * exists, with the key parameters otherwise, and 404 when neither does. Once a wrapped key exists
 * the server answers `/v1/keyparams` 410, so a reader or writer still asking there would stop at a
 * key the owner has. These tests hold SyncClient to that against a stand-in for the server that
 * keeps the server's rules — create-only key parameters, 410 once a wrapped key exists, creates
 * taken only against the state they name — and records every request, so "nothing asked for
 * /v1/keyparams" is a count over what was actually sent, with a planted request as its control.
 *
 * integration.test.ts holds the same client to the real server.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { createHash } from 'node:crypto'
import {
  KEY_CHANGED_BEFORE_UPLOAD,
  PASSPHRASE_DOES_NOT_OPEN_KEY,
  SNAPSHOTS_WITHOUT_KEY,
  SyncClient,
  SyncError,
  parseKeyDocument,
  type KeyParams,
} from './client'
import { initCrypto, deriveKeys, encryptSnapshot, fromBase64, newSalt, toBase64, DEFAULT_KDF } from './crypto'
import { createRecoverableDataKey, type RecoverableDataKey } from '../recovery/dataKey'
import { enrolExistingOwner, subkeysFromMaster } from '../recovery/migration'

const PASS = 'the passphrase this owner syncs with'
const enc = new TextEncoder()
const dec = new TextDecoder()

const etagOf = (bytes: string) => `"${createHash('sha256').update(bytes).digest('hex')}"`

interface Call {
  method: string
  path: string
  headers: Record<string, string>
}

/**
 * The server's key-document rules, in memory. `before` runs ahead of every request, so a test can
 * make "another writer" act between two of this client's requests.
 */
function fakeServer() {
  const calls: Call[] = []
  const state = {
    keyparams: null as string | null,
    wrapped: [] as string[],
    snapshots: new Map<string, { bytes: Uint8Array; createdAt: number }>(),
    before: null as ((call: Call) => void) | null,
  }
  const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
  const doFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const path = new URL(String(input)).pathname
    const headers: Record<string, string> = {}
    for (const [k, v] of Object.entries((init?.headers ?? {}) as Record<string, string>)) headers[k.toLowerCase()] = v
    const call = { method, path, headers }
    calls.push(call)
    state.before?.(call)
    const body = init?.body
    const newest = state.wrapped.length

    if (path === '/v1/keydoc' && method === 'GET') {
      if (newest > 0) {
        const doc = state.wrapped[newest - 1]!
        return new Response(doc, {
          status: 200,
          headers: { 'X-Key-Document': 'wrapped', 'X-Key-Document-Version': String(newest), ETag: etagOf(doc) },
        })
      }
      if (state.keyparams !== null) {
        return new Response(state.keyparams, { status: 200, headers: { 'X-Key-Document': 'keyparams', ETag: etagOf(state.keyparams) } })
      }
      return json({ error: 'no key document' }, 404)
    }
    if (path === '/v1/keydoc' && method === 'POST') {
      const ifNone = headers['if-none-match']
      const ifMatch = headers['if-match']
      if ((ifNone === undefined) === (ifMatch === undefined)) return json({ error: 'precondition required' }, 428)
      const holds =
        ifNone !== undefined
          ? ifNone === '*' && newest === 0 && state.keyparams === null
          : newest === 0 && state.keyparams !== null && etagOf(state.keyparams) === ifMatch
      if (!holds) return json({ error: 'moved' }, 412)
      state.wrapped.push(String(body))
      return json({ version: 1 }, 201)
    }
    const put = /^\/v1\/keydoc\/(\d+)$/.exec(path)
    if (put && method === 'PUT') {
      const version = Number(put[1])
      if (version < 2) return json({ error: 'a new version is 2 or more' }, 400)
      if (version !== newest + 1 || newest === 0) return json({ error: 'not the next version' }, 409)
      state.wrapped.push(String(body))
      return json({ version }, 201)
    }
    if (path === '/v1/keyparams') {
      if (newest > 0) return json({ error: 'superseded by the wrapped key' }, 410)
      if (method === 'PUT') {
        if (state.keyparams !== null) return json({ error: 'the key parameters already exist' }, 409)
        state.keyparams = String(body)
        return new Response(null, { status: 204 })
      }
      return state.keyparams === null ? json({ error: 'no keyparams' }, 404) : new Response(state.keyparams, { status: 200 })
    }
    if (path === '/v1/snapshots' && method === 'GET') {
      return json({ lineages: [...new Set([...state.snapshots.keys()].map((k) => k.split('/')[0]))] })
    }
    const snap = /^\/v1\/snapshots\/([^/]+)(?:\/(\d+))?$/.exec(path)
    if (snap && !snap[2] && method === 'GET') {
      const versions = [...state.snapshots.entries()]
        .filter(([k]) => k.split('/')[0] === snap[1])
        .map(([k, v]) => ({ version: Number(k.split('/')[1]), size: v.bytes.length, contentHash: 'x', createdAt: v.createdAt }))
      return json({ lineage: snap[1], versions })
    }
    if (snap && snap[2] && method === 'PUT') {
      const key = `${snap[1]}/${snap[2]}`
      if (state.snapshots.has(key)) return json({ error: 'version already exists' }, 409)
      const bytes = body as Uint8Array
      state.snapshots.set(key, { bytes, createdAt: state.snapshots.size + 1 })
      return json({ version: Number(snap[2]), size: bytes.length, contentHash: 'x', createdAt: 1 }, 201)
    }
    if (snap && snap[2] && method === 'GET') {
      const got = state.snapshots.get(`${snap[1]}/${snap[2]}`)
      return got ? new Response(got.bytes as unknown as BodyInit, { status: 200 }) : json({ error: 'not found' }, 404)
    }
    return json({ error: 'not found' }, 404)
  }) as typeof fetch
  return { calls, state, doFetch, client: new SyncClient('http://sync.test', 'token', doFetch) }
}

const requests = (calls: Call[]) => calls.map((c) => `${c.method} ${c.path}`)
const keyparamsJson = (saltB64: string) => JSON.stringify({ v: 1, alg: 'xchacha20poly1305', kdf: DEFAULT_KDF, saltB64 } satisfies KeyParams)

let salt: Uint8Array
let saltB64: string
/** A year of syncing under the old design: the key parameters, and a snapshot under their key. */
let legacySnapshot: Uint8Array
/** That owner's archive enrolled: their master, wrapped under their passphrase and a code. */
let enrolled: Awaited<ReturnType<typeof enrolExistingOwner>>

beforeAll(async () => {
  await initCrypto()
  salt = newSalt()
  saltB64 = toBase64(salt)
  legacySnapshot = encryptSnapshot(enc.encode('{"from":"before the wrapped key"}'), deriveKeys(PASS, salt, DEFAULT_KDF).syncKey, 'laptop', 0)
  enrolled = await enrolExistingOwner(PASS, salt, DEFAULT_KDF)
}, 120_000)

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) Reading the answer, as the kind it says it is.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) the three answers of GET /v1/keydoc', () => {
  it('reads nothing, the key parameters, and the newest wrapped key', async () => {
    const server = fakeServer()
    expect(await server.client.getKeyDocument()).toEqual({ kind: 'none' })

    server.state.keyparams = keyparamsJson(saltB64)
    const kp = await server.client.getKeyDocument()
    expect(kp.kind).toBe('keyparams')
    if (kp.kind === 'keyparams') {
      expect(kp.params.saltB64).toBe(saltB64)
      expect(kp.etag).toBe(etagOf(server.state.keyparams))
    }

    server.state.wrapped.push(JSON.stringify(enrolled.blob))
    const wrapped = await server.client.getKeyDocument()
    expect(wrapped.kind).toBe('wrapped')
    if (wrapped.kind === 'wrapped') {
      expect(wrapped.version).toBe(1)
      expect(wrapped.wrapped).toEqual(enrolled.blob)
    }
  })

  it('keeps the ETag exactly as the server sent it', () => {
    // An enrolment sends it back unchanged, so any tidying here — unquoting, trimming, lowering —
    // would make every enrolment a 412.
    const odd = '"0123456789abcdef-an-ETag-this-client-does-not-parse"'
    const read = parseKeyDocument(new Headers({ 'X-Key-Document': 'keyparams', ETag: odd }), keyparamsJson(saltB64))
    expect(read.kind === 'keyparams' && read.etag).toBe(odd)
  })

  it('refuses a body that is not the kind its header names, and a header it does not know', () => {
    const kp = keyparamsJson(saltB64)
    const wrapped = JSON.stringify(enrolled.blob)
    const headers = (kind: string, extra: Record<string, string> = {}) => new Headers({ 'X-Key-Document': kind, ETag: '"e"', ...extra })
    // Positive controls: each body reads as its own kind.
    expect(parseKeyDocument(headers('keyparams'), kp).kind).toBe('keyparams')
    expect(parseKeyDocument(headers('wrapped', { 'X-Key-Document-Version': '3' }), wrapped).kind).toBe('wrapped')
    // And not as the other, nor under a kind this client does not know.
    expect(() => parseKeyDocument(headers('keyparams'), wrapped)).toThrow(SyncError)
    expect(() => parseKeyDocument(headers('wrapped', { 'X-Key-Document-Version': '3' }), kp)).toThrow(SyncError)
    expect(() => parseKeyDocument(headers('keyparams-v2'), kp)).toThrow(SyncError)
    expect(() => parseKeyDocument(headers('wrapped'), wrapped)).toThrow(/without a version/)
    expect(() => parseKeyDocument(new Headers({ 'X-Key-Document': 'keyparams' }), kp)).toThrow(/without an ETag/)
    expect(() => parseKeyDocument(headers('keyparams'), 'not json')).toThrow(SyncError)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) The pull and the writer reach the master through whichever document is there.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) the pull and the writer take their key from the key document', () => {
  it('key parameters: the key is derived from them, as it always was', async () => {
    const server = fakeServer()
    server.state.keyparams = keyparamsJson(saltB64)
    server.state.snapshots.set('laptop/0', { bytes: legacySnapshot, createdAt: 1 })
    const pulled = await server.client.pullLatest('laptop', PASS)
    expect(dec.decode(pulled.plaintext)).toBe('{"from":"before the wrapped key"}')
    expect(requests(server.calls)).toEqual(['GET /v1/keydoc', 'GET /v1/snapshots/laptop', 'GET /v1/snapshots/laptop/0'])
  }, 60_000)

  it('a wrapped key: the passphrase slot opens the same master, so the old snapshot still opens', async () => {
    // The enrolled document wraps the master the key parameters derive, so a snapshot written
    // under the key parameters a year ago opens through the wrapped key today.
    const server = fakeServer()
    server.state.keyparams = keyparamsJson(saltB64)
    server.state.wrapped.push(JSON.stringify(enrolled.blob))
    server.state.snapshots.set('laptop/0', { bytes: legacySnapshot, createdAt: 1 })
    const pulled = await server.client.pullLatest('laptop', PASS)
    expect(dec.decode(pulled.plaintext)).toBe('{"from":"before the wrapped key"}')
  }, 60_000)

  it('a wrapped key the passphrase does not open is refused in fixed words, before any snapshot is asked for', async () => {
    const server = fakeServer()
    server.state.wrapped.push(JSON.stringify(enrolled.blob))
    server.state.snapshots.set('laptop/0', { bytes: legacySnapshot, createdAt: 1 })
    const wrong = `${PASS} `
    expect(wrong).not.toBe(PASS)
    const refused = await server.client.pullLatest('laptop', wrong).catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(SyncError)
    expect((refused as Error).message).toBe(PASSPHRASE_DOES_NOT_OPEN_KEY)
    expect(requests(server.calls)).toEqual(['GET /v1/keydoc'])
  }, 60_000)

  it('the writer publishes key parameters only when the server holds no key document', async () => {
    const server = fakeServer()
    await server.client.pushSnapshot('laptop', 0, enc.encode('{"v":0}'), PASS)
    // The stored lineages are listed before any key is made, and the key document is read again
    // right before the upload.
    expect(requests(server.calls)).toEqual([
      'GET /v1/keydoc',
      'GET /v1/snapshots',
      'PUT /v1/keyparams',
      'GET /v1/keydoc',
      'PUT /v1/snapshots/laptop/0',
    ])
    // And a second push reads what the first published rather than publishing again.
    server.calls.length = 0
    await server.client.pushSnapshot('laptop', 1, enc.encode('{"v":1}'), PASS)
    expect(requests(server.calls)).toEqual(['GET /v1/keydoc', 'GET /v1/keydoc', 'PUT /v1/snapshots/laptop/1'])
  }, 60_000)

  it('a writer that loses the race to publish (409) reads again and uses the key parameters that won', async () => {
    const server = fakeServer()
    const theirs = toBase64(newSalt())
    // Another writer publishes between this writer's read and its PUT.
    server.state.before = (call) => {
      if (call.method === 'PUT' && call.path === '/v1/keyparams' && server.state.keyparams === null) {
        server.state.keyparams = keyparamsJson(theirs)
      }
    }
    const keys = await server.client.ensureKeys(PASS)
    expect(requests(server.calls)).toEqual(['GET /v1/keydoc', 'GET /v1/snapshots', 'PUT /v1/keyparams', 'GET /v1/keydoc'])
    expect(Buffer.from(keys.syncKey)).toEqual(Buffer.from(deriveKeys(PASS, fromBase64(theirs), DEFAULT_KDF).syncKey))
  }, 60_000)

  it('the same when a wrapped key got there first (410): it opens the wrapped key', async () => {
    const server = fakeServer()
    server.state.before = (call) => {
      if (call.method === 'PUT' && call.path === '/v1/keyparams' && server.state.wrapped.length === 0) {
        server.state.wrapped.push(JSON.stringify(enrolled.blob))
      }
    }
    const keys = await server.client.ensureKeys(PASS)
    expect(requests(server.calls)).toEqual(['GET /v1/keydoc', 'GET /v1/snapshots', 'PUT /v1/keyparams', 'GET /v1/keydoc'])
    expect(Buffer.from(keys.syncKey)).toEqual(Buffer.from(subkeysFromMaster(enrolled.master).syncKey))
  }, 60_000)
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) Once a wrapped key exists, nothing asks for the key parameters.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(c) once a wrapped key exists, nothing asks for /v1/keyparams', () => {
  it('the recorder sees a request to /v1/keyparams, and the server answers it 410 (positive control)', async () => {
    const server = fakeServer()
    server.state.wrapped.push(JSON.stringify(enrolled.blob))
    const res = await server.doFetch('http://sync.test/v1/keyparams', { headers: { Authorization: 'Bearer token' } })
    expect(res.status).toBe(410)
    expect(server.calls.filter((c) => c.path === '/v1/keyparams')).toHaveLength(1)
  })

  it('a push and a pull against a wrapped key send none', async () => {
    const server = fakeServer()
    server.state.keyparams = keyparamsJson(saltB64)
    server.state.wrapped.push(JSON.stringify(enrolled.blob))
    await server.client.pushSnapshot('desk', 0, enc.encode('{"written":"after enrolment"}'), PASS)
    const pulled = await new SyncClient('http://sync.test', 'token', server.doFetch).pullLatest('desk', PASS)
    expect(dec.decode(pulled.plaintext)).toBe('{"written":"after enrolment"}')
    // Non-vacuity: the run did talk to the server, about the key and about snapshots.
    expect(requests(server.calls)).toContain('GET /v1/keydoc')
    expect(requests(server.calls)).toContain('PUT /v1/snapshots/desk/0')
    expect(server.calls.filter((c) => c.path === '/v1/keyparams')).toEqual([])
  }, 60_000)
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (d) Creating and replacing the wrapped key.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(d) a create names the state it was read against, and only that one', () => {
  let doc: RecoverableDataKey
  beforeAll(async () => {
    doc = (await createRecoverableDataKey(PASS)).blob
  }, 60_000)

  it('a first run sends If-None-Match: * and no If-Match', async () => {
    const server = fakeServer()
    expect(await server.client.createKeyDocument(doc, { kind: 'firstRun' })).toBe('created')
    const post = server.calls.find((c) => c.method === 'POST')!
    expect(post.headers['if-none-match']).toBe('*')
    expect(post.headers['if-match']).toBeUndefined()
    expect(JSON.parse(server.state.wrapped[0]!)).toEqual(doc)
  })

  it('an enrolment sends If-Match with the ETag exactly as it was received, and no If-None-Match', async () => {
    const server = fakeServer()
    server.state.keyparams = keyparamsJson(saltB64)
    const read = await server.client.getKeyDocument()
    expect(read.kind).toBe('keyparams')
    const etag = read.kind === 'keyparams' ? read.etag : ''
    expect(await server.client.createKeyDocument(doc, { kind: 'enrolment', etag })).toBe('created')
    const post = server.calls.find((c) => c.method === 'POST')!
    expect(post.headers['if-match']).toBe(etag)
    expect(post.headers['if-none-match']).toBeUndefined()
  })

  it('a state that moved is "moved" (412), and a first run on key parameters is one', async () => {
    const server = fakeServer()
    server.state.keyparams = keyparamsJson(saltB64)
    expect(await server.client.createKeyDocument(doc, { kind: 'firstRun' })).toBe('moved')
    expect(await server.client.createKeyDocument(doc, { kind: 'enrolment', etag: '"not-these"' })).toBe('moved')
    expect(server.state.wrapped).toEqual([])
  })

  it('428 is a fault in this client, and is thrown rather than retried', async () => {
    const server = fakeServer()
    // The only way to reach it is a server that asks anyway; this client always names a state.
    const asks = (async () => new Response('{}', { status: 428 })) as unknown as typeof fetch
    const refused = await new SyncClient('http://sync.test', 'token', asks).createKeyDocument(doc, { kind: 'firstRun' }).catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(SyncError)
    expect((refused as SyncError).status).toBe(428)
    expect(server.calls).toEqual([])
  })

  it('the next version is a PUT to its number, and a version someone else wrote first is "moved" (409)', async () => {
    const server = fakeServer()
    expect(await server.client.createKeyDocument(doc, { kind: 'firstRun' })).toBe('created')
    expect(await server.client.putKeyDocumentVersion(2, doc)).toBe('written')
    expect(await server.client.putKeyDocumentVersion(2, doc)).toBe('moved')
    expect(requests(server.calls).slice(-2)).toEqual(['PUT /v1/keydoc/2', 'PUT /v1/keydoc/2'])
    await expect(server.client.putKeyDocumentVersion(1, doc)).rejects.toThrow(RangeError)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (e) The writer makes no key beside snapshots it cannot open, and uploads under no key the
       server has stopped serving (#258).
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(e) no key parameters beside stored snapshots', () => {
  it('a server that stores snapshots and no key document gets no key parameters, and no snapshot', async () => {
    const server = fakeServer()
    server.state.snapshots.set('laptop/0', { bytes: legacySnapshot, createdAt: 1 })
    const refused = await server.client.pushSnapshot('laptop', 1, enc.encode('{"v":1}'), PASS).catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(SyncError)
    expect((refused as Error).message).toBe(SNAPSHOTS_WITHOUT_KEY)
    // Read, and nothing written: the listing is what told it no.
    expect(requests(server.calls)).toEqual(['GET /v1/keydoc', 'GET /v1/snapshots'])
    expect(server.state.keyparams).toBeNull()
  })

  it('positive control: the same server with no snapshot gets its key parameters and the snapshot', async () => {
    const server = fakeServer()
    await server.client.pushSnapshot('laptop', 0, enc.encode('{"v":0}'), PASS)
    expect(server.state.keyparams).not.toBeNull()
    expect(requests(server.calls)).toContain('PUT /v1/snapshots/laptop/0')
  }, 60_000)
})

describe('(f) the key document is read again right before a snapshot is uploaded', () => {
  let otherPassphrasesMaster: RecoverableDataKey
  let samePassphraseOtherMaster: RecoverableDataKey

  beforeAll(async () => {
    // Two ways the key can move between the writer's read and its upload. An enrolment typed with
    // another passphrase on a server with no snapshot to check it against: the key parameters'
    // salt, a different master. And a lock that opens with the writer's own passphrase, over a
    // master of its own.
    otherPassphrasesMaster = (await enrolExistingOwner('a passphrase the writer does not have', salt, DEFAULT_KDF)).blob
    samePassphraseOtherMaster = (await createRecoverableDataKey(PASS)).blob
  }, 180_000)

  /** A server with the key parameters, whose key document becomes `next` just before the second read. */
  function switchingServer(next: RecoverableDataKey) {
    const server = fakeServer()
    server.state.keyparams = keyparamsJson(saltB64)
    let reads = 0
    server.state.before = (call) => {
      if (call.method === 'GET' && call.path === '/v1/keydoc' && ++reads === 2) server.state.wrapped.push(JSON.stringify(next))
    }
    return server
  }

  it('an enrolment under another passphrase between the two reads: refused, and the snapshot is not sent', async () => {
    const server = switchingServer(otherPassphrasesMaster)
    const refused = await server.client.pushSnapshot('laptop', 0, enc.encode('{"v":0}'), PASS).catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(SyncError)
    expect((refused as Error).message).toBe(KEY_CHANGED_BEFORE_UPLOAD)
    expect(requests(server.calls)).toEqual(['GET /v1/keydoc', 'GET /v1/keydoc'])
    expect(server.state.snapshots.size).toBe(0)
  }, 60_000)

  it('a lock that opens with the same passphrase to another master: refused as well', async () => {
    // Opening is not enough; it has to open to the key the snapshot was encrypted under.
    const server = switchingServer(samePassphraseOtherMaster)
    const refused = await server.client.pushSnapshot('laptop', 0, enc.encode('{"v":0}'), PASS).catch((e: unknown) => e)
    expect((refused as Error).message).toBe(KEY_CHANGED_BEFORE_UPLOAD)
    expect(server.state.snapshots.size).toBe(0)
  }, 60_000)

  it('positive control: an enrolment of the same passphrase between the two reads is the same key, and the snapshot goes', async () => {
    const server = switchingServer(enrolled.blob)
    await server.client.pushSnapshot('laptop', 0, enc.encode('{"v":0}'), PASS)
    expect(requests(server.calls)).toEqual(['GET /v1/keydoc', 'GET /v1/keydoc', 'PUT /v1/snapshots/laptop/0'])
    // And it opens through what the server now serves.
    const pulled = await new SyncClient('http://sync.test', 'token', server.doFetch).pullLatest('laptop', PASS)
    expect(dec.decode(pulled.plaintext)).toBe('{"v":0}')
  }, 60_000)
})
