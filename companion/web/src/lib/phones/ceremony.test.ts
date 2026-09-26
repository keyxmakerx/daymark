import { describe, it, expect, beforeAll } from 'vitest'
import {
  CODE_TTL_MS,
  back,
  discard,
  expire,
  initialState,
  isPairingCode,
  loadList,
  mint,
  pairThisPhone,
  pairingQrText,
  phonesView,
  poll,
  secondsLeft,
  tryAgain,
  wordsDontMatch,
  type PhonesPorts,
  type PhonesState,
  type PhonesView,
} from './ceremony'
import { PhonesFault, devicesApi, type CodeView, type DeviceRecord, type DevicesApi, type MintedCode } from './devices'
import { deviceKeyId, deviceWords } from './words'
import * as COPY from './copy'

/*
 * The owner's side of pairing a phone, as ORDER and as words (#431). Every port call is recorded, so
 * "calls nothing" is a statement about the list of calls, and every state's words are pinned here
 * exactly as the decided design gives them.
 */

const KEY_A = 'JUO5L_EJVRFHatyDadtt3JM2ZaEZeN2hQE7hBmypVZ0'
const WORDS_A = ['hotel', 'cedar', 'earth', 'coral', 'nacho', 'hotel']
const KEY_B = 'A'.repeat(43)
let ID_A = ''
let ID_B = ''

beforeAll(async () => {
  ID_A = (await deviceKeyId(KEY_A))!
  ID_B = (await deviceKeyId(KEY_B))!
})

const SERVER_NOW = 1_800_000_000_000
const ADDRESS = 'https://daymark.example.org'
const CODE = 'K7M4RD96QA'
const CODE_ID = 'code-id-1'

/** Ports with a scripted server. `calls` records every request in order; the clock is `t`. */
function harness(script: Partial<Record<keyof DevicesApi, (...args: never[]) => unknown>> = {}) {
  const calls: string[] = []
  const clock = { t: 5_000 }
  const scripted = <T>(name: keyof DevicesApi, fallback: () => T) =>
    (async (...args: unknown[]) => {
      calls.push(args.length ? `${name} ${args.join(' ')}` : name)
      const f = script[name] as ((...a: unknown[]) => T) | undefined
      return f ? f(...args) : fallback()
    }) as never
  const api: DevicesApi = {
    listDevices: scripted<DeviceRecord[]>('listDevices', () => []),
    mintCode: scripted<MintedCode>('mintCode', () => ({ codeId: CODE_ID, code: CODE, baseUrl: ADDRESS, expiresAt: SERVER_NOW + CODE_TTL_MS })),
    codeState: scripted<CodeView>('codeState', () => ({ state: 'waiting', expiresAt: SERVER_NOW + CODE_TTL_MS })),
    confirm: scripted('confirm', () => ({ keyId: ID_A, pairedAt: SERVER_NOW + 30_000 })),
    revoke: scripted('revoke', () => undefined),
  }
  const ports: PhonesPorts = { api, words: deviceWords, keyIdOf: deviceKeyId, now: () => clock.t }
  return { ports, calls, clock }
}

const redeemedBy = (publicKey: string, keyId: string, confirmBy = SERVER_NOW + 40_000 + 120_000): (() => CodeView) =>
  () => ({ state: 'redeemed', keyId, publicKey, confirmBy })

/** Ready, with the list read and empty. */
async function ready(h: ReturnType<typeof harness>): Promise<PhonesState> {
  return loadList(h.ports, initialState(true))
}

/** A code on screen, minted at this browser's t = 5000. */
async function codeShown(h: ReturnType<typeof harness>): Promise<PhonesState> {
  return mint(h.ports, await ready(h))
}

/** Every string a view could put on screen, for "nothing else reached it". */
const allText = (v: PhonesView) => JSON.stringify(v)

