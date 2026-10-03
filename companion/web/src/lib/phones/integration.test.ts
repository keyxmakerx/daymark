/*
 * PAIRING A PHONE AGAINST THE REAL SERVER (#431): the owner's page, driven through the same ports
 * and transitions the Phones section uses, and a test phone doing what the phone does.
 *
 *   owner  mints a code on a server whose public address is https
 *   phone  makes an Ed25519 key pair and redeems the code with its public key and a signature over
 *          the code's id and that key (auth/DeviceSignature.kt); asks after its registration, signed
 *   owner  sees the code redeemed and the six words of the phone's key, confirms, sees it registered
 *   phone  is registered
 *   owner  lists the phones, disconnects this one, lists again: disconnected
 *   phone  is refused
 *
 * And a server whose public address is http mints nothing, which the section shows as the http state.
 *
 * Requires the server fat jar, as sync/integration.test.ts does: set DAYMARK_SERVER_JAR or build it
 * with `(cd companion/server && ./gradlew shadowJar)`. The suite skips, saying so, without it. Each
 * server gets its own free port and an empty data directory.
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync } from 'node:fs'
import { createServer, type AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { initCrypto } from '../sync/crypto'
import { devicesApi } from './devices'
import { deviceKeyId, deviceWords } from './words'
import {
  CODE_TTL_MS,
  back,
  disconnect,
  initialState,
  loadList,
  mint,
  pairThisPhone,
  phonesView,
  poll,
  type PhonesPorts,
  type PhonesState,
} from './ceremony'

const JAR = process.env.DAYMARK_SERVER_JAR || resolve(process.cwd(), '../server/build/libs/daymark-companion.jar')
const HAVE_JAR = existsSync(JAR)
const TOKEN = 'phones-integration-token'
const HTTPS_ADDRESS = 'https://daymark.example.org'

if (!HAVE_JAR) {
  console.warn(`[phones integration] SKIPPED — server jar not found at ${JAR}. Build it with: (cd companion/server && ./gradlew shadowJar)`)
}

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

function startServer(port: number, publicBaseUrl: string): ChildProcess {
  return spawn('java', ['-jar', JAR], {
    env: {
      ...process.env,
      DAYMARK_PORT: String(port),
      DAYMARK_BIND_ADDR: '127.0.0.1',
      DAYMARK_DATA_DIR: mkdtempSync(join(tmpdir(), 'companion-phones-')),
      DAYMARK_AUTH_TOKEN: TOKEN,
      DAYMARK_PUBLIC_BASE_URL: publicBaseUrl,
      DAYMARK_WEB_DIR: '/nonexistent-web',
      DAYMARK_LOG_LEVEL: 'warn',
      DAYMARK_RATE_LIMIT_RPS: '2000',
    },
    stdio: 'ignore',
  })
}

async function waitForHealth(url: string, timeoutMs = 45_000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      if ((await fetch(url)).ok) return
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  throw new Error('server did not become healthy in time')
}

function portsOn(base: string): PhonesPorts {
  return { api: devicesApi(base, TOKEN), words: deviceWords, keyIdOf: deviceKeyId, now: () => Date.now() }
}

/** The test phone: a fresh Ed25519 key pair, and the two requests a phone makes before it is paired. */
async function testPhone(base: string) {
  const sodium = await initCrypto()
  const variant = sodium.base64_variants.URLSAFE_NO_PADDING
  const b64 = (bytes: Uint8Array) => sodium.to_base64(bytes, variant)
  const pair = sodium.crypto_sign_keypair()
  const publicKey = b64(pair.publicKey)
  const keyId = b64(sodium.crypto_generichash(16, pair.publicKey))

  return {
    publicKey,
    keyId,
    /** The code's id, as the phone works it out: BLAKE2b-256 of the context, LF, and the code. */
    codeIdOf: (code: string) => b64(sodium.crypto_generichash(32, sodium.from_string(`daymark-pairing-code-v1\n${code}`))),
    async redeem(code: string, codeId: string) {
      const proof = sodium.crypto_sign_detached(sodium.from_string(`daymark-pairing-redeem-v1\n${codeId}\n${publicKey}`), pair.privateKey)
      return fetch(`${base}/v1/devices/redeem`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, publicKey, signature: b64(proof) }),
      })
    },
    /** GET /v1/devices/registration, signed as DeviceSignature.requestMessage describes. */
    async registration() {
      const target = '/v1/devices/registration'
      const time = String(Math.floor(Date.now() / 1000))
      const nonce = b64(sodium.randombytes_buf(16))
      const bodyHash = b64(sodium.crypto_generichash(32, new Uint8Array(0)))
      const lines = [
        'daymark-request-v1',
        'GET',
        target,
        bodyHash,
        time,
        nonce,
        'if-match:',
        'if-none-match:',
        'x-rel-token:',
        'x-setting-key:',
        'x-share-meta:',
      ]
      const signature = sodium.crypto_sign_detached(sodium.from_string(lines.join('\n')), pair.privateKey)
      return fetch(base + target, {
        headers: { 'X-Device-Key': keyId, 'X-Device-Time': time, 'X-Device-Nonce': nonce, 'X-Device-Signature': b64(signature) },
      })
    },
  }
}

