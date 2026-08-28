/*
 * THE TWO HALVES OF THE FINGERPRINT CHECK, HELD AGAINST EACH OTHER.
 *
 * WHY THIS FILE EXISTS, AND WHY NEITHER SIDE'S OWN SUITE COULD HAVE CAUGHT WHAT IT CATCHES. The
 * security control of the whole therapist-key exchange is one sentence long: before an owner pins a
 * key, the clinician reads its fingerprint out to them on a channel the server is not on. Both
 * sides of that were implemented, both sides were tested, and both suites passed while the ceremony
 * was impossible to complete honestly.
 *
 * The owner's console demanded TWO fingerprints — correctly, and for a reason therapistKeys.ts
 * argues at length: the two keys arrive in one JSON body from one machine, so a check on the
 * encryption key alone leaves the signing key free to be swapped and vice versa. The clinician's
 * acceptance page displayed ONE: `Enrolment` carried `signFingerprint` and nothing else, the done
 * card rendered that, and a grep of every therapist surface found no fingerprint of the X25519 key
 * anywhere in the product. Put those two facts together and the accept button on the owner's screen
 * could never light up from anything a clinician said. It could only light up from the one place an
 * encryption fingerprint existed: the owner's own screen, drawn by `keyFingerprints()` from the
 * very record the server had just supplied. An owner who copied it across would have satisfied
 * `confirmationMatches` by comparing a value with itself — the exact tautology therapistKeys.ts
 * says in its own doc comment that it exists to prevent, reintroduced by the shape of the two
 * surfaces rather than by any line in either of them.
 *
 * That is why the assertions below span the two modules. Each side in isolation was consistent;
 * what was broken was the join. A test that never crosses it cannot see the break.
 *
 * NOTHING HERE IS A NETWORK TEST. The therapist half generates real keys and the owner half parses
 * a real response body, but the "server" is one line: it takes the two base64url strings the
 * clinician's browser would have posted and hands them back the way the route does. That is
 * precisely the trust model — a relay that carries two strings and vouches for neither — so a
 * faithful stub of it is a faithful stub of the only thing the server does here.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  acceptTherapistKeys,
  confirmationMatches,
  keyFingerprints,
  parseTherapistKeyRecord,
  TherapistKeyError,
  type TherapistKeyRecord,
} from './therapistKeys'
import { beginAcceptance, completeAcceptance, type AcceptancePorts, type Enrolment } from '../therapist/inviteAccept'
import { initAssignmentCrypto, newBoxKeyPair, newSignKeyPair } from '../assignments/crypto'
import { initShareCrypto, PinStore } from '../share/pairing'
import type { KeyRegistration, LoginResult, RedeemResult, SessionInfo } from '../therapist/session'
import type { TherapistKeys, WrappedKeyBlob } from '../therapist/keyStore'

const RELREF = 'rel-ref-opaque-0001'

/* ── The clinician's browser, with the slow parts stubbed and the keys real ──────────────── */

function memoryStorage() {
  let value: string | null = null
  return { getItem: () => value, setItem: (_k: string, v: string) => void (value = v) }
}

/**
 * Ports enough to run the acceptance. The KEYPAIRS ARE REAL — they are what both fingerprints are
 * computed from, so stubbing them would stub the subject — and only the Argon2id wrap is faked,
 * which costs a second per derivation and has nothing to do with what this file asserts.
 */
function therapistPorts(published: { boxPubB64: string; signPubB64: string }[]): AcceptancePorts {
  // The wrap has to round-trip, because `beginAcceptance` proves it does before it will go on —
  // deliberately, and that proof is the reason a stub cannot simply hand back a fresh keypair.
  const vault = new Map<string, TherapistKeys>()
  return {
    redeem: async (): Promise<RedeemResult> => ({ ok: true, relRef: RELREF, scope: [], enrollTicket: 'ticket' }),
    enrol: async () => 'enrolled',
    login: async (): Promise<LoginResult> => ({
      ok: true,
      session: { relRef: '', credentialKind: 'totp', csrf: 'CSRF', absoluteExpiresAt: 9e15, idleExpiresAt: 9e15 },
    }),
    // THE SERVER, in full: it takes the two strings and keeps them. It does not look at them, it
    // cannot check them, and nothing it stores makes them any more true than when they arrived.
    register: async (_s: SessionInfo, boxPubB64: string, signPubB64: string): Promise<KeyRegistration> => {
      published.push({ boxPubB64, signPubB64 })
      return 'registered'
    },
    logout: async () => {},
    newKeys: (): TherapistKeys => ({ box: newBoxKeyPair(), sign: newSignKeyPair() }),
    wrapKeys: async (keys: TherapistKeys, passphrase: string): Promise<WrappedKeyBlob> => {
      vault.set(passphrase, keys)
      return { v: 1, kdf: { alg: 'argon2id', memMiB: 256, ops: 3 }, saltB64: 'salt', nonceB64: 'nonce', ctB64: 'ct' }
    },
    unwrapKeys: async (_b: WrappedKeyBlob, passphrase: string): Promise<TherapistKeys> => {
      const held = vault.get(passphrase)
      if (!held) throw new Error('wrong reading passphrase or tampered key blob')
      return {
        box: { publicKey: held.box.publicKey.slice(), privateKey: held.box.privateKey.slice() },
        sign: { publicKey: held.sign.publicKey.slice(), privateKey: held.sign.privateKey.slice() },
      }
    },
    randomToken: (bytes: number) => ({ raw: new Uint8Array(bytes).fill(7), b64url: 'token' }),
    toBase64: (b: Uint8Array) => sodiumB64(b),
    storage: memoryStorage(),
    now: () => 1_700_000_000_000,
  }
}

