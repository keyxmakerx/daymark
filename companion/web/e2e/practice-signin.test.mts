/*
 * THE PRACTICE CONSOLE'S SIGN-IN, PRESSED IN A REAL BROWSER AGAINST A REAL SERVER (#494).
 *
 * Every browser refuses a `fetch` called with a receiver other than the window ("Illegal
 * invocation"); node does not. So a client that stores the global fetch and calls it as a method
 * passes every node test and fails every sign-in in Chrome, Firefox and Safari, which is how the
 * practice console shipped: each press of Sign in answered "No answer from the server (Failed to
 * execute 'fetch' on 'Window': Illegal invocation)". src/lib/fetchCalls.test.ts holds the rule in
 * node; this holds it where it actually bit.
 *
 *   control   in this browser, a fetch called as a method throws, and called detached it does not:
 *             the browser enforces the rule this test exists to watch
 *   member    opens /practice.html, types a member id the server has never seen and six digits,
 *             presses Sign in: the request reaches the server, the server refuses it, and the page
 *             says "Not signed in" without "No answer from the server" or "Illegal invocation"
 *
 * Signing in successfully needs an enrolled clinician, which only the pairing ceremony makes; the
 * paired-loop walk drives that. The bug lived before the request left the page, so a refusal that
 * came back from the server proves the request left it.
 *
 * RUN IT (not part of `pnpm test`):   cd companion/web && pnpm e2e:practice
 * It builds the server jar (gradle skips it when nothing changed) and this package's bundle into a
 * temporary directory, and starts the jar on 127.0.0.1 in the practice shape with an empty data
 * directory. Chromium is the one preinstalled under PLAYWRIGHT_BROWSERS_PATH (default
 * /opt/pw-browsers); CHROMIUM_PATH overrides it. Nothing is downloaded.
 *
 * CREDENTIALS. The owner token is made up per run and scrubbed from anything printed on failure.
 * The six digits typed are not anybody's code: no authenticator was ever enrolled on this server.
 */
import { afterAll, beforeAll, expect, it } from 'vitest'
import { chromium, type Browser, type Page } from 'playwright-core'
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { createRequire } from 'node:module'
import { createServer, type AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const WEB = fileURLToPath(new URL('..', import.meta.url))
const SERVER = join(WEB, '..', 'server')
const JAR = join(SERVER, 'build', 'libs', 'daymark-companion.jar')
const OWNER_TOKEN = `e2e-owner-${randomBytes(16).toString('hex')}`

const redact = (text: string) => text.split(OWNER_TOKEN).join('[REDACTED]')
const log = (line: string) => console.log(`[practice-signin] ${redact(line)}`)
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

let scratch = ''
let BASE = ''
let server: ChildProcess | null = null
let serverLog = ''
let browser: Browser | null = null
let page: Page | null = null
const pageErrors: string[] = []
const answered: string[] = []

beforeAll(async () => {
  scratch = mkdtempSync(join(tmpdir(), 'daymark-practice-e2e-'))
  const distDir = join(scratch, 'dist')
  const dataDir = join(scratch, 'data')
  serverLog = join(scratch, 'server.log')
  mkdirSync(dataDir)
  log('building the server jar')
  build(join(SERVER, 'gradlew'), ['shadowJar', '--console=plain', '-q'], SERVER, 'the server jar build')
  log('building the web bundle')
  build(process.execPath, [join(WEB, 'node_modules', 'vite', 'bin', 'vite.js'), 'build', '--outDir', distDir, '--emptyOutDir', '--logLevel', 'warn'], WEB, 'the web build')
  expect(existsSync(join(distDir, 'practice.html'))).toBe(true)

  const port = await freePort()
  BASE = `http://127.0.0.1:${port}`
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) if (v !== undefined && !k.startsWith('DAYMARK_')) env[k] = v
  const fd = openSync(serverLog, 'a')
  server = spawn('java', ['-jar', JAR], {
    env: {
      ...env,
      DAYMARK_BIND_ADDR: '127.0.0.1',
      DAYMARK_PORT: String(port),
      DAYMARK_DATA_DIR: dataDir,
      DAYMARK_WEB_DIR: distDir,
      DAYMARK_AUTH_TOKEN: OWNER_TOKEN,
      // The practice shape: the one that serves practice.html and answers its routes.
      DAYMARK_SETUP_MODE: 'practice',
      DAYMARK_THERAPIST_AUTH: '',
      DAYMARK_PUBLIC_BASE_URL: BASE,
      // Plain http on 127.0.0.1, so the session cookie cannot be Secure here.
      DAYMARK_COOKIE_INSECURE: '1',
      DAYMARK_LOG_LEVEL: 'warn',
    },
    stdio: ['ignore', fd, fd],
  })
  closeSync(fd)
  const deadline = Date.now() + 60_000
  for (;;) {
    if (server.exitCode !== null || Date.now() > deadline) {
      const why = server.exitCode !== null ? `exited with code ${server.exitCode}` : 'did not answer /healthz within 60s'
      throw new Error(redact(`the server ${why}:\n${readFileSync(serverLog, 'utf8').slice(-2000)}`))
    }
    if (await fetch(`${BASE}/healthz`).then((r) => r.ok, () => false)) break
    await sleep(300)
  }
  log(`server up on ${BASE}, practice shape, empty data directory`)

  browser = await chromium.launch({ executablePath: chromiumPath(), args: ['--no-sandbox'] })
  const ctx = await browser.newContext({ viewport: { width: 1100, height: 900 } })
  ctx.setDefaultTimeout(20_000)
  page = await ctx.newPage()
  page.on('pageerror', (e) => pageErrors.push(e.message))
  page.on('response', (r) => answered.push(`${r.request().method()} ${new URL(r.url()).pathname} -> ${r.status()}`))
})

afterAll(async () => {
  await browser?.close().catch(() => {})
  if (server && server.exitCode === null) server.kill('SIGKILL')
  if (scratch) rmSync(scratch, { recursive: true, force: true })
})

it('control: this browser refuses a fetch called as a method, and accepts it detached', async () => {
  await page!.goto(`${BASE}/practice.html`)
  const outcome = await page!.evaluate(async () => {
    const holder = { doFetch: fetch }
    let method = 'no error'
    try {
      await holder.doFetch('/healthz')
    } catch (e) {
      method = e instanceof Error ? e.message : String(e)
    }
    const detached = await (0, holder.doFetch)('/healthz').then((r) => r.status)
    return { method, detached }
  })
  expect(outcome.method).toMatch(/Illegal invocation/)
  expect(outcome.detached).toBe(200)
})

it('pressing Sign in reaches the server, and the page reports its refusal', async () => {
  const p = page!
  await p.goto(`${BASE}/practice.html`)
  await p.locator('#practice-member-id').fill('e2e_nobody')
  await p.locator('#practice-totp').fill('123456')
  answered.length = 0
  await p.getByRole('button', { name: 'Sign in', exact: true }).click()

  const callout = p.locator('text=Not signed in').first()
  await callout.waitFor()
  const said = (await p.locator('body').innerText()).replace(/\s+/g, ' ')
  log(`the server answered: ${answered.filter((a) => a.includes('/v1/')).join(', ') || 'nothing'}`)

  expect(said).not.toContain('Illegal invocation')
  expect(said).not.toContain('No answer from the server')
  // The refusal came from the server, so the request left the page.
  expect(answered.some((a) => /^POST \/v1\/totp\/verify -> 4\d\d$/.test(a))).toBe(true)
  expect(pageErrors).toEqual([])
})
