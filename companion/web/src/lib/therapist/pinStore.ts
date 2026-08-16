/*
 * PERSISTENCE for the owner console's therapist pin store.
 *
 * WHY THIS FILE EXISTS. ShareBuilder.seal() used to do this, one line before sealing:
 *
 *     const pins = new PinStore()
 *     pins.pin({ x25519Pub: therapist.boxPub, ed25519Pub: therapist.signPub })
 *
 * — build an empty store, then pin the very keys it was about to seal to. buildShare's three
 * assertions ("is this therapist pinned", "does meta.recipientFp match the key", "is this the
 * X25519 key we pinned for them") then each compared a value with itself, so none of them could
 * fail. The console printed "sealed to their pinned key" and had checked nothing. If a hostile
 * server, or a mis-paste into the pin form, had swapped the therapist's X25519 public key, the
 * owner's journal — their worst days, in their own words — would have been sealed to the new key
 * and the console would have said the same reassuring thing.
 *
 * A pin is only a control if it OUTLIVES the thing it checks. So the pins live in localStorage
 * across sessions: the first seal to a therapist records their two fingerprints, and every later
 * seal is checked against that record instead of against itself.
 *
 * WHAT GOES INTO STORAGE. PinStore.serialize() emits fingerprints and timestamps only — no key
 * bytes, no inbox token, no journal content, no names. That is the property that makes this
 * storable at all; if PinStore ever starts carrying key material, this file has to stop using
 * localStorage.
 *
 * BUT SAY THE REST OF IT. This is the FIRST persistent client-side storage anywhere in the web
 * app — before this, `localStorage` appeared nowhere under `src/lib`. So it leaves a durable,
 * unencrypted record on the device that says: this browser profile is a Daymark owner console,
 * it has N therapist relationships, and they were first pinned on these dates. No keys and no
 * writing, but on a device that might be shared, borrowed, or looked through, the number of
 * clinicians someone sees and when that started is information about their care. "Fingerprints
 * and timestamps only" is true and is not the whole truth, and this file should not be read as
 * claiming otherwise.
 *
 * The trade was made deliberately: without persistence the pin check is a tautology that proves
 * nothing, and a substituted encryption key would hand a hostile server the person's entire
 * journal. A key-substitution defence is worth more than the metadata it costs. Because the record
 * is the person's to keep or drop, listPins/forgetAllPins/forgetPeer below let them see it and
 * erase it from inside the app — previously the only way to clear it was clearing site data, which
 * a person would have to know to do. components/owner/PinRecord.svelte is the screen.
 *
 * AND A WAY BACK. Failing closed on a changed key is the point, but a therapist who legitimately
 * re-keys would otherwise leave the owner permanently unable to share, with no route back except
 * that same site-data reset — a bigger hammer that silently un-pins everyone. rotatePin below is
 * the route back, and it is deliberately NOT one click: a "trust the new key" button would hand
 * back exactly what the pin bought, since the whole value of the pin is that a silent key change
 * is refused. It takes the SAS words the owner has to have read back on another channel.
 *
 * WHAT THIS STILL DOES NOT CATCH. Pins are keyed by the therapist's Ed25519 fingerprint, which is
 * also how the owner console derives a therapist's id — so a swap of the SIGNING key reads as a
 * different therapist and gets trust-on-first-use like any new one. Only the encryption key,
 * the one shares are actually sealed to, is nailed down by a prior pin. Closing the remaining gap
 * needs a relationship identifier that is not itself derived from a key, which the console does
 * not have today. The SAS words in OwnerUnlock are the out-of-band check that covers it.
 */
import {
  PinStore,
  PairingError,
  fingerprints,
  type PublicIdentity,
  type PinnedIdentity,
} from '../share/pairing'

/** Versioned so a later pin format can be migrated rather than silently mis-read. */
export const PIN_STORAGE_KEY = 'daymark.owner.pins.v1'

/** The slice of the Storage API this needs. Lets tests pass a plain object; Node has no DOM. */
export interface PinStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  /**
   * Optional because the read and write paths above do not need it — NOT because erasing is
   * optional. A storage object that cannot remove makes forgetAllPins() throw rather than return
   * quietly, so nobody is told their record was cleared when it is still sitting there.
   */
  removeItem?(key: string): void
}

/** localStorage, or null where it does not exist or the browser refuses to hand it over. */
export function defaultPinStorage(): PinStorage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    // Access itself throws in some blocked/partitioned contexts, not just returns undefined.
    return null
  }
}

