/*
 * The snapshot writer and the blob limit (#315). A snapshot is padded before it is encrypted, so
 * near the limit a padded snapshot can be over it where the unpadded one would have fitted. The
 * writer never sends such a snapshot unpadded instead: it refuses before it makes any request, in
 * fixed words that say nothing was sent and give both sizes and the limit. These tests pin the band
 * where that happens, the words, the absence of any request, and — as the positive control for
 * that absence — the request sequence of a snapshot that does fit.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  DEFAULT_MAX_BLOB_BYTES,
  SnapshotTooLargeError,
  SyncClient,
  snapshotRefusedText,
  snapshotTooLargeText,
} from './client'
import { initCrypto, snapshotBlobLength, unpaddedSnapshotBlobLength } from './crypto'

const MiB = 1 << 20

interface Call {
  method: string
  path: string
  bodyLength: number | null
  /** The first five bytes of a binary body: the magic and the format byte of a snapshot. */
  head: number[] | null
}

/** A stand-in for the server's /v1 API that records every request it is sent. */
function fakeServer() {
  const calls: Call[] = []
  const state = { snapshotStatus: 201, keyparams: null as string | null }
  const doFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const path = new URL(String(input)).pathname
    const body = init?.body
    const binary = body instanceof Uint8Array ? body : null
    calls.push({
      method,
      path,
      bodyLength: binary ? binary.length : typeof body === 'string' ? body.length : null,
      head: binary ? Array.from(binary.subarray(0, 5)) : null,
    })
    if (path === '/v1/keydoc' && method === 'GET') {
      return state.keyparams === null
        ? new Response(null, { status: 404 })
        : new Response(state.keyparams, { status: 200, headers: { 'X-Key-Document': 'keyparams', ETag: '"kp"' } })
    }
    if (path === '/v1/snapshots' && method === 'GET') {
      return new Response(JSON.stringify({ lineages: [] }), { status: 200 })
    }
    if (path === '/v1/keyparams' && method === 'PUT') {
      state.keyparams = String(body)
      return new Response(null, { status: 204 })
    }
    if (path.startsWith('/v1/snapshots/') && method === 'PUT') {
      if (state.snapshotStatus !== 201) {
        return new Response(JSON.stringify({ error: 'payload too large' }), { status: state.snapshotStatus })
      }
      const size = binary?.length ?? 0
      return new Response(JSON.stringify({ version: 0, size, contentHash: 'x', createdAt: 1 }), { status: 201 })
    }
    return new Response(null, { status: 500 })
  }) as typeof fetch
  return { calls, state, doFetch }
}

const noFetch = (() => {
  throw new Error('no request was expected')
}) as unknown as typeof fetch

beforeAll(async () => {
  await initCrypto()
})

describe('the band where padding alone takes a snapshot over the default limit', () => {
  it('is every plaintext from 25,690,109 to 26,214,355 bytes', () => {
    expect(DEFAULT_MAX_BLOB_BYTES).toBe(25 * MiB)
    // The largest snapshot that fits padded, and the first that does not.
    expect(snapshotBlobLength(25_690_108)).toBe(25_690_157)
    expect(snapshotBlobLength(25_690_109)).toBe(26_214_445)
    expect(snapshotBlobLength(25_690_108)).toBeLessThanOrEqual(DEFAULT_MAX_BLOB_BYTES)
    expect(snapshotBlobLength(25_690_109)).toBeGreaterThan(DEFAULT_MAX_BLOB_BYTES)
    // From there to the largest snapshot that fits unpadded, only the padding takes it over.
    expect(unpaddedSnapshotBlobLength(25_690_109)).toBe(25_690_154)
    expect(unpaddedSnapshotBlobLength(26_214_355)).toBe(DEFAULT_MAX_BLOB_BYTES)
    expect(unpaddedSnapshotBlobLength(26_214_356)).toBeGreaterThan(DEFAULT_MAX_BLOB_BYTES)
  })
})

