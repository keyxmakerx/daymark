/*
 * The owner's side of a pairing, as a state machine a test can hold.
 *
 * WHY THIS IS A MODULE AND NOT COMPONENT CODE. The property that matters here is an ORDER of
 * effects — a code is generated, a run is opened, the run is persisted, a reply is read, an offer
 * is opened, keys are pinned, and only then is a ticket forwarded — and an order can only be
 * observed by something watching the calls happen. Written inside a Svelte component that order
 * would be provable only by driving a browser; written here, against ports, every transition is a
 * node test. It is the same argument therapist/loginGate.ts makes, for the same reason.
 *
 * THE ONE RULE THE WHOLE SCREEN HANGS ON: a retry after a failed envelope is a HUMAN action, never
 * automatic (plan 4.0a). Nothing in this module opens a second run on its own. `newCode` exists,
 * it costs one of the invitation's eight exchanges, and only a person clicking calls it — because
 * an automatic retry would spend a person's whole guess budget on a bad phone line and turn the
 * bound the PAKE gives into a number nobody was watching.
 *
 * WRONG CODE IS A QUESTION, NOT A VERDICT. The mismatch state carries no diagnosis, because there
 * is none to carry: a typo and a stranger holding the link produce the same null. The screen asks;
 * the person decides between a new code and stopping the invitation.
 */
import type { InviteResponse } from '../sync/portal'
import { newPairingCode, type PairingCode } from './pairingCode'
import type { OwnerPairingState } from './relay'
import type { TherapistOffer } from './payloads'
import type { OfferCheck } from '../owner/therapistKeys'
import type { PinWrite } from '../therapist/pinStore'
import { OWNER_COPY } from './copy'
import {
  forgetOwnerRun,
  loadOwnerRun,
  ownerRunFromState,
  ownerStateFromRun,
  saveOwnerRun,
  type OwnerRunStorage,
} from './ownerRunStore'

/**
 * Mirrors PairingStore.MAX_EXCHANGES_PER_INVITE. Duplicated rather than fetched because it is what
 * the screen counts down from before any request is made; the server refuses past it regardless,
 * and the invitation listing carries the real count, so the two cannot drift into a wrong answer —
 * only into a screen that offers a run the server then declines with the mint-a-fresh-one message.
 */
export const MAX_EXCHANGES_PER_INVITE = 8

/** One invitation as the owner's list route reports it. */
export interface InviteSummary {
  inviteId: string
  status: string
  createdAt: number
  expiresAt: number
  failCount: number
  exchangeCount: number
  latestExchange?: { exchangeId: string; state: string }
}

/** Everything this module reaches outside itself for. See the header for why these are ports. */
export interface OwnerCeremonyPorts {
  mintInvite(): Promise<InviteResponse>
  listInvites(): Promise<InviteSummary[]>
  openRun(inviteId: string, code: PairingCode): Promise<OwnerPairingState>
  readRun(run: OwnerPairingState): Promise<
    | { state: 'waiting' }
    | { state: 'cancelled' }
    | { state: 'superseded' }
    | { state: 'complete'; isk: Uint8Array; offer: TherapistOffer | null }
  >
  approveRun(exchangeId: string, enrolTicketB64: string): Promise<void>
  cancelRun(exchangeId: string): Promise<boolean>
  reportInvite(inviteId: string): Promise<void>
  /**
   * Read the offer's keys against this console's record. WRITES NOTHING — the screen has to be
   * able to say what approving would do before anybody approves, and a check that recorded as it
   * looked would have made the telling pointless.
   */
  inspectOffer(offer: TherapistOffer): OfferCheck
  /** Record the offer's keys. Insert-only: a supersession is a new row beside the old, never over. */
  pinOffer(offer: TherapistOffer): PinWrite | 'unreadable'
  /** A fresh code. A port so a test can pin one; the real one is newPairingCode. */
  freshCode(): Promise<PairingCode>
  /**
   * The owner's own name for this relationship, for the sentences that name a person.
   *
   * NOT the offer's `displayName`, ever. That one is typed by whoever answered, so using it in
   * "replace the keys held for X" would let the party being checked choose the words of the check.
   */
  displayName: string
  storage: OwnerRunStorage | null
  now(): number
}