describe('the words of every state, verbatim', () => {
  it('not connected: the one sentence, and nothing to press', () => {
    const v = phonesView(initialState(false), 0)
    expect(v.heading).toBe('Phones')
    expect(v.lede).toBe('Connect to this server above to pair a phone or see the phones already paired.')
    expect(v.pairButton).toBeNull()
    expect(v.area).toBeNull()
    expect(v.rows).toBeNull()
    expect(v.notice).toBeNull()
  })

  it('ready: the lede and "Pair a phone"', async () => {
    const h = harness()
    const v = phonesView(await ready(h), 0)
    expect(v.lede).toBe('A paired phone syncs your journal to this server and signs each request with its own key.')
    expect(v.pairButton).toBe('Pair a phone')
    expect(v.area).toBeNull()
  })

  it('an http server: the sentence, and no button (from the 409)', async () => {
    const h = harness({ mintCode: () => { throw new PhonesFault('http') } })
    const s = await mint(h.ports, await ready(h))
    const v = phonesView(s, 0)
    expect(v.lede).toBe(
      "This server's address begins with http://, so it cannot make a pairing code. Phones pair only over https. Everything else here works as it does.",
    )
    expect(v.pairButton).toBeNull()
    expect(v.area).toBeNull()
    // It stays that way for this connection: nothing offers a code again.
    expect(phonesView(back(s), 0).pairButton).toBeNull()
    expect((await mint(h.ports, s)).pairing.step).toBe('closed')
    expect(h.calls.filter((c) => c === 'mintCode')).toHaveLength(1)
  })

  it('a code on screen: the address, the code in two groups, the clock, and the way out', async () => {
    const h = harness()
    const s = await codeShown(h)
    const v = phonesView(s, h.clock.t + 8_000)
    expect(v.area).toEqual({
      kind: 'code',
      lede: 'On the phone, choose Pair with a server. Scan this, or type the server address and the code.',
      addressLabel: 'Server address',
      address: ADDRESS,
      codeLabel: 'Code',
      code: 'K7M4R-D96QA',
      qrText: 'daymark-pair:v1?server=https%3A%2F%2Fdaymark.example.org&code=K7M4RD96QA',
      timeLeft: { before: '', clock: '1:52', after: ' left · works once' },
      waiting: 'Waiting for a phone to use this code.',
      discard: 'Discard this code',
    })
    const shownArea = v.area!
    const line = shownArea.kind === 'code' ? shownArea.timeLeft : null
    expect(`${line!.before}${line!.clock}${line!.after}`).toBe('1:52 left · works once')
    expect(COPY.codeTimeLeft(112)).toBe('1:52 left · works once')
    // No "Pair a phone" beside a code already on screen.
    expect(v.pairButton).toBeNull()
  })

  it('announces once, politely, at thirty seconds left, and is silent before', async () => {
    const h = harness()
    const s = await codeShown(h)
    const deadline = s.pairing.step === 'waiting' ? s.pairing.deadline : 0
    expect(phonesView(s, deadline - 31_000).announcement).toBe('')
    expect(phonesView(s, deadline - 30_000).announcement).toBe('Thirty seconds left on this code.')
    expect(phonesView(s, deadline - 1_000).announcement).toBe('Thirty seconds left on this code.')
  })

  it('a phone used the code: its six words in order, the confirm window, and the two answers', async () => {
    const h = harness({ codeState: redeemedBy(KEY_A, '') })
    // The id comes from the key, so the harness answers with the real one.
    h.ports.api.codeState = async () => redeemedBy(KEY_A, ID_A, SERVER_NOW + 88_000)()
    const s = await poll(h.ports, await codeShown(h))
    const v = phonesView(s, h.clock.t)
    expect(v.area).toEqual({
      kind: 'compare',
      lede: 'A phone used the code. It shows six words. Read these six aloud and check them on the phone, in this order.',
      words: WORDS_A,
      within: { before: 'Confirm within ', clock: '1:28', after: '. If you do not, the phone is not paired and nothing is stored.' },
      pair: 'Pair this phone',
      mismatch: "The words don't match",
    })
    const compareArea = v.area!
    const w = compareArea.kind === 'compare' ? compareArea.within : null
    expect(`${w!.before}${w!.clock}${w!.after}`).toBe('Confirm within 1:28. If you do not, the phone is not paired and nothing is stored.')
    // The code is gone from the screen once a phone has used it.
    expect(allText(v)).not.toContain('K7M4R')
  })

  it('the words do not match: the sentence, and a new code or back', async () => {
    const h = harness()
    h.ports.api.codeState = async () => redeemedBy(KEY_A, ID_A)()
    const s = wordsDontMatch(await poll(h.ports, await codeShown(h)))
    expect(phonesView(s, 0).area).toEqual({
      kind: 'ended',
      sentence:
        'This phone was not paired. Nothing was stored. Different words mean the key that reached this server is not the one on the phone. Make a new code when you are ready.',
      actions: [
        { id: 'new-code', label: 'Make a new code' },
        { id: 'back', label: 'Back to phones' },
      ],
    })
  })

  it('paired: the area closes and one past-tense sentence stands above the list', async () => {
    const h = harness()
    h.ports.api.codeState = async () => redeemedBy(KEY_A, ID_A)()
    const s = await pairThisPhone(h.ports, await poll(h.ports, await codeShown(h)))
    const v = phonesView(s, 0)
    expect(v.notice).toBe('Paired a phone.')
    expect(v.area).toBeNull()
    expect(v.pairButton).toBe('Pair a phone')
    // For the rest of the visit: a new code does not take it away.
    expect(phonesView(await mint(h.ports, s), 0).notice).toBe('Paired a phone.')
  })

  it('lapsed, unconfirmed, unreachable and refused: each its sentence and its buttons', async () => {
    const h = harness()
    const shown = await codeShown(h)
    const ended = (s: PhonesState) => phonesView(s, 0).area
    h.ports.api.codeState = async () => { throw new PhonesFault('gone') }
    expect(ended(await poll(h.ports, shown))).toEqual({
      kind: 'ended',
      sentence: 'This code lapsed after two minutes. No phone used it.',
      actions: [
        { id: 'new-code', label: 'Make a new code' },
        { id: 'back', label: 'Back to phones' },
      ],
    })
    h.ports.api.codeState = async () => redeemedBy(KEY_A, ID_A)()
    const comparing = await poll(h.ports, shown)
    const deadline = comparing.pairing.step === 'compare' ? comparing.pairing.deadline : 0
    expect(ended(expire(comparing, deadline))).toEqual({
      kind: 'ended',
      sentence:
        'The time to confirm ran out. The phone was not paired and nothing was stored. The phone will need a new code.',
      actions: [
        { id: 'new-code', label: 'Make a new code' },
        { id: 'back', label: 'Back to phones' },
      ],
    })
    h.ports.api.codeState = async () => { throw new PhonesFault('unreachable') }
    expect(ended(await poll(h.ports, shown))).toEqual({
      kind: 'ended',
      sentence: 'This server could not be reached. Nothing changed.',
      actions: [
        { id: 'try-again', label: 'Try again' },
        { id: 'back', label: 'Back to phones' },
      ],
    })
    h.ports.api.codeState = async () => { throw new PhonesFault('refused') }
    expect(ended(await poll(h.ports, shown))).toEqual({
      kind: 'ended',
      sentence: 'This server refused the request. Nothing changed. Connect again above, then try once more.',
      actions: [{ id: 'back', label: 'Back to phones' }],
    })
  })
})

