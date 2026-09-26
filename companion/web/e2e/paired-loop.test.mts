/*
 * THE PAIRED LOOP, DRIVEN IN TWO REAL BROWSERS AGAINST A REAL SERVER.
 *
 * Every screen below has node tests over the modules beneath it, and none of those tests renders
 * a component or crosses from one person's browser to the other's, so the loop can be broken for
 * everyone who tries it while every one of them passes. This drives it the way two people would:
 *
 *   owner      first run: Paired. The Recovery code screen makes the key file (the stand-in for
 *              owner-key custody; COMPANION_ARCHITECTURE.md §1). Open the synthetic backup on the
 *              file route, go to the Owner console with it still open, unlock with the key file,
 *              add a clinician (their inbox token is shown once), connect, mint the invitation and
 *              make a code.
 *   clinician  open the link; type the code, a name and a reading passphrase; wait.
 *   owner      check for the reply: the clinician's name and two fingerprints. Approve.
 *   clinician  the poll lands; enrol the authenticator (codes computed here, RFC 6238); the two
 *              fingerprints to read aloud are the two the owner saw.
 *   owner      the Pinned keys record holds that key. Grant read.share and assign.questionnaire.
 *              Tick self-checks, moods and sleep, leave the journal unticked, seal a share.
 *   clinician  sign in (authenticator, reading passphrase, and the inbox token handed over by
 *              hand); open the share: the ticked types, and no journal. Send an assignment.
 *   owner      the inbox lists it, verified, from the clinician the owner named.
 *
 * WHY THE BACKUP IS OPENED BEFORE THE CONSOLE IS UNLOCKED. The unlocked session, and every
 * clinician added in it, lives in the Owner console component, and choosing another route unmounts
 * it: leaving the console to open a backup and coming back finds it locked, with no clinician, and
 * the inbox token that named the relationship was shown once. So the backup is opened first, the
 * one order in which a person can seal today.
 *
 * RUN IT (not part of `pnpm test`):   cd companion/web && pnpm e2e:paired
 * Each run builds the server jar (gradle skips it when nothing changed) and this package's bundle
 * into a temporary directory, starts the jar on 127.0.0.1 with an empty data directory and that
 * origin as its public address, and stops it at the end. A server that exits at start-up (a
 * refusal, say) fails the run at once with its log, rather than as a timeout. Chromium is the one preinstalled under PLAYWRIGHT_BROWSERS_PATH (default
 * /opt/pw-browsers), at the revision the pinned playwright-core expects; CHROMIUM_PATH overrides
 * it. Nothing is downloaded. E2E_SHOTS=<dir> keeps a screenshot of both browsers after every step.
 *
 * CREDENTIALS. The owner token, the inbox token, the recovery code, the passphrases, the invitation
 * link, the pairing code and the authenticator secret are all made up per run, never logged, and
 * scrubbed from anything this file prints on failure. Each is typed only where the product sends
 * it: the pairing code only into the clinician's browser, which is where a person would type it
 * (CLAUDE.md §4, it never leaves the device it was typed on).
 */
