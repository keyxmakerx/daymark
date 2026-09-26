/*
 * The command-line writer, run the way a person runs it: `pnpm push`, spawned from this package.
 *
 * WHY SPAWN. The writer's code is imported by other tests through vitest's own resolver, which
 * reads vite.config.ts; the command does not go through vitest at all. When package.json ran this
 * file with tsx, which ignores that config, libsodium-wrappers-sumo's ESM build failed to resolve
 * and the writer died before its first line, while every test of the code it runs stayed green
 * (#373). Only running the command can see whether the command starts.
 *
 * WHAT COUNTS AS STARTED: exit 0, with the usage on stdout, from `--help`. main() prints it, and
 * main() runs only after every static import (the sync client, the sync crypto, libsodium) has been
 * resolved and evaluated, so a module that fails to load cannot produce it.
 *
 * ITS POSITIVE CONTROL is the writer's own source, copied where its imports cannot resolve and run
 * by the same command line with the same `--help`. The same predicate must call that "not
 * started". Because the copy is the real source, the control also holds the premise above: if the
 * usage were ever printed before the imports load, the control would print it and fail.
 *
 * "NOTHING WAS SENT": a listener on 127.0.0.1 counts every request the writer makes. A snapshot
 * that fits only unpadded is refused with the fixed words, and the listener counts none. ITS
 * POSITIVE CONTROL is the same run at the default limit, whose requests the listener must see: the
 * version list, then the key document (#258). The key document is asked for only after libsodium has
 * initialised (SyncClient.ensureKeys), so that second request is also the proof that the crypto
 * library runs under the command, rather than merely resolving.
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { spawn } from 'node:child_process'
import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { snapshotTooLargeText } from '../lib/sync/client'
import { snapshotBlobLength, unpaddedSnapshotBlobLength } from '../lib/sync/crypto'

const WEB = process.cwd()
const ENTRY = 'src/cli/push.ts'
const PUSH_SCRIPT: string = JSON.parse(readFileSync(resolve(WEB, 'package.json'), 'utf8')).scripts.push

/** Lines only the writer's usage contains: pnpm's own output and a stack trace contain neither. */
const USAGE_MARKS = ['Usage:', "pnpm --silent push -- --server <url> --lineage <name> --backup <file.json>"]

interface Run {
  status: number | null
  stdout: string
  stderr: string
}

/** The package manager running this suite, falling back to the one on PATH. */
function packageManager(): [string, string[]] {
  const execpath = process.env.npm_execpath
  if (execpath && /pnpm/.test(execpath)) {
    return /\.[cm]?js$/.test(execpath) ? [process.execPath, [execpath]] : [execpath, []]
  }
  return ['pnpm', []]
}

/**
 * The environment a person's shell would give the command: this process's, less what vitest adds
 * about itself and less any sync passphrase or access token the caller happens to have set.
 */
function shellEnv(extra: Record<string, string>): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) {
    if (v === undefined || k.startsWith('VITEST') || k === 'TEST' || k === 'NODE_ENV') continue
    if (k === 'DAYMARK_SYNC_PASSPHRASE' || k === 'DAYMARK_AUTH_TOKEN') continue
    env[k] = v
  }
  return { ...env, ...extra }
}

