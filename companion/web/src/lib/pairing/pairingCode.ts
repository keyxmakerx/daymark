/*
 * The pairing code — the short secret two people share out loud (plan §3.7.3, §3.7.4).
 *
 * This is the PRS that CPace turns into a key. It is generated on the OWNER'S DEVICE, displayed
 * there, read to the clinician over some channel the server is not on (a phone call, a message
 * in whatever the two people already use), typed once, and never transmitted to the server in
 * any form. That last clause is the hard invariant of §3.7.4 — a server holding the code could
 * run the exchange itself and sit in the middle — and it is why this module has no network
 * import and never will.
 *
 * WHY SIX CHARACTERS IS ENOUGH, WHICH IS THE WHOLE POINT OF USING A PAKE. Against a normal
 * secret, six characters would be absurd: an attacker who captures anything derived from it
 * grinds offline at billions of guesses a second. CPace gives no such handle — a wrong code
 * yields a wrong key and nothing an attacker can test offline — so the only attack is guessing
 * ONLINE, one guess per protocol run, against a server that counts. Five payload symbols over
 * this 31-symbol alphabet is 2^24.8 ≈ 29 million codes; the invite's shared counter arms a
 * lockout after three wrong secrets with backoff capped at an hour, so a link-holder gets on
 * the order of 70 guesses inside a 24-hour invite. That is roughly one chance in 400,000, for
 * an attacker who already had the link. The code is short because the protocol is strong, not
 * despite it.
 *
 * WHY THERE IS A CHECK SYMBOL, and it is not the reason the recovery code has one. A mistyped
 * recovery code costs a person three seconds of Argon2 and an unexplained failure. A mistyped
 * PAIRING code costs a whole round trip through the relay AND SPENDS ONE OF THE GUESSES the
 * lockout is counting — so without a local check, a clinician fumbling a character twice would
 * push an honest pairing towards a lockout that exists to stop attackers. The check symbol
 * catches every single-character error and every transposition before anything leaves the
 * browser, which keeps honest typos out of the attacker budget entirely. That is a security
 * property, not a nicety.
 *
 * THE ALPHABET IS SHARED WITH THE RECOVERY CODE ON PURPOSE. It is a human-factors artefact — 31
 * symbols with both halves of every handwriting collision removed (no 0/O, no 1/I/L) — and the
 * hazard it addresses is identical here: a character read aloud down a phone line and typed by
 * someone who has never seen it written. Importing it rather than copying it means the two
 * cannot drift; the check ARITHMETIC is re-derived below rather than imported, because the
 * payload length differs and because a pairing fault should not surface wearing recovery's
 * error type. pairingCode.test.ts pins the two implementations to the same formula.
 *
 * NO DOM, NO CLOCK, NO NETWORK. Everything except newPairingCode() (which needs a CSPRNG) is a
 * pure function of a string.
 */
import _sodium from 'libsodium-wrappers-sumo'
import { initCrypto } from '../sync/crypto'
import { RECOVERY_ALPHABET, ALPHABET_SIZE } from '../recovery/recoveryCode'

/** The shared 31-symbol alphabet. Re-exported under this name so pairing callers need not
 *  reach into the recovery module, and so the sharing is visible at both ends. */
export const PAIRING_ALPHABET = RECOVERY_ALPHABET

/** Characters that can never appear in a code, so seeing one is a definite error, not a guess. */
const NEVER_IN_A_CODE = new Set(['0', 'O', '1', 'I', 'L'])

/** Symbols carrying entropy. */
export const PAIRING_PAYLOAD_SYMBOLS = 5
/** One check symbol — see the header for why this one is load-bearing rather than polite. */
export const PAIRING_CHECK_SYMBOLS = 1
/** Six. What a person says out loud. */
export const PAIRING_CODE_SYMBOLS = PAIRING_PAYLOAD_SYMBOLS + PAIRING_CHECK_SYMBOLS
/** Symbols per display group: two groups of three, which is how it gets read down a phone. */
export const PAIRING_GROUP_SIZE = 3

/**
 * 24.79 bits. Computed rather than written, so it cannot drift away from the alphabet and length
 * it describes; the test asserts it against the floor the online-guessing argument needs.
 */
export const PAIRING_CODE_ENTROPY_BITS = PAIRING_PAYLOAD_SYMBOLS * Math.log2(ALPHABET_SIZE)

/** A generated code, in both the form CPace eats and the form a person reads aloud. */
export interface PairingCode {
  /** Six symbols, no separators, upper case. THIS is the PRS handed to CPace. */
  canonical: string
  /** The same six as `XXX-XXX`, for showing and for reading out. */
  display: string
}

/** Every distinguishable way a typed code can fail to be a code. One value per diagnosis. */
export type PairingCodeFault =
  | 'empty'
  /** A 0, O, 1, I or L — characters this alphabet excludes precisely so this is unambiguous. */
  | 'confusable'
  | 'notInAlphabet'
  | 'tooShort'
  | 'tooLong'
  /** Well-formed, right length, check symbol disagrees — a misheard or mistyped character. */
  | 'checksum'

