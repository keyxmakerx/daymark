/*
 * THE PHONES SECTION, DRIVEN IN CHROMIUM AGAINST THE REAL SERVER (#431).
 *
 * The section's states, order and words are node tests (lib/phones), and none of them renders the
 * component or reaches a server from a page. This drives it the way the owner would, with a test
 * phone doing what the phone does:
 *
 *   owner   opens "Connect to your sync server": the Phones section says to connect above
 *   owner   types the access token and presses the button: the server has nothing synced, the
 *           phone list proves the token, and the section is ready, with no phone paired
 *   owner   "Pair a phone": the QR code, the https address, the code and its clock
 *   phone   redeems the code with a fresh Ed25519 key and a signed proof
 *   owner   the six words appear, and are the phone's; "The words don't match" asks the server
 *           nothing, and stops the poll
 *   owner   "Make a new code"; a second phone redeems it; "Pair this phone": paired, at the top
 *   owner   "Disconnect", the clay confirm, "Disconnect this phone": disconnected
 *   owner   a code the server says lapsed, a confirm it says came too late, a server that cannot
 *           be reached (and "Try again" bringing the code back), and a refusal whose body carries
 *           a planted sentence that never reaches the page (these four answers are the server's,
 *           played by the browser's request routing; the page is the real one)
 *   owner   a second server whose address is http: "Pair a phone" gives the http sentence, and no
 *           button
 *
 * RUN IT (not part of `pnpm test`):   cd companion/web && pnpm e2e:phones
 * It builds the server jar (gradle skips it when nothing changed) and this package's bundle into a
 * temporary directory, and starts two servers on 127.0.0.1 with empty data directories. Chromium is
 * the one preinstalled under PLAYWRIGHT_BROWSERS_PATH (default /opt/pw-browsers); CHROMIUM_PATH
 * overrides it. Nothing is downloaded. E2E_SHOTS=<dir> keeps one screenshot of the sync card per
 * state, named by the state.
 *
 * CREDENTIALS. The owner token is made up per run and scrubbed from anything printed on failure.
 */
import { afterAll, beforeAll, expect, it } from 'vitest'
import { chromium, type Browser, type Locator, type Page } from 'playwright-core'
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { createRequire } from 'node:module'
import { createServer, type AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as COPY from '../src/lib/phones/copy'
import { deviceWords } from '../src/lib/phones/words'
import { pairingQrText } from '../src/lib/phones/ceremony'
import { qrDrawing } from '../src/lib/phones/qr'
import { initCrypto } from '../src/lib/sync/crypto'

const WEB = fileURLToPath(new URL('..', import.meta.url))
const SERVER = join(WEB, '..', 'server')
const JAR = join(SERVER, 'build', 'libs', 'daymark-companion.jar')
const OWNER_TOKEN = `e2e-owner-${randomBytes(16).toString('hex')}`
const HTTPS_ADDRESS = 'https://daymark.example.org'
const PLANTED = 'Planted server sentence 4c1d'

const redact = (text: string) => text.split(OWNER_TOKEN).join('[REDACTED]')
const log = (line: string) => console.log(`[phones-walk] ${redact(line)}`)
const sleep = (ms: number) => new Promise((r) => setTimeout(r, Math.max(0, ms)))

function build(cmd: string, args: string[], cwd: string, what: string) {
  const r = spawnSync(cmd, args, { cwd, encoding: 'utf8', env: process.env, maxBuffer: 64 * 1024 * 1024 })
  if (r.status !== 0) throw new Error(`${what} failed (exit ${r.status}):\n${`${r.stdout ?? ''}\n${r.stderr ?? ''}`.slice(-3000)}`)
}

function freePort(): Promise<number> {
  return new Promise((done, fail) => {
    const s = createServer()
    s.on('error', fail)
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address() as AddressInfo
      s.close(() => done(port))
    })
  })
}

/** The preinstalled Chromium at the revision playwright-core was built for, or the newest there. */
function chromiumPath(): string {
  if (process.env.CHROMIUM_PATH) return process.env.CHROMIUM_PATH
  const base = process.env.PLAYWRIGHT_BROWSERS_PATH || '/opt/pw-browsers'
  const pkg = createRequire(import.meta.url).resolve('playwright-core/package.json')
  const listed = JSON.parse(readFileSync(join(dirname(pkg), 'browsers.json'), 'utf8')) as { browsers: { name: string; revision: string }[] }
  const pinned = listed.browsers.find((b) => b.name === 'chromium')?.revision
  const candidates = existsSync(base)
    ? readdirSync(base)
        .filter((d) => /^chromium-\d+$/.test(d))
        .sort((a, b) => (a === `chromium-${pinned}` ? -1 : b === `chromium-${pinned}` ? 1 : Number(b.slice(9)) - Number(a.slice(9))))
        .map((d) => join(base, d, 'chrome-linux', 'chrome'))
        .filter((p) => existsSync(p))
    : []
  if (!candidates.length) throw new Error(`No Chromium under ${base}. Set CHROMIUM_PATH to a chrome binary; nothing is downloaded.`)
  return candidates[0]!
}

