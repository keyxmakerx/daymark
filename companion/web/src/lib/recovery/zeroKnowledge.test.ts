import { describe, it, expect, beforeAll } from 'vitest'
import _sodium from 'libsodium-wrappers-sumo'
import { initCrypto, encryptSnapshot, decryptSnapshot } from '../sync/crypto'
import * as dataKeyModule from './dataKey'
import {
  createRecoverableDataKey,
  replacePassphrase,
  rotateRecoveryCode,
  unwrapWithRecoveryCode,
  wrapExistingDataKey,
  DataKeyError,
  type RecoverableDataKey,
  type SlotKind,
  type WrappedSlot,
} from './dataKey'
import { newRecoveryCode, type RecoveryCode } from './recoveryCode'
import * as migrationModule from './migration'
import { enrolExistingOwner, subkeysFromMaster } from './migration'

/*
 * THE ADVERSARY TEST — the zero-knowledge claim, written as assertions instead of as a paragraph.
 *
 * THE CLAIM. docs/COMPANION_ACCESS_CONTROL.md § Key recovery: "The server never holds a recovery
 * secret. The user can recover; the server still can't read." Adding a second way into the data key
 * is exactly the kind of change that quietly turns that sentence into marketing — the whole point of
 * recovery is that something extra now exists, and if that something extra ends up on the server in
 * any usable form then the product has grown an escrow and a backdoor while claiming it has neither.
 *
 * THE ADVERSARY. `SERVER` below is everything the server holds, and is deliberately built by
 * round-tripping through JSON: it is the bytes on disk, not the objects in memory, so it cannot
 * accidentally close over a variable that the real server would never have. It holds every wrapped
 * slot of every blob, the snapshot ciphertext they protect, and every public parameter. It holds no
 * passphrase, no recovery code, and no data key. That is the full, honest picture of a stolen disk
 * (COMPANION_SECURITY.md §3 T1).
 *
 * EVERY BLOB, NOT THE FIRST ONE. This suite used to hold exactly one blob — the output of
 * createRecoverableDataKey — and that was a hole big enough to drive a key escrow through. A
 * person's blob is rewritten every time they change their passphrase (replacePassphrase), rotate
 * their code (rotateRecoveryCode) or migrate an existing archive (enrolExistingOwner), and a slot
 * appended by any of those three sits on the server for the rest of that account's life. Checking
 * only the first blob a person ever had checks the one moment when nobody has had a chance to add
 * anything. So `SERVER.blobs` is the output of every function in this codebase that publishes one,
 * every assertion below quantifies over all of them, and a test in this file fails if a new
 * publisher is added and not covered here — because "the guard did not run on that path" is the
 * shape of the miss, not a broken cipher.
 *
 * WHAT THIS TEST CAN AND CANNOT DO, SAID PLAINLY. It cannot prove XChaCha20-Poly1305 or Argon2id
 * are secure; nothing in a test file can. What it CAN do is prove that this codebase did not hand
 * the adversary a shortcut past them: that no secret is present in the stored bytes in any encoding,
 * that every value the adversary can actually compute from what it holds fails to open anything,
 * that the slots have no room in them for smuggled material, that the stored structure contains no
 * field beyond the ones this design says it has, and — the assertion the paragraph above is about —
 * that every slot on the server opens under a secret the USER holds, so there is no way in that
 * belongs to somebody else. Those are the ways a zero-knowledge store realistically stops being one
 * — a leaked field, a derivable key, an extra byte, an extra slot — and each of them is a line below.
 */

const PASSPHRASE = 'correct horse battery staple sync'
/** The passphrase after a change. The old one must stop working; the recovery code must not. */
const NEW_PASSPHRASE = 'a completely different passphrase entirely'
/** An already-migrated owner's passphrase, whose master was derived rather than random. */
const LEGACY_PASSPHRASE = 'the passphrase they have had since 2019'
const PLAINTEXT = new TextEncoder().encode('a journal entry that must never be readable by the server')

/** One secret the USER is holding at the moment a blob was published. */
interface UserSecret {
  label: string
  value: string
  /** Which kind of slot it is expected to fit. Only an ordering hint — every secret is tried. */
  kind: SlotKind
}

/**
 * One published blob, as it sits on the server, plus the two things the adversary does NOT get:
 * the key it wraps, and the secrets its owner holds. Both are here so the assertions can say
 * "this is absent from the bytes" and "this, and only this, opens it".
 */
interface StoredBlob {
  label: string
  blob: RecoverableDataKey
  dataKey: Uint8Array
  secrets: UserSecret[]
}