describe('the order of the ceremony', () => {
  it('mints, reads the code until a phone uses it, and confirms only on the click', async () => {
    const h = harness()
    let s = await codeShown(h)
    expect(h.calls).toEqual(['listDevices', 'mintCode'])
    s = await poll(h.ports, s)
    expect(s.pairing.step).toBe('waiting')
    h.ports.api.codeState = async (id: string) => {
      h.calls.push(`codeState ${id}`)
      return redeemedBy(KEY_A, ID_A)()
    }
    s = await poll(h.ports, s)
    expect(s.pairing.step).toBe('compare')
    // Showing the words confirmed nothing.
    expect(h.calls.some((c) => c.startsWith('confirm'))).toBe(false)
    s = await pairThisPhone(h.ports, s)
    expect(h.calls.at(-1)).toBe(`confirm ${CODE_ID} ${ID_A}`)
    expect(s.notice).toBe('paired')
  })

  it('confirms exactly the key id of the key whose words were shown', async () => {
    const h = harness()
    h.ports.api.codeState = async () => redeemedBy(KEY_A, ID_A)()
    const s = await poll(h.ports, await codeShown(h))
    expect(phonesView(s, 0).area).toMatchObject({ words: WORDS_A })
    const sent: string[] = []
    h.ports.api.confirm = async (_codeId: string, keyId: string) => {
      sent.push(keyId)
      return { keyId, pairedAt: SERVER_NOW }
    }
    await pairThisPhone(h.ports, s)
    expect(sent).toEqual([await deviceKeyId(KEY_A)])
    expect(ID_A).not.toBe(ID_B)
  })

  it('refuses a server that shows one key and names another, and confirms nothing', async () => {
    const h = harness()
    // KEY_A's words with KEY_B's id: the id is not the shown key's own.
    h.ports.api.codeState = async () => redeemedBy(KEY_A, ID_B)()
    const s = await poll(h.ports, await codeShown(h))
    expect(s.pairing.step).toBe('refused')
    expect(allText(phonesView(s, 0))).not.toContain('hotel')
    expect(await pairThisPhone(h.ports, s)).toBe(s)
    expect(h.calls.some((c) => c.startsWith('confirm'))).toBe(false)
    // Positive control: the same answer with the key's own id is shown for comparison.
    h.ports.api.codeState = async () => redeemedBy(KEY_A, ID_A)()
    expect((await poll(h.ports, await codeShown(h))).pairing.step).toBe('compare')
  })

  it('"The words don\'t match" calls nothing, and nothing polls after it', async () => {
    const h = harness()
    h.ports.api.codeState = async () => {
      h.calls.push('codeState')
      return redeemedBy(KEY_A, ID_A)()
    }
    const comparing = await poll(h.ports, await codeShown(h))
    const before = [...h.calls]
    const s = wordsDontMatch(comparing)
    expect(s.pairing.step).toBe('mismatch')
    expect(h.calls).toEqual(before)
    // The component polls only a code on screen; the module refuses to poll anything else.
    expect(await poll(h.ports, s)).toBe(s)
    expect(await pairThisPhone(h.ports, s)).toBe(s)
    expect(h.calls).toEqual(before)
    // Positive control: the recorder does see a call.
    await poll(h.ports, await codeShown(h))
    expect(h.calls.length).toBeGreaterThan(before.length)
  })

  it('"Discard this code" asks nothing and calls nothing, and returns to "Pair a phone"', async () => {
    const h = harness()
    const shown = await codeShown(h)
    const before = [...h.calls]
    const s = discard(shown)
    expect(h.calls).toEqual(before)
    expect(s.pairing.step).toBe('closed')
    expect(phonesView(s, 0).pairButton).toBe('Pair a phone')
    expect(await poll(h.ports, s)).toBe(s)
    expect(h.calls).toEqual(before)
  })

  it('"Back to phones" calls nothing', async () => {
    const h = harness({ codeState: () => { throw new PhonesFault('gone') } })
    const lapsed = await poll(h.ports, await codeShown(h))
    const before = [...h.calls]
    expect(back(lapsed).pairing.step).toBe('closed')
    expect(h.calls).toEqual(before)
  })

  it('the confirm window ending on this browser\'s clock ends the pairing, and calls nothing', async () => {
    const h = harness()
    h.ports.api.codeState = async () => redeemedBy(KEY_A, ID_A)()
    const s = await poll(h.ports, await codeShown(h))
    const before = [...h.calls]
    const deadline = s.pairing.step === 'compare' ? s.pairing.deadline : 0
    expect(expire(s, deadline - 1)).toBe(s)
    expect(expire(s, deadline).pairing.step).toBe('unconfirmed')
    expect(h.calls).toEqual(before)
  })

  it('moves the server\'s deadlines onto this browser\'s clock', async () => {
    const h = harness()
    // Minted when this browser read 5000 and the server read SERVER_NOW: the code has two minutes.
    const s = await codeShown(h)
    expect(s.pairing.step === 'waiting' && s.pairing.deadline).toBe(5_000 + CODE_TTL_MS)
    expect(phonesView(s, 5_000).area).toMatchObject({ timeLeft: { clock: '2:00' } })
    // Redeemed forty seconds later on the server's clock: confirmable until then plus two minutes.
    h.ports.api.codeState = async () => redeemedBy(KEY_A, ID_A, SERVER_NOW + 40_000 + 120_000)()
    const c = await poll(h.ports, s)
    expect(c.pairing.step === 'compare' && c.pairing.deadline).toBe(5_000 + 40_000 + 120_000)
    expect(secondsLeft(10_000, 9_001)).toBe(1)
    expect(secondsLeft(10_000, 10_000)).toBe(0)
    expect(secondsLeft(10_000, 99_999)).toBe(0)
  })

  it('a code confirmed too late is the time running out, not a refusal', async () => {
    const h = harness({ confirm: () => { throw new PhonesFault('gone') } })
    h.ports.api.codeState = async () => redeemedBy(KEY_A, ID_A)()
    const s = await pairThisPhone(h.ports, await poll(h.ports, await codeShown(h)))
    expect(s.pairing.step).toBe('unconfirmed')
  })

  it('a code the server says lapsed is lapsed; the page\'s own clock does not decide that', async () => {
    const h = harness()
    const shown = await codeShown(h)
    const deadline = shown.pairing.step === 'waiting' ? shown.pairing.deadline : 0
    // Past the deadline here, and the server still says waiting: the code stays.
    expect(expire(shown, deadline + 5_000)).toBe(shown)
    expect((await poll(h.ports, shown)).pairing.step).toBe('waiting')
    h.ports.api.codeState = async () => { throw new PhonesFault('gone') }
    expect((await poll(h.ports, shown)).pairing.step).toBe('lapsed')
  })

  it('an unreachable server while a code is shown replaces the code; "Try again" asks again', async () => {
    const h = harness({ codeState: () => { throw new PhonesFault('unreachable') } })
    const shown = await codeShown(h)
    const lost = await poll(h.ports, shown)
    expect(lost.pairing.step).toBe('unreachable')
    expect(allText(phonesView(lost, 0))).not.toContain('K7M4R')
    h.ports.api.codeState = async () => ({ state: 'waiting', expiresAt: SERVER_NOW + CODE_TTL_MS })
    const again = await tryAgain(h.ports, lost)
    expect(again.pairing).toEqual(shown.pairing)
  })

  it('"Try again" after a mint that did not reach the server mints again', async () => {
    let fail = true
    const h = harness({
      mintCode: () => {
        if (fail) throw new PhonesFault('unreachable')
        return { codeId: CODE_ID, code: CODE, baseUrl: ADDRESS, expiresAt: SERVER_NOW + CODE_TTL_MS }
      },
    })
    const lost = await codeShown(h)
    expect(lost.pairing.step).toBe('unreachable')
    fail = false
    expect((await tryAgain(h.ports, lost)).pairing.step).toBe('waiting')
    expect(h.calls.filter((c) => c === 'mintCode')).toHaveLength(2)
  })

  it('a minted address that is not https is the server saying it cannot pair, and shows no code', async () => {
    const h = harness({ mintCode: () => ({ codeId: CODE_ID, code: CODE, baseUrl: 'http://daymark.example.org', expiresAt: SERVER_NOW }) })
    const s = await codeShown(h)
    expect(s.httpOnly).toBe(true)
    expect(allText(phonesView(s, 0))).not.toContain('K7M4R')
  })

  it('a minted code the phone would decline is not shown', async () => {
    // The check symbol changed, derived from the code so it really differs.
    const wrong = CODE.slice(0, 9) + (CODE[9] === 'B' ? 'C' : 'B')
    expect(wrong).not.toBe(CODE)
    const h = harness({ mintCode: () => ({ codeId: CODE_ID, code: wrong, baseUrl: ADDRESS, expiresAt: SERVER_NOW }) })
    const s = await codeShown(h)
    expect(s.pairing.step).toBe('refused')
    expect(allText(phonesView(s, 0))).not.toContain(wrong.slice(0, 5))
  })
})

