import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { CONSOLE_POINTER, REISSUE_DISCONNECTS, reissueLine } from './copy'
import { sameServer } from './devices'

/*
 * WHAT THE REST OF THE OWNER'S PAGE SAYS ABOUT PHONES (#431): the line above the access token's
 * re-issue, and the console's pointer to where phones pair. The words are pinned verbatim; where they
 * stand is read from the components' source.
 */

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')
const RECOVER = read('../components/owner/RecoverAccess.svelte')
const CONSOLE = read('../components/owner/OwnerConsole.svelte')
const withoutComments = (src: string) => src.replace(/<!--[\s\S]*?-->/g, '')

describe('the re-issue line', () => {
  it('says what re-issuing does to the phones, with the count when one is known', () => {
    expect(REISSUE_DISCONNECTS).toBe(
      'Re-issuing your access token disconnects every paired phone. Each one must pair again before it can sync.',
    )
    expect(reissueLine(null)).toBe(REISSUE_DISCONNECTS)
    expect(reissueLine(0)).toBe(REISSUE_DISCONNECTS)
    expect(reissueLine(1)).toBe('Re-issuing your access token disconnects your 1 paired phone.')
    expect(reissueLine(3)).toBe('Re-issuing your access token disconnects all 3 paired phones.')
  })

  it('stands directly above the button that re-issues the token, whatever the state of the card', () => {
    const markup = withoutComments(RECOVER)
    const step = markup.indexOf('<legend>2. Confirm the link</legend>')
    const line = markup.indexOf('<p class="para reissue">{reissue}</p>')
    const button = markup.indexOf("'Confirm and re-issue'")
    expect(step).toBeGreaterThan(-1)
    expect(line).toBeGreaterThan(step)
    expect(button).toBeGreaterThan(line)
    // Nothing but the button's own tag between them, and no condition around the line.
    expect(markup.slice(line, button)).toMatch(/^<p class="para reissue">\{reissue\}<\/p>\s*<button class="primary" onclick=\{confirm\}[^>]*>\{busy \? 'Confirming…' : $/)
    expect(markup.slice(step, line)).not.toMatch(/\{#if/)
    // Positive control: a condition put around it is seen.
    const hidden = markup.replace('<p class="para reissue">', '{#if connection}<p class="para reissue">')
    expect(hidden).not.toBe(markup)
    expect(hidden.slice(step, hidden.indexOf('<p class="para reissue">'))).toMatch(/\{#if/)
  })

  it('counts the phones still connected, through the connection this visit proved', () => {
    expect(RECOVER).toMatch(/devicesApi\(c\.serverUrl, c\.token\)\.listDevices\(\)/)
    expect(RECOVER).toMatch(/phones\.filter\(\(p\) => p\.revokedAt === null\)\.length/)
    expect(RECOVER).toMatch(/const reissue = \$derived\(reissueLine\(countApplies \? connectedPhones : null\)\)/)
  })

  it("shows the count only while the card's own Server URL names the server it was read from (#434)", () => {
    expect(RECOVER).toMatch(
      /const countApplies = \$derived\(connection !== null && sameServer\(serverUrl, connection\.serverUrl, location\.origin\)\)/,
    )
    const page = 'https://daymark.example.com'
    // Blank is this page's own server, however the other side spells it.
    expect(sameServer('', '', page)).toBe(true)
    expect(sameServer('', 'https://daymark.example.com/', page)).toBe(true)
    expect(sameServer(' https://DAYMARK.example.com// ', 'https://daymark.example.com', page)).toBe(true)
    // Another server is not this one: the count would describe phones the re-issue never touches.
    expect(sameServer('https://other.example.com', '', page)).toBe(false)
    expect(sameServer('', 'https://other.example.com', page)).toBe(false)
    expect(sameServer('https://daymark.example.com:8443', '', page)).toBe(false)
    expect(sameServer('https://daymark.example.com/a', 'https://daymark.example.com/b', page)).toBe(false)
    // An address that does not parse names nothing, not even another one that does not parse.
    expect(sameServer('http://', 'http://', page)).toBe(false)
  })
})

describe("the owner console's pointer", () => {
  it('says where phones pair, in one line, inside the Server connection panel', () => {
    expect(CONSOLE_POINTER).toBe("Phones pair from the Connect to your sync server card on the owner's page.")
    const markup = withoutComments(CONSOLE)
    const panel = markup.slice(markup.indexOf('<details class="conn">'), markup.indexOf('</details>'))
    expect(panel).toContain('<summary>Server connection')
    expect(panel).toContain('{CONSOLE_POINTER}')
    // Positive control: the slice is the panel, not the whole file.
    expect(panel).not.toContain('<SharingStrip')
  })
})
