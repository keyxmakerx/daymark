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
 * journal. A key-substitution defence is worth more than the metadata it costs. What is still
 * owed — and is NOT built — is a way to see and forget these pins from inside the app. Today the
 * only way to clear them is clearing site data, which a person would have to know to do. That is
 * a gap, not a design; it is written down in docs/SECURITY_AUDIT_2026-08.md rather than left for
 * someone to discover.
 *
 * WHAT THIS STILL DOES NOT CATCH. Pins are keyed by the therapist's Ed25519 fingerprint, which is
 * also how the owner console derives a therapist's id — so a swap of the SIGNING key reads as a
 * different therapist and gets trust-on-first-use like any new one. Only the encryption key,
 * the one shares are actually sealed to, is nailed down by a prior pin. Closing the remaining gap
 * needs a relationship identifier that is not itself derived from a key, which the console does
 * not have today. The SAS words in OwnerUnlock are the out-of-band check that covers it.
 */
import { PinStore, PairingError, fingerprints, type PublicIdentity } from '../share/pairing'

/** Versioned so a later pin format can be migrated rather than silently mis-read. */
export const PIN_STORAGE_KEY = 'daymark.owner.pins.v1'

/** The slice of the Storage API this needs. Lets tests pass a plain object; Node has no DOM. */
export interface PinStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
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