async function until<T>(what: string, check: () => Promise<T | null | undefined | false>, timeoutMs = 15_000, everyMs = 250): Promise<T> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const got = await check().catch(() => null)
    if (got) return got
    if (Date.now() > deadline) throw new Error(`gave up after ${Math.round(timeoutMs / 1000)}s waiting for ${what}`)
    await sleep(everyMs)
  }
}

/** A test phone: a fresh Ed25519 key pair, and the redemption a phone makes. */
async function testPhone(base: string) {
  const sodium = await initCrypto()
  const variant = sodium.base64_variants.URLSAFE_NO_PADDING
  const b64 = (bytes: Uint8Array) => sodium.to_base64(bytes, variant)
  const pair = sodium.crypto_sign_keypair()
  const publicKey = b64(pair.publicKey)
  return {
    publicKey,
    async redeem(code: string): Promise<number> {
      const codeId = b64(sodium.crypto_generichash(32, sodium.from_string(`daymark-pairing-code-v1\n${code}`)))
      const proof = sodium.crypto_sign_detached(sodium.from_string(`daymark-pairing-redeem-v1\n${codeId}\n${publicKey}`), pair.privateKey)
      const res = await fetch(`${base}/v1/devices/redeem`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, publicKey, signature: b64(proof) }),
      })
      return res.status
    },
  }
}

// ── the run's state ───────────────────────────────────────────────────────────────────────────────
let scratch = ''
let distDir = ''
const servers: { proc: ChildProcess; log: string }[] = []
let HTTPS_BASE = ''
let HTTP_BASE = ''
let browser: Browser | null = null
const pageErrors: string[] = []

async function startServer(port: number, publicBaseUrl: string): Promise<string> {
  const base = `http://127.0.0.1:${port}`
  const dataDir = join(scratch, `data-${port}`)
  const serverLog = join(scratch, `server-${port}.log`)
  mkdirSync(dataDir)
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) if (v !== undefined && !k.startsWith('DAYMARK_')) env[k] = v
  const fd = openSync(serverLog, 'a')
  const proc = spawn('java', ['-jar', JAR], {
    env: {
      ...env,
      DAYMARK_BIND_ADDR: '127.0.0.1',
      DAYMARK_PORT: String(port),
      DAYMARK_DATA_DIR: dataDir,
      DAYMARK_WEB_DIR: distDir,
      DAYMARK_AUTH_TOKEN: OWNER_TOKEN,
      // Solo: the shape where the Phones section is the one place a phone pairs.
      DAYMARK_SETUP_MODE: 'solo',
      DAYMARK_THERAPIST_AUTH: '',
      DAYMARK_PUBLIC_BASE_URL: publicBaseUrl,
      DAYMARK_LOG_LEVEL: 'warn',
    },
    stdio: ['ignore', fd, fd],
  })
  closeSync(fd)
  servers.push({ proc, log: serverLog })
  const deadline = Date.now() + 60_000
  for (;;) {
    if (proc.exitCode !== null || Date.now() > deadline) {
      throw new Error(redact(`the server on ${port} did not start:\n${readFileSync(serverLog, 'utf8').slice(-2000)}`))
    }
    if (await fetch(`${base}/healthz`).then((r) => r.ok, () => false)) return base
    await sleep(300)
  }
}

beforeAll(async () => {
  scratch = mkdtempSync(join(tmpdir(), 'daymark-phones-e2e-'))
  distDir = join(scratch, 'dist')
  log('building the server jar')
  build(join(SERVER, 'gradlew'), ['shadowJar', '--console=plain', '-q'], SERVER, 'the server jar build')
  log('building the web bundle')
  build(process.execPath, [join(WEB, 'node_modules', 'vite', 'bin', 'vite.js'), 'build', '--outDir', distDir, '--emptyOutDir', '--logLevel', 'warn'], WEB, 'the web build')
  HTTPS_BASE = await startServer(await freePort(), HTTPS_ADDRESS)
  const httpPort = await freePort()
  HTTP_BASE = await startServer(httpPort, `http://127.0.0.1:${httpPort}`)
  log(`servers up: ${HTTPS_BASE} (public address ${HTTPS_ADDRESS}), ${HTTP_BASE} (http)`)
  browser = await chromium.launch({ executablePath: chromiumPath(), args: ['--no-sandbox'] })
})