export type PairingCodeParse =
  | { ok: true; code: PairingCode }
  | { ok: false; fault: PairingCodeFault; at?: number }

/**
 * What to show a person for each fault.
 *
 * Written for someone mid-ceremony with another person waiting on the line, so every one of
 * them is short and says what to do next. None accuses anybody of anything, and none of them
 * implies an attack: a mistyped character and a hostile guess are indistinguishable to this
 * software, and saying otherwise to the person who simply misheard a letter would be both wrong
 * and unkind.
 */
export const PAIRING_FAULT_TEXT: Record<PairingCodeFault, string> = {
  empty: 'No code was entered yet.',
  confusable: 'Pairing codes never contain 0, O, 1, I or L. Ask for that character again.',
  notInAlphabet: 'That character is not one a pairing code uses.',
  tooShort: 'A pairing code is six characters.',
  tooLong: 'A pairing code is six characters.',
  checksum: 'One character does not match the rest. Worth asking them to read it back once more.',
}

/** Strip separators and upper-case, exactly as the recovery input does and for the same reasons. */
export function normalizePairingInput(typed: string): string {
  return typed.replace(/[\s\-‐-―]+/g, '').toUpperCase()
}

/**
 * The check symbol's numeric value: sum of (position × symbol value) mod 31 — the same formula
 * recoveryCode.ts documents at length, over a five-symbol payload instead of a twenty-nine.
 * Because 31 is prime and the weights 1..5 are distinct and non-zero, the two guarantees carry
 * over unchanged: EVERY single-symbol error and EVERY transposition of two differing symbols is
 * caught with certainty.
 */
function checkValue(payload: string): number {
  let sum = 0
  for (let i = 0; i < payload.length; i++) {
    const value = PAIRING_ALPHABET.indexOf(payload[i])
    // Reachable only through parsePairingCode (which validated every character) or
    // newPairingCode (which drew them from the alphabet). A symbol in neither would index at -1
    // and produce a plausible-looking wrong check value, which is worse than no check at all.
    if (value < 0) throw new PairingCodeError('notInAlphabet', i + 1)
    sum = (sum + (i + 1) * value) % ALPHABET_SIZE
  }
  return sum
}

/** The check symbol for a five-symbol payload. */
export function pairingCheckSymbol(payload: string): string {
  return PAIRING_ALPHABET[checkValue(payload)]
}

/** Six canonical symbols to `XXX-XXX`, which is the form a person sees and says. */
export function formatPairingCode(canonical: string): string {
  const groups: string[] = []
  for (let i = 0; i < canonical.length; i += PAIRING_GROUP_SIZE) {
    groups.push(canonical.slice(i, i + PAIRING_GROUP_SIZE))
  }
  return groups.join('-')
}

/** Thrown by requirePairingCode(); carries the same diagnosis the parse result would have. */
export class PairingCodeError extends Error {
  constructor(
    readonly fault: PairingCodeFault,
    readonly at?: number,
  ) {
    super(PAIRING_FAULT_TEXT[fault])
    this.name = 'PairingCodeError'
  }
}

/**
 * Read a typed code, or say exactly why it is not one. Characters before length before checksum,
 * the same order and for the same reasons as parseRecoveryCode.
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
  const payload = canonical.slice(0, PAIRING_PAYLOAD_SYMBOLS)
  if (canonical.slice(PAIRING_PAYLOAD_SYMBOLS) !== pairingCheckSymbol(payload)) {
    return { ok: false, fault: 'checksum' }
  }
  return { ok: true, code: { canonical, display: formatPairingCode(canonical) } }
}

/** parsePairingCode() for callers that would only have thrown on the failure branch anyway. */
export function requirePairingCode(typed: string): PairingCode {
  const parsed = parsePairingCode(typed)
  if (!parsed.ok) throw new PairingCodeError(parsed.fault, parsed.at)
  return parsed.code
}

/**
 * A fresh code, drawn uniformly from the alphabet by the CSPRNG.
 *
 * Rejection sampling rather than a modulo, for the reason newRecoveryCode sets out: 256 is not a
 * multiple of 31, so `byte % 31` would favour the first eight symbols — a small bias, in the one
 * place where uniformity IS the security argument, and free to remove. Bytes at or above 248 are
 * discarded and redrawn.
 */
export async function newPairingCode(): Promise<PairingCode> {
  await initCrypto()
  const LIMIT = ALPHABET_SIZE * Math.floor(256 / ALPHABET_SIZE) // 248
  const symbols: string[] = []
  while (symbols.length < PAIRING_PAYLOAD_SYMBOLS) {
    const draw = _sodium.randombytes_buf(PAIRING_PAYLOAD_SYMBOLS * 2)
    for (let i = 0; i < draw.length && symbols.length < PAIRING_PAYLOAD_SYMBOLS; i++) {
      if (draw[i] < LIMIT) symbols.push(PAIRING_ALPHABET[draw[i] % ALPHABET_SIZE])
    }
    draw.fill(0)
  }
  const payload = symbols.join('')
  const canonical = payload + pairingCheckSymbol(payload)
  return { canonical, display: formatPairingCode(canonical) }
}
