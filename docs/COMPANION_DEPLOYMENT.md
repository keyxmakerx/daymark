# Daymark Companion — Deployment (Docker / Compose / Reverse Proxy)

> ## ⚠️ STATUS: DESIGN ONLY — NO CODE EXISTS YET
>
> **Nothing in this document is implemented.** There is no `ghcr.io/daymark/companion`
> image, no server binary, no `daymark-companion` container, no "Daymark Sync" build
> flavor, and no published digest. Every `docker-compose.yml`, environment variable,
> reverse-proxy snippet, ASCII diagram, and table below describes an **intended**
> deployment design still under review. Image names, digests, env-var names, ports,
> and defaults are **placeholders** and may change or be dropped entirely.
>
> The flagship Daymark app remains **fully offline, declares no `INTERNET`
> permission, and operates 100% on-device** (see [../PRIVACY.md](../PRIVACY.md) and
> [../SECURITY.md](../SECURITY.md)). Everything in this document lives **only** in a
> separate, opt-in **Daymark Sync** flavor and the self-hosted Companion container,
> and never alters that default. Whether the owner→therapist sharing and game-plan
> tracks ship at all in the first Companion release — or whether sync lands first —
> is an unresolved sequencing decision; see [COMPANION_SCOPE.md](COMPANION_SCOPE.md).
>
> This document is the **deployment / operations** track. For *what the Companion is*
> see [COMPANION_SCOPE.md](COMPANION_SCOPE.md); for *how it is built* see
> [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md); for the *threat model and
> crypto contract* see [COMPANION_SECURITY.md](COMPANION_SECURITY.md); for the
> *clinician-facing surface* see [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md).

---

## Contents

