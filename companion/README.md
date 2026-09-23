# Daymark Companion

The optional, **self-hosted** companion server for Daymark — run on your own machine (NAS, home
server, mini-PC) with Docker. It keeps an end-to-end-encrypted copy of your journal, lets you read it
on a bigger screen, and, if you want, lets you show chosen slices to one clinician. The server stores
ciphertext it cannot read. The phone app works fully without it and its default build has no network
access at all; the Companion is a convenience, never a requirement.

How it fits together, and what a compromised server could still do:
[`../docs/COMPANION_ARCHITECTURE.md`](../docs/COMPANION_ARCHITECTURE.md). The operator's full guide —
topology, the proxy contract, every setting, backup and restore:
[`../docs/COMPANION_DEPLOYMENT.md`](../docs/COMPANION_DEPLOYMENT.md).

## What works today

- **The hardened container**: non-root, read-only root filesystem, no capabilities, no outbound
  connections by default, and a health check.
- **Reading an exported backup file** in the browser. The file never leaves your device and no
  server is needed.
- **Encrypted sync.** The server stores append-only ciphertext; the browser reads and decrypts it.
  The writer today is the command-line tool below. **The phone does not sync yet.**
- **Self-checks and a focus task** (Steady Attention) in the browser — non-diagnostic, licence-clean,
  and never uploaded. Results stay on that device.
- **With `DAYMARK_THERAPIST_AUTH=1`:** the owner console (invite a clinician and pair with a spoken
  code, grant capabilities, share chosen slices, review what they assign, read the access log); the
  clinician's portal (sign-in with a six-digit authenticator code, the shared-data dashboard,
  assignments, game plans, leaving); and the practice console.
- **Optional email (SMTP)**, off unless configured: invitation links, owner notifications, and
  recovery of the owner's access token. Emails carry links and event names, never record content.

