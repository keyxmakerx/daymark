# Daymark Companion — Session Handoff

> Written for a **fresh chat** to continue the Companion build. Snapshot of where things
> stand, what's verified, what's not, open decisions, and how to resume. Read this first.

> ### 📌 Update (2026-07-03, coordinator)
>
> - **PR #6 is MERGED** — everything below that says "on `claude/companion-features` /
>   PR #6" is now simply **on `main`** (merge `6e1b44c`).
> - **The §2 "build seems to be failing" report is RESOLVED: unreproducible.** Post-merge
>   `main` was re-verified green locally *and* in CI at every layer (web build 0 errors,
>   unit 134/134, integration 5/5, server 57/57, Docker smoke ✅, Android app build ✅).
>   One known false alarm: in the cloud workspace, `./gradlew` may fail with HTTP 403
>   downloading the Gradle distribution (egress-proxy policy) — use the preinstalled
>   system `gradle`; it is not a code failure.
> - **Open decision §5.1 is DECIDED: Option A** (owner notifications + access-token
>   re-issue only). §5.2 confirmed: the audit log is next.
> - **The working plan + parallel track assignments now live in
>   [`COMPANION_PLAN.md`](COMPANION_PLAN.md).** Read that after this file.

## 1. Where the work lives

- **Active branch:** `claude/companion-migration-8j32gv`? No — **`claude/companion-features`**
  (open as **PR #6**, base `main`). All recent work is committed here.
- **Merged already (on `main`):** M1 (container + Phase-0 viewer), M2 (E2EE sync), M3
  self-checks/dashboard/assignment-core — via PRs #1–#6-ancestors. The therapist portal +
  SMTP + phone spec are the **latest commits on `claude/companion-features`** (in PR #6).
- **Closed/superseded:** PR #7 (assignments design) — folded into PR #6.
- Everything Companion is under `companion/` (web = Svelte 5 + TS + Vite; server = Ktor/Kotlin)
  and `docs/COMPANION_*.md` + `docs/SYNC_PROTOCOL.md`.

## 2. Build/verify status (IMPORTANT — re-check first)

Last **independently re-verified green** at the therapist-portal commit:
- `cd companion/web && pnpm install && pnpm build` → 0 errors
- `pnpm test:unit` → **134/134** (all non-integration; crypto, instruments gate, tasks,
  assignments, stats, therapist, share)
- `pnpm test:integration` → **5/5** (boots the real server jar)
- `cd companion/server && ./gradlew --no-daemon test` → **57 tests**, BUILD SUCCESSFUL
- CI on PR #6 (`f6ffce4`): web, server, sync-integration, Docker all **green**.

> ⚠️ **The maintainer reported "the build seems to be failing" at handoff, but it was NOT
> reproduced/confirmed before this note.** FIRST STEP in the new chat: re-run the four
> commands above. Two doc-only edits (`companion/README.md`, `docs/COMPANION_README.md`) may
> be uncommitted — commit or stash them; they don't affect the build. If a build failure is
> real, check: `pnpm install` state (lockfile), the libsodium alias in `vite.config.ts`
> (must point at `libsodium-wrappers-sumo/dist/modules-sumo/…`), and the multi-page build
> (`index.html` + `therapist.html`).

## 3. What is built + verified (companion/)

- **Container + Phase-0 offline report viewer**; **E2EE sync** (zero-knowledge `/v1` blob
  store; client crypto Argon2id → XChaCha20-Poly1305; browser reader + `pnpm push` CLI writer).
  Wire format = `docs/SYNC_PROTOCOL.md` (base64 = URL-safe/no-pad; conformance vector).
- **Self-check engine** (data-driven instruments + build-failing **honesty gate**: non-
  diagnostic, no self-harm slot, no licensed sources, no clinical-cutoff bands) + original
  **"Steady Attention"** task. `companion/web/src/lib/{instruments,tasks}/`.
- **Dynamic interactive dashboard** (`components/Dashboard.svelte`) — expandable cards, range
  toggles, activity association, self-check trends. Replaced the tabbed viewer. PDF = optional.
- **Capability/assignment model** (`lib/assignments/`): owner-granted, Android-permission-style
  capabilities + apply modes (propose default; settings NEVER auto); validator (capability
  match, catalog+gate, setting allowlist); write-back crypto = Ed25519-sign (bound to
  `ASSIGNMENT_CONTEXT` + `recipientOwnerFp`) then `crypto_box_seal` to owner; open verifies
  vs **pinned** therapist fp; rejects wrong-context + cross-owner splice.
- **Therapist portal** (built via workflow, independently re-verified):
  - Server (`companion/server/.../{auth,mail,routes,storage}`): **TOTP** enrol/verify
    (Argon2id-hashed, lockout) gated by **single-use invite-bound enrolment ticket**
    (relRef derived server-side; insert-only, 409 on overwrite); opaque **session** cookies +
    **CSRF** on therapist writes; zero-knowledge grant/assignment/share/game-plan blob
    endpoints + per-relationship inbox tokens; setting-key allowlist enforced structurally.
  - Web (`lib/share/`, `lib/therapist/`, `components/{owner,therapist}/`, `therapist.html`):
    mutual **OOB short-authentication-string pairing** + fingerprint pin; curated **share**
    bundle (CEK sealed to therapist, owner-signed, AAD-bound); owner **acceptance inbox** +
    grant management + invite panel; therapist **"what you've allowed"** + capability-scoped
    **assign surface** + **game-plan authoring** + shared-data dashboard; wrapped key custody.
  - **SMTP mailer** (`mail/`): pluggable transport, **OFF by default** (only when
    `DAYMARK_SMTP_*` set), TLS, creds via `*_FILE`, a **content guard** so emails carry only
    invite/notification links — **no record content**. Tested vs embedded GreenMail. This is
    the **one deliberate owner-configured outbound**; the "no outbound" wording in
    `docs/COMPANION_DEPLOYMENT.md` is updated accordingly.

## 4. NOT built / not verifiable here (do not claim otherwise)

- **WebAuthn/passkey**: server endpoints return **501 (scaffold)**. **TOTP is the only
  working therapist auth path.** The browser ceremony can't be verified headlessly.
- **Phone `sync` flavor (Kotlin, Milestone 2b)**: **spec only** — `docs/COMPANION_PHONE_2B.md`
  (no Android SDK in this env). The web crypto/`SYNC_PROTOCOL.md` are its conformance oracle.
- **Real external SMTP delivery**: only GreenMail-tested; a live-server test is deploy-time.

## 5. Open decisions (maintainer to confirm; sensible defaults chosen)

1. **App ⇄ server email.** Server is zero-knowledge → it **cannot** reset PIN or E2EE
   passphrase (no escrow). Options: **(A, recommended/default)** owner notifications +
   re-issue the *server access token* only; **(B)** a real owner account (email+password,
   password reset) — a departure from account-less local-first; **(C)** hybrid (account gates
   web portal login only, keys stay local). *Awaiting pick; default = A.*
2. **"The other important thing"** the maintainer half-remembered → strongest candidate is an
   **owner-readable audit log of therapist access** (who viewed which share, when) — flagged
   in `COMPANION_SECURITY.md`, **queued next**. Other candidates surfaced: server backup/
   restore docs, owner portal login/MFA, trusted-proxy per-client rate-limiting.

## 6. Next steps (recommended order)

1. **Confirm the build is green** (§2). Commit the two pending doc edits.
2. **Audit log** (owner-readable therapist-access log) — verifiable web+server slice.
3. **App email = option A** (notifications + access-token recovery) via the existing Mailer.
4. **WebAuthn server-side** (attestation/assertion, CI-testable) behind the pinned RP-ID/origins.
5. Follow-ups: credential-rotation endpoint, expired-ticket sweep, crypto-random lineage IDs,
   trusted-proxy rate-limit config, server `/data` backup docs.
6. **Phone 2b** (needs an Android CI job; can't verify locally).

## 7. Environment gotchas (this workspace)

- **No Docker daemon** (client only) → images build/run only in CI (the `Docker` job).
- **No Android SDK** → phone (2b) is CI/emulator-only.
- **libsodium**: use `libsodium-wrappers-sumo` (standard build lacks Argon2id/kdf); the ESM
  build is broken → `vite.config.ts` aliases to its CJS. Base64 = URL-safe, no padding.
- `pnpm test:unit` = all non-integration (excludes `**/integration.test.ts`, no jar needed);
  `pnpm test:integration` boots the jar (CI `integration` job downloads the jar artifact).
- CI = `.github/workflows/companion.yml`, path-filtered to `companion/**`; jobs: web,
  server, integration (needs server), docker (needs web+server). Android app = `build.yml`.
- **Flaky in this session:** `AskUserQuestion` and the `Workflow` launch occasionally return
  "permission stream closed" — just retry, or ask in plain text.
- Commit trailers: `Co-Authored-By: Claude Opus 4.8 …` + the `Claude-Session:` line.
  Push with `git push -u origin claude/companion-features` (retry w/ backoff on network err).
  Do **not** put the model id in commits/PRs.

## 8. Key file map

- Sync crypto/contract: `companion/web/src/lib/sync/{crypto,client}.ts`, `docs/SYNC_PROTOCOL.md`
- Assignments: `companion/web/src/lib/assignments/{types,validate,crypto}.ts`
- Instruments + gate: `companion/web/src/lib/instruments/{validate,scoring,predicate,catalog/}`
- Therapist web: `companion/web/src/lib/{share,therapist}/`, `components/{owner,therapist}/`
- Server: `companion/server/src/main/kotlin/com/daymark/companion/{Application,Config}.kt`,
  `{auth,mail,routes,storage}/…`; tests in `…/src/test/…`
- Docs index: `docs/COMPANION_README.md` (has the build-status matrix + roadmap).