import { afterAll, beforeAll, expect, it } from 'vitest'
import { chromium, type Browser, type BrowserContext, type Locator, type Page } from 'playwright-core'
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import { createHmac, randomBytes } from 'node:crypto'
import { closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { createServer, type AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseBackup, type BackupData } from '../src/lib/backup'
import { OWNER_COPY, THERAPIST_COPY } from '../src/lib/pairing/copy'

const WEB = fileURLToPath(new URL('..', import.meta.url))
const SERVER = join(WEB, '..', 'server')
const JAR = join(SERVER, 'build', 'libs', 'daymark-companion.jar')

// ── what each person types, all made up for this run ─────────────────────────────────────────────
const OWNER_TOKEN = `e2e-owner-${randomBytes(16).toString('hex')}`
const KEY_FILE_PASSPHRASE = `e2e key file ${randomBytes(6).toString('hex')}`
const READING_PASSPHRASE = `e2e reading ${randomBytes(6).toString('hex')}`
const OWNER_NAMES_THEM = 'Dr Example'
const THEY_NAME_THEMSELVES = 'Sam Rivera'
const ASSIGNMENT_NOTE = 'Made-up note from the paired-loop test'

/** Every secret this run has seen, scrubbed from anything printed. Grows as the run learns them. */
const secrets: string[] = [OWNER_TOKEN, KEY_FILE_PASSPHRASE, READING_PASSPHRASE]
/**
 * Scrubbed by value first, then by shape, for a failure that lands after a secret reached the screen
 * but before this file read it: anything shaped like a pairing code, and any long unbroken token.
 */
function redact(text: string): string {
  let out = text
  for (const s of secrets) if (s.length >= 4) out = out.split(s).join('[REDACTED]')
  return out
    .replace(/\b[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}\b/g, '[REDACTED-CODE]')
    .replace(/[A-Za-z0-9_-]{32,}/g, '[REDACTED-LONG]')
}
const log = (line: string) => console.log(`[paired-loop] ${redact(line)}`)

// ── the synthetic backup, in src/lib/backup.ts's shape ────────────────────────────────────────────
const DAY = 86_400_000
function syntheticBackup(now: number): BackupData {
  const at = (daysAgo: number, hour: number) => {
    const d = new Date(now - daysAgo * DAY)
    d.setHours(hour, 0, 0, 0)
    return d.getTime()
  }
  const epochDay = (ms: number) => {
    const d = new Date(ms)
    return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / DAY)
  }
  const moods: [number, number, string][] = [
    [26, 3, 'Made-up note: a slow morning, tea on the step.'],
    [23, 2, 'Made-up note: slept badly, foggy all day.'],
    [20, 4, 'Made-up note: a long walk by the canal.'],
    [17, 3, 'Made-up note: an ordinary work day.'],
    [13, 2, 'Made-up note: a disagreement that stayed with me.'],
    [10, 4, 'Made-up note: finished a chapter.'],
    [6, 5, 'Made-up note: a friend visited, we cooked.'],
    [2, 3, 'Made-up note: laundry and reading.'],
  ]
  return {
    version: 6,
    exportedAt: now,
    entries: moods.map(([d, level, note], i) => ({ id: i + 1, dateTime: at(d, 19), moodLevel: level, note, photoPath: null })),
    activities: [
      { id: 1, name: 'Walk', iconKey: 'walk', sortOrder: 0, archived: false },
      { id: 2, name: 'Reading', iconKey: 'book', sortOrder: 1, archived: false },
    ],
    refs: [
      { entryId: 3, activityId: 1 },
      { entryId: 6, activityId: 2 },
      { entryId: 8, activityId: 2 },
    ],
    journal: [
      { id: 1, dateTime: at(21, 21), title: 'Made-up title: earlier nights', body: 'Made-up body: trying to be asleep by eleven.' },
      { id: 2, dateTime: at(12, 21), title: 'Made-up title: a better week', body: 'Made-up body: plans felt lighter this week.' },
      { id: 3, dateTime: at(4, 21), title: 'Made-up title: notes before a session', body: 'Made-up body: things to bring up.' },
    ],
    goals: [],
    // `night` is an epoch DAY, the local date of the morning woken (LocalDate.toEpochDay() in the
    // app's SleepLog.kt); the two times are epoch millis.
    sleepLogs: [18, 14, 9, 5, 1].map((d, i) => ({
      id: i + 1,
      night: epochDay(at(d, 7)),
      bedTime: at(d + 1, 23),
      wakeTime: at(d, 7),
      sleepLatencyMin: 15 + i * 5,
      awakeMin: 5 * i,
      quality: [2, 3, 4, 3, 4][i],
      note: '',
    })),
    assessments: [
      { id: 1, key: 'phq9', dateTime: at(24, 18), score: 11, bandLabel: 'Moderate' },
      { id: 2, key: 'gad7', dateTime: at(23, 18), score: 9, bandLabel: 'Mild' },
      { id: 3, key: 'phq9', dateTime: at(10, 18), score: 8, bandLabel: 'Mild' },
      { id: 4, key: 'gad7', dateTime: at(9, 18), score: 6, bandLabel: 'Mild' },
      { id: 5, key: 'who5', dateTime: at(5, 18), score: 13, bandLabel: 'Low wellbeing' },
    ],
    moodLabels: {},
    moodColors: {},
  }
}

