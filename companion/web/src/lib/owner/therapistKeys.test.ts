/*
 * The owner side of the key exchange, tested where it is load-bearing: what the console does with a
 * response it did not author.
 *
 * The three cases the brief names are the three failure shapes that matter, and each is here for a
 * reason that is not "coverage":
 *
 *   - A key of the wrong length is what a substitution attempt looks like when it is careless, and
 *     what a client bug looks like when it is not. Either way it must never reach the pin store, a
 *     fingerprint, or a seal.
 *   - A 404 is the ORDINARY state of a new relationship, and the one non-error this module has. If
 *     it ever starts throwing, an owner waiting for their therapist to finish enrolling gets told
 *     something is broken.
 *   - A refused confirmation is the entire security property of the surface above this module. The
 *     assertion is not that it throws; it is that the pin store is byte-for-byte what it was.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import {
  fetchTherapistKeys,
  parseTherapistKeyRecord,
  acceptTherapistKeys,
  confirmationMatches,
  keyFingerprints,
  groupFingerprint,
  peerOf,
  TherapistKeyError,
  type TherapistKeyRecord,
} from './therapistKeys'
import {
  initShareCrypto,
  newIdentity,
  newBoxKeyPair,
  publicOf,
  fingerprint,
  fingerprints,
  PinStore,
  type Identity,
} from '../share/pairing'
import { toBase64 } from '../share/sharecrypto'

const RELREF = 'rel-ref_with-url~unsafe chars'
const ENDPOINT = { baseUrl: 'https://s.example/', token: 'owner-token' }

interface Recorded {
  url: string
  init: RequestInit
}

/** A fetch that answers every call the same way and records what it was asked. */
function fakeFetch(answer: () => Response, log: Recorded[] = []): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    log.push({ url: String(input), init: init ?? {} })
    return answer()
  }) as unknown as typeof fetch
}

