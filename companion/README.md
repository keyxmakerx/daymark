# Daymark Companion

The optional, **self-hosted** companion server for Daymark — run on your own machine
(NAS, home server, mini-PC) with Docker. The phone app works fully without it and ships
**no network access by default**; the Companion is a convenience, never a requirement.

> **Status: Milestone 3 — sit-down self-checks.** Implemented: the hardened container; the
> **Phase-0 offline report viewer**; **end-to-end-encrypted sync** (zero-knowledge blob
> store + client-side XChaCha20-Poly1305/Argon2id, browser reader + CLI writer); and now the
> **expanded self-check suite** — a data-driven, non-diagnostic **questionnaire engine** with
> a build-failing honesty gate, plus an original **"Steady Attention"** focus/timing task; a
> **dynamic interactive dashboard** (expandable cards, range toggles, activity-association and
> self-check trends) replacing the old tabbed viewer; and the **therapist portal** — the
> owner-granted (Android-permission-style) capability model, therapist **TOTP auth** +
> single-use invites, **pairing + share crypto**, the owner **acceptance inbox**, the
> capability-scoped **assign surface** + game-plan authoring, and an opt-in **SMTP mailer**
> (the one owner-configured outbound; no record content in emails). **Not verifiable here /
> not built:** WebAuthn (a 501 scaffold — **TOTP is the working therapist auth path**), and
> the phone Sync *flavor* (2b, Kotlin — spec only). See the design set in
> [`../docs/COMPANION_README.md`](../docs/COMPANION_README.md), the sync spec in
> [`../docs/SYNC_PROTOCOL.md`](../docs/SYNC_PROTOCOL.md), the features design in
> [`../docs/COMPANION_FEATURES.md`](../docs/COMPANION_FEATURES.md), and the assignment/dashboard
> design in [`../docs/COMPANION_ASSIGNMENTS.md`](../docs/COMPANION_ASSIGNMENTS.md).

## Self-checks (Milestone 3)

Open the portal → **Self-checks**. Two things run there, entirely on-device and **never
uploaded**:

- **Questionnaire engine** — instruments are pure JSON definitions (`src/lib/instruments/`);
  a new license-clean instrument is added by dropping a definition + an `INSTRUMENTS.md`
  ledger row, no per-instrument code. A **load-time + CI honesty gate** (`validate.ts`,
  `instruments.test.ts`) *fails the build* if a definition is not non-diagnostic, contains a
  self-harm slot, names a forbidden (licensed) source (TOVA/Conners/CAARS/…), references a
  missing ledger anchor, or presents a band as a clinical screen/cutoff. Ships two
  **self-authored** instruments (a daily wellbeing check and a focus & follow-through check).
- **Steady Attention** — an original CPT-style sustained-attention task. Reports robust
  count-based metrics (omissions/commissions/accuracy) plus a *caveated* reaction-time
  mean; it measures its own frame jitter and flags **lower-precision** runs, per the design.

Non-diagnostic by construction: descriptive bands only, a fixed disclaimer on every result,
and no self-harm item anywhere in the engine.

## What's here

```
companion/
├── web/            Svelte 5 + TS + Vite frontend (vendored, strict-CSP, zero third-party)
│   └── src/
│       ├── lib/sync/   E2EE sync: crypto.ts (the reference impl), client.ts, tests
│       ├── cli/        daymark-sync push — reference writer (Node)
│       └── …           Phase-0 report viewer + "Connect to sync" reader
├── server/         Ktor (Kotlin): serves the bundle, /healthz, the zero-knowledge /v1 blob store
├── Dockerfile      Multi-stage build (web → server jar → minimal JRE runtime, non-root)
├── docker-compose.yml            The app, published on 127.0.0.1:8080. No bundled proxy.
├── docker-compose.no-egress.yml  Override for a containerised proxy: no host port, no egress
└── INSTRUMENTS.md  Instrument-ledger stub (for the future assessment runner)
```

## Sync (Milestone 2)

End-to-end-encrypted, append-only snapshot sync. The server stores only opaque ciphertext
(it never sees your passphrase, keys, or data); all crypto is client-side. The wire format
is specified in [`../docs/SYNC_PROTOCOL.md`](../docs/SYNC_PROTOCOL.md).