/** The real encoding, because the owner's parser really does decode it. */
let sodiumB64: (b: Uint8Array) => string

/**
 * Everything the clinician's browser does, end to end, and the body the owner's console then reads.
 *
 * `substitute` is where a hostile relay is modelled: it gets to rewrite the response body after the
 * clinician's real keys were posted, which is exactly the power the server has.
 */
async function ceremony(substitute: (posted: { boxPubB64: string; signPubB64: string }) => {
  boxPubB64: string
  signPubB64: string
} = (p) => p): Promise<{ enrolment: Enrolment; record: TherapistKeyRecord }> {
  const published: { boxPubB64: string; signPubB64: string }[] = []
  const ports = therapistPorts(published)
  const enrolment = await beginAcceptance(ports, { inviteId: 'inv', secret: 's', passphrase: 'seven brass lanterns' })
  await completeAcceptance(ports, enrolment, '123456')
  expect(published).toHaveLength(1)
  const body = { ...substitute(published[0]!), registeredAt: 1_700_000_000_000 }
  return { enrolment, record: parseTherapistKeyRecord(body) }
}

beforeAll(async () => {
  const so = await initAssignmentCrypto()
  await initShareCrypto()
  sodiumB64 = (b) => so.to_base64(b, so.base64_variants.URLSAFE_NO_PADDING)
})

/* ═══════════════════════════════════════════════════════════════════════════ */

describe('the check the owner is asked to make is one a clinician can actually answer', () => {
  it('every value the owner’s gate demands is a value the clinician’s screen produced', async () => {
    // THE regression. The owner types back what they HEARD; the only legitimate source for that is
    // the clinician's side. So the gate is fed strictly from the `Enrolment` — nothing from the
    // owner's own record is allowed anywhere near this call, because the whole question is whether
    // the ceremony can be completed without borrowing a value from the screen being checked.
    const { enrolment, record } = await ceremony()
    expect(
      confirmationMatches(record, { boxFp: enrolment.boxFingerprint, signFp: enrolment.signFingerprint }),
    ).toBe(true)

    // And they are genuinely two different values, so a page that showed one of them twice — or a
    // person who read the same line out twice — could not satisfy the gate by accident.
    expect(enrolment.boxFingerprint).not.toBe(enrolment.signFingerprint)
    const expected = keyFingerprints(record)
    expect(enrolment.boxFingerprint).toBe(expected.boxFp)
    expect(enrolment.signFingerprint).toBe(expected.signFp)
  })

  it('and the pin is only reached through them', async () => {
    // The end of the path: the owner hears both, types both, and the console files the pair.
    const { enrolment, record } = await ceremony()
    const pins = new PinStore()
    expect(
      acceptTherapistKeys(pins, record, { boxFp: enrolment.boxFingerprint, signFp: enrolment.signFingerprint }),
    ).toBe('pinned-now')
    expect(pins.pinnedX25519Fp(enrolment.signFingerprint)).toBe(enrolment.boxFingerprint)
  })

  it('grouped for reading on one side, ungrouped on the other, still the same characters', async () => {
    // The clinician reads in fours because the page groups in fours; the owner may type it back
    // however it was said to them. Spacing is not part of a fingerprint, so the gate strips it —
    // and this asserts the two groupings really do meet, rather than trusting that they must.
    const { enrolment, record } = await ceremony()
    const spoken = (fp: string) => (fp.match(/.{1,4}/g) ?? []).join(' ')
    expect(
      confirmationMatches(record, {
        boxFp: spoken(enrolment.boxFingerprint),
        signFp: `  ${spoken(enrolment.signFingerprint)}  `,
      }),
    ).toBe(true)
  })
})