let created: { blob: RecoverableDataKey; recoveryCode: RecoveryCode; dataKey: Uint8Array }
/** Exactly what is on the server's disk: every wrapped blob, and the snapshot they protect. */
let SERVER: { blobs: StoredBlob[]; snapshotEnvelope: Uint8Array }
/** Every secret string that appeared anywhere in this file's history, live or superseded. */
let ALL_SECRETS: string[]
/** Every 32-byte value the adversary can compute from what it holds. None of them may open anything. */
let candidateKeys: { label: string; key: Uint8Array }[]

/** The AAD dataKey.ts binds into every slot. Reproduced here so the adversary attacks the real thing. */
function aadFor(kind: SlotKind): Uint8Array {
  return new TextEncoder().encode(`daymark.datakey.v1|${kind}`)
}

const b64 = (s: string) => _sodium.from_base64(s, _sodium.base64_variants.URLSAFE_NO_PADDING)

/** The bytes a server would have on disk for one blob. */
const storedBytesOf = (b: StoredBlob) => new TextEncoder().encode(JSON.stringify(b.blob))

beforeAll(async () => {
  await initCrypto()

  // 1. A new owner: random data key, wrapped under a passphrase and a fresh recovery code.
  created = await createRecoverableDataKey(PASSPHRASE)
  const envelope = encryptSnapshot(PLAINTEXT, subkeysFromMaster(created.dataKey).syncKey, 'devA', 3)

  // 2. An EXISTING owner migrating an archive: the master is the one their passphrase already
  //    derives, so nothing is re-encrypted. Different code path, same obligation.
  const legacySalt = _sodium.randombytes_buf(_sodium.crypto_pwhash_SALTBYTES)
  const enrolled = await enrolExistingOwner(LEGACY_PASSPHRASE, legacySalt)

  // 3. A passphrase change, and 4. a rotated recovery code. Both rewrite the blob on the server,
  //    and both are places an extra slot would live for the rest of the account's life.
  const afterChange = await replacePassphrase(created.blob, created.dataKey, NEW_PASSPHRASE)
  const afterRotation = await rotateRecoveryCode(created.blob, created.dataKey)

  const onDisk = (blob: RecoverableDataKey) => JSON.parse(JSON.stringify(blob)) as RecoverableDataKey
  SERVER = {
    snapshotEnvelope: Uint8Array.from(envelope),
    blobs: [
      {
        label: 'createRecoverableDataKey',
        blob: onDisk(created.blob),
        dataKey: created.dataKey,
        secrets: [
          { label: 'the passphrase', value: PASSPHRASE, kind: 'passphrase' },
          { label: 'the recovery code', value: created.recoveryCode.canonical, kind: 'recovery' },
        ],
      },
      {
        label: 'enrolExistingOwner (migration)',
        blob: onDisk(enrolled.blob),
        dataKey: enrolled.master,
        secrets: [
          { label: 'the legacy passphrase', value: LEGACY_PASSPHRASE, kind: 'passphrase' },
          { label: 'the recovery code issued at migration', value: enrolled.recoveryCode.canonical, kind: 'recovery' },
        ],
      },
      {
        label: 'replacePassphrase',
        blob: onDisk(afterChange),
        dataKey: created.dataKey,
        secrets: [
          { label: 'the new passphrase', value: NEW_PASSPHRASE, kind: 'passphrase' },
          // Still the ORIGINAL code, deliberately: a passphrase change must not silently invalidate
          // the piece of paper in somebody's filing cabinet.
          { label: 'the unchanged recovery code', value: created.recoveryCode.canonical, kind: 'recovery' },
        ],
      },
      {
        label: 'rotateRecoveryCode',
        blob: onDisk(afterRotation.blob),
        dataKey: created.dataKey,
        secrets: [
          { label: 'the unchanged passphrase', value: PASSPHRASE, kind: 'passphrase' },
          { label: 'the rotated recovery code', value: afterRotation.recoveryCode.canonical, kind: 'recovery' },
        ],
      },
    ],
  }

  ALL_SECRETS = [
    PASSPHRASE,
    NEW_PASSPHRASE,
    LEGACY_PASSPHRASE,
    created.recoveryCode.canonical,
    created.recoveryCode.display,
    enrolled.recoveryCode.canonical,
    enrolled.recoveryCode.display,
    afterRotation.recoveryCode.canonical,
    afterRotation.recoveryCode.display,
  ]

  const b = (bytes: Uint8Array) => _sodium.crypto_generichash(32, bytes)
  const everythingStored = new TextEncoder().encode(
    SERVER.blobs.map((s) => JSON.stringify(s.blob)).join(''),
  )
  const raw = SERVER.blobs.flatMap((s, blobIndex) =>
    s.blob.slots.map((slot, i) => ({
      label: `blob ${blobIndex} slot ${i}`,
      salt: b64(slot.saltB64),
      nonce: b64(slot.nonceB64),
      ct: b64(slot.ctB64),
    })),
  )
  candidateKeys = [
    { label: 'all zeros', key: new Uint8Array(32) },
    { label: 'all ones', key: new Uint8Array(32).fill(0xff) },
    { label: 'hash of everything stored', key: b(everythingStored) },
    { label: 'hash of the snapshot envelope', key: b(SERVER.snapshotEnvelope) },
    ...raw.flatMap((r) => [
      { label: `${r.label} salt, zero-extended`, key: Uint8Array.from([...r.salt, ...new Uint8Array(16)]) },
      { label: `${r.label} salt hashed`, key: b(r.salt) },
      { label: `${r.label} nonce hashed`, key: b(r.nonce) },
      { label: `${r.label} ciphertext hashed`, key: b(r.ct) },
      { label: `${r.label} salt || nonce hashed`, key: b(Uint8Array.from([...r.salt, ...r.nonce])) },
      { label: `${r.label} ciphertext, first 32 bytes`, key: r.ct.slice(0, 32) },
      { label: `${r.label} ciphertext, last 32 bytes`, key: r.ct.slice(r.ct.length - 32) },
    ]),
  ]
}, 600000)

