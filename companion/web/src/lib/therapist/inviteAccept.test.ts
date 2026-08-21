/*
 * The acceptance ceremony, and the four things about it that must not drift.
 *
 * 1. PUBLIC KEYS ARE NEVER REGISTERED BEFORE ENROLMENT SUCCEEDS. The ordering assertions below are
 *    the point of the whole file. A relationship whose keys are on file but whose credential never
 *    enrolled is a relationship the owner can seal a journal to and the therapist can never open.
 * 2. A FAILED CEREMONY LEAVES NOTHING BEHIND. Every refusal path asserts the storage is untouched,
 *    because a half-written record for a relationship with no credential blocks the retry.
 * 3. THE WRAP IS PROVEN BEFORE IT IS TRUSTED. The stored blob is the only copy of the clinician's
 *    secret keys that survives the tab; a passphrase that does not round-trip has to stop the flow
 *    while stopping is still free.
 * 4. THE KEY-CHECKING COPY ASKS FOR A CHANNEL THE SERVER DOES NOT DRAW. "Read it to them", never
 *    "check it matches on screen" — see the last block.
 * 5. A LOST ANSWER IS NOT A REFUSAL. The enrolment commits something no route in this product can
 *    undo, so the rollback in (2) may only run when the server said no in its own words. Rolling
 *    back on silence deletes the clinician's only secret keys out from under a live credential.
 * 6. AND A CEREMONY CUT IN HALF CAN BE FINISHED. A tab that closed between the sign-in and the key
 *    registration used to end the relationship: the invite is spent, a fresh one cannot enrol a
 *    second time, and the owner's console reads 404 forever. The rescue path sends what is left.
 *
 * Both fingerprints are asserted here and the pairing with the owner's console is asserted in
 * owner/keyCeremony.test.ts — the two halves of one check that has to be completable by a person.
 *
 * The crypto is stubbed in most cases and real in one. Argon2id at the 256 MiB floor costs about a
 * second per derivation and the ceremony does two; paying that in every case would buy nothing,
 * because every case but one is about ordering rather than about cryptography.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import {
  AcceptError,
  KEY_CHECK_COPY,
  MIN_PASSPHRASE_CHARS,
  base32,
  beginAcceptance,
  checkPassphrase,
  completeAcceptance,
  findKeyRecord,
  groupForReading,
  loadKeyRecords,
  otpauthUri,
  resumeAcceptance,
  unfinishedKeyRecords,
  type AcceptancePorts,
  type KeyRecordStorage,
} from './inviteAccept'
import { KeyUnwrapError, unwrap, wrap, type TherapistKeys, type WrappedKeyBlob } from './keyStore'
import { fingerprint, initAssignmentCrypto, newBoxKeyPair, newSignKeyPair } from '../assignments/crypto'
import { PortalError, type LoginResult, type RedeemResult } from './session'

const RELREF = 'rel-ref-opaque-0001'
const TICKET = 'enrol-ticket-single-use'
const PASSPHRASE = 'seven brass lanterns humming'

/* ── A storage that behaves like the browser's, and one that refuses to ──────────────────── */

function memoryStorage(seed: string | null = null): KeyRecordStorage & { raw(): string | null } {
  let value = seed
  return {
    getItem: () => value,
    setItem: (_k, v) => {
      value = v
    },
    removeItem: () => {
      value = null
    },
    raw: () => value,
  }
}

/* ── Stub crypto: faithful about the one behaviour the ceremony depends on ───────────────── */

function copyKeys(k: TherapistKeys): TherapistKeys {
  return {
    box: { publicKey: k.box.publicKey.slice(), privateKey: k.box.privateKey.slice() },
    sign: { publicKey: k.sign.publicKey.slice(), privateKey: k.sign.privateKey.slice() },
  }
}

/**
 * Wrap/unwrap without Argon2id. The contract it keeps is the one the ceremony leans on: the same
 * passphrase gives the same keys back, and any other passphrase throws exactly as keyStore does.
 */