afterAll(async () => {
  await browser?.close().catch(() => {})
  for (const { proc } of servers) if (proc.exitCode === null) proc.kill('SIGKILL')
  if (scratch) rmSync(scratch, { recursive: true, force: true })
})

/** A page on [base], on the sync card, with its Phones section. */
async function openSyncCard(base: string): Promise<{ page: Page; card: Locator; phones: Locator; requests: string[] }> {
  const ctx = await browser!.newContext({ viewport: { width: 1100, height: 1000 } })
  ctx.setDefaultTimeout(15_000)
  const page = await ctx.newPage()
  const requests: string[] = []
  page.on('pageerror', (e) => pageErrors.push(e.message))
  page.on('request', (r) => requests.push(`${r.method()} ${new URL(r.url()).pathname}`))
  await page.goto(`${base}/`)
  await page.locator('button.route', { hasText: 'Connect to your sync server' }).click()
  const card = page.locator('div.sync.card')
  return { page, card, phones: card.locator('section.phones'), requests }
}

async function shot(card: Locator, name: string) {
  const dir = process.env.E2E_SHOTS
  if (!dir) return
  mkdirSync(dir, { recursive: true })
  await card.scrollIntoViewIfNeeded()
  await card.screenshot({ path: join(dir, `${name}.png`) })
}

const text = async (l: Locator) => (await l.innerText()).replace(/\s+/g, ' ')
const shows = (l: Locator, sentence: string) => until(`"${sentence}"`, async () => (await text(l)).includes(sentence))

