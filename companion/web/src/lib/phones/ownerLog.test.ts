import { describe, expect, it } from 'vitest'
import { OwnerLogError, fetchOwnerLog, ownerLogLines } from './ownerLog'

const row = (seq: number, action: string, meta?: Record<string, string>) => ({
  seq, ts: 1_700_000_000 + seq, actor: 'owner', action, objectRef: null, meta: meta ?? null, entryHash: 'h' + seq,
})

function answering(status: number, body: unknown, seen: string[] = []): typeof fetch {
  return (async (url: string) => {
    seen.push(url)
    return new Response(typeof body === 'string' ? body : JSON.stringify(body), { status })
  }) as unknown as typeof fetch
}

async function faultOf(p: Promise<unknown>): Promise<string> {
  try {
    await p
  } catch (e) {
    if (e instanceof OwnerLogError) return e.fault
    throw e
  }
  return 'none'
}

describe('the owner log', () => {
  it('reads a page, and asks for the next one from its cursor', async () => {
    const seen: string[] = []
    const page = await fetchOwnerLog('https://s.example/', 'tok', null, answering(200, { events: [row(9, 'device.paired')], nextCursor: 9 }, seen))
    expect(page.events.map((e) => e.seq)).toEqual([9])
    expect(page.nextCursor).toBe(9)
    await fetchOwnerLog('https://s.example', 'tok', 9, answering(200, { events: [], nextCursor: null }, seen))
    expect(seen[0]).toBe('https://s.example/v1/owner/audit?limit=50')
    expect(seen[1]).toBe('https://s.example/v1/owner/audit?limit=50&before=9')
  })

  it('refuses a whole page for one bad row, rather than showing fewer lines', async () => {
    const good = { events: [row(2, 'device.paired'), row(1, 'lockout')], nextCursor: null }
    // The control: the same page with every row well formed reads.
    expect((await fetchOwnerLog('', 't', null, answering(200, good))).events).toHaveLength(2)
    const bad = { events: [row(2, 'device.paired'), { ...row(1, 'lockout'), seq: 'one' }], nextCursor: null }
    expect(await faultOf(fetchOwnerLog('', 't', null, answering(200, bad)))).toBe('unreachable')
    expect(await faultOf(fetchOwnerLog('', 't', null, answering(200, 'not json')))).toBe('unreachable')
  })

  it('names an older server, a refusal and no answer apart', async () => {
    expect(await faultOf(fetchOwnerLog('', 't', null, answering(404, {})))).toBe('unsupported')
    expect(await faultOf(fetchOwnerLog('', 't', null, answering(401, {})))).toBe('refused')
    const down = (async () => { throw new TypeError('offline') }) as unknown as typeof fetch
    expect(await faultOf(fetchOwnerLog('', 't', null, down))).toBe('unreachable')
  })

  it('words each line as the owner log reads it, meta included', () => {
    const lines = ownerLogLines([
      { ...row(3, 'device.revoked', { by: 'reissue' }), actor: 'owner' },
      { ...row(2, 'lockout'), actor: 'owner' },
    ] as never)
    expect(lines[0].what).toBe('Disconnected a phone when the access token was re-issued')
    expect(lines[1].what).toBe('Paused one network address after repeated wrong tries')
  })
})