describe('a server holding EVERY published blob and no secret recovers nothing', () => {
  it('holds the output of every function that publishes a blob — the premise of everything below', () => {
    // The premise, asserted rather than assumed. If this list ever stops matching what the module
    // can produce, every guard below is running on a subset of the real surface and reporting a
    // clean bill of health for the paths it never looked at.
    expect(SERVER.blobs.map((s) => s.label)).toEqual([
      'createRecoverableDataKey',
      'enrolExistingOwner (migration)',
      'replacePassphrase',
      'rotateRecoveryCode',
    ])
    expect(SERVER.snapshotEnvelope.length).toBeGreaterThan(0)
    for (const stored of SERVER.blobs) {
      expect(stored.blob.slots.length, stored.label).toBeGreaterThan(0)
      expect(stored.dataKey.length, stored.label).toBe(32)
    }
  })

  it('and there is no OTHER function that publishes one', () => {
    /*
     * The guard on the guard, and the reason this suite grew it: the hole it is closing was never a
     * weak assertion, it was a strong assertion pointed at one of four code paths. The failure mode
     * repeats itself the moment somebody adds a fifth — a WebAuthn-PRF slot, a Shamir split, a
     * second live recovery code, all of which dataKey.ts's slot list is explicitly shaped to accept
     * — and adds it without adding it to the list above.
     *
     * So the exported surface of both modules is pinned. A new export fails this line and the
     * author has to answer one question in the diff: does it publish a blob, and if so, is it in
     * SERVER.blobs? That is a question somebody can answer in ten seconds and cannot answer wrongly
     * by accident, which is the most a test can do about a category of omission.
     */
    const exportedFunctions = (m: object) =>
      Object.entries(m)
        .filter(([, v]) => typeof v === 'function')
        .map(([k]) => k)
        .sort()
    expect(exportedFunctions(dataKeyModule)).toEqual([
      'DataKeyError',
      'createRecoverableDataKey',
      'newDataKey',
      'replacePassphrase',
      'rotateRecoveryCode',
      'unwrapWithPassphrase',
      'unwrapWithRecoveryCode',
      'wrapDataKey',
      'wrapExistingDataKey',
      'zeroizeDataKey',
    ])
    expect(exportedFunctions(migrationModule)).toEqual([
      'enrolExistingOwner',
      'masterFromPassphrase',
      'subkeysFromMaster',
    ])
    // wrapExistingDataKey is the one publisher not listed by name in SERVER.blobs, and it is
    // covered twice over: createRecoverableDataKey and enrolExistingOwner are both a call to it.
    expect(createRecoverableDataKey.toString()).toContain('wrapExistingDataKey')
    expect(enrolExistingOwner.toString()).toContain('wrapExistingDataKey')
    expect(typeof wrapExistingDataKey).toBe('function')
  })

  it('EVERY slot of EVERY published blob opens under a secret the USER holds', async () => {
    /*
     * THE ANTI-ESCROW ASSERTION, and the one this file exists for.
     *
     * "No escrow" is not "the ciphertexts look fine". It is that every way into the data key belongs
     * to the person whose key it is. A slot that opens under an operator's secret is indistinguishable
     * from a legitimate one by every other test here — it is the right length, it holds no plaintext,
     * it has the right fields, its parameters are at the floor — and it is a complete backdoor. The
     * only thing that separates the two is WHOSE SECRET OPENS IT, so that is what is asserted.
     *
     * It costs a full Argon2id per slot, at the 256 MiB / 3-pass floor, and that is the correct
     * price: running the check at weakened parameters would be checking a different system. Each
     * slot's matching secret is tried first so the happy path is one derivation; a slot that opens
     * under nothing tries them all before it fails, which is the case where seconds do not matter.
     */
    for (const stored of SERVER.blobs) {
      for (const [i, slot] of stored.blob.slots.entries()) {
        const ordered = [...stored.secrets].sort((a, b) => Number(b.kind === slot.kind) - Number(a.kind === slot.kind))
        let openedBy: string | null = null
        for (const secret of ordered) {
          if (await opensWith(slot, secret.value, stored.dataKey)) {
            openedBy = secret.label
            break
          }
        }
        expect(
          openedBy,
          `${stored.label}: slot ${i} (${slot.kind}) opens under NO secret its owner holds — ` +
            'that is a slot for somebody else, which is what an escrow is',
        ).not.toBeNull()
      }
    }
  }, 600000)

  it('publishes no slot the user did not ask for', () => {
    // The cheap, structural half of the same claim, and it fires without deriving anything. Every
    // function in this module publishes exactly one passphrase slot and exactly one recovery slot
    // — the format ALLOWS more (a printed code and one in a safe, eventually a Shamir share), and
    // no function here produces them, so an extra slot arriving from one of these paths is an extra
    // slot somebody added rather than one a person asked for.
    for (const stored of SERVER.blobs) {
      expect(stored.blob.slots.map((s) => s.kind).sort(), stored.label).toEqual(['passphrase', 'recovery'])
      expect(stored.blob.v, stored.label).toBe(1)
    }
  })

  it('cannot open any slot with any value it is able to compute', () => {
    for (const stored of SERVER.blobs) {
      for (const slot of stored.blob.slots) {
        const ct = b64(slot.ctB64)
        const nonce = b64(slot.nonceB64)
        for (const candidate of candidateKeys) {
          expect(
            () => _sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ct, aadFor(slot.kind), nonce, candidate.key),
            `${stored.label}: ${slot.kind} slot opened by "${candidate.label}"`,
          ).toThrow()
        }
      }
    }
  })

  it('cannot read the snapshot with any value it is able to compute', () => {
    for (const candidate of candidateKeys) {
      // Both the candidate used directly as a content key and the candidate run through the same
      // subkey derivation a real master would go through — the second is the shape a wrong guess
      // would actually take, since the wrapped secret is a master rather than a content key.
      expect(() => decryptSnapshot(SERVER.snapshotEnvelope, candidate.key, 'devA', 3)).toThrow()
      expect(() => decryptSnapshot(SERVER.snapshotEnvelope, subkeysFromMaster(candidate.key).syncKey, 'devA', 3)).toThrow()
    }
  })

  it('holds no copy of any data key anywhere in the bytes it stores', () => {
    // A direct statement of the property, checked byte by byte rather than argued: the 32 bytes that
    // decrypt everything appear at no offset of anything on disk. This is the assertion that would
    // fail first if someone ever "helpfully" cached the unwrapped key into the stored structure.
    // Every key against every blob, not each against its own, because a blob that carried a
    // DIFFERENT owner's key would be the same disaster and would pass the narrower check.
    const stored = Uint8Array.from([
      ...SERVER.blobs.flatMap((s) => [...storedBytesOf(s)]),
      ...SERVER.snapshotEnvelope,
    ])
    for (const blob of SERVER.blobs) {
      expect(indexOfBytes(stored, blob.dataKey), `${blob.label}: its data key is on disk`).toBe(-1)
    }
  })

  it('holds no secret, in plain text, base64url or hex', () => {
    // Every secret this account has ever had, including superseded ones — a passphrase that was
    // changed and a code that was rotated are exactly as damaging on disk as a live one.
    const stored =
      SERVER.blobs.map((s) => JSON.stringify(s.blob)).join('') +
      Buffer.from(SERVER.snapshotEnvelope).toString('hex')
    for (const secret of ALL_SECRETS) {
      expect(stored).not.toContain(secret)
      expect(stored).not.toContain(Buffer.from(secret).toString('base64url'))
      expect(stored).not.toContain(Buffer.from(secret).toString('hex'))
    }
  })

  it('stores no field beyond the ones this design says it stores', () => {
    // The realistic way a zero-knowledge store stops being one is not a broken cipher; it is a field
    // somebody added. This allowlist is deliberately exact, so ANY new key in the stored structure
    // fails here and has to be argued for on its way in — in any blob, from any publisher.
    for (const stored of SERVER.blobs) {
      expect(Object.keys(stored.blob).sort(), stored.label).toEqual(['slots', 'v'])
      for (const slot of stored.blob.slots) {
        expect(Object.keys(slot).sort(), stored.label).toEqual(['ctB64', 'kdf', 'kind', 'nonceB64', 'saltB64'])
        expect(Object.keys(slot.kdf).sort(), stored.label).toEqual(['alg', 'memMiB', 'ops'])
      }
    }
  })

  it('stores slots with no room in them for anything but a wrapped key', () => {
    // 32 bytes of key plus a 16-byte Poly1305 tag. There is nowhere in a 48-byte ciphertext to hide
    // a hint, a checksum of the passphrase, or a second copy of anything.
    for (const stored of SERVER.blobs) {
      for (const slot of stored.blob.slots) {
        expect(b64(slot.ctB64).length, `${stored.label}: ${slot.kind}`).toBe(48)
      }
    }
  })

  it('keeps every slot at the KDF floor, in every blob it publishes', () => {
    // The floor is what makes a stolen blob expensive to attack, and a blob is rewritten on every
    // passphrase change and every rotation — so "written at the floor once" is not the property that
    // matters. A publisher that dropped its parameters would leave a cheap slot next to a strong one.
    for (const stored of SERVER.blobs) {
      for (const slot of stored.blob.slots) {
        expect(slot.kdf.alg, stored.label).toBe('argon2id')
        expect(slot.kdf.memMiB, stored.label).toBeGreaterThanOrEqual(256)
        expect(slot.kdf.ops, stored.label).toBeGreaterThanOrEqual(3)
      }
    }
  })

  it('gains nothing from a well-formed guess at the recovery code', async () => {
    // The adversary knows the format perfectly — it is in this repository — so it can produce codes
    // that pass the checksum all day. What it cannot do is produce the right one: 143.67 bits, no
    // rate limit to evade and nothing to evade it on, and each guess still costs a full Argon2id.
    const guess = await newRecoveryCode()
    await expect(unwrapWithRecoveryCode(SERVER.blobs[0]!.blob, guess.display)).rejects.toThrow(DataKeyError)
  }, 120000)

  it('the ONE thing the server does hold is the shape of the design, which was always public', () => {
    // Stated so the claim is not overstated: the server learns that this owner has enrolled a
    // recovery code at all, and when the blob was written. That is metadata, it is the same class of
    // leak COMPANION_SECURITY.md § Honest limits already documents, and it is not content.
    for (const stored of SERVER.blobs) {
      expect(stored.blob.slots.some((s) => s.kind === 'recovery'), stored.label).toBe(true)
    }
  })
})

