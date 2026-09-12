/*
 * The therapist's side of a pairing: one code and one passphrase (plan §4.0a).
 *
 * WHAT REPLACED WHAT. The old ceremony started by redeeming the invitation secret, which the link
 * carries — so whoever read the email got an enrolment ticket, and the code in the design document
 * secured nothing. Here the invitation secret only opens a conversation: it fetches the owner's
 * CPace message and posts a reply. What travels back with that reply is an OFFER, sealed under a
 * key that exists only if the therapist typed the same short code the owner spoke — this browser's
 * two public keys, a name for the owner to see, and an enrolment ticket THIS BROWSER chose. The
 * owner's device opens it or cannot; if it opens, they approve, and the server begins honouring
 * that ticket. A link-holder who never learned the code seals an offer nobody can open and is
 * never approved.
 *
 * THE ORDER, AND WHY EACH STEP IS WHERE IT IS:
 *
 *   1. Parse the code HERE, before anything is sent. A mistyped character is caught by the check
 *      symbol on this device (pairing/pairingCode.ts) and costs nothing; only a well-formed code
 *      that is nevertheless wrong reaches the wire, which is the case the PAKE is for.
 *   2. Fetch, which is what tells this browser the relationship reference.
 *   3. ONLY THEN generate keys. The relRef is the key this browser files records under, so it is
 *      the first moment "do I already hold keys for this relationship?" can be asked — and asking
 *      it late would mean minting a second set of keys for someone the owner has already pinned,
 *      which their console would rightly refuse as a substitution. The refusal here is the same
 *      insert-only rule beginAcceptance has, at the same point in the ceremony's knowledge.
 *   4. Wrap under the passphrase and prove the wrap reopens, then write the record — all before
 *      the reply is posted, so the keys the owner is about to see are keys this browser can still
 *      open tomorrow. A record written for a run that then fails is removed (see `forget`).
 *   5. Respond: MSGb and the sealed offer, together, once.
 *   6. Wait for the owner. Polling, at the cadence the shared rate budget allows.
 *   7. Take the OWNER'S keys out of the envelope their approval carried and write them into this
 *      relationship's record — before enrolling, so a clinician is never enrolled into a
 *      relationship whose owner they cannot verify (issue #101).
 *   8. Enrol with the ticket from the offer, then sign in and register the public keys — the same
 *      steps as before, through the same code (inviteAccept.ts's enrolWithTicket and
 *      completeAcceptance), because from a ticket onwards the two ceremonies are one.
 *
 * WHY STEP 7 EXISTS. The pairing used to prove keys in one direction only. The owner learned the
 * clinician's keys from an envelope no one without the code could have sealed; the clinician
 * learned the owner's by typing base64 into the sign-in form, which is worth whatever the channel
 * that string travelled on was worth — and the clinical content flows along that direction. The
 * owner's approval now carries their own two public keys sealed under the same run's key in the
 * other direction, and this module is where they are opened and written down.
 *
 * NOTHING HERE RETRIES BY ITSELF. A run the owner cancelled, a run superseded by a fresh code, an
 * invitation that died: all read as `gone`, and the answer is to ask the person for a new code. An
 * automatic retry would spend the invitation's eight exchanges on a bad afternoon.
 */
import {
  AcceptError,
  CREDENTIAL_ID_BYTES,
  enrolWithTicket,
  findKeyRecord,
  forgetKeyRecord,
  pinOwnerKeysOnRecord,
  saveKeyRecord,
  sameKeys,
  type AcceptancePorts,
  type Enrolment,
  type KeyRecord,
  type OwnerPublicKeysB64,
} from './inviteAccept'
import { zeroize, type TherapistKeys, type WrappedKeyBlob } from './keyStore'
import {
  PAIRING_STATUS_POLL_MS,
  ownerKeysFromEnvelope,
  therapistAnswerPairing,
  therapistPairingStatus,
  type TherapistRunKeying,
  type TherapistStatusResult,
} from '../pairing/relay'
import { newEnrolTicketB64, type TherapistOffer } from '../pairing/payloads'
import { DISPLAY_NAME_MAX_CODEPOINTS, validDisplayName } from '../pairing/payloads'
import { parsePairingCode, PAIRING_FAULT_TEXT, type CanonicalPairingCode } from '../pairing/pairingCode'

