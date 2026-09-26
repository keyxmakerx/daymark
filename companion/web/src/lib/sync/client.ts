/*
 * SyncClient — talks to the Companion /v1 API and applies the owner crypto.
 *
 * Used by the browser portal (read path), the reference CLI writer, and the integration
 * test. The server is zero-knowledge: this client encrypts before PUT and decrypts after
 * GET; the server only ever holds opaque bytes.
 *
 * THE SIZE LIMIT AND PADDING (#315). Snapshots are padded before they are encrypted, so a padded
 * snapshot can be larger than the server's blob limit where the unpadded one would have fitted:
 * at the default 25 MiB limit that is any plaintext from 25,690,109 to 26,214,355 bytes. Such a
 * snapshot is never sent unpadded instead. pushSnapshot checks the padded size against the limit
 * it was given BEFORE it derives a key or makes a request, and refuses with fixed words that say
 * nothing was sent, both sizes, and the limit. The limit defaults to the server's own default
 * (DEFAULT_MAX_BLOB_BYTES); the server's actual limit is its operator's setting, which this client
 * cannot see, so a server that refuses anyway (413) is reported as the server's answer, with the
 * same two sizes.
 *
 * THE KEY IS READ FROM THE KEY DOCUMENT (#258; docs/SYNC_PROTOCOL.md §2 and §3). Both flows here —
 * the reader's pull and the writer's push — learn how to reach the master from `GET /v1/keydoc`,
 * which answers with the owner's wrapped key when one exists and with the key parameters
 * otherwise. A wrapped key is opened with the passphrase slot; key parameters are derived from as
 * they always were. Nothing here reads `/v1/keyparams`: once a wrapped key exists the server
 * answers it 410, and a reader that still asked there would stop at a key the owner has. The one
 * request left on that path is the writer's create-only PUT when the server holds no key document
 * at all; a 409 or 410 to it means another writer got there first, and the writer reads the key
 * document again and uses what is there.
 */
import {
  initCrypto,
  deriveKeys,
  newSalt,
  encryptSnapshot,
  decryptSnapshot,
  snapshotBlobLength,
  unpaddedSnapshotBlobLength,
  toBase64,
  fromBase64,
  DEFAULT_KDF,
  type KdfParams,
  type OwnerKeys,
} from './crypto'
import { DataKeyError, unwrapWithPassphrase, zeroizeDataKey, type RecoverableDataKey } from '../recovery/dataKey'
import { subkeysFromMaster } from '../recovery/migration'

/**
 * The largest blob a Daymark server stores unless its operator sets DAYMARK_MAX_BLOB_BYTES
 * (docs/COMPANION_DEPLOYMENT.md). A writer whose server accepts more is given that number instead.
 */
export const DEFAULT_MAX_BLOB_BYTES = 26_214_400

export interface KeyParams {
  v: 1
  alg: 'xchacha20poly1305'
  kdf: KdfParams
  saltB64: string
}

export interface SnapshotMeta {
  version: number
  size: number
  contentHash: string
  createdAt: number
}

/**
 * What `GET /v1/keydoc` answered: nothing yet, the key parameters, or the newest wrapped key.
 *
 * `etag` is kept exactly as the server sent it, quotes included, because an enrolment names the key
 * parameters it wrapped by sending that value back unchanged (docs/SYNC_PROTOCOL.md §2).
 */
export type KeyDocument =
  | { kind: 'none' }
  | { kind: 'keyparams'; params: KeyParams; etag: string }
  | { kind: 'wrapped'; wrapped: RecoverableDataKey; version: number; etag: string }

/**
 * The state a create of the wrapped key is made against: a first run, which the server takes only
 * while it holds no key document of either kind, or an enrolment of the key parameters whose
 * `ETag` this is.
 */
export type CreateAgainst = { kind: 'firstRun' } | { kind: 'enrolment'; etag: string }

type FetchLike = typeof fetch

export class SyncError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
  }
}

