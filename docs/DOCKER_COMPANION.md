# Daymark Companion (self-hosted) — Plan, Scope & Security

> **Superseded / expanded:** this was the original single-user plan. The companion has since been
> scoped to a **multi-party** design (owner + therapist + zero-knowledge server) covering sync,
> revocable MFA-protected therapist access, and therapist-authored game plans. See the expanded
> doc set starting at **[COMPANION_README.md](COMPANION_README.md)**
> (SCOPE · ARCHITECTURE · SECURITY · THERAPIST · DEPLOYMENT). This file is kept as the original
> reference; where they differ, the new doc set is authoritative.

> Status: **design / not yet built.** This document scopes the optional, self-hosted companion
> the user runs on their **own** machine (NAS, home server, mini-PC). It is a *companion*, not a
> backend: the phone app works fully without it and ships **no network access by default**.

## 1. Why a companion at all?

The phone is the wrong place for some things:

- **Sit-down review.** A big screen is better for browsing a year of data, comparing periods,
  reading long journals, exporting reports.
- **Sync across devices / a durable home for backups** without trusting a third-party cloud.
- **"Hardcore" assessments that don't belong on a phone** — longer attention/ADHD-style tasks
  (continuous-performance-style reaction tests), longer questionnaires, anything that needs a
  keyboard, a steady screen, and a quiet sit-down. These are *screening/observation* tools, never
  diagnostic (same posture as the in-app check-ins).

The companion answers all three **without compromising the app's privacy promise.**

## 2. Non-negotiable principles

1. **The core app stays no-network.** The default F-Droid build has **no `INTERNET` permission**.
   Sync lives in a **separate build flavor** ("Daymark Sync") that adds `INTERNET` *only* when the
   user deliberately installs it. The privacy claim of the main app remains verifiable.
2. **Self-hosted only.** No Daymark-operated servers, no accounts, no SaaS. The user owns the
   container and the data. We ship an image + `docker-compose.yml`, nothing phones home.
3. **Zero-knowledge server.** The server stores **ciphertext it cannot read.** Encryption/decryption
   happen on the **client** (phone, and the browser via WASM/JS). The server never sees the
   passphrase or plaintext. A breach of the server leaks only opaque blobs.
4. **Non-diagnostic, license-clean.** Any companion-only assessment uses public-domain or
   self-authored instruments, framed strictly as "this is a self-check, not a diagnosis," with the
   same offline crisis-resources posture as the app.
5. **No telemetry, ever.** The container makes **no outbound connections.** This is enforceable
   (egress firewall / `network_mode` notes in docs) and is part of the threat model below.

## 3. Architecture (target)

```
┌────────────────────┐      ciphertext over HTTPS       ┌──────────────────────────────┐
│  Phone (Sync flavor)│ ───────────────────────────────▶│  Companion container (yours)  │
│  - E2EE snapshot     │   PUT /snapshots (opaque blob)  │  - Ktor (Kotlin) API          │
│  - passphrase (local)│ ◀───────────────────────────────│  - blob store (files/SQLite)  │
└────────────────────┘      list/fetch ciphertext         │  - static web UI (zero-know.) │
                                                          │  - optional companion tests   │
        ┌─────────────────────────────────────────────┐  └──────────────────────────────┘
        │ Browser (your laptop)                        │            ▲
        │  - fetches ciphertext, decrypts in-browser   │────────────┘  ciphertext only
        │  - renders reports, runs sit-down tests      │
        │  - passphrase entered locally, never sent    │
        └─────────────────────────────────────────────┘
```

- **Backend:** **Kotlin + Ktor.** Shares the language with the app and can reuse the
  `kotlinx.serialization` snapshot models, so the data contract is defined once. Small, no heavy
  framework, trivial to containerize.
- **Storage:** start with the filesystem (one ciphertext blob per snapshot version) + a tiny SQLite
  index (snapshot id, device label, createdAt, size, content hash). No plaintext columns ever.
- **Web UI:** static assets served by Ktor. **Decryption happens in the browser** (WebCrypto +
  a small audited lib, or a libsodium/age WASM build). The server is a dumb, encrypted blob host.
- **Transport:** HTTPS. Self-hosters terminate TLS at a reverse proxy (Caddy/Traefik/nginx) or use
  the container's built-in self-signed cert for LAN-only use. Documented both ways.

## 4. Scope — phased, each independently shippable

### Phase 0 — Offline report viewer (no server, no sync) — **do first**
A **static web app** (could even be a single HTML file) that the user opens locally and into which
they **drag the existing backup JSON** the app already exports. It renders the rich "sit-down"
views: year overview, period comparisons, correlations, journal reader, printable report.
- **Zero phone changes, zero network, zero new attack surface.** Pure client-side; the file never
  leaves the laptop.
- Validates the data contract and the report UI before any sync/crypto exists.
- Ships in the repo under `companion/` with its own README; can be hosted by the Phase-1 container
  later, but works standalone (e.g. `file://`).

### Phase 1 — E2EE snapshot sync
- **Phone:** new `sync` product flavor adds `INTERNET`; a Settings → Sync screen to pair with a
  server URL + a **sync passphrase** (used to derive the encryption key; **never uploaded**).
  Snapshots are the existing versioned `BackupData` JSON, encrypted client-side, then `PUT` to the
  server. Append-only versions (never overwrite history) so a bad sync can't destroy data.