/** Versioned, and in sessionStorage: a run belongs to the tab that opened it. */
export const THERAPIST_RUN_STORAGE_KEY = 'daymark.pairing.therapist-run.v1'

/**
 * What this browser keeps between answering and the owner approving.
 *
 * NOT THE CODE, and not the passphrase, and not the derived key. What is here is the ticket — a
 * secret, but one whose whole life is this ceremony and whose only power is to enrol the credential
 * this browser already holds the keys for — the ids needed to ask whether the owner has decided
 * yet, and this run's half of the CPace transcript.
 *
 * WHY THE TRANSCRIPT IS HERE, which is new with the owner → clinician envelope. The owner's keys
 * arrive sealed under this run's key, at approval, which can be an hour or a day after the answer.
 * A clinician who reloads the tab in between must still be able to open it, and the honest way to
 * make that possible is the one the owner's side already uses (pairing/ownerRunStore.ts): keep the
 * SCALAR — one secret, whose life is this run — and the public messages that were on the wire
 * anyway, and re-derive. Keeping the key itself would be storing the thing that opens the envelope
 * rather than the thing that computes it, for no gain; keeping the code would break the one
 * invariant the whole ceremony rests on (§3.7.4). A test greps a stored record for both, with
 * planted controls so it cannot pass by being blind.
 *
 * MSGb is stored as well as MSGa, and that is not redundancy: the responder's transcript half
 * cannot be recomputed from the scalar without the generator, and the generator needs the code.
 *
 * sessionStorage, so all of it dies with the tab. A run that outlives its tab is the case the copy
 * already handles by asking for a new code.
 */
export interface TherapistRunRecord {
  v: 1
  inviteId: string
  exchangeId: string
  relRef: string
  enrolTicketB64: string
  credentialId: string
  /** This run's sid, as the owner posted it. Public. */
  sidB64: string
  /** The CPace scalar this browser answered with. Secret, and the reason this record is per-tab. */
  ybB64: string
  /** The owner's opening message, as fetched. Public. */
  msgAB64: string
  /** This browser's reply, as posted. Public, and not recomputable without the code. */
  msgBB64: string
}

export interface TherapistRunStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem?(key: string): void
}

export function defaultTherapistRunStorage(): TherapistRunStorage | null {
  try {
    return globalThis.sessionStorage ?? null
  } catch {
    return null
  }
}

export function saveTherapistRun(storage: TherapistRunStorage | null, record: TherapistRunRecord): void {
  if (!storage) return
  try {
    storage.setItem(THERAPIST_RUN_STORAGE_KEY, JSON.stringify(record))
  } catch {
    // A refusing browser costs the ability to survive a reload, not the ceremony.
  }
}

export function loadTherapistRun(storage: TherapistRunStorage | null): TherapistRunRecord | null {
  if (!storage) return null
  let raw: string | null
  try {
    raw = storage.getItem(THERAPIST_RUN_STORAGE_KEY)
  } catch {
    return null
  }
  if (!raw) return null
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null
  const r = parsed as Record<string, unknown>
  if (r.v !== 1) return null
  for (const k of [
    'inviteId',
    'exchangeId',
    'relRef',
    'enrolTicketB64',
    'credentialId',
    'sidB64',
    'ybB64',
    'msgAB64',
    'msgBB64',
  ] as const) {
    if (typeof r[k] !== 'string' || (r[k] as string).length === 0) return null
  }
  return {
    v: 1,
    inviteId: r.inviteId as string,
    exchangeId: r.exchangeId as string,
    relRef: r.relRef as string,
    enrolTicketB64: r.enrolTicketB64 as string,
    credentialId: r.credentialId as string,
    sidB64: r.sidB64 as string,
    ybB64: r.ybB64 as string,
    msgAB64: r.msgAB64 as string,
    msgBB64: r.msgBB64 as string,
  }
}

