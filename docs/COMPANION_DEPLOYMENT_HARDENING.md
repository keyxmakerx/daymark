# Companion deployment — hardened stack, research findings, and audit checklist

> **Provenance.** Produced by a research pass on 2026-08-08 in which every version number, image
> tag and "best practice" claim had to be re-verified against a fetched primary source, then
> reviewed. It exists because the maintainer asked, explicitly, that nothing be assumed from a
> model's stale training data — and the findings in §7 show that instinct was right.
>
> **This is the reasoning behind the shipped files**, not a second source of truth. The live
> artefacts are `companion/docker-compose.yml`, `companion/docker-compose.no-egress.yml`,
> `companion/Dockerfile` and `companion/healthcheck/main.go`. Where this document and those files
> disagree, the files win.
>
> **REVISED 2026-08-09: the bundled Caddy is gone.** The original spec shipped a reverse proxy as a
> second compose service. The deployment now ships the application only and documents a *contract*
> (§3) for whichever proxy the operator already runs. Everything Caddy was credited with in the
> audit tables of §6 has been re-marked: some items moved to the operator's proxy, some were since
> fixed in code, and some are simply open again. The Caddy research in §7 is kept — it is still the
> best available guidance for an operator choosing a proxy — but it no longer describes anything
> this repo starts.
>
> **One thing is NOT verified and must be before any public exposure:** every image digest in §9.1
> (re-resolve them; they were resolved by an agent, not by pulling).

---

# Daymark Companion — Hardened Deployment Implementation Spec

**Date:** 2026-08-08 · **Target:** replace the Milestone-1 scaffold with a production `docker compose` deployment
**Repo paths:** `/home/user/daymark/companion/` (build context = this directory)

Everything below was cross-checked against the actual repo. Where the five reports disagreed, the conflict is stated inline and resolved with reasoning. Digests are as-resolved by the reports on 2026-08-08 and **must be re-verified before merge** (§9.1).

---

## 1. The stack decision

| Layer | Decision |
|---|---|
| **Reverse proxy** | **None shipped.** The operator's own proxy fronts the app; §3 states the contract it must satisfy. *(Superseded 2026-08-09 — this row previously read "Caddy 2.11.4-alpine, digest-pinned". See §2.0.)* |
| **App runtime base** | **`gcr.io/distroless/java21-debian13:nonroot`**, digest-pinned, UID 65532, no shell / no package manager / no curl, with a ~2 MB stdlib-only static Go healthcheck binary built in-repo. |
| **Logging** | **No aggregation stack.** Docker `local` log driver with hard rotation caps and the app's existing SQLite `AuditStore` as the real accountability record. Access logging is now entirely the proxy's business; §5.3 says what it must not record. |

### Deliberately rejected — and what the rejections are worth now

*These were choices about what to **bundle**. Nothing is bundled any more, so read the table as
research for an operator picking their own proxy, not as a decision this repo makes for them. The
findings themselves were verified against primary sources on 2026-08-08 and still hold.*

| Rejected | Why |
|---|---|
| **Traefik v3.7.10** | Right tool for label-driven multi-service routing; wrong shape for one static upstream. Two-file static/dynamic config split, fast security-driven release cadence (v3.7.10/v3.6.25/v2.11.54 all shipped 2026-07-31), and its Docker provider wants `/var/run/docker.sock` — the repo currently has **zero** socket exposure and that is worth keeping. Its one genuine advantage (built-in `rateLimit`) is already covered at the app layer. |
| **nginx 1.30.4** | No ACME client. Every self-hosted nginx TLS setup is nginx + certbot + a timer + a reload hook, and that chain is what rots in year three. Getting worse, not better: LE moved `tlsserver` to 45-day certs on 2026-05-13 and moves default `classic` to 64-day on 2027-02-10. Also no internal CA for LAN, and `add_header` has no set-if-absent form (see §3 CSP intersection trap). |
| **Pangolin** | Genuine 2025/26 newcomer, but it is a Traefik-based tunnel control plane for NAT traversal + SSO. It adds a database, a dashboard and an auth layer to a deployment whose entire thesis is minimalism. |
| **Nginx Proxy Manager / Angie / freenginx** | NPM adds a DB and an admin web UI (attack surface, patch burden) to save 30 lines. The nginx forks don't solve ACME. |
| **Loki + Alloy** | Would create a second, indexed, long-retention copy of exactly the request metadata (who accessed which relationship, when, how often, from where) that the E2EE design exists to deny the operator. You cannot say "I cannot read your data" while running a full-text index over your users' access patterns. ~3 extra containers, several hundred MB RAM, and Loki **has no authentication of its own** (`auth_enabled` toggles multi-tenancy, not auth). |
| **Vector as default** | Good software, but redaction-in-pipeline is the wrong layer: the plaintext identifier is emitted first and scrubbed second. Better to never emit it. Vector earns its place only as an egress *gate* if logs must leave the box. |
| **Promtail** | **EOL 2026-03-02.** Not an option regardless. |
| **Chainguard free tier** | `:latest` only; versioned tags are paid. A moving tag is the opposite of the goal. |
| **Docker Hardened Images** | Free and Apache-2.0 since Dec 2025 and genuinely excellent — but `dhi.io` requires `docker login`. For a Dockerfile a therapist's IT person builds on their own VPS, an account-gated registry is a support-ticket generator. Revisit if Daymark ever publishes prebuilt images from CI. |
| **Alpine JRE (`21-jre-alpine`)** | `org.xerial:sqlite-jdbc` sniffs `/etc/os-release` to pick its bundled native lib and has a documented Alpine-with-glibc failure mode. Distroless is Debian 13/glibc — the well-trodden path. |
| **`willfarrell/autoheal`** | Requires mounting the Docker socket. Root-equivalent host access in a deployment whose pitch is that the server is untrusted. Strictly worse than a container that sits `unhealthy` until an uptime monitor notices. |
| **daemon `userns-remap`** | On Docker 29 it silently disables the containerd image store (now the default) as a workaround for moby#47377. Bigger change than the isolation it buys. Prefer rootless Docker or skip. |
| **Proxy-level rate limiting via `mholt/caddy-ratelimit`** | Not rejected outright — see §3.4. Rejected *as the default* because it forfeits "pin an official digest and never build anything," which for an ops-less therapy practice is a worse security outcome than relying on the app's existing limiter. |

---

## 2. `docker-compose.yml`

**Path:** `/home/user/daymark/companion/docker-compose.yml` — the live file. It is deliberately
**not reproduced here**: an earlier revision of this document inlined the whole thing, and the copy
started drifting from the original within a day. What follows is the reasoning that does not fit in
the file's own comments.

### 2.0 There is no bundled reverse proxy