Not built, among others: passkeys (the WebAuthn routes answer 501; the six-digit code is the
clinician's sign-in), and the phone's side of sync and pairing (issue #138). The build state of each
feature is in the documents under [`../docs/`](../docs/), and the open work is in the issues.

## Quick start (Docker)

```bash
cd companion
cp .env.example .env          # set DAYMARK_DOMAIN (the real external hostname)
mkdir -p secrets && chmod 700 secrets
openssl rand -base64 48 | tr -d '\n' > secrets/auth_token
sudo chown 65532:65532 secrets/auth_token && chmod 400 secrets/auth_token
docker compose up -d --build
# then open http://localhost:8080
```

For one person and their own backup (Solo), leave `DAYMARK_THERAPIST_AUTH=0`. Set it to `1` only if a
clinician or a practice will use this machine; with it at 0 every relationship route answers 503.

## The access token, which switches sync on

The sync API is off until the server has an access token: `DAYMARK_AUTH_TOKEN`, or
`DAYMARK_AUTH_TOKEN_FILE` pointing at a secret (the compose file mounts `secrets/auth_token`). Without
one, every `/v1` sync route answers 503, and the server says so at startup in its own log. The file
must be readable by UID 65532: Docker secrets keep the host file's owner and mode, and the server
refuses to start, saying why, when it cannot read the file.

The token is the owner's credential: every client presents it, and it gates who may store and fetch
blobs. It decrypts nothing — the passphrase does that, and it never leaves your devices. If an owner
recovers access by email, the server rotates the token and the old one stops working; changing
`DAYMARK_AUTH_TOKEN` and restarting makes the new value the accepted one again.

## Health checks

- **`/healthz`** — liveness: the process is up.
- **`/readyz`** — readiness: and `/data` is still writable. It answers 503 when it is not: a
  read-only mount, a volume owned by the wrong UID, or a full disk.

The container's own `HEALTHCHECK` probes `/readyz`. Both are unauthenticated and say only
`{"ok":true}` or `{"ok":false}`; the reason goes to the server log.

## The data directory

Everything the server keeps lives in `DAYMARK_DATA_DIR` (`/data`), and **it must be writable by UID
65532**, the image's non-root user. The compose file uses a *named* volume, which Docker seeds with the
right ownership. A **bind mount** keeps the host directory's ownership, so a root-owned `/data` gives
`NOT READY: cannot write to /data (Permission denied)` and the app serves only the viewer and
`/healthz`. Container managers that generate their own compose tend to use bind mounts, so check this
first:

```bash
chown -R 65532:65532 /your/host/path/for/data
```

What is in `/data` and how to back it up and restore it: `../docs/COMPANION_DEPLOYMENT.md`.

## In front of it: your reverse proxy

**There is no bundled reverse proxy, deliberately.** The app speaks plain HTTP on `127.0.0.1:8080`
and expects yours — Cosmos Cloud, Caddy, Traefik, nginx, a Cloudflare tunnel — to terminate TLS in
front of it. What the proxy has to do:

- **Name it in `DAYMARK_TRUSTED_PROXIES`**, as its exact address. Left empty behind a proxy, every
  per-client control (sync lockout, sign-in lockout, the recovery rate limit) keys on the proxy's
  address, and one attacker can lock everyone out. Never a broad range.
- **Add `Strict-Transport-Security`.** The app sends every other security header but not HSTS,
  because it cannot know whether it is behind TLS.
- **Forward the root paths too.** `DAYMARK_BASE_PATH` moves only the web pages. `/healthz`,
  `/readyz` and the whole `/v1/…` API stay at the server root, so a proxy that forwards only a
  sub-path serves the pages and drops the API.
- **If the proxy is itself a container**, attach it to the app's network and use
  `docker-compose.no-egress.yml`, which removes the host port and leaves the app no route out at all.

The full contract is in `../docs/COMPANION_DEPLOYMENT.md`; worked configurations are in
[`../docs/alternatives/`](../docs/alternatives/).

## Before you rely on it

None of this is observable from a web page, so it is a list to check by hand:

- The access token is set, and its file is readable by UID 65532.
- `/readyz` answers 200, so `/data` is writable.
- `DAYMARK_DOMAIN` is the real external hostname: invitation and recovery links are built from it.
- The proxy terminates TLS, sets HSTS, forwards the root paths, and is named exactly in
  `DAYMARK_TRUSTED_PROXIES`.
- `DAYMARK_THERAPIST_AUTH` is 1 only if a clinician or practice uses this machine.
- `/data` is backed up, and you have tried a restore.
- The image is pinned by digest unless you want it to update itself (below).
- SMTP stays off unless you need email; enabling it opens the one deliberate outbound path.

## Push a backup from your laptop

Until the phone can sync, the command-line writer encrypts an exported Daymark backup with your sync
passphrase and uploads it as the next append-only version. The passphrase is read from the
environment, never from the command line. The wire format is
[`../docs/SYNC_PROTOCOL.md`](../docs/SYNC_PROTOCOL.md).

```bash
cd companion/web && pnpm install
DAYMARK_SYNC_PASSPHRASE='your sync passphrase' \
  pnpm push -- --server http://localhost:8080 --token "$DAYMARK_AUTH_TOKEN" \
              --lineage laptop --backup ~/Downloads/daymark-backup.json
```

Read it back in the browser: open the portal → **Connect to your sync server** → the token, the
lineage and the passphrase. The snapshot is fetched and decrypted in your browser.

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
manager auto-updates (Cosmos, Watchtower, a Portainer stack that repulls) and you want that.

Pin the **digest** when you do not want that. A moving tag plus an auto-updating manager is how a
server changes behaviour overnight with no diff to look at, and this application carries a
migrating database: an update can change on-disk state in ways that do not reverse by rolling the
image back. **Take a backup before an update you did not choose the timing of.** The exact digest
for any build is printed in that run's summary on the Actions tab.

Only builds from `main` move `:latest` and `:main`. A manually dispatched build of a branch
publishes its `sha-` tag and nothing else.

**One-time setup:** GHCR packages are created private even for a public repository. After the first
publish, open the package in GitHub → Package settings → change visibility to public. Otherwise
every pull needs `docker login ghcr.io` with a token carrying `read:packages`.

To run the published image from this compose file rather than building:

```bash
# Pinned (recommended for anything you rely on):
export DAYMARK_IMAGE=ghcr.io/keyxmakerx/daymark-companion@sha256:<digest>

# Or following main:
export DAYMARK_IMAGE=ghcr.io/keyxmakerx/daymark-companion:latest

docker compose pull && docker compose up -d
```

Building it yourself stays fully supported and is the default. The image is **linux/amd64 only**;
an arm64 host (a Pi, an Apple-silicon machine) needs a local build.

## Local development

**Frontend:**
```bash
cd companion/web
pnpm install
pnpm dev        # Vite dev server with HMR
pnpm test       # unit tests
pnpm check      # type-check
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

All configuration is `DAYMARK_*` environment variables: [`.env.example`](.env.example) for the
common ones with their reasons, `server/src/main/kotlin/com/daymark/companion/Config.kt` for every
one, and the table in `../docs/COMPANION_DEPLOYMENT.md`.

## Instruments

The self-checks' licence ledger is [`INSTRUMENTS.md`](INSTRUMENTS.md); a test reads it as data.

## Licence

GPL-3.0-only, consistent with the app.