/**
 * Read the persisted pins.
 *
 * Unreadable storage and unreadable CONTENT both throw rather than returning an empty store: an
 * empty store means "nobody is pinned", so the next seal trust-on-first-uses whatever key it is
 * handed, and falling back to that quietly would restore the hole this file exists to close — at
 * the moment there is most reason to be suspicious.
 *
 * An ABSENT key is different and does return an empty store, which is worth being plain about: a
 * key that was never written and a key someone deleted are indistinguishable from here. Clearing
 * site data therefore resets trust silently. That is simultaneously the only recovery path when a
 * therapist legitimately re-keys, and the way to bypass this check — and there is no third
 * behaviour available, because the browser does not tell us which happened. The mitigation is not
 * technical: the SAS words in the pairing flow are what the owner actually checks a key against.
 */
export function loadPins(storage: PinStorage | null = defaultPinStorage()): PinStore {
  if (!storage) {
    throw new PairingError(
      'this browser will not let the console remember which keys you pinned, so it cannot tell you if they change — refusing to seal',
    )
  }
  const raw = storage.getItem(PIN_STORAGE_KEY)
  if (raw === null) return new PinStore()
  try {
    return PinStore.load(raw)
  } catch {
    throw new PairingError(
      'the stored therapist pins are unreadable — clear them and re-pin after checking the fingerprint words out of band',
    )
  }
}

export function savePins(pins: PinStore, storage: PinStorage | null = defaultPinStorage()): void {
  if (!storage) throw new PairingError('no storage available to record the pin')
  try {
    storage.setItem(PIN_STORAGE_KEY, pins.serialize())
  } catch (e) {
    // setItem throws on a full or partitioned quota (Safari private browsing is the common one).
    // Fail closed, but not with a raw DOMException: "The quota has been exceeded." tells an owner
    // trying to share with their therapist nothing about what to do next.
    throw new PairingError(
      'this browser refused to save the pin, so it could not remember this therapist\'s keys — nothing was sealed or sent',
    )
  }
}

export type PinOutcome = 'pinned-now' | 'already-pinned'

/**
 * Trust-on-first-use, and only on first use.
 *
 * The second branch is the fix: on every seal after the first, the stored pin is left ALONE so
 * buildShare compares the live key against what was recorded back then. Re-pinning here — which
 * is what the old inline code did every single time — would overwrite the evidence with the thing
 * being checked and make the gate vacuous again.
 */
export function pinOnFirstUse(pins: PinStore, peer: PublicIdentity, now: number = Date.now()): PinOutcome {
  const { ed25519Fp } = fingerprints(peer)
  if (pins.isPinned(ed25519Fp)) return 'already-pinned'
  pins.pin(peer, now)
  return 'pinned-now'
}

/**
 * The pins as data, so a screen can show a person what is being kept about them.
 *
 * PinStore answers questions about one peer at a time and exposes no iterator; pairing.ts is
 * another slice's file, so this reads back the exact bytes savePins() writes instead of adding an
 * accessor there. That is worth keeping even if PinStore later grows one: what the console shows
 * is parsed from the stored form, so the screen cannot display something other than what is stored.
 */
export function listPins(pins: PinStore): PinnedIdentity[] {
  return JSON.parse(pins.serialize()) as PinnedIdentity[]
}

/**
 * Drop this browser's entire pin record.
 *
 * removeItem, not setItem('[]'): an empty array is still a `daymark.owner.pins.v1` key sitting in
 * localStorage announcing that this browser profile is a Daymark owner console. The existence of
 * the record is half of what is being cleared, so writing an empty one would clear the wrong half.
 *
 * What it does NOT do, because someone deleting something needs to know how far the deletion
 * reaches: it does not touch the server, which never had this record; it does not reach the
 * therapist; and it does not unsend anything already sealed. It also re-arms trust-on-first-use —
 * the next seal records whatever key it is handed, with nothing left to compare against. That is
 * the honest cost of forgetting and the screen says it in those words.
 */
export function forgetAllPins(storage: PinStorage | null = defaultPinStorage()): void {
  if (!storage || typeof storage.removeItem !== 'function') {
    throw new PairingError(
      'this browser will not let the console erase the stored pins — clearing site data for this page is the only way left to remove them',
    )
  }
  storage.removeItem(PIN_STORAGE_KEY)
}

/**
 * The same erasure for ONE relationship, returned as a new store for the caller to save.
 *
 * Separate from forgetAllPins because forget-everything has a cost that is easy to walk into: it
 * also un-pins the therapists the person did not mean to forget, and the next share sealed to each
 * of them trusts whatever key arrives. Someone ending one relationship should not have to give up
 * key-substitution cover for the others to get privacy from this device.
 *
 * PinStore has no delete, so this rebuilds one from the entries that are kept. Via load(), so an
 * entry that has gone malformed is rejected here rather than being written back out.
 */
