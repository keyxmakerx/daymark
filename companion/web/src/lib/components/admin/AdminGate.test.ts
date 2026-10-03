/*
 * The door to the server console (#322), asserted over its source, as AdminConsole.test.ts does:
 * there is no DOM in this suite. The requests and the words are lib/admin/claim.ts's, tested
 * there; what is left is that the console is behind the door and the secrets are handled as the
 * header says. Every absence assertion carries a control that plants the thing and sees it.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const read = (rel: string) => readFileSync(resolve(process.cwd(), rel), 'utf8')
const SOURCE = read('src/lib/components/admin/AdminGate.svelte')
const ENTRY = read('src/admin.ts')
const SCRIPT = SOURCE.match(/<script[^>]*>([\s\S]*?)<\/script>/)?.[1] ?? ''
const MARKUP = SOURCE.replace(/<!--[\s\S]*?-->/g, '').replace(/<script[\s\S]*?<\/script>/g, '').replace(/<style[\s\S]*?<\/style>/g, '')

describe('the console is behind the door', () => {
  it('the entry point mounts the gate, not the console', () => {
    expect(ENTRY).toMatch(/mount\(AdminGate,/)
    expect(ENTRY).not.toMatch(/mount\(AdminConsole,/)
    // Control: the same pattern sees a console mount when there is one.
    expect(ENTRY.replace('mount(AdminGate,', 'mount(AdminConsole,')).toMatch(/mount\(AdminConsole,/)
  })

  it('renders the console only on the signed-in screen', () => {
    const at = MARKUP.indexOf('<AdminConsole')
    expect(at).toBeGreaterThan(-1)
    expect(MARKUP.lastIndexOf('{#if', at)).toBe(MARKUP.indexOf("{#if screen === 'console'}"))
    expect(MARKUP.indexOf('<AdminConsole', at + 1)).toBe(-1)
  })

  it('reaches the console only through enter() or a session the overview accepted', () => {
    const sets = [...SCRIPT.matchAll(/screen = 'console'/g)].length
    expect(sets).toBe(2)
  })
})

describe('the secrets are handled as the header says', () => {
  const persists = (src: string) => /localStorage|sessionStorage|indexedDB|document\.cookie|console\.(log|info|debug)/.test(src)

  it('stores nothing in the browser, reads no cookie and logs nothing', () => {
    expect(persists(SCRIPT)).toBe(false)
    expect(persists(SCRIPT + '\nlocalStorage.setItem("k", csrfToken)')).toBe(true)
  })

  it('the setup code field is a password field', () => {
    const field = MARKUP.slice(MARKUP.indexOf('Setup code, from'), MARKUP.indexOf('bind:value={setupCode}'))
    expect(field).toMatch(/type="password"/)
    expect(field.replace('type="password"', 'type="text"')).not.toMatch(/type="password"/)
  })

  it('no message renders what was typed: refusals come only from `problem`, set from claim.ts', () => {
    const echoes = /(?<!bind:value=)\{(setupCode|digits|name)\}/
    expect(MARKUP).not.toMatch(echoes)
    expect(`${MARKUP}<p>{setupCode}</p>`).toMatch(echoes)
    const sets = [...SCRIPT.matchAll(/problem = ([^\n]+)/g)]
    expect(sets.length, 'the loop below must have something to check').toBeGreaterThan(3)
    for (const m of sets) {
      expect(m[1], 'problem is only ever a message from claim.ts, or cleared').toMatch(/^(''|\$state\(''\)|\w+\.message)$/)
    }
  })

  it('zeroes the seed once the claim succeeds', () => {
    expect(SCRIPT).toMatch(/seed\.fill\(0\)\s*\n\s*seed = null/)
  })

  it('never sends an Authorization header of its own', () => {
    expect(/Authorization|Bearer/.test(SCRIPT)).toBe(false)
    expect(/Authorization|Bearer/.test(SCRIPT + 'Bearer x')).toBe(true)
  })
})
