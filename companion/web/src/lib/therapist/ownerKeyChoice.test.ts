/*
 * WHICH OWNER KEYS A SIGNING-IN CLINICIAN USES — issues #101 and #122.
 *
 * The history is two defects deep, and both are worth keeping in view because this one function is
 * where each of them lived.
 *
 * #122: LoginGate fetched the owner's published keys and assigned them over whatever the clinician
 * had typed, with no comparison and no notice. Inert only because nothing had ever published an
 * owner key; the moment the owner console gained a publish button, a server handing back a key it
 * controlled would have replaced the one the clinician verified. Every forged share would then have
 * verified, and every genuine one would have failed.
 *
 * #101: the typed keys themselves were the weaker half. The owner learned the clinician's keys from
 * an envelope only a code-holder could seal; the clinician learned the owner's by pasting base64
 * from wherever it arrived. The pairing now seals the owner's keys back under the same code, so the
 * typed path is GONE and what wins is what the ceremony proved. These tests hold that: the pin
 * wins, a disagreeing published copy refuses, and a record with no pin is the one honestly-weaker
 * case, which says so.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  chooseOwnerKeys,
  pinnedOwnerKeysOf,
  OWNER_KEY_MISMATCH,
  OWNER_KEY_NO_PUBLISHED_COPY,
  OWNER_KEY_UNPINNED_CAVEAT,
} from './inviteAccept'

const PINNED = { signPubB64: 'SIGN-PINNED', boxPubB64: 'BOX-PINNED' }
const SERVER = { signPubB64: 'SIGN-SERVER', boxPubB64: 'BOX-SERVER' }

describe('(a) what the pairing proved wins', () => {
  it('uses the pinned keys when the server agrees', () => {
    expect(chooseOwnerKeys(PINNED, { ...PINNED })).toEqual({ ok: true, keys: PINNED, source: 'pinned' })
  })

  it('uses the pinned keys when the server publishes nothing, and says there was nothing to compare', () => {
    expect(chooseOwnerKeys(PINNED, null)).toEqual({
      ok: true,
      keys: PINNED,
      source: 'pinned',
      nothingToCompare: true,
    })
  })

  it('never returns the published keys when a pin exists', () => {
    // The #122 regression, stated as a property over many shapes rather than one example: with a
    // pin present, there is no published value that can come back as the answer.
    for (const published of [SERVER, { ...PINNED, boxPubB64: 'BOX-SERVER' }, { ...PINNED }, null]) {
      const out = chooseOwnerKeys(PINNED, published)
      if (out.ok) {
        expect(out.source).toBe('pinned')
        expect(out.keys).toEqual(PINNED)
      }
    }
  })
})

describe('(b) a disagreement refuses; it never picks a winner', () => {
  it('refuses when the published signing key differs', () => {
    expect(chooseOwnerKeys(PINNED, { signPubB64: 'SIGN-SERVER', boxPubB64: 'BOX-PINNED' })).toEqual({
      ok: false,
      reason: 'mismatch',
    })
  })

  it('refuses when only the published encryption key differs', () => {
    // Half a match is not a match. The encryption key is what everything sent to this clinician is
    // sealed to; a server that swapped only that half would read everything while every signature
    // still checked out.
    expect(chooseOwnerKeys(PINNED, { signPubB64: 'SIGN-PINNED', boxPubB64: 'BOX-SERVER' })).toEqual({
      ok: false,
      reason: 'mismatch',
    })
  })
})

describe('(c) a record from before the envelope existed, and the dead end', () => {
  it('falls back to the published keys only when there is no pin', () => {
    expect(chooseOwnerKeys(null, SERVER)).toEqual({ ok: true, keys: SERVER, source: 'published' })
  })

  it('says there is nothing to use when there is nothing on either side', () => {
    expect(chooseOwnerKeys(null, null)).toEqual({ ok: false, reason: 'none' })
  })

  it('reads a pin off a record only when it has both halves', () => {
    // Half a pinned owner is not a near-miss, and must not be offered as one: a record holding one
    // proved key beside one absent one would otherwise be compared, and read as verified.
    expect(pinnedOwnerKeysOf({ pinnedOwnerSignPubB64: 'S', ownerBoxPubB64: 'B' })).toEqual({
      signPubB64: 'S',
      boxPubB64: 'B',
    })
    expect(pinnedOwnerKeysOf({ pinnedOwnerSignPubB64: 'S' })).toBeNull()
    expect(pinnedOwnerKeysOf({ ownerBoxPubB64: 'B' })).toBeNull()
    expect(pinnedOwnerKeysOf({ pinnedOwnerSignPubB64: 'S', ownerBoxPubB64: '' })).toBeNull()
    expect(pinnedOwnerKeysOf({})).toBeNull()
  })
})

describe('(d) what the refusal says', () => {
  it('names no cause, because it cannot tell them apart', () => {
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

describe('(e) what a clinician with no ceremony pin is told (issue #101)', () => {
  it('names the consequence, and the remedy, and does not reassure', () => {
    // A caveat that makes someone feel covered is worse than none: it spends the one moment they
    // were going to think about it.
    expect(OWNER_KEY_UNPINNED_CAVEAT).toMatch(/never proved/i)
    expect(OWNER_KEY_UNPINNED_CAVEAT).toMatch(/does not vouch/i)
    expect(OWNER_KEY_UNPINNED_CAVEAT).toMatch(/check the fingerprint with them/i)
    expect(OWNER_KEY_UNPINNED_CAVEAT).toMatch(/fresh invitation/i)
  })

  it('does not describe the weaker path as equivalent to the ceremony', () => {
    const FORBIDDEN = [
      {
        name: 'equivalence',
        pattern: /\b(just as (safe|secure)|equally|same as the code|fully verified)\b/i,
        planted: 'This is just as safe as the code.',
      },
      {
        name: 'reassurance',
        pattern: /\b(don't worry|no need to|perfectly (safe|fine)|secure)\b/i,
        planted: "Don't worry, this is secure.",
      },
    ]
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
      expect(pattern.test(OWNER_KEY_UNPINNED_CAVEAT), name).toBe(false)
      expect(pattern.test(OWNER_KEY_NO_PUBLISHED_COPY), name).toBe(false)
    }
  })

  it('the no-published-copy line is a fact, not a warning', () => {
    // Nothing is missing that the clinician needs: the stronger value is present. Telling them to
    // do something about it would be inventing a task out of an absence.
    expect(OWNER_KEY_NO_PUBLISHED_COPY).toMatch(/nothing to compare/i)
    expect(OWNER_KEY_NO_PUBLISHED_COPY).not.toMatch(/\b(stop|do not|check with|ask them)\b/i)
  })
})

describe('(f) the sign-in form has no way to type an owner key (issue #101)', () => {
  const source = readFileSync(
    fileURLToPath(new URL('../components/therapist/LoginGate.svelte', import.meta.url)),
    'utf8',
  )

  it('has no owner-key inputs, and no state bound to one', () => {
    // The fields are gone rather than hidden. A hidden field is one `{#if}` away from returning,
    // and what it would return is the half of the pairing nothing proved.
    for (const gone of ['f-pinnedOwnerSignPub', 'f-ownerBoxPub', 'OWNER_KEY_PASTE_CAVEAT']) {
      expect(source.includes(gone), `still present: ${gone}`).toBe(false)
    }
    expect(source).not.toMatch(/bind:value=\{(pinnedOwnerSignPubB64|ownerBoxPubB64)\}/)
    // Control: the detector sees these same shapes when they are there.
    const planted = source + '\n<input id="f-ownerBoxPub" bind:value={ownerBoxPubB64} />'
    expect(planted.includes('f-ownerBoxPub')).toBe(true)
    expect(planted).toMatch(/bind:value=\{(pinnedOwnerSignPubB64|ownerBoxPubB64)\}/)
  })

  it('reads the pin off the stored record and compares it with what the server published', () => {
    expect(source).toContain('pinnedOwnerKeysOf(rec)')
    expect(source).toContain('client.ownerKeys(session)')
    const pin = source.indexOf('pinnedOwnerKeysOf(rec)')
    const choose = source.indexOf('chooseOwnerKeys(')
    expect(choose).toBeGreaterThan(-1)
    // The pin is the first argument — the precedence the function then enforces.
    expect(source.slice(choose, choose + 80)).toContain('pinnedOwnerKeysOf(rec)')
    expect(pin).toBeGreaterThan(-1)
  })

  it('shows the caveat only on the path that has no pin', () => {
    expect(source).toContain("assurance === 'unpinned'")
    expect(source).toContain('{OWNER_KEY_UNPINNED_CAVEAT}')
    const caveatAt = source.indexOf('{OWNER_KEY_UNPINNED_CAVEAT}')
    const guardAt = source.lastIndexOf("assurance === 'unpinned'", caveatAt)
    expect(guardAt).toBeGreaterThan(-1)
    // The guard is the one immediately around it, not some distant mention.
    expect(caveatAt - guardAt).toBeLessThan(400)
  })
})