/** The refusal when the passphrase does not open the passphrase slot of the server's wrapped key. */
export const PASSPHRASE_DOES_NOT_OPEN_KEY = 'That passphrase does not open the key this server holds.'

/** A server that asks which state a create was made against is answering a fault in this client. */
const CREATE_NAMED_NO_STATE =
  'the server answered 428: this create named no state it was made against, which this client always names'

/** The one refusal for KDF parameters under the floor, wherever on the server they came from. */
const WEAK_KDF = 'server returned weak/unknown KDF parameters — refusing to derive a key'

const isRecord = (x: unknown): x is Record<string, unknown> => typeof x === 'object' && x !== null && !Array.isArray(x)

/** The shape of key parameters, and nothing more: the KDF floor is checked where the key is derived. */
function isKeyParams(x: unknown): x is KeyParams {
  return isRecord(x) && x.v === 1 && isRecord(x.kdf) && typeof x.saltB64 === 'string'
}

/** The shape of a wrapped key, and nothing more: dataKey.ts checks every slot again on every use. */
function isWrappedKey(x: unknown): x is RecoverableDataKey {
  return (
    isRecord(x) &&
    x.v === 1 &&
    Array.isArray(x.slots) &&
    x.slots.every(
      (s) =>
        isRecord(s) &&
        typeof s.kind === 'string' &&
        isRecord(s.kdf) &&
        typeof s.saltB64 === 'string' &&
        typeof s.nonceB64 === 'string' &&
        typeof s.ctB64 === 'string',
    )
  )
}

/**
 * A 200 from `GET /v1/keydoc`, read as the kind `X-Key-Document` names and checked as that kind,
 * because the server vouches for neither (docs/SYNC_PROTOCOL.md §2). Anything this client cannot
 * read as what it claims to be is refused rather than guessed at.
 */
export function parseKeyDocument(headers: Headers, body: string): KeyDocument {
  const kind = headers.get('X-Key-Document')
  const etag = headers.get('ETag')
  if (!etag) throw new SyncError('the server sent a key document without an ETag')
  let parsed: unknown
  try {
    parsed = JSON.parse(body)
  } catch {
    throw new SyncError('the server sent a key document that is not JSON')
  }
  if (kind === 'keyparams') {
    if (!isKeyParams(parsed)) throw new SyncError('the server sent key parameters this client cannot read')
    return { kind: 'keyparams', params: parsed, etag }
  }
  if (kind === 'wrapped') {
    const version = headers.get('X-Key-Document-Version') ?? ''
    if (!/^[1-9][0-9]{0,15}$/.test(version) || !Number.isSafeInteger(Number(version))) {
      throw new SyncError('the server sent a wrapped key without a version')
    }
    if (!isWrappedKey(parsed)) throw new SyncError('the server sent a wrapped key this client cannot read')
    return { kind: 'wrapped', wrapped: parsed, version: Number(version), etag }
  }
  throw new SyncError('the server sent a key document of a kind this client does not know')
}

/**
 * A snapshot that was not stored because of its size. `limitBytes` is the limit this client
 * refused it against before sending anything, or null when the server refused it (status 413).
 */
export class SnapshotTooLargeError extends SyncError {
  constructor(
    message: string,
    readonly paddedBytes: number,
    readonly unpaddedBytes: number,
    readonly limitBytes: number | null,
    status?: number,
  ) {
    super(message, status)
  }
}

