# Daymark Companion

The optional, **self-hosted** companion server for Daymark — run on your own machine
(NAS, home server, mini-PC) with Docker. The phone app works fully without it and ships
**no network access by default**; the Companion is a convenience, never a requirement.

> **Status: Milestone 2 — sync.** Implemented: the hardened, reverse-proxy-friendly
> container; the **Phase-0 offline report viewer**; and now **end-to-end-encrypted sync** —
> a zero-knowledge blob-store API plus client-side XChaCha20-Poly1305/Argon2id crypto, with
> a browser "Connect to sync" reader and a reference CLI writer. **Still designed but not
> built:** the phone Sync *flavor* (Milestone 2b), the expanded sit-down features, therapist
> access, and game plans. See the design set in
> [`../docs/COMPANION_README.md`](../docs/COMPANION_README.md) and the wire spec in
> [`../docs/SYNC_PROTOCOL.md`](../docs/SYNC_PROTOCOL.md).

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
├── docker-compose.yml
├── reverse-proxy/  Worked Caddy / Traefik / nginx examples
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

## Quick start (Docker)

```bash
cd companion
docker compose up --build
# then open http://localhost:8080
```

The container runs **non-root** on a **read-only root filesystem** (only `/data` and a
`/tmp` tmpfs are writable), drops all Linux capabilities, and makes **no outbound
connections**. For real use, put it behind your reverse proxy and remove the `ports:`
block — see [`reverse-proxy/`](reverse-proxy/) and
[`../docs/COMPANION_DEPLOYMENT.md`](../docs/COMPANION_DEPLOYMENT.md).

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
- **No telemetry, no outbound calls.** Nothing phones home.
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
