/*
 * The envelope layer's contract, pinned. The load-bearing property is the honest failure: a
 * mismatched code produces null — one bit, no diagnosis — because this is the exact point
 * where "wrong code" becomes observable at all, and anything richer than null here is where a
 * typo would start being rendered as an attack.
 */
import { beforeAll, describe, expect, it } from 'vitest'
import {
  ENVELOPE_VERSION,
  directionKey,
  initEnvelope,
  openEnvelope,
  sealEnvelope,
} from './envelope'
import { initCpace, cpaceStart, cpaceRespond, cpaceFinish } from './cpace'

const utf8 = (t: string): Uint8Array => new TextEncoder().encode(t)
const toHex = (b: Uint8Array): string =>
  Array.from(b)
    .map((x) => x.toString(16).padStart(2, '0'))
    .join('')

let iskA: Uint8Array // owner's ISK from a real matching exchange
let iskB: Uint8Array // therapist's ISK, same exchange (equal bytes)
let iskWrong: Uint8Array // therapist's ISK from a WRONG-code exchange with the same owner run

const SID_B64 = 'c2lkLXNpZC1zaWQtc2lk'

beforeAll(async () => {
  await initEnvelope()
  const sodium = await initCpace()
  const sid = sodium.randombytes_buf(16)
  const inputs = { prs: utf8('MATCH-CODE'), ci: utf8('daymark/cpace/v1|rel'), sid }
  const a = cpaceStart(inputs, utf8('owner'))
  const b = cpaceRespond(inputs, a.msgA, utf8('therapist'))
  iskA = cpaceFinish(inputs, a, b.msgB)
  iskB = b.isk
  // The wrong-code side: same owner opening, different typed code on the responder.
  const wrong = cpaceRespond({ ...inputs, prs: utf8('WRONG-CODE') }, a.msgA, utf8('therapist'))
  iskWrong = wrong.isk
})

describe('sealed negotiation over a real CPace key', () => {
  it('round-trips both directions between the two ends of a matching exchange', () => {
    const payload = utf8('{"kind":"therapist-keys","boxPubB64":"..."}')
    const toOwner = sealEnvelope(iskB, SID_B64, 'therapist-to-owner', payload)
    expect(toHex(openEnvelope(iskA, SID_B64, 'therapist-to-owner', toOwner)!)).toBe(toHex(payload))

    const toTherapist = sealEnvelope(iskA, SID_B64, 'owner-to-therapist', utf8('welcome'))
    expect(new TextDecoder().decode(openEnvelope(iskB, SID_B64, 'owner-to-therapist', toTherapist)!)).toBe(
      'welcome',
    )
  })

  it('a wrong code surfaces here — as null, one bit, no diagnosis', () => {
    // iskWrong came from a REAL protocol run whose only difference is the typed code. The
    // envelope refusing to open is the first observable consequence of the mismatch anywhere
    // in the design — CPace itself emitted no signal, on purpose. Null and nothing more,
    // because a typo and an attacker are indistinguishable and the caller must render a calm
    // question, not a verdict.
    const sealed = sealEnvelope(iskA, SID_B64, 'owner-to-therapist', utf8('welcome'))
    expect(openEnvelope(iskWrong, SID_B64, 'owner-to-therapist', sealed)).toBeNull()
  })

  it('reflection is refused: a sealed direction only opens as that direction', () => {
    const sealed = sealEnvelope(iskA, SID_B64, 'owner-to-therapist', utf8('mine'))
    expect(openEnvelope(iskB, SID_B64, 'therapist-to-owner', sealed)).toBeNull()
    // And the two directional keys really are distinct keys, not one key with two names.
    expect(toHex(directionKey(iskA, 'owner-to-therapist'))).not.toBe(
      toHex(directionKey(iskA, 'therapist-to-owner')),
    )
  })

  it('an envelope is bound to its exchange: another sid refuses it', () => {
    const sealed = sealEnvelope(iskA, SID_B64, 'owner-to-therapist', utf8('bound'))
    expect(openEnvelope(iskB, 'ZGlmZmVyZW50LXNpZA', 'owner-to-therapist', sealed)).toBeNull()
  })

  it('tampering with any byte refuses; truncation refuses; a foreign version refuses', () => {
    const sealed = sealEnvelope(iskA, SID_B64, 'owner-to-therapist', utf8('intact'))
    for (const index of [0, 1, 12, 30, sealed.length - 1]) {
      const bent = new Uint8Array(sealed)
      bent[index] ^= 0x20
      expect(openEnvelope(iskB, SID_B64, 'owner-to-therapist', bent), `byte ${index}`).toBeNull()
    }
    expect(openEnvelope(iskB, SID_B64, 'owner-to-therapist', sealed.subarray(0, 20))).toBeNull()
    expect(ENVELOPE_VERSION).toBe(0x01)
  })

  it('two seals of one payload never share bytes beyond the version — fresh nonce every time', () => {
    const one = sealEnvelope(iskA, SID_B64, 'owner-to-therapist', utf8('same'))
    const two = sealEnvelope(iskA, SID_B64, 'owner-to-therapist', utf8('same'))
    expect(toHex(one)).not.toBe(toHex(two))
    expect(toHex(one.subarray(1, 25))).not.toBe(toHex(two.subarray(1, 25)))
    // Both still open.
    expect(openEnvelope(iskB, SID_B64, 'owner-to-therapist', one)).not.toBeNull()
    expect(openEnvelope(iskB, SID_B64, 'owner-to-therapist', two)).not.toBeNull()
  })

  it('a short or oversized ISK is refused before any key is derived', () => {
    expect(() => directionKey(new Uint8Array(32), 'owner-to-therapist')).toThrow(/64 bytes/)
    expect(() => sealEnvelope(new Uint8Array(63), SID_B64, 'owner-to-therapist', utf8('x'))).toThrow(
      /64 bytes/,
    )
  })
})
