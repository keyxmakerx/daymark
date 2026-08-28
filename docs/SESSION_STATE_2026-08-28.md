# Session state — 2026-08-28 (updated after the overnight run)

Working notes so a compacted session can resume without re-deriving anything.
Branch: `claude/therapist-key-registration`, PR #76 open against `main`.
HEAD at last update: `0d76024` (CPace) on top of `deec9e3` (audit hardening) on top of
`853d797` (libsodium 5.2.0) on top of `bef4503` (stable signing key, 0.2.0).

## The immediate goal (still standing)

The maintainer's server could not be restored; demos run from what this repo produces.
**Image warning: `sha-f1bca1c26528` is the blank-invite build. Do not deploy it.** A good one:
`sha-bccb4b99644a`. Anything at or after `deec9e3` needs a fresh `workflow_dispatch` on
companion.yml or a merge to main.

## What landed since the previous checkpoint (all pushed)

1. **libsodium 1.0.18 → 1.0.20 on the phone** (`853d797`): experiment run 33128182690 proved
   AGP 8.13/D8 accepts a JDK-21 `:sync-crypto`; both lazysodium artifacts at 5.2.0. The false
   parity comment is replaced by `LazySodiumParityTest` — a dependency-free class-file reader,
   mutation-proven against four doctored AARs. Residual: ristretto has never EXECUTED on real
   hardware; one instrumentation test budgeted before depending on it.
2. **Audit hardening batch** (`deec9e3`, workflow + adversarial pass, all four findings fixed):
   `AuditStore.verifyChain` + owner-gated `/v1/relations/{relRef}/audit-chain` (appends nothing);
   admin console real SHA-256 chain-head display with the honesty caveats; wire-level durable
   pairing-limiter restart test (mutation-proven); locked invite AND locked totp paths no longer
   append LOCKOUT per probe — one row per episode, on arming, both surfaces tested; totp route
   moved onto `AuthStore.nowMs()` (mixed clocks were untestable and divergent); `append()`
   refuses `'|'` (chain-hash injectivity); attempt_windows capped with oldest-first eviction.
3. **Gate 0.2 ANSWERED YES** (`0d76024`): CPace (CPACE-RISTRETTO255-SHA512) implemented twice
   against the IETF draft — `companion/web/src/lib/pairing/cpace.ts` and sync-crypto
   `CpaceCrypto.kt` — both pinned to the CFRG WG vectors; live fresh-randomness exchange run
   JVM↔browser in both directions (keys agree; wrong code diverges silently). Harness:
   `companion/web/e2e/cpace-live.mts` (vitest-driven; sumo's ESM dist is broken, use the alias)
   + `CpaceLiveCli.kt`. Plan doc gate table updated in place.

Suite counts at `0d76024`: web 1300/1300, svelte-check 519 files clean; server 365/365 local.
CI on `0d76024` was in flight at write time — it is the oracle for `:sync-crypto` (root Gradle
cannot run on this machine; dl.google.com is proxy-blocked).

## Standing constraints (verbatim rules that keep recurring)

- No green/success/score/streak/tick anywhere. Gaps in data never drawn as failure. Calm
  register. Semantic tokens only. Server never sees content; logs carry no content.
- The server vouches for NO key it relays — fingerprints compared out of band, read aloud.
- Only a human report burns an invite. Wrong PAKE code = silent key divergence, never an error,
  never a burn. Insert-only key tables via PK. Lockouts: one audit row per episode, on arming.
- Android cannot build here; CI is the only oracle. Sum XML test results; never trust
  BUILD SUCCESSFUL or a green feeling.
- Repo-wide recurring bug shape: a check written against an assumption about where a change can
  appear goes green when the assumption stops holding. Mutation-test anything load-bearing.

## Open tasks worth remembering

#18 CPace gate — answered, pending CI confirmation on `0d76024` · #22 phone sync client
(CI-only build) · #23 phone as root of trust (blocked on #22) · #24 heartbeat · #26 lazysodium
— folded, pending same CI run · #27 PR #66 distroless digest bump (merge is the maintainer's
call) · #28 sqlite-jdbc PR #68 (reflection test half is DONE via LazySodiumParityTest) · #29
dev-toolchain batch (prod clean; hold TS 7 / PR #54).

Not yet built: recovery transport/storage/wire format; QR pairing; the CPace-over-relay
transport (§3.7.3's store-and-forward shape — the crypto now has its oracle, the routes do not
exist). Dependabot: ~23 open PRs. The scratch JDK-21 project used for local :sync-crypto work
lives in this session's scratchpad only — rebuild it from companion/server's wrapper if needed.
