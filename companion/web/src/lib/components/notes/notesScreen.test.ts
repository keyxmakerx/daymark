import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

/*
 * The notes screen's promises, read from its source (web tests have no DOM).
 * Each absence is paired with a presence the same reading finds.
 */
const src = readFileSync(new URL('./NotesScreen.svelte', import.meta.url), 'utf8')
const portal = readFileSync(new URL('../therapist/TherapistPortal.svelte', import.meta.url), 'utf8')

describe('the notes screen', () => {
  it('sends nothing anywhere', () => {
    expect(src).toContain("from '../../notes/vault'")
    expect(src).not.toMatch(/\bfetch\s*\(|XMLHttpRequest|sendBeacon|\.client\./)
  })
  it('says the plain copy is not locked, beside the button that makes it', () => {
    const i = src.indexOf('Download a plain copy')
    expect(i).toBeGreaterThan(-1)
    expect(src.slice(i, i + 300)).toContain('The download is not locked.')
  })
  it('reports a notebook that did not open, and offers no way to write over it', () => {
    const start = src.indexOf("{:else if loaded === 'unreadable'}")
    const end = src.indexOf('{:else}', start)
    expect(start).toBeGreaterThan(-1)
    const block = src.slice(start, end)
    expect(block).toContain('Nothing has been written over them.')
    expect(block).not.toContain('<button')
    expect(block).not.toContain('<form')
  })
  it('asks before deleting a note', () => {
    expect(src).toContain('Delete this note? It cannot be brought back.')
  })
  it('is reachable from the console without any grant, as the clinician\'s own', () => {
    expect(portal).toMatch(/label: 'Tools', items: \[\{ id: 'notes', label: 'Notes' \}/)
    const at = portal.indexOf("{#if tab === 'notes'}")
    expect(at).toBeGreaterThan(-1)
    expect(at).toBeLessThan(portal.indexOf("{:else if grant && tab !== 'timers'}"))
  })
})
