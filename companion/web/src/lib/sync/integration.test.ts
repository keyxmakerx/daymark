/*
 * End-to-end sync integration test: boots the real Companion server jar, then drives the
 * full owner path through the HTTP API — encrypt → PUT → list → fetch → decrypt. Proves
 * the server + crypto contract agree, with the server only ever holding opaque bytes.
 *
 * Requires the server fat jar. Set DAYMARK_SERVER_JAR or build it first:
 *   (cd companion/server && ./gradlew shadowJar)
 * The suite skips (with a warning) if the jar is absent, so unit tests still run alone.
 *
 * THE KEY DOCUMENT (#258). The main server's tests run in order against one data directory: the
 * snapshot tests first, under key parameters the first push publishes, then the enrolment that
 * wraps their master, then the tests that read a server holding a wrapped key. A first run needs a
 * server holding no key document at all, so it gets a second, empty server on a free port.
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync } from 'node:fs'
import { createServer, type AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { DEFAULT_MAX_BLOB_BYTES, PASSPHRASE_DOES_NOT_OPEN_KEY, SnapshotTooLargeError, SyncClient, SyncError } from './client'
import { fromBase64 } from './crypto'
import { createRecoverableDataKey, replacePassphrase, unwrapWithPassphrase, zeroizeDataKey } from '../recovery/dataKey'
import { enrolExistingOwner } from '../recovery/migration'

const JAR = process.env.DAYMARK_SERVER_JAR || resolve(process.cwd(), '../server/build/libs/daymark-companion.jar')
const HAVE_JAR = existsSync(JAR)
const PORT = Number(process.env.DAYMARK_TEST_PORT || 18099)
const TOKEN = 'integration-token'
const BASE = `http://127.0.0.1:${PORT}`

if (!HAVE_JAR) {
  console.warn(`[integration] SKIPPED — server jar not found at ${JAR}. Build it with: (cd companion/server && ./gradlew shadowJar)`)
}

/** A port nothing is listening on, for the second server. */
function freePort(): Promise<number> {
  return new Promise((done, fail) => {
    const probe = createServer()
    probe.on('error', fail)
    probe.listen(0, '127.0.0.1', () => {
      const { port } = probe.address() as AddressInfo
      probe.close(() => done(port))
    })
  })
}

/** The jar on `port`, with an empty data directory. */
function startServer(port: number): ChildProcess {
  return spawn('java', ['-jar', JAR], {
    env: {
      ...process.env,
      DAYMARK_PORT: String(port),
      DAYMARK_DATA_DIR: mkdtempSync(join(tmpdir(), 'companion-int-')),
      DAYMARK_AUTH_TOKEN: TOKEN,
      DAYMARK_WEB_DIR: '/nonexistent-web',
      DAYMARK_LOG_LEVEL: 'warn',
      DAYMARK_RATE_LIMIT_RPS: '2000', // the test fires many requests from one IP; don't rate-limit it
    },
    stdio: 'ignore',
  })
}

/** A fetch that records `METHOD /path` for every request, and is otherwise fetch. */
function recordingFetch(seen: string[]): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    seen.push(`${init?.method ?? 'GET'} ${new URL(String(input)).pathname}`)
    return fetch(input, init)
  }) as typeof fetch
}

async function waitForHealth(url: string, timeoutMs = 30000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(url)
      if (res.ok) return
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  throw new Error('server did not become healthy in time')
}