export function forgetTherapistRun(storage: TherapistRunStorage | null): void {
  if (!storage) return
  try {
    if (storage.removeItem) storage.removeItem(THERAPIST_RUN_STORAGE_KEY)
    else storage.setItem(THERAPIST_RUN_STORAGE_KEY, '')
  } catch {
    // Nothing to do.
  }
}

/** Everything the pairing half reaches outside itself for, beyond what the enrolment already needs. */
export interface PairingAcceptancePorts {
  /** Answer the owner's run, sealing the offer this callback builds once the relRef is known. */
  answer(args: {
    inviteId: string
    secret: string
    code: CanonicalPairingCode
    makeOffer: (relRef: string) => Promise<TherapistOffer>
  }): Promise<{ exchangeId: string; relRef: string } & TherapistRunKeying>
  /** Has the owner decided? */
  status(args: { inviteId: string; secret: string; exchangeId: string }): Promise<TherapistStatusResult>
  /**
   * Open the envelope the owner's approval carried, using this run's transcript, and return the two
   * public keys inside — or null for every failure, which is the only honest answer an AEAD gives.
   *
   * A port so that the ORDER this module owns (owner keys written down, THEN enrolment) stays a
   * node test over stubs, while the crypto it stands for is proved end to end where a real ceremony
   * runs: pairing/relay.ts's ownerKeysFromEnvelope, against a real envelope the owner's side sealed.
   */
  ownerKeysFrom(run: TherapistRunRecord, envB64: string): OwnerPublicKeysB64 | null
  /** Write the owner's keys into this relationship's record. Insert-only; see pinOwnerKeysOnRecord. */
  pinOwnerKeys(relRef: string, keys: OwnerPublicKeysB64): 'pinned-now' | 'already-pinned' | 'differs-from-pin' | 'no-record'
  /** The enrolment half, and everything after it. */
  accept: AcceptancePorts
  runStorage: TherapistRunStorage | null
  /** How long to wait between polls. A port so a test does not sleep. */
  wait(ms: number): Promise<void>
}

/** The real ports over a live relay and a live portal client. */
export function pairingPortsFor(
  accept: AcceptancePorts,
  baseUrl: string,
  runStorage: TherapistRunStorage | null = defaultTherapistRunStorage(),
): PairingAcceptancePorts {
  return {
    answer: async (args) => {
      const r = await therapistAnswerPairing({ ...args, baseUrl })
      return {
        exchangeId: r.exchangeId,
        relRef: r.relRef,
        sidB64: r.sidB64,
        ybB64: r.ybB64,
        msgAB64: r.msgAB64,
        msgBB64: r.msgBB64,
      }
    },
    status: (args) => therapistPairingStatus({ ...args, baseUrl }),
    ownerKeysFrom: (run, envB64) => ownerKeysFromEnvelope(run, envB64),
    pinOwnerKeys: (relRef, keys) => pinOwnerKeysOnRecord(relRef, keys, accept.storage),
    accept,
    runStorage,
    wait: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  }
}

export interface PairingAcceptInput {
  inviteId: string
  secret: string
  /** What the person typed. Parsed here; a bad check symbol never reaches the wire. */
  typedCode: string
  passphrase: string
  /** What the owner will see beside the fingerprints. Their own words, shown as such. */
  displayName: string
  host?: string
}

/** Where an answered run stands while the owner decides. */
export type PairingWait =
  | { state: 'waiting' }
  /** `envB64` is the owner's keys, sealed under this run's key; absent from a console that predates them. */
  | { state: 'approved'; scope: string[]; envB64?: string }
  /** Cancelled, superseded, reported, expired: one answer, and the remedy is a new code. */
  | { state: 'gone' }

export interface AnsweredRun {
  record: TherapistRunRecord
  keys: TherapistKeys
}

/**
 * Steps 1 to 5: parse the code, answer the owner's run, and leave this browser holding keys, a
 * record, and a ticket nobody else has seen in the clear.
 *
 * ON FAILURE, NOTHING IS LEFT BEHIND. A run that could not be answered removes the record it wrote
 * and zeroizes the keys, so a person who tries again with a new code is not refused by their own
 * previous attempt. That is the same reasoning as beginAcceptance's rollback, applied one step
 * earlier: no credential exists yet, so there is nothing the record could still be protecting.
 */
