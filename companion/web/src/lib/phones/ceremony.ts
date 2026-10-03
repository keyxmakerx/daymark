/*
 * PAIRING A PHONE, AND THE PHONES ALREADY PAIRED: the owner's side, as a state machine a test can
 * hold (#431; the server's half is routes/DeviceRoutes.kt).
 *
 * WHY THIS IS A MODULE AND NOT COMPONENT CODE. What matters here is an order of effects, and which
 * effects happen at all: a code is minted, its state is read until a phone redeems it, the six words
 * of the key that redeemed it are shown, and only the person's click confirms that key, by the id
 * of the key whose words were shown. "The words don't match", "Discard this code", "Keep it paired"
 * and "Back to phones" call nothing. Written in a Svelte component, none of that could be seen
 * without driving a browser; written here against ports, each is a node test
 * (pairing/ownerCeremony.ts makes the same argument for the clinician's pairing).
 *
 * WHAT THE WEB DOES NOT TRUST. The server relays a key and vouches for nothing about it. So the key
 * id the confirmation names is worked out here from the public key whose words are shown, and a
 * server that answers with one key's words and another key's id is refused before anything is
 * confirmed. A minted address that is not https is treated as the server's own answer that it cannot
 * pair, because the phone declines one, and a code that is not ten symbols with a right check symbol
 * is not shown, because the phone would decline that too.
 *
 * THE TWO CLOCKS. Every time the server gives is on its clock. The difference between its clock and
 * this browser's is read once, from the code it mints (a code lives exactly CODE_TTL_MS), and every
 * deadline on screen is the server's deadline moved onto this browser's clock by it. A deadline
 * reached here moves nothing by itself except the confirm window, whose end the server has already
 * fixed: a code that lapses is reported lapsed only when the server says so.
 *
 * PURE apart from its ports. No fetch, no DOM, no timers: the component owns the clock and calls
 * [poll] about every POLL_EVERY_MS while a code is shown, and [expire] once a second.
 */
import { PhonesFault, type DeviceRecord, type DevicesApi } from './devices'
import { isPublicKeyB64 } from './words'
import * as COPY from './copy'

/** How long a code can be used, and how long a used one can be confirmed: DeviceKeyStore's two minutes. */
export const CODE_TTL_MS = 120_000
export const CONFIRM_WINDOW_MS = 120_000

/** How often a code on screen is asked after. */
export const POLL_EVERY_MS = 2_000

/** The phone announces nothing; the page announces once, at this many seconds left on a code. */
export const ANNOUNCE_AT_SECONDS = 30

/** Everything this module reaches outside itself for. */
export interface PhonesPorts {
  api: DevicesApi
  /** The six words of a key (words.ts). */
  words(publicKeyB64: string): Promise<string[]>
  /** A key's id, worked out from the key; null for text that is not one (words.ts). */
  keyIdOf(publicKeyB64: string): Promise<string | null>
  /** This browser's clock, in milliseconds. */
  now(): number
}

/** One phone as the list shows it: its words and dates, and the id Disconnect names. No name. */
export interface PhoneRow {
  keyId: string
  words: string[]
  pairedAt: number
  disconnectedAt: number | null
}

/** A code on screen. [deadline] is on this browser's clock. */
export interface ShownCode {
  step: 'waiting'
  codeId: string
  code: string
  address: string
  qrText: string
  deadline: number
}

/** A phone used the code: its key's words, and until when this browser may confirm it. */
export interface Comparing {
  step: 'compare'
  codeId: string
  keyId: string
  words: string[]
  deadline: number
}

/** What "Try again" does again. */
export type Retry =
  | { act: 'list' }
  | { act: 'mint' }
  | { act: 'poll'; code: ShownCode }
  | { act: 'confirm'; compare: Comparing }
  | { act: 'disconnect'; keyId: string }

/** The pairing area, below where "Pair a phone" stands. */
export type Pairing =
  | { step: 'closed' }
  | ShownCode
  | Comparing
  | { step: 'mismatch' }
  | { step: 'lapsed' }
  | { step: 'unconfirmed' }
  | { step: 'unreachable'; retry: Retry }
  | { step: 'refused' }
  | { step: 'unsupported' }