describe.skipIf(!HAVE_JAR)('sync integration (real server)', () => {
  let proc: ChildProcess
  const enc = new TextEncoder()
  const dec = new TextDecoder()
  const PASS = 'a-long-enough-sync-passphrase'

  beforeAll(async () => {
    proc = startServer(PORT)
    await waitForHealth(`${BASE}/healthz`)
  }, 60000)

  afterAll(() => {
    proc?.kill('SIGKILL')
  })

  it('round-trips a backup snapshot through encrypt → PUT → fetch → decrypt', async () => {
    const client = new SyncClient(BASE, TOKEN)
    const backup = JSON.stringify({ version: 12, exportedAt: 1, entries: [{ id: 1, dateTime: 1, moodLevel: 5, note: 'rad' }] })

    const meta = await client.pushSnapshot('devA', 0, enc.encode(backup), PASS)
    expect(meta.version).toBe(0)
    expect(meta.size).toBeGreaterThan(0)

    const pulled = await client.pullLatest('devA', PASS)
    expect(pulled.version).toBe(0)
    expect(dec.decode(pulled.plaintext)).toBe(backup)
  }, 60000)

  it('enforces append-only versions (409 on overwrite)', async () => {
    const client = new SyncClient(BASE, TOKEN)
    await expect(client.pushSnapshot('devA', 0, enc.encode('{}'), PASS)).rejects.toBeInstanceOf(SyncError)
  }, 60000)

  it('picks the latest version across multiple pushes', async () => {
    const client = new SyncClient(BASE, TOKEN)
    await client.pushSnapshot('devB', 0, enc.encode('{"v":0}'), PASS)
    await client.pushSnapshot('devB', 1, enc.encode('{"v":1}'), PASS)
    const pulled = await client.pullLatest('devB', PASS)
    expect(pulled.version).toBe(1)
    expect(dec.decode(pulled.plaintext)).toBe('{"v":1}')
  }, 60000)

  it('the server stores ciphertext, not plaintext (no marker leaks)', async () => {
    const client = new SyncClient(BASE, TOKEN)
    const secret = 'UNIQUE-PLAINTEXT-MARKER-9f3a'
    await client.pushSnapshot('devC', 0, enc.encode(JSON.stringify({ note: secret })), PASS)
    const raw = await client.getBlob('devC', 0) // raw stored bytes
    const asText = Buffer.from(raw).toString('latin1')
    expect(asText.includes(secret)).toBe(false) // plaintext must not appear in the stored blob
  }, 60000)

  it('rejects the wrong passphrase on pull', async () => {
    const client = new SyncClient(BASE, TOKEN)
    await client.pushSnapshot('devD', 0, enc.encode('{"x":1}'), PASS)
    await expect(client.pullLatest('devD', 'WRONG-passphrase')).rejects.toBeTruthy()
  }, 60000)

  it('stores snapshots padded: two lengths in one size bucket are stored at one size (#315)', async () => {
    const client = new SyncClient(BASE, TOKEN)
    const longer = JSON.stringify({ note: 'a'.repeat(3000) })
    const shortMeta = await client.pushSnapshot('devE', 0, enc.encode('{"v":0}'), PASS)
    const longMeta = await client.pushSnapshot('devE', 1, enc.encode(longer), PASS)
    expect(shortMeta.size).toBe(4141) // 29-byte header, the 4096-byte padded body, the 16-byte tag
    expect(longMeta.size).toBe(shortMeta.size)
    const pulled = await client.pullLatest('devE', PASS)
    expect(dec.decode(pulled.plaintext)).toBe(longer)
  }, 60000)

  it('a snapshot that fits only unpadded is never stored, and the writer says why (#315)', async () => {
    const plaintext = new Uint8Array(25_690_109) // padded, one bucket over the default 25 MiB limit
    const atDefault = new SyncClient(BASE, TOKEN)
    const refused = await atDefault.pushSnapshot('devF', 0, plaintext, PASS).catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(SnapshotTooLargeError)
    expect((refused as SnapshotTooLargeError).limitBytes).toBe(DEFAULT_MAX_BLOB_BYTES)
    expect(await atDefault.listVersions('devF')).toEqual([])

    // Told its server accepts more than this one does, the writer sends it, and this server's own
    // limit refuses it: reported as the server's answer, with both sizes.
    const toldMore = new SyncClient(BASE, TOKEN, undefined, { maxBlobBytes: 64 * 1024 * 1024 })
    const answered = await toldMore.pushSnapshot('devF', 0, plaintext, PASS).catch((e: unknown) => e)
    expect(answered).toBeInstanceOf(SnapshotTooLargeError)
    expect((answered as SnapshotTooLargeError).status).toBe(413)
    expect((answered as Error).message).toBe(
      'Nothing was stored. The server answered that this snapshot is larger than it accepts. ' +
        'Padded, it is 26,214,445 bytes; unpadded it would have been 25,690,154.',
    )
    expect(await toldMore.listVersions('devF')).toEqual([])
  }, 120000)

  /* ── The key document (#258). In this order, and after every test above. ───────────────────── */

  it('enrolment: the create names the key parameters by their ETag, and the server then serves the wrapped key', async () => {
    const client = new SyncClient(BASE, TOKEN)
    const read = await client.getKeyDocument()
    expect(read.kind).toBe('keyparams') // published by the first push above
    if (read.kind !== 'keyparams') return
    const { blob, master } = await enrolExistingOwner(PASS, fromBase64(read.params.saltB64), read.params.kdf)
    zeroizeDataKey(master)
    // Neither a first run nor a stale ETag is taken while these key parameters are here.
    expect(await client.createKeyDocument(blob, { kind: 'firstRun' })).toBe('moved')
    expect(await client.createKeyDocument(blob, { kind: 'enrolment', etag: '"0000"' })).toBe('moved')
    expect(await client.createKeyDocument(blob, { kind: 'enrolment', etag: read.etag })).toBe('created')
    const now = await client.getKeyDocument()
    expect(now.kind === 'wrapped' && now.version).toBe(1)
    expect(now.kind === 'wrapped' && now.wrapped).toEqual(blob)
    // The state moved, so the same enrolment a second time is refused.
    expect(await client.createKeyDocument(blob, { kind: 'enrolment', etag: read.etag })).toBe('moved')
  }, 180000)

  it('once a wrapped key exists, /v1/keyparams answers 410, and a push and a pull never ask for it', async () => {
    // Positive control: the server itself refuses the old route now.
    const direct = await fetch(`${BASE}/v1/keyparams`, { headers: { Authorization: `Bearer ${TOKEN}` } })
    expect(direct.status).toBe(410)

    const seen: string[] = []
    await new SyncClient(BASE, TOKEN, recordingFetch(seen)).pushSnapshot('devG', 0, enc.encode('{"after":"enrolment"}'), PASS)
    const pulled = await new SyncClient(BASE, TOKEN, recordingFetch(seen)).pullLatest('devG', PASS)
    expect(dec.decode(pulled.plaintext)).toBe('{"after":"enrolment"}')
    // A snapshot written under the key parameters, before any wrapped key existed, opens through it.
    const old = await new SyncClient(BASE, TOKEN, recordingFetch(seen)).pullLatest('devB', PASS)
    expect(dec.decode(old.plaintext)).toBe('{"v":1}')

    expect(seen).toContain('GET /v1/keydoc')
    expect(seen).toContain('PUT /v1/snapshots/devG/0')
    expect(seen.filter((r) => r.endsWith(' /v1/keyparams'))).toEqual([])
  }, 120000)

  it('a new version is the next number, and then only the new passphrase opens what the server serves', async () => {
    const client = new SyncClient(BASE, TOKEN)
    const read = await client.getKeyDocument()
    expect(read.kind).toBe('wrapped')
    if (read.kind !== 'wrapped') return
    const NEW = 'a-new-passphrase-after-a-recovery'
    const master = await unwrapWithPassphrase(read.wrapped, PASS)
    const next = await replacePassphrase(read.wrapped, master, NEW)
    zeroizeDataKey(master)
    expect(await client.putKeyDocumentVersion(read.version + 2, next)).toBe('moved')
    expect(await client.putKeyDocumentVersion(read.version + 1, next)).toBe('written')
    expect(await client.putKeyDocumentVersion(read.version + 1, next)).toBe('moved')

    expect(dec.decode((await new SyncClient(BASE, TOKEN).pullLatest('devG', NEW)).plaintext)).toBe('{"after":"enrolment"}')
    const refused = await new SyncClient(BASE, TOKEN).pullLatest('devG', PASS).catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(SyncError)
    expect((refused as Error).message).toBe(PASSPHRASE_DOES_NOT_OPEN_KEY)
  }, 180000)
})

