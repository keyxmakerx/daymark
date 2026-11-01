import { describe, expect, it } from 'vitest'
import { recoverSignIn, type RecoverPorts } from './recoverSignIn'
import { RECOVERY_REFUSAL, RECOVER_REFUSAL, ownerAccountApi } from './account'
import { CODE_DOES_NOT_OPEN_THIS } from '../components/recovery/copy'
import type { RecoveryChallenge } from './recoveryKey'

/* Getting back in with the recovery code (#325): the order, what is sent, and where it stops. */

const CHALLENGE: RecoveryChallenge = {
  challengeId: 'rc_1',
  challenge: 'ch',
  ownerId: 'owner-1',
  expiresAt: 0,
  recoveryKey: { v: 1, slots: [] },
}
const CODE = 'ABCDE-FGHJK-MNPQR-STUVW-XYZ23-45678'
const INPUTS = { typedCode: CODE, address: 'https://here.example', name: 'Sam', seed: new Uint8Array(20).fill(3), totpCode: '123456' }

function ports(over: Partial<RecoverPorts> = {}) {
  const calls: string[] = []
  const sent: unknown[] = []
  const p: RecoverPorts = {
    async recoveryChallenge() {
      calls.push('challenge')
      return { ok: true, value: CHALLENGE }
    },
    async prove(c, code, address) {
      calls.push(`prove ${c.challengeId} ${address}`)
      expect(code).toBe(CODE)
      return 'sig'
    },
    async recover(req) {
      calls.push('recover')
      sent.push(req)
      return { ok: true, value: { name: req.name, csrf: 'c' } }
    },
    ...over,
  }
  return { p, calls, sent }
}

describe('recoverSignIn', () => {
  it('asks for a challenge, proves it for this page, then sends only the signature with the new sign-in', async () => {
    const { p, calls, sent } = ports()
    expect(await recoverSignIn(p, INPUTS)).toEqual({ ok: true, name: 'Sam' })
    expect(calls).toEqual(['challenge', 'prove rc_1 https://here.example', 'recover'])
    expect(sent).toEqual([{ challengeId: 'rc_1', signature: 'sig', name: 'Sam', seed: INPUTS.seed, totpCode: '123456' }])
  })

  it('never sends the code: not to the challenge, not with the replacement', async () => {
    const bodies: string[] = []
    const api = ownerAccountApi('https://here.example', async (_url, init = {}) => {
      bodies.push(String(init.body ?? ''))
      const path = String(_url)
      if (path.endsWith('/challenge')) {
        return new Response(JSON.stringify({ ...CHALLENGE, recoveryKey: { v: 1, slots: [] } }), { status: 200 })
      }
      return new Response(JSON.stringify({ name: 'Sam', csrfToken: 'c', absoluteExpiry: 1 }), { status: 200 })
    })
    const out = await recoverSignIn(
      { recoveryChallenge: () => api.recoveryChallenge(), prove: async () => 'sig', recover: (r) => api.recover(r) },
      INPUTS,
    )
    expect(out.ok).toBe(true)
    expect(bodies).toHaveLength(2)
    const canonical = CODE.replace(/-/g, '')
    for (const body of bodies) {
      expect(body).not.toContain(canonical)
      expect(body).not.toContain(CODE.slice(0, 5))
    }
    // Control: the check sees the code when a body does carry it.
    expect(JSON.stringify({ code: CODE })).toContain(CODE.slice(0, 5))
  })

  it('stops at a refused challenge with its fixed sentence, proving and sending nothing', async () => {
    const { p, calls } = ports({ recoveryChallenge: async () => ({ ok: false, status: 409, message: RECOVERY_REFUSAL[409] }) })
    expect(await recoverSignIn(p, INPUTS)).toEqual({ ok: false, stage: 'challenge', status: 409, message: RECOVERY_REFUSAL[409] })
    expect(calls).toEqual([])
  })

  it('stops at a code that does not open the key, before anything that could change the sign-in', async () => {
    const { p, calls } = ports({
      prove: async () => {
        throw new Error('that secret does not open this slot')
      },
    })
    const out = await recoverSignIn(p, INPUTS)
    expect(out).toEqual({ ok: false, stage: 'code', status: null, message: CODE_DOES_NOT_OPEN_THIS })
    expect(calls).toEqual(['challenge'])
  })

  it('a refused replacement is its fixed sentence', async () => {
    const { p } = ports({ recover: async () => ({ ok: false, status: 401, message: RECOVER_REFUSAL[401] }) })
    expect(await recoverSignIn(p, INPUTS)).toEqual({ ok: false, stage: 'recover', status: 401, message: RECOVER_REFUSAL[401] })
  })
})

describe('the account calls, on the wire', () => {
  it('the challenge and the replacement go to their routes, with no credential at all', async () => {
    const seen: { url: string; init: RequestInit }[] = []
    const api = ownerAccountApi('https://here.example/', async (url, init = {}) => {
      seen.push({ url: String(url), init })
      return new Response(JSON.stringify({ error: 'x' }), { status: 401 })
    })
    expect(await api.recoveryChallenge()).toEqual({ ok: false, status: 401, message: 'The server answered in a way this console does not expect. Nothing was changed.' })
    expect(await api.recover({ challengeId: 'rc', signature: 's', name: ' Sam ', seed: new Uint8Array(20), totpCode: '123 456' })).toEqual({
      ok: false,
      status: 401,
      message: RECOVER_REFUSAL[401],
    })
    expect(seen.map((s) => s.url)).toEqual(['https://here.example/v1/owner/recovery/challenge', 'https://here.example/v1/owner/recovery'])
    for (const s of seen) {
      const h = (s.init.headers ?? {}) as Record<string, string>
      expect(h.Authorization).toBeUndefined()
      expect(h['X-CSRF-Token']).toBeUndefined()
    }
    expect(JSON.parse(String(seen[1].init.body))).toMatchObject({ name: 'Sam', totpCode: '123456' })
  })

  it.each([404, 409, 429, 503])('a %i challenge is its fixed sentence', async (status) => {
    const api = ownerAccountApi('', async () => new Response(JSON.stringify({ error: 'server words' }), { status }))
    expect(await api.recoveryChallenge()).toEqual({ ok: false, status, message: RECOVERY_REFUSAL[status] })
  })
})
