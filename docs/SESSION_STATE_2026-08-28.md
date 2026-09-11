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

4. **The CPace relay + envelope layer** (`c985e78` and after): PairingStore + owner/therapist
   relay routes (opaque blobs, invite-secret proof that consumes nothing, shared counter with
   redeem — proven by test), `pairing/relay.ts` with the §3.7.4 code-never-on-the-wire test,
   and `pairing/envelope.ts` — directional AEAD over the ISK, where a wrong code surfaces as a
   null and nothing more. All additive; the demoable ceremony is untouched. See the PROGRESS
   banner under 4.0a in the plan doc for what deliberately remains.

CI: `0d76024` green on BOTH workflows (confirms :sync-crypto with lazysodium 5.2.0, the parity
test's AAR wiring, and the Kotlin CPace vectors in the root build). Root Gradle still cannot
run on this machine; CI stays the oracle. Suite counts at the last local run: server 373/373,
web 1313/1313, svelte-check 523 files clean.

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

## Addendum 2026-09-01 — the pairing stack was audited before screens were built on it

Branch `claude/pairing-stack-audit-suak0v`. The relay + envelope layer (item 4 above) went
through an adversarial pass — own findings refuted by independent skeptics, two fresh lenses
(guess budget, authz/state machine), every fresh finding verified — after a mutation check
showed the existing tests are load-bearing (code-in-a-header, one-key-both-directions, swapped
ISK transcript, dropped once-only guard: each turns exactly the right test red). Three fixes
landed, each red-first: re-open retires the replaced run (`SUPERSEDED`, and the resurfacing of
stale rows after the newer one leaves OPEN is closed); the CI carries the invite id (v2,
lv_cat); the owner's run pins its reply and refuses a swapped one. The guess bound stated in
the plan's 4.0a AUDIT banner is the verified one; the banner also lists what the UI stage must
hold and what was accepted. Two independent low-effort validation passes followed (five agents,
then three told to disagree): the first closed two nits (a pinned run refuses a swapped or
regressed read; the close retry is tested), the second caught that the new guard over-refused
the owner's own Cancel after a failed close — fixed with the honest sequence as a test — and
wrote the phone-side CI bytes into the 4.0a banner so 4.0b does not start from a comment. Noted for that stage: companion/server's own Gradle wrapper DOES
run on this machine (the root build still does not), so server tests have a local oracle now.

## Addendum 2026-09-04 — the pairing UI is planned, and its foundations are in

PR #83 merged; the audit stack is on `main`. A plan for the screens was written and reviewed
adversarially before any code: the shape that survived is ONE sealed envelope, therapist to
owner, carrying the therapist's public keys, a display name and a therapist-chosen enrol
ticket; the owner opens it, sees a name, clicks Approve, and hands that ticket to the server;
`POST /v1/invite/{id}/redeem` is then removed, so the link alone can no longer mint a ticket.
The review refuted a two-envelope draft (the owner never needs to send anything sealed back),
found that the enrol ticket's ten-minute TTL is wrong once the clock starts at approval, that
the shared "pair" limiter allows the therapist about ten status polls per five minutes, and that
nine server test files call the route being removed. All of that is in the plan.

This addendum's commit is the foundations only, no visible change: the pairing code module
(`companion/web/src/lib/pairing/pairingCode.ts`; eight symbols, two groups of four, one check
symbol, entropy stated), the relay taking a branded canonical code and refusing anything else
before the first request (the previous version fed the code in as typed, so a stray space made
a different key), `ownerCollectPairing` no longer taking the code at all (`cpaceFinish` never
needed it, and its type now says so), the owner's half of a run persisted in sessionStorage
with a test that greps the record for the code, pairing audit labels, the owner's
Stop-this-invitation button on the existing report route, and `revokeLineage` counting the
ciphertext files it could not delete instead of swallowing them. Plan open question 7 is
answered in place. Decisions the docs did not hold before: the code shape; the owner side
ships on the web with sessionStorage and a lower-assurance line; ending a connection deletes
server copies and tells nobody.

The channel followed on the same branch (the second of the three planned changes). Server:
`pairing_exchanges` gains an opaque `env_to_owner` column written once by respond;
`POST …/pairing/{id}/approve` (bearer) takes the therapist-chosen 32-byte ticket, moves the
invitation to REDEEMING with a ticket that expires with the invitation, and closes the run,
invitation first and compensated with `abandonRedeem` if the run refuses to move; cancel on a
CLOSED run is the abandon; `POST /v1/invite/{id}/pairing/{ex}/status` is the therapist's poll
(the one touch allowed against a REDEEMING invite, flat 410 for everything but WAITING and
APPROVED, 45-second cadence written at the route and mirrored in the client because the shared
"pair" budget charges every allowed request); `GET /v1/relations/{relRef}/invites` is the owner's
list with `failCount`; `close` is gone; audit gains `pairing.approved` and `pairing.abandoned`.
Client: `companion/web/src/lib/pairing/payloads.ts` pins the offer (v1: two 32-byte keys, a name
of at most 64 code points with no control or bidi characters, a 32-byte ticket; extra fields
refused); the relay seals it on answer, opens it on collect and returns `offer: null` for any
failure, and gains approve, cancel and status. Mutation-checked: an approve that skips the
RESPONDED check, an abandon that keeps the ticket, a ticket that expires in ten minutes, a fetch
that admits a REDEEMING invite, an offer posted in the clear, a validator that admits an extra
field — each turns the test that names it red. The §3.7.4 grep now also asserts the ticket is
in exactly one request (approve) and no response, and that the offer's fields never travel in
the clear. Server 382/382, web 1400+, svelte-check clean.

## Addendum 2026-09-04b — the screens, and the cut-over

The third of the three planned changes. `lib/pairing/ownerCeremony.ts` and
`lib/therapist/pairingAccept.ts` hold both halves as ports-and-transitions so the ORDER is a node
test: keys are pinned before a ticket is forwarded, the therapist learns the relationship before
generating any keys (the insert-only question needs the relRef), and nothing opens a second run on
its own. `components/owner/PairingPanel.svelte` renders the owner's phases; the code lives in an
`<output>` with `aria-live`, never a form control and nowhere the email path reaches, with a
structural test over the source. `InviteAcceptance.svelte` asks for the code, a name and a
passphrase, then holds a waiting state of its own rather than a spinner. `therapistKeys.ts` gains
`pinFromPairing`, a second door with the envelope as its stated authority rather than a bypass of
`acceptTherapistKeys` (passing that gate its own expected fingerprints would be the tautology its
header forbids).

THE CUT-OVER: `POST /v1/invite/{id}/redeem` is removed, along with `beginAcceptance` and
`PortalClient.redeemInvite`. A ticket now exists only because an owner approved a run whose reply
opened under their code. Two tests: the path answers as an unknown route, and no file under
`routes/` mentions `redeemInvite` (mutation-checked — re-adding a redeem handler turns exactly that
test red). Six server test files moved from the redeem route to the relay's fetch, which shares the
secret, the counter and the backoff; a correct secret with no run waiting answers 410 where a wrong
one is still 401, and that difference is what the burn-rule tests now assert. `AuthStore.redeemInvite`
survives as a store function for the lockout and burn-rule tests only.

The read-aloud is demoted, not deleted: fingerprints and their labels stay on the therapist's done
screen, and the copy says the code did the confirming. Its test asserts the ABSENCE of the
instruction, with a control so the detector is not blind. Suites: server 383/383, web 1438 passing,
svelte-check 541 files clean, production build clean.

Not done in this change: the Playwright pass driving both screens against a live server, and the
therapist self-leave (Slice E).

## Addendum 2026-09-11 — the pairing UI is finished and verified; handoff for a fresh session

Branch `claude/pairing-stack-audit-suak0v`, nine commits on top of `main` (`3d46bf8`), head
`a55159b`. **No pull request has been opened** — that is the maintainer's call. Both CI workflows
are green on the head commit.

All three planned changes landed (foundations, the channel, the screens and the cut-over; each has
its own addendum above). What the last commit added on top of them: the ceremony was driven end to
end in two real Chromium profiles against a locally built server, and that run found two copy bugs
no unit test could — the acceptance page still promised a read-aloud it no longer performs, and
"Accept an invitation" was printed twice, as the page heading and again as the first card title.
Both fixed. The server's own record of the live run: `pairing.opened, pairing.responded,
pairing.approved, enrol.ok`, invitation `CONSUMED`, one credential, zero unconsumed tickets, the
exchange `CLOSED` with both `msg_b` and `env_to_owner` stored. Suites at the last full local run:
server 383, web 1443, svelte-check 541 files clean, production build clean.

**The one rough edge, recorded rather than fixed.** After a reload the owner can still collect, open
the offer and approve — but cannot re-show the invitation link (only the mint response carries it)
nor the code (it is deliberately never persisted). The screen says so and offers New code. Fixing it
means either a route that re-serves the link or accepting that a reload ends the invitation.

**Not done, in the order they were deferred.** (1) Therapist self-leave, Slice E — planned, never
started; it destroys only the clinician's own credential. (2) The phone side, plan 4.0b — the offer
bytes and the approve/status/invites contract are written into the 4.0a banner so it does not start
from a comment, but no phone-side pairing code exists. (3) Dependabot: post `@dependabot rebase`
before merging anything (CI has been push-only since `9efaa49`, so every open PR's last run tested
its tip against a stale parent), and close #39 — the AGP ignore rule in the Dependabot config names
a coordinate that appears nowhere in the tree, which is why #39 reappears.

**Releases, since it came up and was not written down.** Pushing a `v*` tag runs
`.github/workflows/release.yml`, which builds `assembleFossRelease`, re-verifies the APK declares no
INTERNET, and *creates* the GitHub Release with the APK attached. You do not draft a release first.
Only the `foss` flavor is published; `sync` gets its own path when it is feature-complete. If the
`KEYSTORE_BASE64` secret is missing the build **silently falls back to debug signing** rather than
failing, and Android will not update across that signature change — so check the secrets before
tagging. A tag can point at a commit `build.yml` never ran; tag something already verified.

**Workflow change made in this session.** `CLAUDE.md` now exists at the repository root, auto-loaded
into every session and every subagent. It carries the prime directive, the repo map, the commands
and which oracle works where, the recurring copy/security/process rules, the testing conventions,
and a pointer table saying which single document answers which question. It exists because sessions
kept spending their context re-deriving all of that. Keep it short; when it grows, move the detail
into the document the table points at.