it('walks the Phones section through every state', async () => {
  const { page, card, phones, requests } = await openSyncCard(HTTPS_BASE)
  const button = (name: string) => phones.getByRole('button', { name, exact: true })

  // 1. Not connected.
  await shows(phones, COPY.NOT_CONNECTED)
  expect(await button(COPY.PAIR_A_PHONE).count()).toBe(0)
  await shot(card, '01-not-connected')

  // 2. The token alone, on a server nothing has been synced to: the phone list proves it.
  await card.getByLabel('Access token').fill(OWNER_TOKEN)
  await card.getByRole('button', { name: 'Fetch & decrypt latest' }).click()
  await shows(phones, COPY.READY_LEDE)
  await shows(phones, COPY.NO_PHONES)
  await shot(card, '02-ready-no-phones')

  // 3. A code on screen: the QR of exactly the pairing text, the address, the code, the clock.
  await button(COPY.PAIR_A_PHONE).click()
  await shows(phones, COPY.CODE_LEDE)
  const shownCode = (await phones.locator('dd.code').innerText()).trim()
  expect(shownCode).toMatch(/^[2-9A-HJKMNP-Z]{5}-[2-9A-HJKMNP-Z]{5}$/)
  const code = shownCode.replace('-', '')
  expect((await phones.locator('dd.address').innerText()).trim()).toBe(HTTPS_ADDRESS)
  expect(await phones.locator('svg.qr path.qr-ink').getAttribute('d')).toBe(qrDrawing(pairingQrText(HTTPS_ADDRESS, code)).path)
  expect(await text(phones.locator('p.time'))).toMatch(/^[0-2]:[0-5]\d left · works once$/)
  await shot(card, '03-code-shown')
  await page.emulateMedia({ colorScheme: 'dark' })
  await shot(card, '03-code-shown-dark')
  await page.emulateMedia({ colorScheme: 'light' })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await shot(card, '03-code-shown-phone-width')
  await page.setViewportSize({ width: 1100, height: 1000 })

  // 4. A phone redeems it: its six words appear.
  const phoneA = await testPhone(HTTPS_BASE)
  expect(await phoneA.redeem(code)).toBe(202)
  await shows(phones, COPY.COMPARE_LEDE)
  const words = (await phones.locator('ol.words li').allInnerTexts()).map((w) => w.replace(/^\d+\s*/, '').trim())
  expect(words).toEqual(await deviceWords(phoneA.publicKey))
  expect(await text(phones)).not.toContain(shownCode)
  await shot(card, '04-compare-words')

  // 5. "The words don't match": no request, and no more polling.
  const before = requests.length
  await button(COPY.WORDS_DONT_MATCH).click()
  await shows(phones, COPY.MISMATCH)
  await sleep(4_500)
  expect(requests.slice(before).filter((r) => r.includes('/v1/devices'))).toEqual([])
  await shot(card, '05-words-dont-match')

  // 6. A new code, a second phone, and "Pair this phone".
  await button(COPY.MAKE_A_NEW_CODE).click()
  await shows(phones, COPY.CODE_LEDE)
  const code2 = (await phones.locator('dd.code').innerText()).trim().replace('-', '')
  expect(code2).not.toBe(code)
  const phoneB = await testPhone(HTTPS_BASE)
  expect(await phoneB.redeem(code2)).toBe(202)
  await shows(phones, COPY.COMPARE_LEDE)
  const wordsB = await deviceWords(phoneB.publicKey)
  await button(COPY.PAIR_THIS_PHONE).click()
  await shows(phones, COPY.PAIRED)
  expect(await text(phones.locator('li.row').first())).toContain(COPY.rowWords(wordsB))
  expect(await text(phones.locator('li.row').first())).toContain(`Paired ${COPY.longDate(Date.now())}`)
  await shot(card, '06-paired')

  // 7. Disconnect, with its clay confirm.
  await phones.locator('li.row').first().getByRole('button', { name: COPY.DISCONNECT, exact: true }).click()
  await shows(phones, COPY.DISCONNECT_CONSEQUENCE)
  await shot(card, '07-disconnect-confirm')
  await button(COPY.DISCONNECT_THIS_PHONE).click()
  await shows(phones, COPY.DISCONNECTED)
  expect(await text(phones.locator('li.row').first())).toContain('· Disconnected ')
  expect(await phones.locator('li.row').first().getByRole('button').count()).toBe(0)
  await shot(card, '08-disconnected')

  // 8. A code the server says has lapsed.
  await button(COPY.PAIR_A_PHONE).click()
  await shows(phones, COPY.CODE_LEDE)
  await page.route('**/v1/devices/pairing/*', (route) =>
    route.request().method() === 'GET' ? route.fulfill({ status: 404, body: JSON.stringify({ error: PLANTED }) }) : route.fallback(),
  )
  await shows(phones, COPY.LAPSED)
  await page.unroute('**/v1/devices/pairing/*')
  await shot(card, '09-lapsed')

  // 9. A phone redeems, and the server says the confirmation came too late.
  await button(COPY.MAKE_A_NEW_CODE).click()
  await shows(phones, COPY.CODE_LEDE)
  const code3 = (await phones.locator('dd.code').innerText()).trim().replace('-', '')
  expect(await (await testPhone(HTTPS_BASE)).redeem(code3)).toBe(202)
  await shows(phones, COPY.COMPARE_LEDE)
  await page.route('**/confirm', (route) => route.fulfill({ status: 404, body: JSON.stringify({ error: PLANTED }) }))
  await button(COPY.PAIR_THIS_PHONE).click()
  await shows(phones, COPY.UNCONFIRMED)
  await page.unroute('**/confirm')
  await shot(card, '10-unconfirmed')

  // 10. The server cannot be reached while a code is shown; "Try again" brings the code back.
  await button(COPY.MAKE_A_NEW_CODE).click()
  await shows(phones, COPY.CODE_LEDE)
  const code4 = (await phones.locator('dd.code').innerText()).trim()
  await page.route('**/v1/devices/pairing/*', (route) => (route.request().method() === 'GET' ? route.abort() : route.fallback()))
  await shows(phones, COPY.UNREACHABLE)
  expect(await text(phones)).not.toContain(code4)
  await shot(card, '11-unreachable')
  await page.unroute('**/v1/devices/pairing/*')
  await button(COPY.TRY_AGAIN).click()
  await shows(phones, COPY.CODE_LEDE)
  expect((await phones.locator('dd.code').innerText()).trim()).toBe(code4)

  // 11. The server refuses, with a sentence of its own that never reaches the page.
  await button(COPY.DISCARD_CODE).click()
  await page.route('**/v1/devices/pairing', (route) => route.fulfill({ status: 401, body: JSON.stringify({ error: PLANTED, message: PLANTED }) }))
  await button(COPY.PAIR_A_PHONE).click()
  await shows(phones, COPY.REFUSED)
  await page.unroute('**/v1/devices/pairing')
  expect(await page.locator('body').innerText()).not.toContain(PLANTED)
  await shot(card, '12-refused')
  await button(COPY.BACK_TO_PHONES).click()
  await shows(phones, COPY.PAIR_A_PHONE)

  // 12. A server whose address is http.
  const http = await openSyncCard(HTTP_BASE)
  await http.card.getByLabel('Access token').fill(OWNER_TOKEN)
  await http.card.getByRole('button', { name: 'Fetch & decrypt latest' }).click()
  await shows(http.phones, COPY.READY_LEDE)
  await http.phones.getByRole('button', { name: COPY.PAIR_A_PHONE, exact: true }).click()
  await shows(http.phones, COPY.HTTP_ONLY)
  expect(await http.phones.getByRole('button', { name: COPY.PAIR_A_PHONE, exact: true }).count()).toBe(0)
  await shot(http.card, '13-http-server')

  expect(pageErrors).toEqual([])
})