function jsonBody(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('reading the keys a therapist published', () => {
  let therapist: Identity
  let registered: { boxPubB64: string; signPubB64: string; registeredAt: number }

  beforeAll(async () => {
    await initShareCrypto()
    therapist = newIdentity()
    registered = {
      boxPubB64: toBase64(therapist.x25519.publicKey),
      signPubB64: toBase64(therapist.ed25519.publicKey),
      registeredAt: 1_700_000_000_000,
    }
  })

  it('sends the owner bearer token to the relationship route and decodes both keys', async () => {
    const log: Recorded[] = []
    const record = await fetchTherapistKeys(ENDPOINT, RELREF, fakeFetch(() => jsonBody(registered), log))
    expect(record).not.toBeNull()
    expect(record!.boxPub).toEqual(therapist.x25519.publicKey)
    expect(record!.signPub).toEqual(therapist.ed25519.publicKey)
    expect(record!.registeredAt).toBe(registered.registeredAt)

    // The relRef is escaped into the path, and the trailing slash on the base is not doubled.
    expect(log[0].url).toBe(
      `https://s.example/v1/relations/${encodeURIComponent(RELREF)}/therapist-keys`,
    )
    expect((log[0].init.headers as Record<string, string>).Authorization).toBe('Bearer owner-token')
    // No inbox token goes anywhere near this route: the module is never given one.
    expect(JSON.stringify(log[0].init)).not.toContain('X-Rel-Token')
  })

  it('returns null — not an error — when nothing is registered yet', async () => {
    const record = await fetchTherapistKeys(
      ENDPOINT,
      RELREF,
      fakeFetch(() => new Response('{"error":"no keys registered"}', { status: 404 })),
    )
    // The state of every relationship between the invite and the clinician finishing enrolment.
    // An owner in that state is waiting, not troubleshooting.
    expect(record).toBeNull()
  })

  it('refuses a key that does not decode to exactly 32 bytes', async () => {
    // 31 bytes: valid base64url, plausible at a glance, and not a key. The server's own decoder
    // refuses it on the way in, which is exactly why this side may not depend on that.
    const short = toBase64(new Uint8Array(31).fill(7))
    await expect(
      fetchTherapistKeys(ENDPOINT, RELREF, fakeFetch(() => jsonBody({ ...registered, boxPubB64: short }))),
    ).rejects.toThrow(TherapistKeyError)
    await expect(
      fetchTherapistKeys(ENDPOINT, RELREF, fakeFetch(() => jsonBody({ ...registered, boxPubB64: short }))),
    ).rejects.toThrow(/exactly 32 bytes/)

    // And the same for a 33-byte key and for the signing half, so the check is not one-sided.
    const long = toBase64(new Uint8Array(33).fill(9))
    await expect(
      fetchTherapistKeys(ENDPOINT, RELREF, fakeFetch(() => jsonBody({ ...registered, signPubB64: long }))),
    ).rejects.toThrow(TherapistKeyError)
  })

  it('refuses a body that is not a key record at all', async () => {
    const hostile: unknown[] = [
      { ...registered, boxPubB64: 'not base64url!!' },
      { ...registered, boxPubB64: '' },
      { ...registered, signPubB64: 42 },
      { boxPubB64: registered.boxPubB64 }, // signing key absent
      { ...registered, registeredAt: 'yesterday' },
      { ...registered, registeredAt: Number.NaN },
      [registered],
      'a string',
      null,
    ]
    for (const body of hostile) {
      expect(() => parseTherapistKeyRecord(body), JSON.stringify(body) ?? 'undefined').toThrow(TherapistKeyError)
    }
    // Non-vacuity: the same parser accepts the honest body, so the list above is failing on its
    // content rather than on the parser being broken.
    expect(parseTherapistKeyRecord(registered).boxPub).toEqual(therapist.x25519.publicKey)
  })

  it('ignores fields it was not told about rather than refusing the record', async () => {
    // A later server version that adds a field is not lying. A parser that rejects the unknown
    // turns every additive change into a coordinated release of two codebases.
    const record = parseTherapistKeyRecord({ ...registered, rotationCount: 3, note: 'hello' })
    expect(record.signPub).toEqual(therapist.ed25519.publicKey)
  })

  it('does not let a 200 that is not JSON through, and names auth failures without guessing', async () => {
    await expect(
      fetchTherapistKeys(ENDPOINT, RELREF, fakeFetch(() => new Response('<html>login</html>', { status: 200 }))),
    ).rejects.toThrow(TherapistKeyError)

    await expect(
      fetchTherapistKeys(ENDPOINT, RELREF, fakeFetch(() => new Response('nope', { status: 401 }))),
    ).rejects.toMatchObject({ status: 401 })
    await expect(
      fetchTherapistKeys(ENDPOINT, RELREF, fakeFetch(() => new Response('nope', { status: 403 }))),
    ).rejects.toMatchObject({ status: 403 })
    await expect(
      fetchTherapistKeys(ENDPOINT, RELREF, fakeFetch(() => new Response('boom', { status: 500 }))),
    ).rejects.toMatchObject({ status: 500 })
  })
})

describe('the confirmation gate in front of the pin', () => {
  let therapist: Identity
  let record: TherapistKeyRecord
  let heard: { boxFp: string; signFp: string }

  beforeAll(async () => {
    await initShareCrypto()
    therapist = newIdentity()
    record = {
      boxPub: therapist.x25519.publicKey,
      signPub: therapist.ed25519.publicKey,
      registeredAt: 1_700_000_000_000,
    }
    // What a clinician reading both fingerprints down a phone line gets the owner to type back.
    heard = keyFingerprints(record)
  })

  it('a refused confirmation leaves the pin store exactly as it was', () => {
    const pins = new PinStore()
    const before = pins.serialize()

    const refusals: { boxFp: string; signFp: string }[] = [
      { boxFp: '', signFp: '' },
      { boxFp: '   ', signFp: '   ' },
      { boxFp: 'not the fingerprint', signFp: 'nor this one' },
      { boxFp: heard.boxFp.slice(0, -1), signFp: heard.signFp },
      { boxFp: heard.boxFp + 'x', signFp: heard.signFp },
      { boxFp: heard.boxFp.toLowerCase(), signFp: heard.signFp },
      // The two halves, each on their own. Neither is a confirmation: the keys arrive in one body
      // from one machine, so a server that keeps one real key and swaps the other passes any check
      // that only looks at the half it left alone.
      { boxFp: heard.boxFp, signFp: '' },
      { boxFp: '', signFp: heard.signFp },
      // And the pair swapped over, which matches nothing while looking plausible on screen.
      { boxFp: heard.signFp, signFp: heard.boxFp },
    ]

    for (const typed of refusals) {
      const label = JSON.stringify(typed)
      expect(() => acceptTherapistKeys(pins, record, typed), label).toThrow(TherapistKeyError)
      // The assertion that matters. Not "it threw" — that a person who heard something different,
      // or who typed nothing and pressed the button anyway, has pinned nothing at all.
      expect(pins.serialize(), label).toBe(before)
      expect(pins.isPinned(fingerprints(publicOf(therapist)).ed25519Fp), label).toBe(false)
    }
    // Case is part of a fingerprint: lower-casing one above is a refusal, not a courtesy.
    expect(heard.boxFp).not.toBe(heard.boxFp.toLowerCase())
  })

  it('pins once both typed fingerprints match, and stores the fetched key', () => {
    const pins = new PinStore()
    // Spacing is how the characters were grouped for reading and how they were typed back, not part
    // of the fingerprint — so it is stripped everywhere, not only at the ends.
    const spaced = {
      boxFp: groupFingerprint(heard.boxFp).join(' '),
      signFp: '  ' + groupFingerprint(heard.signFp).join('  ') + ' ',
    }
    expect(confirmationMatches(record, spaced)).toBe(true)

    expect(acceptTherapistKeys(pins, record, spaced, 1_700_000_100_000)).toBe('pinned-now')
    const { ed25519Fp, x25519Fp } = fingerprints(peerOf(record))
    expect(pins.isPinned(ed25519Fp)).toBe(true)
    expect(pins.pinnedX25519Fp(ed25519Fp)).toBe(x25519Fp)
    expect(pins.assertPinned(ed25519Fp).pinnedAt).toBe(1_700_000_100_000)

    // Offering the same keys again changes nothing, including the date they were first recorded.
    expect(acceptTherapistKeys(pins, record, heard, 1_900_000_000_000)).toBe('already-pinned')
    expect(pins.assertPinned(ed25519Fp).pinnedAt).toBe(1_700_000_100_000)
  })

  it('will not quietly replace an encryption key that is already on file', () => {
    // The substitution the pin exists to catch, arriving through this route: same therapist
    // identity, different key to seal to. It must not read as 'already-pinned' — that word would
    // tell an owner everything was in order while the console held two different keys for one
    // person — and it must not overwrite, because replacing a pinned key is PinRecord's ceremony
    // and costs its own out-of-band check.
    const pins = new PinStore()
    pins.pin(publicOf(therapist), 1_700_000_000_000)
    const before = pins.serialize()

    const substituted: TherapistKeyRecord = {
      boxPub: newBoxKeyPair().publicKey,
      signPub: therapist.ed25519.publicKey,
      registeredAt: 1_800_000_000_000,
    }
    // Confirmed correctly against the substituted record — the owner heard exactly what the server
    // sent, which is what happens when a therapist genuinely re-keys. Still not pinned here.
    expect(acceptTherapistKeys(pins, substituted, keyFingerprints(substituted))).toBe('differs-from-pin')
    expect(pins.serialize()).toBe(before)
  })

  it('shows the seal target and the identity key as two different fingerprints', () => {
    expect(heard.boxFp).toBe(fingerprint(therapist.x25519.publicKey))
    expect(heard.signFp).toBe(fingerprint(therapist.ed25519.publicKey))
    expect(heard.boxFp).not.toBe(heard.signFp)
    // 22 characters of base64url — comfortably over the floor confirmationMatches refuses under.
    expect(heard.boxFp.length).toBeGreaterThanOrEqual(16)
  })

  it('grouping a fingerprint for reading loses nothing', () => {
    // The property the whole read-aloud step rests on: what is spoken is the entire value, never a
    // summary of it. Same contract, and the same group size, as the therapist acceptance page.
    expect(groupFingerprint(heard.boxFp).join('')).toBe(heard.boxFp)
    expect(groupFingerprint(heard.boxFp)[0].length).toBe(4)
    expect(groupFingerprint('').length).toBe(0)
  })

  it('an empty answer is never an answer', () => {
    // The tautology guard, from the direction a caller can actually reach. Nothing typed confirms
    // nothing — including for a degenerate record, where an owner pressing the button on an empty
    // field must not be handed the empty expected value the check would then agree with.
    expect(confirmationMatches(record, { boxFp: '', signFp: '' })).toBe(false)
    expect(confirmationMatches(record, { boxFp: '   ', signFp: '   ' })).toBe(false)
    expect(
      confirmationMatches({ ...record, boxPub: new Uint8Array(0) }, { boxFp: '', signFp: heard.signFp }),
    ).toBe(false)
  })
})