export interface PhonesState {
  /** Whether the card above has proved an address and token in this visit. */
  connected: boolean
  /** The server declined to mint because its address is not https: no button, for this connection. */
  httpOnly: boolean
  /** The phones, as last read; null until the server has said, so no gap reads as "no phones". */
  list: PhoneRow[] | null
  /** The one sentence above the list, for the rest of this visit. */
  notice: 'paired' | 'disconnected' | null
  pairing: Pairing
  /** The row whose inline Disconnect confirm is open. */
  confirming: string | null
  /** The server's clock minus this browser's, from the last code minted. */
  offset: number
}

export function initialState(connected: boolean): PhonesState {
  return { connected, httpOnly: false, list: null, notice: null, pairing: { step: 'closed' }, confirming: null, offset: 0 }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The QR code's text, and the code's own check.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The text of the QR code, which the phone reads (docs/SYNC_PROTOCOL.md): the https address and the
 * ten symbols, and nothing else. Both values are spelled by encodeURIComponent exactly: the phone
 * declines any other spelling, and URLSearchParams spells a space and `! ' ( ) ~` differently.
 */
export function pairingQrText(address: string, code: string): string {
  return `daymark-pair:v1?server=${encodeURIComponent(address)}&code=${encodeURIComponent(code)}`
}

/** The symbols of a pairing code, as the server's PairingCode.kt and the phone read them. */
export const CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTUVWXYZ'

/**
 * Whether [code] is a pairing code the phone will take: ten symbols of the alphabet, the last the
 * sum of position × value over the first nine, mod 31.
 */
export function isPairingCode(code: string): boolean {
  if (code.length !== 10) return false
  let sum = 0
  for (let i = 0; i < 9; i++) {
    const value = CODE_ALPHABET.indexOf(code[i]!)
    if (value < 0) return false
    sum = (sum + (i + 1) * value) % CODE_ALPHABET.length
  }
  return code[9] === CODE_ALPHABET[sum]
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The list.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** Connected first, newest first; then disconnected, the latest disconnected first. */
export function sortRows(rows: readonly PhoneRow[]): PhoneRow[] {
  const connected = rows.filter((r) => r.disconnectedAt === null).sort((a, b) => b.pairedAt - a.pairedAt)
  const gone = rows
    .filter((r) => r.disconnectedAt !== null)
    .sort((a, b) => b.disconnectedAt! - a.disconnectedAt! || b.pairedAt - a.pairedAt)
  return [...connected, ...gone]
}

/**
 * The list's rows, each made from its key: the words, and an id that must be the key's own. Null when
 * a row's key is not one, or the server's id for it is another key's, since Disconnect would then
 * name a phone other than the one whose words are shown.
 */
async function rowsOf(ports: PhonesPorts, records: readonly DeviceRecord[]): Promise<PhoneRow[] | null> {
  const rows: PhoneRow[] = []
  for (const r of records) {
    if (!isPublicKeyB64(r.publicKey)) return null
    if ((await ports.keyIdOf(r.publicKey)) !== r.keyId) return null
    rows.push({ keyId: r.keyId, words: await ports.words(r.publicKey), pairedAt: r.pairedAt, disconnectedAt: r.revokedAt })
  }
  return sortRows(rows)
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   Transitions.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

const CLOSED: Pairing = { step: 'closed' }

/** What a call that did not succeed leaves on screen. A `gone` is the caller's to read first. */
function failed(state: PhonesState, e: unknown, retry: Retry): PhonesState {
  const kind = e instanceof PhonesFault ? e.kind : 'unreachable'
  if (kind === 'http') return { ...state, httpOnly: true, pairing: CLOSED }
  if (kind === 'unsupported') return { ...state, pairing: { step: 'unsupported' } }
  if (kind === 'refused' || kind === 'gone') return { ...state, pairing: { step: 'refused' } }
  return { ...state, pairing: { step: 'unreachable', retry } }
}

/** Read the list of phones. */
export async function loadList(ports: PhonesPorts, state: PhonesState): Promise<PhonesState> {
  if (!state.connected) return state
  try {
    const rows = await rowsOf(ports, await ports.api.listDevices())
    if (!rows) return { ...state, pairing: { step: 'refused' } }
    return { ...state, list: rows }
  } catch (e) {
    return failed(state, e, { act: 'list' })
  }
}

/** "Pair a phone", and "Make a new code": mint a code and show it. */
export async function mint(ports: PhonesPorts, state: PhonesState): Promise<PhonesState> {
  if (!state.connected || state.httpOnly) return state
  try {
    const minted = await ports.api.mintCode()
    // The phone pairs only over https and declines any other address, so a code for one is no code.
    if (!/^https:\/\//i.test(minted.baseUrl)) return { ...state, httpOnly: true, pairing: CLOSED }
    if (!isPairingCode(minted.code)) return { ...state, pairing: { step: 'refused' } }
    const offset = minted.expiresAt - CODE_TTL_MS - ports.now()
    return {
      ...state,
      confirming: null,
      offset,
      pairing: {
        step: 'waiting',
        codeId: minted.codeId,
        code: minted.code,
        address: minted.baseUrl,
        qrText: pairingQrText(minted.baseUrl, minted.code),
        deadline: minted.expiresAt - offset,
      },
    }
  } catch (e) {
    return failed(state, e, { act: 'mint' })
  }
}

/**
 * Ask where the code on screen stands: one request per call. Still waiting changes nothing; used by
 * a phone shows its key's words; gone means it lapsed unused.
 */
export async function poll(ports: PhonesPorts, state: PhonesState): Promise<PhonesState> {
  if (state.pairing.step !== 'waiting') return state
  const code = state.pairing
  try {
    const view = await ports.api.codeState(code.codeId)
    if (view.state === 'waiting') return state
    if (view.state === 'registered') {
      // Confirmed from another page with this token: paired all the same. Its words are the list's.
      const next: PhonesState = { ...state, notice: 'paired', pairing: CLOSED }
      return loadList(ports, next)
    }
    if (!isPublicKeyB64(view.publicKey)) return { ...state, pairing: { step: 'refused' } }
    const keyId = await ports.keyIdOf(view.publicKey)
    if (keyId === null || keyId !== view.keyId) return { ...state, pairing: { step: 'refused' } }
    const words = await ports.words(view.publicKey)
    return {
      ...state,
      pairing: { step: 'compare', codeId: code.codeId, keyId, words, deadline: view.confirmBy - state.offset },
    }
  } catch (e) {
    if (e instanceof PhonesFault && e.kind === 'gone') return { ...state, pairing: { step: 'lapsed' } }
    return failed(state, e, { act: 'poll', code })
  }
}

/** "Discard this code": back to the phones. Asks nothing and calls nothing; the code lapses. */
export function discard(state: PhonesState): PhonesState {
  return state.pairing.step === 'waiting' ? { ...state, pairing: CLOSED } : state
}

/**
 * "The words don't match". CALLS NOTHING: there is no route that refuses a key, and none is needed.
 * The key that redeemed the code authenticates nothing until it is confirmed, so stopping here and
 * letting its confirm window pass leaves nothing stored anywhere.
 */
export function wordsDontMatch(state: PhonesState): PhonesState {
  return state.pairing.step === 'compare' ? { ...state, pairing: { step: 'mismatch' } } : state
}

/** "Back to phones", from any sentence that ended a pairing. Calls nothing. */
export function back(state: PhonesState): PhonesState {
  const step = state.pairing.step
  return step === 'waiting' || step === 'compare' || step === 'closed' ? state : { ...state, pairing: CLOSED }
}

/** The confirm window ended on this browser's clock: the phone was not paired. Calls nothing. */
export function expire(state: PhonesState, now: number): PhonesState {
  if (state.pairing.step === 'compare' && now >= state.pairing.deadline) return { ...state, pairing: { step: 'unconfirmed' } }
  return state
}

/**
 * "Pair this phone": confirm the key whose words are on screen, by the id worked out from that key.
 * The new phone goes to the top of the list, and "Paired a phone." above it.
 */
export async function pairThisPhone(ports: PhonesPorts, state: PhonesState): Promise<PhonesState> {
  if (state.pairing.step !== 'compare') return state
  const compare = state.pairing
  try {
    const registered = await ports.api.confirm(compare.codeId, compare.keyId)
    if (registered.keyId !== compare.keyId) return { ...state, pairing: { step: 'refused' } }
    const row: PhoneRow = { keyId: compare.keyId, words: compare.words, pairedAt: registered.pairedAt, disconnectedAt: null }
    const next: PhonesState = {
      ...state,
      notice: 'paired',
      pairing: CLOSED,
      list: state.list === null ? null : sortRows([row, ...state.list.filter((r) => r.keyId !== row.keyId)]),
    }
    // A list never read is read now, rather than shown as this one phone alone.
    return next.list === null ? loadList(ports, next) : next
  } catch (e) {
    if (e instanceof PhonesFault && e.kind === 'gone') return { ...state, pairing: { step: 'unconfirmed' } }
    return failed(state, e, { act: 'confirm', compare })
  }
}

/** "Disconnect" on a row: open its confirm. Calls nothing. */
export function askDisconnect(state: PhonesState, keyId: string): PhonesState {
  const row = state.list?.find((r) => r.keyId === keyId)
  return row && row.disconnectedAt === null ? { ...state, confirming: keyId } : state
}

/** "Keep it paired": close the confirm. Calls nothing. */
export function keepPaired(state: PhonesState): PhonesState {
  return { ...state, confirming: null }
}

/** "Disconnect this phone": the row moves to the disconnected phones, and "Disconnected a phone." */
export async function disconnect(ports: PhonesPorts, state: PhonesState, keyId: string): Promise<PhonesState> {
  const closed = { ...state, confirming: null }
  try {
    await ports.api.revoke(keyId)
    const at = ports.now()
    const list = state.list === null ? null : sortRows(state.list.map((r) => (r.keyId === keyId && r.disconnectedAt === null ? { ...r, disconnectedAt: at } : r)))
    return { ...closed, list, notice: 'disconnected' }
  } catch (e) {
    return failed(closed, e, { act: 'disconnect', keyId })
  }
}

/** "Try again": what could not reach the server, once more. */
export async function tryAgain(ports: PhonesPorts, state: PhonesState): Promise<PhonesState> {
  if (state.pairing.step !== 'unreachable') return state
  const retry = state.pairing.retry
  const closed = { ...state, pairing: CLOSED }
  switch (retry.act) {
    case 'list':
      return loadList(ports, closed)
    case 'mint':
      return mint(ports, closed)
    case 'poll':
      return poll(ports, { ...state, pairing: retry.code })
    case 'confirm':
      return pairThisPhone(ports, { ...state, pairing: retry.compare })
    case 'disconnect':
      return disconnect(ports, closed, retry.keyId)
  }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   What the section shows, for one state at one moment. Every word comes from copy.ts.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export type ActionId = 'new-code' | 'back' | 'try-again'

export interface ActionView {
  id: ActionId
  label: string
}

/** A sentence with a clock in it, so the clock can sit in a fixed-width slot. */
export interface ClockLine {
  before: string
  clock: string
  after: string
}

export type AreaView =
  | {
      kind: 'code'
      lede: string
      addressLabel: string
      address: string
      codeLabel: string
      code: string
      qrText: string
      timeLeft: ClockLine
      waiting: string
      discard: string
    }
  | { kind: 'compare'; lede: string; words: string[]; within: ClockLine; pair: string; mismatch: string }
  | { kind: 'ended'; sentence: string; actions: ActionView[] }

export interface RowView {
  keyId: string
  words: string
  dates: string
  connected: boolean
  /** The inline confirm beneath this row, when it is open. */
  confirm: { question: string; consequence: string; keep: string; disconnect: string } | null
}

export interface PhonesView {
  heading: string
  lede: string
  /** "Paired a phone." or "Disconnected a phone.", above the list. */
  notice: string | null
  /** Null when there is no list to show: not connected, or not read yet. */
  rows: RowView[] | null
  /** The sentence standing for an empty list the server has read out. */
  empty: string | null
  /** Whether "Pair a phone" is offered. */
  pairButton: string | null
  area: AreaView | null
  /** The polite live region's text: empty, then the thirty-second line once. */
  announcement: string
}

/** Whole seconds left before [deadline], rounded up, so a fresh code reads 2:00. */
export function secondsLeft(deadline: number, now: number): number {
  return Math.max(0, Math.ceil((deadline - now) / 1000))
}

const NEW_CODE: ActionView = { id: 'new-code', label: COPY.MAKE_A_NEW_CODE }
const BACK: ActionView = { id: 'back', label: COPY.BACK_TO_PHONES }
const TRY_AGAIN: ActionView = { id: 'try-again', label: COPY.TRY_AGAIN }

function areaOf(pairing: Pairing, now: number): AreaView | null {
  switch (pairing.step) {
    case 'closed':
      return null
    case 'waiting': {
      const left = secondsLeft(pairing.deadline, now)
      return {
        kind: 'code',
        lede: COPY.CODE_LEDE,
        addressLabel: COPY.ADDRESS_LABEL,
        address: pairing.address,
        codeLabel: COPY.CODE_LABEL,
        code: COPY.displayCode(pairing.code),
        qrText: pairing.qrText,
        timeLeft: { before: '', clock: COPY.clock(left), after: COPY.CODE_TIME_LEFT_AFTER },
        waiting: COPY.WAITING,
        discard: COPY.DISCARD_CODE,
      }
    }
    case 'compare': {
      const left = secondsLeft(pairing.deadline, now)
      return {
        kind: 'compare',
        lede: COPY.COMPARE_LEDE,
        words: pairing.words,
        within: { before: COPY.CONFIRM_WITHIN_BEFORE, clock: COPY.clock(left), after: COPY.CONFIRM_WITHIN_AFTER },
        pair: COPY.PAIR_THIS_PHONE,
        mismatch: COPY.WORDS_DONT_MATCH,
      }
    }
    case 'mismatch':
      return { kind: 'ended', sentence: COPY.MISMATCH, actions: [NEW_CODE, BACK] }
    case 'lapsed':
      return { kind: 'ended', sentence: COPY.LAPSED, actions: [NEW_CODE, BACK] }
    case 'unconfirmed':
      return { kind: 'ended', sentence: COPY.UNCONFIRMED, actions: [NEW_CODE, BACK] }
    case 'unreachable': {
      const sent = pairing.retry.act === 'confirm' || pairing.retry.act === 'disconnect'
      return { kind: 'ended', sentence: sent ? COPY.UNREACHABLE_AFTER_SEND : COPY.UNREACHABLE, actions: [TRY_AGAIN, BACK] }
    }
    case 'refused':
      return { kind: 'ended', sentence: COPY.REFUSED, actions: [BACK] }
    case 'unsupported':
      return { kind: 'ended', sentence: COPY.CANNOT_PAIR_PHONES, actions: [] }
  }
}

function rowView(row: PhoneRow, confirming: string | null): RowView {
  const open = confirming === row.keyId && row.disconnectedAt === null
  return {
    keyId: row.keyId,
    words: COPY.rowWords(row.words),
    dates: COPY.rowDates(row.pairedAt, row.disconnectedAt),
    connected: row.disconnectedAt === null,
    confirm: open
      ? {
          question: COPY.DISCONNECT_QUESTION,
          consequence: COPY.DISCONNECT_CONSEQUENCE,
          keep: COPY.KEEP_IT_PAIRED,
          disconnect: COPY.DISCONNECT_THIS_PHONE,
        }
      : null,
  }
}

/** The section, for [state] at [now] on this browser's clock. */
export function phonesView(state: PhonesState, now: number): PhonesView {
  const base = { heading: COPY.PHONES_HEADING, announcement: '' }
  if (!state.connected) {
    return { ...base, lede: COPY.NOT_CONNECTED, notice: null, rows: null, empty: null, pairButton: null, area: null }
  }
  const rows = state.list === null ? null : state.list.map((r) => rowView(r, state.confirming))
  const notice = state.notice === 'paired' ? COPY.PAIRED : state.notice === 'disconnected' ? COPY.DISCONNECTED : null
  const area = areaOf(state.pairing, now)
  const announcement =
    state.pairing.step === 'waiting' && secondsLeft(state.pairing.deadline, now) <= ANNOUNCE_AT_SECONDS
      ? COPY.THIRTY_SECONDS_LEFT
      : ''
  return {
    ...base,
    lede: state.httpOnly ? COPY.HTTP_ONLY : COPY.READY_LEDE,
    notice,
    rows,
    empty: rows !== null && rows.length === 0 ? COPY.NO_PHONES : null,
    pairButton: !state.httpOnly && state.pairing.step === 'closed' ? COPY.PAIR_A_PHONE : null,
    area,
    announcement,
  }
}