// ── RFC 6238, as the server's Totp.kt: HMAC-SHA1, 30-second steps, 6 digits ──────────────────────
function base32(secret: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = 0
  let value = 0
  const out: number[] = []
  for (const c of secret.toUpperCase().replace(/[^A-Z2-7]/g, '')) {
    value = (value << 5) | alphabet.indexOf(c)
    bits += 5
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff)
      bits -= 8
    }
  }
  return Buffer.from(out)
}
function totpAt(secret: string, step: number): string {
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(step))
  const h = createHmac('sha1', base32(secret)).update(counter).digest()
  const o = h[h.length - 1] & 0xf
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, '0')
}
/**
 * A code the server will take. It accepts one step either side of its own and each step once per
 * credential, so every code here is for a step later than the last one used, and never more than
 * one step ahead of the clock.
 */
let lastStep = -1
async function nextTotp(secret: string): Promise<string> {
  const nowStep = Math.floor(Date.now() / 30_000)
  const use = Math.max(nowStep, lastStep + 1)
  if (use > nowStep + 1) await sleep((use - 1) * 30_000 - Date.now() + 250)
  lastStep = use
  return totpAt(secret, use)
}

// ── plumbing ──────────────────────────────────────────────────────────────────────────────────────
const sleep = (ms: number) => new Promise((r) => setTimeout(r, Math.max(0, ms)))
const squash = (s: string) => s.replace(/\s+/g, '')

function build(cmd: string, args: string[], cwd: string, what: string) {
  const r = spawnSync(cmd, args, { cwd, encoding: 'utf8', env: process.env, maxBuffer: 64 * 1024 * 1024 })
  if (r.status !== 0) {
    throw new Error(`${what} failed (exit ${r.status}):\n${`${r.stdout ?? ''}\n${r.stderr ?? ''}`.slice(-3000)}`)
  }
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
  // browsers.json is not in playwright-core's exports map; package.json is, and sits beside it.
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
  if (!candidates[0].includes(`chromium-${pinned}/`)) log(`Chromium revision ${pinned} is not under ${base}; using ${candidates[0]}`)
  return candidates[0]
}

/** Polls until `check` returns something truthy, or fails naming what it waited for. */
async function until<T>(what: string, check: () => Promise<T | null | undefined | false>, timeoutMs: number, everyMs = 500): Promise<T> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const got = await check().catch(() => null)
    if (got) return got
    if (Date.now() > deadline) throw new Error(`gave up after ${Math.round(timeoutMs / 1000)}s waiting for ${what}`)
    await sleep(everyMs)
  }
}

// ── the run's state ───────────────────────────────────────────────────────────────────────────────
let scratch = ''
let dataDir = ''
let distDir = ''
let serverLog = ''
let server: ChildProcess | null = null
let BASE = ''
let browser: Browser | null = null
let ownerCtx: BrowserContext
let clinicianCtx: BrowserContext
let owner: Page
let clinician: Page
const pageErrors: string[] = []
/** Every API answer of 400 or above, for the failure dump. Paths carry ids and digests, never a token. */
const refusals: string[] = []
let stepNo = 0

async function shots(tag: string) {
  const dir = process.env.E2E_SHOTS
  if (!dir) return
  mkdirSync(dir, { recursive: true })
  await owner?.screenshot({ path: join(dir, `${tag}-owner.png`), fullPage: true }).catch(() => {})
  await clinician?.screenshot({ path: join(dir, `${tag}-clinician.png`), fullPage: true }).catch(() => {})
}

/**
 * What a screen shows, read from the part under test: the first of `selectors` on the page. The
 * route cards above the owner's console fill any fixed budget read from the top of the page.
 */
async function screenText(page: Page | undefined, selectors: string[]): Promise<string> {
  if (!page) return '(no page)'
  const text = await page
    .evaluate((sels) => {
      const el = sels.map((s) => document.querySelector(s)).find((e) => e) ?? document.body
      return (el as HTMLElement).innerText ?? ''
    }, selectors)
    .catch((e) => `(unreadable: ${e instanceof Error ? e.message : String(e)})`)
  return redact(text.replace(/\n{2,}/g, '\n')).slice(0, 2500)
}