Enable it by setting an access token (otherwise `/v1` is fail-closed/disabled):

```bash
# in docker-compose.yml / .env
DAYMARK_AUTH_TOKEN=<a long random token>   # or DAYMARK_AUTH_TOKEN_FILE=/run/secrets/…
```

**Push a backup from your laptop** (reference writer, until the phone Sync flavor ships):

```bash
cd companion/web && pnpm install
DAYMARK_SYNC_PASSPHRASE='your sync passphrase' \
  pnpm push -- --server http://localhost:8080 --token "$DAYMARK_AUTH_TOKEN" \
              --lineage laptop --backup ~/Downloads/daymark-backup.json
```

**Read it back** in the browser: open the portal → **Connect to sync** → enter the token,
device/lineage, and passphrase. The snapshot is fetched and decrypted in your browser.

## Published image

CI publishes to GHCR **after** the image has passed every gate in `.github/workflows/companion.yml`
— it serves `/healthz` and `/readyz` under the real hardening, both compose topologies boot, egress
is dead on the app network, and the no-egress override really does remove the host binding. The
push is the last step of that job rather than a separate one, so what ships is the exact artefact
that was tested, not a rebuild of the same source.

```
ghcr.io/keyxmakerx/daymark-companion@sha256:<digest>  # immutable AND verifiable — the safest pin
ghcr.io/keyxmakerx/daymark-companion:sha-<commit>     # immutable, but a tag can be force-pushed
ghcr.io/keyxmakerx/daymark-companion:latest           # moves with main — for auto-updating setups
ghcr.io/keyxmakerx/daymark-companion:main             # identical to :latest, same image
```

**Pick by whether you want the deployment to change without you.**

`:latest` and `:main` are the same image and both follow `main`. Use one of them if your container
manager auto-updates (Cosmos, Watchtower, a Portainer stack that repulls) and you want that — it is
what those tools are for, and `:latest` in particular is what everything assumes when no tag is
given, so its absence is a confusing failure rather than a safe default.

Pin the **digest** when you do not want that. A moving tag plus an auto-updating manager is how a
server changes behaviour overnight with no diff to look at, and this application carries a
migrating database: an update can change on-disk state in ways that do not reverse by rolling the
image back. **Take a backup before an update you did not choose the timing of.** The exact digest
for any build is printed in that run's summary on the Actions tab.

Only builds from `main` move `:latest` and `:main`. A manually dispatched build of a branch
publishes its `sha-` tag and nothing else, so a branch can be tested without anything that follows
a moving tag pulling it.

**One-time setup:** GHCR packages are created private even for a public repo. After the first
publish, open the package in GitHub → Package settings → change visibility to public. Otherwise
every pull needs `docker login ghcr.io` with a PAT carrying `read:packages`.

To run the published image from this compose file rather than building:

```bash
# Pinned (recommended for anything you rely on):
export DAYMARK_IMAGE=ghcr.io/keyxmakerx/daymark-companion@sha256:<digest>

# Or following main:
export DAYMARK_IMAGE=ghcr.io/keyxmakerx/daymark-companion:latest

docker compose pull && docker compose up -d
```

**The `/data` volume must be writable by UID 65532.** The image runs as a non-root user, and the
compose file uses a *named* volume, which Docker seeds from the image with the right ownership. A
**bind mount** does not do that — it keeps the host directory's ownership — so a bind-mounted
`/data` owned by root gives `NOT READY: cannot write to /data (Permission denied)`, the app serving
only the viewer and `/healthz`. Container managers that generate their own compose tend to use bind
mounts, so if you did not write the mount yourself, check this first:

```bash
chown -R 65532:65532 /your/host/path/for/data
```

Building it yourself stays fully supported and is the default — nothing here requires the registry.
Note the image is **linux/amd64 only** for now; CI does not cross-build, so an arm64 host (a Pi, an
Apple-silicon machine) still needs a local build.

## Quick start (Docker)