describe('the QR code carries the address and the code, and nothing else', () => {
  it('spells both with encodeURIComponent, exactly', () => {
    expect(pairingQrText('https://daymark.example.org', 'K7M4RD96QA')).toBe(
      'daymark-pair:v1?server=https%3A%2F%2Fdaymark.example.org&code=K7M4RD96QA',
    )
  })

  it('does not spell a value as URLSearchParams would, which the phone declines', () => {
    const address = "https://daymark.example.org/a b!'()*~"
    const text = pairingQrText(address, CODE)
    expect(text).toBe(`daymark-pair:v1?server=${encodeURIComponent(address)}&code=${CODE}`)
    expect(text).toContain("%2Fa%20b!'()*~&")
    // Positive control: URLSearchParams really does spell it otherwise, so the assertion above can fail.
    const params = new URLSearchParams({ server: address, code: CODE }).toString()
    expect(`daymark-pair:v1?${params}`).not.toBe(text)
  })

  it('knows a pairing code the way the phone does', () => {
    expect(isPairingCode(CODE)).toBe(true)
    const wrong = CODE.slice(0, 9) + (CODE[9] === 'B' ? 'C' : 'B')
    expect(wrong).not.toBe(CODE)
    expect(isPairingCode(wrong)).toBe(false)
    expect(isPairingCode(CODE.toLowerCase())).toBe(false)
    expect(isPairingCode('K7M4R-D96QA')).toBe(false)
    expect(isPairingCode(CODE.slice(0, 9))).toBe(false)
    // I, L and O are not symbols.
    expect(isPairingCode('I' + CODE.slice(1))).toBe(false)
  })
})

