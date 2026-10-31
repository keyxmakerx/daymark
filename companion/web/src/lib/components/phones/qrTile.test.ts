import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

/*
 * The invitation link can be shown as a QR code, for a clinician in the room (Phone mode). Asserted
 * structurally over the source: web tests run in node with no DOM (CLAUDE.md §5).
 */
const read = (p: string) => readFileSync(new URL(p, import.meta.url), 'utf8')
const PANEL = read('../owner/InvitePanel.svelte')
const TILE = read('./QrTile.svelte')

describe('the invitation as a code', () => {
  it('draws the invitation link itself, and nothing else, as the code', () => {
    expect(PANEL).toMatch(/<QrTile text=\{invite\.link\} \/>/)
    // Control: the panel draws exactly one code, so the match above is the only one.
    expect(PANEL.match(/<QrTile\b/g)).toHaveLength(1)
  })

  it('is hidden until asked for, and hidden again for the next invitation', () => {
    expect(PANEL).toMatch(/let showCode = \$state\(false\)/)
    const another = PANEL.slice(PANEL.indexOf('function startAnother'), PANEL.indexOf('}', PANEL.indexOf('function startAnother')))
    expect(another).toContain('showCode = false')
  })

  it('says, where it is shown, that anyone who sees the screen can open it', () => {
    expect(PANEL).toContain('anyone who can see this screen can open it too')
  })

  it('draws with the vendored encoder, scan colours in every theme', () => {
    expect(TILE).toMatch(/import \{ qrDrawing \} from '\.\.\/\.\.\/phones\/qr'/)
    expect(TILE).toContain('var(--scan-paper)')
    expect(TILE).toContain('var(--scan-ink)')
  })
})
