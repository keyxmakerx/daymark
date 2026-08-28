import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { trustPostureFor, type TrustPosture } from './posture'
import { OWNER_ROUTES } from '../onboarding/audience'
import type { SetupSurface } from '../setup/shape'

/**
 * THE ONE RULE THE TRUST STRIP CANNOT BE ALLOWED TO BREAK.
 *
 *     a page that is reaching the network is never described as sending nothing.
 *
 * This mapping used to be a ternary inside App.svelte: `sync` named, `owner` and `recover` named,
 * everything else falling through to `local` — the posture whose copy reads "This tab reads your
 * backup in the browser and sends nothing." A fall-through is a safe default only while every
 * request the page can make belongs to one of the named tabs. It stopped being one the day a
 * configuration probe was added on load: the request belonged to no tab at all, so the default
 * carried on promising silence over a page that had just spoken.
 *
 * The fix is not a fourth name in the ternary — that is the same construction with one more thing
 * to remember. It is that the mapping takes "is this load reaching the network" as an input, so
 * the property below is checkable over the whole surface union rather than over the three
 * surfaces somebody thought of.
 */

/** Every surface App.svelte can be showing: the owner's six, plus the practice panel. */
const SURFACES: SetupSurface[] = [...OWNER_ROUTES.map((r) => r.id), 'practice']

describe('the trust posture', () => {
  it('covers every surface the page can show', () => {
    // Non-vacuity for the sweeps below: an empty list would make all of them pass over nothing.
    expect(SURFACES.length).toBe(7)
    expect(SURFACES).toContain('file')
    expect(SURFACES).toContain('practice')
    expect(new Set(SURFACES).size).toBe(SURFACES.length)
  })

  it('never says “sends nothing” about a load that is reading configuration', () => {
    /*
     * THE REGRESSION, as a property rather than as a list. Before this, the file tab — the default
     * surface, and where a Solo owner lands — resolved to `local` while App.svelte's unconditional
     * probe was in flight, so the strip promised silence directly above a panel that said it was
     * reading /v1/config.
     */
    for (const surface of SURFACES) {
      expect(trustPostureFor(surface, true), surface).not.toBe('local')
    }
    // And the surfaces that have no server-facing job of their own say exactly what is happening.
    expect(trustPostureFor('file', true)).toBe('setup')
    expect(trustPostureFor('assess', true)).toBe('setup')
    expect(trustPostureFor('build', true)).toBe('setup')
    expect(trustPostureFor('practice', true)).toBe('setup')
  })

  it('lets the surfaces that talk to a server keep their own, louder sentence', () => {
    // `sync` and `account` already state that this tab reaches the server, and both say more
    // about what is at stake than `setup` does. Downgrading them would trade precision for
    // vagueness; what must never happen is the opposite direction, which the sweep above pins.
    for (const reading of [true, false]) {
      expect(trustPostureFor('sync', reading)).toBe('sync')
      expect(trustPostureFor('owner', reading)).toBe('account')
      expect(trustPostureFor('recover', reading)).toBe('account')
    }
  })

  it('says nothing leaves only when nothing is leaving', () => {
    for (const surface of SURFACES) {
      const quiet = trustPostureFor(surface, false)
      const posture: TrustPosture = quiet
      expect(['local', 'sync', 'account']).toContain(posture)
    }
    // The settled offline surfaces, which are the ones the promise is for.
    expect(trustPostureFor('file', false)).toBe('local')
    expect(trustPostureFor('assess', false)).toBe('local')
    expect(trustPostureFor('build', false)).toBe('local')
    // The practice panel reads nothing and holds no client, so it is as local as an unopened
    // dropzone. The page that talks to a server about a practice is practice.html.
    expect(trustPostureFor('practice', false)).toBe('local')
    // `setup` is reachable ONLY through a live configuration read — it is not a tab.
    expect(SURFACES.map((s) => trustPostureFor(s, false))).not.toContain('setup')
  })

  it('is the mapping TrustBar actually branches on', () => {
    // A posture this function can return and the component cannot print would be a blank strip on
    // the one screen that must not be silent about itself.
    const bar = readFileSync(
      fileURLToPath(new URL('../components/TrustBar.svelte', import.meta.url)),
      'utf8',
    )
    expect(bar).toContain("import type { TrustPosture }")
    const printed: TrustPosture[] = ['local', 'setup', 'sync', 'account']
    const reachable = new Set<TrustPosture>()
    for (const surface of SURFACES) {
      for (const reading of [true, false]) reachable.add(trustPostureFor(surface, reading))
    }
    expect([...reachable].sort()).toEqual([...printed].sort())
    // `account` is the else-branch, so three of the four are named in the markup.
    for (const posture of ['local', 'setup', 'sync'] as const) {
      expect(bar, `TrustBar has no branch for ${posture}`).toContain(`surface === '${posture}'`)
    }
  })
})
