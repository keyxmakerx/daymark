import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/*
 * #434 item 1: the first visit to a server with nothing on it is not a failure. The sync card has a
 * Connect that proves the address and token without the passphrase, and a pull with no passphrase
 * that proves them says so in plain ink, not in the alarm style.
 */
const source = readFileSync(fileURLToPath(new URL('./SyncPanel.svelte', import.meta.url)), 'utf8')

function body(name: string): string {
  const start = source.indexOf(`async function ${name}(`)
  expect(start, `no ${name}`).toBeGreaterThan(-1)
  const next = source.indexOf('\n  async function ', start + 1)
  const end = source.indexOf('\n  }\n', start)
  return source.slice(start, next > -1 && next < end ? next : end)
}

describe('connecting to the sync server', () => {
  it('has a Connect that needs no passphrase', () => {
    expect(source).toMatch(/<button onclick=\{connect\}[^>]*>Connect<\/button>/)
    const connect = body('connect')
    expect(connect).toContain('proveToken(')
    expect(connect).not.toMatch(/passphrase\b(?!\s+and fetch)/)
    // The control: the fetch does read the passphrase, and the scan sees it there.
    expect(body('fetchAndDecrypt')).toMatch(/passphrase\b(?!\s+and fetch)/)
  })

  it('says a proved connection as a note, and keeps the alarm for what did not work', () => {
    const fetch = body('fetchAndDecrypt')
    const proved = fetch.slice(fetch.indexOf('if (await proveToken(tried))'), fetch.indexOf('} else {'))
    expect(proved).toContain('note = ')
    expect(proved).not.toContain('error = ')
    expect(source).toMatch(/\{:else if note\}\s*<p class="note" role="status">/)
    expect(source).toMatch(/<p class="error" role="alert">/)
  })
})
