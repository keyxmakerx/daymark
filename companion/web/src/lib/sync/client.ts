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

type FetchLike = typeof fetch

export class SyncError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message)
  }
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
      throw new SyncError('server returned weak/unknown KDF parameters — refusing to derive a key')
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

  async getKeyParams(): Promise<KeyParams | null> {
    const res = await this.req('/v1/keyparams')
    if (res.status === 404) return null
    if (!res.ok) throw new SyncError(`keyparams fetch failed`, res.status)
    return (await res.json()) as KeyParams
  }

  async putKeyParams(kp: KeyParams): Promise<void> {
    const res = await this.req('/v1/keyparams', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(kp),
    })
    if (!res.ok) throw new SyncError('keyparams store failed', res.status)
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

  /** Get existing key params or create+publish them (writer only — needs the passphrase). */
  async ensureKeys(passphrase: string): Promise<OwnerKeys> {
    await initCrypto()
    const existing = await this.getKeyParams()
    if (existing) return this.derive(passphrase, existing.saltB64, existing.kdf)
    const saltB64 = toBase64(newSalt())
    await this.putKeyParams({ v: 1, alg: 'xchacha20poly1305', kdf: DEFAULT_KDF, saltB64 })
    return this.derive(passphrase, saltB64, DEFAULT_KDF)
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

  /** Fetch + decrypt the highest version of a lineage. Throws if no keyparams/snapshots. */
  async pullLatest(lineage: string, passphrase: string): Promise<{ version: number; plaintext: Uint8Array }> {
    await initCrypto()
    const kp = await this.getKeyParams()
    if (!kp) throw new SyncError('no key parameters on server — nothing has been synced yet')
    const keys = this.derive(passphrase, kp.saltB64, kp.kdf)
    const versions = await this.listVersions(lineage)
    if (versions.length === 0) throw new SyncError(`no snapshots for lineage "${lineage}"`)
    const head = versions.reduce((a, b) => (b.version > a.version ? b : a))
    const blob = await this.getBlob(lineage, head.version)
    const plaintext = decryptSnapshot(blob, keys.syncKey, lineage, head.version)
    return { version: head.version, plaintext }
  }
}
