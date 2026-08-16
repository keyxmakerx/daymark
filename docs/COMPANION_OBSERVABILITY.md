# Daymark Companion — Observability, Hardening and Operations

**Audience: the person running the server.** Not the owner, not the clinician. This document
describes what a deployed Companion tells you about itself, what it deliberately refuses to tell
you, and what you have to do to keep it configured correctly.

Every claim here was checked against the tree at the time of writing, and every claim cites the
file that makes it true. Where a claim is about something the code does **not** do, the citation is
the file where you can confirm the absence. If you change the code, this document is wrong until
you change it too.

Two framing facts that decide everything below:

1. **This server is a zero-knowledge relay for mental-health records.** Every blob it holds is
   ciphertext it cannot read. The entire value of operating one is that operating it grants no
   access to anyone's records. A log line is the usual way that guarantee dies — not through the
   cipher, but through a debug statement that seemed harmless, because a log record reaches you,
   the container runtime, journald, and every downstream shipper, forever, with none of the access
   control the data itself has.
2. **The rule this deployment holds to:** a log line, a metric, or an alert may say that the system
   is under load or under attack, and **nothing about any individual's mental health.** Not what
   they recorded, not when in a way that profiles them, not who they are seeing, not what a
   clinician sent them.

There are **no location features of any kind** in this server, and none may be added — no geo-IP,
no country lookup, no map, no "enrich the source address for security". That is an absolute product
constraint, not a default.

---

## Contents