describe('the writer refuses such a snapshot plainly, and never sends it unpadded (#315)', () => {
  const server = fakeServer()
  const client = new SyncClient('http://sync.test', 'token', server.doFetch)

  it('the largest snapshot that fits padded is sent, padded (positive control for the requests)', async () => {
    server.calls.length = 0
    server.state.snapshotStatus = 201
    const meta = await client.pushSnapshot('devA', 0, new Uint8Array(25_690_108), 'passphrase')
    expect(server.calls.map((c) => `${c.method} ${c.path}`)).toEqual([
      'GET /v1/keydoc',
      'GET /v1/snapshots',
      'PUT /v1/keyparams',
      'GET /v1/keydoc',
      'PUT /v1/snapshots/devA/0',
    ])
    const put = server.calls[4]!
    expect(put.head).toEqual([0x44, 0x4d, 0x53, 0x31, 0x02]) // "DMS1", format 2
    expect(put.bodyLength).toBe(25_690_157)
    expect(meta.size).toBe(25_690_157)
  }, 60_000)

  it('refuses one byte more before any request, and says padding is what took it over', async () => {
    server.calls.length = 0
    const refused = await client.pushSnapshot('devA', 1, new Uint8Array(25_690_109), 'passphrase').catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(SnapshotTooLargeError)
    // Nothing was sent: not the snapshot, not even a request for the key document.
    expect(server.calls).toEqual([])
    const e = refused as SnapshotTooLargeError
    expect(e.message).toBe(
      'Nothing was sent. Padded, this snapshot is 26,214,445 bytes, and the most this writer sends ' +
        'is 26,214,400. Unpadded it would have been 25,690,154 bytes, but snapshots are always padded ' +
        'before they are encrypted, so that the server learns only roughly how big each one is.',
    )
    expect([e.paddedBytes, e.unpaddedBytes, e.limitBytes]).toEqual([26_214_445, 25_690_154, DEFAULT_MAX_BLOB_BYTES])
  })

  it('refuses a snapshot too large even unpadded in the same words, without blaming the padding', async () => {
    server.calls.length = 0
    const refused = await client.pushSnapshot('devA', 1, new Uint8Array(26_214_356), 'passphrase').catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(SnapshotTooLargeError)
    expect(server.calls).toEqual([])
    expect((refused as Error).message).toBe(
      'Nothing was sent. Padded, this snapshot is 26,214,445 bytes, and the most this writer sends ' +
        'is 26,214,400. Unpadded it would have been 26,214,401 bytes, which is more than that too.',
    )
  })

  it('reports a server that refuses anyway as the server’s answer, with both sizes', async () => {
    server.calls.length = 0
    server.state.snapshotStatus = 413
    const refused = await client
      .pushSnapshot('devA', 1, new TextEncoder().encode('{"hello":"daymark"}'), 'passphrase')
      .catch((e: unknown) => e)
    server.state.snapshotStatus = 201
    expect(refused).toBeInstanceOf(SnapshotTooLargeError)
    const e = refused as SnapshotTooLargeError
    expect(e.status).toBe(413)
    expect(e.limitBytes).toBeNull()
    expect(e.message).toBe(
      'Nothing was stored. The server answered that this snapshot is larger than it accepts. ' +
        'Padded, it is 4,141 bytes; unpadded it would have been 64.',
    )
    expect(server.calls.at(-1)?.bodyLength).toBe(4141)
  }, 60_000)

  it('the refusals are the fixed texts, with only the numbers slotted in', () => {
    expect(snapshotTooLargeText(9, 5, 7)).toBe(
      'Nothing was sent. Padded, this snapshot is 9 bytes, and the most this writer sends is 7. ' +
        'Unpadded it would have been 5 bytes, but snapshots are always padded before they are ' +
        'encrypted, so that the server learns only roughly how big each one is.',
    )
    expect(snapshotRefusedText(1_234_567, 1_000)).toBe(
      'Nothing was stored. The server answered that this snapshot is larger than it accepts. ' +
        'Padded, it is 1,234,567 bytes; unpadded it would have been 1,000.',
    )
  })
})

describe('the limit a writer is given', () => {
  it('a client given its server’s larger limit accepts what the default limit refuses', () => {
    const atDefault = new SyncClient('http://sync.test', 'token', noFetch)
    expect(() => atDefault.assertSnapshotFits(25_690_109)).toThrow(SnapshotTooLargeError)
    const raised = new SyncClient('http://sync.test', 'token', noFetch, { maxBlobBytes: 26_214_445 })
    expect(() => raised.assertSnapshotFits(25_690_109)).not.toThrow()
  })

  it('must be a positive whole number of bytes', () => {
    for (const bad of [0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
      expect(() => new SyncClient('http://sync.test', 'token', noFetch, { maxBlobBytes: bad }), String(bad)).toThrow(RangeError)
    }
    expect(() => new SyncClient('http://sync.test', 'token', noFetch, { maxBlobBytes: 1 })).not.toThrow()
  })
})

describe('the command-line writer', () => {
  const read = (rel: string) => readFileSync(resolve(process.cwd(), rel), 'utf8')
  /** Source with commentary removed: what actually runs. */
  const codeOnly = (src: string) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')
  const PUSH = codeOnly(read('src/cli/push.ts'))

  it('checks the size before its first request, so "Nothing was sent" is true of the whole run', () => {
    const check = PUSH.indexOf('client.assertSnapshotFits(plaintext.length)')
    const firstRequest = PUSH.indexOf('client.listVersions(')
    const push = PUSH.indexOf('client.pushSnapshot(')
    expect(check).toBeGreaterThan(-1)
    expect(firstRequest).toBeGreaterThan(-1)
    expect(push).toBeGreaterThan(-1)
    expect(check).toBeLessThan(firstRequest)
    expect(firstRequest).toBeLessThan(push)
  })

  it('passes the limit it was given to the client', () => {
    expect(PUSH).toContain("arg('max-blob-bytes')")
    expect(PUSH).toContain('new SyncClient(server, token, undefined, { maxBlobBytes })')
  })
})
