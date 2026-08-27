# Session state — 2026-08-28 (pre-compaction checkpoint)

Working notes so a compacted session can resume without re-deriving anything.
Branch: `claude/therapist-key-registration`, HEAD `ff8e2f8`, all pushed. PR #76 open against `main`.

## The immediate goal

The maintainer's own server could not be restored this week. They want to demonstrate the app's
progress **to their therapist today**. The demo therefore cannot depend on their deployment — it has
to run from what this repo produces: the Companion jar + web dist locally, or the GHCR image.

**Image warning: `sha-f1bca1c26528` is the blank-invite build. Do not deploy it.** Current HEAD has
the fixes; a fresh image needs `workflow_dispatch` on companion.yml (branch publish) or a merge to
`main` (publishes `:latest`).

## What works, verified in a real browser (Chromium via Playwright, this environment has both)

- Local smoke stack: `/tmp/run-smoke.sh` (jar on :8099, env inside it: THERAPIST_AUTH=1,
  token `smoke-token-0123456789`, web dist from companion/web/dist). Screenshot harness:
  `scratchpad/smoke/smoke.mjs` + `seeded.mjs` (chromium at
  `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`, `--no-sandbox`).
- All five pages render clean: index (first-run, three shapes), /therapist, /portal/invite (302 →
  /therapist, fragment carried), practice.html, admin.html.
- Sign-in: 9 fields → 3 → 2 (measured). Stored-record path + folded fallback. Owner fingerprint
  shown on unlock, read-aloud copy.
- Suites: server 338/0, web 1263/0, type-check 513 files clean, vite build 4 entries.

## What was built this session (all on the branch)

1. `/v1/relations/{relRef}/therapist-keys` (POST session+CSRF, GET owner bearer) — therapist keys → owner.
2. `/v1/relations/{relRef}/owner-keys` (POST owner bearer, GET session) — owner keys → therapist.
   Auth reversed on purpose; mutation-checked both directions. Audit actions for all.
3. Invite acceptance page (one code + one passphrase), `/portal/invite` routed (302, was a 404,
   then a blank page — relative assets resolve against the URL directory; test now asserts the
   redirect destination, not a 200).
4. Recovery-code crypto (`lib/recovery/`, 143-bit prime-alphabet code, dual-slot wrap, adversary
   test) + UI flows. **No transport/storage yet — placeholder marked on screen.**
5. Org control plane (server complete: create/roster/add/accept/role/remove/audit) + practice.html
   console + three-plane invariant test (parses the spec table at test time; planting read.share on
   org-admin fails 9 tests).
6. First-run entry (Solo/Paired/Practice fork, fail-open storage), `DAYMARK_THERAPIST_AUTH` shipped
   in compose + .env.example (was in Config.kt only — everything Paired/Practice 503s without it).
7. LoginGate reduction + `PortalClient.ownerKeys` + `KeyRecord.inboxToken` (remembered after first
   sign-in; token digest IS the relRef so the server can never hand it back).

## Standing constraints (verbatim rules that keep recurring)

- No green/success/score/streak/tick anywhere. Gaps in data never drawn as failure. Non-diagnostic.
  Calm register. Semantic tokens only. Server never sees content; logs carry no content.
- The server vouches for NO key it relays — fingerprints compared out of band, read aloud.
- Only a human report burns an invite (wrong code never does). Insert-only key tables via PK.
- Android cannot build here (dl.google.com proxy-blocked; CI is the only oracle). Phone has no
  networking at all yet — Solo demo works from an exported backup file.
- Repo-wide recurring bug shape: a check written against an assumption about where a change can
  appear goes green when the assumption stops holding. Mutation-test anything load-bearing.

## Open tasks worth remembering

#16 audit chain digest · #18 CPace JVM↔browser gate · #25 four PR #75 findings (wire-level limiter
test etc.) · #26/#30 libsodium 1.0.18→1.0.20 via lazysodium 5.2.0 (needs :sync-crypto on JDK 21;
one CI run decides; check 1.0.19/20 changelogs for relevant fixes) · #27 PR #66 distroless digest
bump (highest-value security merge, needs dependabot rebase) · #28 sqlite-jdbc + parity reflection
test · #29 dev-toolchain vuln batch (prod is clean; hold TS 7).

Dependabot: ~23 open PRs. QR pairing: designed (§3.10–3.11 of PLAN doc) not built. LoginGate
fallback still has the nine fields (deliberate). Recovery storage/transport unbuilt.