- [0. The honest surface — what this build actually exposes](#0-the-honest-surface--what-this-build-actually-exposes)
- [1. The reverse proxy, and `DAYMARK_TRUSTED_PROXIES`](#1-the-reverse-proxy-and-daymark_trusted_proxies)
- [2. What the logs contain, and what they cannot](#2-what-the-logs-contain-and-what-they-cannot)
- [3. SMTP — the one deliberate outbound connection](#3-smtp--the-one-deliberate-outbound-connection)
- [4. The audit chain's real guarantee](#4-the-audit-chains-real-guarantee)
- [5. Known-bad, stated not buried](#5-known-bad-stated-not-buried)
- [6. Operator runbook](#6-operator-runbook)
- [7. Gaps nobody has filed](#7-gaps-nobody-has-filed)

---

## 0. The honest surface — what this build actually exposes

| Surface | Exists? | Where |
|---|---|---|
| Liveness probe `GET /healthz` | yes, unauthenticated, `{"ok":true}` always | `Application.kt:182` |
| Readiness probe `GET /readyz` | yes, unauthenticated, 200/503, content-free body | `Application.kt:191`, `Readiness.kt` |
| Capability probe `GET /v1/config` | yes, unauthenticated, `{"smtpEnabled":bool}` and nothing else | `Application.kt:207` |
| Owner-readable audit log `GET /v1/rel/{relRef}/audit` | yes, requires inbox token **and** owner bearer token | `AuditRoutes.kt:40` |
| **Metrics endpoint** | **no.** No `/metrics`, no Micrometer, no Prometheus, no counters route | absent from `build.gradle.kts` and every route file |
| **HTTP access log** | **no.** Ktor's `CallLogging` plugin is not installed | `Application.kt:123-132` installs `ProxyMisconfigWarning`, `ContentNegotiation`, `SecurityHeaders` and `StatusPages` — and nothing else |
| **Structured / JSON log** | **no.** `logback.xml` is a plain-text `ConsoleAppender` | `src/main/resources/logback.xml` |
| **Request IDs / correlation** | **no.** Nothing generates or propagates one | no `CallId`, no MDC anywhere in `src/main` |
| **Chain-verification route** | **no.** The server appends a hash chain and never checks one | `AuditStore.kt` has `append` and `list`, no verify |
| **Administrator identity** | **no.** No `DAYMARK_ADMIN_*` setting exists and no route authenticates one | `Config.kt` |

That table is the whole of it. The web admin console at `companion/web/src/lib/admin/health.ts`
reaches the same conclusion independently and renders the missing counters as first-class "this
build exposes no counter for X" statements rather than substituting something adjacent. Read that
file if you want the same audit from the front end's point of view.

**The consequence, stated plainly:** your only two operational signals are the process log and the
two probe endpoints. Everything in §6 is built out of those, because there is nothing else.

---

## 1. The reverse proxy, and `DAYMARK_TRUSTED_PROXIES`

### 1.1 What is riding on this one setting

`ClientAddress.kt` is correct and does not need changing. What needs explaining is why it matters,
because the failure mode is silent.

Every per-source control in this server keys on `call.clientAddress()`:

| Control | Default budget | Keyed on | Where |
|---|---|---|---|
| Sync/owner bearer rate limit | `DAYMARK_RATE_LIMIT_RPS` = 5 req/s | client address | `AuthGuard.kt:53,175` |
| Sync/owner bearer lockout | `DAYMARK_AUTH_LOCKOUT_FAILS` = 8 fails → `_SECONDS` = 900 s | client address | `AuthGuard.kt:95-103` |
| TOTP verify source budget | 20 attempts / 5 min | client address | `TherapistAuthRoutes.kt:79,152` |
| Therapist session-cookie budget | 120 requests / 60 s | client address | `RelationRoutes.kt:69-70, 406` |
| Recovery request budget | `DAYMARK_REISSUE_MAX_PER_HOUR` = 3 / hour | client address | `RecoveryRoutes.kt:93-94` |
| Audit `meta.sourceIp` (opt-in only) | n/a | client address | `TherapistAuthRoutes.kt:264-268` |

Behind a reverse proxy, the TCP peer is **the proxy, identically, for every request on the
internet**. `ClientAddress.resolve` only looks past it at `X-Forwarded-For` when the peer is inside
your configured allowlist (`ClientAddress.kt:105-121`). With no allowlist, forwarded headers are
ignored entirely and every one of those six controls collapses onto a single bucket.

The naive alternative — trust the header from anyone — is worse, and is why the default is
trust-nothing: any client could then send `X-Forwarded-For: <random>` and get a fresh bucket per
request, turning a shared-bucket denial of service into an unlimited-attempts bypass.

### 1.2 The rule the app implements

From `ClientAddress.kt:28-41`, in order:

1. **No trusted proxies configured → always the peer address.** The default, and byte-for-byte the
   behaviour this server had before the setting existed.
2. **Peer is not a trusted proxy → the peer address**, ignoring whatever headers it sent.
3. **Peer is trusted →** walk `X-Forwarded-For` **right-to-left**, skipping entries that are
   themselves trusted proxies, and take the first that is not.
4. Malformed chain, or a chain entirely of trusted proxies → fall back to the peer.

Right-to-left is the load-bearing detail. The leftmost entry is fully attacker-controlled; the
rightmost is the one your trusted proxy actually observed and appended.

### 1.3 Set, or append — and why "set from the client's value" is the bug

**Both replacing and appending are safe with this implementation. Passing the client's value
through unchanged is not.**

- **Replace** (`X-Forwarded-For: <peer address>`): the header contains exactly one hop, the real
  client. Right-to-left finds it immediately.
- **Append** (`X-Forwarded-For: <whatever the client sent>, <peer address>`): the forged prefix
  becomes noise. Right-to-left takes the rightmost entry — the one your proxy wrote — and never
  examines the forged part at all.
- **Pass through** (`proxy_set_header X-Forwarded-For $http_x_forwarded_for`, or any config that
  forwards the client's header without adding the observed address): the entire chain is
  attacker-authored. Right-to-left now takes an attacker-chosen value, and every lockout and rate
  limit above is bypassable by varying one header. **This is the configuration that must never
  ship.**

You must know which of the first two your proxy does, because it changes nothing about the app but
everything about what you should see when you test it.

#### nginx

`docs/alternatives/nginx.conf:31` uses the replace form:

```nginx
location / {
    proxy_pass         http://daymark_companion;
    proxy_http_version 1.1;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Forwarded-For   $remote_addr;   # SET — drops any client value
    proxy_set_header   X-Forwarded-Proto $scheme;
    proxy_connect_timeout 5s;
    proxy_read_timeout    60s;
}
```

`$proxy_add_x_forwarded_for` is the append form and is equally fine. `$http_x_forwarded_for` is the
pass-through form and is the bug. Note also that `docs/alternatives/nginx.conf` has **no catch-all
`default_server`**, which requirement 5 of `COMPANION_DEPLOYMENT_HARDENING.md` §3.1 requires; add
one before using it publicly.

#### Caddy

Caddy's `reverse_proxy` **appends** by default. `docs/alternatives/Caddyfile:129-138` pins the
replace form explicitly:

```caddyfile
reverse_proxy daymark-companion:8080 {
    header_up X-Forwarded-For   {client_ip}
    header_up X-Forwarded-Proto {scheme}
    header_up X-Forwarded-Host  {host}
}
```

Do **not** set Caddy's own `trusted_proxies` when Caddy is the edge — unset is correct there, and
means Caddy ignores client-supplied `X-Forwarded-*` for its own client-IP logic
(`docs/alternatives/Caddyfile:46-48`).

Do **not** let Caddy add a Content-Security-Policy. `SecurityHeaders.kt:49-60` already sends one
including `'wasm-unsafe-eval'`, and two CSP headers on one response are *intersected* by the
browser, not overridden — a "hardened" proxy CSP silently kills every decryption in the viewer.

#### Traefik

Traefik filters `X-Forwarded-*` at the entrypoint based on `forwardedHeaders.trustedIPs`
(`docs/alternatives/traefik.md:30-38`):

```yaml
entryPoints:
  websecure:
    address: ":443"
    forwardedHeaders:
      trustedIPs:
        - "10.89.0.0/24"     # your LB / Traefik itself. Never a broad range.
```

Traefik's Docker provider wants `/var/run/docker.sock`. This deployment mounts no socket anywhere,
on purpose; adding one puts root-equivalent host access next to a container whose whole premise is
that the server is untrusted.

### 1.4 What `DAYMARK_TRUSTED_PROXIES` must contain

The proxy's source address **as the app sees it**, which is often not the address you expect:

- Proxy running on the Docker host, forwarding to the published loopback port → usually the
  `docker0` gateway (commonly `172.17.0.1`), **not** the proxy's LAN address.
- Proxy running in a container attached to `daymark-companion_back` → that container's address on
  *that* network (allocated from `10.89.0.0/24`; the app itself is pinned at `10.89.0.3`, see
  `companion/docker-compose.yml:89-91,201-203`).

Read it off the running system:

```sh
docker inspect <your-proxy-container> \
  --format '{{range $n, $c := .NetworkSettings.Networks}}{{$n}} {{$c.IPAddress}}{{"\n"}}{{end}}'
```

Take the address on the `daymark-companion_back` row, not any other, and write it as a `/32`.

**Never a broad range.** `172.16.0.0/12` lets any co-resident container forge `X-Forwarded-For` and
walk straight past the auth lockout. `ClientAddress.kt` deliberately ships no such default
(`Config.kt:74-88`); a broad range can only get there by you typing it.

**Write literal addresses, not names.** `ClientAddress.parseTrusted` calls
`InetAddress.getByName` (`ClientAddress.kt:79`), so a container name or hostname *will* resolve —
once, at startup — and be pinned to whatever address it had at that moment. Docker reassigns
container addresses on recreate. A name here produces an allowlist that works today and silently
stops matching after an unrelated `docker compose up -d`, with no warning of any kind (see §1.6).
Note the asymmetry: the *hop*-parsing path refuses DNS entirely and is documented as doing so
(`ClientAddress.kt:142-156`); only the config path resolves names.

### 1.5 The four ways to get it wrong, and how each one shows up

| Mistake | What the app does | Symptom you will actually see | Detected? |
|---|---|---|---|
| **A. Unset, behind a proxy** | Every control keys on the proxy's address | One user's bad token locks out everyone for 900 s. `DAYMARK_RATE_LIMIT_RPS=5` becomes a **global** 5 req/s ceiling across all bearer traffic, so a large snapshot sync starts getting 429s for no reason. Recovery is capped at 3 attempts/hour *for the whole server*. | **Yes** — one-time WARN, see §1.6 |
| **B. Set to the wrong address** (proxy's LAN address instead of the address the app sees; a stale container IP; a name that has since moved) | `isTrusted(peer)` is false → falls back to the peer → **identical behaviour to A** | Identical to A | **NO. Silent.** See §7.1 |
| **C. Set too broadly** (`172.16.0.0/12`, a whole bridge CIDR, a CDN's published ranges) | Any host in that range is believed | Lockouts never engage. `AuthGuard`'s failure map and bucket map grow per forged address; `AuthGuard.kt:164-172` eventually logs *"AuthGuard is tracking N active sources … and a sweep freed none"*. The bearer token becomes brute-forceable at line rate. | Partially — only under enough volume to trip the sweep warning |
| **D. Proxy passes the client's `X-Forwarded-For` through** without adding the observed address | The whole chain is attacker-authored | Same as C, reachable by any client with one header, at any volume | **No** |

### 1.6 Empty allowlist with a proxy present — stated plainly

`ClientAddress.resolve` returns the peer on line 106 the moment the allowlist is empty. There is no
partial credit and no fallback heuristic. Behind a proxy that means:

> **Every client on the internet is one client, as far as this server's security controls are
> concerned.** Eight bad bearer tokens from one attacker lock out the owner and every therapist for
> fifteen minutes. Three recovery requests exhaust the hourly budget for everybody. Twenty TOTP
> attempts exhaust the five-minute window for every clinician at once. Five requests per second is
> the total capacity of the authenticated API. And the audit log's `sourceIp` field, if you enabled
> it, records the proxy — so it reads as working while being useless.

The app makes this one case loud. `Application.kt:112-119` logs a WARN at startup whenever the
allowlist is empty; and because "unset" is *also* correct for a directly-reachable deployment, the
`ProxyMisconfigWarning` plugin (`Application.kt:123`, `RequestClient.kt:54-79`) additionally logs
once per process the first time an `X-Forwarded-For` actually arrives while the allowlist is empty —
which is evidence rather than a hint:

```sh
docker compose logs companion | grep -i 'X-Forwarded-For'
```

Once per process, deliberately: the trigger is an attacker-supplied header, so an unbounded version
would be a log-flood amplifier for anyone who can send one (`RequestClient.kt:50-52`).

**This detector cannot see mistake B.** The latch is armed as `AtomicBoolean(trusted.isEmpty())`
(`RequestClient.kt:34`), so a *non-empty but wrong* allowlist arms nothing and warns never. See
§7.1.

### 1.7 How to verify — the lockout-isolation test

Because there is no access log and no counter, the one end-to-end proof available in this build is
behavioural. You need two client addresses that reach the proxy from genuinely different sources
(two machines, or a phone off Wi-Fi).

```sh
# From source A — burn the lockout (default DAYMARK_AUTH_LOCKOUT_FAILS = 8).
for i in $(seq 1 9); do
  curl -s -o /dev/null -w '%{http_code}\n' \
    -H 'Authorization: Bearer definitely-not-the-token' \
    https://daymark.example.com/v1/snapshots
done
# expect: 401 × 8, then 429

# From source B — a request that should still work.
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $REAL_TOKEN" \
  https://daymark.example.com/v1/snapshots
```

- **B answers 200** → the two sources are being distinguished. The allowlist is right.
- **B answers 429** ("temporarily locked") → they share a bucket. The allowlist is unset, wrong, or
  your proxy is not forwarding the header. **This is the failure you are testing for.**

Cost of the test: source A stays locked out for `DAYMARK_AUTH_LOCKOUT_SECONDS` (900 s by default).
`AuthGuard` keeps all of this in memory (`AuthGuard.kt:36-37`), so **restarting the container clears
every lockout and every rate-limit bucket instantly** — which is your reset, and also worth knowing
in an incident, because "restart it to unstick the lockout" also erases whatever brute force the
lockout was resisting. The TOTP and invite lockouts are different: they live in `auth.db`
(`AuthStore.kt:52-65, 37-48`) and survive a restart.

---

## 2. What the logs contain, and what they cannot

### 2.1 There is no access log, and that is the design

Nothing in this server writes a line per request. `Application.kt:123-132` installs four plugins —
`ProxyMisconfigWarning`, `ContentNegotiation`, `SecurityHeaders`, `StatusPages` — and Ktor's
`CallLogging` is not among them. So there is **no** per-request record of path, status, latency,
body size, user agent, referrer or client address anywhere in the app's output.

For a SIEM operator this is the headline: **do not build detections that assume request volume,
status-code ratios or endpoint hit counts are available from this server.** They are not. Whatever
you get, you get from your proxy, and `COMPANION_DEPLOYMENT_HARDENING.md` §5.3 asks you to redact
the client IP and delete the user agent there too.

### 2.2 The complete inventory of log statements

This is every logging call in `companion/server/src/main`, at the time of writing. If you find a
line in your logs that is not in this table, either the code has changed or it came from Netty/Ktor
(pinned at `WARN`/`INFO` in `logback.xml:9-10`).

| Level | Logger | Message | Variable content it can carry |
|---|---|---|---|
| INFO | `com.daymark.companion` | `Daymark Companion starting on {}:{} basePath={} sync={} smtp={} dataDir={}` (`Application.kt:49`) | Operator configuration only: bind address, port, base path, two booleans, data dir |
| WARN | `com.daymark.companion` | `DAYMARK_AUTH_TOKEN is not set — the /v1 sync API is DISABLED…` (`Application.kt:54`) | none |
| INFO | `com.daymark.companion` | `Outbound SMTP is ENABLED … host={} port={} tls={}` (`Application.kt:60`) | Operator configuration only. **Never the password** — `MailerConfig.toString()` redacts (`MailerConfig.kt:53-56`) and `Config.toString()` redacts the bearer token (`Config.kt:105-109`) |
| WARN | `com.daymark.companion` | `DAYMARK_TRUSTED_PROXIES is unset…` (`Application.kt:113`) | none |
| **ERROR** | `com.daymark.companion` | `unhandled error on {}` **+ full stack trace** (`Application.kt:129`) | **The resolved request URI.** On `/v1/rel/{relRef}/{channel}/{lineage}/{version}` that is a raw relRef *and* a raw lineage id. See §7.2 |
| WARN | `com.daymark.companion` | `Web directory '{}' not found…` (`Application.kt:136`) | An absolute filesystem path |
| INFO | `…companion.readiness` | `readiness restored: {} is writable` (`Readiness.kt:72`) | `DAYMARK_DATA_DIR` |
| ERROR | `…companion.readiness` | `NOT READY: {}` (`Readiness.kt:73`) | `DAYMARK_DATA_DIR` + an IOException class and message |
| WARN | `…companion.proxy` | The one-time `Received X-Forwarded-For from {} …` (`RequestClient.kt:59-67`) | **The direct peer address, twice.** Normally the proxy; if no proxy is present, an actual client address. Once per process |
| WARN | `…companion.auth` | `AuthGuard is tracking {} active sources (threshold {}) and a sweep freed none.` (`AuthGuard.kt:165`) | Two integers. No addresses |
| DEBUG | `…mail.Mailer` | `mailer disabled; dropping message (kind={})` / `sent mail (kind={})` (`Mailer.kt:33,46`) | A message-kind label (`invite`, `recovery`, `notification.<ReviewKind>`, `security.<event>`) |
| ERROR | `…mail.Mailer` | `refusing to send: content guard rejected message (kind={}): {}` (`Mailer.kt:41`) | Kind + the guard's own message, which is one of three fixed strings, a URL *scheme*, or a sentinel word from the closed list in `MailContentGuard.kt:29-32`. **Never the body, subject or recipient** |
| WARN | `…mail.Mailer` | `mail send failed (kind={}): {}` (`Mailer.kt:50`) | Kind + the exception's `javaClass.simpleName`. Deliberately not the message, not the recipient |
| WARN | `…mail.OwnerNotifier` | `owner notification failed (event={}): {}` (`OwnerNotifier.kt:29`) | A `ReviewKind` enum name + exception class name |
| WARN | `…routes.RecoveryRoutes` | `recovery email failed to send: {}` / `token-reissued receipt failed to send: {}` (`RecoveryRoutes.kt:109,137`) | Exception class name only |
| WARN | `…routes.RecoveryRoutes` | `access-token recovery was requested but no public base URL is configured…` (`RecoveryRoutes.kt:112`) | none — but see §7.6, its *presence* is an oracle |
| WARN | `…companion.audit` | `audit log append failed` **+ stack trace** (`RelationRoutes.kt:336`, `TherapistAuthRoutes.kt:39`) | The exceptions reachable here carry fixed messages and SQLite column names, not values — but it is a stack trace, and a future exception type could change that |
| WARN | `…companion.routes` | `blob store I/O error: {}` (`SyncRoutes.kt:150`) | **The `BlobStoreException` message, which is `"disk write failed: " + IOException.message` — and a `java.nio` filesystem exception's message is the file path, which contains the lineage id.** See §7.3 |

### 2.3 What can never appear, and why you should believe it

A SIEM ingesting this server's output will not find, at any level:

- request or response bodies, blob bytes, journal or entry text — nothing reads a body into a log
  statement anywhere in the table above;
- mail subjects, mail bodies, or recipient addresses — `Mailer.kt` logs a *kind* and an exception
  class name and nothing else, on every branch;
- TOTP secrets or codes, session cookies, bearer or inbox tokens, wrapped keys, passphrases — the
  bearer token is redacted even out of `Config.toString()` (`Config.kt:96-109`), and the SMTP
  password out of `MailerConfig.toString()`;
- the audit log's `actor`, `action` or `objectRef` — the audit log is a SQLite table read by the
  *owner* over an authenticated route (`AuditRoutes.kt`); it is never mirrored to stdout;
- blob sizes or per-request byte counts — length alone is an acuity signal, and nothing logs one;
- **any location field of any kind.** There is no geo-IP, no country lookup, and no enrichment hook
  where one could be added.

Two things you *will* find that are still personal data, and should be treated as such:

- **A raw peer address**, once per process, in the `RequestClient.kt:59` warning.
- **A raw relRef and lineage id**, in the two paths described in §7.2 and §7.3.

An IP is personal data and this codebase already treats it that way: the owner-readable audit log
records one only when the operator opts in with `DAYMARK_ACCESS_LOG_SOURCE_IP` (default `false`,
`Config.kt:145-146`, set explicitly to `"false"` at `docker-compose.yml:127`). Do not widen where
it goes. If you enable it, understand you have changed the retention story too — see §7.4.

### 2.4 The shipped log level hides more than you expect

`DAYMARK_LOG_LEVEL` defaults to `warn` in all three places that set it: `Dockerfile:61`,
`docker-compose.yml:101`, `.env.example:37`. `Application.applyLogLevel` (`Application.kt:93-97`)
applies it to the `com.daymark.companion` hierarchy at startup, overriding `logback.xml`.

At `warn`, these never print:

- **the startup banner** (`Application.kt:49`) — which is the *only* place the live bind address,
  base path, sync-enabled, smtp-enabled and data dir are reported. A successful boot produces no
  line at all confirming which configuration is running;
- **the SMTP-enabled line** (`Application.kt:60`) — so an operator cannot confirm from the log that
  outbound mail came up with the TLS mode they intended;
- **`readiness restored`** (`Readiness.kt:72`) — you see the failure edge (`NOT READY`, ERROR) and
  never the recovery edge. A `/readyz` outage looks permanent in the log even after it clears;
- both of `Mailer`'s DEBUG lines, so there is no positive confirmation that any message was sent.

`io.ktor` is pinned at `INFO` and `io.netty` at `WARN` by `logback.xml:9-10`, and `applyLogLevel`
does not touch them, so raising or lowering `DAYMARK_LOG_LEVEL` changes neither.

**Recommendation:** run at `info` unless log volume is an actual problem. There is no per-request
logging, so `info` costs you a handful of lines per process lifetime and buys you the boot banner
and the readiness recovery edge. `warn` is a sensible default for a chatty server; this is not one.

### 2.5 Two modules that are built but not wired

`companion/server/src/main/kotlin/com/daymark/companion/observability/` contains
`SecurityLog.kt`, `SecurityEvent.kt` and `AlertRules.kt`, with tests. They are well-built and they
are **not connected to anything**: nothing in `Application.kt` calls `SecurityLog.install`, no route
calls `SecurityLog.current().emit`, and nothing constructs an `AlertRules`. Verify with:

```sh
grep -rn 'SecurityLog\|AlertRules' companion/server/src/main --include=*.kt | grep -v /observability/
# (no output)
```

So **today there is no JSON security-event log and no operator alerting**, regardless of what those
files' documentation describes. `logback.xml` also carries no `daymark.security` logger, so even
once emission is wired the line would inherit the root appender's plain-text pattern rather than
going out as one JSON object per line.

They are worth wiring, and the design constraints they encode are the right ones — a closed event
vocabulary, no free-text detail values, source addresses pseudonymised by default, and relationship
references reduced to a per-process keyed digest so a year of archived logs cannot be joined into a
profile of one person's therapy. Until they are wired, §6's runbook is built on the log statements
in §2.2 only.

---

## 3. SMTP — the one deliberate outbound connection

### 3.1 What is configured, and the default

SMTP is **off unless `DAYMARK_SMTP_HOST` is set** (`MailerConfig.kt:31`). When off, no socket is
ever opened: `Mailer.forConfig` returns a mailer wrapping an in-memory sink with `enabled = false`,
which short-circuits every send (`Mailer.kt:72-81`, `Mailer.kt:32-35`).

| Variable | Default | Notes |
|---|---|---|
| `DAYMARK_SMTP_HOST` | unset | The **only** switch. Unset = whole feature off |
| `DAYMARK_SMTP_PORT` | `587` | 587 for STARTTLS, 465 for implicit. Validated `1..65535` (`MailerConfig.kt:45-47`) |
| `DAYMARK_SMTP_TLS` | `starttls` | `starttls` \| `implicit`/`smtps`. **`none`/`plain`/`plaintext` is refused; any unrecognised value is refused** (`MailerConfig.kt:84-100`) |
| `DAYMARK_SMTP_FROM` | unset | **Required** when enabled; startup fails without it (`MailerConfig.kt:42-44`) |
| `DAYMARK_SMTP_USER` / `_FILE` | unset | Omit for unauthenticated submission |
| `DAYMARK_SMTP_PASS` / `_FILE` | unset | Use the `_FILE` form |
| `DAYMARK_SMTP_ALLOW_INSECURE_LINKS` | `false` | Dev only — permits `http://` links in bodies |

Connect/read/write timeouts are 10 s / 15 s / 15 s and are **not operator-configurable** — they are
constructor defaults on `MailerConfig` with no environment variable behind them
(`MailerConfig.kt:22-23`).

### 3.2 TLS is mandatory, and `none` is refused at startup

`MailerConfig.parseTls` **throws** `MailerConfigException` on `none`, `plain` or `plaintext` when
SMTP is enabled, and on any value it does not recognise (`MailerConfig.kt:84-100`). That throw
happens inside `Config.fromEnv()`, which is the first statement of `main()` — so the process dies
before it binds a port and before it writes a single log line. What you will see is a JVM stack
trace on stderr and a container that never becomes healthy.

Beyond the config parse, the transport itself refuses to downgrade (`SmtpMailTransport.kt:58-88`):

- STARTTLS mode sets `mail.smtp.starttls.enable=true` **and** `.required=true`, so a server that
  will not upgrade is refused rather than silently spoken to in the clear;
- implicit mode uses the `smtps` transport with `ssl.enable=true` — TLS from the first byte;
- `ssl.checkserveridentity=true` in production, and `ssl.trust` is **not** set to `*` — the JVM
  default trust store applies. (Tests point at GreenMail with a per-test trust override; production
  never does.)
- The transport is resolved explicitly for the configured protocol and connected to the single
  configured `host:port`. The static `Transport.send(...)` overload is deliberately avoided because
  it resolves the default protocol and would fall back to plain SMTP on port 25
  (`SmtpMailTransport.kt:41-45`).

`Application.kt:56-61` calls `config.mailer.validate()` at startup when SMTP is enabled, so a
missing `From` or an out-of-range port also refuses to boot rather than failing at first send.

### 3.3 `_FILE` secrets

`Config.envOrFile(name, env)` (`Config.kt:160-190`) reads `NAME_FILE` in preference to `NAME`, and
the file form wins. It supports `DAYMARK_AUTH_TOKEN`, `DAYMARK_SMTP_USER` and `DAYMARK_SMTP_PASS`.
It does **not** apply to `DAYMARK_SMTP_HOST` or `DAYMARK_SMTP_FROM`, which are read straight from
the environment (`MailerConfig.kt:62,66`) — those are not secrets, but do not expect the `_FILE`
suffix to work on them.

```yaml
# docker-compose.override.yml
services:
  companion:
    environment:
      DAYMARK_SMTP_HOST: "mail.example.org"
      DAYMARK_SMTP_PORT: "587"
      DAYMARK_SMTP_TLS:  "starttls"
      DAYMARK_SMTP_FROM: "companion@example.org"
      DAYMARK_SMTP_USER: "companion"
      DAYMARK_SMTP_PASS_FILE: "/run/secrets/companion_smtp_pass"
    secrets:
      - companion_smtp_pass

secrets:
  companion_smtp_pass:
    file: ./secrets/smtp_pass
```

```sh
printf '%s' 'the-password' > secrets/smtp_pass
sudo chown 65532:65532 secrets/smtp_pass && chmod 400 secrets/smtp_pass
```

That `chown` is not optional. **Compose ignores the `secrets:` `uid`/`gid`/`mode` keys outside
Swarm**, so the container sees the host file's ownership; left as `root:root 0600`, UID 65532 cannot
read it. `Config.envOrFile` detects exactly this and produces a readable error naming the file and
the process user (`Config.kt:170-182`) rather than throwing an `AccessDeniedException` out of the
first statement of `main()` with no context.

### 3.4 The egress consequence — read this before enabling SMTP

The default network sets `com.docker.network.bridge.enable_ip_masquerade: "false"`
(`docker-compose.yml:200`). Packets still leave, but they carry a `10.89.0.0/24` source that nothing
upstream will route a reply to, so **outbound connections do not complete**. Enabling SMTP therefore
also means restoring an egress path — see `COMPANION_DEPLOYMENT_HARDENING.md` §2.1.

`docker-compose.no-egress.yml` is **not compatible with SMTP at all**: that network has no gateway
by design.

The honest framing: this server makes no outbound connections. SMTP is the single deliberate
exception, it is owner-configured, it goes to exactly one host and port, and it is off by default.
Everything else about the deployment — the disabled masquerade, the read-only root filesystem, the
dropped capabilities — is built on the assumption that nothing dials out. Turning SMTP on is a
deliberate widening of that, not a configuration detail.

### 3.5 What mail is sent, and to whom

Four message kinds exist and there is **no API to supply a subject or a body** — the sealed
`MailMessage` hierarchy has no free-text field (`MailMessage.kt`), and every rendered message is
re-checked by `MailContentGuard.assertClean` before it reaches a transport (`Mailer.kt:36-43`).

| Kind | Recipient | Contents | Trigger |
|---|---|---|---|
| `TherapistInvite` | An address the **owner** supplies on `POST /v1/invite` | Fixed template + the invite link + an ISO expiry + an optional operator-chosen display name | `TherapistAuthRoutes.kt:85-98` |
| `ReviewNotification` | The owner's **registered** address, and only if they opted into that `ReviewKind` | Fixed template + the portal URL. The kind does **not** change the body — no leakage of which record type | `OwnerNotifier.kt:23-30` |
| `AccessRecovery` | The **registered** address — never the requester's | Fixed template + a single-use confirm link + an ISO expiry | `RecoveryRoutes.kt:100-119` |
| `SecurityNotice(TOKEN_REISSUED)` | The registered address, **unconditionally** — not subject to the opt-in preferences, the same way a password-reset confirmation is not | Fixed template, no link at all | `RecoveryRoutes.kt:133-139` |

Both secret-bearing links put the secret in the URL **fragment** — `…/portal/invite#id=…&s=…`
(`TherapistAuthRoutes.kt:278`) and `…/recover#t=…` (`RecoveryRoutes.kt:180`) — so it is never sent
to any server in a request line, never reaches a proxy access log, and never appears in a
`Referer`. It is still plaintext in an email at rest on your mail provider; treat an invite mailbox
accordingly.

The content guard's `RECORD_SENTINELS` list (`MailContentGuard.kt:29-32`) scans the rendered
subject and body for record-like words, with the link **cut out of the haystack first** — a
deliberate fix, because scanning it meant an operator self-hosting at `mood.example.org` could not
send a single message, and random base64url tokens hit a three-letter sentinel roughly 1 in 400
(`MailContentGuard.kt:76-95`).

### 3.6 What to monitor

There is no delivery receipt and no queue. Watch for these four patterns:

```sh
docker compose logs companion | grep -E \
  'mail send failed|content guard rejected|owner notification failed|failed to send'
```

- `mail send failed (kind=…): <ExceptionClass>` — the transport failed. Recurring
  `SSLHandshakeException` means TLS or trust; `AuthenticationFailedException` means credentials;
  `ConnectException`/`SocketTimeoutException` usually means you enabled SMTP without restoring
  egress (§3.4).
- `refusing to send: content guard rejected message` — **investigate this one.** Nothing was
  delivered, and it means something in this process tried to put text into an email the guard did
  not recognise as template. That is a bug or an attack.
- Silence is not success. `Mailer` logs successful sends at DEBUG (`Mailer.kt:46`), which the
  default `warn` level discards (§2.4).

One precision point for anyone grepping: `Mailer.send` never throws — it catches and returns
`MailResult.Failed` (`Mailer.kt:44-52`). So the `runCatching { … }.onFailure { … }` wrappers in
`RecoveryRoutes.kt:107-110,136-138` fire only on a malformed URI, not on a failed delivery. The
strings `recovery email failed to send` and `token-reissued receipt failed to send` will therefore
almost never appear; the real signal is `mail send failed (kind=recovery)` from `Mailer`.

---

## 4. The audit chain's real guarantee

`AuditStore` maintains an append-only, hash-chained, metadata-only log per relationship
(`AuditStore.kt`). Each entry's `entryHash` is
`SHA-256(prevHash ‖ seq ‖ ts ‖ relRef ‖ actor ‖ action ‖ objectRef ‖ meta)`
(`AuditStore.kt:249-264`), chained from a fixed 64-zero genesis (`AuditStore.kt:203-204`).

**What it establishes:** the entries you were handed agree with one another. A *stored* entry
cannot be silently altered or reordered without breaking the hash of every entry after it.

**What it does not, and cannot, establish:**

> The chain is **server-computed, not counterparty-signed.** A server that quietly declines to
> append an event leaves a chain that verifies perfectly. A server that truncates the history to an
> earlier point leaves a shorter chain that also verifies perfectly. **Tampering with a stored entry
> is detectable. Never having stored one is not.**

This is not a caveat in fine print; it is the caveat the admin console is built around. The console
renders it as body text under every verdict — including the clean one, which is the only place it
matters — and refuses the words "verified", "valid", "intact" and "passed" in favour of "internally
consistent", precisely because the warmer words are heard as statements about the log rather than
about the arithmetic (`companion/web/src/lib/admin/health.ts`, `CHAIN_CAVEAT` and
`CHAIN_VERDICT_WORD`). `AuditStore.kt:56-65` says the same thing in the source, and
`COMPANION_SECURITY.md` §9 records it as retraction R12.

Three further limits worth an operator's attention:

1. **Nothing in the server ever verifies a chain.** `AuditStore` has `append` and `list`; there is
   no verify method and no route. Verification happens in a browser, in the admin console, over a
   run the operator pastes in from a database they already hold
   (`health.ts`, `CHAIN_NOT_ADMIN_READABLE`).
2. **You cannot read the log as operator, and should not.** The only route that serves it demands
   the relationship inbox token **and** the owner bearer token together (`AuditRoutes.kt:41-50`). A
   server administrator holds neither. That is a boundary to keep, not a gap to route around.
3. **Retention deletion breaks the anchor to genesis.** See §7.4 — this one is not documented
   anywhere else.

---

## 5. Known-bad, stated not buried

These are conditions of the deployment, not incidents. They do not clear. If you are running this
server, you have accepted both.

### 5.1 WebAuthn is a 501 stub

Every WebAuthn route answers `501 Not Implemented`
(`routes/TherapistAuthRoutes.kt:231-246`):

```kotlin
val webauthnStub: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
    call.respond(
        HttpStatusCode.NotImplemented,
        ErrorDto("webauthn attestation/assertion verification out of scope for headless verification"),
    )
}
post("/webauthn/register/begin", webauthnStub)
post("/webauthn/register/finish", webauthnStub)
post("/webauthn/assert/begin", webauthnStub)
post("/webauthn/assert/finish", webauthnStub)
```

Registration and assertion verification were never written. What *does* exist is the config
pinning: `DAYMARK_WEBAUTHN_RP_ID` and `DAYMARK_WEBAUTHN_ORIGINS` are read into `Config`
(`Config.kt:131-133`) so that an eventual implementation cannot regress to deriving the relying
party from a client-controllable `Host` header. That pinning is the entire extent of what exists.

**Consequence.** TOTP is the only second factor a therapist can enrol on this build, and TOTP is
phishable in a way a hardware passkey is not. The fresh, action-scoped step-up assertion that
sensitive actions are specified to require in `COMPANION_SECURITY.md` §5.3 cannot be obtained here,
so those actions rest on the session cookie and its CSRF token alone.

`DAYMARK_WEBAUTHN_ORIGINS` also doubles as the fallback for `DAYMARK_PUBLIC_BASE_URL`
(`Config.kt:134-135`), so it is load-bearing for outbound link construction even though passkeys do
not work — do not delete it on the grounds that WebAuthn is a stub.

### 5.2 `totp.secret_b64` stores the seed in cleartext

`AuthStore.kt:52-65` creates the table:

```sql
CREATE TABLE IF NOT EXISTS totp (
    credential_id TEXT NOT NULL PRIMARY KEY,
    rel_ref       TEXT NOT NULL,
    secret_b64    TEXT NOT NULL,
    ...
)
```

and `enrollTotp` inserts the caller-supplied `secretB64` verbatim (`AuthStore.kt:267-272`);
`getTotp` reads it back for verification (`AuthStore.kt:286-294`), which `Totp.verifyStep` needs in
order to recompute codes.

This is **structural, not an oversight**: a TOTP verifier must hold the shared secret, because a
hash cannot regenerate a code. `Totp.kt:12-18` and `Secrets.kt:15-17` both say so in as many words,
and it is the "honestly weaker" path in `COMPANION_SECURITY.md` §5.2. Everything else on this server
that can be hashed is hashed — invite secrets are Argon2id (`AuthStore.kt:134`), session ids and
inbox tokens are BLAKE2b (`Secrets.kt:69-70`), the owner bearer token is only ever compared in
constant time.

**Consequence, and this is the part to act on.** Anyone who can read `DAYMARK_DATA_DIR`, or any
backup or snapshot of it, can mint valid second-factor codes for every enrolled therapist,
indefinitely, without any of them noticing.

- Treat that volume as holding an **authenticating secret**, not only ciphertext. It needs the
  protection you would give a password file, not the protection you would give an encrypted blob
  store.
- Your backups inherit that. A restored copy of `auth.db` is as good as the original for minting
  codes. Encrypt backups at rest and restrict who can read them.
- A therapist who loses a device cannot be protected by rotating the seed alone if a leaked backup
  exists; the credential has to be deleted and re-enrolled, and the old backup remains dangerous.

Note also that `OwnerAccountStore` stores the owner bearer token in plaintext in `owner-account.db`
(`OwnerAccountStore.kt:28-34`, `56-63`), deliberately and for a documented reason — it is the same
class of secret as `DAYMARK_AUTH_TOKEN` itself, which is already plaintext in a mounted file. The
registered notification email is likewise plaintext by necessity, because the server must read it to
address a message. Same conclusion: the data directory holds secrets.

---

## 6. Operator runbook

### 6.1 Daily — about two minutes

```sh
# 1. Container state. Docker NEVER restarts a container for being unhealthy on a single host,
#    so an unhealthy container will sit there indefinitely. This is a signal for a human.
docker compose ps

# 2. Readiness, from outside the container, through your proxy.
curl -s -o /dev/null -w '%{http_code}\n' https://daymark.example.com/readyz    # expect 200

# 3. The five patterns that mean something. There is no access log, so this is the whole check.
docker compose logs --since 24h companion | grep -E \
  'NOT READY|X-Forwarded-For|AuthGuard is tracking|unhandled error|blob store I/O error|content guard rejected|mail send failed|audit log append failed'
```

### 6.2 Weekly — about ten minutes

```sh
# Disk. No route reports storage use; you have to look.
docker system df -v | grep daymark-companion_blobs
docker compose exec companion /usr/bin/java -version 2>/dev/null || true   # distroless: no shell

# Backups. Take one and verify it restores; a backup you have never restored is a hypothesis.
# See COMPANION_DEPLOYMENT.md §6.1 for the WAL-aware procedure.

# Image freshness. Every FROM is digest-pinned (Dockerfile), so nothing updates by itself —
# that is the point, and it means you have to look. See COMPANION_DEPLOYMENT_HARDENING.md §4.7.
```

Also weekly, if you have therapist relationships live: spot-check one audit run in the admin
console. Export a run from `audit.db` on the host, paste it into the console's chain panel, and read
the verdict **together with its caveat** (§4). A clean verdict means the entries agree with each
other; it is not evidence that anything is complete.

### 6.3 The alert catalogue — what each line means and what to do

| Log pattern | Means | Do |
|---|---|---|
| `NOT READY: …` | `/readyz` is failing. The data directory would not take a 4 KiB write + fsync | Check free space, the volume's ownership against UID 65532, and whether it is mounted read-only. `Readiness.kt` deliberately does **not** probe SQLite lock contention, so a wedged writer still answers 200 — a known gap, stated in `Readiness.kt:20-24` |
| `Received X-Forwarded-For from … DAYMARK_TRUSTED_PROXIES is EMPTY` | Something is proxying and the app is ignoring it. All clients share one lockout bucket | Set `DAYMARK_TRUSTED_PROXIES` to the address named in the line, as a `/32`. Restart. Re-run §1.7 |
| `DAYMARK_TRUSTED_PROXIES is unset` (at startup) | A hint, not evidence — unset is also correct for a directly-reachable deployment | If you have a proxy, fix it. If you do not, ignore it |
| `AuthGuard is tracking N active sources … a sweep freed none` | Either a wide distributed flood, or `DAYMARK_TRUSTED_PROXIES` pointing at something that varies per request (mistake C or D in §1.5) | Check the allowlist is not a broad range. Then look at your proxy's own rate limiting. Emitted at most once per second, so it will not itself become the flood |
| `unhandled error on <uri>` + stack trace | A 500. **The URI can contain a raw relRef and lineage id** | Treat the line as sensitive; do not paste it into a public issue. Redact the path before sharing. See §7.2 |
| `blob store I/O error: disk write failed: …` | The sync blob store could not write. **The message can contain a lineage id** | Free space / permissions. Redact before sharing. See §7.3. Note the relationship store's equivalent failure logs **nothing** — §7.5 |
| `refusing to send: content guard rejected message` | Something tried to put non-template text into an outbound email. Nothing was delivered | Investigate as a bug or an attack. Check recent changes to `MailTemplates` or its callers |
| `mail send failed (kind=…)` | The one deliberate egress path is broken. The owner is no longer being told about invites, reviews or token reissue | §3.6 |
| `audit log append failed` | Access is still being served but is no longer being recorded. The owner's log is now incomplete, and nothing in the chain will ever show that | Highest-priority of the storage errors. Check the data directory. The append is deliberately non-blocking (`auditSafely`) so a logging failure never fails a real request — which is correct, and is also why this is silent to everyone but you |
| `Web directory '…' not found` | Static assets will 404 | Check the `DAYMARK_WEB_DIR` mount |
| `DAYMARK_AUTH_TOKEN is not set` | The `/v1` sync API is disabled, fail-closed | Create the secret; see `docker-compose.yml:8-16` |

### 6.4 After any change — the verification checklist

Run all of this after changing the proxy, the compose file, the image digest, or `.env`.

```sh
# 1. It boots and the configuration you think is live, is live.
#    Requires DAYMARK_LOG_LEVEL=info — at the shipped `warn` this line does not print (§2.4).
docker compose logs companion | grep 'Daymark Companion starting on'

# 2. Both probes answer through the proxy, not just on loopback.
curl -s https://daymark.example.com/healthz     # {"ok":true}
curl -s https://daymark.example.com/readyz      # {"ok":true}
curl -s https://daymark.example.com/v1/config   # {"smtpEnabled":false}

# 3. Exactly ONE Content-Security-Policy header, and it contains wasm-unsafe-eval.
#    Two CSP headers are intersected by the browser and silently kill in-browser decryption.
curl -sI https://daymark.example.com/ | grep -ci '^content-security-policy:'   # must be 1
curl -sI https://daymark.example.com/ | grep -i 'wasm-unsafe-eval'             # must match

# 4. HSTS is present — the app deliberately does not send it (SecurityHeaders.kt:75-77).
curl -sI https://daymark.example.com/ | grep -i '^strict-transport-security:'

# 5. An unknown Host is refused at the edge (requirement 5 of the proxy contract).
curl -sI -H 'Host: evil.example' https://daymark.example.com/ | head -1        # not 200

# 6. The trusted-proxy warning is ABSENT.
docker compose logs companion | grep -i 'X-Forwarded-For'                      # expect nothing
#    Absence is necessary but NOT sufficient — a wrong-but-parseable address is silent (§7.1).

# 7. THE ONE THAT MATTERS: lockout isolation, from two real source addresses. See §1.7.
#    Absent this, you have not tested the trusted-proxy contract, you have only read it.
```

If you changed SMTP, additionally: confirm the process came up at all (a refused TLS mode kills it
before it logs, §3.2), send one invite to an address you control, and confirm the link's host is
your public origin and not something derived from a `Host` header.

---

## 7. Gaps nobody has filed

Specific, checkable, and not softened. Each one is something an operator would reasonably assume
this server does, and it does not.

### 7.1 The proxy-misconfiguration detector cannot see the most likely misconfiguration

`RequestClient.kt:34` arms the one-time warning latch as:

```kotlin
attributes.put(MisconfigWarningKey, AtomicBoolean(trusted.isEmpty()))
```

and `warnIfForwardedButUntrusted` returns immediately if the latch is false
(`RequestClient.kt:55-56`). `Application.kt:112-119` likewise warns only when the list is empty.

So the detector fires when the allowlist is **empty**, and is completely silent when it is
**non-empty but wrong** — the operator who understood the requirement, went looking for the address,
and picked the wrong one. That is mistake B in §1.5, and it is by far the likeliest way to get this
wrong, because the correct address is counter-intuitive (the `docker0` gateway rather than the
proxy's LAN address) and because a container address that was right yesterday changes on recreate.

The behaviour is identical to having set nothing at all — every per-client control keys on the proxy
— but the operator has a line in `.env` telling them it is handled, and neither the app nor any
document tells them otherwise.

**The check that would close it exists and is cheap:** the same latch condition, evaluated against
the resolved peer rather than the list's emptiness. If an `X-Forwarded-For` arrives from a peer that
is *not* in a *non-empty* allowlist, that is unambiguous evidence of a wrong allowlist — an
allowlist was configured, something is proxying, and the app is ignoring it. Unlike the empty case
there is no benign reading. Same once-per-process latch, same log-flood protection.

Until then, §1.7's lockout-isolation test is the **only** way to know, and this document should be
the last place an operator learns that.

### 7.2 An unhandled exception writes a raw relRef and lineage id into the process log

`Application.kt:129`:

```kotlin
exception<Throwable> { call, cause ->
    log.error("unhandled error on {}", call.request.local.uri, cause)
    call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal error"))
}
```

`call.request.local.uri` is the **resolved** URI, not the route template. On the relationship
surface that is `/v1/rel/<relRef>/<channel>/<lineage>/<version>` — the relRef *is* the relationship
identifier, and the lineage id identifies a specific share. Both are sensitive linkage. The HTTP
*response* is correctly generic; the *log* is not. It also dumps a full stack trace, whose frames
may carry parameter values.

This one **is** filed — `COMPANION_DEPLOYMENT_HARDENING.md` §5.4(a) describes it precisely (citing
its old line number, 98) and prescribes the fix: log the route template and `cause.javaClass.simpleName`,
the pattern the codebase already uses correctly in `Mailer.kt:50`. It is recorded here because it is
**still present**, and because §6.3 needs operators to know that this specific line is not safe to
paste into a bug report.

### 7.3 The only disk-full log line in the codebase can carry a lineage id

Not filed anywhere. `SyncRoutes.kt:150`:

```kotlin
if (e.kind == BlobStoreException.Kind.DISK_FULL) routeLog.warn("blob store I/O error: {}", e.message)
```

`e.message` comes from `BlobStore.kt:151`:

```kotlin
throw BlobStoreException("disk write failed: ${e.message}", BlobStoreException.Kind.DISK_FULL)
```

The wrapped `IOException` is thrown from `Files.createDirectories(dir)` or
`Files.move(tmp, target, …)` where `dir = blobsDir.resolve(lineage)` and
`target = dir.resolve("$version.blob")` (`BlobStore.kt:100-101,142`). A `java.nio.file.FileSystemException`'s
message **is the file path**. So the one log line that reports a full disk on the sync surface
prints `/data/blobs/<lineage>` into the operator's log and every downstream shipper.

`COMPANION_DEPLOYMENT_HARDENING.md` §5.2 explicitly lists `lineage` as never-log. The line predates
the rule and nobody has reconciled them.

The fix is one line: log `e.kind` and the exception class, not the message. The operator needs to
know the blob store could not write; the path adds nothing they cannot get from `df`.

### 7.4 `DAYMARK_ACCESS_LOG_RETENTION_DAYS` is not a retention guarantee, and pruning destroys the chain's anchor

Two distinct problems, both in `AuditStore`, neither documented.

**(a) Retention only runs on write.** `pruneExpiredLocked(relRef, now)` is called from exactly one
place: the end of `append` (`AuditStore.kt:128`). A relationship that goes quiet — a therapist
removed, a client who stopped, a relationship that simply had a slow month — is **never pruned**.
Its entries live past the 90-day window forever, including `meta.sourceIp` if the operator enabled
it. So the setting means "pruned lazily on the next write to the same relationship", not "retained
for 90 days". `COMPANION_SECURITY.md` §9 states the lazy behaviour in passing; nothing states the
consequence, which is that the relationships most likely to be dormant — ended ones — are exactly
the ones whose records are kept indefinitely. That is the wrong way round for a privacy control.

**(b) Pruning silently makes the chain unverifiable from genesis.** The chain starts from a fixed
genesis hash for `seq = 1` (`AuditStore.kt:180-185, 203-204`). `pruneExpiredLocked` issues a bare
`DELETE` (`AuditStore.kt:188-195`) and records nothing. After the first prune, the oldest surviving
entry's `prevHash` refers to a row that no longer exists, so no verifier can anchor the run — and,
crucially, **routine retention pruning is byte-for-byte indistinguishable from a hostile server
truncating the history.** §4's caveat says a truncated chain verifies perfectly; this design means
every chain older than the retention window *is* a truncated chain, by policy, with nothing marking
where the boundary legitimately falls.

A checkpoint row — the pruned prefix's last `seq` and `entryHash`, written before the `DELETE` —
would cost one row and would let a verifier distinguish "trimmed here, on this date, by policy" from
"starts here for reasons unknown". That is not a design change; it is the missing half of the
existing one.

### 7.5 The relationship surface reports a full disk to nobody

`SyncRoutes.failBlob` logs on `DISK_FULL` (`SyncRoutes.kt:150`). `RelationRoutes.failRel`
(`RelationRoutes.kt:509-536`) handles the identical `DISK_FULL` kind and **logs nothing at all** —
it responds `507 Insufficient Storage` and returns.

So a disk filling up because of therapist traffic produces a 507 for the clinician and **zero
operator signal**. `/readyz` may catch it separately, since it probes the same volume — but only if
the failure is space rather than, say, a per-directory limit or an ownership problem on the
relationship subtree, and only within its 5-second cache window.

The asymmetry is invisible in review because the two functions are in different files and read
identically otherwise. Note the trap when fixing it: **do not copy `SyncRoutes.kt:150`.** The
relation store's exception message is built the same way (`RelationStore.kt:218-220`) and its paths
contain the relRef *and* the channel *and* the lineage — copying the sync pattern here would leak
strictly more than §7.3 does. Log the `kind`, not the message.

### 7.6 The highest-privilege operation in the system leaves no trace anywhere

`POST /v1/recovery/confirm` (`RecoveryRoutes.kt:123-144`) rotates the owner bearer token and returns
the new one in the response body. It is unauthenticated by design — that is what a recovery path is.
After it runs:

- **No audit entry.** `AuditAction` (`AuditStore.kt:15-36`) has no value for it. This half *is*
  filed, as item 1 of `COMPANION_DEPLOYMENT_HARDENING.md` §5.6.
- **No log line.** Success logs nothing. Failure (`Gone`) logs nothing. Grep `RecoveryRoutes.kt` —
  the only three log statements are about mail.
- **No rate limit.** `allowReissueAttempt` is called on `/recovery/request` (`RecoveryRoutes.kt:94`)
  and **not** on `/recovery/confirm`. The confirm endpoint takes an unmetered, unauthenticated POST
  from anyone who can reach the server. The token is 256 bits so guessing is not the risk; the
  absence of any budget, any lockout and any log on the endpoint that changes who can reach the data
  is.

The only signal that the owner's server access was just rotated is the `SecurityNotice` email — and
only if SMTP is configured *and* an address is registered *and* delivery succeeds. On the default
deployment, where SMTP is off, **an owner token rotation is completely silent.**

A related, smaller point in the same file: `RecoveryRoutes.kt:112` logs "access-token recovery was
requested but no public base URL is configured" — and that branch is reached **only when
`requestReissue` returned non-null**, i.e. only when the presented email matched the registered one.
The route's non-enumeration guarantee holds for the *caller* (always 202), but the operator's log
distinguishes a match from a miss. It only fires when `DAYMARK_PUBLIC_BASE_URL` is unset, which
compose always sets — but the shape is worth not repeating.

### 7.7 `AuditAction.SHARE_DENIED` is declared, documented, and never appended

`AuditStore.kt:29-35`:

```kotlin
/**
 * A read of an expired or withdrawn share was refused.
 *
 * The owner's log distinguishes expiry from revocation; the therapist's 410 does not. That
 * asymmetry is the point — the owner is entitled to know what their own access control did.
 */
SHARE_DENIED("share.denied"),
```

Nothing appends it. Confirm with:

```sh
grep -rn 'SHARE_DENIED' companion/server/src --include=*.kt
# one hit: the declaration
```

`RelationRoutes.failRel` maps `RelationStoreException.Kind.GONE` to a 410 and returns
(`RelationRoutes.kt:534`) with no audit call. So the owner **cannot** see that someone tried to read
a share after it expired or after they withdrew it — which is precisely the event a person is most
likely to want proof of, and precisely what that doc comment promises them.

Worse for the promise: the store collapses both causes into one `Kind.GONE`
(`RelationStore.kt:259,265`), so even if the append were added at the route it could not
"distinguish expiry from revocation" without the store also carrying the reason out. The comment
describes a two-part design of which zero parts exist.

This is a documentation-truth failure *inside the source*, which is the worst place for one: it will
be read as a description of behaviour by the next person to touch the file.

### 7.8 There is no way to ask the server what address it thinks you are

The entire trusted-proxy contract turns on one question — "which address is this server keying my
controls on?" — and there is no endpoint that answers it. `/healthz`, `/readyz` and `/v1/config` are
the complete unauthenticated surface (`Application.kt:182-209`), and none of them echoes anything
about the caller.

That is why §1.7 has to be a destructive behavioural test that costs a 900-second lockout. An
owner-bearer-authenticated endpoint returning `{"clientAddress": "…", "trustedProxyCount": N,
"forwardedHeaderPresent": bool}` would turn the whole of §1 into a single `curl`, would be readable
only by someone who already holds the owner token, and would reveal nothing about anyone's records.
Its absence is the single largest practical obstacle to operators getting §1 right.

### 7.9 A metric or alert cannot exist because the numbers are not kept

Beyond "there is no `/metrics`": for several of the quantities an operator would first ask for, the
value **does not exist in the process at all** and would have to be added, not merely exposed.

- Failed bearer authentications: `AuthGuard` keeps a per-source count and decays it
  (`AuthGuard.kt:36,95-103`); the accessor is marked visible-for-tests and no route reads it.
- Active lockouts: same map carries a `lockedUntil`; nothing lists it, and **a lockout engaging or
  clearing is not logged**. The single most actionable security event this server has is invisible.
- Rate-limited requests: **nowhere.** `AuthGuard.allowRate` returns a decision and keeps no tally
  (`AuthGuard.kt:175-190`); `AttemptLimiter.allow` likewise (`AttemptLimiter.kt:34-48`).
- Live therapist sessions, TOTP lockouts, failed invite redemptions: durable in `auth.db` and
  queryable on the box, served by no route.

The observability modules in §2.5 would close most of this if wired — `SecurityEventType` already
has `LOCKOUT_ENGAGED`, `RATE_LIMITED` and `AUTH_FAILURE` in its vocabulary. Until then, an operator
asking "is this server under attack right now?" has one answer available to them: the
`AuthGuard is tracking N active sources` line, which fires only under a flood wide enough to defeat
a sweep.

### 7.10 `companion/README.md`'s posture section describes a build that no longer exists

`companion/README.md` states, under "Security posture", that the runtime "currently uses
`temurin-jre` + `curl` for the healthcheck" and that a distroless runtime and pinned digests are "a
documented follow-up"; and it closes with "What is **not** yet implemented (and must not be
assumed): sync, the blob store, authentication/MFA, therapist sharing, and game plans."

All of that is stale. `Dockerfile:49` is digest-pinned distroless with a static Go healthcheck
(`Dockerfile:38-42,74-75`), and sync, the blob store, TOTP auth, therapist sharing and game plans
are all present in `src/main`. An operator reading that section will conclude the deployment has no
authentication and calibrate their exposure accordingly — which is the one direction a
documentation error must never point.

---

## Related documents

- [`COMPANION_DEPLOYMENT.md`](COMPANION_DEPLOYMENT.md) — topologies, the full environment-variable
  reference, backup/restore, egress lockdown.
- [`COMPANION_DEPLOYMENT_HARDENING.md`](COMPANION_DEPLOYMENT_HARDENING.md) — §3 is the nine-point
  reverse-proxy contract this document's §1 operationalises; §5 is the logging and retention policy.
- [`COMPANION_SECURITY.md`](COMPANION_SECURITY.md) — the multi-party threat model; §9 is the audit
  posture and retraction R12.
- [`alternatives/`](alternatives/) — reference proxy configs. Not shipped, not tested, and where
  they disagree with the contract, the contract is right.
