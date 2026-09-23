/*
 * THE PAIRING CODE — the short secret an owner says to their therapist so that the two of them,
 * and nobody holding only the emailed link, end up with the same key.
 *
 * WHAT IT IS FOR, AND WHAT IT IS NOT. It is the password-related string of a CPace run (cpace.ts).
 * It is generated on the owner's device, shown there, spoken or texted to the therapist by any
 * route EXCEPT the one the link took, typed once on the therapist's device, and never sent to the
 * server by either side (COMPANION_PAIRING.md §5; relay.test.ts greps the wire for it). It is NOT
 * a recovery code, and the two must never be confused, which is why this is its own file rather
 * than a parameter on recovery/recoveryCode.ts: a recovery code opens a person's own data key and
 * is never to be said aloud to anyone; a pairing code is MEANT to be said aloud, once, to one
 * person. Same alphabet, opposite instructions. The framing around each has to say which one it is.
 *
 * SHAPE. Eight symbols shown as two groups of four — K7M4-RD96. Seven carry entropy and the eighth
 * is a check symbol, over the same 31-symbol alphabet as the recovery code (digits 2–9, letters
 * A–Z without I, L and O). Decided 2026-09-03; recorded in docs/COMPANION_PAIRING.md §2.
 *
 * WHY THE CHECK SYMBOL IS WORTH A CHARACTER HERE. A wrong pairing code produces no error by design:
 * two different keys and silence (relay.ts, WRONG CODE ≠ ERROR). So the ONLY place a mistyped code
 * can be caught is before the run, on the therapist's device, and the check symbol does that with
 * certainty for any single wrong character and any swapped pair. The proof is with checkValue() in
 * recoveryCode.ts and holds for a seven-symbol payload because the weights 1..7 are distinct and
 * non-zero mod 31. A typo is refused instantly with a position; only a code that is well-formed AND
 * wrong goes on to spend one of the invitation's eight exchanges.
 *
 * ENTROPY, STATED RATHER THAN IMPLIED. Seven symbols over 31 is 7 × log2(31) = 34.68 bits. That
 * would be absurd for a password and is enough here because of what the PAKE gives: an attacker
 * holding the link gets at most one online test per exchange the owner opens, at most eight
 * exchanges per invitation, and zero offline tests (COMPANION_PAIRING.md §2). Eight guesses out of
 * 31^7 ≈ 27.5 billion is a chance below one in three billion per invitation, and each guess is an
 * audited event the owner sees counted. More symbols would buy nothing against that bound and cost
 * every clinician a longer read-back.
 *
 * NO ECHO, NO CORRECTION, NO NETWORK. As recoveryCode.ts: fault text never quotes what was typed,
 * nothing is auto-corrected, and everything except newPairingCode() is a pure function of a string.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { initCrypto } from '../sync/crypto'
import { RECOVERY_ALPHABET, ALPHABET_SIZE, normalizeRecoveryInput, checkSymbol } from '../recovery/recoveryCode'

/** The same 31 symbols, in the same order, as the recovery code. The order is the check arithmetic. */
export const PAIRING_ALPHABET = RECOVERY_ALPHABET

/** Characters that can never appear in a code, so seeing one is a definite error, not a guess. */
const NEVER_IN_A_CODE = new Set(['0', 'O', '1', 'I', 'L'])

export const PAIRING_PAYLOAD_SYMBOLS = 7
export const PAIRING_CHECK_SYMBOLS = 1
export const PAIRING_CODE_SYMBOLS = PAIRING_PAYLOAD_SYMBOLS + PAIRING_CHECK_SYMBOLS
/** Two groups of four. */
export const PAIRING_GROUP_SIZE = 4

/** 34.68 bits. Computed so it cannot drift from the alphabet and length it describes. */
export const PAIRING_CODE_ENTROPY_BITS = PAIRING_PAYLOAD_SYMBOLS * Math.log2(ALPHABET_SIZE)

/**
 * A code that has been through parsePairingCode(): exactly eight upper-case symbols, no separators,
 * check symbol verified. The brand exists so that relay.ts can refuse, at the type level, to take a
 * string somebody typed; the runtime check in relay.ts refuses it again at the value level, because
 * a cast is one keystroke and the recurring bug in this repo is a check that assumes its input.
 */
export type CanonicalPairingCode = string & { readonly __brand: 'CanonicalPairingCode' }

/** The shape of a canonical code, built from the alphabet so the two cannot disagree. */
export const CANONICAL_PAIRING_CODE = new RegExp(`^[${PAIRING_ALPHABET}]{${PAIRING_CODE_SYMBOLS}}$`)

/** True only for a string that is already canonical. Does not normalise; that is the parser's job. */
export function isCanonicalPairingCode(s: string): s is CanonicalPairingCode {
  if (!CANONICAL_PAIRING_CODE.test(s)) return false
  const payload = s.slice(0, PAIRING_PAYLOAD_SYMBOLS)
  return s.slice(PAIRING_PAYLOAD_SYMBOLS) === checkSymbol(payload)
}

