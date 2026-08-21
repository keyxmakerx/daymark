/*
 * The invite link's rules, and the one that is a security property rather than a parsing
 * preference: the two values are read from the FRAGMENT and from nowhere else.
 *
 * The query-string case below is the load-bearing one. It is the assertion that fails if somebody
 * later "fixes" a link that does not work by also looking at `search` — a change that would look
 * like a compatibility improvement in a diff and would mean every proxy between a clinician and the
 * server had written a live invite secret to disk.
 */
import { describe, it, expect } from 'vitest'
import {
  INVITE_FAULT_TEXT,
  apiBaseFrom,
  isInvitePath,
  looksLikeInvite,
  parseInviteFragment,
  parseInviteLink,
  withoutFragment,
} from './inviteLink'

/** Shaped like `Secrets.newToken()`: base64url of 32 bytes, unpadded. */
const ID = 'Ku7dQ2n1YfL0pR8sT3vW9xZ4aB6cD5eG7hJ2kM1nP0Q'
const SECRET = 'z9Y8x7W6v5U4t3S2r1Q0p9O8n7M6l5K4j3I2h1G0f9E'

describe('reading an invitation out of a link', () => {
  it('reads id and s out of the fragment', () => {
    const parsed = parseInviteFragment(`#id=${ID}&s=${SECRET}`)
    expect(parsed.ok).toBe(true)
    expect(parsed.ok && parsed.invite).toEqual({ inviteId: ID, secret: SECRET })
  })

  it('does not care about parameter order, a missing #, or extra parameters', () => {
    // A mail client that reorders or appends must not break an invitation, and an extra parameter
    // cannot change what these two mean.
    for (const fragment of [
      `#s=${SECRET}&id=${ID}`,
      `id=${ID}&s=${SECRET}`,
      `#id=${ID}&s=${SECRET}&utm_source=mail`,
    ]) {
      const parsed = parseInviteFragment(fragment)
      expect(parsed.ok, fragment).toBe(true)
      expect(parsed.ok && parsed.invite.secret, fragment).toBe(SECRET)
    }
  })

  it('reads the fragment of a whole pasted link', () => {
    const parsed = parseInviteLink(`https://therapy.example.org/portal/invite#id=${ID}&s=${SECRET}`)
    expect(parsed.ok && parsed.invite).toEqual({ inviteId: ID, secret: SECRET })
  })

  it('REFUSES a link that carries the same values in the query string', () => {
    // The property this whole module exists for. A fragment never reaches a request line, an
    // access log, a proxy or a Referer header; a query string reaches all four. So a link in this
    // shape is not "the same link with a different separator" — it is a link whose secret has
    // already been logged, and the honest answer is that it is not the link the server sent.
    const leaked = `https://therapy.example.org/portal/invite?id=${ID}&s=${SECRET}`
    const parsed = parseInviteLink(leaked)
    expect(parsed.ok).toBe(false)
    expect(parsed.ok === false && parsed.fault).toBe('noFragment')

    // And the same for the query-plus-empty-fragment shape, which a URL parser would happily
    // hand you the search params for.
    expect(parseInviteLink(`${leaked}#`).ok).toBe(false)
  })
})

