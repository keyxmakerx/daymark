import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { nextVersion, sealShare, type ShareSealPorts } from './sealShare'

/*
 * THE ORDER IS WHAT THIS FILE IS FOR (#275). Every port records its call, so a test can see that
 * whether the clinician ended the relationship is asked before anything is pinned, built, sealed or
 * sent, that a check which failed does not stop a share, and that one version is both signed and
 * published. The last block holds ShareBuilder.svelte to handing that work to sealShare: a module
 * the screen does not go through proves nothing about the screen.
 */

const ENDED_AT = Date.UTC(2026, 2, 3)

/** Stand-ins for the pin record, the bundle and the sealed share. sealShare only passes them on. */
const PINS = { stands: 'pins' } as const
const BUNDLE = { stands: 'bundle' } as const
const SEALED = { stands: 'sealed' } as const

type Answer<T> = T | 'unreachable'

interface Setup {
  /** Null is a console with no server configured. */
  server: { ending: Answer<{ endedAt: number } | null>; versions: Answer<number[]>; publishFails?: boolean } | null
  sealRefuses?: boolean
}

function harness(setup: Setup) {
  const calls: string[] = []
  /** The version each step was handed. */
  const handed: Record<string, number> = {}
  const offline = () => Promise.reject(new Error('offline'))
  const s = setup.server
  const ports: ShareSealPorts<typeof PINS, typeof BUNDLE, typeof SEALED> = {
    server: s && {
      relationshipEnding: () => {
        calls.push('ending')
        return s.ending === 'unreachable' ? offline() : Promise.resolve(s.ending)
      },
      listVersions: () => {
        calls.push('versions')
        return s.versions === 'unreachable' ? offline() : Promise.resolve(s.versions.map((version) => ({ version })))
      },
      publish: (sealed, version) => {
        calls.push('publish')
        handed.publish = version
        expect(sealed).toBe(SEALED)
        return s.publishFails ? offline() : Promise.resolve()
      },
    },
    pin: () => {
      calls.push('pin')
      return PINS
    },
    build: (version) => {
      calls.push('build')
      handed.build = version
      return BUNDLE
    },
    seal: (bundle, version, pins) => {
      calls.push('seal')
      handed.seal = version
      expect(bundle).toBe(BUNDLE)
      expect(pins).toBe(PINS)
      if (setup.sealRefuses) throw new Error('refusing to seal a share to an unpinned clinician')
      return SEALED
    },
  }
  return { ports, calls, handed }
}

const LIVE: Setup = { server: { ending: null, versions: [] } }
const ENDED: Setup = { server: { ending: { endedAt: ENDED_AT }, versions: [] } }

describe('the ending is asked about before any work on the share', () => {
  it('asks first, and after an ending nothing is pinned, built, sealed or sent', async () => {
    const live = harness(LIVE)
    await sealShare(live.ports)
    expect(live.calls).toEqual(['ending', 'versions', 'pin', 'build', 'seal', 'publish'])

    // "Nothing was sealed or sent" is literally true: nothing was even started.
    const ended = harness(ENDED)
    await sealShare(ended.ports)
    expect(ended.calls).toEqual(['ending'])
  })
})

describe('what the builder is told', () => {
  it('a clinician who ended it: refused, with the date the server gave, and nothing sent', async () => {
    const h = harness(ENDED)
    expect(await sealShare(h.ports)).toEqual({ kind: 'ended', endedAt: ENDED_AT })
    expect(h.calls).not.toContain('publish')
    // Control: the same harness does record an upload when there is one.
    const live = harness(LIVE)
    await sealShare(live.ports)
    expect(live.calls).toContain('publish')
  })

  it('a failed check is not an ending: an unreachable server does not stop the share', async () => {
    const h = harness({ server: { ending: 'unreachable', versions: [0] } })
    expect(await sealShare(h.ports)).toEqual({ kind: 'published', version: 1 })
    expect(h.calls).toContain('seal')
    expect(h.calls).toContain('publish')
  })

  it('with no server configured, asks nothing and seals here, sending nowhere', async () => {
    const h = harness({ server: null })
    expect(await sealShare(h.ports)).toEqual({ kind: 'sealed-locally', version: 0 })
    expect(h.calls).toEqual(['pin', 'build', 'seal'])
  })

  it('a seal the pins refuse sends nothing', async () => {
    const h = harness({ ...LIVE, sealRefuses: true })
    await expect(sealShare(h.ports)).rejects.toThrow(/unpinned/)
    expect(h.calls).toContain('seal')
    expect(h.calls).not.toContain('publish')
  })

  it('an upload that fails is an error for the screen, never a share called published', async () => {
    const h = harness({ server: { ending: null, versions: [], publishFails: true } })
    await expect(sealShare(h.ports)).rejects.toThrow('offline')
  })
})