export async function answerPairing(ports: PairingAcceptancePorts, input: PairingAcceptInput): Promise<AnsweredRun> {
  const parsed = parsePairingCode(input.typedCode)
  if (!parsed.ok) throw new AcceptError(PAIRING_FAULT_TEXT[parsed.fault], 'code')
  if (!validDisplayName(input.displayName) || input.displayName.trim().length === 0) {
    throw new AcceptError(
      `Give the person who invited you a name to recognise you by — up to ${DISPLAY_NAME_MAX_CODEPOINTS} characters, and plain text.`,
      'name',
    )
  }

  let made: { keys: TherapistKeys; record: KeyRecord; ticket: string } | null = null
  try {
    const answered = await ports.answer({
      inviteId: input.inviteId,
      secret: input.secret,
      code: parsed.code.canonical,
      makeOffer: async (relRef) => {
        // The first moment this browser knows which relationship it is being invited into, and so
        // the first moment the insert-only question can be asked.
        if (findKeyRecord(relRef, ports.accept.storage) !== null) {
          throw new AcceptError(
            'This browser already holds keys for this relationship. Sign in with your reading passphrase instead — accepting again here would replace keys that cannot be recovered.',
            'record',
          )
        }
        const keys = ports.accept.newKeys()
        let wrapped: WrappedKeyBlob
        try {
          wrapped = await ports.accept.wrapKeys(keys, input.passphrase)
        } catch {
          throw new AcceptError('Your keys could not be wrapped under that passphrase, so nothing was sent.', 'wrap')
        }
        let reopened: TherapistKeys | null = null
        try {
          reopened = await ports.accept.unwrapKeys(wrapped, input.passphrase)
        } catch {
          throw new AcceptError(
            'The wrapped keys would not open again under the passphrase you chose, so nothing was sent. Nothing has been lost — try again, and if it happens twice the fault is in this page rather than in what you typed.',
            'wrap',
          )
        }
        const opens = sameKeys(reopened, keys)
        zeroize(reopened)
        if (!opens) {
          throw new AcceptError(
            'The wrapped keys opened to something other than the keys that went in, so nothing was sent. This is a fault in this page, not in what you typed.',
            'wrap',
          )
        }
        const credentialId = ports.accept.randomToken(CREDENTIAL_ID_BYTES).b64url
        const record: KeyRecord = { v: 1, relRef, credentialId, wrapped, createdAt: ports.accept.now() }
        saveKeyRecord(record, ports.accept.storage)
        const ticket = newEnrolTicketB64()
        made = { keys, record, ticket }
        return {
          boxPubB64: ports.accept.toBase64(keys.box.publicKey),
          signPubB64: ports.accept.toBase64(keys.sign.publicKey),
          displayName: input.displayName.trim(),
          enrolTicketB64: ticket,
        }
      },
    })
    const built = made as { keys: TherapistKeys; record: KeyRecord; ticket: string } | null
    if (!built) throw new AcceptError('The pairing did not get far enough to send anything.', 'pairing')
    const record: TherapistRunRecord = {
      v: 1,
      inviteId: input.inviteId,
      exchangeId: answered.exchangeId,
      relRef: answered.relRef,
      enrolTicketB64: built.ticket,
      credentialId: built.record.credentialId,
      sidB64: answered.sidB64,
      ybB64: answered.ybB64,
      msgAB64: answered.msgAB64,
      msgBB64: answered.msgBB64,
    }
    saveTherapistRun(ports.runStorage, record)
    return { record, keys: built.keys }
  } catch (e) {
    const built = made as { keys: TherapistKeys; record: KeyRecord; ticket: string } | null
    if (built) {
      forgetKeyRecord(built.record.relRef, ports.accept.storage)
      zeroize(built.keys)
    }
    if (e instanceof AcceptError) throw e
    const because = e instanceof Error && e.message ? e.message : ''
    // A run somebody else answered first, or one the owner has already replaced, reads the same
    // from here: there is nothing to answer. The remedy is a new code, not a diagnosis.
    if (/refused \(410\)|refused \(409\)/.test(because)) {
      throw new AcceptError(
        'That pairing is no longer open. Ask the person who invited you for a new code and try again.',
        'pairing',
      )
    }
    throw new AcceptError(
      `The pairing could not be sent${because ? ` (${because})` : ''}. Nothing was set up. Ask for a new code and try again.`,
      'pairing',
    )
  }
}