describe.skipIf(!HAVE_JAR)('pairing a phone against the real server (#431)', () => {
  let https: ChildProcess
  let http: ChildProcess
  let HTTPS_BASE = ''
  let HTTP_BASE = ''

  beforeAll(async () => {
    const [a, b] = [await freePort(), await freePort()]
    HTTPS_BASE = `http://127.0.0.1:${a}`
    HTTP_BASE = `http://127.0.0.1:${b}`
    https = startServer(a, HTTPS_ADDRESS)
    http = startServer(b, HTTP_BASE)
    await Promise.all([waitForHealth(`${HTTPS_BASE}/healthz`), waitForHealth(`${HTTP_BASE}/healthz`)])
  }, 90_000)

  afterAll(() => {
    https?.kill('SIGKILL')
    http?.kill('SIGKILL')
  })

  it('mints, the phone redeems, the words match, the owner confirms, and the phone is registered', async () => {
    const ports = portsOn(HTTPS_BASE)
    const phone = await testPhone(HTTPS_BASE)

    // Ready, with no phone paired yet.
    let s: PhonesState = await loadList(ports, initialState(true))
    expect(s.list).toEqual([])
    expect(phonesView(s, Date.now()).empty).toBe('No phone is paired with this server.')

    // The owner mints: the code, the https address, two minutes.
    s = await mint(ports, s)
    expect(s.pairing.step).toBe('waiting')
    if (s.pairing.step !== 'waiting') return
    const { codeId, code, address, qrText, deadline } = s.pairing
    expect(address).toBe(HTTPS_ADDRESS)
    expect(qrText).toBe(`daymark-pair:v1?server=${encodeURIComponent(HTTPS_ADDRESS)}&code=${code}`)
    expect(deadline - Date.now()).toBeGreaterThan(CODE_TTL_MS - 10_000)
    expect(phone.codeIdOf(code)).toBe(codeId)

    // Nobody has used it: still waiting.
    expect((await poll(ports, s)).pairing.step).toBe('waiting')

    // The phone redeems it, and until the owner confirms, waits.
    const redeemed = await phone.redeem(code, codeId)
    expect(redeemed.status).toBe(202)
    expect((await redeemed.json()).keyId).toBe(phone.keyId)
    expect((await phone.registration()).status).toBe(202)

    // The owner's page sees it redeemed, with the words the phone computes from its own key.
    s = await poll(ports, s)
    expect(s.pairing.step).toBe('compare')
    if (s.pairing.step !== 'compare') return
    expect(s.pairing.words).toEqual(await deviceWords(phone.publicKey))
    expect(s.pairing.keyId).toBe(phone.keyId)
    const words = s.pairing.words

    // The owner confirms: the phone is paired, at the top of the list, and registered on the server.
    s = await pairThisPhone(ports, s)
    expect(s.notice).toBe('paired')
    expect(s.pairing.step).toBe('closed')
    expect(s.list?.[0]).toMatchObject({ keyId: phone.keyId, words, disconnectedAt: null })
    expect(await devicesApi(HTTPS_BASE, TOKEN).codeState(codeId)).toMatchObject({ state: 'registered', keyId: phone.keyId })
    const registered = await phone.registration()
    expect(registered.status).toBe(200)
    expect(await registered.json()).toEqual({ state: 'registered' })

    // The list, read again from the server, says the same.
    s = await loadList(ports, s)
    expect(s.list).toHaveLength(1)
    expect(s.list![0]).toMatchObject({ keyId: phone.keyId, words, disconnectedAt: null })
    const row = phonesView(s, Date.now()).rows![0]!
    expect(row.words).toBe(words.join(' · '))
    expect(row.connected).toBe(true)

    // Disconnect: the row moves to the disconnected phones, and the phone is refused from then on.
    s = await disconnect(ports, s, phone.keyId)
    expect(s.notice).toBe('disconnected')
    expect(s.list![0]!.disconnectedAt).not.toBeNull()
    s = await loadList(ports, s)
    expect(s.list![0]).toMatchObject({ keyId: phone.keyId })
    expect(s.list![0]!.disconnectedAt).not.toBeNull()
    expect(phonesView(s, Date.now()).rows![0]!.connected).toBe(false)
    expect((await phone.registration()).status).toBe(401)
  }, 60_000)

  it('shows a second phone above the first, and a code used by nobody stays waiting', async () => {
    const ports = portsOn(HTTPS_BASE)
    const phone = await testPhone(HTTPS_BASE)
    let s: PhonesState = await mint(ports, await loadList(ports, initialState(true)))
    if (s.pairing.step !== 'waiting') throw new Error('no code')
    expect((await phone.redeem(s.pairing.code, s.pairing.codeId)).status).toBe(202)
    s = await pairThisPhone(ports, await poll(ports, s))
    expect(s.notice).toBe('paired')
    const rows = phonesView(s, Date.now()).rows!
    // Connected first, the newest at the top; the one disconnected above follows.
    expect(rows.map((r) => r.connected)).toEqual([true, false])
    expect(rows[0]!.keyId).toBe(phone.keyId)
  }, 60_000)

  it('a server whose address is http makes no code, and the section says why with no button', async () => {
    const ports = portsOn(HTTP_BASE)
    let s: PhonesState = await loadList(ports, initialState(true))
    expect(s.list).toEqual([])
    s = await mint(ports, s)
    expect(s.httpOnly).toBe(true)
    const v = phonesView(s, Date.now())
    expect(v.lede).toBe(
      "This server's address begins with http://, so it cannot make a pairing code. Phones pair only over https. Everything else here works as it does.",
    )
    expect(v.pairButton).toBeNull()
    expect(v.area).toBeNull()
    // The list still works there.
    expect(v.empty).toBe('No phone is paired with this server.')
    expect(back(s).httpOnly).toBe(true)
  }, 30_000)

  it('a token the server does not accept is refused, in the section\'s own words', async () => {
    const ports: PhonesPorts = { ...portsOn(HTTPS_BASE), api: devicesApi(HTTPS_BASE, 'not-the-token') }
    const s = await loadList(ports, initialState(true))
    expect(s.pairing.step).toBe('refused')
    expect(phonesView(s, Date.now()).area).toMatchObject({
      sentence: 'This server refused the request. Nothing changed. Connect again above, then try once more.',
    })
  }, 30_000)
})