export const defaultFreshCode = (): Promise<PairingCode> => newPairingCode()

/**
 * Where a pairing stands, from the owner's side.
 *
 * `code` is present ONLY while a run is waiting and only in this tab's memory: it is never
 * persisted (ownerRunStore.ts has no field for it) and it is dropped the moment a reply arrives,
 * because after that it can do nothing except be read by someone standing behind the screen.
 */
export type OwnerCeremony =
  /** No invitation yet. */
  | { phase: 'idle' }
  /** An invitation exists; no pairing run is open for it. */
  | { phase: 'invited'; invite: InviteResponse; attemptsLeft: number; failCount: number }
  /** A run is open and the code is on screen. */
  | { phase: 'waiting'; invite: InviteResponse; code: PairingCode; run: OwnerPairingState; attemptsLeft: number; failCount: number }
  /** A run was restored after a reload: it can still be finished, but the code cannot be shown again. */
  | { phase: 'resumed'; invite: InviteResponse; run: OwnerPairingState; attemptsLeft: number; failCount: number }
  /** Someone answered, and what they sealed did not open. No diagnosis exists; the person decides. */
  | { phase: 'mismatch'; invite: InviteResponse; run: OwnerPairingState; attemptsLeft: number; failCount: number }
  /**
   * The offer opened. This is the moment the read-aloud used to be.
   *
   * `keys` is what approving WOULD do to the record, worked out before anything is written, so the
   * screen can say it first. A 'supersedes' here is an approvable state with its own words, not a
   * refusal — see owner/therapistKeys.ts, checkOffer, for why the code is authority enough.
   */
  | { phase: 'answered'; invite: InviteResponse; run: OwnerPairingState; offer: TherapistOffer; keys: OfferCheck; failCount: number }
  /** Approved; the ticket is with the server and the therapist has still to finish. */
  | { phase: 'approved'; invite: InviteResponse; run: OwnerPairingState; offer: TherapistOffer; pin: PinWrite; replaced: boolean }
  /** The owner cancelled, or opened a newer run, or the invitation died. Terminal for this run. */
  | { phase: 'ended'; invite: InviteResponse; reason: 'cancelled' | 'superseded' | 'expired' | 'reported' | 'capped' }

export class CeremonyError extends Error {}

const attemptsLeftOf = (used: number): number => Math.max(0, MAX_EXCHANGES_PER_INVITE - used)

/** The invitation this relationship is currently working with, if any: newest that is still alive. */
function liveInvite(invites: InviteSummary[]): InviteSummary | null {
  return invites.find((i) => i.status === 'PENDING' || i.status === 'REDEEMING') ?? null
}

/**
 * Mint an invitation. Deliberately does NOT open a run or make a code: the link goes by email and
 * the code by something else, and there is no reason to have made one until the owner is ready to
 * say it. A code sitting on a screen nobody is reading is a code on a screen.
 */
export async function startInvitation(ports: OwnerCeremonyPorts): Promise<OwnerCeremony> {
  const invite = await ports.mintInvite()
  return { phase: 'invited', invite, attemptsLeft: MAX_EXCHANGES_PER_INVITE, failCount: 0 }
}

/**
 * Open a run: a fresh code, the CPace opening message, and the owner's half persisted before the
 * screen shows anything. Persisted FIRST-ish — after the server has an exchange id, which is the
 * earliest moment there is a run to persist — so a reload between the request and the render
 * still finds a finishable run rather than a scalar nobody can name.
 */
export async function openRun(ports: OwnerCeremonyPorts, state: OwnerCeremony): Promise<OwnerCeremony> {
  if (
    state.phase !== 'invited' &&
    state.phase !== 'waiting' &&
    state.phase !== 'resumed' &&
    state.phase !== 'mismatch'
  ) {
    throw new CeremonyError('there is nothing to open a pairing run for')
  }
  const { invite, failCount, attemptsLeft } = state
  const code = await ports.freshCode()
  const run = await ports.openRun(invite.inviteId, code)
  saveOwnerRun(ports.storage, ownerRunFromState(run.inviteId, run, ports.now()))
  return {
    phase: 'waiting',
    invite,
    code,
    run,
    attemptsLeft: attemptsLeftOf(MAX_EXCHANGES_PER_INVITE - attemptsLeft + 1),
    failCount,
  }
}

