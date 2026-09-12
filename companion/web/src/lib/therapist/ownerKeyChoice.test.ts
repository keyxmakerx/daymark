/*
 * WHICH OWNER KEY A SIGNING-IN CLINICIAN PINS — issue #122.
 *
 * LoginGate used to fetch the owner's published keys and assign them over whatever the clinician
 * had typed, with no comparison and no notice. It was inert only because nothing in the product had
 * ever published an owner key; the moment the owner console gained a publish button it became live,
 * and a server handing back a key it controlled would have replaced the one the clinician had
 * verified by hand. Every forged share would then have verified, and every genuine one would have
 * failed — which is exactly the substitution the whole pinning design exists to catch.
 *
 * session.ts's own header had said all along that the caller must pin on first use and refuse a
 * change. These are the tests for the caller finally doing it.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { chooseOwnerKeys, OWNER_KEY_MISMATCH, OWNER_KEY_PASTE_CAVEAT } from './inviteAccept'

const TYPED = { signPubB64: 'SIGN-TYPED', boxPubB64: 'BOX-TYPED' }
const NOTHING_TYPED = { signPubB64: '', boxPubB64: '' }

describe('(a) what a clinician checked by hand wins', () => {
  it('uses the typed keys when the server agrees', () => {
    expect(chooseOwnerKeys(TYPED, { ...TYPED })).toEqual({ ok: true, keys: TYPED, source: 'typed' })
  })

  it('uses the typed keys when the server has published nothing', () => {
    expect(chooseOwnerKeys(TYPED, null)).toEqual({ ok: true, keys: TYPED, source: 'typed' })
  })

  it('reads them the way a person pastes them, with the whitespace gone', () => {
    const out = chooseOwnerKeys({ signPubB64: '  SIGN-TYPED\n', boxPubB64: ' BOX-TYPED ' }, { ...TYPED })
    expect(out).toEqual({ ok: true, keys: TYPED, source: 'typed' })
  })
})

describe('(b) a disagreement refuses; it never picks a winner', () => {
  it('refuses when the published signing key differs', () => {
    expect(chooseOwnerKeys(TYPED, { signPubB64: 'SIGN-SERVER', boxPubB64: 'BOX-TYPED' })).toEqual({
      ok: false,
      reason: 'mismatch',
    })
  })

  it('refuses when only the published encryption key differs', () => {
    // Half a match is not a match. The encryption key is what everything sent to this clinician is
    // sealed to; a server that swapped only that half would read everything while every signature
    // still checked out.
    expect(chooseOwnerKeys(TYPED, { signPubB64: 'SIGN-TYPED', boxPubB64: 'BOX-SERVER' })).toEqual({
      ok: false,
      reason: 'mismatch',
    })
  })

  it('never returns the published keys when something was typed', () => {
    // The regression this file exists to prevent, stated as a property over many shapes rather
    // than one example: with both halves typed, there is no published value that can come back.
    const published = [
      { signPubB64: 'SIGN-SERVER', boxPubB64: 'BOX-SERVER' },
      { signPubB64: 'SIGN-TYPED', boxPubB64: 'BOX-SERVER' },
      { signPubB64: 'SIGN-SERVER', boxPubB64: 'BOX-TYPED' },
      { ...TYPED },
      null,
    ]
    for (const p of published) {
      const out = chooseOwnerKeys(TYPED, p)
      if (out.ok) expect(out.source).toBe('typed')
      expect(out.ok ? out.keys : null).not.toEqual({ signPubB64: 'SIGN-SERVER', boxPubB64: 'BOX-SERVER' })
    }
  })
})

describe('(c) the honestly-weaker path, and the dead end', () => {
  it('falls back to the published keys only when nothing was typed', () => {
    const published = { signPubB64: 'SIGN-SERVER', boxPubB64: 'BOX-SERVER' }
    expect(chooseOwnerKeys(NOTHING_TYPED, published)).toEqual({ ok: true, keys: published, source: 'published' })
  })

  it('treats half-typed as nothing typed rather than as a comparison', () => {
    // One key on its own cannot be checked against anything, so it must not be treated as a pin
    // the server then has to agree with — that would refuse a sign-in over an unfinished paste.
    const published = { signPubB64: 'SIGN-SERVER', boxPubB64: 'BOX-SERVER' }
    expect(chooseOwnerKeys({ signPubB64: 'SIGN-TYPED', boxPubB64: '' }, published).ok).toBe(true)
    expect(chooseOwnerKeys({ signPubB64: '', boxPubB64: 'BOX-TYPED' }, published).ok).toBe(true)
  })

  it('says there is nothing to pin when there is nothing on either side', () => {
    expect(chooseOwnerKeys(NOTHING_TYPED, null)).toEqual({ ok: false, reason: 'none' })
  })
})

describe('(d) what the refusal says', () => {
  it('names no cause, because there are three and it can tell them apart in none', () => {
    expect(OWNER_KEY_MISMATCH).toContain('mistyped')
    expect(OWNER_KEY_MISMATCH).toContain('changed')
    expect(OWNER_KEY_MISMATCH).toContain('cannot tell which')
  })

  it('says nothing happened, and where to check', () => {
    expect(OWNER_KEY_MISMATCH).toContain('Nothing has been signed in')
    expect(OWNER_KEY_MISMATCH).toContain('not this server')
  })

  it('echoes no key', () => {
    expect(OWNER_KEY_MISMATCH).not.toMatch(/\$\{|[A-Za-z0-9_-]{30,}/)
  })
})

describe('(e) the caveat beside the manual owner-key fields (issue #101)', () => {
  const source = readFileSync(
    fileURLToPath(new URL('../components/therapist/LoginGate.svelte', import.meta.url)),
    'utf8',
  )

  it('names the consequence and does not reassure', () => {
    // A caveat that makes someone feel covered is worse than none: it spends the one moment they
    // were going to think about it.
    expect(OWNER_KEY_PASTE_CAVEAT).toContain('weaker half')
    expect(OWNER_KEY_PASTE_CAVEAT).toContain('nothing here proves theirs to you')
    expect(OWNER_KEY_PASTE_CAVEAT).toMatch(/check the fingerprint with them/i)
  })

  it('does not describe the paste as equivalent to the ceremony', () => {
    const FORBIDDEN = [
      { name: 'equivalence', pattern: /\b(just as (safe|secure)|equally|same as the code|fully verified)\b/i, planted: 'This is just as safe as the code.' },
      { name: 'reassurance', pattern: /\b(don't worry|no need to|perfectly (safe|fine)|secure)\b/i, planted: "Don't worry, this is secure." },
    ]
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
      expect(pattern.test(OWNER_KEY_PASTE_CAVEAT), name).toBe(false)
    }
  })

  it('is rendered beside the fields it is about, not in a banner above the form', () => {
    // Someone reaching these fields has already scrolled past every banner on the page.
    const caveat = source.indexOf('{OWNER_KEY_PASTE_CAVEAT}')
    const signField = source.indexOf('f-pinnedOwnerSignPub')
    const boxField = source.indexOf('f-ownerBoxPub')
    expect(caveat).toBeGreaterThan(-1)
    expect(caveat).toBeLessThan(signField)
    expect(signField).toBeLessThan(boxField)
    // Close enough to be read as belonging to them.
    expect(signField - caveat).toBeLessThan(400)
  })
})