describe('no server text reaches the page', () => {
  const PLANTED = 'Planted server sentence 7f3a'

  /** A fetch that answers every request with [status] and the planted sentence as its body. */
  function answering(status: number, hits: string[]): typeof fetch {
    return (async (input: RequestInfo | URL, init?: RequestInit) => {
      hits.push(`${init?.method ?? 'GET'} ${String(input)}`)
      return new Response(JSON.stringify({ error: PLANTED, message: PLANTED }), { status, headers: { 'Content-Type': 'application/json' } })
    }) as typeof fetch
  }

  it('whatever the server refuses with, the section says only its own words', async () => {
    for (const status of [400, 401, 403, 404, 409, 410, 429, 500, 502, 503]) {
      const hits: string[] = []
      const api = devicesApi('', 'token', answering(status, hits))
      const ports: PhonesPorts = { api, words: deviceWords, keyIdOf: deviceKeyId, now: () => 0 }
      let s = await loadList(ports, initialState(true))
      const views = [phonesView(s, 0)]
      s = await mint(ports, back(s))
      views.push(phonesView(s, 0))
      // A code on screen, then each of its calls refused the same way.
      const shown: PhonesState = {
        ...initialState(true),
        pairing: { step: 'waiting', codeId: 'c', code: CODE, address: ADDRESS, qrText: pairingQrText(ADDRESS, CODE), deadline: 1 },
      }
      views.push(phonesView(await poll(ports, shown), 0))
      const comparing: PhonesState = {
        ...initialState(true),
        pairing: { step: 'compare', codeId: 'c', keyId: ID_A, words: WORDS_A, deadline: 1 },
      }
      views.push(phonesView(await pairThisPhone(ports, comparing), 0))
      // Positive control: the planted sentence really was in every answer the section read.
      expect(hits.length, String(status)).toBe(4)
      for (const v of views) expect(allText(v), String(status)).not.toContain(PLANTED)
    }
  })

  it('the detector sees the planted sentence when it is on screen (positive control)', () => {
    const planted: PhonesState = { ...initialState(true), list: [{ keyId: 'k', words: [PLANTED], pairedAt: 0, disconnectedAt: null }] }
    expect(allText(phonesView(planted, 0))).toContain(PLANTED)
  })
})