describe.skipIf(!HAVE_JAR)('a first run on a server holding no key document (real server)', () => {
  let proc: ChildProcess
  let base = ''
  const enc = new TextEncoder()
  const dec = new TextDecoder()
  const PASS = 'a-first-run-passphrase'

  beforeAll(async () => {
    const port = await freePort()
    base = `http://127.0.0.1:${port}`
    proc = startServer(port)
    await waitForHealth(`${base}/healthz`)
  }, 60000)

  afterAll(() => {
    proc?.kill('SIGKILL')
  })

  it('If-None-Match: * is taken once; then the writer opens the wrapped key and publishes no key parameters', async () => {
    const client = new SyncClient(base, TOKEN)
    expect(await client.getKeyDocument()).toEqual({ kind: 'none' })
    const made = await createRecoverableDataKey(PASS)
    zeroizeDataKey(made.dataKey)
    expect(await client.createKeyDocument(made.blob, { kind: 'firstRun' })).toBe('created')
    expect(await client.createKeyDocument(made.blob, { kind: 'firstRun' })).toBe('moved')
    const read = await client.getKeyDocument()
    expect(read.kind === 'wrapped' && read.wrapped).toEqual(made.blob)

    const seen: string[] = []
    await new SyncClient(base, TOKEN, recordingFetch(seen)).pushSnapshot('first', 0, enc.encode('{"first":"run"}'), PASS)
    const pulled = await new SyncClient(base, TOKEN, recordingFetch(seen)).pullLatest('first', PASS)
    expect(dec.decode(pulled.plaintext)).toBe('{"first":"run"}')
    expect(seen).toEqual([
      'GET /v1/keydoc',
      'PUT /v1/snapshots/first/0',
      'GET /v1/keydoc',
      'GET /v1/snapshots/first',
      'GET /v1/snapshots/first/0',
    ])
  }, 180000)

  it('after a first run the key parameters are refused (410), and a create that names no state answers 428', async () => {
    const put = await fetch(`${base}/v1/keyparams`, {
      method: 'PUT',
      headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
      body: '{"v":1}',
    })
    expect(put.status).toBe(410)
    const bare = await fetch(`${base}/v1/keydoc`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
      body: '{"v":1,"slots":[]}',
    })
    expect(bare.status).toBe(428)
  }, 60000)
})
