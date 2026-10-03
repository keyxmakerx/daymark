/*
 * THE OWNER'S KEY ON THE SERVER — found, made once, enrolled once, opened every visit (#258).
 *
 * The server keeps the owner's master locked twice, once under the passphrase and once under the
 * recovery code (dataKey.ts), and hands the locks back with the owner's token
 * (docs/SYNC_PROTOCOL.md §1.2 and §2). What a console does on arriving depends on what the server
 * holds, and there are exactly three answers:
 *
 *   nothing          a first run. A random master, locked under the passphrase and a new recovery
 *                    code, is created with `If-None-Match: *`, read back and opened before any
 *                    identity is derived. The ONLY place a random master is made: an owner with an
 *                    archive already has a master, and a second one would be a second identity.
 *   key parameters   an enrolment. The master the passphrase already derives is locked, through
 *                    enrolExistingOwner() over the published parameters, and created with `If-Match`
 *                    set to their ETag exactly as it was received. No snapshot is encrypted again and
 *                    the identity does not change, because the master does not.
 *   a locked key     an unlock, with the passphrase or the recovery code (owner/unlock.ts).
 *
 * WHY THIS IS A MODULE AND NOT COMPONENT CODE. What matters here is ORDER — a passphrase proved
 * before anything is written, a create named against the state that was read, a read-back opened
 * before any identity exists — and order can only be observed by something watching the calls. So
 * the reads and writes are ports, and each order is a node test (pairing/ownerCeremony.ts makes the
 * same argument for the same reason).
 *
 * THE ENROLMENT ANCHOR. Before anything is written, the passphrase has to be shown to be the one
 * the archive was written under, or an enrolment with a mistyped passphrase would lock a master that
 * opens nothing and then retire the key parameters that did. The server stores no manifest to
 * compare against (#179), so the proof is the newest stored snapshot: subkey 1 of the master the
 * passphrase and the key parameters derive has to open it. Where the server stores no snapshot at
 * all there is nothing to prove against, and the passphrase is asked for twice instead. A
 * passphrase that fails either check writes nothing.
 *
 * BEFORE ANY UPLOAD, the new document is opened here with the passphrase and with the recovery
 * code, and both must give back the master it was made from, derived independently of the call
 * that locked it. A locking step that wrapped anything else — a random master where key parameters
 * exist is the failure the whole design exists to prevent — is refused before the server hears of
 * it.
 *
 * THE STATE CAN MOVE UNDER A CONSOLE. Two devices can both read "nothing" and both try a first
 * run; the server takes one and answers the other 412. That is not an error here: the console reads
 * the key document again and acts on what is there now, and says so. 428, a create that names no
 * state, cannot come from this module, which always names one (sync/client.ts).
 *
 * WHAT IS NEVER HANDED BACK: a master. Every master made or opened here is wiped before the
 * function returns, and what a caller gets is the identity (owner/identity.ts) and, once, the new
 * recovery code.
 */
import { decryptSnapshot, fromBase64, initCrypto } from '../sync/crypto'
import type { CreateAgainst, KeyDocument, SyncClient } from '../sync/client'
import { paced, pause, type Wait } from '../sync/paced'
import { isLaneLineage } from '../lane/lineage'
import type { LaneKey } from '../lane/lane'
import {
  createRecoverableDataKey,
  replacePassphrase,
  unwrapWithPassphrase,
  unwrapWithRecoveryCode,
  zeroizeDataKey,
  type RecoverableDataKey,
} from './dataKey'
import { enrolExistingOwner, masterFromPassphrase, subkeysFromMaster, syncKeyFromMaster } from './migration'
import type { RecoveryCode } from './recoveryCode'
import { ownerIdentityFromMaster } from '../owner/identity'
import type { Identity } from '../share/pairing'

/** One stored snapshot, by where the server files it. */
export interface SnapshotRef {
  lineage: string
  version: number
}