/**
 * Look for a reply. The only place an offer is opened, and the only place a mismatch is named.
 *
 * The failure of the envelope is NOT an error here: it returns the mismatch phase, and the screen
 * renders a question. An error would be this module claiming to know which of a typo and a stranger
 * it was looking at, which is precisely the thing the design refuses to guess.
 */
export async function checkForReply(ports: OwnerCeremonyPorts, state: OwnerCeremony): Promise<OwnerCeremony> {
  if (state.phase !== 'waiting' && state.phase !== 'resumed' && state.phase !== 'mismatch') {
    throw new CeremonyError('there is no open pairing run to check')
  }
  const { invite, run, attemptsLeft, failCount } = state
  const read = await ports.readRun(run)
  if (read.state === 'waiting') return state
  if (read.state === 'cancelled') {
    forgetOwnerRun(ports.storage)
    return { phase: 'ended', invite, reason: 'cancelled' }
  }
  if (read.state === 'superseded') {
    forgetOwnerRun(ports.storage)
    return { phase: 'ended', invite, reason: 'superseded' }
  }
  // The run is answered either way; keep the persisted copy so the pin it carries survives a reload.
  saveOwnerRun(ports.storage, ownerRunFromState(run.inviteId, run, ports.now()))
  if (!read.offer) return { phase: 'mismatch', invite, run, attemptsLeft, failCount }
  // Read the keys against the record HERE, where nothing has been written and nothing forwarded, so
  // the answered screen can state what approving would do before it is an option.
  return { phase: 'answered', invite, run, offer: read.offer, keys: ports.inspectOffer(read.offer), failCount }
}

/**
 * Approve: record the keys, then forward the ticket. In that order, and the order is the point.
 *
 * Recording is local and reversible by the owner; forwarding the ticket is neither, because it puts
 * the invitation into REDEEMING and lets a person enrol. If the record refuses, nothing is forwarded
 * and the ceremony stays where it was. The reverse order would have approved an enrolment the
 * console then declined to record keys for.
 *
 * KEYS THAT DIFFER FROM THE ONES ON FILE ARE APPROVABLE (issue #111), which is the change from the
 * version of this function that refused them. The argument is owner/therapistKeys.ts's, in
 * checkOffer's header, and rests on two facts that are not this module's to assume: that the old
 * invitation is spent by the time a second one can be answered (the server's PENDING gate), and
 * that replacing a key reaches nothing already sealed. The screen states the second one before the
 * button; this function's part is to refuse the one case that IS ambiguous — keys already recorded
 * for a different person — and to leave everything else to the person who typed the code.
 */
export async function approve(ports: OwnerCeremonyPorts, state: OwnerCeremony): Promise<OwnerCeremony> {
  if (state.phase !== 'answered') throw new CeremonyError('there is no answered pairing run to approve')
  if (state.keys.kind === 'other-person') {
    throw new CeremonyError(OWNER_COPY.sameKeysAsOther(ports.displayName, state.keys.displayName))
  }
  if (state.keys.kind === 'unreadable') {
    throw new CeremonyError('The keys in that reply could not be read. Nothing was approved; give them a new code.')
  }
  const pin = ports.pinOffer(state.offer)
  if (pin === 'unreadable') {
    throw new CeremonyError('The keys in that reply could not be read. Nothing was approved; give them a new code.')
  }
  await ports.approveRun(state.run.exchangeId, state.offer.enrolTicketB64)
  forgetOwnerRun(ports.storage)
  // `replaced` comes from the check made BEFORE the write, not from what the write returned: a
  // clinician who re-keyed completely is a brand-new identity to the pin record ('pinned-now')
  // while being, to the owner, the same person whose keys have just changed.
  return {
    phase: 'approved',
    invite: state.invite,
    run: state.run,
    offer: state.offer,
    pin,
    replaced: state.keys.kind === 'supersedes',
  }
}