describe('one version, signed and published', () => {
  it('the bundle, the seal and the upload get the same number, one past the highest published', async () => {
    const h = harness({ server: { ending: null, versions: [0, 3, 1] } })
    expect(await sealShare(h.ports)).toEqual({ kind: 'published', version: 4 })
    expect(h.handed).toEqual({ build: 4, seal: 4, publish: 4 })
  })

  it('counts from the highest version, not from how many there are', () => {
    expect(nextVersion([])).toBe(0)
    expect(nextVersion([{ version: 0 }])).toBe(1)
    expect(nextVersion([{ version: 5 }, { version: 2 }])).toBe(6)
  })
})

describe('the share builder hands its work to sealShare', () => {
  const builder = readFileSync(
    fileURLToPath(new URL('../components/owner/ShareBuilder.svelte', import.meta.url)),
    'utf8',
  )

  /** The instance script with comments removed, so a comment that names a call is not the call. */
  function scriptOf(src: string): string {
    const open = src.indexOf('<script')
    const close = src.indexOf('</script>')
    if (open < 0 || close < open) return ''
    return src
      .slice(open, close)
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/(?<!:)\/\/[^\n]*/g, '')
  }

  /** Where the object handed to sealShare begins and ends, by matching its braces. */
  function portsOf(code: string): { start: number; end: number } | null {
    const start = code.indexOf('await sealShare({')
    if (start < 0) return null
    let depth = 0
    for (let i = start + 'await sealShare('.length; i < code.length; i++) {
      if (code[i] === '{') depth++
      else if (code[i] === '}' && --depth === 0) return { start, end: i }
    }
    return null
  }

  /** Each call that asks the server, touches the pins or seals, made once and only inside the ports. */
  const WORK = ['relationshipEnding(', 'listVersions(', 'putBlob(', 'loadPins(', 'pinOnFirstUse(', 'savePins(', 'buildShare(']

  function handsItsWorkToSealShare(src: string): boolean {
    const code = scriptOf(src)
    const ports = portsOf(code)
    if (!ports) return false
    const inside = (at: number) => at > ports.start && at < ports.end
    return (
      WORK.every((call) => {
        const first = code.indexOf(call)
        return inside(first) && code.indexOf(call, first + 1) === -1
      }) &&
      // The sealed bundle is built in there too. The preview above it builds one for the counts.
      code.slice(ports.start, ports.end).includes('buildShareBundle(')
    )
  }

  it('holds for the builder as written', () => {
    expect(handsItsWorkToSealShare(builder)).toBe(true)
  })

  it('fails for work done before the call, after it, or with no call at all (positive control)', () => {
    const sealedEarly = builder.replace("const lineage = 'share'", "const early = buildShare(null)\n      const lineage = 'share'")
    const askedLate = builder.replace("if (outcome.kind === 'ended') {", "await portal?.relationshipEnding('r')\n      if (outcome.kind === 'ended') {")
    const noCall = builder.replace('await sealShare({', 'await somethingElse({')
    for (const planted of [sealedEarly, askedLate, noCall]) {
      expect(planted).not.toBe(builder)
      expect(handsItsWorkToSealShare(planted)).toBe(false)
    }
    // An empty read is a failure, not a vacuous pass.
    expect(handsItsWorkToSealShare('')).toBe(false)
  })

  it('says the refusal sealShare returns in the words of COMPANION_UX.md §7.8', () => {
    expect(scriptOf(builder)).toMatch(/if \(outcome\.kind === 'ended'\) \{\s*error = shareRefusedBecauseEnded\(/)
  })
})
