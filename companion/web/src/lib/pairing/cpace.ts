/*
 * Daymark Companion — CPace, the balanced PAKE for pairing (COMPANION_PAIRING.md §3).
 *
 * CPACE-RISTRETTO255-SHA512, implemented directly against draft-irtf-cfrg-cpace (the CFRG
 * document, not a library's reading of it) and pinned byte-for-byte to the working group's own
 * published test vectors in cpace.test.ts. The Kotlin mirror in
 * sync-crypto/src/main/kotlin/com/daymark/synccrypto/CpaceCrypto.kt MUST stay byte-identical;
 * both sides prove it against the same vectors, which is what makes a JVM party and a browser
 * party arrive at the same key (COMPANION_PAIRING.md §3).
 *
 * What a PAKE buys here, in one sentence: two people who share a short human code (the PRS)
 * derive a strong key through a relay that never learns the code, and an attacker in the middle
 * gets exactly one online guess per protocol run — no offline dictionary, which is why eight
 * human-typable characters are enough where they would be absurd as a password
 * (COMPANION_PAIRING.md §2 and §3).
 *
 * The flow is one round trip and survives store-and-forward relaying (COMPANION_PAIRING.md §3):
 *
 *   party A: start()   → MSGa ── relay ──▶ party B: respond(MSGa) → MSGb, ISK
 *   party A: finish(MSGb) → ISK             (same ISK, or garbage if the codes differed)
 *
 * WRONG CODE ≠ ERROR, and that is load-bearing: a mismatched PRS yields two different ISKs and
 * no signal here. Whether the keys match is learned at the next layer (an AEAD open failing),
 * and the caller there decides what a failure means — see the invite burn rule: a wrong code is
 * indistinguishable from an attacker's guess BY CONSTRUCTION, so it must never destroy anything
 * by itself.
 *
 * What this module deliberately does NOT do: transport, retries, rate limiting, or deciding who
 * is A and who is B. It is pure byte-in/byte-out crypto, like sync/crypto.ts, so both language
 * halves stay comparable line by line.
 */
import _sodium from 'libsodium-wrappers-sumo'

export type Sodium = typeof _sodium
let sodium: Sodium | null = null

/** Must be awaited once before any CPace call (loads the WASM). */
export async function initCpace(): Promise<Sodium> {
  if (sodium) return sodium
  await _sodium.ready
  sodium = _sodium
  return sodium
}

function s(): Sodium {
  if (!sodium) throw new Error('cpace not initialized — await initCpace() first')
  return sodium
}

/* ------------------------------------------------------------------------------------------
 * Constants, verbatim from the draft's CPACE-RISTRETTO255-SHA512 suite.
 * ---------------------------------------------------------------------------------------- */

const DSI = new TextEncoder().encode('CPaceRistretto255')
const DSI_ISK = new TextEncoder().encode('CPaceRistretto255_ISK')
/** SHA-512's input block size — the zero-pad target for the generator string's first block. */
const S_IN_BYTES = 128
export const CPACE_SID_BYTES = 16
export const CPACE_POINT_BYTES = 32
export const CPACE_ISK_BYTES = 64

/* ------------------------------------------------------------------------------------------
 * lv_cat — the draft's length-value encoding (LEB128 lengths), and its exact inverse.
 * ---------------------------------------------------------------------------------------- */

/** LEB128 length prefix + data (draft: prepend_len). 7 bits per byte, least significant first. */
export function prependLen(data: Uint8Array): Uint8Array {
  let length = data.length
  const enc: number[] = []
  for (;;) {
    enc.push(length < 128 ? length : (length & 0x7f) + 0x80)
    length >>>= 7
    if (length === 0) break
  }
  const out = new Uint8Array(enc.length + data.length)
  out.set(enc, 0)
  out.set(data, enc.length)
  return out
}

/** Concatenation of length-prefixed items (draft: lv_cat). */
export function lvCat(...items: Uint8Array[]): Uint8Array {
  const parts = items.map(prependLen)
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let off = 0
  for (const p of parts) {
    out.set(p, off)
    off += p.length
  }
  return out
}