/**
 * Does `secret` open this slot, and does it yield the key it is supposed to?
 *
 * Deliberately re-implemented against raw libsodium rather than calling unwrapWithPassphrase: those
 * take a whole blob and pick a slot by kind, so they cannot ask the question this file needs, which
 * is about ONE slot and any secret at all. Yielding the wrong key counts as not opening it — a slot
 * that holds somebody else's key is not a way in for this owner either.
 */
async function opensWith(slot: WrappedSlot, secret: string, expected: Uint8Array): Promise<boolean> {
  const kek = _sodium.crypto_pwhash(
    32,
    secret,
    b64(slot.saltB64),
    slot.kdf.ops,
    slot.kdf.memMiB * 1024 * 1024,
    _sodium.crypto_pwhash_ALG_ARGON2ID13,
  )
  try {
    const opened = _sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
      null,
      b64(slot.ctB64),
      aadFor(slot.kind),
      b64(slot.nonceB64),
      kek,
    )
    return Buffer.from(opened).equals(Buffer.from(expected))
  } catch {
    return false
  } finally {
    kek.fill(0)
  }
}

/** Index of `needle` inside `haystack`, or -1. Small and obvious on purpose; this is a test. */
function indexOfBytes(haystack: Uint8Array, needle: Uint8Array): number {
  outer: for (let i = 0; i + needle.length <= haystack.length; i++) {
    for (let j = 0; j < needle.length; j++) if (haystack[i + j] !== needle[j]) continue outer
    return i
  }
  return -1
}