function spawnRun(args: string[], extraEnv: Record<string, string> = {}): Promise<Run> {
  const [cmd, pre] = packageManager()
  return new Promise((done, fail) => {
    const child = spawn(cmd, [...pre, ...args], { cwd: WEB, env: shellEnv(extraEnv), stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (b: Buffer) => (stdout += b.toString('utf8')))
    child.stderr.on('data', (b: Buffer) => (stderr += b.toString('utf8')))
    const timer = setTimeout(() => child.kill('SIGKILL'), 90_000)
    child.on('error', fail)
    child.on('close', (status) => {
      clearTimeout(timer)
      done({ status, stdout, stderr })
    })
  })
}

/**
 * `pnpm --silent push -- <args>`: the script package.json declares, run in the documented form.
 * `--silent` keeps pnpm's own banner out of the output the tests read.
 */
const push = (args: string[], env: Record<string, string> = {}) => spawnRun(['--silent', 'push', '--', ...args], env)

/** The same command line with only its entry swapped, for the positive control. */
function pushWithEntry(entry: string, args: string[]): Promise<Run> {
  const words = PUSH_SCRIPT.trim().split(/\s+/)
  return spawnRun(['exec', ...words.map((w) => (w === ENTRY ? entry : w)), '--', ...args])
}

function started(run: Run): boolean {
  return run.status === 0 && USAGE_MARKS.every((mark) => run.stdout.includes(mark))
}

let scratch = ''

beforeAll(() => {
  scratch = mkdtempSync(join(tmpdir(), 'daymark-push-cli-'))
})

afterAll(() => {
  if (scratch) rmSync(scratch, { recursive: true, force: true })
})

describe('pnpm push starts (#373)', () => {
  it('package.json runs the writer by its path, so the control below can swap exactly that', () => {
    expect(PUSH_SCRIPT.trim().split(/\s+/).filter((w) => w === ENTRY)).toHaveLength(1)
  })

  it('`pnpm --silent push -- --help` prints the usage and exits 0', async () => {
    const run = await push(['--help'])
    expect(started(run), `pnpm push did not start:\n${run.stderr.slice(0, 600)}`).toBe(true)
    expect(run.stderr).toBe('')
  }, 120_000)

  it('positive control: the writer with its imports cut off does not start, and the check says so', async () => {
    const copy = join(scratch, 'cut', 'cli', 'push.ts')
    mkdirSync(join(scratch, 'cut', 'cli'), { recursive: true })
    writeFileSync(copy, readFileSync(resolve(WEB, ENTRY)))
    const run = await pushWithEntry(copy, ['--help'])
    expect(run.stderr).toContain('lib/sync/client')
    expect(run.stdout).not.toContain(USAGE_MARKS[0])
    expect(started(run)).toBe(false)
  }, 120_000)
})

describe('pnpm push refuses a snapshot too large once padded, and sends nothing (#315)', () => {
  let listener: Server
  let base = ''
  const seen: string[] = []
  const auth: (string | undefined)[] = []

  beforeAll(async () => {
    // Every request is counted and refused, so no run here can store anything or derive a key.
    listener = createServer((req, res) => {
      seen.push(`${req.method} ${req.url}`)
      auth.push(req.headers.authorization)
      res.writeHead(401, { 'content-type': 'application/json' }).end('{"error":"unauthorized"}')
    })
    await new Promise<void>((ready) => listener.listen(0, '127.0.0.1', ready))
    base = `http://127.0.0.1:${(listener.address() as AddressInfo).port}`
  })

  afterAll(async () => {
    await new Promise<void>((closed) => listener.close(() => closed()))
  })

  // A synthetic backup: no entries, made up for this test.
  const backup = () => {
    const path = join(scratch, 'backup.json')
    writeFileSync(path, JSON.stringify({ version: 6, exportedAt: 0, entries: [], activities: [] }))
    return path
  }
  const args = (extra: string[]) => ['--server', base, '--lineage', 'cli-test', '--backup', backup(), ...extra]
  const TOKEN = 'spawn-test-token'
  const env = { DAYMARK_SYNC_PASSPHRASE: 'a passphrase used only by this test', DAYMARK_AUTH_TOKEN: TOKEN }

  it('over --max-blob-bytes only once padded: the fixed refusal, and the listener counts no request', async () => {
    const length = readFileSync(backup()).length
    const padded = snapshotBlobLength(length)
    const unpadded = unpaddedSnapshotBlobLength(length)
    // A limit between the two sizes: the case where padding alone is what takes it over.
    const limit = padded - 1
    expect(unpadded).toBeLessThanOrEqual(limit)

    seen.length = 0
    const run = await push(args(['--max-blob-bytes', String(limit)]), env)
    expect(run.stderr).toContain(`push failed: ${snapshotTooLargeText(padded, unpadded, limit)}\n`)
    expect(run.status).toBe(1)
    expect(run.stderr).toContain('run this again with --max-blob-bytes set to the same number.')
    expect(run.stdout).toBe('')
    expect(seen).toEqual([])
  }, 120_000)

  it('positive control: at the default limit the listener sees the requests, the second after libsodium is up', async () => {
    seen.length = 0
    auth.length = 0
    const run = await push(args([]), env)
    expect(run.status).toBe(1)
    expect(run.stderr).toContain('push failed: key document fetch failed')
    expect(seen).toEqual(['GET /v1/snapshots/cli-test', 'GET /v1/keydoc'])
    // The token that reached the server is the one from the environment (#384).
    expect(auth).toEqual([`Bearer ${TOKEN}`, `Bearer ${TOKEN}`])
  }, 120_000)
})

describe('pnpm push takes the access token from the environment, never the command line (#384)', () => {
  let listener: Server
  let base = ''
  const seen: string[] = []

  beforeAll(async () => {
    listener = createServer((req, res) => {
      seen.push(`${req.method} ${req.url}`)
      res.writeHead(401, { 'content-type': 'application/json' }).end('{"error":"unauthorized"}')
    })
    await new Promise<void>((ready) => listener.listen(0, '127.0.0.1', ready))
    base = `http://127.0.0.1:${(listener.address() as AddressInfo).port}`
  })

  afterAll(async () => {
    await new Promise<void>((closed) => listener.close(() => closed()))
  })

  const backup = () => {
    const path = join(scratch, 'backup-token.json')
    writeFileSync(path, JSON.stringify({ version: 6, exportedAt: 0, entries: [], activities: [] }))
    return path
  }
  const passphraseOnly = { DAYMARK_SYNC_PASSPHRASE: 'a passphrase used only by this test' }
  // Distinctive, so finding it (or not) in the output means something.
  const ARG_TOKEN = 'token-typed-on-the-command-line-7f3a'

  it('a --token argument is refused before anything is sent, and never repeated', async () => {
    seen.length = 0
    const run = await push(['--server', base, '--token', ARG_TOKEN, '--lineage', 'cli-test', '--backup', backup()], {
      ...passphraseOnly,
      DAYMARK_AUTH_TOKEN: 'an-environment-token-that-would-otherwise-be-used',
    })
    expect(run.status).toBe(1)
    expect(run.stderr).toContain(
      'push failed: the access token is read from DAYMARK_AUTH_TOKEN, never from the command line, where other users of this machine could read it. Nothing was sent.',
    )
    expect(run.stdout + run.stderr).not.toContain(ARG_TOKEN)
    expect(seen).toEqual([])
  }, 120_000)

  it('with no DAYMARK_AUTH_TOKEN, it says so and sends nothing', async () => {
    seen.length = 0
    const run = await push(['--server', base, '--lineage', 'cli-test', '--backup', backup()], passphraseOnly)
    expect(run.status).toBe(1)
    expect(run.stderr).toContain('push failed: set DAYMARK_AUTH_TOKEN in the environment (the server access token)')
    expect(seen).toEqual([])
  }, 120_000)
})