/** Everything this module asks of the server. See the header for why these are ports. */
export interface ServerKeyPorts {
  /** `GET /v1/keydoc`. */
  read(): Promise<KeyDocument>
  /** `POST /v1/keydoc` against the state read: 'moved' is the server's 412. */
  create(wrapped: RecoverableDataKey, against: CreateAgainst): Promise<'created' | 'moved'>
  /** `PUT /v1/keydoc/{version}`: 'moved' is the server's 409. */
  replace(version: number, wrapped: RecoverableDataKey): Promise<'written' | 'moved'>
  /** The newest snapshot the server stores, by when it was stored, or null when it stores none. */
  newestSnapshot(): Promise<SnapshotRef | null>
  /** One stored snapshot's bytes. */
  snapshot(ref: SnapshotRef): Promise<Uint8Array>
}

/**
 * Why a set-up wrote nothing. Every one of these is decided before the create is sent, so the
 * sentence a screen shows for it can say that nothing was stored and be true.
 */
export type SetUpFault =
  | 'noPassphrase'
  /** Nothing to check the passphrase against, and it was typed once: the second field is needed. */
  | 'typeItTwice'
  | 'passphrasesDiffer'
  /** The enrolment anchor: this passphrase does not open the newest stored snapshot. */
  | 'doesNotOpenNewest'
  /** A first run on a server that stores snapshots and no key document: a new master opens none of them. */
  | 'snapshotsWithoutKey'
  /** The new document did not open, with both secrets, to the master it was made from. */
  | 'selfCheckFailed'
  /** The server already holds a locked key: that is an unlock, not a set-up. */
  | 'alreadyLocked'

export type SetUpResult =
  /**
   * Created, read back and opened. The recovery code is here once, for the screen to show. `lane` is
   * subkey 1 of the master the read-back opened to, with the ETag of that read-back, for the
   * console's lane (lane/lane.ts, #345); the caller wipes its key when the console locks, or at once
   * if it has no lane to write.
   */
  | { kind: 'stored'; recoveryCode: RecoveryCode; identity: Identity; lane: LaneKey }
  /**
   * The server took the create (201), and it could not be read back afterwards, however many times
   * it was asked. The lock the code opens is on the server, so the code is here to be shown: it is
   * the only copy anyone will ever have. No identity is derived, because nothing was read back to
   * open; `sent` is what was created, for confirmStored() to compare a later read against.
   */
  | { kind: 'storedUnread'; recoveryCode: RecoveryCode; sent: RecoverableDataKey }
  /** The server's 412: what it holds now, read again. Nothing from this set-up was stored. */
  | { kind: 'moved'; now: KeyDocument }
  /** Nothing was written. */
  | { kind: 'refused'; fault: SetUpFault }
  /** The create was taken, and what the server handed back did not open to the same master. */
  | { kind: 'unchecked' }
  /**
   * The create got no answer this module could trust, and reading again did not find the key it
   * sent. Whether anything was stored is not known here; the next read says.
   */
  | { kind: 'failed' }

/** How an enrolment will know the passphrase is the right one, before it is typed. */
export type EnrolmentCheck = 'newestSnapshot' | 'typedTwice'

const same = (a: Uint8Array, b: Uint8Array): boolean => a.length === b.length && a.every((byte, i) => byte === b[i])

/**
 * Which check an enrolment on this server will make, so a screen can ask for the passphrase once
 * or twice before it is typed. set-up asks the server again when it runs, and trusts only that.
 */
export async function enrolmentCheck(ports: ServerKeyPorts): Promise<EnrolmentCheck> {
  return (await ports.newestSnapshot()) ? 'newestSnapshot' : 'typedTwice'
}

/**
 * Set up the owner's key on a server that holds none, or holds only key parameters. Dispatches on
 * what was read, and on nothing else: key parameters are always an enrolment, never a first run.
 */
export async function setUp(
  ports: ServerKeyPorts,
  held: KeyDocument,
  passphrase: string,
  repeated: string | null,
): Promise<SetUpResult> {
  if (held.kind === 'wrapped') return { kind: 'refused', fault: 'alreadyLocked' }
  if (!passphrase) return { kind: 'refused', fault: 'noPassphrase' }
  await initCrypto()
  return held.kind === 'none' ? firstRun(ports, passphrase, repeated) : enrol(ports, held, passphrase, repeated)
}