/** 26214400 → "26,214,400", so a size can be read at a glance and compared with a setting. */
function bytesText(n: number): string {
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/**
 * The refusal when a padded snapshot is over this client's limit. Fixed words with the sizes
 * slotted in: the consequence first, then only what this client knows — the padded size, the
 * unpadded size and the limit it was given. Whether padding is what took it over is said, because
 * this client knows it; what the server would have done is never said, because the server's limit
 * is its operator's setting.
 */
export function snapshotTooLargeText(paddedBytes: number, unpaddedBytes: number, limitBytes: number): string {
  const head =
    `Nothing was sent. Padded, this snapshot is ${bytesText(paddedBytes)} bytes, and the most ` +
    `this writer sends is ${bytesText(limitBytes)}.`
  if (unpaddedBytes <= limitBytes) {
    return (
      `${head} Unpadded it would have been ${bytesText(unpaddedBytes)} bytes, but snapshots are ` +
      'always padded before they are encrypted, so that the server learns only roughly how big ' +
      'each one is.'
    )
  }
  return `${head} Unpadded it would have been ${bytesText(unpaddedBytes)} bytes, which is more than that too.`
}

/** The refusal when the server answers 413 to a snapshot this client did send. */
export function snapshotRefusedText(paddedBytes: number, unpaddedBytes: number): string {
  return (
    'Nothing was stored. The server answered that this snapshot is larger than it accepts. ' +
    `Padded, it is ${bytesText(paddedBytes)} bytes; unpadded it would have been ${bytesText(unpaddedBytes)}.`
  )
}

export interface SyncClientOptions {
  /** The largest snapshot blob this client sends. Defaults to DEFAULT_MAX_BLOB_BYTES. */
  maxBlobBytes?: number
}

export class SyncClient {
  private readonly base: string
  private readonly maxBlobBytes: number
  // Cache the (expensive, ≥256 MiB) Argon2id derivation per passphrase+salt within this instance.
  private keyCache: { tag: string; keys: OwnerKeys } | null = null
  constructor(
    baseUrl: string,
    private readonly token: string,
    private readonly doFetch: FetchLike = fetch.bind(globalThis),
    options: SyncClientOptions = {},
  ) {
    this.base = baseUrl.replace(/\/+$/, '')
    const max = options.maxBlobBytes ?? DEFAULT_MAX_BLOB_BYTES
    if (!Number.isSafeInteger(max) || max < 1) throw new RangeError(`maxBlobBytes must be a positive whole number, not ${max}`)
    this.maxBlobBytes = max
  }

  /** Reject server-supplied KDF params below the security-doc floor (downgrade defense). */
  private validateKdf(params: KdfParams) {
    if (params.alg !== 'argon2id' || params.memMiB < 256 || params.ops < 3) {
      throw new SyncError(WEAK_KDF)
    }
  }

  private derive(passphrase: string, saltB64: string, params: KdfParams): OwnerKeys {
    this.validateKdf(params)
    const tag = `${saltB64}|${params.memMiB}|${params.ops}|${passphrase}`
    if (this.keyCache?.tag === tag) return this.keyCache.keys
    const keys = deriveKeys(passphrase, fromBase64(saltB64), params)
    this.keyCache = { tag, keys }
    return keys
  }

  private headers(extra: Record<string, string> = {}): Record<string, string> {
    return { Authorization: `Bearer ${this.token}`, ...extra }
  }

  private async req(path: string, init: RequestInit = {}): Promise<Response> {
    const res = await this.doFetch(this.base + path, { ...init, headers: { ...this.headers(), ...(init.headers as Record<string, string>) } })
    return res
  }

  // --- raw API ---

  /** The owner's key document: the newest wrapped key, else the key parameters, else nothing. */
  async getKeyDocument(): Promise<KeyDocument> {
    const res = await this.req('/v1/keydoc')
    if (res.status === 404) return { kind: 'none' }
    if (!res.ok) throw new SyncError('key document fetch failed', res.status)
    return parseKeyDocument(res.headers, await res.text())
  }

  /**
   * Create version 1 of the wrapped key against the state this client read (docs/SYNC_PROTOCOL.md
   * §2): `If-None-Match: *` for a first run, `If-Match` with the key parameters' ETag, exactly as
   * received, for an enrolment. Exactly one of the two, always, so the server never has to ask
   * (428). 'moved' is the server's 412: the key documents are no longer the ones read, so the
   * caller reads them again and acts on what is there now.
   */
  async createKeyDocument(wrapped: RecoverableDataKey, against: CreateAgainst): Promise<'created' | 'moved'> {
    const precondition: Record<string, string> =
      against.kind === 'firstRun' ? { 'If-None-Match': '*' } : { 'If-Match': against.etag }
    const res = await this.req('/v1/keydoc', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...precondition },
      body: JSON.stringify(wrapped),
    })
    if (res.status === 201) return 'created'
    if (res.status === 412) return 'moved'
    if (res.status === 428) throw new SyncError(CREATE_NAMED_NO_STATE, 428)
    throw new SyncError('key document store failed', res.status)
  }

  /**
   * Store the next version of the wrapped key: a new passphrase or a new recovery code. `version`
   * is the one this write makes, so it is taken only while `version - 1` is the newest; 'moved' is
   * the server's 409, another device having written first.
   */
  async putKeyDocumentVersion(version: number, wrapped: RecoverableDataKey): Promise<'written' | 'moved'> {
    if (!Number.isSafeInteger(version) || version < 2) throw new RangeError(`a new version of the wrapped key is 2 or more, not ${version}`)
    const res = await this.req(`/v1/keydoc/${version}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(wrapped),
    })
    if (res.status === 201) return 'written'
    if (res.status === 409) return 'moved'
    throw new SyncError('key document store failed', res.status)
  }

  /**
   * Publish the key parameters. Create-only on the server: false when another writer published
   * first (409) or a wrapped key already supersedes them (410), and the caller reads again.
   */
  async publishKeyParams(kp: KeyParams): Promise<boolean> {
    const res = await this.req('/v1/keyparams', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(kp),
    })
    if (res.ok) return true
    if (res.status === 409 || res.status === 410) return false
    throw new SyncError('keyparams store failed', res.status)
  }

  async listLineages(): Promise<string[]> {
    const res = await this.req('/v1/snapshots')
    if (!res.ok) throw new SyncError('list lineages failed', res.status)
    return ((await res.json()) as { lineages: string[] }).lineages
  }

  async listVersions(lineage: string): Promise<SnapshotMeta[]> {
    const res = await this.req(`/v1/snapshots/${encodeURIComponent(lineage)}`)
    if (!res.ok) throw new SyncError('list versions failed', res.status)
    return ((await res.json()) as { versions: SnapshotMeta[] }).versions
  }

  async putBlob(lineage: string, version: number, bytes: Uint8Array): Promise<SnapshotMeta> {
    const res = await this.req(`/v1/snapshots/${encodeURIComponent(lineage)}/${version}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/octet-stream' },
      // Uint8Array is a valid fetch body at runtime in both the browser and Node 18+.
      body: bytes as unknown as BodyInit,
    })
    if (res.status === 409) throw new SyncError('version already exists (append-only)', 409)
    if (!res.ok) throw new SyncError('blob store failed', res.status)
    return (await res.json()) as SnapshotMeta
  }

  async getBlob(lineage: string, version: number): Promise<Uint8Array> {
    const res = await this.req(`/v1/snapshots/${encodeURIComponent(lineage)}/${version}`)
    if (!res.ok) throw new SyncError('blob fetch failed', res.status)
    return new Uint8Array(await res.arrayBuffer())
  }

  // --- high-level (crypto applied) ---

  /**
   * The subkeys a key document and a passphrase reach: derived from key parameters as they always
   * were, or opened from the passphrase slot of a wrapped key. The master is wiped as soon as its
   * two subkeys exist.
   */
  private async keysFrom(doc: Exclude<KeyDocument, { kind: 'none' }>, passphrase: string): Promise<OwnerKeys> {
    if (doc.kind === 'keyparams') return this.derive(passphrase, doc.params.saltB64, doc.params.kdf)
    const tag = `wrapped|${doc.etag}|${passphrase}`
    if (this.keyCache?.tag === tag) return this.keyCache.keys
    let master: Uint8Array
    try {
      master = await unwrapWithPassphrase(doc.wrapped, passphrase)
    } catch (e) {
      if (e instanceof DataKeyError && /below the security floor/.test(e.message)) throw new SyncError(WEAK_KDF)
      throw new SyncError(PASSPHRASE_DOES_NOT_OPEN_KEY)
    }
    try {
      const keys = subkeysFromMaster(master)
      this.keyCache = { tag, keys }
      return keys
    } finally {
      zeroizeDataKey(master)
    }
  }

  /**
   * The writer's keys: from the key document, or, when the server holds none, from key parameters
   * this writer publishes. The publish is create-only, so when another writer's key parameters or a
   * wrapped key got there first, this reads the key document again and uses what is there.
   */
  async ensureKeys(passphrase: string): Promise<OwnerKeys> {
    await initCrypto()
    const doc = await this.getKeyDocument()
    if (doc.kind !== 'none') return this.keysFrom(doc, passphrase)
    const saltB64 = toBase64(newSalt())
    if (await this.publishKeyParams({ v: 1, alg: 'xchacha20poly1305', kdf: DEFAULT_KDF, saltB64 })) {
      return this.derive(passphrase, saltB64, DEFAULT_KDF)
    }
    const now = await this.getKeyDocument()
    if (now.kind === 'none') throw new SyncError('the server refused the key parameters and holds no key document')
    return this.keysFrom(now, passphrase)
  }

  /**
   * Encrypt a plaintext snapshot, padded, and PUT it as the given append-only version. Refuses a
   * snapshot whose padded blob is over this client's limit before anything else happens, so the
   * refusal's "nothing was sent" holds even for the key parameters (see the header).
   */
  async pushSnapshot(lineage: string, version: number, plaintext: Uint8Array, passphrase: string): Promise<SnapshotMeta> {
    this.assertSnapshotFits(plaintext.length)
    const keys = await this.ensureKeys(passphrase)
    const blob = encryptSnapshot(plaintext, keys.syncKey, lineage, version)
    try {
      return await this.putBlob(lineage, version, blob)
    } catch (e) {
      if (e instanceof SyncError && e.status === 413) {
        const unpaddedBytes = unpaddedSnapshotBlobLength(plaintext.length)
        throw new SnapshotTooLargeError(snapshotRefusedText(blob.length, unpaddedBytes), blob.length, unpaddedBytes, null, 413)
      }
      throw e
    }
  }

  /**
   * Throws SnapshotTooLargeError when a snapshot of this many plaintext bytes, padded, is over this
   * client's limit. Makes no request, so a caller can check before it contacts the server at all.
   */
  assertSnapshotFits(plaintextLength: number): void {
    const paddedBytes = snapshotBlobLength(plaintextLength)
    if (paddedBytes <= this.maxBlobBytes) return
    const unpaddedBytes = unpaddedSnapshotBlobLength(plaintextLength)
    throw new SnapshotTooLargeError(
      snapshotTooLargeText(paddedBytes, unpaddedBytes, this.maxBlobBytes),
      paddedBytes,
      unpaddedBytes,
      this.maxBlobBytes,
    )
  }

  /** Fetch + decrypt the highest version of a lineage. Throws if no key document/snapshots. */
  async pullLatest(lineage: string, passphrase: string): Promise<{ version: number; plaintext: Uint8Array }> {
    await initCrypto()
    const doc = await this.getKeyDocument()
    if (doc.kind === 'none') throw new SyncError('no key parameters on server — nothing has been synced yet')
    const keys = await this.keysFrom(doc, passphrase)
    const versions = await this.listVersions(lineage)
    if (versions.length === 0) throw new SyncError(`no snapshots for lineage "${lineage}"`)
    const head = versions.reduce((a, b) => (b.version > a.version ? b : a))
    const blob = await this.getBlob(lineage, head.version)
    const plaintext = decryptSnapshot(blob, keys.syncKey, lineage, head.version)
    return { version: head.version, plaintext }
  }
}