/**
 * Step 6, until it resolves: wait for the owner, at the cadence the shared budget allows.
 *
 * [onTick] lets the screen say something true while it waits without this module knowing what a
 * screen is. [signal] lets a person leave. Nothing here gives up on its own: an owner may take a
 * day to approve, and the ticket lives as long as the invitation.
 */
export async function waitForApproval(
  ports: PairingAcceptancePorts,
  record: TherapistRunRecord,
  secret: string,
  opts: { onTick?: (polls: number) => void; signal?: { aborted: boolean } } = {},
): Promise<PairingWait> {
  let polls = 0
  for (;;) {
    if (opts.signal?.aborted) return { state: 'waiting' }
    const status = await ports.status({ inviteId: record.inviteId, secret, exchangeId: record.exchangeId })
    if (status.state !== 'waiting') {
      if (status.state === 'gone') forgetTherapistRun(ports.runStorage)
      return status
    }
    opts.onTick?.(++polls)
    await ports.wait(PAIRING_STATUS_POLL_MS)
  }
}

/**
 * Steps 7 and 8: write down the owner's keys, then spend the ticket the owner just made live.
 *
 * THE ORDER IS THE POINT, and it is the mirror of the owner's own (pairing/ownerCeremony.ts pins
 * before it forwards a ticket). Enrolling is not reversible from this side: the ticket is spent,
 * the credential exists, the invitation is CONSUMED, and no route un-enrols one. Pinning is a local
 * write. So the local write happens first, and a clinician is never left enrolled into a
 * relationship whose owner nothing ever proved to them.
 *
 * WHAT EACH REFUSAL MEANS. A missing envelope is an owner console that approved before this existed;
 * one that will not open is a run this browser is not the other end of, or bytes that were changed.
 * Both say the same thing to a person — ask for a new code — because this side cannot tell them
 * apart and a guess here would be a guess about whether someone is being attacked. A record that
 * already holds DIFFERENT owner keys is the substitution the pin exists to make visible, and it
 * stops everything.
 */
export async function enrolAfterApproval(
  ports: PairingAcceptancePorts,
  run: AnsweredRun,
  scope: string[],
  host?: string,
  /** E2, as the status poll returned it. Absent means the owner's console proved nothing. */
  envB64?: string,
): Promise<Enrolment> {
  if (!envB64) {
    throw new AcceptError(
      'The approval carried no keys for you to check them by, so nothing was enrolled. Ask the person ' +
        'who invited you for a new code, from a console they have reloaded.',
      'pairing',
    )
  }
  const ownerKeys = ports.ownerKeysFrom(run.record, envB64)
  if (!ownerKeys) {
    throw new AcceptError(
      'The keys that came back with the approval could not be opened, so nothing was enrolled. Ask ' +
        'the person who invited you for a new code.',
      'pairing',
    )
  }
  const pinned = ports.pinOwnerKeys(run.record.relRef, ownerKeys)
  if (pinned === 'differs-from-pin') {
    throw new AcceptError(
      'This browser already holds different keys for the person who invited you, so nothing was ' +
        'enrolled. Check with them on a channel that is not this server before going further.',
      'record',
    )
  }
  if (pinned === 'no-record') {
    throw new AcceptError(
      'This browser no longer holds the keys it made for this relationship, so nothing was enrolled. ' +
        'Ask for a new invitation and accept it in this browser.',
      'record',
    )
  }
  const enrolment = await enrolWithTicket(ports.accept, {
    relRef: run.record.relRef,
    scope,
    enrollTicket: run.record.enrolTicketB64,
    credentialId: run.record.credentialId,
    keys: run.keys,
    host,
  })
  // The ticket is spent; keeping the record would only leave a live ticket in storage.
  forgetTherapistRun(ports.runStorage)
  return enrolment
}