/**
 * A server holding no key document: a random master, locked twice. The only caller of
 * createRecoverableDataKey in the product, and a test holds it to that.
 */
async function firstRun(ports: ServerKeyPorts, passphrase: string, repeated: string | null): Promise<SetUpResult> {
  // A passphrase chosen here has never been typed before, so twice is the only check it can have.
  if (repeated === null) return { kind: 'refused', fault: 'typeItTwice' }
  if (repeated !== passphrase) return { kind: 'refused', fault: 'passphrasesDiffer' }
  if (await ports.newestSnapshot()) return { kind: 'refused', fault: 'snapshotsWithoutKey' }
  const made = await createRecoverableDataKey(passphrase)
  try {
    if (!(await opensToMaster(made.blob, passphrase, made.recoveryCode, made.dataKey))) {
      return { kind: 'refused', fault: 'selfCheckFailed' }
    }
    return await store(ports, made.blob, { kind: 'firstRun' }, passphrase, made.dataKey, made.recoveryCode)
  } finally {
    zeroizeDataKey(made.dataKey)
  }
}

/**
 * Key parameters and no locked key: lock the master the passphrase already derives. The anchor
 * runs first, and a passphrase that fails it writes nothing (see the header).
 */
async function enrol(
  ports: ServerKeyPorts,
  held: Extract<KeyDocument, { kind: 'keyparams' }>,
  passphrase: string,
  repeated: string | null,
): Promise<SetUpResult> {
  const newest = await ports.newestSnapshot()
  if (!newest) {
    if (repeated === null) return { kind: 'refused', fault: 'typeItTwice' }
    if (repeated !== passphrase) return { kind: 'refused', fault: 'passphrasesDiffer' }
  }
  const salt = fromBase64(held.params.saltB64)
  // The direct derivation, made here and independently of the call that locks the master, so the
  // check before upload compares two derivations rather than one with itself.
  const direct = await masterFromPassphrase(passphrase, salt, held.params.kdf)
  try {
    if (newest && !opensSnapshot(direct, newest, await ports.snapshot(newest))) {
      return { kind: 'refused', fault: 'doesNotOpenNewest' }
    }
    const enrolled = await enrolExistingOwner(passphrase, salt, held.params.kdf)
    zeroizeDataKey(enrolled.master)
    if (!(await opensToMaster(enrolled.blob, passphrase, enrolled.recoveryCode, direct))) {
      return { kind: 'refused', fault: 'selfCheckFailed' }
    }
    return await store(ports, enrolled.blob, { kind: 'enrolment', etag: held.etag }, passphrase, direct, enrolled.recoveryCode)
  } finally {
    zeroizeDataKey(direct)
  }
}

/** Whether subkey 1 of this master opens the snapshot the server stores at `ref`. */
function opensSnapshot(master: Uint8Array, ref: SnapshotRef, bytes: Uint8Array): boolean {
  const { syncKey, manifestSeed } = subkeysFromMaster(master)
  try {
    decryptSnapshot(bytes, syncKey, ref.lineage, ref.version)
    return true
  } catch {
    return false
  } finally {
    syncKey.fill(0)
    manifestSeed.fill(0)
  }
}

/** Whether the document opens, with the passphrase and with the code, to exactly this master. */
async function opensToMaster(
  wrapped: RecoverableDataKey,
  passphrase: string,
  code: RecoveryCode,
  master: Uint8Array,
): Promise<boolean> {
  const opened: Uint8Array[] = []
  try {
    opened.push(await unwrapWithPassphrase(wrapped, passphrase))
    opened.push(await unwrapWithRecoveryCode(wrapped, code.canonical))
    return opened.every((m) => same(m, master))
  } catch {
    return false
  } finally {
    for (const m of opened) zeroizeDataKey(m)
  }
}

