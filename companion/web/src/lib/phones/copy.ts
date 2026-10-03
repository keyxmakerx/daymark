/*
 * EVERY WORD THE PHONES SECTION SAYS (#431), and nothing the server said.
 *
 * Fixed templates only. The slots are the server's address, the code, the six words, the time left,
 * dates and a count, and nothing else: no refusal, error or status text from the server reaches the
 * page, because the server's words are not the product's words and a person pairing a phone reads
 * every one of them. The person's word is "disconnect" everywhere; "revoke" stays with shares and
 * grants.
 *
 * PURE. Strings and small functions that fill their slots.
 */
import { MONTH_NAMES } from '../calendar/core'

export const PHONES_HEADING = 'Phones'

/* ── Before the section can do anything ─────────────────────────────────────────────────────── */

/** The card above has not proved a server address and access token in this visit. */
export const NOT_CONNECTED =
  'Connect to this server above to pair a phone or see the phones already paired.'

/** The server declined to make a code because its address is not https. No button follows it. */
export const HTTP_ONLY =
  "This server's address begins with http://, so it cannot make a pairing code. Phones pair only " +
  'over https. Everything else here works as it does.'

/* ── Ready ──────────────────────────────────────────────────────────────────────────────────── */

export const READY_LEDE =
  'A paired phone syncs your journal to this server and signs each request with its own key.'

/** The list, when the server has said it holds no phone. Never shown for a list not yet read. */
export const NO_PHONES = 'No phone is paired with this server.'

export const PAIR_A_PHONE = 'Pair a phone'

/* ── A code on screen ───────────────────────────────────────────────────────────────────────── */

export const CODE_LEDE =
  'On the phone, choose Pair with a server. Scan this, or type the server address and the code.'

export const ADDRESS_LABEL = 'Server address'
export const CODE_LABEL = 'Code'
export const WAITING = 'Waiting for a phone to use this code.'
export const DISCARD_CODE = 'Discard this code'

/** The one announcement the countdown makes, politely, at thirty seconds left. */
export const THIRTY_SECONDS_LEFT = 'Thirty seconds left on this code.'

/** Whole seconds as the clock shows them: `1:52`, `0:07`. Never below zero. */
export function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** What follows the clock beside a code: `1:52` + ` left · works once`. */
export const CODE_TIME_LEFT_AFTER = ' left · works once'

/** `1:52 left · works once` */
export function codeTimeLeft(seconds: number): string {
  return `${clock(seconds)}${CODE_TIME_LEFT_AFTER}`
}

/** The code as the person reads it: two groups of five, `K7M4R-D96QX`. */
export function displayCode(code: string): string {
  return `${code.slice(0, 5)}-${code.slice(5)}`
}

/* ── A phone used the code: compare ─────────────────────────────────────────────────────────── */

export const COMPARE_LEDE =
  'A phone used the code. It shows six words. Read these six aloud and check them on the phone, ' +
  'in this order.'

/** The sentence the confirm window's clock stands in, either side of the clock. */
export const CONFIRM_WITHIN_BEFORE = 'Confirm within '
export const CONFIRM_WITHIN_AFTER = '. If you do not, the phone is not paired and nothing is stored.'

/** `Confirm within 1:28. If you do not, the phone is not paired and nothing is stored.` */
export function confirmWithin(seconds: number): string {
  return `${CONFIRM_WITHIN_BEFORE}${clock(seconds)}${CONFIRM_WITHIN_AFTER}`
}

export const PAIR_THIS_PHONE = 'Pair this phone'
export const WORDS_DONT_MATCH = "The words don't match"

/* ── How a pairing ends ─────────────────────────────────────────────────────────────────────── */

export const MISMATCH =
  'This phone was not paired. Nothing was stored. Different words mean the key that reached this ' +
  'server is not the one on the phone. Make a new code when you are ready.'

/** Above the list for the rest of this visit, in solid ink. */
export const PAIRED = 'Paired a phone.'

export const LAPSED = 'This code lapsed after two minutes. No phone used it.'

export const UNCONFIRMED =
  'The time to confirm ran out. The phone was not paired and nothing was stored. The phone will ' +
  'need a new code.'

export const MAKE_A_NEW_CODE = 'Make a new code'
export const BACK_TO_PHONES = 'Back to phones'

/* ── The server did not do what was asked ───────────────────────────────────────────────────── */

export const UNREACHABLE = 'This server could not be reached. Nothing changed.'
export const TRY_AGAIN = 'Try again'

export const REFUSED =
  'This server refused the request. Nothing changed. Connect again above, then try once more.'

/* ── The list ───────────────────────────────────────────────────────────────────────────────── */

/** A row is its six words, no name: `amber · kettle · north · ribbon · salt · tunnel`. */
export function rowWords(words: readonly string[]): string {
  return words.join(' · ')
}

/** `12 March 2026`, in this browser's calendar. */
export function longDate(ms: number): string {
  const d = new Date(ms)
  return `${d.getDate()} ${MONTH_NAMES[d.getMonth()]} ${d.getFullYear()}`
}

/** `Paired 12 March 2026`, or `Paired 12 March 2026 · Disconnected 20 March 2026`. */
export function rowDates(pairedAt: number, disconnectedAt: number | null): string {
  const paired = `Paired ${longDate(pairedAt)}`
  return disconnectedAt === null ? paired : `${paired} · Disconnected ${longDate(disconnectedAt)}`
}

export const DISCONNECT = 'Disconnect'

/** The inline confirm beneath a row, in clay. */
export const DISCONNECT_QUESTION = 'Disconnect this phone?'
export const DISCONNECT_CONSEQUENCE =
  'It is refused from its next sync. Nothing on the phone is erased, and what it already sent stays ' +
  'on this server. To sync again it must pair again.'
export const KEEP_IT_PAIRED = 'Keep it paired'
export const DISCONNECT_THIS_PHONE = 'Disconnect this phone'

/** Above the list for the rest of this visit, in solid ink. */
export const DISCONNECTED = 'Disconnected a phone.'

/* ── Elsewhere on the page ──────────────────────────────────────────────────────────────────── */

/** The owner console's "Server connection" panel: where phones pair, in one line. */
export const CONSOLE_POINTER =
  "Phones pair from the Connect to your sync server card on the owner's page."

/** Directly above the access-token re-issue's confirm button, always visible. */
export const REISSUE_DISCONNECTS =
  'Re-issuing your access token disconnects every paired phone. Each one must pair again before it ' +
  'can sync.'

/**
 * The re-issue line, with the count of connected phones when a proved connection has read it. No
 * count, or none connected, is the count-free line, which is true either way.
 */
export function reissueLine(connectedPhones: number | null): string {
  if (connectedPhones === null || connectedPhones < 1) return REISSUE_DISCONNECTS
  if (connectedPhones === 1) return 'Re-issuing your access token disconnects your 1 paired phone.'
  return `Re-issuing your access token disconnects all ${connectedPhones} paired phones.`
}