function stubCrypto() {
  const vault = new Map<string, TherapistKeys>()
  let n = 0
  return {
    wrapKeys: async (keys: TherapistKeys, passphrase: string): Promise<WrappedKeyBlob> => {
      const ctB64 = `ct-${n++}`
      vault.set(`${ctB64}|${passphrase}`, copyKeys(keys))
      return { v: 1, kdf: { alg: 'argon2id', memMiB: 256, ops: 3 }, saltB64: 'salt', nonceB64: 'nonce', ctB64 }
    },
    unwrapKeys: async (blob: WrappedKeyBlob, passphrase: string): Promise<TherapistKeys> => {
      const held = vault.get(`${blob.ctB64}|${passphrase}`)
      if (!held) throw new KeyUnwrapError('wrong reading passphrase or tampered key blob')
      return copyKeys(held)
    },
  }
}

interface Harness {
  ports: AcceptancePorts
  calls: string[]
  storage: ReturnType<typeof memoryStorage>
  registered: { relRef: string; boxPubB64: string; signPubB64: string }[]
  enrolments: { ticket: string; credentialId: string; secretB64: string }[]
}

function harness(over: Partial<AcceptancePorts> = {}, seed: string | null = null): Harness {
  const calls: string[] = []
  const storage = memoryStorage(seed)
  const registered: Harness['registered'] = []
  const enrolments: Harness['enrolments'] = []
  const crypto = stubCrypto()
  let counter = 0

  const base: AcceptancePorts = {
    redeem: async (): Promise<RedeemResult> => ({ ok: true, relRef: RELREF, scope: ['read.share'], enrollTicket: TICKET }),
    enrol: async (ticket, credentialId, secretB64) => {
      enrolments.push({ ticket, credentialId, secretB64 })
      return 'enrolled'
    },
    login: async (): Promise<LoginResult> => ({
      ok: true,
      session: { relRef: '', credentialKind: 'totp', csrf: 'CSRF', absoluteExpiresAt: 9e15, idleExpiresAt: 9e15 },
    }),
    register: async (session, boxPubB64, signPubB64) => {
      registered.push({ relRef: session.relRef, boxPubB64, signPubB64 })
      return 'registered'
    },
    logout: async () => {},
    newKeys: () => ({ box: newBoxKeyPair(), sign: newSignKeyPair() }),
    wrapKeys: crypto.wrapKeys,
    unwrapKeys: crypto.unwrapKeys,
    randomToken: (bytes) => {
      const raw = new Uint8Array(bytes).fill(++counter)
      return { raw, b64url: `token-${counter}` }
    },
    toBase64: (b) => `b64-${b.length}-${b[0]}`,
    storage,
    now: () => 1_700_000_000_000,
  }

  /*
   * The recorder wraps the ports AFTER the overrides are merged in, not before.
   *
   * The other order is the trap, and this file walked into it once: a case that overrides `enrol`
   * with a failing stub replaces the recording version too, so `calls` loses the very call the case
   * is about, and "enrolment was attempted and refused" becomes indistinguishable from "enrolment
   * was never reached". Wrapping last means every case observes the same sequence regardless of
   * which behaviours it swapped out.
   */
  const merged: AcceptancePorts = { ...base, ...over }
  const ports: AcceptancePorts = { ...merged }
  const recorder = ports as unknown as Record<string, (...args: unknown[]) => unknown>
  for (const name of ['redeem', 'enrol', 'login', 'register', 'logout'] as const) {
    const inner = merged[name] as (...args: unknown[]) => unknown
    recorder[name] = (...args: unknown[]) => {
      calls.push(name)
      return inner(...args)
    }
  }

  return { ports, calls, storage, registered, enrolments }
}

beforeAll(async () => {
  // fingerprint() and the keypair generators are sodium-backed; the stubs above are not.
  await initAssignmentCrypto()
})

/* ═══════════════════════════════════════════════════════════════════════════ */