/** One named step. A failure says which, and what each screen showed, with every secret scrubbed. */
async function step<T>(name: string, fn: () => Promise<T>): Promise<T> {
  stepNo += 1
  const label = `step ${stepNo}: ${name}`
  const started = Date.now()
  log(label)
  try {
    const out = await fn()
    log(`  done in ${((Date.now() - started) / 1000).toFixed(1)}s`)
    await shots(String(stepNo).padStart(2, '0'))
    return out
  } catch (e) {
    await shots(`${String(stepNo).padStart(2, '0')}-FAILED`)
    const tail = serverLog && existsSync(serverLog) ? readFileSync(serverLog, 'utf8').split('\n').slice(-15).join('\n') : ''
    console.log(
      redact(
        [
          `[paired-loop] FAILED at ${label}`,
          `--- the owner's screen ---\n${await screenText(owner, ['section.console', '.unlock', 'main'])}`,
          `--- the clinician's screen ---\n${await screenText(clinician, ['section.portal', 'section.accept', 'body'])}`,
          `--- page errors ---\n${pageErrors.join('\n') || '(none)'}`,
          `--- API answers of 400 and above ---\n${refusals.slice(-20).join('\n') || '(none)'}`,
          `--- server log, last lines ---\n${tail}`,
        ].join('\n'),
      ),
    )
    // An assertion's own diff does not survive the re-throw, so its two values travel in the message.
    const values =
      e && typeof e === 'object' && 'actual' in e && 'expected' in e
        ? `\n  actual:   ${JSON.stringify((e as { actual: unknown }).actual)}\n  expected: ${JSON.stringify((e as { expected: unknown }).expected)}`
        : ''
    throw new Error(redact(`${label} — ${e instanceof Error ? e.message : String(e)}${values}`))
  }
}

beforeAll(async () => {
  scratch = mkdtempSync(join(tmpdir(), 'daymark-paired-e2e-'))
  dataDir = join(scratch, 'data')
  distDir = join(scratch, 'dist')
  serverLog = join(scratch, 'server.log')
  mkdirSync(dataDir)

  // Both builds run every time: gradle decides whether the jar is stale, and the bundle is built
  // from this tree into its own directory, so the run never serves a dist left over from another.
  log('building the server jar')
  build(join(SERVER, 'gradlew'), ['shadowJar', '--console=plain', '-q'], SERVER, 'the server jar build')
  expect(existsSync(JAR), `no jar at ${JAR}`).toBe(true)
  log('building the web bundle')
  build(process.execPath, [join(WEB, 'node_modules', 'vite', 'bin', 'vite.js'), 'build', '--outDir', distDir, '--emptyOutDir', '--logLevel', 'warn'], WEB, 'the web build')
  expect(existsSync(join(distDir, 'therapist.html'))).toBe(true)

  const port = await freePort()
  BASE = `http://127.0.0.1:${port}`
  // A person's environment may carry DAYMARK_* settings (a token file, above all) that would
  // outrank the ones this run sets, so none of them are inherited.
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
      DAYMARK_THERAPIST_AUTH: '1',
      // With the clinician portal on, the server will not start without its public address (it
      // exits with "Refusing to start:"), and the invitation link is built from it: so it is the
      // exact origin both browsers use.
      DAYMARK_PUBLIC_BASE_URL: BASE,
      // What the server actually reads (Config.kt): drops Secure from the session cookie, because
      // this server is plain http on 127.0.0.1. There is no DAYMARK_COOKIE_SECURE. The switch is
      // refused beside an https:// public address, which this one is not.
      DAYMARK_COOKIE_INSECURE: '1',
      DAYMARK_LOG_LEVEL: 'warn',
    },
    stdio: ['ignore', fd, fd],
  })
  closeSync(fd)
  // Stops at once if the server exits, rather than waiting out the deadline on a dead process.
  const deadline = Date.now() + 60_000
  for (;;) {
    if (server.exitCode !== null || Date.now() > deadline) {
      const why = server.exitCode !== null ? `exited with code ${server.exitCode}` : 'did not answer /healthz within 60s'
      throw new Error(redact(`the server ${why}:\n${readFileSync(serverLog, 'utf8').slice(-2000)}`))
    }
    if (await fetch(`${BASE}/healthz`).then((r) => r.ok, () => false)) break
    await sleep(300)
  }
  log(`server up on ${BASE}, empty data directory`)

  browser = await chromium.launch({ executablePath: chromiumPath(), args: ['--no-sandbox'] })
  ownerCtx = await browser.newContext({ viewport: { width: 1280, height: 1000 }, acceptDownloads: true })
  clinicianCtx = await browser.newContext({ viewport: { width: 1280, height: 1000 } })
  for (const ctx of [ownerCtx, clinicianCtx]) ctx.setDefaultTimeout(20_000)
  owner = await ownerCtx.newPage()
  clinician = await clinicianCtx.newPage()
  for (const [who, page] of [['owner', owner], ['clinician', clinician]] as const) {
    page.on('pageerror', (e) => pageErrors.push(`${who}: ${e.message}`))
    page.on('response', (r) => {
      if (r.status() >= 400) refusals.push(`${who}: ${r.request().method()} ${new URL(r.url()).pathname} -> ${r.status()}`)
    })
  }
})