/**
 * Parse exactly [count] lv items and require the input fully consumed — a message with bytes
 * to spare is refused, not tolerated, because "extra bytes nobody parsed" is where a protocol
 * grows a second meaning.
 */
export function parseLv(data: Uint8Array, count: number): Uint8Array[] {
  const out: Uint8Array[] = []
  let off = 0
  for (let i = 0; i < count; i++) {
    let length = 0
    let shift = 0
    for (;;) {
      if (off >= data.length) throw new Error('cpace: truncated message')
      const b = data[off++]
      length |= (b & 0x7f) << shift
      if ((b & 0x80) === 0) break
      shift += 7
      if (shift > 28) throw new Error('cpace: absurd length')
    }
    if (off + length > data.length) throw new Error('cpace: truncated message')
    out.push(data.subarray(off, off + length))
    off += length
  }
  if (off !== data.length) throw new Error('cpace: trailing bytes')
  return out
}

/* ------------------------------------------------------------------------------------------
 * The protocol.
 * ---------------------------------------------------------------------------------------- */

function concat(...arrays: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(arrays.reduce((n, a) => n + a.length, 0))
  let off = 0
  for (const a of arrays) {
    out.set(a, off)
    off += a.length
  }
  return out
}

/**
 * The generator string (draft: generator_string). The zero-pad length is chosen so that
 * DSI, PRS and the pad fill exactly one hash input block — the draft's defence against
 * length-extension-style structure sharing between different PRS values.
 */
export function generatorString(prs: Uint8Array, ci: Uint8Array, sid: Uint8Array): Uint8Array {
  const lenZpad = Math.max(
    0,
    S_IN_BYTES - 1 - prependLen(prs).length - prependLen(DSI).length,
  )
  return lvCat(DSI, prs, new Uint8Array(lenZpad), ci, sid)
}

/** hash-to-group: g = ristretto255_from_hash(SHA-512(generator_string)). */
export function calculateGenerator(prs: Uint8Array, ci: Uint8Array, sid: Uint8Array): Uint8Array {
  const so = s()
  return so.crypto_core_ristretto255_from_hash(so.crypto_hash_sha512(generatorString(prs, ci, sid)))
}

export interface CpaceInputs {
  /** The password-related string — the short human pairing code. Never sent anywhere. */
  prs: Uint8Array
  /** Channel identifier binding both parties' identities. Public, must match on both sides. */
  ci: Uint8Array
  /** Session id, 16 bytes, fresh per pairing attempt. Public. */
  sid: Uint8Array
}

export interface CpaceStartResult {
  /** Secret scalar; keep until finish(), then discard. */
  ya: Uint8Array
  /** lv_cat(Ya, ADa) — the bytes to relay to the other party. */
  msgA: Uint8Array
}

export interface CpaceRespondResult {
  /**
   * Secret scalar. Returned for the same reason [CpaceStartResult] returns the initiator's: a
   * responder who has to survive a page reload keeps this, not the code and not the key, and
   * re-derives with [cpaceResumeRespond]. Discard it when the run ends.
   */
  yb: Uint8Array
  /** lv_cat(Yb, ADb) — the bytes to relay back. */
  msgB: Uint8Array
  /** The 64-byte intermediate session key. Equal on both sides iff PRS/CI/sid matched. */
  isk: Uint8Array
}

function requireSid(sid: Uint8Array): void {
  if (sid.length !== CPACE_SID_BYTES) throw new Error('cpace: sid must be 16 bytes')
}

/**
 * ISK = SHA-512( lv_cat(DSI_ISK, sid, K) || transcript_ir ), with
 * transcript_ir = lv_cat(Ya, ADa) || lv_cat(Yb, ADb) — the initiator/responder ordering.
 * Our flow always has distinguishable roles (the owner initiates: COMPANION_PAIRING.md §1, "Who
 * starts a pairing"), so the parallel-execution ("oc") transcript variant is deliberately not
 * implemented.
 */
function deriveIsk(sid: Uint8Array, k: Uint8Array, msgA: Uint8Array, msgB: Uint8Array): Uint8Array {
  return s().crypto_hash_sha512(concat(lvCat(DSI_ISK, sid, k), msgA, msgB))
}

