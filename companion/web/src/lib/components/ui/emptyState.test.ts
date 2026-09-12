/*
 * "YET" IS BANNED FROM EVERY EMPTY STATE, ON BOTH SURFACES (issue #103).
 *
 * WHY A WORD BAN RATHER THAN A JUDGEMENT. "No X yet" is not a time marker. It presupposes the
 * person will do X and dates their not having done it — which, on a mental-health tool, is a tool
 * deciding on no evidence that more logging is better and quietly keeping score. "No X" conveys the
 * same fact and asserts nothing about the reader.
 *
 * The rule has no defined exception, because an adjudicated rule survives exactly as long as the
 * next person to write a string agrees with the last one. A word ban is a test.
 *
 * WHERE THE BAN APPLIES, stated so the boundary is mechanical rather than argued each time:
 *
 *   - WEB: every <EmptyState> call site, including titles supplied through a copy constant — the
 *     indirection is followed here, or moving a string into a module would be a way around the
 *     rule.
 *   - ANDROID: every string literal in app/src/main/java. Wider than "empty states" deliberately,
 *     because Compose has no EmptyState component to anchor on and a list of blessed files is a
 *     list that goes stale. Comments are stripped first, so prose ABOUT the rule does not trip it.
 *
 * Outside that boundary "yet" is still a word: a placeholder saying a feature is not built, an
 * operator diagnostic saying a probe has not been called, a membership that is an offer rather than
 * an acceptance. None of those date a person's inaction, which is the thing the ban is for.
 */
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const WEB_SRC = fileURLToPath(new URL('../../../', import.meta.url))
const ANDROID_SRC = fileURLToPath(new URL('../../../../../../app/src/main/java/', import.meta.url))

const YET = /\byet\b/i

function walk(dir: string, ext: string[]): string[] {
  const out: string[] = []
  for (const name of readdirSync(dir)) {
    const full = dir + name
    if (statSync(full).isDirectory()) out.push(...walk(full + '/', ext))
    else if (ext.some((e) => name.endsWith(e))) out.push(full)
  }
  return out
}

const stripSvelteComments = (s: string) => s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '')
const stripKotlinComments = (s: string) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) Every <EmptyState> call site on the web.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** `export const NAME = '…'` across the web source, so a title moved into a module is still read. */
const constants = new Map<string, string>()
for (const file of walk(WEB_SRC, ['.ts'])) {
  if (file.endsWith('.test.ts')) continue
  const src = readFileSync(file, 'utf8')
  for (const m of src.matchAll(/export const ([A-Z][A-Z0-9_]*) =\s*((?:'[^']*'|"[^"]*")(?:\s*\+\s*(?:'[^']*'|"[^"]*"))*)/g)) {
    constants.set(m[1], m[2].replace(/['"]\s*\+\s*['"]/g, '').replace(/^['"]|['"]$/g, ''))
  }
}

/** Each <EmptyState …> call site as the text a person would read. */
function emptyStateText(source: string): string[] {
  const out: string[] = []
  for (const m of source.matchAll(/<EmptyState\b([\s\S]*?)(\/>|<\/EmptyState>)/g)) {
    let text = m[0]
    /*
     * Follow every SHOUTY_CASE identifier in the call site, not just a bare `title={CONST}` —
     * RosterPanel's title is a ternary (`noPractice ? '…' : EMPTY_ROSTER_TITLE`), and a rule that
     * only saw the simple form would be dodged by writing a conditional.
     */
    for (const ref of text.matchAll(/\b([A-Z][A-Z0-9_]{2,})\b/g)) {
      const resolved = constants.get(ref[1])
      if (resolved) text += ` ${resolved}`
    }
    out.push(text)
  }
  return out
}

const svelteFiles = walk(WEB_SRC, ['.svelte'])
const callSites = svelteFiles.flatMap((f) => emptyStateText(stripSvelteComments(readFileSync(f, 'utf8'))).map((t) => [f, t] as const))

describe('(a) the web empty states', () => {
  it('finds call sites at all, and resolves a title held in a copy module', () => {
    // Non-vacuity. A scanner that matched nothing would pass every assertion below, which is the
    // exact failure this repository keeps re-finding.
    expect(callSites.length).toBeGreaterThan(8)
    expect(constants.has('EMPTY_ROSTER_TITLE')).toBe(true)
    const resolved = callSites.find(([f]) => f.includes('RosterPanel'))
    expect(resolved?.[1]).toContain('No roster read')
  })

  it('the detector catches a planted "yet"', () => {
    const planted = '<EmptyState title="No readings yet"><p>Nothing has arrived.</p></EmptyState>'
    expect(emptyStateText(planted).some((t) => YET.test(t))).toBe(true)
    // And does not fire on an ordinary one, or the assertion below could never hold.
    expect(emptyStateText('<EmptyState title="No readings" />').some((t) => YET.test(t))).toBe(false)
  })

  it('no call site says "yet"', () => {
    const offenders = callSites.filter(([, t]) => YET.test(t)).map(([f, t]) => `${f}: ${t.slice(0, 70)}`)
    expect(offenders).toEqual([])
  })

  it('no call site shouts', () => {
    // The other half of the same rule, and the one that was already house style.
    const planted = '<EmptyState title="No entries yet!" />'
    expect(planted).toContain('!')
    expect(callSites.filter(([, t]) => /!/.test(t.replace(/!==?/g, ''))).map(([f]) => f)).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) Every string literal in the Android app.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) the Android empty states', () => {
  const literals: { file: string; text: string }[] = []
  for (const file of walk(ANDROID_SRC, ['.kt'])) {
    const src = stripKotlinComments(readFileSync(file, 'utf8'))
    for (const m of src.matchAll(/"((?:[^"\\\n]|\\.)*)"/g)) {
      literals.push({ file, text: m[1] })
    }
  }

  it('reads the Android source at all', () => {
    // Same non-vacuity guard: if the relative path to app/ ever breaks, this fails loudly rather
    // than reporting that every Android string is clean.
    expect(literals.length).toBeGreaterThan(500)
    expect(literals.some((l) => l.text === 'No goals')).toBe(true)
  })

  it('the detector catches a planted "yet"', () => {
    const planted = stripKotlinComments('val s = "No goals yet"')
    const found = [...planted.matchAll(/"((?:[^"\\\n]|\\.)*)"/g)].map((m) => m[1])
    expect(found.some((t) => YET.test(t))).toBe(true)
    // Comments are stripped first, so prose about the rule is not an offender.
    expect(stripKotlinComments('// "nothing yet" is banned\nval s = "No goals"')).not.toContain('nothing yet')
  })

  it('no string literal says "yet"', () => {
    const offenders = literals.filter((l) => YET.test(l.text)).map((l) => `${l.file}: ${l.text.slice(0, 60)}`)
    expect(offenders).toEqual([])
  })
})