describe('the owner phone routes, as the section calls them', () => {
  function recorder(status: number, body: unknown) {
    const seen: { method: string; url: string; auth: string | null; body: string | null; cache: string | undefined }[] = []
    const doFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers)
      seen.push({
        method: init?.method ?? 'GET',
        url: String(input),
        auth: headers.get('Authorization'),
        body: typeof init?.body === 'string' ? init.body : null,
        cache: init?.cache,
      })
      return new Response(status === 204 ? null : JSON.stringify(body), { status })
    }) as typeof fetch
    return { seen, doFetch }
  }

  it('reach each route with the owner token, uncached', async () => {
    const list = recorder(200, { devices: [{ keyId: ID_A, publicKey: KEY_A, pairedAt: 1 }] })
    expect(await devicesApi('https://s.example/', 'tok', list.doFetch).listDevices()).toEqual([
      { keyId: ID_A, publicKey: KEY_A, pairedAt: 1, revokedAt: null },
    ])
    expect(list.seen).toEqual([{ method: 'GET', url: 'https://s.example/v1/devices', auth: 'Bearer tok', body: null, cache: 'no-store' }])

    const minted = recorder(201, { codeId: 'c1', code: CODE, baseUrl: ADDRESS, expiresAt: 9 })
    expect(await devicesApi('', 'tok', minted.doFetch).mintCode()).toEqual({ codeId: 'c1', code: CODE, baseUrl: ADDRESS, expiresAt: 9 })
    expect(minted.seen[0]).toMatchObject({ method: 'POST', url: '/v1/devices/pairing', auth: 'Bearer tok' })

    const state = recorder(200, { state: 'redeemed', keyId: ID_A, publicKey: KEY_A, confirmBy: 5 })
    expect(await devicesApi('', 'tok', state.doFetch).codeState('c1')).toEqual({ state: 'redeemed', keyId: ID_A, publicKey: KEY_A, confirmBy: 5 })
    expect(state.seen[0]).toMatchObject({ method: 'GET', url: '/v1/devices/pairing/c1' })

    const confirm = recorder(201, { keyId: ID_A, pairedAt: 7 })
    expect(await devicesApi('', 'tok', confirm.doFetch).confirm('c1', ID_A)).toEqual({ keyId: ID_A, pairedAt: 7 })
    expect(confirm.seen[0]).toMatchObject({ method: 'POST', url: '/v1/devices/pairing/c1/confirm' })
    expect(JSON.parse(confirm.seen[0]!.body!)).toEqual({ keyId: ID_A })

    const revoke = recorder(204, null)
    await devicesApi('', 'tok', revoke.doFetch).revoke(ID_A)
    expect(revoke.seen[0]).toMatchObject({ method: 'POST', url: `/v1/devices/${ID_A}/revoke` })
  })

  it('read each refusal by its status alone', async () => {
    const kind = async (p: Promise<unknown>) => {
      try {
        await p
        return 'ok'
      } catch (e) {
        return e instanceof PhonesFault ? e.kind : 'other'
      }
    }
    const api = (status: number) => devicesApi('', 't', recorder(status, { error: 'x' }).doFetch)
    expect(await kind(api(409).mintCode())).toBe('http')
    expect(await kind(api(404).codeState('c'))).toBe('gone')
    expect(await kind(api(404).confirm('c', 'k'))).toBe('gone')
    expect(await kind(api(404).revoke('k'))).toBe('refused')
    expect(await kind(api(404).listDevices())).toBe('refused')
    for (const s of [401, 403, 429, 400]) expect(await kind(api(s).listDevices()), String(s)).toBe('refused')
    for (const s of [500, 502, 503, 504]) expect(await kind(api(s).listDevices()), String(s)).toBe('unreachable')
    const offline = devicesApi('', 't', (async () => { throw new TypeError('Failed to fetch') }) as typeof fetch)
    expect(await kind(offline.listDevices())).toBe('unreachable')
    // A 200 that is not the route's answer (a captive portal's page) is no answer.
    const portal = devicesApi('', 't', (async () => new Response('<html>sign in</html>', { status: 200 })) as typeof fetch)
    expect(await kind(portal.listDevices())).toBe('unreachable')
    expect(await kind(api(200).listDevices())).toBe('unreachable')
    // Positive control: a real answer is not a fault.
    expect(await kind(devicesApi('', 't', recorder(200, { devices: [] }).doFetch).listDevices())).toBe('ok')
  })
})

describe('the clock', () => {
  it('reads minutes and seconds, never below zero', () => {
    expect(COPY.clock(120)).toBe('2:00')
    expect(COPY.clock(112)).toBe('1:52')
    expect(COPY.clock(88)).toBe('1:28')
    expect(COPY.clock(7)).toBe('0:07')
    expect(COPY.clock(0)).toBe('0:00')
    expect(COPY.clock(-3)).toBe('0:00')
    expect(COPY.confirmWithin(88)).toBe('Confirm within 1:28. If you do not, the phone is not paired and nothing is stored.')
  })
})