/** Party A, step 1. [scalar] is for test vectors only — production omits it and gets a fresh one. */
export function cpaceStart(inputs: CpaceInputs, ada: Uint8Array, scalar?: Uint8Array): CpaceStartResult {
  const so = s()
  requireSid(inputs.sid)
  const g = calculateGenerator(inputs.prs, inputs.ci, inputs.sid)
  const ya = scalar ?? so.crypto_core_ristretto255_scalar_random()
  const Ya = so.crypto_scalarmult_ristretto255(ya, g)
  return { ya, msgA: lvCat(Ya, ada) }
}

/** Party B: consume MSGa, produce MSGb and the key. Throws on an invalid or identity point. */
export function cpaceRespond(
  inputs: CpaceInputs,
  msgA: Uint8Array,
  adb: Uint8Array,
  scalar?: Uint8Array,
): CpaceRespondResult {
  const so = s()
  requireSid(inputs.sid)
  const [Ya] = parseLv(msgA, 2)
  if (Ya.length !== CPACE_POINT_BYTES) throw new Error('cpace: bad point length')
  const g = calculateGenerator(inputs.prs, inputs.ci, inputs.sid)
  const yb = scalar ?? so.crypto_core_ristretto255_scalar_random()
  const Yb = so.crypto_scalarmult_ristretto255(yb, g)
  // scalar_mult_vfy: libsodium throws here when Ya is not a valid encoding or the result is
  // the identity — the draft's mandatory abort, not a condition we get to soften.
  const k = so.crypto_scalarmult_ristretto255(yb, Ya)
  const msgB = lvCat(Yb, adb)
  return { yb, msgB, isk: deriveIsk(inputs.sid, k, msgA, msgB) }
}

/**
 * Party B, again: the same key from the same run, without the code.
 *
 * THE RESPONDER'S MIRROR OF cpaceFinish, and it exists for the same reason — a ceremony that spans
 * a page reload must be finishable from what a device may keep, and what a device may keep never
 * includes the code. The generator was spent in [cpaceRespond]; what is still needed is the scalar
 * (secret, kept), and the two transcript messages and the sid (public, already on the wire). That
 * is why the responder's persisted half can carry MSGb: recomputing it would need the generator,
 * and the generator would need the code back.
 *
 * Given the same inputs it returns the same 64 bytes [cpaceRespond] did, so a resumed run and an
 * unbroken one are indistinguishable to everything downstream. Throws on an invalid or identity
 * point, exactly as the first pass does.
 */
export function cpaceResumeRespond(
  inputs: Pick<CpaceInputs, 'sid'>,
  yb: Uint8Array,
  msgA: Uint8Array,
  msgB: Uint8Array,
): Uint8Array {
  const so = s()
  requireSid(inputs.sid)
  const [Ya] = parseLv(msgA, 2)
  if (Ya.length !== CPACE_POINT_BYTES) throw new Error('cpace: bad point length')
  const k = so.crypto_scalarmult_ristretto255(yb, Ya)
  return deriveIsk(inputs.sid, k, msgA, msgB)
}

/**
 * Party A, final step: consume MSGb, produce the key. Throws on an invalid or identity point.
 *
 * Takes only the sid from the inputs: the PRS and CI were spent computing the generator in
 * cpaceStart and are not needed again. The narrowed type says so, which is what lets a caller
 * persist the owner's half of a run without the code (relay.ts, ownerRunStore.ts).
 */
export function cpaceFinish(
  inputs: Pick<CpaceInputs, 'sid'>,
  start: CpaceStartResult,
  msgB: Uint8Array,
): Uint8Array {
  const so = s()
  requireSid(inputs.sid)
  const [Yb] = parseLv(msgB, 2)
  if (Yb.length !== CPACE_POINT_BYTES) throw new Error('cpace: bad point length')
  const k = so.crypto_scalarmult_ristretto255(start.ya, Yb)
  return deriveIsk(inputs.sid, k, start.msgA, msgB)
}
