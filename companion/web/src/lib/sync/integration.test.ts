/*
 * End-to-end sync integration test: boots the real Companion server jar, then drives the
 * full owner path through the HTTP API — encrypt → PUT → list → fetch → decrypt. Proves
 * the server + crypto contract agree, with the server only ever holding opaque bytes.
 *
 * Requires the server fat jar. Set DAYMARK_SERVER_JAR or build it first:
 *   (cd companion/server && ./gradlew shadowJar)
 * The suite skips (with a warning) if the jar is absent, so unit tests still run alone.
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { SyncClient, SyncError } from './client'

const JAR = process.env.DAYMARK_SERVER_JAR || resolve(process.cwd(), '../server/build/libs/daymark-companion.jar')
const HAVE_JAR = existsSync(JAR)
const PORT = Number(process.env.DAYMARK_TEST_PORT || 18099)
const TOKEN = 'integration-token'
const BASE = `http://127.0.0.1:${PORT}`

if (!HAVE_JAR) {
  console.warn(`[integration] SKIPPED — server jar not found at ${JAR}. Build it with: (cd companion/server && ./gradlew shadowJar)`)
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
    const dataDir = mkdtempSync(join(tmpdir(), 'companion-int-'))
    proc = spawn('java', ['-jar', JAR], {
      env: {
        ...process.env,
        DAYMARK_PORT: String(PORT),
        DAYMARK_DATA_DIR: dataDir,
        DAYMARK_AUTH_TOKEN: TOKEN,
        DAYMARK_WEB_DIR: '/nonexistent-web',
        DAYMARK_LOG_LEVEL: 'warn',
        DAYMARK_RATE_LIMIT_RPS: '2000', // the test fires many requests from one IP; don't rate-limit it

      },
      stdio: 'ignore',
    })
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
})