describe('the substitution the reading-aloud is for', () => {
  it('a relay that swaps ONLY the encryption key is caught, and nothing is pinned', async () => {
    // The attack the single-fingerprint version could not have caught even in principle: the
    // clinician's real signing key is kept, so the value they read out agrees, and every share the
    // owner ever seals goes to the attacker's X25519 key. It is caught here because the owner is
    // asked for the other fingerprint too — and it is only asked for honestly because the
    // clinician's screen now has one to read.
    const attacker = newBoxKeyPair()
    const { enrolment, record } = await ceremony((posted) => ({
      boxPubB64: sodiumB64(attacker.publicKey),
      signPubB64: posted.signPubB64,
    }))

    const heard = { boxFp: enrolment.boxFingerprint, signFp: enrolment.signFingerprint }
    expect(confirmationMatches(record, heard)).toBe(false)

    const pins = new PinStore()
    const before = pins.serialize()
    expect(() => acceptTherapistKeys(pins, record, heard)).toThrow(TherapistKeyError)
    expect(pins.serialize()).toBe(before)
    expect(pins.pinnedX25519Fp(enrolment.signFingerprint)).toBeNull()
  })

  it('a relay that swaps ONLY the signing key is caught too', async () => {
    // The mirror image: the seal still reaches the real clinician, but the attacker can author
    // assignments and game plans the owner opens as their therapist's.
    const attacker = newSignKeyPair()
    const { enrolment, record } = await ceremony((posted) => ({
      boxPubB64: posted.boxPubB64,
      signPubB64: sodiumB64(attacker.publicKey),
    }))
    expect(
      confirmationMatches(record, { boxFp: enrolment.boxFingerprint, signFp: enrolment.signFingerprint }),
    ).toBe(false)
  })

  it('and reading back what the console itself drew never satisfies the gate on its own', () => {
    // Not a test of a code path — a statement of what the two typed fields are FOR. The values the
    // owner's screen shows are computed from the record the server supplied, so they always match
    // it; that is why they are shown, and it is why they can never be the evidence. The evidence is
    // that the same characters came out of a person's mouth. This assertion exists so that anyone
    // tempted to prefill those inputs from `keyFingerprints(record)` reads this comment first.
    const record = {
      boxPub: new Uint8Array(32).fill(1),
      signPub: new Uint8Array(32).fill(2),
      registeredAt: 1,
    } as TherapistKeyRecord
    const drawn = keyFingerprints(record)
    expect(confirmationMatches(record, { boxFp: drawn.boxFp, signFp: drawn.signFp })).toBe(true)
    expect(drawn.boxFp).not.toBe(drawn.signFp)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   The screen, read as text.

   There is no component-rendering harness in this repository, and the property below is a property
   of the source anyway: does the page a clinician looks at put BOTH fingerprints in front of them.
   A module that computes a value nothing renders is exactly the failure this file is about, so the
   assertions above are not enough on their own — `Enrolment.boxFingerprint` could be correct, and
   correct, and never drawn. Same technique as components/invariants.tree.test.ts, which polices the
   whole tree by reading it.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('the acceptance page shows the clinician both of the values it computed', () => {
  const path = fileURLToPath(new URL('../components/therapist/InviteAcceptance.svelte', import.meta.url))
  const src = readFileSync(path, 'utf8')
  /** Markup only: a fingerprint named in a comment is not a fingerprint anybody can read out. */
  const markup = src.replace(/<script[\s\S]*?<\/script>/g, '').replace(/<!--[\s\S]*?-->/g, '')

  it('the file was read and the script really was stripped', () => {
    // Non-vacuity. Both assertions below are "does this string appear", which is the shape of test
    // that goes green by matching nothing at all.
    expect(src.length).toBeGreaterThan(4000)
    expect(markup).not.toContain('import ')
    expect(markup).toContain('Card')
  })

  it('renders the encryption fingerprint as well as the signing one', () => {
    expect(markup).toContain('boxFingerprint')
    expect(markup).toContain('signFingerprint')
    // Labelled, and labelled with the same two names the owner's console prints above its two
    // fields — otherwise the clinician is reading out two unnamed strings and the person writing
    // them down has to guess which field each belongs in.
    expect(markup).toContain('Encryption key fingerprint')
    expect(markup).toContain('Signing key fingerprint')
  })

  it('and the owner’s console labels them the same way', () => {
    const owner = readFileSync(
      fileURLToPath(new URL('../components/owner/TherapistKeyIntake.svelte', import.meta.url)),
      'utf8',
    ).replace(/<script[\s\S]*?<\/script>/g, '')
    expect(owner.length).toBeGreaterThan(1000)
    expect(owner).toContain('Encryption key fingerprint')
    expect(owner).toContain('Signing key fingerprint')
  })
})