/**
 * Create, then read back and open, then — only then — derive the identity. A 412 reads the key
 * document again and hands it back for the screen to act on.
 *
 * THE CODE IS NEVER DROPPED WHILE THE SERVER MAY HOLD ITS LOCK (#258). Once a create may have
 * landed, a recovery code that is not shown is a lock nobody holds, so two uncertain endings are
 * handled rather than reported as failures:
 *
 *   - the create itself got no answer this module can trust (the request failed, or a proxy
 *     answered 502 or 504 after passing it on): the key document is read again, and if it now holds
 *     a locked key that opens with the passphrase to the master that was sent, the create landed and
 *     this is 'stored'; anything else is 'failed', and whether something was stored is left to the
 *     next read;
 *   - the create was taken (201) and the read-back could not be read (the adapter asks again after a
 *     429, a 5xx or a failed request): 'storedUnread', with the code, and no identity, because nothing
 *     was read back to open.
 *
 * 'unchecked' is kept for the one case it names: a read-back that was read and does not open, with
 * the passphrase, to the same master.
 */
async function store(
  ports: ServerKeyPorts,
  wrapped: RecoverableDataKey,
  against: CreateAgainst,
  passphrase: string,
  master: Uint8Array,
  recoveryCode: RecoveryCode,
): Promise<SetUpResult> {
  let created: 'created' | 'moved' | 'uncertain'
  try {
    created = await ports.create(wrapped, against)
  } catch {
    created = 'uncertain'
  }
  if (created === 'moved') return { kind: 'moved', now: await ports.read() }
  let back: KeyDocument | null
  try {
    back = await ports.read()
  } catch {
    back = null
  }
  if (!back) return created === 'created' ? { kind: 'storedUnread', recoveryCode, sent: wrapped } : { kind: 'failed' }
  let opened: Uint8Array | null = null
  try {
    if (back.kind === 'wrapped') opened = await unwrapWithPassphrase(back.wrapped, passphrase).catch(() => null)
    if (back.kind !== 'wrapped' || !opened || !same(opened, master)) return created === 'created' ? { kind: 'unchecked' } : { kind: 'failed' }
    return {
      kind: 'stored',
      recoveryCode,
      identity: ownerIdentityFromMaster(opened),
      lane: { syncKey: syncKeyFromMaster(opened), keyDocumentEtag: back.etag },
    }
  } finally {
    zeroizeDataKey(opened)
  }
}

/** What a later read of the key document says about a document this tab sent and could not read back. */
export type StoredCheck =
  /** The server holds exactly the document that was sent. `wrapped` is it, as read back, and `etag` its ETag. */
  | { kind: 'held'; wrapped: RecoverableDataKey; etag: string }
  /** The server answered with something else, which is `now`: what was sent is not what it serves. */
  | { kind: 'other'; now: KeyDocument }
  /** The server could not be read this time either. */
  | { kind: 'unread' }

/**
 * Read the key document again after a 'storedUnread', or after a new passphrase that was 'unread'
 * or 'unchecked', and compare it with what was sent. It needs no secret: a document that is byte
 * for byte the one this tab made opens as it did when it was made — a set-up's was opened to the
 * master before it was sent (opensToMaster), and a new passphrase's is dataKey.ts replacePassphrase()
 * of the master that was open. The owner console's door then opens a set-up's with the recovery code
 * still on screen to derive the identity, so the identity still comes from what was read back.
 */