- [0. Deployment principles](#0-deployment-principles-what-every-choice-optimizes-for)
- [1. Topologies (LAN-only vs public)](#1-topologies-lan-only-vs-public)
- [2. The canonical `docker-compose.yml` (Topology A)](#2-the-canonical-docker-composeyml-topology-a)
- [3. Topology B & C variants (single container)](#3-topology-b--c-variants-single-container)
- [4. Reverse-proxy worked examples (Caddy / Traefik / nginx)](#4-reverse-proxy-worked-examples)
- [5. Configuration & secrets surface](#5-configuration--secrets-surface)
- [6. Volume, backup & restore](#6-volume-backup--restore)
- [7. Upgrades & migrations](#7-upgrades--migrations)
- [8. Network egress lockdown](#8-network-egress-lockdown)
- [9. First-run checklist](#9-first-run-checklist-topology-a)
- [10. Hardening defaults (one table)](#10-hardening-defaults-one-table)
- [11. What deployment hardening does NOT buy you](#11-what-deployment-hardening-does-not-buy-you-honesty-section)

---

## 0. Deployment principles (what every choice optimizes for)

The Companion container is a **dumb, zero-knowledge ciphertext blob host + policy
mediator**. It never holds a passphrase, a private key, a reading key, or any
plaintext. Every hardening choice below is designed so that one sentence stays true:

> **A full compromise of this container and its disk leaks only opaque ciphertext
> blobs plus non-secret routing metadata** (blob ids, sizes, timestamps, version
> counters, expiry/revoke flags, and per-relationship inbox tokens).

Note the deliberate phrasing — *"opaque ciphertext + non-secret metadata,"* **not**
*"nothing."* The index reveals a relationship graph and traffic-analysis surface that
no amount of container hardening hides; see [§11](#11-what-deployment-hardening-does-not-buy-you-honesty-section)
and [COMPANION_SECURITY.md](COMPANION_SECURITY.md). With that boundary stated:

1. **Compose-first.** A single `docker-compose.yml` is the canonical install. One
   auditable file documents the image, the (lack of) network egress, the volume, the
   resource limits, and the entire config surface in one place.
2. **Reverse-proxy-friendly, but proxy-optional.** The container terminates plain
   HTTP on a single internal port and is happy behind Caddy / Traefik / nginx. It
   also has a LAN-only self-signed mode for users with no proxy. TLS is **defense in
   depth** — payloads are already end-to-end encrypted, so a broken TLS layer leaks
   only ciphertext.
3. **No outbound network by default.** The app needs zero egress. The compose file
   puts the container on its **own** `internal: true` bridge so the no-telemetry
   claim is enforced at the network layer, not merely promised. See [§8](#8-network-egress-lockdown)
   for the structural-honesty caveat about *sharing* a network with the proxy.
   **One deliberate exception:** owner-configured outbound **SMTP** for therapist
   invite/notification links. It is **OFF unless `DAYMARK_SMTP_HOST` is set**; when
   enabled it egresses only to the operator's configured mail server, requires TLS
   (`DAYMARK_SMTP_TLS=starttls|implicit`), reads credentials via `DAYMARK_SMTP_PASS_FILE`,
   and its emails carry **only** the invite/notification link — never any record or
   plaintext content. Enabling it means the operator must open a narrow egress path to
   exactly that mail host (see [§8](#8-network-egress-lockdown)); it does not reopen
   general egress.
4. **Least privilege at the container boundary.** Non-root, read-only rootfs, all
   capabilities dropped, `no-new-privileges`, and a single writable named volume. A
   bug in the API cannot write outside the blob store, escalate, or persist a webshell.
5. **Env-driven, secret-safe config.** Everything is configured by environment
   variables; every secret supports a `*_FILE` indirection so it can come from a
   Docker secret or a mounted file, never baked into the image or surfaced in
   `docker inspect`.
6. **The server is a convenience replica.** The phone (native **Daymark Sync** flavor)
   holds the authoritative copy. Backups, upgrades, and migrations are designed so
   that losing the container never loses owner data, and an upgrade can never
   silently mutate or re-encrypt ciphertext.

---

## 1. Topologies (LAN-only vs public)

Pick one. All three use the **same image and the same `companion` service**; they
differ only in how TLS and ingress are handled.

> **STATUS — WebAuthn/passkey + PRF is a config-pinned SCAFFOLD in this build.** The
> server's `/v1/webauthn/register/*` and `/v1/webauthn/assert/*` endpoints return **501
> Not Implemented** (attestation/assertion verification is out of scope for this slice;
> see `TherapistAuthRoutes.kt` and the `Config.kt` "verification is scaffold-only"
> note). **TOTP is the only functioning therapist auth path today.** The RP-ID / origin
> columns and `DAYMARK_WEBAUTHN_*` rows below describe how the passkey path *will* be
> pinned when implemented — they are **config plumbing, not a working login path yet**.
> A hardware passkey (WebAuthn) is the stronger path when available; until this scaffold
> is completed, plan for TOTP. (Mirrors the in-app LowerAssuranceBanner copy.)

| Topology | When | TLS | Network exposure | WebAuthn? |
|---|---|---|---|---|
| **A. Container + reverse proxy** (recommended, public or LAN) | You already run Caddy/Traefik/nginx, or want a real cert, a hostname, or a sub-path. | Terminated at the proxy (Let's Encrypt or internal CA). | Only the proxy is published; the companion listens on an internal-only network. | 🚧 Scaffold (501) — real origin *would* work once implemented; **use TOTP today.** |
| **B. Single container, LAN-only self-signed** | Home LAN / NAS, no proxy, "just works." | Self-signed cert generated on first boot, or a static cert you mount. | Companion publishes `:8443` directly to the LAN. | 🚧 Scaffold (501) — and even once built, bare-IP / `.local` = **TOTP only** (see note). **Use TOTP today.** |
| **C. Single container, loopback / behind host firewall** | Advanced; you front it with the host's own nginx or an SSH tunnel. | None in-container (plain HTTP bound to `127.0.0.1`). | Bound to loopback only; never reachable off-box without a tunnel/proxy. | 🚧 Scaffold (501). **Use TOTP today.** |

**Default recommendation: Topology A.** It gives a real certificate, clean
`X-Forwarded-*` handling, sub-path mounting, and keeps the companion off the
published-port surface. Topology B is the documented fallback for proxy-less LAN
users.

> **WebAuthn / RP-ID reality (LAN & bare-IP self-hosters).** *(Applies once the
> WebAuthn scaffold above is implemented — the endpoints return 501 in this build, so
> today every deployment uses the TOTP path regardless of RP-ID.)* WebAuthn requires a
> *secure origin* with a real registrable domain. Deployments on a bare IP
> (`https://192.168.1.10`), an `.local` mDNS name, or a self-signed cert the OS does
> not trust **cannot register passkeys** in most browsers. Those deployments are
> **officially documented-as-unsupported for the WebAuthn-PRF path**; the therapist
> must use the **TOTP fallback** (a weaker, server-stored-Argon2id-hash custody path,
> honestly flagged in [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §limits). The
> RP-ID and origin allowlist are **config-pinned** (`DAYMARK_WEBAUTHN_RP_ID` +
> origin allowlist), **never** derived from a client-controllable `Host` /
> `X-Forwarded-Host`. Give the box a real hostname (even a LAN-internal one with an
> internal-CA cert, Topology A) to keep passkeys working.

### 1.1 Picture

```
Topology A (proxy):                              Topology B (LAN self-signed):

  Internet / LAN                                  LAN
       │ 443                                       │ 8443 (HTTPS, self-signed)
  ┌────▼─────┐                              ┌──────▼─────────────┐
  │  Caddy   │  edge net (egress-capable)   │ daymark-companion  │
  │ /Traefik │═══════════════╗              │  (built-in TLS)    │
  │ /nginx   │               ║ multi-homed  │  read-only rootfs  │
  └────┬─────┘               ║              │  non-root, no egr. │
       │  http://companion:8080             └─────────┬──────────┘
  ┌────▼───────────────┐     ║                         │ volume
  │ daymark-companion  │◀════╝               ┌─────────▼────────┐
  │  companion-internal│                     │ blobs (named)    │
  │  internal: true    │                     └──────────────────┘
  │  NO route to gw    │
  └────────┬───────────┘
           │ volume
  ┌────────▼────────┐
  │ blobs (named)   │
  └─────────────────┘

  The proxy is MULTI-HOMED: it joins both the egress-capable 'edge' net AND the
  companion's OWN 'companion-internal' (internal:true) net. The companion joins
  ONLY 'companion-internal', so it has no path to the gateway. See §8.
```

---

## 2. The canonical `docker-compose.yml` (Topology A)

This is the reference compose file, behind an external reverse proxy. It encodes the
[§10 hardening defaults](#10-hardening-defaults-one-table). Topology B/C variants
follow in [§3](#3-topology-b--c-variants-single-container).

```yaml
# docker-compose.yml — Daymark Companion (Topology A: behind your reverse proxy)
# DESIGN ONLY — image, digest, and env names are placeholders; no code exists yet.
#
# Zero-knowledge ciphertext host. The container never sees plaintext, keys, or the
# passphrase. A full compromise of this container/volume must leak only opaque
# blobs + non-secret metadata.

services:
  companion:
    image: ghcr.io/daymark/companion@sha256:REPLACE_WITH_PINNED_DIGEST  # pin by DIGEST, never :latest
    container_name: daymark-companion
    restart: unless-stopped

    # ---- Identity / privileges -------------------------------------------------
    # Run as a fixed non-root UID:GID. Must match ownership of the named volume (§6.3).
    user: "10001:10001"

    # ---- Filesystem: read-only rootfs, single writable volume ------------------
    read_only: true
    volumes:
      - blobs:/data                       # ONLY writable persistent path: ciphertext + SQLite index
    tmpfs:
      - /tmp:size=16m,mode=1777           # scratch (multipart spill, temp); never persisted
      - /run:size=4m                      # for the secret mount + any runtime sockets

    # ---- Hardening -------------------------------------------------------------
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL                               # the app binds an unprivileged port; needs no Linux caps
    # cap_add: []                         # intentionally empty

    # ---- Networking: NO egress by default --------------------------------------
    # The companion joins ONLY its own internal:true network. The proxy is
    # multi-homed (joins this net AND the egress-capable edge net). The companion
    # therefore has no route to the gateway. See §8 for the structural-honesty note.
    networks:
      - companion-internal
    expose:
      - "8080"                            # documents the in-container HTTP port; NOT host-published
    # NOTE: do NOT publish ports here in Topology A. The proxy reaches it over the internal net.

    # ---- Configuration (env-driven; secrets via *_FILE, see §5) ----------------
    environment:
      DAYMARK_BIND_ADDR: "0.0.0.0"        # listen on all NICs *inside* the container only
      DAYMARK_PORT: "8080"
      DAYMARK_TLS_MODE: "off"             # proxy terminates TLS; container speaks plain HTTP internally
      DAYMARK_DATA_DIR: "/data"
      DAYMARK_BASE_PATH: "/"              # set to e.g. "/daymark" to serve under a sub-path (see §4.4)

      # --- Trusted-proxy contract (see §4.0) ---
      # DEFAULT IS TRUST-NONE. You MUST set this to the single narrow address/CIDR of
      # your proxy on the internal net. There is NO broad default (a 172.16.0.0/12
      # default would let any co-resident container forge X-Forwarded-*).
      DAYMARK_TRUSTED_PROXIES: "10.89.0.2/32"     # <-- the proxy's IP on companion-internal; EXAMPLE ONLY
      DAYMARK_FORWARDED_HEADERS: "x-forwarded"    # honor X-Forwarded-* ONLY from the pinned proxy

      # --- WebAuthn: config-pinned, never client-derived (see §1 note, §4.4) ---
      DAYMARK_WEBAUTHN_RP_ID: "daymark.example.com"
      DAYMARK_WEBAUTHN_ORIGINS: "https://daymark.example.com"   # exact origin allowlist

      # --- Server auth token (gates PUT/list/enumerate). Loaded from a file: ---
      DAYMARK_AUTH_TOKEN_FILE: "/run/secrets/companion_auth_token"
      DAYMARK_TOTP_ISSUER: "Daymark Companion"

      # --- Limits / DoS hygiene (a token-holder is an integrity/availability weapon) ---
      DAYMARK_MAX_BLOB_BYTES: "26214400"  # 25 MiB per blob
      DAYMARK_MAX_REQUEST_BYTES: "27262976"
      DAYMARK_MAX_VERSIONS: "200"         # append-only retention cap per snapshot lineage
      DAYMARK_PER_TOKEN_QUOTA_BYTES: "5368709120"  # 5 GiB per-token storage quota; fail-closed on full
      DAYMARK_RATE_LIMIT_RPS: "5"
      DAYMARK_LINEAGE_CREATE_RPM: "10"    # cap lineage/version creation (anti monotonic-poisoning)
      DAYMARK_AUTH_LOCKOUT_FAILS: "8"
      DAYMARK_AUTH_LOCKOUT_SECONDS: "900"

      # --- Sharing / retention (bounds the no-PFS harvest-now-decrypt-later window) ---
      DAYMARK_SHARE_MAX_TTL_DAYS: "90"    # ceiling on share/game-plan expiry the owner can request
      DAYMARK_BLOB_HARD_DELETE: "on-supersede-and-expiry"  # hard-delete superseded/expired blob BYTES
      DAYMARK_SIZE_PADDING: "bucketed"    # pad blobs to fixed buckets BY DEFAULT (acuity/withdrawal anti-deanon)

      # --- Audit (events not content; owner-readable; IP off-by-default) ---
      DAYMARK_ACCESS_LOG_RETENTION_DAYS: "90"
      DAYMARK_ACCESS_LOG_SOURCE_IP: "off" # IP geolocates the clinic; off by default
      DAYMARK_LOG_LEVEL: "info"
      TZ: "UTC"

    # ---- Secrets ---------------------------------------------------------------
    secrets:
      - companion_auth_token              # mounted at /run/secrets/companion_auth_token (mode 0400)

    # ---- Health ----------------------------------------------------------------
    # Healthcheck hits an unauthenticated, content-free liveness endpoint returning
    # 200 + {"ok":true}. Uses a tiny bundled static /healthcheck binary so the image
    # ships no curl/wget and the probe leaks nothing.
    healthcheck:
      test: ["CMD", "/healthcheck", "http://127.0.0.1:8080/healthz"]
      interval: 30s
      timeout: 3s
      retries: 3
      start_period: 15s

    # ---- Resource limits -------------------------------------------------------
    deploy:
      resources:
        limits:
          cpus: "1.0"
          memory: 256M
        reservations:
          memory: 64M
    # Non-Swarm extras (honored by `docker compose`):
    mem_limit: 256m
    pids_limit: 256
    ulimits:
      nofile:
        soft: 1024
        hard: 2048
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "5"

networks:
  companion-internal:
    # The companion's OWN network. internal:true → NO route to the gateway → no egress.
    # The reverse proxy ALSO joins this network (multi-homed) to reach the companion,
    # while keeping its public/egress NICs on a SEPARATE 'edge' network in ITS compose.
    internal: true

volumes:
  blobs:
    # Named volume → trivial backup (just files: ciphertext blobs + the SQLite index).

secrets:
  companion_auth_token:
    file: ./secrets/companion_auth_token   # 0400 host file; or external/swarm secret (§5)
```

**Why these choices, briefly**

- `read_only: true` + a single `blobs:/data` volume means the *only* place the
  process can write is the ciphertext store. A compromised process cannot drop a
  webshell into the asset dir or rewrite the binary.
- The dedicated `internal: true` network is the structural enforcement of "no
  telemetry, ever" — **but only if the companion does not also share an
  egress-capable network**; see the honesty caveat in [§8](#8-network-egress-lockdown).
- `cap_drop: ALL` + `no-new-privileges` + non-root UID = no privilege to abuse even
  with code execution.
- `tmpfs /tmp` keeps transient scratch off the persistent volume and out of backups.
- Secrets via file mount keep the token out of `docker inspect`, image layers, and
  process env dumps.
- The DoS-hygiene limits exist because **a bare token-holder (no E2EE key) is an
  integrity/availability weapon**: it can PUT garbage versions to evict real history
  (append-only-prune eviction DoS), exhaust disk, or poison version counters. Per-token
  quota + fail-closed-on-disk-full + lineage/version caps + server-derived blob paths
  close those. See [COMPANION_SECURITY.md](COMPANION_SECURITY.md).

---

## 3. Topology B & C variants (single container)

### 3.1 Topology B — LAN-only, container-terminated self-signed TLS

Use when there is no proxy. The container generates a self-signed cert on first boot
(persisted to the volume) **or** you mount your own cert/key. Publish `8443` to the LAN.

```yaml
# docker-compose.lan.yml — Topology B: LAN-only, self-signed TLS, no proxy
# DESIGN ONLY.
services:
  companion:
    image: ghcr.io/daymark/companion@sha256:REPLACE_WITH_PINNED_DIGEST
    container_name: daymark-companion
    restart: unless-stopped
    user: "10001:10001"
    read_only: true
    volumes:
      - blobs:/data
      - certs:/data/tls            # cert+key persist across restarts
    tmpfs:
      - /tmp:size=16m,mode=1777
      - /run:size=4m
    security_opt: ["no-new-privileges:true"]
    cap_drop: ["ALL"]
    ports:
      - "8443:8443"                # published to the LAN; bind to a specific host IP if desired:
      # - "192.168.1.10:8443:8443"
    environment:
      DAYMARK_BIND_ADDR: "0.0.0.0"
      DAYMARK_PORT: "8443"
      DAYMARK_TLS_MODE: "self-signed"   # 'self-signed' | 'static' | 'off'
      DAYMARK_TLS_CERT_DIR: "/data/tls" # where self-signed (auto) or static (mounted) cert lives
      DAYMARK_TLS_SAN: "daymark.lan,192.168.1.10"  # SANs baked into the auto cert
      DAYMARK_DATA_DIR: "/data"
      DAYMARK_BASE_PATH: "/"
      # WebAuthn only works if 'daymark.lan' is a real, device-trusted name (see §1 note):
      DAYMARK_WEBAUTHN_RP_ID: "daymark.lan"
      DAYMARK_WEBAUTHN_ORIGINS: "https://daymark.lan:8443"
      # No proxy here → trust NO forwarded headers from anyone:
      DAYMARK_TRUSTED_PROXIES: ""
      DAYMARK_FORWARDED_HEADERS: "none"
      DAYMARK_AUTH_TOKEN_FILE: "/run/secrets/companion_auth_token"
      DAYMARK_MAX_BLOB_BYTES: "26214400"
      DAYMARK_SHARE_MAX_TTL_DAYS: "90"
      TZ: "UTC"
    secrets: ["companion_auth_token"]
    healthcheck:
      # self-signed → the checker accepts the local cert for a loopback liveness probe only
      test: ["CMD", "/healthcheck", "--insecure", "https://127.0.0.1:8443/healthz"]
      interval: 30s
      timeout: 3s
      retries: 3
      start_period: 15s
    mem_limit: 256m
    pids_limit: 256

volumes:
  blobs:
  certs:

secrets:
  companion_auth_token:
    file: ./secrets/companion_auth_token
```

> **LAN self-signed caveat (stated honestly).** A self-signed cert means the
> phone/browser must trust it (import the CA, or accept the fingerprint once). The
> owner should verify the cert fingerprint **out-of-band** the first time, exactly
> like the therapist-key TOFU pairing step (see
> [COMPANION_SECURITY.md](COMPANION_SECURITY.md)). Self-signed protects against
> *passive* LAN snooping but **not** against an active MITM who presents their own
> self-signed cert — so **mount a real cert (`DAYMARK_TLS_MODE: static`) or use
> Topology A for anything beyond a trusted home LAN.** Payloads are E2EE regardless,
> so a broken TLS layer leaks only ciphertext.

**Mounting your own cert (`static` mode):**

```yaml
    environment:
      DAYMARK_TLS_MODE: "static"
      DAYMARK_TLS_CERT_DIR: "/data/tls"
    volumes:
      - ./tls/fullchain.pem:/data/tls/fullchain.pem:ro
      - ./tls/privkey.pem:/data/tls/privkey.pem:ro
```

### 3.2 Topology C — loopback only (front it with host nginx / SSH tunnel)

```yaml
# Fragment — Topology C: bind to loopback, front with host nginx or an SSH tunnel.
    environment:
      DAYMARK_TLS_MODE: "off"
      DAYMARK_PORT: "8080"
      DAYMARK_TRUSTED_PROXIES: "127.0.0.1/32"
      DAYMARK_FORWARDED_HEADERS: "x-forwarded"
    ports:
      - "127.0.0.1:8080:8080"    # reachable only from the host; tunnel or host-nginx in front
```

---

## 4. Reverse-proxy worked examples

All examples assume the companion is reachable from the proxy as
`http://companion:8080` on the shared internal network. Each covers upstream TLS
termination, `X-Forwarded-*` / trusted-proxy handling, and a sub-path mount variant.

### 4.0 Trusted-proxy contract (read first)

> **Default is TRUST-NONE.** The companion derives the client's "secure?" state,
> rate-limit identity, lockout key, and audit source-IP from the **socket peer** by
> default. It honors `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-For`
> **only** when the immediate peer is inside `DAYMARK_TRUSTED_PROXIES` — which you
> must set explicitly to the single narrow address/CIDR of your proxy. **There is no
> broad shipped default.** (An earlier draft shipped `172.16.0.0/12`; it is
> **removed** because it lets any co-resident Docker container forge `X-Forwarded-*`.)

The proxy MUST **strip** any inbound `X-Forwarded-For` / `X-Real-IP` /
`X-Forwarded-Host` / `Forwarded` from clients before setting its own.

| Header | Companion uses it for (only when proxy is trusted) | Risk if spoofed (untrusted) |
|---|---|---|
| `X-Forwarded-Proto` | Deciding if the connection is "secure"; the HTTPS-required-for-non-loopback gate; `Secure` cookies / HSTS. | Bypass the no-plain-HTTP guard. |
| `X-Forwarded-Host` | Building absolute URLs *only*. **NOT** the WebAuthn RP-ID/origin — those are config-pinned. | URL confusion (WebAuthn unaffected: pinned). |
| `X-Forwarded-For` | Rate-limit & lockout keying; access-log source. | Evade lockout (the sole brute-force defense for the token) / poison the access log. |

**Rule:** set `DAYMARK_TRUSTED_PROXIES` to the *narrowest* CIDR (ideally a `/32`)
that contains your proxy's address on the internal network. WebAuthn RP-ID/origin is
**never** taken from a forwarded header — it comes from
`DAYMARK_WEBAUTHN_RP_ID` / `DAYMARK_WEBAUTHN_ORIGINS`.

### 4.1 Caddy

Caddy sets the forwarded headers correctly by default and does ACME automatically.

```caddyfile
# Caddyfile — TLS termination + Let's Encrypt, reverse-proxy to the companion
daymark.example.com {
    encode zstd gzip

    # Security headers at the edge (the app also sends a strict CSP; these reinforce it).
    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains"
        X-Content-Type-Options "nosniff"
        Referrer-Policy "no-referrer"
        -Server
    }

    reverse_proxy companion:8080 {
        # Caddy strips client-supplied X-Forwarded-* and sets its own from the real connection.
        header_up X-Forwarded-Proto {scheme}
        header_up X-Forwarded-Host  {host}
    }
}
```

**Sub-path mount (serve under `https://host/daymark`):**

```caddyfile
daymark.example.com {
    handle_path /daymark/* {
        reverse_proxy companion:8080 {
            header_up X-Forwarded-Proto  {scheme}
            header_up X-Forwarded-Host   {host}
            header_up X-Forwarded-Prefix /daymark
        }
    }
}
```

> Set `DAYMARK_BASE_PATH: "/daymark"` so the app emits asset URLs and portal routes
> under that prefix. `handle_path` strips the prefix before proxying; the app re-adds
> it via `BASE_PATH` / `X-Forwarded-Prefix` when generating links (see §4.4).

For **internal-CA / LAN with a real hostname** (keeps WebAuthn working), point Caddy
at its built-in CA instead of ACME:

```caddyfile
{
    pki {
        ca internal { }
    }
}
daymark.lan {
    tls internal              # Caddy's built-in CA; trust Caddy's root on your devices
    reverse_proxy companion:8080
}
```

### 4.2 Traefik

Labels on the companion service; Traefik discovers it via the Docker provider.

```yaml
# traefik static config (traefik.yml) — relevant bits
entryPoints:
  websecure:
    address: ":443"
    forwardedHeaders:
      # Mirror of DAYMARK_TRUSTED_PROXIES: trust X-Forwarded-* only from Traefik itself.
      # Pin the NARROWEST address; do NOT use a broad bridge CIDR.
      trustedIPs: ["10.89.0.2/32"]   # EXAMPLE: Traefik's address on the internal net
certificatesResolvers:
  le:
    acme:
      email: you@example.com
      storage: /acme/acme.json
      httpChallenge:
        entryPoint: web
providers:
  docker:
    exposedByDefault: false
```

```yaml
# companion service labels (add to the compose service in §2)
    labels:
      traefik.enable: "true"
      traefik.docker.network: "companion-internal"
      traefik.http.routers.daymark.rule: "Host(`daymark.example.com`)"
      traefik.http.routers.daymark.entrypoints: "websecure"
      traefik.http.routers.daymark.tls.certresolver: "le"
      traefik.http.services.daymark.loadbalancer.server.port: "8080"
      # Security headers middleware:
      traefik.http.middlewares.daymark-sec.headers.stsSeconds: "31536000"
      traefik.http.middlewares.daymark-sec.headers.contentTypeNosniff: "true"
      traefik.http.middlewares.daymark-sec.headers.referrerPolicy: "no-referrer"
      traefik.http.routers.daymark.middlewares: "daymark-sec@docker"
```

**Sub-path mount (`/daymark`) — strip the prefix with a middleware:**

```yaml
    labels:
      traefik.http.routers.daymark.rule: "Host(`daymark.example.com`) && PathPrefix(`/daymark`)"
      traefik.http.middlewares.daymark-strip.stripprefix.prefixes: "/daymark"
      traefik.http.routers.daymark.middlewares: "daymark-strip@docker,daymark-sec@docker"
```

> Traefik forwards `X-Forwarded-Prefix: /daymark` after stripping; the app reads it
> (or `DAYMARK_BASE_PATH`) to rebuild absolute links. Set
> `DAYMARK_BASE_PATH: "/daymark"` to match.
> `entryPoints.websecure.forwardedHeaders.trustedIPs` is the Traefik-side mirror of
> `DAYMARK_TRUSTED_PROXIES` — keep them consistent and **narrow**.

### 4.3 nginx

```nginx
# /etc/nginx/conf.d/daymark.conf — TLS termination + reverse proxy
# nginx IS the trust boundary here, so it SETS the forwarded headers, overwriting
# any client-supplied values (the strip step).

upstream daymark_companion {
    server companion:8080;        # resolvable on the shared docker network, or 127.0.0.1:8080 on host
    keepalive 16;
}

server {
    listen 443 ssl;
    http2 on;
    server_name daymark.example.com;     # WebAuthn RP-ID is config-pinned to this; do NOT use $host

    ssl_certificate     /etc/letsencrypt/live/daymark.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/daymark.example.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "no-referrer" always;
    server_tokens off;

    client_max_body_size 26m;     # must be >= DAYMARK_MAX_BLOB_BYTES (25 MiB) or PUTs 413

    location / {
        proxy_pass http://daymark_companion;
        proxy_http_version 1.1;
        proxy_set_header Connection "";

        # Forwarded headers — nginx OVERWRITES any client-supplied values (the strip):
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $remote_addr;     # single trusted hop → use peer, not the chain
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;

        # Allow time for large blob PUTs; the app streams to disk.
        proxy_request_buffering off;
        proxy_read_timeout 60s;
    }
}

# Redirect plain HTTP → HTTPS (WebAuthn needs a secure origin)
server {
    listen 80;
    server_name daymark.example.com;
    return 308 https://$host$request_uri;
}
```

**Sub-path mount (`/daymark`):**

```nginx
    location /daymark/ {
        proxy_pass http://daymark_companion/;   # trailing slash strips the /daymark/ prefix
        proxy_set_header X-Forwarded-Prefix /daymark;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;
    }
```

> Set `DAYMARK_BASE_PATH: "/daymark"`. The `proxy_pass …/` trailing-slash form strips
> the prefix on the way in; `X-Forwarded-Prefix` lets the app re-add it when
> generating absolute URLs.
>
> **`X-Forwarded-For` note:** because nginx is the only trusted hop, set it to
> `$remote_addr` (the real client) rather than `$proxy_add_x_forwarded_for` (which
> *appends* to a possibly client-spoofed chain). `DAYMARK_TRUSTED_PROXIES` should be
> just the nginx address (the internal-net IP, or `127.0.0.1/32` for host-nginx).

### 4.4 Sub-path / base-path: the app's responsibilities

Serving under a prefix is split between proxy and app:

| Concern | Proxy does | App does (via `DAYMARK_BASE_PATH` / `X-Forwarded-Prefix`) |
|---|---|---|
| Route matching | `PathPrefix(/daymark)` / `location /daymark/` | Mounts all routes under `${BASE_PATH}`. |
| Prefix stripping | `handle_path` / `stripprefix` / trailing-slash `proxy_pass` | Accepts the stripped path. |
| Asset URLs | passes `X-Forwarded-Prefix` | Emits `<base href>` / asset `src` with the prefix so static JS/WASM load. |
| WebAuthn origin | passes real `X-Forwarded-Proto`/`Host` for *URLs only* | Verifies the assertion origin against the **config-pinned** `DAYMARK_WEBAUTHN_ORIGINS`, **exactly** — not the forwarded header. |
| Cookies / CSP | — | `Path=${BASE_PATH}` on the session cookie; CSP `base-uri 'self'`. |

> **WebAuthn pitfall (most common proxy failure).** The RP-ID is a registrable
> domain (`daymark.example.com`), independent of the sub-path, and is **pinned in
> config**. The **origin** the app verifies an assertion against is also
> **config-pinned** (`DAYMARK_WEBAUTHN_ORIGINS`), and must *exactly* equal what the
> browser sends (scheme + host + port). If the pinned origin does not match the real
> external URL, passkey registration/assertion fails with an origin-mismatch error.
> Set `DAYMARK_WEBAUTHN_RP_ID` / `DAYMARK_WEBAUTHN_ORIGINS` to your real external
> values; do **not** rely on the app inferring them from `Host` / `X-Forwarded-Host`
> (which a client could control).

---

## 5. Configuration & secrets surface

### 5.1 Full environment variable reference

| Variable | Default | Purpose |
|---|---|---|
| `DAYMARK_BIND_ADDR` | `0.0.0.0` | Listen address *inside* the container. |
| `DAYMARK_PORT` | `8080` | In-container listen port. |
| `DAYMARK_DATA_DIR` | `/data` | Root of the writable volume (blobs + `index.sqlite`). |
| `DAYMARK_BASE_PATH` | `/` | Sub-path prefix for routes/assets (e.g. `/daymark`). |
| `DAYMARK_TLS_MODE` | `off` | `off` (proxy terminates) \| `self-signed` (auto cert) \| `static` (mounted cert). |
| `DAYMARK_TLS_CERT_DIR` | `/data/tls` | Where the self-signed cert is generated or the static cert is read. |
| `DAYMARK_TLS_SAN` | _(host)_ | Comma-list of SANs for the auto self-signed cert. |
| `DAYMARK_TRUSTED_PROXIES` | _(empty = trust none)_ | CIDRs whose `X-Forwarded-*` are honored. **No broad default.** Pin a `/32`. |
| `DAYMARK_FORWARDED_HEADERS` | `none` | `x-forwarded` \| `forwarded` \| `none`. |
| `DAYMARK_WEBAUTHN_RP_ID` | _(SCAFFOLD — WebAuthn endpoints return 501; TOTP is the only working path today)_ | Config-pinned RP-ID; never client-derived. Pins the *future* passkey path so it can never regress to `Host`-header derivation. |
| `DAYMARK_WEBAUTHN_ORIGINS` | _(SCAFFOLD — WebAuthn endpoints return 501; TOTP is the only working path today)_ | Exact origin allowlist for assertion verification (used once the scaffold is implemented). |
| `DAYMARK_AUTH_TOKEN` / `…_FILE` | _(required)_ | Server access token (gates PUT/list/enumerate). Prefer `_FILE`. |
| `DAYMARK_TOTP_ISSUER` | `Daymark Companion` | Issuer label for the therapist TOTP fallback. |
| `DAYMARK_MAX_BLOB_BYTES` | `26214400` | Per-blob ciphertext cap (25 MiB). |
| `DAYMARK_MAX_REQUEST_BYTES` | `27262976` | Hard request-body cap (≥ blob + envelope). |
| `DAYMARK_MAX_VERSIONS` | `200` | Append-only retention cap per snapshot lineage; oldest pruned beyond this (only after a newer durable version is confirmed). |
| `DAYMARK_PER_TOKEN_QUOTA_BYTES` | `5368709120` | Per-token storage quota (5 GiB); disk-full fails closed. |
| `DAYMARK_RATE_LIMIT_RPS` | `5` | Per-source request rate limit (keyed by trusted IP or socket peer). |
| `DAYMARK_LINEAGE_CREATE_RPM` | `10` | Cap on lineage/version creation (anti monotonic-poisoning / queue-flooding). |
| `DAYMARK_AUTH_LOCKOUT_FAILS` | `8` | Failed-auth attempts before lockout. |
| `DAYMARK_AUTH_LOCKOUT_SECONDS` | `900` | Lockout duration. |
| `DAYMARK_THERAPIST_AUTH` | _(off)_ | Set `1`/`true` to enable the therapist portal (relationship blob channels + TOTP auth + invites). Fail-closed (503 on every portal path) when unset. |
| `DAYMARK_INVITE_TTL_SECONDS` | `259200` | Single-use therapist-invite TTL (72 h). |
| `DAYMARK_SESSION_IDLE_SECONDS` | `900` | Therapist session idle timeout (15 min). |
| `DAYMARK_SESSION_ABSOLUTE_SECONDS` | `28800` | Therapist session absolute lifetime (8 h). |
| `DAYMARK_TOTP_LOCKOUT_FAILS` | `5` | Bad TOTP codes (and bad invite-secret guesses) before lockout / capped backoff. |
| `DAYMARK_TOTP_LOCKOUT_SECONDS` | `300` | TOTP lockout window / invite-redeem backoff base. |
| `DAYMARK_REL_MAX_VERSIONS` | `50` | Append-only retention cap per relationship-channel lineage. |
| `DAYMARK_REL_QUOTA_BYTES` | `268435456` | Per-relationship storage quota (256 MiB). |
| `DAYMARK_COOKIE_INSECURE` | _(off)_ | Dev/test only: drop the `Secure` attribute on the session cookie (for a plain-HTTP origin). Leave unset in production — the portal requires a TLS origin. |
| `DAYMARK_SHARE_MAX_TTL_DAYS` | `90` | Ceiling on owner-requested share/game-plan expiry (bounds the no-PFS harvest window). |
| `DAYMARK_BLOB_HARD_DELETE` | `on-supersede-and-expiry` | Hard-delete superseded/expired blob **bytes** (not just flag). |
| `DAYMARK_SIZE_PADDING` | `bucketed` | Pad blob sizes to fixed buckets (e.g. 4/16/64 KiB) **by default** (anti acuity/withdrawal de-anon). |
| `DAYMARK_ACCESS_LOG_RETENTION_DAYS` | `90` | Owner-readable access-log retention before pruning. |
| `DAYMARK_ACCESS_LOG_SOURCE_IP` | `off` | Log source IP? **Off by default** (IP geolocates the clinic). |
| `DAYMARK_LOG_LEVEL` | `info` | `error`\|`warn`\|`info`\|`debug`. Never logs plaintext/keys/tokens. |
| `TZ` | `UTC` | Timestamp timezone (logs/metadata only). |

> Any variable `X` may be supplied as `X_FILE` pointing at a file; the file form wins
> and keeps the secret out of `docker inspect` / env dumps. This convention applies
> uniformly to every secret-bearing variable.

### 5.2 Secrets handling

**Option 1 — host file + `*_FILE` mount (compose-native, simplest).**

```bash
# generate a strong token (host)
mkdir -p ./secrets
openssl rand -base64 48 > ./secrets/companion_auth_token
chmod 600 ./secrets/companion_auth_token
```

The compose `secrets:` block (§2) mounts it at `/run/secrets/companion_auth_token`
read-only, and `DAYMARK_AUTH_TOKEN_FILE` points there. **Never** put the token
directly in `environment:` or a committed `.env`.

**Option 2 — external / Swarm Docker secret (no host file in the repo).**

```yaml
secrets:
  companion_auth_token:
    external: true   # created out-of-band:
                     #   printf %s "$TOK" | docker secret create companion_auth_token -
```

**Option 3 — Podman / rootless.** The same compose works; for rootless add
`userns_mode: "keep-id"` (or run as the rootless user's UID) so the `blobs` volume is
writable. `cap_drop: ALL` and `no-new-privileges` carry over unchanged.

**Hygiene rules**

- The image contains **no** secrets and **no** default token — the app **refuses to
  start** without `DAYMARK_AUTH_TOKEN[_FILE]`, so there is no insecure default.
- `.gitignore` `secrets/` and `*.env`. Ship `secrets/.gitkeep` and a
  `companion.env.example` only.
- Rotating the token: replace the secret file/secret, `docker compose up -d` to
  restart; existing blobs are unaffected (the token gates *access*, it is not a
  crypto key).
- The **server auth token is not the E2EE key.** Even if it leaks, an attacker can
  list/PUT opaque blobs but still cannot read anything — zero-knowledge holds. It is
  a network-enumeration / DoS guard, **not** a confidentiality boundary. (It *is* an
  integrity/availability lever, which is why the §2 limits exist.)
- The **WebAuthn-PRF reading key never reaches the server.** The token, the TOTP
  fallback's Argon2id-hashed secret, and the bootstrap invite code are the only
  authenticating material the server stores — and the TOTP secret is honestly flagged
  as a weaker parallel custody path in [COMPANION_SECURITY.md](COMPANION_SECURITY.md).

---

## 6. Volume, backup & restore

The entire persistent state is one named volume `blobs:` containing:

```
/data
  index.sqlite           # NON-SECRET metadata: blob id, type, device label, createdAt,
  index.sqlite-wal       # size (padded), content hash (server-computed), per-relationship
  index.sqlite-shm       # inbox token, share expiry, revoke flag, version/lineage counters
  blobs/<id>.bin         # opaque ciphertext (snapshots, share bundles, game plans)
  tls/                   # (Topology B only) self-signed or mounted cert+key
  access.log.sqlite      # coarse, owner-readable, EVENT-not-content access log
```

> **Metadata honesty.** "Non-secret" does **not** mean "harmless." The index is a
> relationship/traffic-analysis surface — see
> [§11](#11-what-deployment-hardening-does-not-buy-you-honesty-section) and
> [COMPANION_SECURITY.md](COMPANION_SECURITY.md). It contains no plaintext and no
> keys, so a stolen backup leaks only what a stolen disk would.

Everything backed up is **ciphertext + non-secret metadata**, so backups inherit the
zero-knowledge property. (Note the server **computes the content hash itself** over
the opaque blob — any client-supplied `X-Content-Hash` is untrusted — and the
`blob_path` is **server-derived and charset-validated**, never client-supplied, to
close path-traversal on store/DELETE.)

### 6.1 Backup (WAL-aware, consistent)

The index is SQLite in WAL mode, so copy it with a SQLite-consistent method — not a
raw `cp` of a live file.

```bash
# Consistent backup of the whole volume to a tar.gz
TS=$(date -u +%Y%m%dT%H%M%SZ)

# 1) Atomic, WAL-aware snapshot of the SQLite index from inside the running container.
#    dm-admin uses `VACUUM INTO` / the SQLite backup API for a consistent copy.
docker compose exec -T companion /app/dm-admin backup-index \
  /data/index.sqlite /data/_backup/index.sqlite

# 2) Snapshot the volume. Blob files are append-only & content-addressed (never
#    mutated in place), so copying them live is safe; only the index needed step 1.
docker run --rm \
  -v daymark_blobs:/data:ro \
  -v "$PWD/backups:/backup" \
  alpine:3 sh -c "tar czf /backup/daymark-$TS.tar.gz -C /data ."
```

> Store backups encrypted at rest if your host isn't already (e.g. `age` / `gpg` the
> tarball). The contents are already E2EE; defense in depth is cheap.

### 6.2 Restore

```bash
docker compose down
docker volume create daymark_blobs
docker run --rm -v daymark_blobs:/data -v "$PWD/backups:/backup" alpine:3 \
  sh -c "cd /data && tar xzf /backup/daymark-<TS>.tar.gz"
# fix ownership to the container UID:
docker run --rm -v daymark_blobs:/data alpine:3 chown -R 10001:10001 /data
docker compose up -d
```

### 6.3 Volume ownership gotcha

The named volume must be owned by the container UID (`10001`). On first creation the
entrypoint `chown`s `/data` *if* it has permission; with a `read_only` rootfs +
non-root user this can fail on a pre-existing volume and crash-loop the container. The
documented fix is the one-shot `chown` above, or a tiny init service:

```yaml
  init-perms:
    image: alpine:3
    user: "0:0"
    command: ["chown", "-R", "10001:10001", "/data"]
    volumes: [ "blobs:/data" ]
    restart: "no"
    # run once: `docker compose run --rm init-perms`, then start `companion`.
```

---

## 7. Upgrades & migrations

### 7.1 Image upgrade flow (pinned digests)

```bash
# 1) Back up first (always — see §6.1).
# 2) Bump the pinned digest in docker-compose.yml to the new release's sha256.
#    Releases publish provenance/SBOM (and optionally cosign signatures); verify the
#    digest against the release notes before bumping.
# 3) Pull + recreate:
docker compose pull
docker compose up -d
# 4) Watch health + logs:
docker compose ps            # STATUS should reach 'healthy'
docker compose logs -f companion
# 5) Roll back = revert the digest and `up -d` again; the volume is unchanged.
```

- **Always pin by `@sha256:` digest, never `:latest`.** Reproducibility +
  supply-chain integrity is a core principle; a floating tag would let a registry
  compromise swap the image — and for the browser portal that would mean **tampered
  in-browser crypto JS**. (This is exactly why owner secrets are native-phone-only and
  the browser portal is documented as a *lower-assurance convenience path*, not a
  zero-knowledge one — see [COMPANION_SECURITY.md](COMPANION_SECURITY.md).)
- Optionally verify image signatures (cosign) before bumping the digest, if the
  release ships them.

### 7.2 SQLite index migrations

- The index holds **only non-secret metadata** — migrations rewrite columns/indices,
  **never** touch blob ciphertext. A migration can therefore never corrupt or expose
  owner data.
- Migrations are **idempotent, forward-only, transactional**, run automatically at
  startup, and guarded by a `schema_version` row. If a migration fails, the app
  **refuses to start** and leaves the old DB intact (**fail-closed**) rather than
  half-migrating.
- The entrypoint takes an **automatic pre-migration copy** of `index.sqlite` to
  `/data/_pre-migrate/index.<oldver>.sqlite` before applying, so a bad upgrade is
  trivially reversible even without the §6 backup.
- **Blob format is versioned in the blob header, not the DB.** New server versions
  must keep reading old blob format versions (append-only history is sacred); the
  server **never re-encrypts or rewrites** existing blobs. Format changes are
  additive.
- Because the **phone holds the authoritative copy** (and v1 sync is **single-writer,
  last-snapshot-wins replication** — *not* row-level merge; see
  [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md)), the worst case of a botched
  server migration is "re-init the volume and re-sync from the phone." That is the
  documented ultimate fallback.

> **Roadmap note (no claim of v1 support).** True concurrent multi-device row-merge is
> **gated** behind a prerequisite schema migration that adds stable cross-device UUIDs
> and real `updatedAt` columns to every synced table (the current app schema has
> neither — `grep` confirms zero `updatedAt`/`lastModified` columns). That migration
> is deferred to a later phase and is **never claimed as working in v1**. Whether it
> is ever undertaken (vs keeping sync permanently single-writer) is an open maintainer
> decision in [COMPANION_SCOPE.md](COMPANION_SCOPE.md).

### 7.3 Compatibility-matrix discipline

Each release documents: the minimum compatible **Daymark Sync** flavor app version,
the blob-format versions it reads/writes, and the index `schema_version`. The app's
Sync screen surfaces a clear "server too old / too new" message rather than silently
misbehaving.

---

## 8. Network egress lockdown

`internal: true` on the companion's **own** network removes its route to the gateway.
This is the structural enforcement of "no telemetry by default."

> **The SMTP exception (the one deliberate egress path).** If — and only if — the owner
> sets `DAYMARK_SMTP_HOST`, the companion sends therapist invite/notification **links**
> (no record or plaintext content) to that one mail server over TLS, with credentials
> from `*_FILE` secrets. To enable it you must give the container a **narrow** egress
> path to exactly that `host:port` and nothing else — e.g. a second bridge that reaches
> only the mail relay, or a host-firewall allow-rule scoped to the mail host — while
> keeping `internal: true` on its primary network. Do **not** simply drop the container
> onto a general egress-capable network; that reopens telemetry egress. If SMTP is left
> unset (the default), the mailer never opens a socket and the "no outbound, ever" claim
> holds unchanged.

> **Structural-honesty caveat (do not get this wrong).** Putting the companion on a
> network that it **shares with the internet-facing proxy** does **not** lock egress —
> the companion can reach the gateway through that shared, egress-capable bridge. An
> earlier draft claimed Topology-A egress was "structurally enforced" while the
> companion shared the proxy's `edge` net; that claim was **false** and is corrected
> here. The fix, used in §2, is:
>
> 1. Give the companion its **own** `internal: true` network (`companion-internal`).
> 2. Make the **proxy multi-homed**: it joins both `companion-internal` (to reach the
>    companion) **and** its separate egress-capable `edge` net (for public ingress).
> 3. The companion joins **only** `companion-internal`, so it has no path to the
>    gateway.
>
> If for some reason you keep the companion on an egress-capable network, **drop the
> "structurally enforced" wording** and add a host-firewall egress-deny rule instead.

**Verify zero egress at runtime:**

```bash
# From inside the container, any outbound connect must fail/timeout.
docker compose exec companion /healthcheck --expect-fail https://example.com
```

> **CI caveat (scope honestly).** The project's `egress=0` CI test covers only the
> **shipped image** (no outbound calls, web UI loads zero third-party origins —
> enforced by a strict CSP + fully vendored assets). It does **not** validate the
> operator's *runtime* compose/network choices. The command above is how you check
> your own deployment. Separately, the flagship F-Droid build remains **provably
> network-free** (declares no `INTERNET` permission); all server talk lives only in the
> opt-in **Daymark Sync** flavor.

---

## 9. First-run checklist (Topology A)

1. `mkdir -p secrets && openssl rand -base64 48 > secrets/companion_auth_token && chmod 600 secrets/companion_auth_token`
2. Create the `companion-internal` (`internal: true`) network and join **both** the
   companion and the proxy to it; keep the proxy's public NIC on a separate `edge`
   network (see §2, §8).
3. `docker compose run --rm init-perms` (one-time volume `chown`).
4. Set the pinned image **digest**, the real **hostname**, `DAYMARK_WEBAUTHN_RP_ID` /
   `DAYMARK_WEBAUTHN_ORIGINS`, and `DAYMARK_TRUSTED_PROXIES` to your proxy's **narrow**
   address.
5. `docker compose up -d` → wait for `healthy`.
6. Configure the proxy (Caddy/Traefik/nginx per §4); confirm HTTPS and that
   `X-Forwarded-Proto` arrives correctly.
7. Pair the phone **Daymark Sync** flavor (server URL + token + passphrase); confirm a
   snapshot PUT round-trips. (Do **not** type the master passphrase into the browser
   portal — that path is forbidden/strongly-discouraged; see
   [COMPANION_SECURITY.md](COMPANION_SECURITY.md).)
8. Verify zero egress (§8) and that the portal loads **no** third-party origins
   (devtools → Network).
9. Schedule the §6 backup (cron) and store backups **off-box** (encrypted at rest).

---

## 10. Hardening defaults (one table)

| Surface | Default | Guarantee |
|---|---|---|
| Process user | non-root `10001` | No root inside the container. |
| Rootfs | `read_only: true` | Only `/data` (volume) + `/tmp`, `/run` (tmpfs) writable. |
| Capabilities | `cap_drop: ALL` | No Linux privileges to abuse. |
| Privilege escalation | `no-new-privileges:true` | setuid binaries can't elevate. |
| Egress | own `internal: true` network | No route to the internet → no telemetry possible (proxy multi-homed, §8). |
| Ingress | proxy-only (Topology A) | Companion port never host-published. |
| Secrets | `*_FILE` / docker secret; no default token | Token absent from image, env dumps, `docker inspect`; fail-closed if unset. |
| Forwarded headers | honored **only** from pinned `TRUSTED_PROXIES`; default trust-none | No `X-Forwarded-*` spoofing of proto/IP; no rate-limit/lockout bypass. |
| WebAuthn RP-ID / origin | config-pinned, exact match | No client `Host`/`X-Forwarded-Host` origin confusion. |
| Limits | size / version / rate / lockout / per-token quota / lineage caps | DoS + eviction + monotonic-poisoning hygiene; disk-full fails closed. |
| Blob paths & hashes | server-derived path, server-computed hash | No path traversal on store/DELETE; no client-forged content hash. |
| Retention | bounded TTL + hard-delete of superseded/expired bytes | Finite harvest-now-decrypt-later window (no PFS — see §11). |
| Size padding | bucketed by default | Mitigates acuity/withdrawal traffic-analysis de-anon. |
| Audit log | events not content; owner-readable; IP off | No content leak; clinic not geolocated. |
| Image | pinned `@sha256` + SBOM | Reproducible, verifiable supply chain. |
| Data at rest | ciphertext + non-secret metadata only | Disk/backup theft leaks only opaque blobs + metadata. |

---

## 11. What deployment hardening does NOT buy you (honesty section)

Container hardening protects the *host boundary*. It does **not** change the cryptographic
trust model, and it would be dishonest to imply otherwise. The following limits are
**not** fixable by any compose flag and are documented in full in
[COMPANION_SECURITY.md](COMPANION_SECURITY.md):

- **No forward secrecy.** The sealed-box scheme (`crypto_box_seal` / X25519) provides
  *confidentiality + sender anonymity*, **not** recipient forward secrecy. One
  compromise of a recipient's long-term X25519 key (therapist for shares, owner for
  game plans) retroactively decrypts **every** blob ever sealed to it. CEK rotation
  does **not** mitigate this (all versions are sealed to the same long-term key).
  Deployment only *bounds the window* via `DAYMARK_SHARE_MAX_TTL_DAYS` +
  `DAYMARK_BLOB_HARD_DELETE` — it does not eliminate it.
- **Revocation is honest-server-only.** Server-side expiry/revoke stops **future**
  fetches against an **honest** server. An already-pushed share remains readable to a
  colluding server because the wrapped CEK persists and the therapist key never rotates
  on revoke. Real future-data revocation requires therapist **re-keying** (re-pair to a
  new verified key). No compose setting changes this.
- **The browser portal is not zero-knowledge against a malicious operator.** SRI/CSP
  are inert when the same first-party origin serves both the HTML and the assets — a
  hostile operator rewrites both together. Owner secrets are therefore **native-phone
  only**; the browser portal is a lower-assurance convenience path. Pinning the image
  by digest (§7.1) reduces but does not eliminate this risk.
- **The relationship graph is visible to the server.** Even with size padding and
  per-relationship inbox tokens (replacing raw recipient fingerprints in query
  strings), the index reveals *that* relationships and traffic exist. Padding and
  short log retention shrink this; they do not erase it.
- **Audit suppression is only partially detectable.** Therapist-signed attestations
  prevent forgery but, without a signed monotonic hash-chain, a hostile server can
  silently *omit* an event. Until that chain ships, the "access cannot be hidden" claim
  is **retracted**.

These are properties of the **multi-party design**, surfaced here so an operator does
not over-trust a well-hardened container. The container being a *dumb ciphertext host*
is a feature: it means none of the above leaks *plaintext* — but it does not make the
server *trustless*.

---

### Related documents

- [COMPANION_SCOPE.md](COMPANION_SCOPE.md) — what the Companion is, and the unresolved
  sequencing (sync-first vs sharing/game-plans).
- [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md) — internals, the API surface,
  the `game_plans` schema (DB v13), single-writer sync.
- [COMPANION_SECURITY.md](COMPANION_SECURITY.md) — the crypto contract
  (XChaCha20-Poly1305 + Argon2id + X25519/Ed25519), the multi-party threat model,
  retractions, and the full limits section referenced above.
- [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) — the clinician-facing surface,
  TOFU pairing, the non-diagnostic framing, and the TOTP fallback.
- [../PRIVACY.md](../PRIVACY.md) / [../SECURITY.md](../SECURITY.md) — the flagship
  fully-offline app this never alters.
