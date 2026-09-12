/*
 * NOTHING THIS BROWSER KEEPS CARRIES A BARE INBOX TOKEN.
 *
 * THE PROPERTY. The token is a second factor on every route that serves relationship content, and
 * its digest is the relationship reference itself. A copied browser profile already carries the
 * Argon2id-wrapped keys and the credential id; adding the token in the clear beside them would put
 * both halves of what the server asks for in one place, with the wrapping passphrase protecting
 * neither — it is not inside the blob. So the token is held in sessionStorage, for the life of one
 * tab, and localStorage holds what inviteAccept.ts says it holds and nothing else.
 *
 * WHY THIS FILE EXISTS RATHER THAN A COMMENT. KeyRecord used to declare an optional `inboxToken`
 * field and LoginGate used to try to fill it in after every successful sign-in. That write threw
 * every single time — saveKeyRecord is insert-only and the record had just been read out of storage
 * — into a catch written for a browser refusing storage, where it was swallowed. Nobody was ever
 * told, no test in the tree touched the field, and the only visible symptom was a clinician
 * retyping forty-three characters at every visit (issue #125). The obvious repair, forget-then-save,
 * would have quietly made the bare-token-at-rest case real. This is what makes that a decision
 * somebody has to take on purpose rather than a side effect of fixing a throw.
 *
 * THE DETECTOR IS PROVED FIRST, EVERY TIME. An absence assertion over a stringified record is worth
 * exactly as much as the search that reads it: a check that cannot see a token nested inside a
 * record proves only that it is blind, and blindness is indistinguishable from the property holding.
 */
import { describe, it, expect, beforeAll } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  loadKeyRecords,
  type AcceptancePorts,
  type Enrolment,
  type KeyRecordStorage,
} from './inviteAccept'
import { answerPairing, enrolAfterApproval, type PairingAcceptancePorts } from './pairingAccept'
import { newPairingCode, type PairingCode } from '../pairing/pairingCode'
import { recallInboxToken, rememberInboxToken, type InboxTokenStorage } from './inboxTokenStore'
import { KeyUnwrapError, type TherapistKeys, type WrappedKeyBlob } from './keyStore'
import { initAssignmentCrypto, newBoxKeyPair, newSignKeyPair } from '../assignments/crypto'
import type { LoginResult } from './session'

const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')
const INVITE_ACCEPT = read('./inviteAccept.ts')
const LOGIN_GATE = read('../components/therapist/LoginGate.svelte')

/**
 * What LoginGate SHIPS, with the commentary removed.
 *
 * Both files document the write they no longer make, by name and in full, so that the next reader
 * does not mistake its absence for an oversight. A check run over the raw source would read those
 * explanations as the thing they explain — which is how a guard ends up forbidding a file from
 * describing its own history.
 */
const LOGIN_GATE_CODE = LOGIN_GATE.replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(?<!:)\/\/[^\n]*/g, '')

const RELREF = 'rel-ref-opaque-0001'
const PASSPHRASE = 'seven brass lanterns humming'

/** A real minted token's shape: 43 characters of base64url, as owner/inboxToken.ts produces. */
const TOKEN = 'kQ7bXm2pR9tYw4vZ1nL6sH8jF3dG5aC0eB7uI9oP2xM'

/* ── The detector, and the plants that prove it can see ──────────────────────────────────── */

/**
 * Does this stored text disclose the token?
 *
 * Three encodings, because "the token is not in the string" is not the same claim as "no
 * representation of the token is in the string". A record is JSON, a JSON string escapes little,
 * and a URL-safe token survives percent-encoding unchanged — so the escaped and encoded forms are
 * checked too rather than assumed identical.
 */
function discloses(stored: string | null, token: string): boolean {
  if (!stored) return false
  const forms = [token, JSON.stringify(token).slice(1, -1), encodeURIComponent(token)]
  return forms.some((form) => stored.includes(form))
}

/* ── A ceremony harness: the same ports inviteAccept.test.ts drives, trimmed to what is needed ── */

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

function memorySession(): InboxTokenStorage & { raw(): string | null } {
  let value: string | null = null
  return {
    getItem: () => value,
    setItem: (_k, v) => {
      value = v
    },
    raw: () => value,
  }
}

function copyKeys(k: TherapistKeys): TherapistKeys {
  return {
    box: { publicKey: k.box.publicKey.slice(), privateKey: k.box.privateKey.slice() },
    sign: { publicKey: k.sign.publicKey.slice(), privateKey: k.sign.privateKey.slice() },
  }
}