describe('the ceremony, in order', () => {
  it('redeems, enrols, signs in, registers the public keys, and then drops the session', async () => {
    const h = harness()
    const enrolment = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's3cret', passphrase: PASSPHRASE })
    const done = await completeAcceptance(h.ports, enrolment, '123456')

    // THE ordering claim, as a sequence rather than as four separate "was it called" checks.
    expect(h.calls).toEqual(['redeem', 'enrol', 'login', 'register', 'logout'])
    expect(done.registration).toBe('registered')

    // The relRef in the register call comes from the redeem, not from the login (verify does not
    // echo one) and not from anywhere a caller chose. A wrong value here is a 403, by design.
    expect(h.registered).toEqual([
      { relRef: RELREF, boxPubB64: enrolment.boxPubB64, signPubB64: enrolment.signPubB64 },
    ])

    // The enrolment presented the single-use ticket from the redeem, and a secret long enough for
    // the server to accept (it rejects anything under 16 bytes decoded).
    expect(h.enrolments[0].ticket).toBe(TICKET)
    expect(h.enrolments[0].credentialId).toBe(enrolment.credentialId)

    // The authenticator secret is shown in base32 and sent in base64url — two spellings of the one
    // random value, never re-derived from each other.
    expect(enrolment.totpSecretBase32).toMatch(/^[A-Z2-7]+$/)
    expect(enrolment.otpauthUri).toContain(`secret=${enrolment.totpSecretBase32}`)

    // And the browser kept the wrapped blob, the credential id and nothing else identifying.
    const record = findKeyRecord(RELREF, h.storage)
    expect(record?.credentialId).toBe(enrolment.credentialId)
    expect(h.storage.raw()).not.toContain(PASSPHRASE)
    expect(h.storage.raw()).not.toContain(enrolment.totpSecretBase32)
  })

  it('the session it created does not outlive the ceremony', async () => {
    // The acceptance page cannot open the portal — it holds none of the owner's keys — so a live
    // cookie left behind on a shared clinic machine would be a session nobody is using.
    const h = harness()
    const enrolment = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })
    await completeAcceptance(h.ports, enrolment, '123456')
    expect(h.calls[h.calls.length - 1]).toBe('logout')
  })
})

describe('a redeem that fails costs nothing', () => {
  it('stops at the redeem, stores nothing, and enrols nothing', async () => {
    const h = harness({
      redeem: async () => {
        return { ok: false, error: 'This invite is no longer available.' }
      },
    })
    await expect(
      beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 'wrong', passphrase: PASSPHRASE }),
    ).rejects.toMatchObject({ step: 'redeem' })

    // The redeem was attempted and refused; nothing after it ran.
    expect(h.calls).toEqual(['redeem'])
    expect(h.storage.raw()).toBeNull()
    expect(h.enrolments).toEqual([])
  })

  it('surfaces the wording the client chose, including the rate limit a clinician has to wait out', async () => {
    const h = harness({ redeem: async () => ({ ok: false, error: 'Too many attempts — wait and try again.' }) })
    await expect(
      beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 'x', passphrase: PASSPHRASE }),
    ).rejects.toThrow(/Too many attempts/)
  })
})

describe('a passphrase that does not round-trip stops the ceremony', () => {
  it('refuses when the wrapped blob will not reopen', async () => {
    // The failure this catches is silent by nature: wrapping succeeds, the flow continues, and the
    // clinician discovers weeks later that the only copy of their keys does not open.
    const h = harness({
      unwrapKeys: async () => {
        throw new KeyUnwrapError('wrong reading passphrase or tampered key blob')
      },
    })
    await expect(
      beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE }),
    ).rejects.toMatchObject({ step: 'wrap' })

    expect(h.calls).toEqual(['redeem'])
    expect(h.storage.raw()).toBeNull()
  })

  it('refuses when it reopens to different keys', async () => {
    // The nastier half: an unwrap that returns *something* is not an unwrap that returned YOUR
    // keys, and a blob that opens to the wrong bytes would sign and decrypt nothing.
    const h = harness({
      unwrapKeys: async () => ({ box: newBoxKeyPair(), sign: newSignKeyPair() }),
    })
    await expect(
      beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE }),
    ).rejects.toMatchObject({ step: 'wrap' })
    expect(h.calls).toEqual(['redeem'])
    expect(h.storage.raw()).toBeNull()
  })

  it('and a real passphrase really does round-trip, through the real Argon2id wrap', async () => {
    // The one case that pays for the real derivations. Without it the three stubbed cases above
    // would be a test of the stub.
    const h = harness({ wrapKeys: (k, p) => wrap(k, p), unwrapKeys: (b, p) => unwrap(b, p) })
    const enrolment = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })

    const record = findKeyRecord(RELREF, h.storage)
    expect(record).not.toBeNull()
    const reopened = await unwrap(record!.wrapped, PASSPHRASE)
    expect(Array.from(reopened.sign.publicKey)).toEqual(Array.from(enrolment.keys.sign.publicKey))
    expect(Array.from(reopened.box.privateKey)).toEqual(Array.from(enrolment.keys.box.privateKey))
    // And the stored blob is genuinely closed to anything else.
    await expect(unwrap(record!.wrapped, 'some other passphrase entirely')).rejects.toThrow(KeyUnwrapError)
  }, 60000)
})