/**
 * New code: retire this run and open a fresh one. A person's click, always — see the header.
 * Cancelling first matters: the store allows one open run per invitation and would retire this one
 * anyway, but an explicit cancel is what puts the owner's `pairing.cancelled` line in their own log.
 */
export async function newCode(ports: OwnerCeremonyPorts, state: OwnerCeremony): Promise<OwnerCeremony> {
  if (state.phase !== 'waiting' && state.phase !== 'resumed' && state.phase !== 'mismatch') {
    throw new CeremonyError('there is no pairing run to replace')
  }
  await ports.cancelRun(state.run.exchangeId)
  forgetOwnerRun(ports.storage)
  return openRun(ports, { phase: 'invited', invite: state.invite, attemptsLeft: state.attemptsLeft, failCount: state.failCount })
}

/**
 * Take back an approval nobody finished. The invitation returns to PENDING and its ticket dies, so
 * the owner can start over with a new code instead of being stuck between open and enrolled.
 */
export async function abandonApproval(ports: OwnerCeremonyPorts, state: OwnerCeremony): Promise<OwnerCeremony> {
  if (state.phase !== 'approved') throw new CeremonyError('there is no approval to take back')
  await ports.cancelRun(state.run.exchangeId)
  forgetOwnerRun(ports.storage)
  const invites = await ports.listInvites()
  const live = invites.find((i) => i.inviteId === state.invite.inviteId)
  return {
    phase: 'invited',
    invite: state.invite,
    attemptsLeft: attemptsLeftOf(live?.exchangeCount ?? 0),
    failCount: live?.failCount ?? 0,
  }
}

/** End the invitation. The one act that kills one; a wrong code never does (plan §3.9.1). */
export async function stopInvitation(ports: OwnerCeremonyPorts, state: OwnerCeremony): Promise<OwnerCeremony> {
  if (state.phase === 'idle') throw new CeremonyError('there is no invitation to stop')
  await ports.reportInvite(state.invite.inviteId)
  forgetOwnerRun(ports.storage)
  return { phase: 'ended', invite: state.invite, reason: 'reported' }
}

/**
 * Where this relationship stands, on opening the console — from the server's list and whatever run
 * this tab still holds.
 *
 * A RESTORED RUN CANNOT SHOW ITS CODE, and says so rather than inventing one. The code was never
 * persisted, by design; what survives is enough to finish the run (the scalar) and nothing else. So
 * a reloaded owner can still collect a reply and approve, and if they need to say the code again
 * their only honest option is a new one.
 */
export async function restore(ports: OwnerCeremonyPorts): Promise<OwnerCeremony> {
  const invites = await ports.listInvites()
  const live = liveInvite(invites)
  if (!live) {
    forgetOwnerRun(ports.storage)
    return { phase: 'idle' }
  }
  const invite: InviteResponse = { inviteId: live.inviteId, link: '', expiresAt: live.expiresAt }
  const attemptsLeft = attemptsLeftOf(live.exchangeCount)
  const base = { invite, attemptsLeft, failCount: live.failCount }
  const record = loadOwnerRun(ports.storage)
  const latest = live.latestExchange
  // A stored run that is not this invitation's newest is somebody else's tab, or an older run:
  // either way it is not what this screen is about, and keeping it would show a stale code.
  if (!record || !latest || record.exchangeId !== latest.exchangeId || record.inviteId !== live.inviteId) {
    if (record) forgetOwnerRun(ports.storage)
    if (latest && latest.state === 'CLOSED' && live.status === 'REDEEMING') {
      // Approved by this owner in another tab or before a reload; the therapist has still to finish.
      // No offer to show — it was in a run this tab no longer holds — so the screen offers only the
      // honest options: wait, or take the approval back.
      return { phase: 'ended', invite, reason: 'superseded' }
    }
    return { phase: 'invited', ...base }
  }
  const run = ownerStateFromRun(record)
  if (latest.state === 'CANCELLED' || latest.state === 'SUPERSEDED') {
    forgetOwnerRun(ports.storage)
    return { phase: 'ended', invite, reason: latest.state === 'CANCELLED' ? 'cancelled' : 'superseded' }
  }
  return { phase: 'resumed', invite, run, attemptsLeft, failCount: live.failCount }
}
