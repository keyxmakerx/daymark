import { describe, it, expect } from 'vitest'
import { createHash } from 'node:crypto'
import { sha256Hex } from './sha256'

/*
 * The digest is only worth having if it agrees with the server's, byte for byte, on every input
 * a chain hash can be computed over. A digest that drifted would not fail loudly — it would
 * report every honest audit entry as tampered, which is fabricated alarm, the worst output a
 * checking tool can produce. So this suite proves agreement two independent ways:
 *
 *   1. Against the PUBLISHED vectors (FIPS 180-4 / RFC 6234), which pin the algorithm itself and
 *      cannot be satisfied by two implementations sharing one mistake.
 *   2. Against node:crypto — the same oracle health.test.ts uses for its chain fixture, and an
 *      implementation this file shares no code with — across inputs chosen to cross every
 *      padding boundary and every UTF-8 encoding width.
 *
 * The boundary lengths are not decorative: 55/56 is where the length field stops fitting in the
 * first block, 63/64 is the block edge itself, and 119/120 repeats both one block later. A
 * padding bug lives at exactly those seams and nowhere else.
 */

const oracle = (input: string) => createHash('sha256').update(Buffer.from(input, 'utf8')).digest('hex')

describe('sha256Hex matches the published vectors', () => {
  it('the empty string, abc, and the two-block vector', () => {
    // From the specification's own appendix — an independent anchor, not a self-comparison.
    expect(sha256Hex('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    expect(sha256Hex('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')).toBe(
      '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1',
    )
  })

  it('emits 64 lowercase hex characters, and is deterministic', () => {
    const digest = sha256Hex('anything at all')
    expect(digest).toMatch(/^[0-9a-f]{64}$/)
    expect(sha256Hex('anything at all')).toBe(digest)
    // Non-vacuity for every equality in this file: different inputs really do produce different
    // output, so "the two agree" is never "both returned the same constant".
    expect(sha256Hex('anything at all.')).not.toBe(digest)
  })
})

describe('sha256Hex agrees with node:crypto', () => {
  it('across every length that crosses a padding boundary', () => {
    // 0..130 covers both seams of the first block (55/56, 63/64) and both of the second
    // (119/120, 127/128), plus everything between.
    for (let n = 0; n <= 130; n++) {
      const input = 'a'.repeat(n)
      expect(sha256Hex(input), `length ${n}`).toBe(oracle(input))
    }
  })

  it('across every UTF-8 encoding width', () => {
    const inputs = [
      'déjà vu', // two-byte sequences
      'ΣΦΩ λόγος', // Greek, two-byte
      '日本語のテキスト', // three-byte
      '🌒🌓🌔', // four-byte astral pairs, exercising surrogate handling
      'mixed: aé日🌒 end', // all widths in one string
      'a\u0000b', // an embedded NUL is bytes like any other
      '�', // the replacement character itself
    ]
    for (const input of inputs) {
      expect(sha256Hex(input), JSON.stringify(input)).toBe(oracle(input))
    }
  })

  it('on the exact shape of a chain-hash canonical string', () => {
    // The one input class this digest exists for: AuditStore.chainHash's '|'-joined canonical
    // form, genesis hash included. Recomputed against the oracle so that health.ts's
    // verifyChainRun, fed THIS digest, computes the same entry hashes the server stored.
    const canonical = [
      '0'.repeat(64),
      '1',
      '1786000000',
      'rel_7f3a9c2b',
      'therapist',
      'auth.success',
      '',
      'credentialId=cred_a1',
    ].join('|')
    expect(sha256Hex(canonical)).toBe(oracle(canonical))
    // And on a meta encoding that carries the escaped separators, where an encoding-width or
    // boundary slip would land in practice.
    const awkward = ['0'.repeat(64), '2', '1786000300', 'rel_7f3a9c2b', 'owner', 'share.open', 'lin:0',
      'credentialId=a%2CsourceIp%3D10.0.0.1,z=100%25'].join('|')
    expect(sha256Hex(awkward)).toBe(oracle(awkward))
  })

  it('on long inputs spanning many blocks', () => {
    const long = 'The chain is computed by the server. '.repeat(400) // ~15 KiB
    expect(sha256Hex(long)).toBe(oracle(long))
  })
})