describe('keys are registered only after enrolment succeeds', () => {
  it('a refused enrolment never reaches the register call', async () => {
    // The guarantee stated as the failure it prevents: a relationship whose public keys are on file
    // but whose credential never enrolled is one the owner can seal to and the therapist cannot open.
    const h = harness({ enrol: async () => 'refused' })
    await expect(
      beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE }),
    ).rejects.toMatchObject({ step: 'enrol' })

    expect(h.calls).toEqual(['redeem', 'enrol'])
    expect(h.calls).not.toContain('register')
    expect(h.registered).toEqual([])
  })

  it('and rolls the stored record back so a fresh invitation can be accepted later', async () => {
    // Written before the enrolment on purpose (a crash after a server commit would otherwise lose
    // the keys outright). The cost of that choice is this rollback: without it the record would sit
    // there for a relationship with no credential and refuse every retry. Note the stub: 'refused'
    // is the server saying no in its own words, which is the ONLY thing this rollback may run on.
    const h = harness({ enrol: async () => 'refused' })
    await expect(
      beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE }),
    ).rejects.toMatchObject({ step: 'enrol' })
    expect(loadKeyRecords(h.storage)).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   An enrolment whose ANSWER went missing.

   THE BUG THIS BLOCK EXISTS FOR, in full, because every assertion here is shaped by it. `enrol`
   used to answer a boolean and `beginAcceptance` used to roll the stored record back on `false` —
   which collapsed "the server refused" and "I never heard back" into one branch. The second of
   those is not a refusal. `POST /v1/totp/enroll` inserts the credential, spends the ticket and
   drives the invite to CONSUMED in one transaction; no route un-enrols one, and a second attempt
   for the same relationship answers ALREADY_ENROLLED forever. So on a 502, a 504, a VPN flap or a
   backgrounded mobile tab the old code deleted the only copy of the clinician's secret keys, never
   showed them the authenticator secret (it was computed on the success path only), and told them
   "nothing was set up. Ask for a fresh link" — for a relationship that was, at that moment,
   permanently enrolled to a credential nobody on earth held the secret for. Unrepairable without
   deleting a database row by hand.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('an enrolment with no answer is not an enrolment that was refused', () => {
  const lost = { enrol: async () => 'unknown' as const }
  const dropped = {
    enrol: async () => {
      throw new TypeError('NetworkError when attempting to fetch resource.')
    },
  }

  for (const [name, over] of [
    ['a status that says nothing about what the server did', lost],
    ['a fetch that never came back at all', dropped],
  ] as const) {
    it(`keeps the clinician's keys after ${name}`, async () => {
      const h = harness(over)
      const enrolment = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })

      // THE assertion. The wrapped blob is the only copy of these secret keys that survives the
      // tab, and the server may already hold the credential they belong to.
      const record = findKeyRecord(RELREF, h.storage)
      expect(record).not.toBeNull()
      expect(record!.credentialId).toBe(enrolment.credentialId)

      // And the authenticator secret is surfaced rather than dropped, because if the enrolment did
      // commit then this is the secret of the credential that now exists, and nobody else has it.
      expect(enrolment.totpSecretBase32).toMatch(/^[A-Z2-7]+$/)
      expect(enrolment.otpauthUri).toContain(`secret=${enrolment.totpSecretBase32}`)

      // Said plainly rather than dressed as success: the screen has to tell the person this is
      // unknown, and it can only do that if the value reaches it.
      expect(enrolment.serverConfirmedEnrolment).toBe(false)
    })
  }

  it('still registers nothing until a code has actually been accepted', async () => {
    // The ordering guarantee under the new uncertainty. It never rested on the client's belief
    // about the enrolment: registering needs a session, a session needs a code the server accepted,
    // and no code is accepted for a credential that was never inserted. So an enrolment this client
    // is unsure about cannot put a public key on the server ahead of a real one — the server
    // decides, and it decides in the right order.
    const h = harness({ ...lost, login: async () => ({ ok: false, error: 'Code not accepted.' }) })
    const enrolment = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })
    await expect(completeAcceptance(h.ports, enrolment, '000000')).rejects.toMatchObject({ step: 'login' })
    expect(h.calls).toEqual(['redeem', 'enrol', 'login'])
    expect(h.registered).toEqual([])
  })

  it('and finishes normally when the enrolment had in fact committed', async () => {
    // The common case behind an unknown answer: it worked, and the proof is that a code from the
    // authenticator is accepted. Nothing about the rest of the ceremony changes.
    const h = harness(lost)
    const enrolment = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })
    const done = await completeAcceptance(h.ports, enrolment, '123456')
    expect(done.registration).toBe('registered')
    expect(h.calls).toEqual(['redeem', 'enrol', 'login', 'register', 'logout'])
  })

  it('the server saying no in its own words is still a refusal, and still rolls back', async () => {
    // The other half of the distinction, so this block cannot be satisfied by never rolling back at
    // all. 400, 401 and 409 are the statuses the enrol handler emits from branches that write
    // nothing; on those the record must go, or it would block the retry a fresh invite allows.
    const h = harness({ enrol: async () => 'refused' })
    await expect(
      beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE }),
    ).rejects.toMatchObject({ step: 'enrol' })
    expect(loadKeyRecords(h.storage)).toEqual([])
  })
})