export interface PairingCode {
  /** Eight symbols, no separators. THIS is what enters the ceremony. */
  canonical: CanonicalPairingCode
  /** The same symbols as `XXXX-XXXX`, for showing on the owner's screen and saying aloud. */
  display: string
}

export type PairingCodeFault = 'empty' | 'confusable' | 'notInAlphabet' | 'tooShort' | 'tooLong' | 'checksum'

export type PairingCodeParse =
  | { ok: true; code: PairingCode }
  | { ok: false; fault: PairingCodeFault; at?: number }

/**
 * What to show the therapist for each fault. Sentences, in the register of a person reading a code
 * back over the phone. None echoes the input, none says who got it wrong, none scores anything.
 */
export const PAIRING_FAULT_TEXT: Record<PairingCodeFault, string> = {
  empty: 'No code was entered.',
  confusable:
    'Pairing codes never contain the characters 0, O, 1, I or L. Check that character with the person who gave you the code.',
  notInAlphabet: 'That character is not one a pairing code uses.',
  tooShort: 'A pairing code has eight characters. Some are missing.',
  tooLong: 'A pairing code has eight characters. There are too many here.',
  checksum: 'One character does not match the rest of the code. Read it back with the person who gave it to you.',
}

export class PairingCodeError extends Error {
  constructor(
    readonly fault: PairingCodeFault,
    readonly at?: number,
  ) {
    super(PAIRING_FAULT_TEXT[fault])
    this.name = 'PairingCodeError'
  }
}

/** Strip separators and whitespace, upper-case. Same rules as the recovery code, for the same reasons. */
export const normalizePairingInput = normalizeRecoveryInput

/** Eight canonical symbols to `XXXX-XXXX`. */
export function formatPairingCode(canonical: string): string {
  const groups: string[] = []
  for (let i = 0; i < canonical.length; i += PAIRING_GROUP_SIZE) groups.push(canonical.slice(i, i + PAIRING_GROUP_SIZE))
  return groups.join('-')
}

/**
 * Read a typed code, or say exactly why it is not one. Characters before length, length before
 * checksum: the order of diagnosis recoveryCode.ts argues for applies unchanged.
 */
export function parsePairingCode(typed: string): PairingCodeParse {
  const canonical = normalizePairingInput(typed)
  if (canonical.length === 0) return { ok: false, fault: 'empty' }
  for (let i = 0; i < canonical.length; i++) {
    const ch = canonical[i]
    if (NEVER_IN_A_CODE.has(ch)) return { ok: false, fault: 'confusable', at: i + 1 }
    if (!PAIRING_ALPHABET.includes(ch)) return { ok: false, fault: 'notInAlphabet', at: i + 1 }
  }
  if (canonical.length < PAIRING_CODE_SYMBOLS) return { ok: false, fault: 'tooShort' }
  if (canonical.length > PAIRING_CODE_SYMBOLS) return { ok: false, fault: 'tooLong' }
  if (!isCanonicalPairingCode(canonical)) return { ok: false, fault: 'checksum' }
  return { ok: true, code: { canonical, display: formatPairingCode(canonical) } }
}

/** parsePairingCode() for callers that would only have thrown on the failure branch anyway. */
export function requirePairingCode(typed: string): PairingCode {
  const parsed = parsePairingCode(typed)
  if (!parsed.ok) throw new PairingCodeError(parsed.fault, parsed.at)
  return parsed.code
}

/**
 * A fresh code for one invitation, drawn uniformly by the CSPRNG with the same rejection sampling
 * recoveryCode.ts explains (bytes ≥ 248 are redrawn so no symbol is favoured).
 *
 * ONE CODE PER INVITATION. The caller draws a new one for every run it opens after a mismatch
 * ("New code"), never reuses one across invitations, and never stores it: the owner's persisted half
 * of a run (ownerRunStore.ts) deliberately cannot hold it.
 */
export async function newPairingCode(): Promise<PairingCode> {
  await initCrypto()
  const LIMIT = ALPHABET_SIZE * Math.floor(256 / ALPHABET_SIZE) // 248
  const symbols: string[] = []
  while (symbols.length < PAIRING_PAYLOAD_SYMBOLS) {
    const draw = _sodium.randombytes_buf(PAIRING_PAYLOAD_SYMBOLS)
    for (let i = 0; i < draw.length && symbols.length < PAIRING_PAYLOAD_SYMBOLS; i++) {
      if (draw[i] < LIMIT) symbols.push(PAIRING_ALPHABET[draw[i] % ALPHABET_SIZE])
    }
    draw.fill(0)
  }
  const payload = symbols.join('')
  const canonical = (payload + checkSymbol(payload)) as CanonicalPairingCode
  return { canonical, display: formatPairingCode(canonical) }
}