export function forgetPeer(pins: PinStore, ed25519Fp: string): PinStore {
  return PinStore.load(JSON.stringify(listPins(pins).filter((p) => p.ed25519Fp !== ed25519Fp)))
}

/** A recorded encryption key and the different one the console is holding for the same therapist. */
export interface PinRotation {
  ed25519Fp: string
  /** What storage says, and when it was recorded. */
  pinnedX25519Fp: string
  pinnedAt: number
  /** What the console is being asked to accept instead. */
  offeredX25519Fp: string
}

/**
 * What a rotation WOULD change, or null when there is nothing to rotate.
 *
 * Two different "nothing to rotate" cases collapse to null on purpose, because neither is a
 * decision to put in front of a person: an unpinned therapist is trust-on-first-use's job, and an
 * unchanged key is not a change. Only a genuine disagreement between the record and the live key
 * gets a prompt — otherwise the screen would train someone to click through a confirmation that
 * usually means nothing, which is how a real key substitution gets waved past.
 */
export function pendingRotation(pins: PinStore, peer: PublicIdentity): PinRotation | null {
  const { x25519Fp, ed25519Fp } = fingerprints(peer)
  if (!pins.isPinned(ed25519Fp)) return null
  const pinned = pins.assertPinned(ed25519Fp)
  if (pinned.x25519Fp === x25519Fp) return null
  return {
    ed25519Fp,
    pinnedX25519Fp: pinned.x25519Fp,
    pinnedAt: pinned.pinnedAt,
    offeredX25519Fp: x25519Fp,
  }
}

/** The out-of-band check, carried as data so rotatePin can refuse without one. */
export interface RotationConfirmation {
  /** The SAS words this console computed for the keys it is being asked to pin. */
  expectedWords: string
  /** What the owner typed back after reading them with the therapist on another channel. */
  typedWords: string
}

/** Case and spacing are how the words were said aloud, not part of the check. */
function normalizeWords(s: string): string {
  return s.trim().toLowerCase().split(/\s+/).filter(Boolean).join(' ')
}

/**
 * The SAS comparison, exported so the screen's accept button and rotatePin's refusal are the same
 * predicate rather than two that can drift apart — a button that lights up on a phrase rotatePin
 * would reject teaches an owner that the check is noise.
 *
 * False on a short or empty expected phrase, which is the case that matters most: if the console
 * failed to compute the words, '' would otherwise equal the '' of an owner who typed nothing and
 * the confirmation would confirm itself. sasWords() emits 4–8 words, so anything shorter is a
 * failure to compute rather than a short answer.
 */
export function sasWordsMatch(expectedWords: string, typedWords: string): boolean {
  const expected = normalizeWords(expectedWords)
  if (expected.split(' ').length < 4) return false
  return expected === normalizeWords(typedWords)
}

/**
 * Replace the recorded encryption key for a therapist who re-keyed.
 *
 * WHY IT TAKES WORDS AND NOT A BOOLEAN. A pin is worth exactly one thing: a key that changed
 * behind the owner's back is refused. Any affordance that accepts the new key on the strength of
 * a click hands that back — the same server able to substitute the key can also render a page
 * whose most obvious button says "trust it". The words are the part the server cannot supply,
 * because the owner gets them from the therapist on a channel this app is not on. So the caller
 * has to produce them, and this refuses if they do not match what the console computed.
 *
 * The four-word floor is not a formatting rule. Without it, a caller that failed to compute the
 * SAS would pass expectedWords: '' and an owner who typed nothing would satisfy the check — the
 * confirmation would be a tautology of exactly the kind this whole file exists to remove.
 *
 * Mutates `pins`; the caller saves. Rotation is not complete until savePins() succeeds.
 */
export function rotatePin(
  pins: PinStore,
  peer: PublicIdentity,
  confirmation: RotationConfirmation,
  now: number = Date.now(),
): PinRotation {
  const { ed25519Fp } = fingerprints(peer)
  const rotation = pendingRotation(pins, peer)
  if (!rotation) {
    throw new PairingError(
      pins.isPinned(ed25519Fp)
        ? 'the key on file for this therapist is already the one you are holding — nothing to rotate'
        : 'nothing is pinned for this therapist yet, so there is nothing to rotate — the first share you seal records their key',
    )
  }
  if (normalizeWords(confirmation.expectedWords).split(' ').length < 4) {
    throw new PairingError(
      'this console could not work out the fingerprint words for the new key, so there is nothing for you to check it against — the pin was left as it is',
    )
  }
  if (!sasWordsMatch(confirmation.expectedWords, confirmation.typedWords)) {
    throw new PairingError(
      'those are not the fingerprint words for the key you were offered — the pin was left as it is, and nothing has been sealed to the new key',
    )
  }
  pins.pin(peer, now)
  return rotation
}