describe('this browser never replaces keys it already holds', () => {
  it('refuses a second acceptance for the same relationship, and leaves the first record intact', async () => {
    const first = harness()
    const enrolment = await beginAcceptance(first.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })
    const stored = first.storage.raw()

    const second = harness({}, stored)
    await expect(
      beginAcceptance(second.ports, { inviteId: 'inv-2', secret: 's2', passphrase: 'a different passphrase' }),
    ).rejects.toMatchObject({ step: 'record' })

    expect(second.calls).toEqual(['redeem'])
    expect(second.storage.raw()).toBe(stored)
    expect(findKeyRecord(RELREF, second.storage)?.credentialId).toBe(enrolment.credentialId)
  })

  it('refuses to proceed when the stored records cannot be read at all', async () => {
    // An unreadable record must not become permission. "I cannot tell whether this browser holds
    // keys" and "this browser holds no keys" are different answers, and only one of them is safe.
    const h = harness({}, '{ not json at all')
    await expect(
      beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE }),
    ).rejects.toBeInstanceOf(AcceptError)
    expect(h.calls).toEqual(['redeem'])
  })

  it('refuses when the browser will not store at all', async () => {
    const h = harness({ storage: null })
    await expect(
      beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE }),
    ).rejects.toMatchObject({ step: 'record' })
    expect(h.calls).toEqual(['redeem'])
  })

  it('ignores a record from a format it does not know rather than trusting it', async () => {
    const alien = JSON.stringify([{ v: 2, relRef: RELREF, credentialId: 'c', wrapped: {} }])
    expect(loadKeyRecords(memoryStorage(alien))).toEqual([])
  })
})

describe('the server refusing to overwrite is an answer, not a failure', () => {
  it('reports already-registered rather than throwing or retrying', async () => {
    // 409 means keys are already on file for this relationship. The client cannot tell "me, on
    // another device" from "somebody else" — so it hands the fact back and lets the screen say so.
    const h = harness({ register: async () => 'already-registered' })
    const enrolment = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })
    const done = await completeAcceptance(h.ports, enrolment, '123456')
    expect(done.registration).toBe('already-registered')
  })

  it('a rejected code stops before the keys are offered', async () => {
    const h = harness({ login: async () => ({ ok: false, error: 'Code not accepted.' }) })
    const enrolment = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })
    await expect(completeAcceptance(h.ports, enrolment, '000000')).rejects.toMatchObject({ step: 'login' })
    expect(h.registered).toEqual([])
  })
})

describe('the passphrase gate states a requirement and never grades', () => {
  it('asks for length and for a matching second entry', () => {
    expect(checkPassphrase('', '')).toContain('Choose a reading passphrase.')
    expect(checkPassphrase('short', 'short')[0]).toMatch(new RegExp(String(MIN_PASSPHRASE_CHARS)))
    expect(checkPassphrase(PASSPHRASE, PASSPHRASE + '!')).toHaveLength(1)
    expect(checkPassphrase(PASSPHRASE, PASSPHRASE)).toEqual([])
  })

  it('says nothing that scores, rates or grades what was typed', () => {
    // House rule, and the honest reading of it: a strength meter is a guess presented as a
    // measurement, and it rewards the character-class theatre that produces "Passw0rd!".
    const said = [...checkPassphrase('', ''), ...checkPassphrase('short', 'nope')].join(' ').toLowerCase()
    expect(said).not.toMatch(/\b(weak|strong|medium|score|rating|grade|excellent|good|poor)\b/)
  })
})