```bash
cd companion
cp .env.example .env          # set DAYMARK_DOMAIN
mkdir -p secrets && chmod 700 secrets
openssl rand -base64 48 | tr -d '\n' > secrets/auth_token
sudo chown 65532:65532 secrets/auth_token && chmod 400 secrets/auth_token
docker compose up -d --build
# then open http://localhost:8080
```

The container runs **non-root** on a **read-only root filesystem** (only `/data` and a
`/tmp` tmpfs are writable), drops all Linux capabilities, and makes **no outbound
connections by default**. There is exactly **one** deliberate, owner-configured exception:
outbound **SMTP** for therapist invite/notification links. It is **OFF unless
`DAYMARK_SMTP_HOST` is set**; when enabled it egresses only to the operator's configured
mail server, requires TLS (STARTTLS or implicit), takes credentials via `*_FILE` secrets,
and its emails carry **only** links/notifications — **never** any record or plaintext
content.

Two health endpoints: **`/healthz`** is liveness (the process is up) and **`/readyz`**
additionally proves `/data` is still writable, returning 503 when it is not — a read-only
mount, a volume owned by the wrong UID, or a full disk. The container's own HEALTHCHECK
probes `/readyz`.

**There is no bundled reverse proxy, deliberately.** The app speaks plain HTTP on
`127.0.0.1:8080` and expects yours — Cosmos Cloud, Caddy, Traefik, nginx, a Cloudflare
tunnel — to terminate TLS in front of it. Two things are required of it and neither is
optional: set `DAYMARK_TRUSTED_PROXIES` to its address, and add `Strict-Transport-Security`
there (the app sends every other security header but not HSTS, because it cannot know
whether it is behind TLS). If your proxy is itself a container, use
`docker-compose.no-egress.yml`, which drops the host port and leaves the app no route out
at all. The full contract is §3 of
[`../docs/COMPANION_DEPLOYMENT_HARDENING.md`](../docs/COMPANION_DEPLOYMENT_HARDENING.md);
worked configs are in [`../docs/alternatives/`](../docs/alternatives/).

## Local development

**Frontend:**
```bash
cd companion/web
pnpm install
pnpm dev        # Vite dev server with HMR
pnpm build      # type-check + production bundle → dist/
```

**Server:**
```bash
cd companion/server
./gradlew run                       # serves DAYMARK_WEB_DIR (default: ./web)
./gradlew test                      # unit/integration tests
# Serve the built frontend:
DAYMARK_WEB_DIR=../web/dist ./gradlew run
```

## Configuration

All config is via `DAYMARK_*` environment variables (see [`.env.example`](.env.example)
and `server/.../Config.kt`). Milestone 1 consumes the serving knobs
(`DAYMARK_PORT`, `DAYMARK_BASE_PATH`, `DAYMARK_DATA_DIR`, `DAYMARK_WEB_DIR`,
`DAYMARK_LOG_LEVEL`); the sync/auth/share variables are reserved and documented but not
yet active.

## Security posture (what already holds)

- **Zero third-party origins.** The UI is fully vendored; a strict CSP
  (`default-src 'self'`, no `unsafe-inline`; one allowance: `wasm-unsafe-eval` for future
  in-browser crypto) is sent as a real header on every response.
- **No telemetry, no outbound calls by default.** Nothing phones home. The sole,
  owner-configured exception is optional outbound **SMTP** (off unless `DAYMARK_SMTP_*`
  is set) that sends therapist invite/notification **links only** — no record content —
  to the operator's own mail server over TLS, with credentials from `*_FILE` secrets.
- **Hardened container.** Non-root, read-only FS, `cap_drop: ALL`,
  `no-new-privileges`, healthcheck. (The runtime currently uses `temurin-jre` + `curl`
  for the healthcheck; a distroless runtime, a tiny static healthcheck binary, and
  pinned image digests are a documented follow-up — see the `Dockerfile` header.)
- **On-device by design.** The Phase-0 viewer reads your backup in the browser; the file
  never leaves the device.

What is **not** yet implemented (and must not be assumed): sync, the blob store,
authentication/MFA, therapist sharing, and game plans. Those land in later milestones per
the roadmap. Crypto-related hardening called for in the design (distroless runtime, pinned
digests, SBOM/provenance) is a follow-up.

## Licence

GPL-3.0-only, consistent with the app.
