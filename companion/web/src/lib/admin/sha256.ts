/*
 * Synchronous SHA-256 over the UTF-8 bytes of a string, as lowercase hex — the real digest the
 * admin chain check has been missing (task #16).
 *
 * WHY THIS EXISTS AT ALL. lib/admin/health.ts verifies a pasted audit run against an injected
 * `digest: (input: string) => string`, and until now nothing ever injected one: the console ran
 * its chain check with the hashes treated as opaque strings, and said so in its own "not checked"
 * list. The contract is synchronous because verifyChainRun is synchronous and pure, and the one
 * digest a browser ships — SubtleCrypto — is asynchronous and, worse, absent outside secure
 * contexts. Rather than reshape a pure module's contract around an API that may not be there,
 * the digest is implemented here: FIPS 180-4 SHA-256 is about sixty lines of arithmetic with no
 * secrets involved (everything hashed here is already on the operator's screen), and this
 * product's no-third-party rule means a library was never on the table anyway.
 *
 * WHAT IT MUST MATCH, byte for byte: the server's BlobStore.sha256Hex over the same UTF-8 bytes,
 * which is what AuditStore.chainHash stores. A digest that disagreed with the server's would not
 * fail loudly — it would report every honest entry as tampered, a screen full of fabricated
 * alarm, which is the worst failure a checking tool can have. So the suite beside this file
 * proves it against the published FIPS vectors AND against node's own SHA-256 across inputs that
 * cross every block boundary and every UTF-8 encoding width, including the exact canonical
 * strings the chain hashes are computed over.
 *
 * TextEncoder does the UTF-8, deliberately: hand-rolling the encoding is where a lone surrogate
 * or an astral pair would silently diverge from what the JVM's Charsets.UTF_8 wrote, and
 * TextEncoder is specified to encode exactly that (unpaired surrogates become U+FFFD on both
 * sides of the wire's lifetime — the Kotlin side never emits them, so the case cannot arise from
 * honest data).
 */

/** The round constants of FIPS 180-4 §4.2.2: fractional parts of the cube roots of 2..311. */
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
])

const rotr = (x: number, n: number): number => ((x >>> n) | (x << (32 - n))) >>> 0

/**
 * SHA-256 of the UTF-8 encoding of [input], as 64 lowercase hex characters.
 *
 * Deterministic, synchronous, allocation-bounded by the input length. The length is written as a
 * genuine 64-bit bit count (high word via division, low word via modulo) rather than a shifted
 * 32-bit one, so inputs past 512 MiB would still pad correctly — not because such an input is
 * expected, but because a hash function with a silent size cliff is not a hash function.
 */
export function sha256Hex(input: string): string {
  const msg = new TextEncoder().encode(input)
  const len = msg.length

  // Pad to a multiple of 64: the message, one 0x80 byte, zeros, then the 64-bit bit length.
  const blockCount = ((len + 8) >> 6) + 1
  const padded = new Uint8Array(blockCount * 64)
  padded.set(msg)
  padded[len] = 0x80
  const view = new DataView(padded.buffer)
  view.setUint32(padded.length - 8, Math.floor(len / 0x20000000))
  view.setUint32(padded.length - 4, (len % 0x20000000) * 8)

  // Initial hash state (§5.3.3): fractional parts of the square roots of the first 8 primes.
  const state = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ])
  const w = new Uint32Array(64)

  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let t = 0; t < 16; t++) w[t] = view.getUint32(offset + t * 4)
    for (let t = 16; t < 64; t++) {
      const w15 = w[t - 15]!
      const w2 = w[t - 2]!
      const s0 = rotr(w15, 7) ^ rotr(w15, 18) ^ (w15 >>> 3)
      const s1 = rotr(w2, 17) ^ rotr(w2, 19) ^ (w2 >>> 10)
      w[t] = (w[t - 16]! + s0 + w[t - 7]! + s1) >>> 0
    }

    let a = state[0]!
    let b = state[1]!
    let c = state[2]!
    let d = state[3]!
    let e = state[4]!
    let f = state[5]!
    let g = state[6]!
    let h = state[7]!

    for (let t = 0; t < 64; t++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
      const ch = (e & f) ^ (~e & g)
      const t1 = (h + S1 + ch + K[t]! + w[t]!) >>> 0
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const t2 = (S0 + maj) >>> 0
      h = g
      g = f
      f = e
      e = (d + t1) >>> 0
      d = c
      c = b
      b = a
      a = (t1 + t2) >>> 0
    }

    state[0] = (state[0]! + a) >>> 0
    state[1] = (state[1]! + b) >>> 0
    state[2] = (state[2]! + c) >>> 0
    state[3] = (state[3]! + d) >>> 0
    state[4] = (state[4]! + e) >>> 0
    state[5] = (state[5]! + f) >>> 0
    state[6] = (state[6]! + g) >>> 0
    state[7] = (state[7]! + h) >>> 0
  }

  let out = ''
  for (let i = 0; i < 8; i++) out += state[i]!.toString(16).padStart(8, '0')
  return out
}