describe('the authenticator entry names a deployment, never a client', () => {
  it('encodes RFC 4648 base32, unpadded', () => {
    // RFC 4648 §10 test vectors, so the encoder is checked against the standard rather than
    // against itself. Padding is omitted deliberately: '=' has to be escaped in a URI and several
    // authenticators reject it outright.
    const enc = (s: string) => base32(new TextEncoder().encode(s))
    expect(enc('')).toBe('')
    expect(enc('f')).toBe('MY')
    expect(enc('fo')).toBe('MZXQ')
    expect(enc('foo')).toBe('MZXW6')
    expect(enc('foobar')).toBe('MZXW6YTBOI')
  })

  it('carries the host and an opaque prefix, and no name of any kind', () => {
    const uri = otpauthUri('MZXW6YTBOI', 'therapy.example.org', 'abcdef0123456789')
    expect(uri.startsWith('otpauth://totp/Daymark:')).toBe(true)
    expect(decodeURIComponent(uri)).toContain('therapy.example.org (abcdef01)')
    // The whole relRef never lands in a third-party authenticator backup, and the parameters are
    // stated rather than defaulted — the server is SHA1 / 6 digits / 30s and an app assuming
    // SHA256 would show six wrong digits for reasons neither party could see.
    expect(uri).not.toContain('abcdef0123456789')
    expect(uri).toContain('algorithm=SHA1')
    expect(uri).toContain('digits=6')
    expect(uri).toContain('period=30')
  })
})

describe('the fingerprint is read out, not compared on screen', () => {
  it('groups losslessly, so what is spoken is the whole value', () => {
    const fp = 'AbCd3fGh1jKlMnOpQrSt2u'
    expect(groupForReading(fp).join('')).toBe(fp)
    expect(groupForReading(fp)[0]).toHaveLength(4)
    // The same helper groups the authenticator key, which is copied by eye rather than spoken.
    expect(groupForReading('MZXW6YTBOI').join('')).toBe('MZXW6YTBOI')
  })

  it('the copy asks for a channel the server does not draw', () => {
    // THE security-bearing sentence in this flow. Comparing two screens catches a typo; both
    // screens are drawn by the same server, so a substituted key would be drawn in both places and
    // the comparison would agree about the attacker's key. Only a voice is outside that.
    const all = Object.values(KEY_CHECK_COPY).join(' ')
    expect(all).toMatch(/read (it|this|them|these|both of these) (out|aloud)/i)
    expect(KEY_CHECK_COPY.why).toMatch(/aloud/i)
    expect(all).not.toMatch(/matches on screen|check it matches|make sure (it|they) match/i)
    // And it says what to do when it does not match, which is the half that usually goes missing.
    expect(KEY_CHECK_COPY.mismatch).toMatch(/stop/i)
  })

  it('and asks for both keys, because the console at the other end will not pin on one', () => {
    // The copy has to name the encryption key by name. For as long as it did not, the only
    // fingerprint a clinician could read out was the signing one — and the owner's second field
    // had no honest source anywhere in the product except their own screen, which is the server
    // confirming itself. See owner/keyCeremony.test.ts for the pairing asserted end to end.
    const all = Object.values(KEY_CHECK_COPY).join(' ').toLowerCase()
    expect(all).toContain('encryption key')
    expect(all).toContain('signing key')
    expect(KEY_CHECK_COPY.both).toMatch(/both/i)
    expect(KEY_CHECK_COPY.both).toMatch(/half a check is not a check/i)
  })
})