describe('a malformed fragment is refused with a reason, and never echoed back', () => {
  const cases: { name: string; fragment: string; fault: string }[] = [
    { name: 'no fragment at all', fragment: '', fault: 'noFragment' },
    { name: 'a bare hash', fragment: '#', fault: 'noFragment' },
    { name: 'some other anchor', fragment: '#section-2', fault: 'notAnInvite' },
    { name: 'the id half missing', fragment: `#s=${SECRET}`, fault: 'missingId' },
    { name: 'the secret half missing', fragment: `#id=${ID}`, fault: 'missingSecret' },
    { name: 'an empty id', fragment: `#id=&s=${SECRET}`, fault: 'missingId' },
    { name: 'an empty secret', fragment: `#id=${ID}&s=`, fault: 'missingSecret' },
    { name: 'a truncated secret', fragment: `#id=${ID}&s=z9Y8`, fault: 'malformedSecret' },
    { name: 'a sentence where the id goes', fragment: `#id=see my other email&s=${SECRET}`, fault: 'malformedId' },
    { name: 'a whole URL pasted into the secret', fragment: `#id=${ID}&s=https://elsewhere.example/x`, fault: 'malformedSecret' },
  ]

  for (const { name, fragment, fault } of cases) {
    it(`refuses ${name}`, () => {
      const parsed = parseInviteFragment(fragment)
      expect(parsed.ok).toBe(false)
      expect(parsed.ok === false && parsed.fault).toBe(fault)
    })
  }

  it('every fault has copy, and no copy quotes a value back', () => {
    // A fault message is the string most likely to be screenshotted into a support thread or read
    // aloud to somebody helping — and half of what it could echo is a live credential.
    const faults = cases.map((c) => c.fault)
    expect(new Set(faults).size).toBeGreaterThan(4) // the cases really do cover distinct faults
    for (const fault of new Set(faults)) {
      const text = INVITE_FAULT_TEXT[fault as keyof typeof INVITE_FAULT_TEXT]
      expect(text, fault).toBeTruthy()
      expect(text.length, fault).toBeGreaterThan(40)
      expect(text, fault).not.toContain(SECRET)
      expect(text, fault).not.toContain(ID)
    }
  })
})

describe('routing: which page a visitor lands on', () => {
  it('recognises an invite fragment even when it is unusable', () => {
    // Deliberately weaker than the parser. Somebody whose link was truncated should land on the
    // page that can explain that, not on a sign-in form asking for values they have never seen.
    expect(looksLikeInvite(`#id=${ID}&s=${SECRET}`)).toBe(true)
    expect(looksLikeInvite('#id=cut-off-here')).toBe(true)
    expect(looksLikeInvite('#s=')).toBe(true)
    expect(looksLikeInvite('')).toBe(false)
    expect(looksLikeInvite('#')).toBe(false)
    expect(looksLikeInvite('#section-2')).toBe(false)
  })

  it('recognises the invite path under any base path', () => {
    expect(isInvitePath('/portal/invite')).toBe(true)
    expect(isInvitePath('/portal/invite/')).toBe(true)
    expect(isInvitePath('/dm/portal/invite')).toBe(true)
    expect(isInvitePath('/therapist')).toBe(false)
    expect(isInvitePath('/')).toBe(false)
  })
})

describe('the API base is derived from the page that was served', () => {
  it('strips whichever SPA entry served this document', () => {
    // "Server URL" is the one field of LoginGate's nine the page can always answer itself.
    expect(apiBaseFrom('https://therapy.example.org', '/portal/invite')).toBe('https://therapy.example.org')
    expect(apiBaseFrom('https://therapy.example.org', '/therapist')).toBe('https://therapy.example.org')
    expect(apiBaseFrom('https://therapy.example.org', '/therapist.html')).toBe('https://therapy.example.org')
    expect(apiBaseFrom('https://therapy.example.org/', '/')).toBe('https://therapy.example.org')
  })

  it('keeps a deployment base path, which is the case that only breaks on somebody else’s server', () => {
    // Application.kt mounts every route under a configurable basePath. Taking the origin alone
    // would send every call to /v1/… and 404 the entire flow on a /dm deployment.
    expect(apiBaseFrom('https://example.org', '/dm/portal/invite')).toBe('https://example.org/dm')
    expect(apiBaseFrom('https://example.org', '/dm/therapist')).toBe('https://example.org/dm')
  })
})

describe('the spent link is taken back out of the address bar', () => {
  it('drops the fragment and keeps everything else', () => {
    expect(withoutFragment(`https://x.example/portal/invite#id=${ID}&s=${SECRET}`)).toBe(
      'https://x.example/portal/invite',
    )
    expect(withoutFragment('https://x.example/portal/invite')).toBe('https://x.example/portal/invite')
    expect(withoutFragment('https://x.example/p?a=b#c')).toBe('https://x.example/p?a=b')
  })
})