export async function confirmStored(ports: ServerKeyPorts, sent: RecoverableDataKey): Promise<StoredCheck> {
  let back: KeyDocument
  try {
    back = await ports.read()
  } catch {
    return { kind: 'unread' }
  }
  return back.kind === 'wrapped' && JSON.stringify(back.wrapped) === JSON.stringify(sent)
    ? { kind: 'held', wrapped: back.wrapped, etag: back.etag }
    : { kind: 'other', now: back }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   A new passphrase, after the recovery code has opened the key.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

export type ReplaceFault = 'noPassphrase' | 'passphrasesDiffer'

export type ReplaceResult =
  /** Stored as the next version, read back, and opened with the new passphrase to the same master. */
  | { kind: 'written' }
  /** The server's 409: another device wrote a version first. What it holds now, read again. */
  | { kind: 'moved'; now: KeyDocument }
  /** Nothing was written. */
  | { kind: 'refused'; fault: ReplaceFault }
  /**
   * The version was taken, and what the server handed back did not open to the same master. `sent`
   * is the version as it was sent, for a later read to compare against (confirmStored).
   */
  | { kind: 'unchecked'; sent: RecoverableDataKey }
  /**
   * The version may be on the server — it was taken and could not be read back, or the answer to it
   * was lost — and nothing was read back: which passphrase opens what the server holds is not known
   * here. `sent` is as above.
   */
  | { kind: 'unread'; sent: RecoverableDataKey }

/**
 * Lock the open master under a new passphrase and store it as the next version (dataKey.ts
 * replacePassphrase(): the recovery slot is left alone, and the master does not move). The caller
 * keeps the master it opened, and wipes it; this does not.
 */
export async function replacePassphraseOnServer(
  ports: ServerKeyPorts,
  held: Extract<KeyDocument, { kind: 'wrapped' }>,
  master: Uint8Array,
  passphrase: string,
  repeated: string,
): Promise<ReplaceResult> {
  if (!passphrase) return { kind: 'refused', fault: 'noPassphrase' }
  if (repeated !== passphrase) return { kind: 'refused', fault: 'passphrasesDiffer' }
  const next = await replacePassphrase(held.wrapped, master, passphrase)
  let taken: 'written' | 'moved'
  try {
    taken = await ports.replace(held.version + 1, next)
  } catch {
    // No answer this module can trust (the request failed, or a proxy answered after passing it
    // on): the version may be on the server, and a later read says whether it is what was sent.
    return { kind: 'unread', sent: next }
  }
  if (taken === 'moved') return { kind: 'moved', now: await ports.read() }
  let back: KeyDocument
  try {
    back = await ports.read()
  } catch {
    return { kind: 'unread', sent: next }
  }
  if (back.kind !== 'wrapped') return { kind: 'unchecked', sent: next }
  // Opening is not enough: a lock made under this same passphrase over another master opens too.
  const opened = await unwrapWithPassphrase(back.wrapped, passphrase).catch(() => null)
  try {
    return opened && same(opened, master) ? { kind: 'written' } : { kind: 'unchecked', sent: next }
  } finally {
    zeroizeDataKey(opened)
  }
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The ports, over the sync client.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The ports as the real server answers them, through a SyncClient holding the owner's token.
 *
 * Every read that failed for a transient reason is asked again, a little later, a few times
 * (sync/paced.ts). Finding the newest snapshot is one request per lineage, and the server's default
 * allowance is five a second from one address, so an owner with several devices' lineages would
 * otherwise be refused an enrolment by the pace of this console's own reads. The read-back after a
 * create is asked again the same way, because a code whose lock was taken is shown whatever the
 * read-back does, and a read that succeeds on the second try saves the person a check of their own
 * (#258).
 *
 * The newest snapshot is looked for among snapshots only: a lineage that names a web console's lane
 * (lane/lineage.ts) is sealed under the lane's own associated data, never opens as a snapshot, and
 * would make the right passphrase fail the enrolment anchor (#345).
 */
export function serverKeyPorts(client: SyncClient, wait: Wait = pause): ServerKeyPorts {
  return {
    read: () => paced(() => client.getKeyDocument(), wait),
    create: (wrapped, against) => client.createKeyDocument(wrapped, against),
    replace: (version, wrapped) => client.putKeyDocumentVersion(version, wrapped),
    async newestSnapshot() {
      let newest: (SnapshotRef & { createdAt: number }) | null = null
      for (const lineage of await paced(() => client.listLineages(), wait)) {
        if (isLaneLineage(lineage)) continue
        for (const v of await paced(() => client.listVersions(lineage), wait)) {
          const later = !newest || v.createdAt > newest.createdAt || (v.createdAt === newest.createdAt && v.version > newest.version)
          if (later) newest = { lineage, version: v.version, createdAt: v.createdAt }
        }
      }
      return newest && { lineage: newest.lineage, version: newest.version }
    },
    snapshot: (ref) => paced(() => client.getBlob(ref.lineage, ref.version), wait),
  }
}
