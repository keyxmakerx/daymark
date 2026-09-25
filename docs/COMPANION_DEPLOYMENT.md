# Daymark Companion — deployment and operation

For whoever runs a Companion server. The live files are `companion/docker-compose.yml`,
`companion/docker-compose.no-egress.yml`, `companion/Dockerfile` and `companion/.env.example`; where
they and this guide disagree, the files win and this guide has a bug. Day-to-day operation — probes,
logs, the runbook — is [COMPANION_OBSERVABILITY.md](COMPANION_OBSERVABILITY.md). The security model is
[COMPANION_SECURITY.md](COMPANION_SECURITY.md). Image tags and a quick start are in
[companion/README.md](../companion/README.md).

---

## 0. What you are running

- **One container**: the server and the web consoles. It stores ciphertext it cannot read, routing
  metadata, and some sign-in secrets (COMPANION_SECURITY.md §5.2). A stolen disk or backup leaks
  metadata and those secrets — never journal content.
- **No reverse proxy is bundled.** The app speaks plain HTTP; your proxy terminates TLS (§3).
- **Three shapes of one product**, chosen with `DAYMARK_THERAPIST_AUTH`: *Solo*, just you and your
  backup (`0`); *Paired*, plus the clinicians you invite (`1`); *Practice*, a clinic runs the
  machine (`1`). With `0`, every clinician, pairing and practice route answers 503. The shape is a
  server setting, `DAYMARK_SETUP_MODE`, that switches on only what each shape needs (#288). Not
  built: #330.
- **The server is a replica.** The journal lives on the owner's phone; losing the server loses
  convenience, not the journal.

## 1. Topologies

Two, and they differ only in how your proxy reaches the app.

| | Default (`docker-compose.yml`) | No-egress override (add `docker-compose.no-egress.yml`) |
|---|---|---|
| For | a proxy running on the Docker host | a proxy running in a container (Cosmos Cloud, Traefik, Nginx Proxy Manager) |
| Network | bridge `daymark-companion_back`, `10.89.0.0/24`, app at `10.89.0.3`, IP masquerading off | the same network with `internal: true` and no gateway |
| Ingress | published on `127.0.0.1:8080` | your proxy joins `daymark-companion_back` and calls `http://daymark-companion:8080`; no published port |
| Egress | packets leave but no reply ever comes back | dropped; there is no gateway |
| Host services on the bridge gateway | still reachable from the container | nothing to reach them through |

Two topologies because **published ports do not work on Docker `internal:` networks**
([moby/moby#36174](https://github.com/moby/moby/issues/36174)), so a proxy on the host cannot use the
sealed network. The default is the weaker of the two and needs nothing from you. To close it fully,
drop the bridge's traffic on the host — `iptables -I DOCKER-USER -i dmk-back -j DROP` — which keeps
the published port working. CI boots both topologies on every change under `companion/` and proves
egress fails from each.

**Publish on loopback only.** Docker's published ports bypass UFW's rules, so `0.0.0.0` would be
reachable whatever your firewall says. Change `DAYMARK_BIND_IP` only if your proxy is on another
machine and the link between them is already encrypted (a VPN, WireGuard, a private VLAN): the app
speaks plain HTTP and authenticates with a bearer token.

**Prerequisites:** Docker Engine 29.7.2 or later (earlier 29.x releases lack security fixes); Docker
Compose 2.24 or later (the override uses `!reset` and `!override`); buildx (Compose builds through
it). `docker compose config` must parse cleanly before anything else.

## 2. The compose file

What `companion/docker-compose.yml` sets:

| Setting | Value | Why |
|---|---|---|
| Image | built from `companion/Dockerfile`, or `DAYMARK_IMAGE` | Distroless Java 21, digest-pinned, no shell or package manager |
| User | `65532:65532` | Distroless's own non-root user |
| Filesystem | read-only root; `/tmp` a 64 MiB tmpfs, `noexec,nosuid,nodev` | Only `/data` is writable |
| Privileges | `cap_drop: [ALL]`, `no-new-privileges`, AppArmor `docker-default`, default seccomp, `init: true` | Nothing to escalate with |
| Resources | 768 MiB, 1 CPU, 256 processes, 4096/8192 open files | A bug cannot take the host down |
| Logs | `local` driver, 10 MiB × 3, compressed | `json-file` keeps logs without limit by default |
| Health | `/usr/local/bin/healthcheck` probes `/readyz` every 30 s | Docker never restarts an unhealthy container on a single host, so watch it (COMPANION_OBSERVABILITY.md §6) |
| JVM | `-XX:MaxRAMPercentage=70`, `-Djava.io.tmpdir=/tmp`, `-Dorg.sqlite.tmpdir=/data` | The SQLite driver unpacks a native library at start, and `/tmp` is `noexec` |
| Bearer token | the host file `secrets/auth_token`, mounted at `/run/secrets/companion_auth_token` | Never in `environment:`, where `docker inspect` shows it |

**Secret files must be readable by UID 65532 on the host.** Compose ignores the `uid`, `gid` and
`mode` keys on secrets outside Swarm, so the container sees the host file's owner and mode:
`sudo chown 65532:65532 secrets/auth_token && chmod 400 secrets/auth_token`. Otherwise the server
stops at start with a message naming the file.

## 3. Your reverse proxy — the contract

Nothing in this section is shipped: it is what the app expects in front of it. The configs in
[alternatives/](alternatives/README.md) illustrate it; where they disagree with this list, the list
is right.

### 3.1 What your proxy must do

1. **Terminate TLS.** The app speaks plain HTTP and authenticates with a bearer token; anything that
   can read the wire can replay the token.
2. **Add `Strict-Transport-Security`.** The app sends CSP, `X-Frame-Options`, `X-Content-Type-Options`,
   `Referrer-Policy` and the cross-origin headers on every response, but not HSTS, because it cannot
   know it is behind TLS. Suggested: `max-age=31536000; includeSubDomains`; add `preload` only when you
   are sure, since it is effectively irreversible.
3. **Replace or append `X-Forwarded-For`; never pass the client's value through.** The app reads the
   header right to left, skipping trusted hops, so an appended chain is safe and a replaced one is
   safe. A header copied from the client unchanged is not.
4. **Tell the app who you are:** `DAYMARK_TRUSTED_PROXIES` (§4.0).
5. **Refuse unknown `Host` and SNI** with a catch-all that answers an error. The app builds emailed
   links from `DAYMARK_PUBLIC_BASE_URL`, which compose always sets; the catch-all means a poisoned
   invitation link needs two mistakes, not one (#180).
6. **Serve the Companion at the root of its own hostname.** `DAYMARK_BASE_PATH` exists, but a sub-path
   deployment does not work consistently: the API stays at `/v1` on the root while pages move under
   the prefix (#176).
7. **Do not add your own `Content-Security-Policy`.** Two CSP headers are intersected by the browser,
   not overridden, and one without `'wasm-unsafe-eval'` silently breaks every decryption in the
   consoles. If your proxy has a "security headers" or "harden this route" switch, check what CSP it
   sends. Caddy can set a header only if absent (a `?`-prefixed header); nginx's `add_header` cannot.
8. **Point health checks at the right endpoint.** `/healthz` is liveness — the process is up.
   `/readyz` also proves `/data` is writable and answers 503 when it is not; point monitoring there.
   Think before pointing a load balancer at `/readyz`: with one backend, a failing readiness check turns
   a degraded but readable server into an outage.
9. **Cap request bodies and set timeouts.** Suggested: a body limit a little above 26 MiB; about 10 s
   to read headers, 120 s to read a body, 120 s idle. The app has its own floor — 64 KiB for JSON
   bodies, `DAYMARK_MAX_REQUEST_BYTES` for uploads, 120 s to read a request — but header-read timeouts
   are the proxy's.

**The admin console on an address of its own.** By decision, the admin console and every route only
an administrator may call can be served on a second port, which your proxy publishes only on an
admin hostname such as `admin.example.org`, reachable only over the office VPN if you want (#208).
The server tells the two apart by port, never by `Host`. Prefer a hostname to a path: a path needs
sub-paths to work first (#176). Not built: #323.

### 3.2 Worked examples

The reference configs are in [alternatives/](alternatives/README.md). **Cosmos Cloud** (the
maintainer's setup) is a proxy in a container, so it takes the no-egress override:

```sh
docker compose -f docker-compose.yml -f docker-compose.no-egress.yml up -d --build
docker network connect daymark-companion_back cosmos-server
```

Add a route in the Cosmos interface with the target `http://daymark-companion:8080`, and set
`DAYMARK_TRUSTED_PROXIES` to the address Cosmos got on that network (§4.0). Check on your install
whether Cosmos adds its own CSP or HSTS (requirements 2 and 7), and whether its private-network feature
creates a network of its own: use either that or `docker network connect`, never both, or the app sees
whichever address Docker routes from (#207).

### 3.3 Rate limiting

Rate limiting lives in the app, per client address (the table is COMPANION_OBSERVABILITY.md §1.1), and
works only if §4.0 is right. A proxy-level limit is optional: Traefik's `rateLimit`, nginx's
`limit_req`, or Cosmos's per-route limiter. Stock Caddy has none; the third-party `caddy-ratelimit`
module means building and maintaining your own Caddy. `fail2ban` needs a full per-request access log,
which is exactly the record §10 asks you not to keep — decide that trade deliberately.

## 4. Forwarded headers

### 4.0 Trusted-proxy contract (read first)

- **The default trusts no forwarded header.** Every per-client lockout and rate limit keys on the
  TCP peer. Behind a proxy, that peer is the proxy for every request, so every client shares one
  bucket: eight bad bearer tokens from one attacker lock everyone out for 15 minutes, and three
  recovery requests use up the hour for everybody.
- **Set `DAYMARK_TRUSTED_PROXIES` to the proxy's address as the app sees it**, as a `/32`
  (comma-separated addresses and CIDR blocks are accepted):
  - a proxy on the host, forwarding to the published port: usually the `docker0` gateway, commonly
    `172.17.0.1` — not the proxy's LAN address;
  - a proxy container on `daymark-companion_back`: its address on that network. Read it with the
    command below and take the `daymark-companion_back` row.

  ```sh
  docker inspect <proxy-container> \
    --format '{{range $n, $c := .NetworkSettings.Networks}}{{$n}} {{$c.IPAddress}}{{"\n"}}{{end}}'
  ```

  Or let the app tell you: while the list is empty, the first request that arrives carrying
  `X-Forwarded-For` produces one warning naming the address it came from
  (`docker compose logs companion | grep X-Forwarded-For`).
- **Never a broad range** such as `172.16.0.0/12` or a whole bridge: any container sharing it could
  forge `X-Forwarded-For` and walk past the lockout.
- **Write addresses, not names.** A name is resolved once, at start, and silently stops matching when
  Docker gives the container a new address.
- **What the app reads:** `X-Forwarded-For` only, only from a trusted peer, right to left, skipping
  trusted hops. It never reads `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Prefix` or
  `Forwarded`.
- **A wrong but non-empty list fails silently** — no warning (#167). Prove it with the
  lockout-isolation test in COMPANION_OBSERVABILITY.md §1.7.

## 5. Configuration

### 5.1 Environment variables

Read by the server (`Config.kt`, `mail/MailerConfig.kt`). "`_FILE`" means the value may instead be
read from a file named by `NAME_FILE`, which wins.

| Variable | Default | What it does |
|---|---|---|
| `DAYMARK_BIND_ADDR` | `0.0.0.0` | Listen address inside the container |
| `DAYMARK_PORT` | `8080` | Listen port inside the container |
| `DAYMARK_DATA_DIR` | `/data` | The volume (§6) |
| `DAYMARK_WEB_DIR` | `web` (the image sets `/app/web`) | The built consoles |
| `DAYMARK_BASE_PATH` | `/` | Sub-path prefix; not supported yet (§3.1, requirement 6) |
| `DAYMARK_LOG_LEVEL` | `info` (the image, compose and `.env.example` set `warn`) | Level of the app's own loggers (COMPANION_OBSERVABILITY.md §2.4) |
| `DAYMARK_AUTH_TOKEN` (`_FILE`) | unset | The owner's bearer token. Unset: the sync API, owner routes, recovery and the clinician portal all answer 503 |
| `DAYMARK_THERAPIST_AUTH` | off | `1` or `true` turns on the clinician portal, relationships, pairing, practices and the audit log |
| `DAYMARK_PUBLIC_BASE_URL` | first `DAYMARK_WEBAUTHN_ORIGINS` entry | The external origin in emailed links |
| `DAYMARK_WEBAUTHN_RP_ID` | unset | The passkey relying-party id. Passkeys sign people in on an `https` address with a hostname, and never unlock keys (#205); not built: #326 (COMPANION_SECURITY.md §5.1) |
| `DAYMARK_WEBAUTHN_ORIGINS` | unset | Comma-separated; also the fallback for `DAYMARK_PUBLIC_BASE_URL` |
| `DAYMARK_TRUSTED_PROXIES` | empty: trust nothing | §4.0 |
| `DAYMARK_MAX_BLOB_BYTES` | `26214400` (25 MiB) | Largest stored blob |
| `DAYMARK_MAX_REQUEST_BYTES` | `27262976` (26 MiB) | Largest upload body |
| `DAYMARK_MAX_VERSIONS` | `200` | Snapshot versions kept per lineage; older ones are deleted |
| `DAYMARK_PER_TOKEN_QUOTA_BYTES` | `5368709120` (5 GiB) | Snapshot storage quota |
| `DAYMARK_REL_MAX_VERSIONS` | `50` | Versions kept per relationship lineage |
| `DAYMARK_REL_QUOTA_BYTES` | `268435456` (256 MiB) | Storage quota per relationship |
| `DAYMARK_RATE_LIMIT_RPS` | `5` | Requests per second per address, on bearer-token routes only |
| `DAYMARK_AUTH_LOCKOUT_FAILS`, `_SECONDS` | `8`, `900` | Bad bearer tokens before an address is locked out, and for how long |
| `DAYMARK_TOTP_LOCKOUT_FAILS`, `_SECONDS` | `5`, `300` | Bad sign-in codes before a credential is locked, and for how long; also the backoff for wrong invitation secrets |
| `DAYMARK_INVITE_TTL_SECONDS` | `259200` (72 h) | Invitation lifetime |
| `DAYMARK_SESSION_IDLE_SECONDS`, `_ABSOLUTE_SECONDS` | `900`, `28800` | Clinician session lifetimes |
| `DAYMARK_COOKIE_INSECURE` | off | Plain-HTTP testing only: drops `Secure` from the session cookie (#181) |
| `DAYMARK_ACCESS_LOG_RETENTION_DAYS` | `90` | Audit-log retention (COMPANION_SECURITY.md §9) |
| `DAYMARK_ACCESS_LOG_SOURCE_IP` | off | Records the client address in audit entries |
| `DAYMARK_REISSUE_MAX_PER_HOUR` | `3` | Recovery requests per address per hour |
| `DAYMARK_REISSUE_CONFIRM_TTL_SECONDS` | `3600` | Lifetime of a recovery link |
| `DAYMARK_SMTP_HOST` | unset | The one switch for outbound mail (§8) |
| `DAYMARK_SMTP_PORT` | `587` | 587 for STARTTLS, 465 for implicit TLS |
| `DAYMARK_SMTP_TLS` | `starttls` | `starttls` or `implicit`; `none` and anything unknown stop the server at start |
| `DAYMARK_SMTP_FROM` | unset | Required when SMTP is on |
| `DAYMARK_SMTP_USER` (`_FILE`), `DAYMARK_SMTP_PASS` (`_FILE`) | unset | Credentials; give the password as a file |
| `DAYMARK_SMTP_ALLOW_INSECURE_LINKS` | off | Development only: allows `http://` links in mail |

Read by compose only, from `.env`:

| Variable | Default | What it does |
|---|---|---|
| `DAYMARK_DOMAIN` | required | The public hostname as clients type it; compose derives the public base URL and the passkey settings from it |
| `DAYMARK_PUBLIC_SCHEME` | `https` | Scheme of that origin |
| `DAYMARK_BIND_IP` | `127.0.0.1` | Host address the port is published on (§1) |
| `DAYMARK_HOST_PORT` | `8080` | Host port |
| `DAYMARK_IMAGE` | `daymark-companion:1.0.0` | The image to run; set a published digest to run CI's image |

Read by the healthcheck: `DAYMARK_HEALTHCHECK_URL`, default `http://127.0.0.1:8080/readyz`. Compose
also sets `TZ=UTC` and the `JAVA_TOOL_OPTIONS` in §2.

### 5.2 The bearer token

- Create it before the first start:

  ```sh
  mkdir -p secrets && chmod 700 secrets
  openssl rand -base64 48 | tr -d '\n' > secrets/auth_token
  sudo chown 65532:65532 secrets/auth_token && chmod 400 secrets/auth_token
  ```

- `_FILE` works for `DAYMARK_AUTH_TOKEN`, `DAYMARK_SMTP_USER` and `DAYMARK_SMTP_PASS` only. Never put
  a secret in `environment:` or in a committed file.
- **Rotating it:** replace the file and restart. At start the server compares the file with what it
  stored, and a changed file wins over a token issued since by email recovery. Nothing encrypted
  changes: the token gates access and is not a key.
- The token is not the encryption key. Someone holding it can list and upload ciphertext and approve
  pairings, but content routes also demand each relationship's inbox token, so they cannot read the
  journal.
- **A server serves one owner.** Never give its token to a second person: whoever holds it can list
  and download every encrypted copy on the server and push a newer version of each. Each stored
  journal belongs to one owner (#219); several people's journals on one server: not built, #318.
- By decision, a new server is claimed with a one-time setup code it writes to its own log, and each
  person signs in with an account of their own, so no token is created (#208). Not built: #322,
  #324.

## 6. Backup and restore

### 6.1 What is on the volume

The volume is `daymark-companion_blobs`, mounted at `/data`.

| Path | Holds | Present when |
|---|---|---|
| `index.db` and `blobs/<lineage>/<version>.blob` | Snapshot ciphertext and its index | a bearer token is set |
| `keyparams.json` | The owner's key-derivation parameters (salt and cost; public) | an owner has published them |
| `owner-account.db` | The bearer-token digest, the notification email (plaintext), recovery-link digests | a bearer token is set |
| `auth.db` | Invitations (Argon2id), sign-in code seeds (**in the clear**), session digests, attempt counters, public keys, relationship endings | `DAYMARK_THERAPIST_AUTH=1` |
| `rel-index.db` and `rel/<relRef>/<channel>/<lineage>/<version>.blob` | Relationship ciphertext and its index | `DAYMARK_THERAPIST_AUTH=1` |
| `audit.db`, `org-audit.db` | Audit chains, per relationship and per practice | `DAYMARK_THERAPIST_AUTH=1` |
| `org.db` | Practices, members, roles | `DAYMARK_THERAPIST_AUTH=1` |
| `pairing.db` | Pairing messages in transit | `DAYMARK_THERAPIST_AUTH=1` |
| `tmp/` | Staging for atomic writes | with either blob store |

That is eight SQLite databases, all in WAL mode: each may have `-wal` and `-shm` files beside it, and
those belong to it. Also present and not worth keeping: `.readyz` (the readiness probe's file) and the
SQLite native library the server unpacks at every start.

The volume holds **sign-in secrets**: anyone with a copy of `auth.db` can mint sign-in codes for every
enrolled clinician (COMPANION_SECURITY.md §5.2). Protect backups like a password file — encrypted at
rest, readable by few.

### 6.2 Back up

Stop the container for the few seconds a copy takes. Eight databases and their blob files must come
from one moment, and the image has no shell or `sqlite3` to take a live copy.

```sh
cd companion
docker compose stop companion
docker run --rm -v daymark-companion_blobs:/data:ro -v "$PWD/backups:/backup" alpine:3 \
  tar czf "/backup/daymark-$(date -u +%Y%m%dT%H%M%SZ).tar.gz" -C /data .
docker compose start companion
```

A filesystem snapshot of the whole volume at one instant (ZFS, btrfs, LVM) also works: SQLite treats
it like a power cut. Keep `companion/.env` and `companion/secrets/` too — they are not on the volume.
A backup you have never restored is a hypothesis; restore one somewhere once.

### 6.3 Restore

Empty the volume first. A `-wal` file left over from a newer state and replayed onto an older database
would corrupt it.

```sh
cd companion
docker compose down
docker run --rm -v daymark-companion_blobs:/data -v "$PWD/backups:/backup:ro" alpine:3 sh -c \
  'find /data -mindepth 1 -delete && tar xzf /backup/daymark-<time>.tar.gz -C /data && chown -R 65532:65532 /data'
docker compose up -d
```

Add `-f docker-compose.yml -f docker-compose.no-egress.yml` to the `down` and `up` commands if you use
the override. On a new host, run `docker compose create` first so Compose creates the volume, then
restore into it.

After a restore the server is back at the moment of the backup: sign-ins, pairings and audit entries
made since are gone, and clinicians enrolled since must be invited again. An owner who wrote down a
newer audit-chain head will see the chain fall behind it (COMPANION_SECURITY.md §9) — tell them why.

## 7. Upgrades

1. Back up (§6.2).
2. Build from source with `git pull` then `docker compose up -d --build`; or run the image CI
   published: set `DAYMARK_IMAGE` to `ghcr.io/…/daymark-companion@sha256:…`, then `docker compose pull`
   and `docker compose up -d`. Pin a digest, never `:latest`, in production: a moving tag lets a
   registry swap the code that holds people's keys.
3. Watch it come up: `docker compose ps` should reach `healthy`; then run the checks in
   COMPANION_OBSERVABILITY.md §6.4.

**Schema changes** are additive: each store creates missing tables and adds missing columns at start.
There is no schema version and no automatic copy before a change, so the backup from step 1 is the way
back (#193). To roll back, run the previous image — and restore that backup if the new version
changed a database.

**Base images** are pinned by digest, and Dependabot proposes the bumps (`.github/dependabot.yml`).
Nothing updates by itself: a running server keeps its operating system and Java runtime until you
rebuild or pull.

**Volumes from before the switch to UID 65532.** Older builds ran as UID 10001, and Docker copies
ownership from the image only on the first mount of an empty volume. Once:

```sh
docker compose down
docker run --rm -v daymark-companion_blobs:/data alpine:3 chown -R 65532:65532 /data
docker compose up -d --build
```

## 8. Network egress lockdown and the SMTP exception

The app makes no outbound connection except SMTP, and SMTP is off unless `DAYMARK_SMTP_HOST` is set;
while it is off the mailer never opens a socket. Mail carries only invitation and notification links,
never content (COMPANION_OBSERVABILITY.md §3.5). The no-egress override cannot send mail at all, and
on the default network outbound connections get no reply, so turning SMTP on means giving it one
narrow path out.

On the default topology, write an override — `companion/docker-compose.smtp.yml` is not shipped;
this file is yours:

```yaml
services:
  companion:
    networks:
      back: { ipv4_address: 10.89.0.3 }
      mail: { gw_priority: 1 }     # the default route must go through mail, not back
    environment:
      DAYMARK_SMTP_HOST: "${DAYMARK_SMTP_HOST}"
      DAYMARK_SMTP_PORT: "587"
      DAYMARK_SMTP_TLS:  "starttls"
      DAYMARK_SMTP_FROM: "${DAYMARK_SMTP_FROM}"
      DAYMARK_SMTP_USER: "${DAYMARK_SMTP_USER}"
      DAYMARK_SMTP_PASS_FILE: "/run/secrets/companion_smtp_pass"
    secrets:
      - companion_smtp_pass
networks:
  mail:
    driver: bridge
    driver_opts:
      com.docker.network.bridge.name: dmk-mail
secrets:
  companion_smtp_pass:
    file: ./secrets/smtp_pass
```

Make `secrets/smtp_pass` readable by UID 65532 (§2), start with
`docker compose -f docker-compose.yml -f docker-compose.smtp.yml up -d`, and then allow only the mail
server on the host. The order matters — each `-I` goes to the top, so the RETURN must be inserted last:

```sh
iptables -I DOCKER-USER -i dmk-mail -j DROP
iptables -I DOCKER-USER -i dmk-mail -d <smtp-ip>/32 -p tcp --dport 587 -j RETURN
```

`gw_priority` needs Compose 2.33 or later; without it Docker picks the default route itself and may
pick the network with no reply path. Docker's nftables backend has **no** `DOCKER-USER` chain: there,
add a table of your own with a base chain at the same hook and priority. None of this is tested by CI;
check that a real email arrives (#207).

## 9. First run

1. `cp .env.example .env` in `companion/`; set `DAYMARK_DOMAIN`, and `DAYMARK_THERAPIST_AUTH=1` if a
   clinician or a practice will use this server.
2. Create the bearer token (§5.2). By decision, a one-time setup code from the server's own log
   replaces this step (#208); not built: #322.
3. Pick the topology (§1) and start: `docker compose up -d --build`, adding the override if your proxy
   is a container.
4. Configure your proxy (§3), set `DAYMARK_TRUSTED_PROXIES` (§4.0), and restart.
5. Run the checks in COMPANION_OBSERVABILITY.md §6.4, including the lockout-isolation test.
6. Schedule backups (§6.2) and keep them off the host, encrypted.

## 10. Logging and retention policy

Two records, kept for different readers:

| | Container log | Audit log |
|---|---|---|
| Where | Docker's `local` driver | SQLite: `audit.db`, `org-audit.db` |
| About | The process | Actions in a relationship or a practice |
| Read by | The operator | The owner (relationships); the practice's admin (practices) |
| Content | The app's own lines (COMPANION_OBSERVABILITY.md §2.2) | Actor, action, an opaque object reference |
| Integrity | None | A SHA-256 hash chain |
| Retention | 3 × 10 MiB, compressed | `DAYMARK_ACCESS_LOG_RETENTION_DAYS` (90), applied lazily (#165) |

**Never log, at any level, in the app or your proxy:**

- request or response bodies, or their sizes — length alone is a signal about a person;
- `Authorization`, `Cookie`, `Set-Cookie`, `X-Rel-Token`, `X-CSRF-Token`, `X-Content-Hash`,
  `X-Setting-Key`;
- any concrete path parameter — `relRef`, channel, lineage, version, invitation id — only the route
  template;
- email addresses, sign-in codes, recovery and invitation secrets, credential ids;
- stack traces on request paths.

The app breaks two of these today: the unhandled-error line logs the request path with a stack trace,
and the sync disk-full line logs a file path that contains a lineage id (#160).

**Your proxy's access log.** The app keeps none, deliberately. If your proxy keeps one, delete the
client address and the User-Agent, or coarsen the address (Caddy: `ip_mask { ipv4 16 ipv6 32 }` —
never `ip_mask 0`, which turns masking off); keep timestamps to the second, in UTC; and cap its size,
since it shares the disk with `/data`.

**Retention.** Container logs are size-bounded; the app writes no access log; audit entries default to
90 days. A practice that is a HIPAA covered entity may owe six years of documentation, and whether that
reaches raw audit logs is contested. The default stays short because a person hosting their own
journal is not a covered entity; a practice needs its own counsel (#284). Shared items (shares, game
plans and assignments) are kept at most 90 days, and the server deletes the bytes of each once it
has ended (#228). Not built: #332, #338; today only withdrawing a share deletes its bytes.
