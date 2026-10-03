/*
 * THE OWNER'S PHONE ROUTES (#431), with the access token the sync card proved.
 *
 * Six routes of the server's DeviceRoutes.kt, called the way the console calls every owner route:
 * `Authorization: Bearer <token>`, on the address the person typed (blank is this page's own server).
 *
 *   GET  /v1/devices                         every phone paired to this owner, disconnected ones too
 *   POST /v1/devices/pairing                 a code, good for two minutes and one phone; 409 on http
 *   GET  /v1/devices/pairing/{codeId}        where that code stands: waiting, redeemed or registered
 *   POST /v1/devices/pairing/{codeId}/confirm   {keyId}: the one thing that registers a phone's key
 *   POST /v1/devices/{keyId}/revoke          disconnect a phone from its next request on
 *
 * NEVER THE SERVER'S TEXT. An answer is read by its status and, when it succeeded, by the fields it
 * must have; a refusal's body is never read at all. What the section says about a refusal comes from
 * copy.ts, keyed by one of four fault kinds, so there is no path by which a server's sentence could
 * reach the page.
 *
 *   unreachable  no answer, a 5xx (a proxy that cannot reach the server says so with one), or an
 *                answer that is not what the route returns, such as a captive portal's page
 *   refused      401, 403, 429, or any other refusal of the request
 *   http         409 from minting: the server's address is not https, so it makes no code
 *   gone         404 for a code or its confirmation: the code, or the time to confirm, has run out
 */

/** One phone as the list route reports it. The server keeps no name for a phone, only its key. */
export interface DeviceRecord {
  keyId: string
  publicKey: string
  pairedAt: number
  revokedAt: number | null
}

/** A minted code: [code] is shown once, [baseUrl] is the server's https address. */
export interface MintedCode {
  codeId: string
  code: string
  baseUrl: string
  expiresAt: number
}

/** Where a code stands. Times are the server's clock, in milliseconds. */
export type CodeView =
  | { state: 'waiting'; expiresAt: number }
  | { state: 'redeemed'; keyId: string; publicKey: string; confirmBy: number }
  | { state: 'registered'; keyId: string; pairedAt: number }

export interface Registered {
  keyId: string
  pairedAt: number
}

export type FaultKind = 'unreachable' | 'refused' | 'http' | 'gone'

/** Why a call did not give what it asks for. Carries a kind and nothing the server said. */
export class PhonesFault extends Error {
  constructor(readonly kind: FaultKind) {
    super(kind)
  }
}

export interface DevicesApi {
  listDevices(): Promise<DeviceRecord[]>
  mintCode(): Promise<MintedCode>
  codeState(codeId: string): Promise<CodeView>
  confirm(codeId: string, keyId: string): Promise<Registered>
  revoke(keyId: string): Promise<void>
}

type FetchLike = typeof fetch

const isRecord = (x: unknown): x is Record<string, unknown> => typeof x === 'object' && x !== null && !Array.isArray(x)
const isText = (x: unknown): x is string => typeof x === 'string' && x.length > 0
const isTime = (x: unknown): x is number => typeof x === 'number' && Number.isSafeInteger(x) && x >= 0

function asDevice(x: unknown): DeviceRecord | null {
  if (!isRecord(x) || !isText(x.keyId) || !isText(x.publicKey) || !isTime(x.pairedAt)) return null
  if (x.revokedAt !== undefined && x.revokedAt !== null && !isTime(x.revokedAt)) return null
  return { keyId: x.keyId, publicKey: x.publicKey, pairedAt: x.pairedAt, revokedAt: isTime(x.revokedAt) ? x.revokedAt : null }
}

function asCodeView(x: unknown): CodeView | null {
  if (!isRecord(x)) return null
  if (x.state === 'waiting' && isTime(x.expiresAt)) return { state: 'waiting', expiresAt: x.expiresAt }
  if (x.state === 'redeemed' && isText(x.keyId) && isText(x.publicKey) && isTime(x.confirmBy)) {
    return { state: 'redeemed', keyId: x.keyId, publicKey: x.publicKey, confirmBy: x.confirmBy }
  }
  if (x.state === 'registered' && isText(x.keyId) && isTime(x.pairedAt)) {
    return { state: 'registered', keyId: x.keyId, pairedAt: x.pairedAt }
  }
  return null
}

/** The fault a refusal is, by its status alone; [gone] is what a 404 means on this route. */
function faultOf(status: number, gone: FaultKind = 'refused'): PhonesFault {
  if (status >= 500) return new PhonesFault('unreachable')
  if (status === 404) return new PhonesFault(gone)
  return new PhonesFault('refused')
}

/** The owner's phone routes on [serverUrl] ('' for this page's own server), with [token]. */
export function devicesApi(serverUrl: string, token: string, doFetch: FetchLike = fetch.bind(globalThis)): DevicesApi {
  const base = serverUrl.trim().replace(/\/+$/, '')

  /** One request; a request that gets no answer at all is `unreachable`. Never cached. */
  async function send(method: 'GET' | 'POST', path: string, body?: unknown): Promise<Response> {
    const headers: Record<string, string> = { Authorization: `Bearer ${token}` }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    try {
      return await doFetch(base + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        cache: 'no-store',
      })
    } catch {
      throw new PhonesFault('unreachable')
    }
  }

  /** The JSON of a success; an answer that is not JSON is not this server's, so `unreachable`. */
  async function json(res: Response): Promise<unknown> {
    try {
      return await res.json()
    } catch {
      throw new PhonesFault('unreachable')
    }
  }

  return {
    async listDevices() {
      const res = await send('GET', '/v1/devices')
      if (res.status !== 200) throw faultOf(res.status)
      const body = await json(res)
      const rows = isRecord(body) && Array.isArray(body.devices) ? body.devices.map(asDevice) : null
      if (!rows || rows.some((r) => r === null)) throw new PhonesFault('unreachable')
      return rows as DeviceRecord[]
    },

    async mintCode() {
      const res = await send('POST', '/v1/devices/pairing')
      if (res.status === 409) throw new PhonesFault('http')
      if (res.status !== 201) throw faultOf(res.status)
      const body = await json(res)
      if (!isRecord(body) || !isText(body.codeId) || !isText(body.code) || !isText(body.baseUrl) || !isTime(body.expiresAt)) {
        throw new PhonesFault('unreachable')
      }
      return { codeId: body.codeId, code: body.code, baseUrl: body.baseUrl, expiresAt: body.expiresAt }
    },

    async codeState(codeId) {
      const res = await send('GET', `/v1/devices/pairing/${encodeURIComponent(codeId)}`)
      if (res.status !== 200) throw faultOf(res.status, 'gone')
      const view = asCodeView(await json(res))
      if (!view) throw new PhonesFault('unreachable')
      return view
    },

    async confirm(codeId, keyId) {
      const res = await send('POST', `/v1/devices/pairing/${encodeURIComponent(codeId)}/confirm`, { keyId })
      if (res.status !== 200 && res.status !== 201) throw faultOf(res.status, 'gone')
      const body = await json(res)
      if (!isRecord(body) || !isText(body.keyId) || !isTime(body.pairedAt)) throw new PhonesFault('unreachable')
      return { keyId: body.keyId, pairedAt: body.pairedAt }
    },

    async revoke(keyId) {
      const res = await send('POST', `/v1/devices/${encodeURIComponent(keyId)}/revoke`)
      if (res.status !== 204) throw faultOf(res.status)
    },
  }
}