- **Server:** auth (see §5), store/list/fetch **ciphertext** blobs, prune policy (keep last N).
- **Browser:** enter the passphrase locally, fetch + decrypt + render. Server stays zero-knowledge.

### Phase 2 — Companion-only "sit-down" assessments
- Longer **attention / reaction tasks** (a continuous-performance-style test: respond to targets,
  withhold on non-targets; report reaction-time variability, omission/commission counts) — an
  **original** implementation, **not** TOVA/Conners/CAARS (those are licensed). Presented as a
  self-observation tool with explicit "not a diagnosis — discuss results with a clinician" framing.
- Longer questionnaires that are awkward on a phone (e.g. license-clean ADHD **self-report** items
  like the public **ASRS v1.1** with the WHO notice; IPIP personality facets — public domain).
- Results are written back into the encrypted snapshot model so they live alongside everything else
  and sync symmetrically. Keep a `companion/INSTRUMENTS.md` ledger mirroring the app's.

## 5. Security model

### Threat model (who we defend against)
| Adversary | Defense |
|---|---|
| Someone who steals the **server / its disk** | Sees only ciphertext + metadata (sizes, timestamps). No passphrase, no plaintext. |
| Someone on the **network** (LAN/Wi-Fi) | TLS in transit; payload is already E2EE, so even broken TLS leaks only ciphertext. |
| A **malicious/compromised server** | Zero-knowledge: it never has keys. Worst case it withholds/serves stale blobs (integrity below). |
| **Us / supply chain** | No telemetry, reproducible image, pinned deps, the container makes no outbound calls (verifiable). |
| **Brute force** of the passphrase | Argon2id KDF with strong params; guidance to use a long passphrase; rate-limited server auth. |

Out of scope (be honest): a *compromised phone or laptop* (endpoint security is the user's), and a
server that maliciously **deletes** blobs (mitigated by append-only + the phone keeping its own
local copy as source of truth — the server is a convenience replica, not the only copy).

### Cryptography
- **Content encryption:** XChaCha20-Poly1305 (AEAD) via libsodium/age, or WebCrypto AES-256-GCM —
  one primitive, used identically on phone (JVM) and browser (WASM/WebCrypto). Random nonce per blob.
- **Key derivation:** Argon2id from the user's sync passphrase + a stored random salt. The
  **passphrase never leaves the device**; only the salt (non-secret) is stored server-side.
- **Integrity / authenticity:** AEAD tag detects tampering; each snapshot carries a content hash and
  monotonic version so the client can detect rollback/withholding by a hostile server.
- **No key escrow.** Lose the passphrase → the blobs are unrecoverable (by design). Documented
  loudly; the phone's local data remains the primary copy.

### Server hardening (shipped defaults)
- Runs as a **non-root** user; container filesystem **read-only** except the blob volume.
- **No outbound network** needed → document `--network` egress restrictions / a deny-all egress
  policy; the image installs nothing at runtime.
- **Server auth** separate from the E2EE passphrase: a server access token (so randoms on your LAN
  can't enumerate/PUT blobs) with rate limiting + lockout. HTTPS required for non-loopback.
- Minimal base image (distroless/alpine), pinned digests, SBOM, reproducible build; CI builds and
  publishes the image with provenance. Security headers + strict CSP on the web UI (no third-party
  origins, no analytics, no CDNs — assets are vendored).
- Sensible limits: max blob size, max versions retained, request size caps (DoS hygiene).

### Privacy posture vs. the main app
- The **default app build is unchanged**: no `INTERNET`, F-Droid story intact. Only the explicitly
  separate **Sync flavor** can talk to a server, and only to the one the user configures.
- `PRIVACY.md` gains a "Companion (optional, self-hosted)" section explaining exactly what leaves the
  device (ciphertext only), to where (your server), and that the server can't read it.

## 6. Tech choices (recommended)

| Concern | Choice | Why |
|---|---|---|
| Backend | **Kotlin + Ktor** | Shares language + serialization models with the app; tiny; easy to containerize. |
| Storage | Filesystem blobs + SQLite index | No DB server to run/secure; trivial backup (it's just files). |
| Crypto | libsodium (JVM) + libsodium-wasm/WebCrypto (browser) | Same audited primitive both sides; misuse-resistant AEAD. |
| Web UI | Vendored static assets, no CDN | CSP-clean, offline, auditable; matches the app's no-third-party ethos. |
| Packaging | Docker image + `docker-compose.yml` | One-command self-host; compose documents the (lack of) network. |
| License | GPL-3.0 (same as the app) | Consistent copyleft; companion is FOSS. |

## 7. Open questions for the maintainer

1. **Phase 0 first?** Build the offline drag-in **report viewer** (no server, no risk) before any
   sync — quickest visible value, validates the report UI. *Recommended.*
2. **Decryption location:** browser-side (true zero-knowledge, a bit more JS/WASM work) vs. server
   decrypts in memory with a passphrase you type each session (simpler, weaker). *Recommend
   browser-side.*
3. **Companion assessments scope:** how far into "ADHD-style testing"? A single original
   attention/reaction task + license-clean self-reports is a safe, honest start; full
   neuropsych batteries are out (licensing + diagnostic-claim risk).
4. **Distribution of the Sync flavor:** F-Droid (separate listing) vs. GitHub-release-only APK, so
   the flagship F-Droid build stays provably network-free.

---

*This is a plan only — no companion code exists yet. Nothing here changes the app's current
no-network, local-only behavior.*