afterAll(async () => {
  await browser?.close().catch(() => {})
  if (server && server.exitCode === null) {
    const exited = new Promise((r) => server!.once('exit', r))
    server.kill('SIGTERM')
    await Promise.race([exited, sleep(10_000)])
    if (server.exitCode === null) server.kill('SIGKILL')
  }
  if (scratch) rmSync(scratch, { recursive: true, force: true })
})

// ── the loop ──────────────────────────────────────────────────────────────────────────────────────
it('the paired loop: pair, grant, seal a share, read it, and send an assignment back', async () => {
  const backup = syntheticBackup(Date.now())
  // The fixture is only as good as the product's own reading of it.
  expect(parseBackup(JSON.stringify(backup))).toEqual(backup)
  const backupFile = join(scratch, 'synthetic-backup.json')
  writeFileSync(backupFile, JSON.stringify(backup))
  const keyFile = join(scratch, 'owner-key-file.json')

  /*
   * The owner's bearer requests share one token bucket per address: DAYMARK_RATE_LIMIT_RPS, five a
   * second by default, full again after a second. This test acts faster than that: the seal, clicked
   * straight after the grant and the Share tab's own reads, found the bucket empty. The relationship
   * routes answer a rate-limited owner 401, as if the token were wrong, and the console says "blob
   * store failed" (#382). So before each owner action that goes to the server the test waits as long
   * as the bucket takes to refill, and the server keeps its default. A refusal after that is one
   * paced action spending more than the allowance alone. #382 is where this pacing can go.
   */
  const asAPerson = () => owner.waitForTimeout(1_100)
  const routeCard = (label: string) => owner.locator('button.route', { has: owner.locator('.route-label', { hasText: label }) })
  const consoleSection = () => owner.locator('section.console')
  const consoleTab = (name: string) => consoleSection().locator('nav.subnav').getByRole('button', { name, exact: true })
  const summaryCount = async (scope: Locator, heading: string) => {
    const text = squash(await scope.locator('details.card summary', { hasText: heading }).innerText())
    return Number(/(\d+)/.exec(text.slice(heading.replace(/\s+/g, '').length))?.[1] ?? NaN)
  }

  await step('the owner answers the first-run question: Paired', async () => {
    await owner.goto(`${BASE}/`, { waitUntil: 'networkidle' })
    await owner.getByRole('button', { name: /^Paired/ }).click()
    // Paired opens on the owner console.
    await owner.locator('.unlock').waitFor()
  })

  await step('the Recovery code screen makes the owner key file', async () => {
    await routeCard('Connect to your sync server').click()
    await owner.getByRole('button', { name: 'Open the recovery code screen' }).click()
    await owner.getByLabel('Passphrase for this key').fill(KEY_FILE_PASSPHRASE)
    await owner.getByLabel('The same passphrase again').fill(KEY_FILE_PASSPHRASE)
    await owner.getByRole('button', { name: 'Generate a recovery code' }).click()
    // Two Argon2id wraps: a few seconds.
    await owner.locator('.symbols').first().waitFor({ timeout: 90_000 })
    const groups = (await owner.locator('.symbols').allTextContents()).map((g) => g.trim())
    secrets.push(...groups)
    expect(groups).toHaveLength(6)
    await owner.getByRole('button', { name: 'I have written it down' }).click()
    const asked = await owner.locator('.check .box label').allTextContents()
    expect(asked.length).toBeGreaterThan(0)
    for (const label of asked) {
      const n = Number(label.replace(/\D/g, ''))
      await owner.locator(`#confirm-group-${n}`).fill(groups[n - 1])
    }
    await owner.getByRole('button', { name: 'Check what I wrote down' }).click()
    const [download] = await Promise.all([
      owner.waitForEvent('download'),
      owner.getByRole('button', { name: 'Save the wrapped key to a file' }).click(),
    ])
    await download.saveAs(keyFile)
    expect(JSON.parse(readFileSync(keyFile, 'utf8'))).toBeTypeOf('object')
  })

  let ownerJournalCount = NaN
  let ownerMoodDistribution = ''
  await step('the owner opens the synthetic backup on the file route', async () => {
    await routeCard('Open a backup file').click()
    await owner.locator('input[type=file]').setInputFiles(backupFile)
    const loaded = owner.locator('section.loaded')
    await loaded.locator('.filemeta', { hasText: 'synthetic-backup.json' }).waitFor()
    // The positive control for the clinician's side, read from the same component: this backup's
    // own dashboard counts its journal entries, so a zero there is the share, not the reader.
    ownerJournalCount = await summaryCount(loaded, 'Journal')
    expect(ownerJournalCount).toBe(backup.journal!.length)
    ownerMoodDistribution = (await loaded.locator('svg.dist').getAttribute('aria-label')) ?? ''
    expect(ownerMoodDistribution).toMatch(/^Mood distribution — /)
  })

  await step('the Owner console, with the backup still open', async () => {
    // The navigation has to survive a loaded backup, or there is no way to the console with the
    // records in hand.
    const route = routeCard('Owner console')
    if (!(await route.isVisible())) throw new Error('with a backup open, the page offers no route to the Owner console')
    await route.click()
    await owner.locator('.unlock').waitFor()
    // Still loaded: the top bar offers another only while one is open.
    await owner.getByRole('button', { name: 'Open another backup' }).waitFor()
  })

  let inboxToken = ''
  await step('unlock with the key file, add a clinician, enter', async () => {
    const unlock = owner.locator('.unlock')
    await unlock.locator('input[type=file]').setInputFiles(keyFile)
    await unlock.getByLabel('Passphrase', { exact: true }).fill(KEY_FILE_PASSPHRASE)
    await unlock.getByRole('button', { name: 'Unlock', exact: true }).click()
    await unlock.getByText('Your owner fingerprint').waitFor({ timeout: 90_000 })
    await unlock.getByLabel('Display name').fill(OWNER_NAMES_THEM)
    await unlock.getByRole('button', { name: 'Add clinician' }).click()
    inboxToken = (await unlock.locator('output.token').innerText()).trim()
    secrets.push(inboxToken)
    expect(inboxToken.length).toBeGreaterThanOrEqual(32)
    await unlock.getByRole('button', { name: 'Enter console' }).click()
    await consoleSection().waitFor()
  })

  await step('connect the console to the server', async () => {
    await consoleSection().locator('details.conn summary').click()
    await consoleSection().getByLabel('Owner access token').fill(OWNER_TOKEN)
    await asAPerson()
    await consoleSection().getByRole('button', { name: 'Connect', exact: true }).click()
    await consoleSection().locator('.cstatus', { hasText: /^Connected\.$/ }).waitFor()
  })

  let link = ''
  let code = ''
  const pairing = () => consoleSection().locator('.pairing')
  await step('mint the invitation and make a code', async () => {
    await asAPerson()
    await consoleTab('Share').click()
    await asAPerson()
    await pairing().getByRole('button', { name: 'Set up an invitation' }).click()
    await asAPerson()
    await pairing().getByRole('button', { name: 'Create an invitation' }).click()
    link = await pairing().locator('input[aria-label="Invitation link"]').inputValue()
    secrets.push(link, new URL(link.replace('#', '?')).searchParams.get('s') ?? link)
    expect(link).toContain('/portal/invite#')
    await asAPerson()
    await pairing().getByRole('button', { name: 'Make a code' }).click()
    code = (await pairing().locator('output.code').innerText()).trim()
    secrets.push(code, code.toLowerCase(), code.replace('-', ''))
    expect(code).toMatch(/^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$/)
  })

  await step('the clinician opens the link and sends the code, a name and a reading passphrase', async () => {
    await clinician.goto(link, { waitUntil: 'networkidle' })
    await clinician.getByRole('heading', { name: 'Accept an invitation' }).waitFor()
    // The only place the code is typed.
    await clinician.locator('#f-paircode').fill(code)
    await clinician.locator('#f-pairname').fill(THEY_NAME_THEMSELVES)
    await clinician.locator('#f-pass').fill(READING_PASSPHRASE)
    await clinician.locator('#f-pass2').fill(READING_PASSPHRASE)
    await clinician.getByRole('button', { name: 'Send this to them' }).click()
    await clinician.getByText(THERAPIST_COPY.waitingTitle).waitFor({ timeout: 90_000 })
  })

  const ownerSaw = { box: '', sign: '' }
  await step('the owner checks for the reply, sees the name and both fingerprints, and approves', async () => {
    const offer = pairing().locator('dl.offer')
    await until(
      'the clinician’s reply',
      async () => {
        if (await offer.isVisible()) return true
        const check = pairing().getByRole('button', { name: 'Check for a reply' })
        if (await check.isEnabled()) await check.click()
        return offer.isVisible()
      },
      60_000,
      2_000,
    )
    await pairing().getByText(OWNER_COPY.answeredTitle).waitFor()
    expect((await offer.locator('dd.name').innerText()).trim()).toBe(THEY_NAME_THEMSELVES)
    const fps = (await offer.locator('.fp').allInnerTexts()).map(squash)
    expect(fps).toHaveLength(2)
    ownerSaw.box = fps[0]
    ownerSaw.sign = fps[1]
    await asAPerson()
    await pairing().getByRole('button', { name: 'Approve', exact: true }).click()
    await pairing().getByText(OWNER_COPY.approvedTitle, { exact: true }).waitFor({ timeout: 30_000 })
    await pairing().getByRole('button', { name: 'Done', exact: true }).click()
    await consoleSection().locator('section.share').waitFor()
  })

  let totpSecret = ''
  let clinicianReads = { box: '', sign: '' }
  await step('the clinician’s poll lands; they enrol the authenticator and read their fingerprints aloud', async () => {
    // The clinician's page asks the server every 45 seconds whether the owner has decided.
    await clinician.getByText('Set up your authenticator').waitFor({ timeout: 120_000 })
    const href = (await clinician.locator('a[href^="otpauth:"]').getAttribute('href')) ?? ''
    totpSecret = new URL(href).searchParams.get('secret') ?? ''
    secrets.push(totpSecret)
    expect(totpSecret.length).toBeGreaterThanOrEqual(16)
    await clinician.locator('#f-code').fill(await nextTotp(totpSecret))
    await clinician.getByRole('button', { name: 'Confirm and finish' }).click()
    const fingerprints = clinician.locator('p.fingerprint')
    await fingerprints.nth(1).waitFor({ timeout: 30_000 })
    const read = (await fingerprints.allInnerTexts()).map(squash)
    clinicianReads = { box: read[0], sign: read[1] }
    // What one person reads out is what the other has on screen.
    expect(clinicianReads).toEqual(ownerSaw)
  })

  await step('the owner’s Pinned keys record holds the key they approved', async () => {
    await consoleTab('Pinned keys').click()
    const row = consoleSection().locator('.pins .row', { hasText: OWNER_NAMES_THEM })
    const onFile = (await row.locator('dd code').allInnerTexts()).map(squash)
    expect(onFile).toEqual([clinicianReads.sign, clinicianReads.box])
  })

  await step('grant read.share and assign.questionnaire', async () => {
    await consoleTab('Grants').click()
    const grants = consoleSection().locator('section.grants')
    for (const cap of ['read.share', 'assign.questionnaire']) {
      const row = grants.locator('.row', { has: owner.locator('.capid', { hasText: new RegExp(`^${cap.replace('.', '\\.')}$`) }) })
      await row.locator('input[type=checkbox]').check()
    }
    await asAPerson()
    await grants.getByRole('button', { name: 'Sign & publish grant' }).click()
    await grants.locator('.ok', { hasText: 'Published grant v0.' }).waitFor()
  })

  await step('tick self-checks, moods and sleep, leave the journal unticked, and seal the share', async () => {
    await consoleTab('Share').click()
    const share = consoleSection().locator('section.share')
    await share.getByRole('checkbox', { name: /^Self-checks/ }).check()
    await share.getByRole('checkbox', { name: /^Mood entries/ }).check()
    await share.getByRole('checkbox', { name: /^Sleep logs/ }).check()
    const include = await share.locator('fieldset.types').innerText()
    expect(include).toContain(`Self-checks (${backup.assessments!.length})`)
    expect(include).toContain(`Mood entries (${backup.entries.length})`)
    expect(include).toContain(`Sleep logs (${backup.sleepLogs!.length})`)
    expect(include).toContain('Journal (0)')
    const seal = share.getByRole('button', { name: 'Seal & publish share' })
    // Disabled whenever the share builder was handed no records.
    expect(await seal.isEnabled(), 'Seal & publish share is disabled: the share builder has no records').toBe(true)
    await asAPerson()
    await seal.click()
    await share.locator('.ok', { hasText: 'Sealed & published share v0.' }).waitFor({ timeout: 30_000 })
  })

  const portalTab = (name: string) => clinician.locator('section.portal nav.tabs').getByRole('button', { name, exact: true })
  await step('the clinician signs in: authenticator, reading passphrase, and the inbox token handed over', async () => {
    await clinician.goto(`${BASE}/therapist`, { waitUntil: 'networkidle' })
    await clinician.locator('#f-totpCode').fill(await nextTotp(totpSecret))
    await clinician.locator('#f-readingPassphrase').fill(READING_PASSPHRASE)
    await clinician.locator('#f-inboxToken').fill(inboxToken)
    await clinician.getByRole('button', { name: 'Unlock portal' }).click()
    await portalTab('Shared data').waitFor({ timeout: 60_000 })
  })

  await step('the clinician opens the share: the ticked record types, and no journal', async () => {
    await portalTab('Shared data').click()
    const shared = clinician.locator('section.shared')
    await shared.getByRole('button', { name: 'Open shared data' }).click()
    await shared.getByText('Verified against the pinned owner key').waitFor({ timeout: 30_000 })
    // Self-checks: every instrument in the backup.
    const instruments = (await shared.locator('.assess .ak').allTextContents()).map((k) => k.trim()).sort()
    expect(instruments).toEqual(['gad7', 'phq9', 'who5'])
    // Moods: the same distribution the owner's own dashboard drew.
    expect(await shared.locator('svg.dist').getAttribute('aria-label')).toBe(ownerMoodDistribution)
    // Journal: none, where the owner's dashboard of the same backup counted every entry.
    expect(ownerJournalCount).toBeGreaterThan(0)
    expect(await summaryCount(shared, 'Journal')).toBe(0)

    // Sleep has no place on the dashboard; the calendar's ribbon counts every kind, week by week.
    await portalTab('Calendar').click()
    const weeks = await clinician.locator('ol.ribbon .visually-hidden').allTextContents()
    const tally = (labels: string[]) => {
      const t: Record<string, number> = { 'check-in': 0, journal: 0, 'sleep log': 0, 'self-check': 0 }
      for (const l of labels) for (const m of l.matchAll(/(\d+) (check-in|journal|sleep log|self-check)s?\b/g)) t[m[2]] += Number(m[1])
      return t
    }
    // The tally can see a journal when a label names one.
    expect(tally(['Week of a planted day: 2 journals, 1 sleep log'])).toEqual({ 'check-in': 0, journal: 2, 'sleep log': 1, 'self-check': 0 })
    expect(tally(weeks)).toEqual({
      'check-in': backup.entries.length,
      journal: 0,
      'sleep log': backup.sleepLogs!.length,
      'self-check': backup.assessments!.length,
    })
  })

  await step('the clinician sends an assignment', async () => {
    await portalTab('Assign').click()
    const assign = clinician.locator('section.assign')
    await assign.getByLabel('Note (optional)').fill(ASSIGNMENT_NOTE)
    await assign.getByRole('button', { name: 'Publish assignment' }).click()
    await clinician.getByRole('dialog', { name: 'Confirm action' }).getByRole('button', { name: 'Sign & publish' }).click()
    await assign.locator('.ok', { hasText: 'Published assignment v0.' }).waitFor({ timeout: 30_000 })
  })

  await step('the owner’s inbox lists it, verified, from the clinician they named', async () => {
    await consoleTab('Inbox').click()
    const inbox = consoleSection().locator('section.inbox')
    await asAPerson()
    await inbox.getByRole('button', { name: 'Refresh' }).click()
    const card = inbox.locator('article.item').first()
    await card.waitFor({ timeout: 30_000 })
    expect(await inbox.locator('article.item').count()).toBe(1)
    expect((await card.locator('.badge').innerText()).trim().toLowerCase()).toBe('verified')
    expect((await card.locator('.from').innerText()).trim()).toBe(`from ${OWNER_NAMES_THEM}`)
    expect((await card.locator('.note').innerText()).trim()).toBe(`“${ASSIGNMENT_NOTE}”`)
  })

  expect(pageErrors, 'uncaught errors in either page').toEqual([])
  log(`all ${stepNo} steps passed`)
})