/** Wrap/unwrap without Argon2id — this file is about what is stored, not about the KDF. */
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

function harness() {
  const storage = memoryStorage()
  const crypto = stubCrypto()
  let counter = 0
  const ports: AcceptancePorts = {
    enrol: async () => 'enrolled',
    login: async (): Promise<LoginResult> => ({
      ok: true,
      session: { relRef: '', credentialKind: 'totp', csrf: 'CSRF', absoluteExpiresAt: 9e15, idleExpiresAt: 9e15 },
    }),
    register: async () => 'registered',
    logout: async () => {},
    newKeys: () => ({ box: newBoxKeyPair(), sign: newSignKeyPair() }),
    wrapKeys: crypto.wrapKeys,
    unwrapKeys: crypto.unwrapKeys,
    randomToken: (bytes) => ({ raw: new Uint8Array(bytes).fill(++counter), b64url: `token-${counter}` }),
    toBase64: (b) => `b64-${b.length}-${b[0]}`,
    storage,
    now: () => 1_700_000_000_000,
  }
  return { ports, storage }
}

let PAIRING_CODE: PairingCode

beforeAll(async () => {
  await initAssignmentCrypto()
  PAIRING_CODE = await newPairingCode()
})

/** The whole ceremony, exactly as inviteAccept.test.ts drives it. The relay itself is stubbed. */
async function accept(ports: AcceptancePorts): Promise<Enrolment> {
  const pairing: PairingAcceptancePorts = {
    answer: async (args) => {
      await args.makeOffer(RELREF)
      return { exchangeId: 'ex-1', relRef: RELREF }
    },
    status: async () => ({ state: 'approved', scope: ['read.share'] }),
    accept: ports,
    runStorage: null,
    wait: async () => {},
  }
  const run = await answerPairing(pairing, {
    inviteId: 'inv-1',
    secret: 's3cret',
    typedCode: PAIRING_CODE.display,
    passphrase: PASSPHRASE,
    displayName: 'Dr Example',
  })
  return enrolAfterApproval(pairing, run, ['read.share'])
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) The detector, before anything is asserted with it.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) the detector can see a token where one would actually hide', () => {
  it('finds one nested inside a stringified record', () => {
    /*
     * THE PLANT. Shaped exactly like the record this test later asserts is clean, with the token in
     * the field that used to exist. If `discloses` cannot see this, every absence below is vacuous.
     */
    const planted = JSON.stringify([
      {
        v: 1,
        relRef: RELREF,
        credentialId: 'cred-1',
        wrapped: { v: 1, kdf: { alg: 'argon2id', memMiB: 256, ops: 3 }, saltB64: 's', nonceB64: 'n', ctB64: 'c' },
        createdAt: 1_700_000_000_000,
        inboxToken: TOKEN,
      },
    ])
    expect(discloses(planted, TOKEN)).toBe(true)
  })

  it('finds one buried deeper than the shape anybody would look for', () => {
    // A field added later, under another name, nested inside the wrapped blob's own object. The
    // search is over the serialised text precisely so the shape of the record cannot hide anything.
    const planted = JSON.stringify([
      { v: 1, relRef: RELREF, wrapped: { ctB64: 'c', meta: { routing: { header: TOKEN } } } },
    ])
    expect(discloses(planted, TOKEN)).toBe(true)
  })

  it('finds one that went in percent-encoded or escaped', () => {
    expect(discloses(`{"t":"${encodeURIComponent(TOKEN)}"}`, TOKEN)).toBe(true)
    expect(discloses(JSON.stringify({ t: TOKEN }), TOKEN)).toBe(true)
  })

  it('does not fire on a record that genuinely has no token in it', () => {
    // The other direction: a detector that answered true for everything would pass (a) and make
    // every assertion below meaningless in the opposite way.
    const clean = JSON.stringify([{ v: 1, relRef: RELREF, credentialId: 'cred-1', createdAt: 1 }])
    expect(discloses(clean, TOKEN)).toBe(false)
    expect(discloses(null, TOKEN)).toBe(false)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) After the ceremony and after a sign-in, localStorage holds no token.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) what survives the tab, and what does not', () => {
  it('the acceptance ceremony writes a record, and the record carries no token', async () => {
    const { ports, storage } = harness()
    await accept(ports)

    // The ceremony really did store something — otherwise "no token in it" is trivially true.
    const records = loadKeyRecords(storage)
    expect(records).toHaveLength(1)
    expect(records[0]?.relRef).toBe(RELREF)
    expect(records[0]?.wrapped.ctB64).toBeTruthy()

    expect(discloses(storage.raw(), TOKEN)).toBe(false)
  })

  it('a sign-in remembers the token for the tab and adds nothing to the durable record', async () => {
    /*
     * The sign-in half, as LoginGate performs it: the token is written to the tab store once the
     * unlock has succeeded, and the key record is not touched. The old code path is what this
     * replaces — `saveKeyRecord({ ...rec, inboxToken: token })`, which threw, silently, always.
     */
    const { ports, storage } = harness()
    await accept(ports)
    const before = storage.raw()

    const tab = memorySession()
    rememberInboxToken(tab, RELREF, TOKEN)

    // It was remembered — so the absence below is about WHERE, not about nothing having happened.
    expect(recallInboxToken(tab, RELREF)).toBe(TOKEN)
    expect(discloses(tab.raw(), TOKEN)).toBe(true)

    // And the durable record is byte-for-byte what it was.
    expect(storage.raw()).toBe(before)
    expect(discloses(storage.raw(), TOKEN)).toBe(false)
  })

  it('no field of a stored record is a token-shaped string', async () => {
    /*
     * A second reading of the same property that does not depend on knowing the token's value: a
     * record that picked up a bare credential under some future name would still be caught, because
     * nothing a KeyRecord legitimately holds is 43 characters of base64url.
     */
    const { ports, storage } = harness()
    await accept(ports)
    const TOKEN_SHAPED = /^[A-Za-z0-9_-]{43}$/
    expect(TOKEN_SHAPED.test(TOKEN), 'the shape check does not recognise a real token').toBe(true)

    const found: string[] = []
    const walk = (node: unknown, path: string): void => {
      if (typeof node === 'string') {
        if (TOKEN_SHAPED.test(node)) found.push(`${path} = ${node}`)
      } else if (node && typeof node === 'object') {
        for (const [k, v] of Object.entries(node)) walk(v, `${path}.${k}`)
      }
    }
    walk(JSON.parse(storage.raw() ?? '[]'), 'record')
    expect(found).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) The dead write, and the field it wrote into, are gone from the source.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(c) there is no longer anywhere for a token to be put at rest', () => {
  it('KeyRecord declares no inbox token field', () => {
    // The declaration, not a mention: the header above KeyRecord discusses the field it dropped, by
    // name, and must keep being allowed to.
    const DECLARED = /^\s*inboxToken\??\s*:/m
    expect(DECLARED.test('  /** kept so a clinician types it once */\n  inboxToken?: string\n')).toBe(true)
    expect(DECLARED.test(INVITE_ACCEPT)).toBe(false)
  })

  it('the sign-in never writes a key record', () => {
    /*
     * saveKeyRecord is insert-only by design, which is what made the old call a guaranteed throw.
     * The screen has no business writing one at all: the record is the acceptance ceremony's to
     * create, and a sign-in that has already succeeded must not be able to fail afterwards.
     */
    const CALLS = /\bsaveKeyRecord\s*\(/
    expect(CALLS.test('saveKeyRecord({ ...rec, inboxToken: token })')).toBe(true)
    expect(CALLS.test(LOGIN_GATE_CODE)).toBe(false)
  })

  it('the sign-in remembers the token in the tab store, and names no durable one', () => {
    expect(LOGIN_GATE).toContain('rememberInboxToken(tokenPort, relationship, token)')
    // The one storage this screen may reach for is the one that dies with the tab. A planted line
    // shows the check is not blind to the alternative.
    const DURABLE = /\blocalStorage\b/
    expect(DURABLE.test('const port = globalThis.localStorage')).toBe(true)
    expect(DURABLE.test(LOGIN_GATE_CODE)).toBe(false)
  })

  it('inviteAccept.ts says what it keeps, and no longer says it keeps less than it does', () => {
    // The header drifted from the record for months — it claimed the wrapped blob and two
    // identifiers while a field for a bare credential sat below it (issue #125).
    expect(INVITE_ACCEPT).toMatch(/inboxTokenStore\.ts/)
    expect(INVITE_ACCEPT).toMatch(/is NOT in that record/)
  })
})
