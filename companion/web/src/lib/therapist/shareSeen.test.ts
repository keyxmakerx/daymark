import { describe, it, expect, beforeAll } from 'vitest'
import _sodium from 'libsodium-wrappers-sumo'
import { newestOpened, rememberOpened, SHARE_SEEN_STORAGE_KEY, type SeenStorage } from './shareSeen'
import { initAssignmentCrypto, newBoxKeyPair, type BoxKeyPair } from '../assignments/crypto'

/** A Storage stand-in: Node has no localStorage. */
function memory(): SeenStorage & { data: Map<string, string> } {
  const data = new Map<string, string>()
  return { data, getItem: (k) => data.get(k) ?? null, setItem: (k, v) => void data.set(k, v) }
}

describe('the newest share this browser has opened', () => {
  let box: BoxKeyPair
  const rel = 'relref-a'
  // A distinctive time, so finding it (or not) in the stored text means something.
  const t = 1_790_370_095_356

  beforeAll(async () => {
    await initAssignmentCrypto()
    box = newBoxKeyPair()
  })

  it('knows of none until a share has been opened', () => {
    expect(newestOpened(rel, box, memory())).toBeNull()
  })

  it('keeps the later of what it holds and what was just opened', () => {
    const s = memory()
    rememberOpened(rel, t, box, s)
    expect(newestOpened(rel, box, s)).toBe(t)
    rememberOpened(rel, t - 5, box, s)
    expect(newestOpened(rel, box, s)).toBe(t)
    rememberOpened(rel, t + 5, box, s)
    expect(newestOpened(rel, box, s)).toBe(t + 5)
  })

  it('keeps each relationship apart', () => {
    const s = memory()
    rememberOpened(rel, t, box, s)
    const other = newBoxKeyPair()
    rememberOpened('relref-b', 7, other, s)
    expect(newestOpened(rel, box, s)).toBe(t)
    expect(newestOpened('relref-b', other, s)).toBe(7)
  })

  it('stores the time sealed to the relationship key, never in the clear', () => {
    const s = memory()
    rememberOpened(rel, t, box, s)
    const raw = s.data.get(SHARE_SEEN_STORAGE_KEY) ?? ''
    expect(raw).not.toContain(String(t))
    // Positive control: the entry really does hold the time, for the key it was sealed to.
    const entry = (JSON.parse(raw) as Record<string, string>)[rel]
    const plain = _sodium.crypto_box_seal_open(
      _sodium.from_base64(entry, _sodium.base64_variants.URLSAFE_NO_PADDING),
      box.publicKey,
      box.privateKey,
    )
    expect(new TextDecoder().decode(plain)).toBe(String(t))
  })

  it('treats a record it cannot read as absent: other keys, bad JSON, no storage', () => {
    const s = memory()
    rememberOpened(rel, t, box, s)
    expect(newestOpened(rel, newBoxKeyPair(), s)).toBeNull() // re-paired: sealed to keys no longer held
    s.data.set(SHARE_SEEN_STORAGE_KEY, '{not json')
    expect(newestOpened(rel, box, s)).toBeNull()
    expect(newestOpened(rel, box, null)).toBeNull()
    expect(() => rememberOpened(rel, t, box, null)).not.toThrow()
  })

  it('a browser that refuses the write leaves the mark where it was, and does not throw', () => {
    const s = memory()
    rememberOpened(rel, t, box, s)
    const refusing: SeenStorage = { getItem: s.getItem, setItem: () => { throw new Error('quota') } }
    expect(() => rememberOpened(rel, t + 5, box, refusing)).not.toThrow()
    expect(newestOpened(rel, box, s)).toBe(t)
  })
})
