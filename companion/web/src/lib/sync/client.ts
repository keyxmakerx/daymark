/*
 * SyncClient — talks to the Companion /v1 API and applies the owner crypto.
 *
 * Used by the browser portal (read path), the reference CLI writer, and the integration
 * test. The server is zero-knowledge: this client encrypts before PUT and decrypts after
 * GET; the server only ever holds opaque bytes.
 */
import {
  initCrypto,
  deriveKeys,
  newSalt,
  encryptSnapshot,
  decryptSnapshot,
  toBase64,
  fromBase64,
  DEFAULT_KDF,
  type KdfParams,
  type OwnerKeys,
} from './crypto'

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

export class SyncClient {
  private readonly base: string
  // Cache the (expensive, ≥256 MiB) Argon2id derivation per passphrase+salt within this instance.
  private keyCache: { tag: string; keys: OwnerKeys } | null = null
  constructor(
    baseUrl: string,
    private readonly token: string,
    private readonly doFetch: FetchLike = fetch,
  ) {
    this.base = baseUrl.replace(/\/+$/, '')
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

  /** Encrypt a plaintext snapshot and PUT it as the given append-only version. */
  async pushSnapshot(lineage: string, version: number, plaintext: Uint8Array, passphrase: string): Promise<SnapshotMeta> {
    const keys = await this.ensureKeys(passphrase)
    const blob = encryptSnapshot(plaintext, keys.syncKey, lineage, version)
    return this.putBlob(lineage, version, blob)
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
