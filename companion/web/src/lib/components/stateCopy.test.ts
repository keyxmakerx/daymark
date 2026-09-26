import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

/*
 * A COMPONENT COPIES STATE WITH $state.snapshot, NEVER structuredClone.
 *
 * Every object a component holds in $state is a Proxy, and structuredClone refuses every Proxy
 * ("#<Object> could not be cloned"). The node suite renders no component, so such a call passes
 * every test here and throws in every browser, where only e2e/paired-loop.test.mts would meet it.
 * $state.snapshot is the copy that works, for state and for plain values alike, so the rule for
 * components is simply to use it.
 *
 * Scanned tree-wide, so a new component is covered without anyone adding it here. Only code in a
 * script block counts: a comment or a sentence in the markup that names the function is not a call.
 */

const SRC = fileURLToPath(new URL('../..', import.meta.url))

function svelteFiles(dir: string, acc: string[] = []): string[] {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isDirectory()) svelteFiles(p, acc)
    else if (e.name.endsWith('.svelte')) acc.push(p)
  }
  return acc
}

const scriptsOf = (source: string) => [...source.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]).join('\n')
const codeOnly = (source: string) => source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(?<!:)\/\/[^\n]*/g, '')
const callsStructuredClone = (source: string) => /\bstructuredClone\s*\(/.test(codeOnly(scriptsOf(source)))

describe('components copy state with $state.snapshot, never structuredClone', () => {
  const files = svelteFiles(SRC)

  it('found the components, and the check sees a planted call and nothing else', () => {
    expect(files.length).toBeGreaterThan(50)
    expect(files.some((f) => f.endsWith(join('owner', 'GrantManager.svelte')))).toBe(true)
    // The shape that shipped.
    expect(callsStructuredClone('<script lang="ts">\n  onGrantChange(structuredClone(draft))\n</script>')).toBe(true)
    // Named in a comment, or in the markup, it is not a call.
    expect(
      callsStructuredClone('<script>\n  // structuredClone(draft) threw here\n  /* structuredClone(x) */\n</script>\n<p>structuredClone(x)</p>'),
    ).toBe(false)
  })

  it('no component script calls structuredClone', () => {
    const offenders = files.filter((f) => callsStructuredClone(readFileSync(f, 'utf8'))).map((f) => relative(SRC, f))
    expect(offenders, 'a $state value is a Proxy, which structuredClone refuses; copy it with $state.snapshot').toEqual([])
  })
})