describe('an enrolment carries the fingerprint of each key it made', () => {
  it('both, and each is the fingerprint of its own public key', async () => {
    const h = harness()
    const e = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })

    // Computed from the bytes rather than copied from anywhere, and computed for BOTH keys. The
    // version of this interface that carried only `signFingerprint` made the owner's confirmation
    // impossible to complete honestly: their gate wants two values and this side produced one.
    expect(e.boxFingerprint).toBe(fingerprint(e.keys.box.publicKey))
    expect(e.signFingerprint).toBe(fingerprint(e.keys.sign.publicKey))
    // Two keys, two different values — a screen showing one twice would look like a completed
    // ceremony and check nothing.
    expect(e.boxFingerprint).not.toBe(e.signFingerprint)
    expect(e.boxFingerprint.length).toBeGreaterThanOrEqual(16)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════
   Finishing a ceremony that was cut in half.

   THE WINDOW THIS BLOCK EXISTS FOR. Between the sign-in and the key registration the server has
   committed everything irreversible — the credential is inserted, the ticket spent, the invite
   CONSUMED — and a tab that closed there used to end the relationship outright. A reload re-redeems
   a consumed invite and gets 410; a fresh invitation redeems fine and then meets AuthStore's
   insert-only enrolment (`SELECT 1 FROM totp WHERE credential_id=? OR rel_ref=?`) and answers 409
   forever; no route anywhere deletes a totp row. Meanwhile the owner's console reads 404 from the
   key route for the life of the relationship, so it pins nothing and seals nothing. Everything
   needed to finish was already in this browser. Now it can send it.
   ═══════════════════════════════════════════════════════════════════════════ */

describe('a browser that died before the last step can still finish', () => {
  /**
   * Get to exactly where the crash happens: enrolled, signed in once, keys never registered.
   *
   * The registration fails ONCE and then behaves, which is the shape of the real failure — a proxy
   * that dropped one request, a laptop that slept between two of them — rather than a server that
   * is broken forever. A stub that refused every time would make the rescue untestable by making it
   * impossible, and would have hidden whether the second attempt even reaches the wire.
   */
  async function halfDone(over: Partial<AcceptancePorts> = {}) {
    const h = harness(over)
    const recorded = h.ports.register
    let firstAttempt = true
    h.ports.register = async (session, boxPubB64, signPubB64) => {
      if (firstAttempt) {
        firstAttempt = false
        throw new PortalError('key registration failed', 502)
      }
      return recorded(session, boxPubB64, signPubB64)
    }
    const enrolment = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })
    await expect(completeAcceptance(h.ports, enrolment, '123456')).rejects.toMatchObject({ step: 'register' })
    return { h, enrolment }
  }

  it('sends the public keys it already made, with the passphrase and one code', async () => {
    const { h, enrolment } = await halfDone()
    expect(h.registered).toEqual([])
    // What the person comes back to: a record in this browser, and nothing on the server.
    const record = findKeyRecord(RELREF, h.storage)!
    expect(record.registeredAt).toBeUndefined()
    expect(unfinishedKeyRecords(h.storage).map((r) => r.relRef)).toEqual([RELREF])

    const done = await resumeAcceptance(h.ports, record, PASSPHRASE, '123456')

    // The SAME keys, not new ones. A rescue that generated a keypair would quietly replace the one
    // the wrapped blob holds, and every share ever sealed to the old one would stop opening.
    expect(done.acceptance.registration).toBe('registered')
    expect(h.registered).toEqual([{ relRef: RELREF, boxPubB64: enrolment.boxPubB64, signPubB64: enrolment.signPubB64 }])
    expect(done.signFingerprint).toBe(enrolment.signFingerprint)
    expect(done.boxFingerprint).toBe(enrolment.boxFingerprint)
  })

  it('redeems nothing, enrols nothing and never touches the invitation', async () => {
    // The invitation is spent by this point and a second enrolment would be refused anyway — but
    // the reason this matters is sharper than that: a rescue that ran the acceptance again would
    // overwrite the stored record, which is the one operation in this module that destroys keys.
    const { h } = await halfDone()
    const before = h.storage.raw()
    const record = findKeyRecord(RELREF, h.storage)!
    h.calls.length = 0
    await resumeAcceptance(h.ports, record, PASSPHRASE, '123456')
    expect(h.calls).toEqual(['login', 'register', 'logout'])
    // The wrapped blob is byte-for-byte what it was; only the registered-at note is added.
    const after = loadKeyRecords(h.storage)[0]!
    expect(JSON.stringify(after.wrapped)).toBe(JSON.stringify(JSON.parse(before!)[0].wrapped))
    expect(after.credentialId).toBe(JSON.parse(before!)[0].credentialId)
  })

  it('stops asking once the server has answered, and asks again for a relationship it has not', async () => {
    const { h } = await halfDone()
    const record = findKeyRecord(RELREF, h.storage)!
    await resumeAcceptance(h.ports, record, PASSPHRASE, '123456')
    expect(unfinishedKeyRecords(h.storage)).toEqual([])
    expect(findKeyRecord(RELREF, h.storage)!.registeredAt).toBe(1_700_000_000_000)
  })

  it('a 409 counts as answered, because that is what a 409 says', async () => {
    // The ordinary answer on this path: very often the keys already on file are the ones this
    // browser registered a moment before the tab closed. Whether they are is not something either
    // side can settle from here — only the fingerprints, read aloud, can — but "the server holds
    // keys for this relationship" is settled, and that is all this flag records.
    const { h } = await halfDone()
    const record = findKeyRecord(RELREF, h.storage)!
    h.ports.register = async () => 'already-registered'
    const done = await resumeAcceptance(h.ports, record, PASSPHRASE, '123456')
    expect(done.acceptance.registration).toBe('already-registered')
    expect(unfinishedKeyRecords(h.storage)).toEqual([])
  })

  it('a wrong passphrase sends nothing and changes nothing', async () => {
    const { h } = await halfDone()
    const record = findKeyRecord(RELREF, h.storage)!
    const before = h.storage.raw()
    h.calls.length = 0
    await expect(resumeAcceptance(h.ports, record, 'not the passphrase', '123456')).rejects.toMatchObject({
      step: 'wrap',
    })
    expect(h.calls).toEqual([])
    expect(h.storage.raw()).toBe(before)
  })

  it('a refused code leaves the record exactly as it found it, still offered', async () => {
    // The retry has to survive a mistyped code. A rescue that marked the record finished, or worse
    // discarded it, on a bad six digits would take the last way back in away over a typo.
    const { h } = await halfDone()
    const record = findKeyRecord(RELREF, h.storage)!
    const before = h.storage.raw()
    h.ports.login = async () => ({ ok: false, error: 'Code not accepted.' })
    await expect(resumeAcceptance(h.ports, record, PASSPHRASE, '000000')).rejects.toMatchObject({ step: 'login' })
    expect(h.storage.raw()).toBe(before)
    expect(unfinishedKeyRecords(h.storage).map((r) => r.relRef)).toEqual([RELREF])
  })

  it('and the record it finishes really does hold the keys it registered', async () => {
    // The one case that pays for real Argon2id here. Everything above runs on the stub vault, which
    // cannot tell whether the blob in storage is the blob the keys went into — and "the rescue sends
    // the same keys" is a claim about exactly that.
    const h = harness({
      wrapKeys: (k, p) => wrap(k, p),
      unwrapKeys: (b, p) => unwrap(b, p),
      register: async () => {
        throw new PortalError('key registration failed', 502)
      },
    })
    const enrolment = await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 's', passphrase: PASSPHRASE })
    await expect(completeAcceptance(h.ports, enrolment, '123456')).rejects.toMatchObject({ step: 'register' })

    h.ports.register = async (session, boxPubB64, signPubB64) => {
      h.registered.push({ relRef: session.relRef, boxPubB64, signPubB64 })
      return 'registered'
    }
    const done = await resumeAcceptance(h.ports, findKeyRecord(RELREF, h.storage)!, PASSPHRASE, '123456')
    expect(done.signFingerprint).toBe(fingerprint(enrolment.keys.sign.publicKey))
    expect(done.boxFingerprint).toBe(fingerprint(enrolment.keys.box.publicKey))
    expect(h.registered).toEqual([{ relRef: RELREF, boxPubB64: enrolment.boxPubB64, signPubB64: enrolment.signPubB64 }])
  }, 60000)
})

describe('nothing durable holds a secret', () => {
  it('the stored record is the wrapped blob and two opaque identifiers', async () => {
    const h = harness()
    await beginAcceptance(h.ports, { inviteId: 'inv-1', secret: 'the-invite-secret', passphrase: PASSPHRASE })
    const raw = h.storage.raw()!
    expect(raw).not.toContain(PASSPHRASE)
    expect(raw).not.toContain('the-invite-secret')
    const record = JSON.parse(raw)[0] as Record<string, unknown>
    expect(Object.keys(record).sort()).toEqual(['createdAt', 'credentialId', 'relRef', 'v', 'wrapped'])
  })
})
