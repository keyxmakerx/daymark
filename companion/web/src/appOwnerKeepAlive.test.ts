import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/*
 * Leaving the owner console for another route must not unmount it (#383): its unlocked session
 * and every clinician added in it live inside the component.
 */
const APP = readFileSync(fileURLToPath(new URL('./App.svelte', import.meta.url)), 'utf8')

/** The markup of a branch that renders the console only while its route is chosen: the fault. */
const UNMOUNTING = "{:else}\n  <OwnerConsole {data} />\n{/if}"

describe('the owner console survives a route change', () => {
  it('positive control: the detector sees the unmounting shape when it is planted', () => {
    const planted = "{:else if source === 'practice'}\n<P />\n{:else}\n  <OwnerConsole {data} />\n{/if}"
    expect(planted.replace(/\s+/g, ' ')).toContain(UNMOUNTING.replace(/\s+/g, ' '))
  })

  it('the console is not rendered inside the route chain', () => {
    expect(APP.replace(/\s+/g, ' ')).not.toContain(UNMOUNTING.replace(/\s+/g, ' '))
    expect(APP).not.toMatch(/\{:else\}\s*(<!--[\s\S]*?-->\s*)?<OwnerConsole/)
  })

  it('it is mounted once opened and then only hidden', () => {
    expect(APP).toMatch(/\{#if ownerOpened\}\s*<div hidden=\{!ownerShown\}><OwnerConsole \{data\} \/><\/div>\s*\{\/if\}/)
    expect(APP).toContain("const ownerShown = $derived(source === 'owner')")
    expect(APP).toMatch(/if \(ownerShown\) ownerOpened = true/)
  })
})
