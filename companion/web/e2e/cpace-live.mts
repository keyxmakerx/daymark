/*
 * The browser half of a live CPace interop exchange — a JVM ↔ browser proof run (the two
 * implementations of COMPANION_PAIRING.md §3) with fresh randomness, as opposed to the shared
 * deterministic vectors both unit suites pin.
 *
 * This is a TEST HARNESS, not transport: secret scalars travel between the two harness halves
 * as hex strings, which is exactly what production must never do. It exists so a person (or a
 * script) can drive the committed cpace.ts against its CLI twin
 * (sync-crypto/src/test/.../CpaceLiveCli.kt) and watch two independent implementations agree
 * on a key with randomness neither side controls. Not wired into CI — the node side and the
 * JVM side live in different builds — but both are compiled by their own CI so neither can rot
 * silently, and byte-level agreement is enforced forever by the shared CFRG vectors in both
 * unit suites.
 *
 * WHY THIS RUNS UNDER VITEST rather than plain node/tsx: libsodium-wrappers-sumo 0.7.16's ESM
 * dist references libsodium-sumo.mjs at a path that only exists inside the sibling
 * libsodium-sumo package, so a bare `tsx` run dies at module resolution; vite's resolver (the
 * one the real bundle and the whole test suite already go through) handles it. Hence the
 * driver spec + config next to this file. Invocation, from companion/web:
 *
 *   CPACE_MODE=start   CPACE_ARGS=<prsHex>,<ciHex>                                CPACE_OUT=/tmp/a.json \
 *     npx vitest run --config e2e/vitest.cpace-live.config.mts
 *   CPACE_MODE=respond CPACE_ARGS=<prsHex>,<ciHex>,<sidHex>,<msgAHex>             CPACE_OUT=/tmp/b.json ...
 *   CPACE_MODE=finish  CPACE_ARGS=<prsHex>,<ciHex>,<sidHex>,<yaHex>,<msgAHex>,<msgBHex> CPACE_OUT=/tmp/f.json ...
 */
import {
  cpaceFinish,
  cpaceRespond,
  cpaceStart,
  initCpace,
} from '../src/lib/pairing/cpace'

const hex = (h: string): Uint8Array => {
  const out = new Uint8Array(h.length / 2)
  for (let i = 0; i < out.length; i++) out[i] = parseInt(h.slice(i * 2, i * 2 + 2), 16)
  return out
}
const toHex = (b: Uint8Array): string =>
  Array.from(b)
    .map((x) => x.toString(16).padStart(2, '0'))
    .join('')

const ADA = new TextEncoder().encode('ADa')
const ADB = new TextEncoder().encode('ADb')

/** One protocol step; the result object is what the driver writes as JSON. */
export async function runLiveCpace(mode: string, args: string[]): Promise<Record<string, string>> {
  const sodium = await initCpace()
  if (mode === 'start') {
    const [prs, ci] = args.map(hex)
    const sid = sodium.randombytes_buf(16)
    const a = cpaceStart({ prs, ci, sid }, ADA)
    return { sid: toHex(sid), ya: toHex(a.ya), msgA: toHex(a.msgA) }
  }
  if (mode === 'respond') {
    const [prs, ci, sid, msgA] = args.map(hex)
    const b = cpaceRespond({ prs, ci, sid }, msgA, ADB)
    return { msgB: toHex(b.msgB), isk: toHex(b.isk) }
  }
  if (mode === 'finish') {
    const [prs, ci, sid, ya, msgA, msgB] = args.map(hex)
    const isk = cpaceFinish({ prs, ci, sid }, { ya, msgA }, msgB)
    return { isk: toHex(isk) }
  }
  throw new Error(`unknown mode: ${mode}`)
}