The first version of this spec shipped Caddy as a second service in the same compose file. That is
now **removed entirely** — not made opt-in, removed. The audience for a self-hosted therapy tool
already runs a proxy: a NAS with its own UI, a homelab with Traefik, a VPS with nginx, or (the
maintainer's case) Cosmos Cloud. For all of them the bundled option was a second thing to configure
and a second thing to patch, and for none of them was it the thing they would actually use. Shipping
it also meant shipping ACME, a certificate volume, a `:80`/`:443` binding and a privileged-port
sysctl workaround that nobody had ever run.

What replaces it is a **contract** (§3) rather than a component. Compose starts the application, and
only the application.

#### The fact that dictates the network shape

The old topology gave the app an `internal: true` network with
`com.docker.network.bridge.gateway_mode_ipv4: isolated` — no gateway address, no route anywhere —
and let Caddy straddle that network and an internet-facing one. With Caddy gone, the app's only
ingress would have to be a published host port, and:

> **Published ports do not work on Docker `internal:` bridge networks.** Port bindings are simply
> not set up; `docker inspect` shows `"Ports": {}` even for `-p 127.0.0.1:8080:8080`.
> ([moby/moby#36174](https://github.com/moby/moby/issues/36174) — open since 2018, still open.)
> `gateway_mode_ipv4: isolated` goes further and stops Docker assigning the bridge a host-side
> address at all ([moby/moby#49262](https://github.com/moby/moby/pull/49262), merged for 28.0), so
> there is not even a route for DNAT to target.

This was checked because the first draft of the replacement compose file asserted the opposite in a
comment — that publishing is host-side DNAT and therefore independent of the container's route out.
That reasoning is plausible and wrong, which is exactly the sort of claim that ships green and fails
on someone else's machine. Hence the two shipped topologies:

| | Default (`docker-compose.yml`) | No-egress override (`+ docker-compose.no-egress.yml`) |
|---|---|---|
| For | a proxy running on the **Docker host** | a proxy running in a **container** |
| Network | plain bridge, `enable_ip_masquerade: false` | `internal: true` + `gateway_mode_ipv4: isolated` |
| Ingress | published on `127.0.0.1:8080` | proxy joins `daymark-companion_back`, talks to `daymark-companion:8080` |
| Egress | packets leave, replies never return | dropped in the FORWARD chain; no gateway exists |
| Host services on the bridge gateway | still reachable from the container | no address to reach them through |

The default is the weaker of the two and it is the default anyway, because it is the one that works
without knowing anything about the operator's proxy. Be precise about what `enable_ip_masquerade:
false` buys: outbound packets **still leave the host**, carrying a `10.89.0.0/24` source that
nothing upstream will route a reply to. Egress does not *complete*; it is not *dropped*. That is
enough to stop an exfiltration channel that needs a response, and not enough to call the container
sealed. `no-egress.yml` is the sealed version, and it is what the maintainer's Cosmos Cloud setup
should use.

Both topologies are booted and probed in CI on every change to `companion/**` — published port
serves, egress fails, a container on the network resolves `daymark-companion` by name, the override
really does remove the host binding. The `!reset` on `ports:` is asserted directly, because a
silently-non-applying override would put the port back and void the claim.

### 2.1 SMTP egress override (optional, opt-in)

The default network disables NAT masquerading and the no-egress override has no gateway at all, so
**SMTP will not work by default** — that is deliberate. Compose cannot express per-destination
egress allowlists. If the operator enables SMTP (base topology only; the no-egress override is
incompatible by design):

**`/home/user/daymark/companion/docker-compose.smtp.yml`**
```yaml
services:
  companion:
    networks:
      back: { ipv4_address: 10.89.0.3 }
      mail: {}
    environment:
      DAYMARK_SMTP_HOST: "${DAYMARK_SMTP_HOST}"
      DAYMARK_SMTP_PORT: "587"
      DAYMARK_SMTP_TLS:  "starttls"
      DAYMARK_SMTP_FROM: "${DAYMARK_SMTP_FROM}"
      DAYMARK_SMTP_PASS_FILE: "/run/secrets/companion_smtp_pass"
    secrets:
      - source: companion_smtp_pass
        target: companion_smtp_pass
        uid: "65532"
        gid: "65532"
        mode: 0400
networks:
  mail:
    driver: bridge
    driver_opts:
      com.docker.network.bridge.name: dmk-mail
secrets:
  companion_smtp_pass:
    file: ./secrets/smtp_pass
```

A second network is what carries the egress, so `back` keeps its masquerade turned off and the mail
path is the only one that works. Run with
`docker compose -f docker-compose.yml -f docker-compose.smtp.yml up -d`, then pin the actual
allowlist on the host — this is *why* the bridges are named:

```sh
# iptables backend (Docker 29 default)
iptables -I DOCKER-USER -i dmk-mail -d <smtp-ip>/32 -p tcp --dport 587 -j RETURN
iptables -I DOCKER-USER -i dmk-mail -j DROP
```

> **On the experimental nftables backend there is NO `DOCKER-USER` chain.** Add a separate table with a base chain at the same hook/priority instead. Every "put your egress rules in DOCKER-USER" recipe silently does nothing there.

The same `DOCKER-USER` technique is the belt-and-braces completion of the default topology: adding
`iptables -I DOCKER-USER -i dmk-back -j DROP` turns "replies never return" into "packets never
leave," without giving up the published port. It is a host-level change compose cannot make for you,
which is the only reason it is not the default.

### 2.2 Prerequisites

```sh
docker --version         # need >= 29.7.2 (29.3.x is missing the docker cp host-root
                         # escape fixes, a seccomp/AppArmor bypass fix, and BuildKit fixes)
docker compose version   # v5.x. v5 REMOVED the internal builder — buildx is now a hard
                         # requirement for `docker compose build`, not an optimization.
                         # `!reset`/`!override` in the no-egress file need >= v2.24.
docker buildx version
aa-status | grep docker-default
docker compose config    # must parse clean before anything else
```

---

## 3. Your reverse proxy — the contract

Nothing in this section is shipped. It is the interface the deployment expects on its front, stated
precisely enough that any proxy can satisfy it and any operator can check theirs does.

### 3.1 What your proxy MUST do

**1. Terminate TLS.** The app speaks plain HTTP and authenticates with a bearer token. Anything that
can read the wire can replay the token, so the app is only ever published on loopback (or reached
over a container-only network). If your proxy is on another machine, the link between them must
already be encrypted — a VPN, WireGuard, a private VLAN — before you touch `DAYMARK_BIND_IP`.

**2. Add `Strict-Transport-Security`.** `SecurityHeaders.kt` sends CSP, `X-Frame-Options`,
`X-Content-Type-Options` and `Referrer-Policy` on **every** response, and deliberately omits HSTS,
because the app cannot know whether it is behind TLS. HSTS is the proxy's job and only the proxy's
job. Suggested: `max-age=31536000; includeSubDomains` — add `preload` only once you are certain,
because it is effectively irreversible.

**3. Replace, never append, `X-Forwarded-For`.** Your proxy must overwrite any client-supplied value
with the address it actually observed, or append to it — but you must know which, because the app
reads the chain **right-to-left**, skipping trusted hops. Appending is safe; the forged prefix
becomes noise. Setting the header from client input is not. Caddy's `reverse_proxy` appends by
default. nginx's `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` appends; using
`$remote_addr` replaces. Both are fine.

**4. Tell the app who you are.** Set `DAYMARK_TRUSTED_PROXIES` to the proxy's source address **as
the app sees it**, which is often not the address you expect:

- proxy on the host forwarding to the published loopback port → usually the `docker0` gateway
  (commonly `172.17.0.1`), *not* the proxy's LAN address
- proxy in a container on `daymark-companion_back` → that container's address on that network
  (allocated from `10.89.0.0/24`; the app is pinned at `10.89.0.3`)

Never a broad range. A `/12` lets any co-resident container forge the header and walk past the auth
lockout — that is finding R9 in [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §7.

This is the one setting that **fails quiet**: get it wrong and forwarded headers are ignored, every
per-client lockout and rate limit keys on the proxy, all clients share one bucket, and eight bad
tokens from one attacker lock out everybody. There is no error, because ignoring an *untrusted*
header is also the correct behaviour. So the app infers it instead: the first time an
`X-Forwarded-For` arrives while the allowlist is empty, it logs one WARN naming the address it saw.

```sh
docker compose logs companion | grep X-Forwarded-For
```

Once per process, deliberately — the trigger is an attacker-supplied header, so an unbounded version
would be a log-flood amplifier.

**5. Refuse unknown `Host` / unknown SNI.** `RouteUrls.resolveBaseUrl()` prefers
`DAYMARK_PUBLIC_BASE_URL` and only falls back to the client-supplied `Host` if it is unset — which is
why compose always sets it. Closing the same path at the edge with a catch-all that returns an error
for any other Host means a poisoned invite link needs two independent mistakes, not one.

**6. Do not strip the sub-path unless you set `DAYMARK_BASE_PATH` to match.** Serving under
`/daymark` means the app must generate its own links under `/daymark`. Caddy's `handle_path` strips
the prefix; `handle` does not. nginx `proxy_pass` with a trailing slash strips; without, it does not.
Pick one and make `DAYMARK_BASE_PATH` agree with it.

**7. Do NOT add your own Content-Security-Policy.** `SecurityHeaders.kt` already sends one, and two
CSP headers on the same response are **intersected** by the browser, not overridden. The app's
policy includes `'wasm-unsafe-eval'` because all decryption happens in a libsodium WASM module in
the browser; a proxy adding a "hardened" CSP without it silently kills every crypto operation in the
viewer, with no error a non-expert would connect to the cause. If your proxy has a "security headers" or
"harden this route" toggle, check what CSP it emits before enabling it: either turn CSP off for
this route or make it byte-identical to the app's.
Caddy's set-if-absent form is a `?`-prefixed header; nginx `add_header` has **no** set-if-absent
form, which is one of the reasons the nginx example needs care.

**8. Point health checks at the right endpoint.** `/healthz` is liveness: the process is up and routing. `/readyz` additionally proves `/data` is writable and returns 503 when it is not. Point *monitoring* at `/readyz` — it is the one that catches a full disk. Think before pointing a load balancer there: with a single backend, failing readiness turns a degraded-but-readable server into a 502, taking away the operator's last way to read their own data. Both are unauthenticated and content-free (`{"ok":true}` / `{"ok":false}`); the reason for a failure goes to the server log, not to an anonymous caller.

**9. Cap the request body and set read timeouts.** The app enforces its own caps — 64 KiB on JSON
routes (`RequestLimits.kt`) and `DAYMARK_MAX_REQUEST_BYTES` (26 MiB) on blob uploads — so this is
defence in depth rather than the only line. Timeouts are more than that: Netty is non-blocking so
connection-count slowloris is weak, but nothing in the app bounds how slowly a client may dribble a
request, and with the bundled proxy gone nothing else does either. Suggested: ~10 s header read,
~120 s body read, ~120 s idle, and a body cap a little above 26 MiB.

### 3.2 Worked examples

`docs/alternatives/` holds configs for the common proxies. They are **references, not shipped
artefacts** — nothing in CI parses them, and they are the operator's to adapt:

| File | Notes |
|---|---|
| `docs/alternatives/Caddyfile` | Public / Let's Encrypt. Includes the catch-all Host refusal, HSTS, the redaction filter on the access log, and the reasoning for each. Written for the container-on-a-shared-network topology (`no-egress.yml`); change the upstream to `127.0.0.1:8080` for a host install. |
| `docs/alternatives/Caddyfile.lan` | LAN with no public DNS, using Caddy's internal CA. |
| `docs/alternatives/nginx.conf` | Has **no catch-all `default_server`** — add one before using it publicly, or requirement 5 above is unmet. |
| `docs/alternatives/traefik.md` | Label-driven; note it wants `/var/run/docker.sock`, which the rest of this deployment goes out of its way to avoid. |

**Cosmos Cloud** (the maintainer's setup) is a containerised proxy, so it takes the second topology:

```sh
docker compose -f docker-compose.yml -f docker-compose.no-egress.yml up -d --build
docker network connect daymark-companion_back cosmos-server
```

Then add a route in the Cosmos UI with target `http://daymark-companion:8080`, and set
`DAYMARK_TRUSTED_PROXIES` in `.env` to the address Cosmos was given on that network:

```sh
docker inspect cosmos-server \
  --format '{{range $n, $c := .NetworkSettings.Networks}}{{$n}} {{$c.IPAddress}}{{"\n"}}{{end}}'
```

Take the address on the `daymark-companion_back` row, not any other, and write it as a `/32`.

**Not verified from here, so check it in your install rather than trusting this paragraph:** whether
your Cosmos version adds security headers of its own to proxied routes (requirement 7 — a second CSP
breaks in-browser decryption, so look at the response headers before and after enabling any
"harden this route" option), whether it sends HSTS (requirement 2 — if not, add it), and whether its
per-container private-network feature attaches to an existing network or creates its own. If it
creates its own, use *that* network instead of `docker network connect` above and read
`DAYMARK_TRUSTED_PROXIES` from it the same way — but do not do both, or the app will see whichever
address Docker happens to route from and the allowlist will miss.

### 3.3 The bundled Caddyfiles

`companion/reverse-proxy/` is gone. `Caddyfile` and `Caddyfile.lan` moved to `docs/alternatives/`
alongside the nginx and Traefik examples, where their status is honest: worked references, not
tested deployment artefacts. The CI step that ran `caddy validate` against them went with them —
it was validating a file the deployment no longer uses.

Note the caveat that applied when they were shipped and still applies: the four examples do not
agree with each other on sub-path prefix stripping (nginx says "do not strip"; the Caddy example
uses `handle_path`, which does). Requirement 6 above is the rule; the examples are illustrations of
it.

### 3.4 Rate limiting — stated plainly

**Rate limiting lives in the application, and that is now the only place it lives.** `Config.kt` implements `DAYMARK_RATE_LIMIT_RPS` (default 5), `DAYMARK_AUTH_LOCKOUT_FAILS` (8) / `_SECONDS` (900), `DAYMARK_TOTP_LOCKOUT_FAILS` (5) / `_SECONDS` (300), and `DAYMARK_REISSUE_MAX_PER_HOUR` (3) — all keyed on the real client IP **only if `DAYMARK_TRUSTED_PROXIES` is set correctly**, which is requirement 4 in §3.1 and the operator's job. That is the layer that actually knows which endpoints are sensitive.

**If you want proxy-layer rate limiting too, your proxy decides how.** Traefik has `rateLimit` as a first-class middleware; nginx has `limit_req`; Cosmos Cloud has a per-route limiter in its UI. For Caddy the answer is more involved, and it is recorded here because it is the counter-intuitive one:

**Stock Caddy 2.11 has no rate limiting.** It is not a directive; the current directives index contains zero occurrences of "rate". Anyone saying `rate_limit` works on a stock Caddy is wrong. The complete opt-in, with its cost stated:

`Dockerfile.caddy`
```dockerfile
FROM caddy:2.11.4-builder-alpine AS builder
RUN xcaddy build v2.11.4 --with github.com/mholt/caddy-ratelimit
FROM caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
```
Caddyfile addition (inside the site block; `rate_limit` is ordered before `basic_auth` by default):
```caddyfile
	rate_limit {
		zone auth {
			match { path /therapist* /owner* /v1/recovery* /v1/totp* }
			key    {remote_host}
			events 20
			window 1m
		}
		zone general {
			key    {remote_host}
			events 300
			window 1m
		}
		jitter 0.2
	}
```
**Cost:** you now build and maintain your own Caddy binary. You lose "pin an official digest and forget it," and you must rebuild on every Caddy security release. The module is by Caddy's author but is **explicitly not an official Caddy repo**; its WIP notice was removed 2026-01-16 and it gained subnet limiting (`ipv4_prefix`/`ipv6_prefix`) in May 2026, but its `go.mod` still requires caddy v2.10.0, so building against 2.11.4 relies on xcaddy upgrading the dependency. **Build it before committing to it.**

**Middle ground:** `fail2ban` reading the proxy's access log. Keeps a stock image, blocks persistent abusers at the firewall. Requires a full per-request access log, which reintroduces exactly the trail §5 exists to avoid keeping — so it trades the privacy posture for abuse resistance. Do not adopt it without deciding that trade deliberately.

---

## 4. The revised Dockerfile

### 4.1 New file: `/home/user/daymark/companion/healthcheck/main.go`

Stdlib only — no `go.sum`, no third-party module, **no network access at build time**. This satisfies the "no third-party origins" rule at build time as well as runtime.

```go
package main

import (
	"fmt"
	"net/http"
	"os"
	"time"
)

func main() {
	url := os.Getenv("DAYMARK_HEALTHCHECK_URL")
	if url == "" {
		// Literal IP, not "localhost": distroless has no /etc/nsswitch.conf and Go's
		// resolver behaviour on a name is an avoidable variable.
		url = "http://127.0.0.1:8080/healthz"
	}
	resp, err := (&http.Client{Timeout: 2 * time.Second}).Get(url)
	if err != nil {
		fmt.Fprintln(os.Stderr, "healthcheck:", err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		fmt.Fprintf(os.Stderr, "healthcheck: status %d\n", resp.StatusCode)
		os.Exit(1)
	}
}
```

### 4.2 `/home/user/daymark/companion/Dockerfile` (replacement)

```dockerfile
# syntax=docker/dockerfile:1
#
# Daymark Companion — hardened multi-stage build.
#   1. web         — vendored Svelte/Vite bundle (no third-party origins at runtime)
#   2. server      — Ktor fat jar, built via the CI-validated wrapper
#   3. healthcheck — ~2 MB stdlib-only static Go probe (distroless has no shell/curl)
#   4. runtime     — distroless java21-debian13:nonroot, digest-pinned
#
# Every FROM is pinned by INDEX (multi-arch) digest. Pinning a per-arch manifest digest
# breaks arm64 builds. Re-resolve digests per §9.1 before each release.

# ---- 1. Web bundle ----------------------------------------------------------
FROM node:24-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 AS web
WORKDIR /web
# Node 22 is Maintenance LTS (EOL 2027-04-30); 24 is Active LTS (EOL 2028-04-30).
# Alpine/musl is fine here — nothing from this stage runs at runtime.
# Activate the exact pnpm version; do not rely on corepack's lazy auto-download.
RUN corepack enable && corepack prepare pnpm@10.33.0 --activate
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

# ---- 2. Server fat jar ------------------------------------------------------
FROM gradle:8.11.1-jdk21@sha256:7990a44ed0ad609ee740426d3becc69ae7d10a5ed14da7e354ad83cf7ef1d087 AS server
WORKDIR /src
COPY server/ ./
# Use ./gradlew, NOT the image's `gradle`. CI runs gradle/actions/wrapper-validation
# against server/gradlew; invoking the image's Gradle meant the validated wrapper never
# touched the shipped artifact and the two builds could silently diverge in version.
# Requires distributionSha256Sum in gradle-wrapper.properties (§4.3).
RUN chmod +x ./gradlew && ./gradlew --no-daemon shadowJar
# Distroless has no shell, so /data cannot be created with `RUN mkdir && chown`.
# Create it here; Docker copies ownership from the image on FIRST mount of an empty volume.
RUN mkdir -p /empty-data

# ---- 3. Static healthcheck binary ------------------------------------------
FROM golang:1.26-alpine@sha256:0178a641fbb4858c5f1b48e34bdaabe0350a330a1b1149aabd498d0699ff5fb2 AS healthcheck
WORKDIR /src
COPY healthcheck/main.go .
RUN go mod init daymark/healthcheck \
 && CGO_ENABLED=0 GOFLAGS=-trimpath go build -ldflags="-s -w" -o /out/healthcheck .

# ---- 4. Runtime -------------------------------------------------------------
# Debian 13 (trixie) is the maintained distroless line; java21-debian12 still resolves
# but is off it. gcr.io remains the canonical domain — distroless moved its serving infra
# to Artifact Registry behind the same hostname, so the 2025 "gcr.io shutdown" panic does
# not apply. Base carries JAVA_VERSION=21.0.12 (July 2026 CPU), User=65532, ~63.5 MB.
FROM gcr.io/distroless/java21-debian13:nonroot@sha256:e7c40c2378f8c4462aea63041f6b29b7f2b9ab8e80b9217d6d5faf70dfff1779 AS runtime
WORKDIR /app
COPY --from=server      /src/build/libs/daymark-companion.jar /app/app.jar
COPY --from=web         /web/dist                             /app/web
COPY --from=healthcheck /out/healthcheck                      /usr/local/bin/healthcheck
COPY --from=server --chown=65532:65532 /empty-data            /data

ENV DAYMARK_BIND_ADDR=0.0.0.0 \
    DAYMARK_PORT=8080 \
    DAYMARK_DATA_DIR=/data \
    DAYMARK_WEB_DIR=/app/web \
    DAYMARK_BASE_PATH=/ \
    DAYMARK_LOG_LEVEL=warn

EXPOSE 8080
USER 65532:65532

# EXEC (JSON-array) form ONLY.
#
# THIS IS THE SINGLE MOST CONSEQUENTIAL CORRECTION TO THE SCAFFOLD: the previous
# `HEALTHCHECK ... CMD curl -fsS ... || exit 1` is SHELL form, which Docker wraps in
# `/bin/sh -c`. There is no /bin/sh in distroless, so it does not merely lose curl — it
# cannot execute at all, and the container pins `unhealthy` forever. And because Docker
# NEVER restarts a container for being unhealthy (only Swarm/K8s do), that failure is
# completely silent on a single VPS.
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD ["/usr/local/bin/healthcheck"]

# The base image sets its own ENTRYPOINT ["/usr/bin/java","-jar"]. It must be replaced in
# FULL or -Djava.io.tmpdir=/tmp is silently dropped. (JAVA_TOOL_OPTIONS in compose also
# carries the flag; both are set deliberately, belt and braces.)
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=70.0", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
```

### 4.3 Wrapper hardening — `server/gradle/wrapper/gradle-wrapper.properties`

Add (keep the existing 8.11.1 URL):
```properties
distributionSha256Sum=<sha256 from https://services.gradle.org/distributions/gradle-8.11.1-bin.zip.sha256>
```

> **Deliberately NOT doing now:** the Gradle 9 bump. Gradle 9.7.0 is current, `gradle:9-jdk21` resolves to 9.6.1, and Shadow 9.6.1 requires Gradle ≥ 9.2 — a consistent set. But that change is *coupled*: it touches `gradle-wrapper.properties` AND `build.gradle.kts` (`com.gradleup.shadow` 8.3.5 → 9.6.1). **Ship it as its own PR.** A broken fat jar and a broken base image are much easier to debug apart than together.

### 4.4 One-time migration for existing installs (UID 10001 → 65532)

Named volumes keep their original ownership — Docker only copies ownership from the image on the *first* mount of an *empty* volume. This is a migration step, not a rebuild.

```sh
docker compose down
docker run --rm -v daymark-companion_blobs:/data alpine:3 chown -R 65532:65532 /data
docker compose up -d --build
```

Also update every hardcoded `10001` in `docs/COMPANION_DEPLOYMENT.md` — it appears in the compose examples (~L187, L346), the restore runbook (L837), the `init-perms` service (L852), and the hardening table (L1002).

> **Why 65532 rather than keeping 10001:** distroless has an `/etc/passwd` entry only for 65532. Java run as an unknown UID resolves `user.home` to `?`, which has bitten JVM apps before. Adopting the base image's own UID avoids testing that.

### 4.5 Debugging swap (temporary — never commit)

```dockerfile
# FROM gcr.io/distroless/java21-debian13:debug-nonroot@sha256:1783bfe9abfab548623e692d7ceb743c088c0071e04108f68d39135b698607f4
# then: docker compose exec companion /busybox/sh
```

### 4.6 Optional — attestations for published images

```sh
docker buildx build --sbom=true --provenance=mode=max \
  --tag ghcr.io/<org>/daymark-companion:<version> --push companion/
cosign sign --yes ghcr.io/<org>/daymark-companion:<version>@sha256:<digest>
```
Note: buildx ≥0.10 already attaches **provenance** by default unless disabled — you may be shipping it already. SBOM is still opt-in. **BuildKit does not cryptographically sign attestations**; the cosign step is separate and necessary.

### 4.7 Renovate — so the pins don't rot

`/home/user/daymark/renovate.json`
```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": ["config:recommended", "docker:pinDigests"],
  "packageRules": [
    { "matchManagers": ["dockerfile", "docker-compose"], "pinDigests": true }
  ]
}
```
A pinned-and-rotting base is now considered worse than an unpinned one. Digest pinning without an automated update path is the anti-pattern.

---

## 5. Logging + retention policy

### 5.1 Architecture

Two layers, not three — the access log left with the bundled proxy and is now the operator's to
configure, or not.

| | Container logs | Audit log |
|---|---|---|
| **Where** | Docker `local` driver | SQLite `AuditStore` |
| **Subject** | process output | a semantic action |
| **Read by** | operator | **the OWNER (data subject)** |
| **Content** | app WARN/ERROR only | actor / action / objectRef |
| **Integrity** | none | SHA-256 hash chain, append-only |
| **Retention** | size-bounded (3 × 10m) | 90 days, operator-configurable |

Worst case log disk: 1 container × 3 files × 10 MiB = **30 MiB uncompressed ceiling**, less in practice since `local` compresses rotated files. Nothing else in this stack writes logs. **Disk cannot fill from logging** — but that ceiling now covers only what this compose file starts. A proxy with an unbounded access log on the same disk reintroduces the whole problem, so set rotation caps on it too.

`docker compose logs` works normally with the `local` driver. Its on-disk format is internal and not readable by external file-tailing agents — normally a drawback, here a mild privacy feature.

### 5.2 NEVER LOG — app-specific, at any level, in any component

These are specific to Daymark, not a generic list:

- **Request/response bodies.** They are ciphertext, but **length alone is a signal**.
- **Bearer-equivalent secrets:** `X-Rel-Token`, `Authorization`, `X-CSRF-Token`, `X-Content-Hash`, `X-Setting-Key`, `Cookie`/`Set-Cookie`.
- **Any concrete path parameter:** `relRef`, `lineage`, `version`, `inviteId`, `channel`. `relRef` *is* the relationship identifier. Log the matched route **template** (`/v1/rel/{relRef}/{channel}`), never the resolved path.
- **Email addresses** (present in `RecoveryRoutes`, `TherapistAuthRoutes`, `OwnerAccountStore`).
- **TOTP codes, recovery tokens/confirm tokens, invite secrets, WebAuthn challenges and credential IDs.**
- **Blob sizes or byte counts per request.** Entry length is an acuity/withdrawal inference vector.
- **Full stack traces on request-handling paths** (see §5.4).

### 5.3 Redact / coarsen

- **Client IP: OFF in the app** (`DAYMARK_ACCESS_LOG_SOURCE_IP=false`, confirmed consumed by `Config.kt:130`), **and it should be off in your proxy's access log too.** The reason is correct as stated in the repo: an IP geolocates both the clinic *and* the patient. Caddy's form is `request>remote_ip delete`; if you genuinely need it for abuse triage, coarsen rather than keep it — `request>remote_ip ip_mask { ipv4 16  ipv6 32 }`, and **never `ip_mask 0`, which disables masking**. This is now a request, not a guarantee: this repo cannot enforce anything about a log it does not write.
- **User-Agent: delete.** It fingerprints the patient's device, and you already know it's the app.
- **Timestamps:** second granularity, `TZ=UTC` (set in compose). Sub-second timing on a low-volume single-user server is itself a behavioral signal.

### 5.4 Two code fixes that matter more than the stack choice

**(a) `server/src/main/kotlin/com/daymark/companion/Application.kt:98`** — confirmed present:
```kotlin
log.error("unhandled error on {}", call.request.local.uri, cause)
```
`call.request.local.uri` is `/v1/rel/{relRef}/{channel}/{lineage}/{version}` — **`relRef` is the relationship identifier** — and this dumps a full stack trace whose frames may carry parameter values. The HTTP *response* is correctly generic; the *log* is not. Replace with a route-template-only form and log `cause.javaClass.simpleName` rather than the whole throwable — the pattern the codebase already uses correctly in `Mailer.kt:50` and `RecoveryRoutes.kt:99`.

This is also an availability issue: malformed JSON bodies throw inside `call.receive<T>()`, are caught by `StatusPages` as `Throwable`, and become **500 + a logged stack trace** rather than 400. Garbage input floods the log.

**(b) `server/src/main/resources/logback.xml`** — `Application.kt:66 applyLogLevel` only sets the level of the `com.daymark.companion` logger. `io.ktor` and root stay pinned at INFO by `logback.xml`, so **`DAYMARK_LOG_LEVEL=warn` does NOT quiet Ktor.** Either drive those from the env var too, or add `<logger name="io.ktor" level="WARN"/>`.

### 5.5 Retention policy statement (for the docs)

- **Container logs:** size-bounded, ~30 MiB/container pre-compression. No time policy.
- **Access log:** effectively empty (5xx only). No user-identifying fields survive the filter.
- **Audit log:** 90-day default (`AuditStore.DEFAULT_RETENTION_SECONDS`), operator-set via `DAYMARK_ACCESS_LOG_RETENTION_DAYS` (confirmed consumed, `Config.kt:129`).

> **Unresolved retention tension — state it, do not paper over it.** A therapy practice that is a HIPAA covered entity faces 45 CFR §164.316(b)(2)(i)'s six-year documentation retention, and 164.312(b) Audit Controls is a *required*, not addressable, specification. Whether raw system-generated audit logs fall under "documentation required by this subpart" is **genuinely contested** among sources. The design consequence is concrete: retention must be an operator-set knob with the trade-off spelled out (90 days minimizes breach exposure; six years may be required of a practice), and **the default should stay short because an individual self-hoster is not a covered entity at all.** A practice needs its own counsel.

### 5.6 Audit-log coverage gaps to close (priority order)

The existing `AuditStore` is well-built — closed action taxonomy, opaque `objectRef`, explicit "never pass plaintext/keys/TOTP" contract, SHA-256 chain, an `auditSafely {}` wrapper so a logging bug never fails a request, and an honest docstring conceding the chain proves internal consistency and **not** completeness. Its gaps are coverage, not design:

1. **`OWNER_TOKEN_REISSUED`** — `RecoveryRoutes` mints a new owner token via an *unauthenticated* flow. Highest-privilege action in the system, currently absent from `AuditAction`. Must remain visible after the token rotates.
2. **`ACCESS_REVOKED` / `RELATION_DELETED`** — revocation is what a patient most needs proof of.
3. **`INVITE_MINTED` / `INVITE_EXPIRED`** — only `ENROL_OK` exists, so a minted-but-unredeemed invite is invisible.
4. **`AUDIT_READ`** — an audit trail nobody can audit is a half-measure.
5. **`BULK_READ` vs single read** — a therapist paging an entire lineage is a different event from opening one item; the distinction is what makes exfiltration visible.
6. **`POLICY_CHANGED`** — flipping `DAYMARK_ACCESS_LOG_SOURCE_IP` on or shortening retention is a change to the privacy posture and must itself be audited.
7. **`AUTHZ_FAIL` distinct from `AUTH_FAIL`** — a valid credential reaching for someone else's relationship is a materially different signal from a bad password.

### 5.7 Escape hatches (do not adopt by default)

- **Need search/alerting, or logs must leave the box:** `timberio/vector:0.57.0-distroless-libc@sha256:bb5968aa63f3bf7cf77f8cd7548c1442cc91f2a8c20d7ef5c5a03e2a37fbf205`, used as a redaction **gate** on the egress boundary, not an aggregator. VRL is the right tool for enforcing §5.2 before anything ships. ~30–60 MB RSS (estimate, unmeasured).
- **Multi-site practice, central retention:** `grafana/alloy:v1.18.1@sha256:0f4434c92b3e6cdac38bb129b344e1790c246f7b6e2eaffcc16a5fa363240e33` + `grafana/loki:3.7.6@sha256:efd47c67f9bac88ca29bcf8cb997d9ab29d1848bd0aff579282295542a745952` on a separate `internal: true` network. **Loki has NO authentication of its own** — you must supply it, and never expose its port. **Do NOT use the common Alloy config that mounts `/var/run/docker.sock`** for container discovery; that hands Alloy root-equivalent host access and undoes everything in §2. `loki.source.docker` reads the Docker API rather than tailing files, so the `local` driver is not a blocker — but it still needs the socket. Prefer having the app write structured JSON to a file on a read-only bind of the data volume.
- **Promtail: EOL 2026-03-02. Never.**

---

## 6. Security audit checklist

Ordered by **real-world likelihood × impact**. Status: ✅ = mitigated by the compose/proxy config above · ⚠️ = partially mitigated · ❌ = **open**, requires separate work.

### P0 — fix before any public exposure

| # | Item | Status |
|---|---|---|
| **1** | **`DAYMARK_TRUSTED_PROXIES` unset behind a proxy.** Highest-probability real incident. Default behaviour: `AuthGuard` keys every request on the proxy's container IP → 8 bad bearer tokens from anywhere on the internet lock out **every** client for 900 s; `rateLimitRps=5` is likewise global; 3 anonymous recovery attempts exhaust the owner's hourly budget. Worse than documented: `RelationRoutes.resolveRole()` calls `recordFailure()` for any wrong bearer on `/v1/rel/...`, so the therapist surface is a second unauthenticated lever on the same global counter. | ⚠️ **the mitigation this row claimed is gone.** It read "✅ set to `10.89.0.2/32` via pinned IPAM" until 2026-08-09 — that was the bundled Caddy's pinned address, and removing the bundled proxy deleted those two compose lines. `DAYMARK_TRUSTED_PROXIES` now defaults to **empty** (trust nothing), which is correct but is not a mitigation: it makes the failure mode *unset*, which is precisely the state this row describes. What replaces the pin is §3.1 requirement 4 (the operator sets it) plus a one-shot WARN naming the observed address the first time a forwarded header arrives while the list is empty — `RequestClient.kt`. Detection, not prevention. |
| **2** | **Netty 4.1.116.Final request-smuggling primitives.** Ktor 3.0.3 (confirmed in `server/build.gradle.kts:11`) transitively pins Netty 4.1.116.Final. **CVE-2025-58056** (bare-LF chunk terminator; the advisory names nginx as the desyncing front-end class — you are building exactly that topology), **CVE-2025-67735** (CRLF in request URI), **CVE-2026-42587** (br/zstd/snappy decompression bomb bypasses `maxAllocation`). Bump `ktorVersion` → **3.5.2** (pins Netty 4.2.13.Final, at or above all three fixes). Verify: `./gradlew dependencies --configuration runtimeClasspath \| grep -i netty`. | ❌ **code change** |
| **3** | **logback-classic 1.5.12** (confirmed, `build.gradle.kts:12`) is the exact top affected version of CVE-2024-12798 (Janino EL injection → RCE) / CVE-2024-12801 (SaxEventRecorder SSRF). Minimum fix **1.5.13**. | ❌ **code change** |
| **4** | **bcprov-jdk18on 1.79** (confirmed, `build.gradle.kts:32`) falls inside CVE-2026-0636 (LDAP injection, affects 1.74–1.83). Fixed 1.84; latest 1.85.2. | ❌ **code change** |
| **5** | **App exposed directly to the internet / Docker publishes past UFW.** Docker DNATs in `PREROUTING` and traverses `FORWARD`; UFW's rules live in `INPUT`, so `ufw deny 8080` does nothing to `-p 8080:8080`. Re-verified: still true by design in 2026. | ✅ still holds, by a different route. The app publishes to `127.0.0.1` only, and the UFW-bypass applies to `0.0.0.0` bindings: a loopback DNAT rule is unreachable from the LAN whatever UFW says. The no-egress override has no published port at all. *(Was: "no `ports:`, `internal: true` + isolated" — that topology went with the bundled proxy; see §2.0.)* |
| **6** | **Host-header link poisoning.** `RouteUrls.resolveBaseUrl()` falls back to the client `Host` header when `DAYMARK_PUBLIC_BASE_URL` is unset. `POST /v1/invite` then emits — **via your SMTP server** — an invite link on an attacker domain carrying `#id=<inviteId>&s=<secret>`. `RecoveryRoutes` correctly refuses the fallback; `TherapistAuthRoutes` does not. | ⚠️ `DAYMARK_PUBLIC_BASE_URL` is set unconditionally by compose (derived from the required `DAYMARK_DOMAIN`), so the fallback is not reachable in a compose deployment. The second line of defence — a catch-all refusing unknown `Host` — is now **requirement 5 in §3.1**, i.e. the operator's proxy, and unverifiable from here. **Still open in code:** make it fail closed at startup |

### P1 — deployment shape

| # | Item | Status |
|---|---|---|
| **7** | **No resource limits → any bug is a host-wide DoS.** No `mem_limit`/`pids_limit`/`ulimits`, and a JVM with no container limit sizes its heap off *host* RAM. | ✅ `deploy.resources.limits` + `ulimits` + `-XX:MaxRAMPercentage=70` + `ExitOnOutOfMemoryError` |
| **8** | **Unbounded request bodies on unauthenticated routes.** Confirmed: `readCapped()` is applied only in `SyncRoutes.kt:40,78` and `RelationRoutes.kt:135`. `RecoveryRoutes.kt:66,88,115` and `TherapistAuthRoutes.kt:80,97,114,142` use bare `call.receive<T>()` — including `POST /v1/recovery/request`, which requires **no credential at all**. An anonymous multi-GB body is a heap-exhaustion primitive. | ✅ **fixed in code since this audit.** `RequestLimits.kt` adds a 64 KiB cap and all 7 bare `call.receive<T>()` calls now use `receiveCappedJson<T>()`; `/v1/recovery/request` checks its rate budget *before* reading the body. The Caddy `request_body max_size` that originally capped the worst case is gone — §3.1 requirement 9 asks the operator's proxy for the same thing as defence in depth. |
| **9** | **Unbounded log growth.** `restart: unless-stopped` + `log.error(..., cause)` on every unhandled throwable + no `logging:` block = unrotated stack traces on the same disk as `/data`. `json-file` defaults to `max-size: -1`. | ✅ `local` driver, 10m × 3, compressed |
| **10** | **Unpinned mutable base image tags** (all four `FROM` lines today). Non-reproducible builds; a tag repoint is invisible. `21-jre-jammy` also ties you to Ubuntu 22.04 (standard support ends April 2027). | ✅ all four pinned by index digest; ❌ Renovate not yet configured |
| **11** | **`curl` in the runtime image.** Gives any RCE a working HTTP client inside a container that has the blob volume mounted. This is the *security* argument for distroless, distinct from the size argument. | ✅ distroless — no curl, no shell, no package manager |
| **12** | **HEALTHCHECK in shell form on a shell-less base.** Wrapped in `/bin/sh -c` → cannot execute → container pins `unhealthy` forever, and Docker **never restarts on unhealthy** so the failure is silent. | ✅ exec-form static Go binary |
| **13** | **Slowloris / missing downstream timeouts.** Netty is non-blocking so connection-count slowloris is weak, but no read/idle timeouts existed at either layer. | ✅ **closed in the app.** `requestReadTimeoutSeconds` / `responseWriteTimeoutSeconds` are now set on the Netty engine (120 s each — a 25 MiB snapshot over a slow mobile uplink is a legitimate long request, so 10 s would break more than it fixed). The `pids` limit and Netty's non-blocking model still apply on top. §3.1 requirement 9 still asks the operator's proxy for header-read timeouts, which the app cannot enforce for itself, but the deployment no longer *depends* on a proxy it cannot see. |
| **14** | **Secrets in `environment:`** — leak into `docker inspect`, crash dumps, child processes, and logs. | ✅ `secrets:` with `file:` sources, mounted `0400` at `/run/secrets/`, consumed via `*_FILE` |
| **15** | **Docker socket exposure.** Currently zero in the repo; Traefik's Docker provider and the standard Alloy discovery config are the two things that would introduce it. Mounting it `:ro` is **not** a mitigation — the API is not read-only because the file is. | ✅ stronger than before: this stack starts one container and mounts no socket at all. Note the flip side — a Traefik or Cosmos Cloud front-end typically *does* want the socket, so the exposure moves into the operator's proxy and out of this repo's control. |
| **16** | **Docker Engine 29.3.x is missing security fixes** (docker cp host-root escape, seccomp/AppArmor bypass, BuildKit). | ❌ **host prerequisite** — upgrade to 29.7.2 |
| **17** | **Double CSP header → intersection → libsodium WASM breaks.** The app already sends CSP; a naive edge CSP without `'wasm-unsafe-eval'` silently kills all in-browser crypto. | ⚠️ **now the operator's to get right.** The app's CSP is the only one this repo controls; the set-if-absent edge copy went with Caddy. Written up as §3.1 requirement 7, including the specific failure — a proxy-added CSP without `'wasm-unsafe-eval'` intersects with the app's and silently kills in-browser decryption. |
| **18** | **`X-Forwarded-For` append semantics.** Caddy **appends** the client IP to any inbound XFF; the old Caddyfile comment claiming otherwise was factually wrong. Safe today only because `ClientAddress.resolve()` walks right-to-left — a left-to-right refactor turns a global-lockout DoS into an unlimited-attempts bypass. | ✅ the regression test exists: `ClientAddressTest` covers a forged prefix, a direct connection forging the header, and hex-hostname / leading-zero octet inputs. The edge-side `header_up ... {client_ip}` replacement is gone with Caddy, so right-to-left is no longer belt-and-braces — it is the only thing standing between an appended chain and a spoof, which is why the test matters more now, not less. §3.1 requirement 3 states the append-or-replace contract. |
| **19** | **Sensitive identifiers in logs** (`relRef` + stack trace at `Application.kt:98`). | ❌ **open, and less covered than it was.** The 5xx-only filtered access log was Caddy's. `Application.kt` still logs `call.request.local.uri` — which contains `relRef` — with a full stack trace. |
| **20** | **Cert renewal failure as an availability event.** LE `tlsserver` is now 45-day, default `classic` goes 64-day on 2027-02-10 — shorter windows make hand-rolled renewal fail faster and hurt more. | ➖ **out of scope now.** Certificates belong entirely to the operator's proxy. The finding stands as guidance (§7): pick a proxy with a native ACME client and persist its certificate store, because 45–64 day certs punish hand-rolled renewal. |

### P2 — supply chain and posture

| # | Item | Status |
|---|---|---|
| **21** | **Gradle build stage bypasses the wrapper CI validates.** `gradle/actions/wrapper-validation` runs against `server/gradlew`, but the Dockerfile ran the *image's* Gradle — the validated wrapper never touched the shipped artifact. | ✅ now `./gradlew`; ❌ `distributionSha256Sum` must be filled in |
| **22** | **No Gradle dependency verification.** No `gradle/verification-metadata.xml`. For a project whose entire value proposition is "the server cannot read your data," a compromised transitive JAR is the direct path to falsifying that. | ❌ open |
| **23** | **pnpm 10.33.0 forgoes `minimumReleaseAge`.** pnpm 11 (11.20.0) enables it by default at 1440 min — the single highest-leverage npm supply-chain control available; the Shai-Hulud waves were detected in ~12 h, so a 24 h resolution delay blocks them structurally. Also consider `strictDepBuilds: true` (Shai-Hulud propagated entirely via `preinstall`). | ❌ open — **lockfile-format compatibility must be checked before bumping** |
| **24** | **`^` semver ranges throughout `web/package.json`.** Safe today via `--frozen-lockfile` in CI and the Dockerfile, but `vite ^6.0.7` spans CVE-2025-30208 / CVE-2026-39363 — **dev-server-only**, so production is unaffected, but `pnpm dev` runs on the maintainer's machine, which holds deploy credentials. | ❌ open (low) |
| **25** | **GitHub Actions pinned by tag, not SHA.** `permissions: contents: read` is already set (the important half). SHA-pinning third-party actions (`pnpm/action-setup`, `docker/*`) is the next increment. | ❌ open (low) |
| **26** | **Healthcheck proves the wrong thing.** `/healthz` returning 200 does not prove `/data` is writable, that SQLite is not locked, or that the disk has space — the three things that actually break this server. | ◐ **mostly closed.** Split into liveness (`/healthz`, unchanged) and readiness (`/readyz`, new): the latter writes and fsyncs a 4 KiB fixed-name probe into `DAYMARK_DATA_DIR`, which catches a read-only mount, wrong volume ownership and a full disk in one check, and returns 503 when it fails. The container HEALTHCHECK now probes `/readyz`; CI asserts both the 200 and the 503. **Still open:** SQLite lock contention is deliberately NOT probed — see `Readiness.kt` for why (probing it means either holding `BlobStore`'s lock, so a long write hangs the health endpoint, or touching its connection off-thread, which sqlite-jdbc forbids). A wedged writer still reports ready. |
| **27** | **IDOR latent in the plain sync API.** `/v1/snapshots/{lineage}/{version}` is gated by exactly one global bearer token — no per-lineage authorization — and `GET /v1/snapshots` **enumerates every lineage on the server**. Fine for the documented single-owner model; a privilege escalation the moment anyone deploys this for a practice with more than one client, which the project explicitly contemplates. | ❌ open — enforce the single-tenant invariant or add a per-lineage owner column now |
| **28** | **`DAYMARK_COOKIE_INSECURE`** exists for dev — one env var from session cookies over plaintext. | ⚠️ not set; add a startup refusal when it is set together with a `https://` public base URL |
| **29** | **TOTP enroll/verify are unauthenticated POSTs with no CSRF token and no `Origin`/`Sec-Fetch-Site` check.** A cross-site POST cannot read the response, but it can burn the per-credential counter and lock a therapist out (5 fails → 300 s). Low severity, real. | ❌ open — check `Sec-Fetch-Site: same-origin` |
| **30** | **Path-traversal via proxy/app normalisation mismatch.** The app side is fine (Ktor rejects traversal; blob paths derive from a `^[A-Za-z0-9_-]{1,64}$` charset with a `Long` version). Risk is a proxy decoding `%2e%2e%2f` before forwarding, and the three shipped proxy examples disagreeing on sub-path prefix stripping. | ⚠️ **the mismatch risk moved rather than closed.** No proxy is shipped, so no proxy's normalisation is this repo's to guarantee; the four examples in `docs/alternatives/` still disagree on prefix stripping, which is why §3.1 requirement 6 states the rule rather than leaving it to the examples. App side unchanged and fine. ❌ fuzz test open |
| **31** | **CIS/OWASP baseline items** — seccomp default (never `unconfined`), AppArmor `docker-default`, `no-new-privileges`, `cap_drop: ALL`, non-root, read-only rootfs + tmpfs, `init: true`. | ✅ all applied |

### Checked negatives — record these as *checked*, not absent

- **SQL injection:** every statement in `BlobStore.kt` is a `PreparedStatement`; the only `createStatement()` uses are constant SQL. `requireValidName()` gates `lineage` before it reaches SQL *or* the filesystem. **Regression risk, not current state** — add a CI lint for `"SELECT ... $"` interpolation. *(Not read: `RelationStore`, `AuthStore`, `AuditStore` internals — see §9.)*
- **Open redirect:** no redirect-by-parameter anywhere in the SPA or routes; no `respondRedirect` on user input.
- **Reflected XSS:** zero `{@html}`, `innerHTML`, `eval`, `new Function`, `document.write` in `web/src`. Svelte auto-escapes. CSP has no `unsafe-inline` and exactly one narrow relaxation (`wasm-unsafe-eval`). Add a CI grep gate for `{@html}`.
- **Clickjacking:** `frame-ancestors 'none'` + `X-Frame-Options: DENY` as real headers on every response including error pages.
- **Deserialization gadgets:** kotlinx.serialization, `@Serializable` data classes, no polymorphic/contextual registration.
- **Server-side archive handling:** none — blobs are opaque bytes that are only SHA-256'd. The only decompression surface is HTTP-level (CVE-2026-42587) and the browser's own `Dropzone.svelte`.
- **Dependency confusion:** all JVM deps from Maven Central under real coordinates, all npm deps public, no internal package names. Verify `settings.gradle.kts` declares only `mavenCentral()` with `repositoriesMode.set(FAIL_ON_PROJECT_REPOS)`.
- **Default credentials / directory listing / debug endpoints:** none. `syncEnabled` is false unless `DAYMARK_AUTH_TOKEN` is set and `/v1` fail-closes to 503, deliberately indistinguishable from not-configured. `GET /v1/config` returns a single deliberate bit (`{smtpEnabled}`). WebAuthn stubs return a documented 501. **Accept these explicitly rather than leaving them silently present.**

### What E2EE does not buy you — must be in the UI, not only in `COMPANION_SECURITY.md`

The server hands the browser the JavaScript that holds the keys. For the **Android app**, "the server stores ciphertext it cannot read" is a strong, checkable claim — the client is signed, installed out-of-band, and updated through a channel the server does not control. For `index.html` / `therapist.html`, the same server that stores the ciphertext ships the code that holds the key. One extra line of JS on the next reload exfiltrates the passphrase-derived key. **Every header in `SecurityHeaders.kt` is powerless here by construction**: `script-src 'self'` says "only scripts from this origin," and the attacker *is* this origin. SRI does not help — the attacker writes the `integrity` attributes too.

What actually moves the needle, in order of realism:
1. **Say it plainly in the product.** The web client's integrity depends on the server; the Android app's does not. Free, and honest. The person accepting the risk is the therapy client, not the operator.
2. **Publish the bundle hash per release.** Vite is already configured deterministically (`sourcemap: false`, hashed asset names, `assetsInlineLimit: 0`). This is the Code Verify model and it is the practical floor.
3. **Track, don't adopt:** source-code transparency via Web Bundles + a transparency log (twiss explainer); Sigstore-log-based browser verification; "Trust on Reload," ACM Web Conference 2026. The honest answer has moved from "unsolved" to "unsolved, and here is who is working on it."
4. **Do NOT claim CSP/SRI solves it.**

**Metadata the server learns without decrypting anything:** every lineage id (and `GET /v1/snapshots` lists them all), every version number, exact byte size, SHA-256 and `created_at` of every snapshot — therefore journalling cadence, gaps and burst patterns; the full relationship graph; which channel each write went to; a 90-day `AuditStore` record of who opened what and when; `X-Setting-Key` routing tags in cleartext. **Mood-tracking cadence is mental-health data.** Blob sizes are **not** padded — I checked, and there is no `DAYMARK_SIZE_PADDING` in the codebase (one report asserted there is; that assertion is wrong). Size-bucketing and jitter should appear in the docs as a **known-unmitigated** item rather than be silently absent.

**Availability:** E2EE offers nothing. The operator can delete `/data`; `pruneLocked()` hard-deletes blob bytes. The architecture's answer — "the server is never the source of truth" — is correct and load-bearing, so **test it**: verify the Android client fully recovers from a server that returns 500s, serves stale data, or is wiped.

**Recovery:** `/v1/recovery/*` recovers **server access only** — no PIN or passphrase reset is possible. Given a user population that includes people in crisis who forget passphrases, the UI wording here is a safety issue as much as a security one.

### Post-deploy verification tests to add to CI

CI today exercises `127.0.0.1:8080` **directly and never exercises a proxy at all**, so *every* §3 claim is currently untested. Make the shipped compose *be* the tested configuration.

```yaml
- run: docker compose -f companion/docker-compose.yml up -d --wait
- run: ./gradlew dependencies --configuration runtimeClasspath | grep -qE "netty.*4\.2\.1[3-9]" || exit 1
- run: '! grep -rn "{@html}" companion/web/src'
# exactly ONE CSP header, containing wasm-unsafe-eval
- run: test "$(curl -skI https://localhost/ | grep -ci '^content-security-policy:')" -eq 1
- run: curl -skI https://localhost/ | grep -i '^content-security-policy:' | grep -q "wasm-unsafe-eval"
# unknown Host is refused
- run: curl -sk -o /dev/null -w '%{http_code}' -H 'Host: evil.example' https://localhost/ | grep -qv 200
# lockout isolation: 8 bad tokens from A must not lock B  (needs two source IPs)
# request smuggling probe: bare-LF chunk terminator against proxy+app pair
```
Plus a unit test that encodes the XFF security property as an assertion:
```kotlin
// Right-to-left is the security property. Left-to-right = unlimited lockout bypass.
assertEquals("5.6.7.8", ClientAddress.resolve(
    peer = "10.89.0.2",
    forwardedFor = listOf("1.2.3.4, 5.6.7.8"),   // 1.2.3.4 is attacker-supplied
    trusted = ClientAddress.parseTrusted("10.89.0.2/32")))
```

---

## 7. CHANGED-SINCE-TRAINING

Consolidated and deduplicated across all five reports. Everything a 2024/2025-trained model would get wrong here.

### Certificates & TLS
1. **OCSP stapling is dead for Let's Encrypt.** Since 2025-05-07 LE certs contain **no AIA OCSP URL** — only a CRL distribution point — and six-day certs carry neither. `ssl_stapling on; ssl_stapling_verify on; resolver ...;` in nginx is now pure cargo cult, and the `resolver` line is an extra footgun. Caddy's corresponding knob is `ocsp_stapling off`.
2. **Certificate lifetimes are collapsing, and it changes the proxy calculus.** LE moved the `tlsserver` profile to **45-day** certs on 2026-05-13 and moves the **default `classic` profile to 64-day** on 2027-02-10. Six-day "shortlived" (160 h) certs went GA 2026-01-15. Hand-rolled certbot+cron gets proportionally more fragile; ARI-implementing (RFC 9773) proxies degrade gracefully.
3. **Let's Encrypt IP-address certificates are real and GA** (2026-01-15). "You cannot get a publicly-trusted cert for an IP" is now wrong — but only for **public** IPs, so RFC1918 LAN is unchanged.
4. **ZeroSSL is no longer an unconditional Caddy fallback.** Since Caddy 2.8 it is added implicitly **only if `email` is set**, and the `zerossl` issuer module is no longer ACME — it's the ZeroSSL API and needs an API key.

### Caddy
5. **Caddy is on 2.11.x, not 2.8/2.9.** Latest stable v2.11.4. Oddity: **there is no v2.11.0** — a broken release process meant the line started at v2.11.1 (2026-02-23). Do not assume `x.y.0` exists if you script a version check.
6. **Caddy 2.11 shipped 6 CVEs' worth of patches** (CVE-2026-27588/27589/27590) and 2.11.4 patched more (GHSA-vcc4-2c75-vc9v), with maintainers warning the fixes "may be breaking if your application relies on the buggy behaviors." Anything on 2.7/2.8/2.9 is materially behind. Building Caddy from source now requires **Go 1.26**.
7. **`mholt/caddy-ratelimit` is no longer flagged WIP** (notice removed 2026-01-16; gained `ipv4_prefix`/`ipv6_prefix` subnet limiting May 2026). The 2024/2025 caveat "it's a WIP, don't use it in production" is stale. **What has NOT changed:** still third-party, still needs an xcaddy build, `go.mod` still pins caddy v2.10.0 — and rate limiting is **still not in stock Caddy 2.11**.
8. **`ip_mask 0` disables masking, it does not maximize it.** A CIDR of 0 makes `parseRawToMask` return nil and `net.IP.Mask(nil)` renders `<nil>`. Reasoning by analogy to a `/0` CIDR produces logs you believe are anonymized and are not. Use `delete`.
9. **Caddy's default header redaction writes the literal string `REDACTED`**, not empty values — Caddy's own doc comment in `internal/logmarshalers.go` is stale relative to its own implementation. And it covers **only** Cookie / Set-Cookie / Authorization / Proxy-Authorization.

### Traefik / nginx
10. **Traefik is on v3.7.x** (v3.7.10, 2026-07-31), far past v3.0/v3.1. The v3 Headers middleware **removed** `sslRedirect`, `sslTemporaryRedirect`, `sslHost`, `sslForceHost`, `featurePolicy` — a 2024-trained model hands you a headers middleware using `sslRedirect` for HTTP→HTTPS, which now simply fails (use entryPoint-level redirection). `IPWhiteList` → `IPAllowList`. `experimental.http3` removed (plain entryPoint option now). Marathon and Rancher v1 providers removed.
11. **Traefik v3 default rule syntax broke v2 habits:** `Headers`/`HeadersRegexp` → `Header`/`HeaderRegexp`; `PathPrefix` is no longer regex; `Path`/`PathPrefix` no longer accept `{placeholder}` params; regexes are Go syntax; each matcher takes one value combined with explicit `&&`/`||`. `core.defaultRuleSyntax: v2` restores the old syntax temporarily but is deprecated.
12. **Traefik shipped an *incomplete-fix* cohort in 2026** — CVE-2026-54763/54764/54765 revisited header-field **underscore-variant identity spoofing** in BasicAuth/DigestAuth/ForwardAuth after CVE-2026-33433 and CVE-2026-39858. Directly relevant: a fix you might remember as complete was not. (Relatedly, Caddy 2.11.4 now **ignores header fields with underscores** to prevent exactly this class of collision.)
13. **nginx stable is 1.30.4, mainline 1.31.3** — past 1.24/1.26/1.27. Official images moved their Debian base **bookworm → trixie**. 1.30.4 fixes CVE-2026-42533 (map-with-regex buffer overflow, worker crash / possible RCE without ASLR), CVE-2026-60005, CVE-2026-56434.
14. **Pangolin** (Traefik-based self-hosted tunneled reverse proxy with built-in identity/access control) became a credible name post-2025 and is absent from most 2024 training data.

### Docker Engine / Compose
15. **Docker Compose is on v5.x, not v2.x.** v5.0.0 "Mont Blanc" shipped 2025-12-02 and deliberately **skipped 3.0/4.0** so CLI versions could never again be confused with the legacy 2.x/3.x compose *file format*. Latest v5.4.0.
16. **Compose v5 REMOVED the internal builder** — `build:` is delegated to Bake/buildx. **buildx is now a hard requirement** for `docker compose build`, not an optimization.
17. **`pre_start` init containers are real** (Compose v5.3.0, July 2026). Each step runs in its own ephemeral container, can use a **different image**, joins the service's networks, shares its volume mounts, respects `depends_on`, and is not re-run on restart. This replaces the 2024 idiom of a separate init service + `depends_on: {condition: service_completed_successfully}`. `post_start` / `pre_stop` hooks also exist.
18. **Docker Engine is on 29.x** (29.0.0, Nov 2025; latest 29.7.2). **Docker Content Trust was REMOVED from the CLI in 29.0.0** — `export DOCKER_CONTENT_TRUST=1`, a standard 2024 hardening line, is dead advice. Use digest pinning + cosign/attestations.
19. **The `DOCKER-ISOLATION-STAGE-1/2` iptables chains were removed in 29.0.0.** Inherited firewall scripts referencing them are broken.
20. **Docker 29 added an experimental nftables backend with NO `DOCKER-USER` chain at all.** Every "add your egress rules to DOCKER-USER" recipe silently does nothing there. Docker also stops enabling IP forwarding for you under nftables, which can make the daemon fail to start after a reboot.
21. **The containerd image store is now the default** on fresh 29 installs — **except** with `userns-remap`, where it is disabled as a workaround for moby#47377. So enabling userns-remap in 2026 silently opts you out of the default image store. In 2024 it was recommended with no such caveat.
22. **`gateway_mode_ipv4: isolated` exists**, and it is the piece that makes `internal: true` actually isolating. Without it an internal network still gets a bridge address, so an "internal" container can reach host services bound to `0.0.0.0`. A 2024 model says `internal: true` is sufficient.
23. **`"icc": false` in daemon.json does NOT affect Compose-created networks** — only the default `docker0` bridge. Guides presenting it as a global ICC kill switch are wrong. (And `enable_icc: "false"` on your back network would break proxy→app.)
24. **`cap_add: NET_BIND_SERVICE` + non-root `user:` does NOT let a container bind :80/:443.** Docker grants no ambient capabilities, and `no-new-privileges:true` makes the kernel ignore the image's setcap file capability at execve. The working modern pattern is `user:` + `cap_drop: [ALL]` + `sysctls: {net.ipv4.ip_unprivileged_port_start: "0"}`.
25. **The default seccomp profile was tightened in 29.4.2/29.4.3** to block `AF_ALG` and `socketcall(2)` after a kernel-crypto-API container escape.
26. **`deploy.resources.limits` IS honored by plain `docker compose up`** outside Swarm — that's changed from the old v3-era folklore that `deploy:` is Swarm-only. It supports `cpus`, `memory` **and `pids`**.
27. **cgroup v1 is deprecated** in Docker 29 (supported to at least May 2029). Minimum API version is 1.44. Legacy container-link env vars are no longer injected.
28. **`healthcheck` gained `start_interval`** (Engine 25+). New network attributes exist a 2024 model won't emit: `gw_priority` (v2.33+), `interface_name` (v2.36+), `enable_ipv4`. New top-level `models:` and `provider:` surface exists.
29. **Docker still bypasses UFW for published ports** — flagged as explicitly **re-verified**, not assumed, because it's the kind of long-standing footgun one expects to have been fixed by 2026. It has not been.
30. **The top-level `version:` key is obsolete** and current Compose warns on it. The repo correctly omits it — do not "helpfully" add it back.

### Base images & supply chain
31. **Docker Hardened Images became FREE and Apache-2.0 open source in December 2025** — 1000+ images, near-zero CVEs, full SBOMs, SLSA Build L3 provenance, OpenVEX, signatures, no subscription. A 2024-trained model has never heard of DHI (it launched mid-2025 as a paid product). **Caveat a summary misses:** it still requires `docker login dhi.io`.
32. **The canonical distroless Java tag is `java21-debian13`, not `java21-debian12`.** Distroless rebased on Debian 13 (trixie). `java25-debian13` also exists now.
33. **The "gcr.io is shutting down" scare does NOT apply to distroless.** Distroless moved its serving infra to Artifact Registry but **kept the gcr.io domain** — references need no migration.
34. **Java 25 became an LTS in September 2025.** Java 21 is no longer "the newest LTS." Oracle's free JDK 21 updates end September 2026 (OTN licence after), but **Temurin/Corretto/Zulu are unaffected and patch 21 through at least September 2028** — so staying on 21 is fine; assuming it is *current* is not.
35. **Eclipse Temurin's DEFAULT Ubuntu base moved to Ubuntu 26.04 "resolute"** as of the May 2026 release. Untagged tags like `eclipse-temurin:21-jre` now point at resolute, **not** jammy or noble. Treating `-jammy` as "the" temurin variant is two LTS releases behind. (Also: Noble dropped the `adduser` package that `useradd`-based stages assume.)
36. **Node 22 is Maintenance LTS** (EOL 2027-04-30), not the safe default. Node 24 is Active LTS (EOL 2028-04-30); Node 26 shipped 2026-05-05 and enters LTS October 2026.
37. **Gradle is on 9.x — 9.7.0 (2026-08-06/07).** Gradle 9 requires JVM 17+ for the daemon and Kotlin Gradle Plugin ≥ 2.0.0, and embeds Kotlin 2.2.x. The wrapper's version-resolution scheme changed (major-only/minor-only specifiers like `9` or `9.1` resolve to the latest match).
38. **The Shadow plugin situation resolved:** `com.github.johnrengelman.shadow` is dead; `com.gradleup.shadow` is the maintained fork (this repo already moved); the **9.x line was rewritten in Kotlin and requires Gradle ≥ 9.2.0**. Latest 9.6.1 (2026-07-22). The 8.3.x line is also Gradle 9 compatible.
39. **Go is at 1.26.5** (Aug 2026). A 2024 model suggests `golang:1.22/1.23`.
40. **pnpm 11 turns `minimumReleaseAge` ON BY DEFAULT at 1440 minutes** — the single biggest change to npm supply-chain defaults since 2024. Pinning pnpm 10.x with otherwise-exemplary rigour now means **opting out** of the strongest available protection. Yarn 4.10 (`npmMinimalAgeGate`) and npm v11.10 shipped equivalents. "Pin your package manager exactly" is now incomplete advice without a version floor.
41. **BuildKit/buildx attach default provenance attestations automatically** (buildx ≥0.10 / BuildKit ≥0.11) unless disabled — you may already be shipping provenance. SBOM is still opt-in. **Gotcha:** BuildKit does **not** cryptographically sign these; a separate cosign step is required.
42. **Digest pinning is now the assumed baseline, and pinning WITHOUT an automated update path is the anti-pattern** — a pinned-and-rotting base is worse than an unpinned one. Renovate's `docker:pinDigests` can be scoped per-manager.
43. **EU Cyber Resilience Act vulnerability/incident reporting obligations start 2026-09-11** — one month from now. 24 h early warning, 72 h full notification, via ENISA's Single Reporting Platform. The formal SBOM-in-technical-documentation requirement is December 2027, but you need the SBOM **now** because component-level visibility is a prerequisite for the 24 h clock. Free OSS developed outside commercial activity is generally exempt; the exemption **narrows once a product containing it is placed on the EU market** — directly relevant to "small therapy practices" as a customer segment.
44. **Base-image guidance has shifted toward rebuild *cadence* as a first-class criterion**, not just size or CVE count: monthly/quarterly rebuild schedules leave multi-week exposure windows that daily-rebuilt hardened images close. This reframes distroless-vs-Temurin as being about update velocity as much as attack surface.

### Logging
45. **PROMTAIL IS EOL as of 2026-03-02.** The single biggest stale-knowledge trap here — "Loki + Promtail" was *the* canonical self-hosted answer through 2024 and is now simply wrong. No security patches, no bug fixes. **Grafana Alloy** (v1.18.1) is the replacement, and it is an OpenTelemetry Collector distribution, not a Promtail-shaped thing. `alloy convert` migrates configs.
46. **Alloy also superseded Grafana Agent** (Flow mode). `grafana.com/docs/agent/` is the dead product; `grafana.com/docs/alloy/` is current.
47. **The Docker `local` log driver is the better default over `json-file`, and this is under-known.** `local` = 20m × 5 with compression **ON** by default. `json-file` still defaults to `max-size: -1` (**unlimited**), and its `max-file` is **inert unless `max-size` is also set** — so setting `max-file` alone does nothing.
48. **"Loki has auth via `auth_enabled`" is a persistent and dangerous misconception.** `auth_enabled` toggles multi-tenant `X-Scope-OrgID` enforcement. **Loki has NO authentication layer of its own at any setting.** The Helm chart papers over this with a bundled nginx; a hand-rolled compose deployment does not get that.
49. **Vector is still pre-1.0** at 0.57.0.

### Application dependencies
50. **Ktor is at 3.5.2** (2026-07-31), not 3.0.x. Critically the **Netty floor moved**: Ktor 3.0.3 ships Netty **4.1.116.Final**; Ktor 3.5.x ships **4.2.13.Final**. A 2025-trained model treats Ktor 3.0.3 as "recent and fine" — it is nine months behind three HTTP-parsing CVEs.
51. **Three Netty request-parsing CVEs postdate the training baseline**, all directly relevant to "Netty behind a reverse proxy": CVE-2025-58056 (bare-LF chunk terminator smuggling; fix 4.1.125/4.2.5), CVE-2025-67735 (CRLF in request URI; fix 4.1.129/4.2.8), CVE-2026-42587 (br/zstd/snappy decompression-limit bypass; fix 4.1.133/4.2.13 — a 2026 CVE, absent from training data entirely).
52. **bcprov-jdk18on 1.79** was current-ish in the baseline and is now vulnerable: CVE-2026-0636 covers 1.74–1.83, fixed 1.84. Latest 1.85.2. Recommending 1.79 from memory is actively wrong.
53. **logback 1.5.12** was the newest release for much of the baseline window and is **exactly the top affected version** for CVE-2024-12798/CVE-2024-12801. Fix (1.5.13) landed after. Logback has since gone 1.6.x.
54. **npm ecosystem majors:** Vite 8.2.1, TypeScript **7.0.2**, Vitest 4.1.10, Svelte 5.56.8, `@sveltejs/vite-plugin-svelte` 7.3.0, `libsodium-wrappers-sumo` 0.8.4. TypeScript 7.x in particular is unpredictable from 2024.
55. **`xerial/sqlite-jdbc` publishes multiple classifier JARs since 3.53.0.0**, and its Alpine detection (via `/etc/os-release`) has a documented Alpine-with-glibc failure mode.

### Browser E2EE integrity
56. **The problem moved from "nobody has a story" to "two competing stories, neither shipped":** source-code transparency via Web Bundles + a transparency log (twiss explainer), and Sigstore-transparency-log-based browser verification. There is now peer-reviewed treatment of exactly this threat model ("Trust on Reload," ACM Web Conference 2026). The honest answer in a security doc has changed from "unsolved" to "unsolved, and here is who is working on it."

---

## 8. Where reports conflicted, and what I picked

| Conflict | Resolution |
|---|---|
| **Caddy non-root port binding.** Report 1: `user:` + `cap_drop:[ALL]` + `ip_unprivileged_port_start` sysctl; `cap_add: NET_BIND_SERVICE` + `user:` **does not work** under `no-new-privileges`. Reports 4 & 5: `user: 1000:1000` + `cap_add: [NET_BIND_SERVICE]` + `no-new-privileges`. | **Report 1.** Its kernel/Docker reasoning is sound: Docker grants no ambient caps, and `no-new-privileges` makes the kernel ignore file capabilities at execve — so reports 4/5's config would fail to bind. Report 1 flags it as unverified; I've kept the sysctl approach as primary **and written the fallback into the compose comments**. This is the one thing in the file most likely to bite — **test it before cutting DNS over**. |
| **Caddy XFF behaviour.** Report 1: "Caddy sets these from the real connection and does not forward client-supplied values." Report 5: Caddy **appends** the client IP to any existing XFF. | **Report 5.** Caddy ignores inbound XFF for *its own client-IP logic* (which is what report 1 was thinking of) but the upstream still receives the appended chain. Report 5 also found that the repo's own Caddyfile comment repeats report 1's error. Resolved *better than either*: added `header_up X-Forwarded-For {client_ip}` to **replace** rather than append, so the app's right-to-left walk is safe by construction, not by accident. |
| **`servers { trusted_proxies static private_ranges }`.** Report 3 sets it; report 1 says explicitly do not. | **Report 1.** Caddy *is* the edge here; setting `trusted_proxies` only makes sense with something in front. Omitted. |
| **Access log verbosity.** Report 1: full JSON access log with `ip_mask 24 48`. Report 3: `level ERROR`, faults only, IPs `delete`d. | **Report 3.** Its argument is app-specific and stronger: a full-precision or even /24-masked per-request trail is a re-identification vector in a mental-health app, and the security signal (AUTH_FAIL, LOCKOUT) already lives in the hash-chained `AuditStore` where the *data subject* can read it. Report 3's `ip_mask 0` finding also invalidates the naive "just mask harder" instinct. Report 1's masking form is preserved as the documented triage escape hatch. |
| **Log driver.** Reports 1 & 5: `json-file` with rotation options. Reports 3 & 4: `local`. | **`local`.** It rotates *and* compresses by default, and `json-file`'s `max-file` is inert without `max-size`. Same operator ergonomics (`docker compose logs` works). |
| **App UID.** Report 4 keeps `USER 10001` on distroless; reports 2 & 5 say distroless nonroot is 65532. | **65532.** Distroless has an `/etc/passwd` entry only for 65532; running the JVM as an unknown UID resolves `user.home` to `?`, which has bitten JVM apps. Adopting the base's own UID avoids testing that. Migration step provided (§4.4). |
| **Caddy image reference.** Report 3: `caddy:2.11.4@sha256:844f60b6...`; reports 1/4/5: `caddy:2.11.4-alpine@sha256:5f5c8640...`. | **`-alpine`.** Report 1 established that the two are the *same Linux image* (identical linux/amd64 child digest); the unsuffixed tag additionally carries Windows manifests, which is why its list digest differs. The `-alpine` list is narrower. Report 5's `caddy:2-alpine` floating major is rejected outright. |
| **Caddy 2.11.4 release date.** Report 1: 2026-06-03 (index), noting a secondary fetch returned a clearly-wrong "2024". Report 3: 2026-07-16. | **Unresolved; immaterial.** All reports agree 2.11.4 is the current stable. Confirm on the release page if the date matters. |
| **Gradle stage.** Report 2: bump to `gradle:9-jdk21`, but ship as a separate coupled PR. Report 5: switch to `./gradlew` on a temurin JDK stage to fix the wrapper bypass. | **Both, split.** Take report 5's `./gradlew` fix **now** (it's the actual security issue — the CI-validated wrapper never touched the shipped artifact) but run it inside the *already-pinned* `gradle:8.11.1-jdk21` image, avoiding an unverified temurin-jdk digest. Defer report 2's Gradle 9 + Shadow 9 jump to its own PR — a broken fat jar and a broken base image are much easier to debug apart. |
| **Go builder for the healthcheck.** Report 2: vendored `main.go`, `golang:1.26-alpine`, stdlib only. Report 4: heredoc `main.go`, `golang:1.25-alpine`. Report 5: `go install github.com/cryptalia/httpget@latest`. | **Report 2.** Report 5's approach fetches a third-party module from the network at build time — it violates the project's own no-third-party-origins rule and introduces an unaudited dependency into the trusted image. Report 4's heredoc is unmaintainable. A real file in `healthcheck/main.go` is reviewable, diffable, and needs no network. |
| **Proxy body cap.** Report 1: 28MB, above the app's 26 MiB, so the app returns its own semantic 413. Report 5: match exactly (26m at nginx). | **Report 1.** Semantic errors from the layer that understands them beat opaque proxy truncation. Report 5's *underlying* finding still stands and is fixed: the old nginx `32m` vs app 26 MiB mismatch meant nginx buffered 6 MiB the app would reject. |
| **`DAYMARK_SIZE_PADDING`.** Report 3 asserts the project "already pads blobs via `DAYMARK_SIZE_PADDING`." | **Report 3 is wrong.** I grepped the whole server source: no such variable, and no padding logic. Blob sizes are **not** padded. Corrected in §6 — size-bucketing is a known-unmitigated item, not an existing control. |
| **`DAYMARK_ACCESS_LOG_SOURCE_IP` / `_RETENTION_DAYS` wiring.** Report 3 listed as unverified. | **Resolved: both ARE consumed** — `Config.kt:129` and `Config.kt:130`. Set them in compose with confidence. |
| **Reverse-proxy examples.** All reports assume the three example configs coexist. | **Superseded 2026-08-09.** The original resolution — keep only the Caddyfile, move the others to `docs/alternatives/` — assumed one of them was the shipped config. None is now. All four (`Caddyfile`, `Caddyfile.lan`, `nginx.conf`, `traefik.md`) live in `docs/alternatives/` as equal, untested references, and the inconsistency the report found is answered by §3.1 requirement 6 stating the rule instead. |

---

## 9. Confidence and unverified

### 9.1 Re-verify before merge — all digests

Official images are rebuilt whenever the base is patched; the digest changes even though the version does not. **Every digest in this spec was resolved on 2026-08-08 and must be re-resolved.** A pinned digest that is 8 months stale is worse than a pinned tag — put a calendar reminder on it (quarterly, or on any distroless/node/gradle security release). The Caddy digest is no longer among them — nothing in this repo pulls that image.

```sh
curl -sSI -H 'Accept: application/vnd.oci.image.index.v1+json' \
  https://gcr.io/v2/distroless/java21-debian13/manifests/nonroot | grep -i docker-content-digest
TOK=$(curl -s "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/node:pull" | jq -r .token)
curl -sSI -H "Authorization: Bearer $TOK" \
  -H 'Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json' \
  https://registry-1.docker.io/v2/library/node/manifests/24-alpine | grep -i docker-content-digest
```
Verify the distroless base once per digest bump:
```sh
cosign verify gcr.io/distroless/java21-debian13:nonroot@sha256:e7c40c23... \
  --certificate-oidc-issuer https://accounts.google.com \
  --certificate-identity keyless@distroless.iam.gserviceaccount.com
```
Pin **index (multi-arch) digests**, never per-arch manifest digests — the latter breaks arm64.

### 9.2 Must be tested on first deploy (in this order)

CI now covers items that used to live here: `docker compose config` on both files, both topologies
booted to `healthy`, the published loopback port serving `/healthz`, egress failing from the network,
a container resolving `daymark-companion` by name on the no-egress network, and the `!reset` really
removing the host binding. What remains is what only a real deployment can answer — everything that
depends on the operator's proxy.

1. **Exactly ONE `Content-Security-Policy` header, containing `wasm-unsafe-eval`.**
   `curl -skI https://your.host/ | grep -ci '^content-security-policy:'` must return `1`.
   If two appear, your proxy is adding its own — turn that off for this route (§3.1 requirement 7).
   Two CSPs intersect, and the intersection kills in-browser decryption.
2. **The app sees your proxy's address, and honours its `X-Forwarded-For`.** With
   `DAYMARK_TRUSTED_PROXIES` unset, send one request through the proxy and read the warning:
   `docker compose logs companion | grep X-Forwarded-For` names the exact address to configure. Set
   it, restart, and confirm the warning stops.
3. **A request with a forged `X-Forwarded-For` from outside does not change the app's view of the
   client.** Send `X-Forwarded-For: 1.2.3.4` through the proxy and confirm lockout accounting still
   tracks your real address — this is the difference between a DoS and an unlimited-attempts bypass.
4. **An unknown `Host` is refused** (§3.1 requirement 5), and **HSTS is present** on a real response.
5. **Fresh `docker compose up` succeeds without a manual chown** (image-side `/data` ownership at 65532).
6. **A `/recover` and `/portal/invite` URL actually resolve** — see §9.3 item 3.

### 9.3 Confidence per major decision

| Decision | Confidence | What could not be checked |
|---|---|---|
| **Caddy over Traefik/nginx** | ~~High~~ **moot as a shipping decision** — no proxy is bundled. The comparison stands as guidance for an operator choosing one. | Nothing material. |
| **Caddy 2.11.4-alpine + digest** | **High** on the version, **medium** on the digest (rebuilt frequently). | The exact release date (2026-06-03 vs 2026-07-16) conflicts between reports. |
| **Caddyfile syntax** | **Medium-high.** Every directive, subdirective and field path was verified against Caddy's own parser source (`filterencoder.go`, `filters.go`, `builtins.go`, `server.go`), but **no report was able to run `caddy validate`** — no Docker daemon in any research environment. | Whether `?`-prefixed deferred headers behave as documented on reverse-proxied responses **in this exact version** — no test was found or run. `resp_headers>Set-Cookie delete` is a by-convention field path, not source-verified. |
| **`header_up X-Forwarded-For {client_ip}` replaces rather than appends** | **Medium.** `header_up` with a single value is documented as set-semantics, and `{client_ip}` is a real placeholder. But the interaction ordering with Caddy's *automatic* XFF appending was not tested. | **Test:** log the received XFF at the app and send `X-Forwarded-For: 1.2.3.4` from outside. If the chain still appears, rely on the app's right-to-left walk (which is correct) and add the regression test. |
| **Distroless java21-debian13:nonroot** | **High.** Image config blob read directly: `JAVA_VERSION=21.0.12`, `User=65532`, `Entrypoint=["/usr/bin/java","-jar"]`, 33 layers / ~63.5 MB. | Whether it ships a full JDK or a reduced/jlinked runtime (the distroless BUILD file didn't expose `temurin-21-jre` vs `-jdk`). Only matters for the rejected JVM-native healthcheck option. Also: whether the bundled JDK patch level is current — distroless has historically lagged upstream CPUs. Check with `docker run --rm --entrypoint=/usr/bin/java <digest> -version`. |
| **Shell-form HEALTHCHECK breaks on distroless** | **High.** Docker's shell-form wrapping in `/bin/sh -c` is documented behaviour; distroless demonstrably has no shell. This is the highest-confidence correction in the spec. | — |
| **`user:` + `ip_unprivileged_port_start` sysctl for Caddy** | **Moot.** Nothing in this repo binds a privileged port any more, so the least-verified line in the original spec is simply gone rather than resolved. Kept as a warning to anyone reintroducing a proxy container as non-root under `no-new-privileges`. | Never tested; never will be here. |
| **`internal: true` + `gateway_mode_ipv4: isolated`** | **High, and now empirically tested** — CI boots the no-egress override, proves name resolution across it and egress failure from it, and asserts no host port survives. What the original entry got *wrong* was assuming published ports would still work on such a network; they do not ([moby/moby#36174](https://github.com/moby/moby/issues/36174)), which is the whole reason there are two topologies. | Nothing outstanding on this row. The weaker claim is the *default* topology's `enable_ip_masquerade: false`: CI proves egress does not complete, not that packets never leave. §2.1 gives the `DOCKER-USER` rule that closes it. |
| **`local` log driver defaults** | **High** — read from docker/docs source (20m × 5, compression on; `json-file` `max-size: -1`). | — |
| **Caddy `level ERROR` = 5xx only** | **High** — verified in `modules/caddyhttp/server.go:logRequest` (InfoLevel, escalating to ErrorLevel at status ≥ 500). | — |
| **`ip_mask 0` disables masking** | **High** — verified in `IPMaskFilter.Provision` / `parseRawToMask`. | — |
| **Netty CVEs are reachable** | **High for two, medium for one.** CVE-2025-58056 and CVE-2025-67735 are in the core decoder path and reachable regardless. **CVE-2026-42587 depends on whether Ktor's Netty engine enables `HttpContentDecompressor` for *inbound* bodies** — unconfirmed. If it doesn't, that one drops to informational; the fix is free either way. | Also unconfirmed: real-world exploitability of the specific nginx-1.30.x + Netty-4.1.116 pairing for CVE-2025-58056 — that's a live-test question, not a docs question. |
| **Ktor 3.0.3 → 3.5.2 bump** | **Medium.** The Netty pins were read directly from published POMs (3.0.3 → 4.1.116.Final; 3.5.0 → 4.2.13.Final), so the *fix* is certain. The *upgrade risk* is not: this crosses the Netty 4.1 → 4.2 minor boundary, and no one checked the 4.1→4.2 migration notes for behavioural changes affecting Ktor's Netty engine. | Run the full test suite plus the CI docker smoke test; do not blind-bump. |
| **sqlite-jdbc 3.47.1.0 → 3.53.2.1** | **Medium.** A transitive advisory **CVE-2026-24400** surfaced attached to several sqlite-jdbc versions and could not be retrieved (mvnrepository and central.sonatype.com were egress-blocked). **Check NVD or the xerial GitHub advisories directly before treating this bump as complete.** | — |
| **pnpm 10.33.0 → 11.20.0** | **Low-medium.** The `minimumReleaseAge` default is well-attested and the security argument is strong, but **lockfile-format compatibility across the pnpm 10→11 major was not verified**, and `--frozen-lockfile` will hard-fail if the format changed. Also unverified: whether pnpm 11 works cleanly on `node:24-alpine`. Kept at 10.33.0 in the shipped Dockerfile; bump as its own gated change. | — |
| **Logging policy (never-log list)** | **High** on the app-specific items — every field named was traced to a real route, header, or store in this repo. | The Svelte/Vite bundle was **not** audited for client-side logging or beacon behaviour. Worth a separate pass given the no-third-party-origins rule. |
| **HIPAA 6-year retention applicability** | **Low — genuinely contested.** Sources disagree on whether "documentation required by this subpart" reaches system-generated audit logs or only the policies describing them. **Flagged, not resolved.** A practice needs its own counsel; an individual self-hoster is not a covered entity and none of it applies. | — |
| **Audit checklist ordering** | **Medium-high.** Ordering is a judgment call, but items 1–4 are all *verified-vulnerable pinned dependencies or a shipped default*, not theoretical, which anchors the top of the list firmly. | — |

### 9.4 Code paths not read — do not assert negatives about these

- **`RelationStore`, `AuthStore`, `AuditStore` internals.** Only their call sites were read. `BlobStore`'s prepared-statement and `requireValidName()` discipline is confirmed clean; whether the other three match it is **unverified**. SQL-injection and path-traversal negatives should not be asserted for code nobody read.
- **Whether the four stores open separate SQLite files or share one.** Four WAL writers on one file would reintroduce the lock contention `BlobStore`'s single-connection + `synchronized` design carefully avoids. Also: **no `busy_timeout` pragma is set anywhere**, and `pruneLocked()` runs N deletes plus file unlinks inside the write lock on every PUT.
- **`MailContentGuard.kt`.** Clearly exists to keep record content out of outbound mail, but was not read — so it is **unconfirmed** whether it also constrains the *link*, which is the field a spoofed Host header poisons.
- **Whether Ktor's `staticFiles(remotePath, dir) { default("index.html") }` serves an SPA fallback for arbitrary unmatched paths, or only as a directory index.** This is load-bearing: invite links are `{base}/portal/invite#...` and recovery links are `{base}/recover#t=...`, and `App.svelte` reads `window.location.hash` expecting to be loaded at those paths. If `default()` is directory-index-only, **both emailed flows 404 and are dead.** No test covers it. Security-relevant because the natural fix — a catch-all route — is exactly how normalisation-mismatch bugs get introduced.
- **Whether the therapist portal SPA actually sends `X-CSRF-Token` on every state-changing call.** The **server** side is verified correct (missing header = reject, in both `RelationRoutes.resolveRole` and the logout route). A client-side omission would surface as a functional break, not a hole — but confirm before hardening further.
- **Whether the Ktor server writes anything outside `/data` and `/tmp` under a read-only rootfs**, and whether SQLite's WAL/journal files land under `DAYMARK_DATA_DIR`. Verify before switching runtimes.
- **Whether `Permissions-Policy` should include `publickey-credentials-get=(self)`.** The current policy omits the directive entirely (so it defaults to allow, and WebAuthn works). Copying it verbatim to the proxy changes nothing — but it's worth a look independently.
- **`AuthGuard.evictIfLarge()`** caps its maps at 50k entries by dropping *inactive* entries. Under a many-source flood where most entries are actively locked, the maps can still exceed the cap. Bounded-ish; worth a load test.
- **Whether Docker 29.4.2's "default AppArmor profile now requires explicit daemon reload"** affects fresh installs or only in-place upgrades. Verify `docker-default` is actually loaded (`aa-status | grep docker-default`) before relying on the `apparmor=` `security_opt`.
- **Exact CVE identifiers for Docker 29.4.2–29.7.x.** `docs.docker.com` was egress-blocked; the IDs came from a third-party GitHub mirror. The *shape* of the finding (29.3.1 is materially behind on security fixes) is solid; re-check the exact IDs before citing them anywhere.