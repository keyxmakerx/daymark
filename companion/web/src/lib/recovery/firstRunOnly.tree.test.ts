/*
 * A RANDOM MASTER IS MADE IN ONE PLACE: A FIRST RUN (#258).
 *
 * The decision on #258: an owner with an archive already has a master, the one their passphrase
 * derives, and a second, random one would be a second lineage, a changed manifest key that looks
 * exactly like a hostile swap, and a pairing identity the server's insert-only owner_keys cannot
 * even record. So the random-key path — createRecoverableDataKey() and the newDataKey() it calls —
 * survives only for a brand-new owner on a server holding no key document of any kind, which is
 * serverKey.ts's firstRun(). This walks every source file that ships and holds the tree to that:
 * the names appear in dataKey.ts, which defines them, and in serverKey.ts, which calls one of them
 * inside firstRun() and nowhere else.
 *
 * Commentary is stripped before matching, so a comment that names the function (this one does)
 * cannot stand in for a call, and a call cannot hide behind one.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/** This file is at src/lib/recovery/, so two levels up is src/. */
const SRC = fileURLToPath(new URL('../../', import.meta.url))
const rel = (abs: string) => 'src/' + abs.slice(SRC.length)

function walk(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = dir + entry.name
    if (entry.isDirectory()) out.push(...walk(path + '/'))
    else if (/\.(svelte|ts)$/.test(entry.name) && !/\.test\.ts$/.test(entry.name)) out.push(path)
  }
  return out.sort()
}

const codeOnly = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')

const shipped = new Map(walk(SRC).map((f) => [rel(f), codeOnly(readFileSync(f, 'utf8'))]))

const RANDOM_KEY = /\b(createRecoverableDataKey|newDataKey)\b/
const CALL = /\b(createRecoverableDataKey|newDataKey)\s*\(/g
const DEFINITION = /\bfunction\s+(createRecoverableDataKey|newDataKey)\s*\(/

/** The body of a top-level function: from its declaration to the first line that is a lone `}`. */
function bodyOf(code: string, declaration: string): string {
  const start = code.indexOf(declaration)
  if (start < 0) return ''
  const end = code.indexOf('\n}\n', start)
  return code.slice(start, end < 0 ? undefined : end + 2)
}

/** Every call of the random-key functions in `code` that is not inside `allowedBody`. */
function callsOutside(code: string, allowedBody: string): string[] {
  const inside = allowedBody ? code.indexOf(allowedBody) : -1
  const stray: string[] = []
  for (const m of code.matchAll(CALL)) {
    const at = m.index ?? 0
    if (DEFINITION.test(code.slice(Math.max(0, at - 9), at + m[0].length))) continue
    if (inside >= 0 && at >= inside && at < inside + allowedBody.length) continue
    stray.push(m[0])
  }
  return stray
}

/** Files that name either function in what ships. */
const naming = (files: Map<string, string>) => [...files].filter(([, code]) => RANDOM_KEY.test(code)).map(([p]) => p)

describe('the random-key path is reachable only from a first run', () => {
  it('found the tree, and the two files the path lives in', () => {
    expect(shipped.size).toBeGreaterThan(100)
    expect(shipped.has('src/lib/recovery/dataKey.ts')).toBe(true)
    expect(shipped.has('src/lib/recovery/serverKey.ts')).toBe(true)
    expect(bodyOf(shipped.get('src/lib/recovery/serverKey.ts')!, 'async function firstRun(')).toContain('createRecoverableDataKey(')
  })

  it('no shipped file but dataKey.ts and serverKey.ts names either function', () => {
    expect(naming(shipped)).toEqual(['src/lib/recovery/dataKey.ts', 'src/lib/recovery/serverKey.ts'])
  })

  it('serverKey.ts calls it inside firstRun() and nowhere else, and never calls newDataKey()', () => {
    const code = shipped.get('src/lib/recovery/serverKey.ts')!
    expect(callsOutside(code, bodyOf(code, 'async function firstRun('))).toEqual([])
    expect(code).not.toMatch(/\bnewDataKey\b/)
  })

  it('dataKey.ts calls newDataKey() only to make a new recoverable key', () => {
    const code = shipped.get('src/lib/recovery/dataKey.ts')!
    expect(callsOutside(code, bodyOf(code, 'export async function createRecoverableDataKey('))).toEqual([])
  })

  it('positive controls: a planted call in a screen, and one outside firstRun(), are both seen', () => {
    // The screen that made a random key on its own before #258, planted back.
    const planted = new Map(shipped)
    planted.set('src/lib/components/recovery/NewCodeFlow.svelte', 'const made = await createRecoverableDataKey(passphrase)')
    expect(naming(planted)).toContain('src/lib/components/recovery/NewCodeFlow.svelte')
    // An enrolment that reached for the random path, planted into serverKey.ts outside firstRun().
    const code = shipped.get('src/lib/recovery/serverKey.ts')!
    const enrol = bodyOf(code, 'async function enrol(')
    expect(enrol.length).toBeGreaterThan(0)
    const opening = '): Promise<SetUpResult> {'
    expect(enrol).toContain(opening)
    const mutated = code.replace(enrol, enrol.replace(opening, `${opening}\n  await createRecoverableDataKey(passphrase)`))
    expect(mutated).not.toBe(code)
    expect(callsOutside(mutated, bodyOf(mutated, 'async function firstRun('))).toEqual(['createRecoverableDataKey('])
  })
})
