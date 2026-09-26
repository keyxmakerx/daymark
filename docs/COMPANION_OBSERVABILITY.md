# Daymark Companion — observability and operations

**For the person running the server** — not the owner, not the clinician. What a running Companion
tells you about itself, what it deliberately will not tell you, and how to keep it configured
correctly. Installing and configuring it is [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md); the
security model is [COMPANION_SECURITY.md](COMPANION_SECURITY.md). Code is cited by file and function,
never by line number, because line numbers drift.

Two facts decide everything below:

1. **This server relays mental-health records it cannot read.** The whole value of running one is
   that running it grants no access to anyone's records. A log line is the usual way that promise
   dies: a record reaches you, the container runtime, journald and every log shipper downstream,
   forever, with none of the access control the data itself has.
2. **The rule:** a log line, a metric or an alert may say the system is under load or under attack,
   and **nothing about any individual's mental health** — not what they recorded, not when in a way
   that profiles them, not who they see, not what a clinician sent them.

There are **no location features** in this server, and none may be added: no geo-IP, no country
lookup, no "enriching" a source address. That is a product constraint, not a default.

---

## 0. What this build exposes

| Surface | Exists? | Where |
|---|---|---|
| Liveness, `GET /healthz` | Yes. Unauthenticated; always `{"ok":true}` while the process runs | `Application.module` |
| Readiness, `GET /readyz` | Yes. Unauthenticated; 200 or 503 with a content-free body; proves `/data` takes a write; cached for 5 s | `Readiness` |
| Capability, `GET /v1/config` | Yes. Unauthenticated; `{"smtpEnabled":…}` and nothing else | `Application.module` |
| Audit log, `GET /v1/rel/{relRef}/audit` | Yes. Needs the relationship's inbox token **and** the owner's bearer token | `auditRoutes` |
| Chain check, `GET /v1/relations/{relRef}/audit-chain` | Yes. Owner's bearer token; returns counts and the head hash, never entries | `auditChainRoutes`, `AuditStore.verifyChain` |
| Metrics endpoint | **No.** No `/metrics`, no counters route | — |
| HTTP access log | **No.** Ktor's `CallLogging` is not installed | `Application.module` installs `ProxyMisconfigWarning`, `ContentNegotiation`, `SecurityHeaders` and `StatusPages` only |
| Structured or JSON log | **No.** Plain text | `logback.xml` |
| Request ids | **No.** Nothing creates or passes one | — |
| Security-event log and alerts | **No.** `SecurityLog`, `SecurityEvent` and `AlertRules` in `observability/` have no callers (#190) | — |
| Administrator identity | **No.** No setting or route authenticates an administrator, and the admin page loads without a credential. By decision, an administrator signs in with an account of their own (#208); not built: #322 | `Config.kt` |

The admin console reaches the same conclusion independently: `lib/admin/health.ts` shows each missing
counter as "this build exposes no counter for X" rather than substituting something nearby.

**So your signals are the process log and the probes.** Everything in §6 is built from those.

## 1. The reverse proxy, and `DAYMARK_TRUSTED_PROXIES`

The contract — what to set and how to find the address — is
[COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §4.0. This section is what rides on it, how it goes
wrong, and how to prove it is right.

### 1.1 What is keyed on the client address

Every per-source control uses `ApplicationCall.clientAddress()`:

| Control | Budget | Keyed on | Kept in | Where |
|---|---|---|---|---|
| Bearer-token requests (sync and owner routes) | `DAYMARK_RATE_LIMIT_RPS`, 5 per second | address | memory | `AuthGuard.authorize` |
| Bearer-token lockout | `DAYMARK_AUTH_LOCKOUT_FAILS` (8) bad tokens, then `DAYMARK_AUTH_LOCKOUT_SECONDS` (900 s) | address | memory | `AuthGuard` |
| Sign-in code attempts, `POST /v1/totp/verify` | 20 per 5 minutes | address | memory | `therapistAuthRoutes` (`totpSourceLimiter`) |
| Sign-in code lockout | `DAYMARK_TOTP_LOCKOUT_FAILS` (5), then `DAYMARK_TOTP_LOCKOUT_SECONDS` (300 s) | credential | `auth.db` | `AuthStore.recordTotpFailure` |
| Pairing fetch and respond, by a link holder | 20 per 5 minutes | address | `auth.db` | `pairingRelayRoutes` (`pairSourceLimiter`) |
| Wrong secrets for one invitation | after 5, a lockout from 300 s, capped at an hour — never a burn | invitation | `auth.db` | `AuthStore` |
| Pairing status polls | a burst of 6, then one per 10 s | pairing run | memory | `pairingRelayRoutes` (`statusRunLimiter`) |
| Anonymous "I didn't expect this" reports | 30 per minute | address | memory | `therapistAuthRoutes` (`reportFloodGuard`) |
| Clinician session requests (relationship, key and ending routes) | 120 per minute | address | memory | `relationRoutes`, `therapistKeyRoutes`, `relationshipEndingRoutes` |
| Practice console requests | 120 per minute | address | memory | `orgRoutes` |
| Recovery requests, `POST /v1/recovery/request` | `DAYMARK_REISSUE_MAX_PER_HOUR`, 3 per hour | address | memory | `OwnerAccountStore.allowReissueAttempt` |
| The audit entry's `sourceIp` (only if enabled) | — | address | `audit.db` | — |

Behind a reverse proxy, the TCP peer is **the proxy, for every request on the internet**.
`ClientAddress.resolve` looks past it only when the peer is on your allowlist; with none, forwarded
headers are ignored and every control above that is keyed on the address collapses into one bucket.
Trusting the header from anyone would be worse: any client could send a fresh made-up address with
each request and never be limited.

Controls kept in memory reset when the container restarts, which is also the quickest way to clear a
lockout — and it erases whatever the lockout was resisting. Those kept in `auth.db` survive a restart.

### 1.2 The rule the app implements

`ClientAddress.resolve`, in order:

1. No trusted proxies configured → the peer's address, always.
2. The peer is not a trusted proxy → the peer's address, whatever headers it sent.
3. The peer is trusted → walk `X-Forwarded-For` **right to left**, skip entries that are themselves
   trusted proxies, and take the first that is not.
4. A malformed chain, or one made only of trusted proxies → the peer's address.

Right to left is the load-bearing detail: the leftmost entry is whatever the client wrote, the
rightmost is what your trusted proxy saw. `ClientAddressTest` covers a forged prefix, a direct
connection forging the header, and odd address spellings.

### 1.3 Replace, append, or pass through

**Replacing and appending are both safe with this rule; passing the client's value through is not.**

- **Replace** (`X-Forwarded-For: <peer>`): one hop, the real client.
- **Append** (`<whatever the client sent>, <peer>`): the forged part is never reached.
- **Pass through** (forwarding the client's header without adding what the proxy saw): the whole chain
  is the attacker's, and every lockout above can be dodged by changing one header. **This is the
  configuration that must never ship.**

Per proxy:

- **nginx.** `$remote_addr` replaces; `$proxy_add_x_forwarded_for` appends; `$http_x_forwarded_for`
  passes through — the bug. So does a `location` with no `X-Forwarded-For` line in effect: nginx
  forwards every header the client sent unless `proxy_set_header` replaces it, and a location that
  sets any `proxy_set_header` of its own inherits none from its server. The example,
  `docs/alternatives/nginx.conf`, replaces it once, for the whole server.
- **Caddy.** `reverse_proxy` appends by default; `docs/alternatives/Caddyfile` pins the replace form
  with `header_up X-Forwarded-For {client_ip}`. Do not set Caddy's own `trusted_proxies` when Caddy is
  the edge, and do not let Caddy add a CSP — the app sends one, and two are intersected.
- **Traefik.** It deletes the client's `X-Forwarded-*` unless the client's address is in the
  entrypoint's `forwardedHeaders.trustedIPs`, then appends the address it saw. When Traefik is the
  edge, leave `trustedIPs` unset (`docs/alternatives/traefik.md`). Its Docker provider wants the Docker
  socket, which this deployment otherwise never mounts: root-equivalent access to the host, next to a
  server whose premise is that it is untrusted.

### 1.4 What the allowlist must contain

The proxy's address as the app sees it, as a `/32` — how to find it is
[COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §4.0. Write addresses, not names:
`ClientAddress.parseTrusted` resolves a name once, at start, and pins whatever address it had then, so
an allowlist of names silently stops matching after Docker recreates a container. (The addresses in
`X-Forwarded-For` itself are never looked up in DNS.)

### 1.5 The four ways to get it wrong

| Mistake | What the app does | What you will see | Detected? |
|---|---|---|---|
| **A. Unset, behind a proxy** | Every control keys on the proxy | One user's bad token locks everyone out for 900 s; 5 requests per second becomes a ceiling for all bearer traffic, so a large sync hits 429s; recovery allows 3 attempts an hour for the whole server | **Yes** — a warning once per start (§1.6) |
| **B. Set to the wrong address** (the proxy's LAN address; a stale container address; a name that has since moved) | The peer is not trusted, so the same as A | The same as A | **No.** Silent (#167) |
| **C. Set too broadly** (`172.16.0.0/12`, a whole bridge, a CDN's ranges) | Anything in the range is believed | Lockouts never engage; eventually "AuthGuard is tracking N active sources … and a sweep freed none"; the bearer token can be guessed at line rate | Only under a flood |
| **D. The proxy passes the client's header through** | The whole chain is attacker-written | The same as C, for anyone, with one header | **No** |

### 1.6 An empty allowlist behind a proxy

> **Every client on the internet is one client**, as far as this server's security controls are
> concerned. Eight bad bearer tokens from one attacker lock out the owner and every clinician for
> fifteen minutes. Three recovery requests use up the hour for everybody. Twenty wrong sign-in codes
> use up five minutes for every clinician at once. And the audit log's `sourceIp`, if you enabled it,
> records the proxy — so it looks as if it works while being useless.

The app makes this case loud. `Application.module` warns at start whenever the allowlist is empty.
Because "unset" is also right for a server reached directly, the `ProxyMisconfigWarning` plugin
(`warnIfForwardedButUntrusted` in `RequestClient.kt`) also warns, once per start, the first time an
`X-Forwarded-For` actually arrives while the list is empty — which is evidence, not a hint. Once per
start on purpose: the trigger is an attacker-supplied header. It cannot see mistake B: its latch is
armed only when the list is empty.

### 1.7 How to verify — the lockout-isolation test

There is no access log and no counter, so the one end-to-end proof is behavioural. You need two
client addresses that reach the proxy from genuinely different places (two machines, or a phone off
Wi-Fi).

```sh
# From source A: use up the lockout (DAYMARK_AUTH_LOCKOUT_FAILS = 8 by default).
for i in $(seq 1 9); do
  curl -s -o /dev/null -w '%{http_code}\n' \
    -H 'Authorization: Bearer definitely-not-the-token' \
    https://daymark.example.com/v1/snapshots
done
# expect: 401 eight times, then 429

# From source B: a request that should still work.
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $REAL_TOKEN" \
  https://daymark.example.com/v1/snapshots
```

- **B gets 200** → the two sources are told apart. The allowlist is right.
- **B gets 429** → they share a bucket: the allowlist is unset or wrong, or your proxy is not sending
  the header. This is the failure the test exists to find.

Source A stays locked out for `DAYMARK_AUTH_LOCKOUT_SECONDS`; restarting the container clears it. No
endpoint tells an owner which address the server sees for them, which would replace this destructive
test with one request (#167).

## 2. What the logs contain, and what they cannot

### 2.1 There is no access log, and that is the design

Nothing writes a line per request, so there is no record of path, status, latency, size, user agent,
referrer or address anywhere in the app's output. **Do not build detections that assume request
volumes, status ratios or endpoint counts from this server** — whatever you have, your proxy has, and
[COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §10 asks it to keep little.

### 2.2 Every log statement

Every logging call in `companion/server/src/main`. A line that is not here came from Netty or Ktor, or
the code has changed.

| Level | Logger | Message | What it can carry |
|---|---|---|---|
| ERROR | `com.daymark.companion` | `Refusing to start: …` (`main`, then exit 78) | Setting names, a fixed example address, and which of a setting's fixed choices it read; never a value as the operator wrote it |
| INFO | `com.daymark.companion` | `Daymark Companion starting on {}:{} basePath={} sync={} smtp={} dataDir={}` (`main`) | Configuration only |
| INFO | `com.daymark.companion` | `Serving the {} shape, as DAYMARK_SETUP_MODE says: …`, or `… assumed because DAYMARK_SETUP_MODE is not set and DAYMARK_THERAPIST_AUTH is on` (or `off`) `… Set DAYMARK_SETUP_MODE …` (`Application.module`, once per start) | The shape and setting names; never a value as the operator wrote it |
| WARN | `com.daymark.companion` | `DAYMARK_AUTH_TOKEN is not set — the /v1 sync API is DISABLED…` (`main`) | Nothing |
| INFO | `com.daymark.companion` | `Outbound SMTP is ENABLED … host={} port={} tls={}` (`main`) | Configuration only; never the password — `MailerConfig.toString` and `Config.toString` redact |
| WARN | `com.daymark.companion` | `DAYMARK_TRUSTED_PROXIES is unset…` (`Application.module`) | Nothing |
| **ERROR** | `com.daymark.companion` | `unhandled error on {}` **with a full stack trace** (`Application.module`, `StatusPages`) | **The resolved request path** — on the relationship routes, a raw `relRef` and lineage id (#160) |
| WARN | `com.daymark.companion` | `Web directory '{}' not found…` (`Application.module`) | A filesystem path |
| INFO | `…companion.readiness` | `readiness restored: {} is writable` (`Readiness`) | `DAYMARK_DATA_DIR` |
| ERROR | `…companion.readiness` | `NOT READY: {}` (`Readiness`) | `DAYMARK_DATA_DIR` and an I/O error's class and message |
| WARN | `…companion.proxy` | `Received X-Forwarded-For from {} …` (`warnIfForwardedButUntrusted`) | **The peer's address**, once per start — normally the proxy |
| WARN | `…companion.auth` | `AuthGuard is tracking {} active sources … and a sweep freed none.` (`AuthGuard`) | Two numbers; at most once per sweep |
| DEBUG | `…mail.Mailer` | `mailer disabled; dropping message (kind={})`, `sent mail (kind={})` (`Mailer.send`) | A message kind |
| ERROR | `…mail.Mailer` | `refusing to send: content guard rejected message (kind={}): {}` (`Mailer.send`) | A kind and the guard's own fixed message; never the body, subject or recipient |
| WARN | `…mail.Mailer` | `mail send failed (kind={}): {}` (`Mailer.send`) | A kind and an exception class name |
| WARN | `…mail.OwnerNotifier` | `owner notification failed (event={}): {}` (`OwnerNotifier.notify`) | An event name and an exception class name |
| WARN | `…routes.RecoveryRoutes` | `recovery email failed to send: {}`, `token-reissued receipt failed to send: {}` (`recoveryRoutes`) | An exception class name |
| WARN | `…routes.RecoveryRoutes` | `access-token recovery was requested but no public base URL is configured…` (`recoveryRoutes`) | Nothing — but it fires only when the address matched (#160) |
| WARN | `…companion.audit` | `audit log append failed` **with a stack trace** (`auditSafely` in the relationship, auth, key, ending and pairing routes) | Fixed messages and column names today; a stack trace all the same |
| WARN | `…companion.audit` | `org audit append failed` with a stack trace (`orgRoutes`) | The same |
| WARN | `…companion.routes` | `blob store I/O error: {}` (`failBlob` in `SyncRoutes.kt`, on a full disk) | **A file path containing the lineage id** (#160) |
| WARN | `…companion.routes` | `key document store I/O error: {}` (`failKeyDocument` in `SyncRoutes.kt`, when the volume refuses a read or write of the key params or the wrapped key) | An exception class name |
| INFO, or WARN when any copy could not be removed | `…companion.housekeeping` | `relationship sweep: {} stored copies removed, {} already gone, {} could not be removed and are retried next sweep` (`Housekeeping`, at start-up and hourly; #338) | Three counts; never a `relRef`, channel, lineage, version or size |
| DEBUG | `…companion.housekeeping` | `relationship sweep: nothing had ended` | Nothing |
| WARN | `…companion.housekeeping` | `housekeeping job failed (job={}): {}` | A job name and an exception class name |
| ERROR | `…companion.housekeeping` | `housekeeping pass failed: {}` | An exception class name |

A full disk on the relationship store answers 507 and logs nothing (#161).

### 2.3 What can never appear

A log reader will not find, at any level: request or response bodies, blob bytes or journal text; mail
subjects, bodies or recipients; sign-in seeds or codes, session cookies, bearer or inbox tokens,
wrapped keys, passphrases (`Config.toString` and `MailerConfig.toString` redact their secrets); the
audit log's actors, actions or object references, which are never copied to stdout; sizes or byte
counts; and any location field.

What you will find that is still personal data: **a raw peer address**, once per start, in the proxy
warning; and **a raw `relRef` and lineage id** in the two lines marked #160. Treat those lines as
sensitive and redact them before sharing. An address is personal data, and the audit log records one
only when you turn on `DAYMARK_ACCESS_LOG_SOURCE_IP` — which also changes the retention story (§4).

### 2.4 The log level: `info`, and what `warn` hides

`DAYMARK_LOG_LEVEL` is `info` in the code, the image, the compose file and `.env.example` (#171,
#367). `applyLogLevel` applies it to the `com.daymark.companion` loggers at start. At `warn` you
would never see: the startup banner — the only place the live address, base path and flags are
reported; the SMTP-enabled line; and `readiness restored`, so a readiness outage would look
permanent in the log after it clears. The mailer's success lines are DEBUG, so neither `warn` nor
`info` shows them. A startup refusal (COMPANION_DEPLOYMENT.md §5.3) is logged before the level is
applied, so no level hides it.

`logback.xml` pins `io.ktor` at INFO and `io.netty` at WARN, and `DAYMARK_LOG_LEVEL` changes neither
(#169). There is no per-request logging, so `info` costs a handful of lines per start.

## 3. SMTP — the one deliberate outbound connection

### 3.1 Configuration

The variables are in [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §5.1. SMTP is off unless
`DAYMARK_SMTP_HOST` is set; then `Mailer.forConfig` wraps an in-memory sink and never opens a socket.
Connect, read and write timeouts are 10, 15 and 15 seconds and cannot be configured.

### 3.2 TLS is mandatory, and `none` is refused at start

`MailerConfig.parseTls` throws on `none`, `plain` or `plaintext` when SMTP is on, and on any value it
does not recognise. That happens inside `Config.fromEnv`, the first thing `main` does — so the process
dies before it binds a port or logs a line. You will see a Java stack trace and a container that never
becomes healthy. A missing `DAYMARK_SMTP_FROM` or an out-of-range port also stops the start
(`MailerConfig.validate`).

The transport refuses to downgrade (`SmtpMailTransport`): STARTTLS mode requires the upgrade rather
than falling back to the clear; implicit mode is TLS from the first byte; the server's identity is
checked against the Java trust store; and the transport is resolved explicitly for the configured
protocol, host and port, never the default that could fall back to plain SMTP on port 25.

### 3.3 Secrets

Give the password as `DAYMARK_SMTP_PASS_FILE` (`DAYMARK_SMTP_USER_FILE` also works; the host and
From address are read from the environment only). The file must be readable by UID 65532 on the host
([COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §2); if it is not, `Config.envOrFile` stops the
start with a message naming the file and the process user.

### 3.4 The egress consequence — read before enabling SMTP

On the default network outbound connections get no reply, and the no-egress override has no gateway
at all, so SMTP needs a path out: [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §8. The honest
framing: this server makes no outbound connections; SMTP is the single, owner-configured, off-by-default
exception, to one host and port. Everything else about the deployment assumes nothing dials out, so
turning SMTP on widens that deliberately.

### 3.5 What mail is sent, and to whom

Four kinds exist, and **there is no API to supply a subject or a body** — the sealed `MailMessage`
hierarchy has no free-text field, and `MailContentGuard.assertClean` re-checks every rendered message
before a transport sees it.

| Kind | Recipient | Contents | Sent by |
|---|---|---|---|
| `TherapistInvite` | An address the **owner** gives when minting an invitation | Fixed template, the invitation link, its expiry, an optional display name | `therapistAuthRoutes`, `POST /v1/invite` |
| `ReviewNotification` | The owner's **registered** address, only for the kinds they opted into. One more kind, a clinician ending their access, is decided (#216); not built: #329 | Fixed template and the console's address; the kind does not change the body, so nothing reveals what arrived | `OwnerNotifier.notify` |
| `AccessRecovery` | The **registered** address — never the requester's | Fixed template, a single-use confirmation link, its expiry | `recoveryRoutes` |
| `SecurityNotice(TOKEN_REISSUED)` | The registered address, **always** — not subject to the opt-in, as a password-reset receipt is not | Fixed template, no link | `recoveryRoutes` |

The decided design removes the emailed access recovery that `AccessRecovery` and `TOKEN_REISSUED`
serve: access comes back by proving the owner's own key, never by email (#208). Not built: #325.

**Both secret-bearing links put the secret in the URL fragment** — `…/portal/invite#id=…&s=…` and
`…/recover#t=…` — so it is never sent to a server in a request line, never reaches a proxy's access log,
and never appears in a `Referer`. It is still plaintext in an email at rest at your mail provider:
treat an invitation mailbox accordingly. `/portal/invite` redirects to `/therapist` and the browser
carries the fragment along; no route serves `/recover` yet, so the recovery link does not open a page
(#173).

The guard scans the rendered subject and body for record-like words (`MailContentGuard`'s sentinel
list) with the link cut out first, so a server hosted at, say, `mood.example.org`, or a random token
that happens to spell a sentinel, does not block mail.

### 3.6 What to monitor

There is no delivery receipt and no queue.

```sh
docker compose logs companion | grep -E \
  'mail send failed|content guard rejected|owner notification failed|failed to send'
```

- `mail send failed (kind=…): <ExceptionClass>` — the transport failed. `SSLHandshakeException` means
  TLS or trust; `AuthenticationFailedException`, the credentials; `ConnectException` or
  `SocketTimeoutException`, usually SMTP turned on without an egress path (§3.4).
- `refusing to send: content guard rejected message` — **investigate.** Nothing was sent, and
  something in this process tried to put text into an email that the guard did not recognise as
  template. A bug or an attack.
- Silence is not success: successful sends are logged at DEBUG. `Mailer.send` never throws, so the
  recovery route's own "failed to send" lines almost never appear; watch for
  `mail send failed (kind=recovery)` instead.

## 4. The audit chain's real guarantee

`AuditStore` keeps an append-only, hash-chained, metadata-only log per relationship, and another per
practice (COMPANION_SECURITY.md §9). **What it establishes:** the entries agree with one another — a
stored entry cannot be altered or reordered without breaking every later hash.

> **What it does not establish.** The chain is computed by the server and signed by nobody. A server
> that quietly never appends an event leaves a chain that checks out; so does one that cuts off the
> newest entries. **Tampering with a stored entry is detectable. Never having stored one is not.**

The admin console is built around that caveat. It shows `CHAIN_CAVEAT` under every verdict, including
the clean one, and says "internally consistent" rather than "verified", "valid" or "intact"
(`lib/admin/health.ts`). It has two ways to look: a panel that asks the server's own check
(`GET /v1/relations/{relRef}/audit-chain`, owner bearer token; `lib/admin/chainHead.ts`), and an
examiner that recomputes a run pasted in from the database, which does not take the server's word for
it. What outlives a lying server is the **head hash, written down** somewhere the server cannot reach;
the console groups it for copying by hand.

Limits worth an operator's attention:

1. **You cannot read the log as the operator, and should not.** The entries need the relationship's
   inbox token and the owner's bearer token together; an administrator holds neither.
2. **Retention runs only on write.** Entries older than `DAYMARK_ACCESS_LOG_RETENTION_DAYS` are deleted
   on the relationship's next append, so a quiet or ended relationship — and any `sourceIp` it carries
   — is kept indefinitely (#165).
3. **Pruning leaves no marker.** After a prune, the oldest surviving entry points at a row that is gone.
   `verifyChain` takes it as given rather than reporting a break, which also means routine pruning
   looks exactly like a server cutting off history (#165).

## 5. Known-bad, stated not buried

Conditions of the deployment, not incidents. If you run this server, you have accepted them.

### 5.1 Passkeys are a stub

The `/v1/webauthn/*` routes answer `501 Not Implemented` (`therapistAuthRoutes`, `webauthnStub`).
What exists is the configuration pinning: `DAYMARK_WEBAUTHN_RP_ID` and `DAYMARK_WEBAUTHN_ORIGINS` are
read into `Config` so that a later implementation cannot derive the relying party from a `Host` header
(passkey sign-in, decided in #205, is not built: #326). Consequences: the six-digit code is the only
second factor a clinician can have, and it is phishable where a hardware passkey is not. Step-up
does not wait for passkeys: adding a practice member or changing a role already needs a fresh code
that the server checks, and opening a share or publishing needs only the session, by design (#205).
`DAYMARK_WEBAUTHN_ORIGINS` is still the fallback for `DAYMARK_PUBLIC_BASE_URL`, so do not delete it on
the grounds that passkeys are a stub.

### 5.2 The data directory holds sign-in secrets

`AuthStore` keeps each clinician's sign-in seed **as-is** in `auth.db` (`totp.secret_b64`), because a
verifier must recompute codes and a hash cannot (`Totp.kt` says so). Everything else that can be hashed
is: invitation secrets with Argon2id, session ids, inbox tokens and the owner's bearer token with
BLAKE2b. The notification email is plaintext by necessity.

**Act on this.** Anyone who can read `DAYMARK_DATA_DIR`, or any backup or snapshot of it, can mint
valid codes for every enrolled clinician, indefinitely, without anyone noticing. Treat the volume and
its backups like a password file, not like an encrypted blob store. A seed cannot be replaced: if a
backup may have leaked, see COMPANION_SECURITY.md §5.2.

## 6. Operator runbook

### 6.1 Daily — about two minutes

```sh
# 1. Container state. Docker never restarts an unhealthy container on a single host;
#    "unhealthy" sits there until a person notices.
docker compose ps

# 2. Readiness from outside, through your proxy.
curl -s -o /dev/null -w '%{http_code}\n' https://daymark.example.com/readyz    # expect 200

# 3. The patterns that mean something. There is no access log, so this is the whole check.
docker compose logs --since 24h companion | grep -E \
  'NOT READY|X-Forwarded-For|AuthGuard is tracking|unhandled error|blob store I/O error|key document store I/O error|content guard rejected|mail send failed|audit append failed|audit log append failed'
```

### 6.2 Weekly — about ten minutes

```sh
# Disk. No route reports storage use; you have to look.
docker system df -v | grep daymark-companion_blobs
```

- **Backups:** take one and restore it somewhere
  ([COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §6). A backup never restored is a hypothesis.
- **Image freshness:** every `FROM` is digest-pinned, so nothing updates by itself. Look at the open
  Dependabot pull requests, and rebuild or pull (COMPANION_DEPLOYMENT.md §7).
- **The audit chain,** if clinicians are active: in the admin console, check a relationship's chain,
  write down its head, and read the verdict together with its caveat (§4). By decision the check
  belongs in the owner's own console, and the admin console never asks for the owner's token (#208).
  Not built: #322.

### 6.3 What each log pattern means

| Log pattern | Means | Do |
|---|---|---|
| `Refusing to start: …` (then the container restarts) | A setting the server will not run with; the line names it and never its value | Change the named setting (COMPANION_DEPLOYMENT.md §5.3) and start again |
| `NOT READY: …` | `/readyz` is failing: `/data` would not take a 4 KiB write and fsync | Check free space, the volume's ownership (UID 65532) and whether it is mounted read-only. Readiness does not probe SQLite lock contention, so a stuck writer still answers 200 |
| `Received X-Forwarded-For from … DAYMARK_TRUSTED_PROXIES is EMPTY` | Something is proxying and the app is ignoring it; all clients share one lockout bucket | Set `DAYMARK_TRUSTED_PROXIES` to the address in the line, as a `/32`; restart; run §1.7 |
| `DAYMARK_TRUSTED_PROXIES is unset` (at start) | A hint, not evidence: unset is right for a server reached directly | Behind a proxy, fix it; otherwise ignore it |
| `AuthGuard is tracking N active sources … a sweep freed none` | A wide flood, or an allowlist that matches something varying per request (§1.5 C or D) | Check the allowlist is not a broad range; look at your proxy's own limits |
| `unhandled error on <path>` with a stack trace | A 500. **The path can hold a raw `relRef` and lineage id** | Treat the line as sensitive; redact the path before sharing it (#160) |
| `blob store I/O error: disk write failed: …` | The snapshot store could not write. **The message can hold a lineage id** | Free space or permissions; redact before sharing. The relationship store's equivalent logs nothing (#161) |
| `key document store I/O error: …` | The key params or the wrapped key could not be read or written; the owner's devices cannot open or change their key | Free space, the volume's ownership (UID 65532), a read-only mount |
| `refusing to send: content guard rejected message` | Something tried to put non-template text into an email; nothing was sent | Investigate as a bug or an attack |
| `mail send failed (kind=…)` | The one outbound path is broken; the owner is not hearing about invitations, reviews or token re-issues | §3.6 |
| `audit log append failed`, `org audit append failed` | Access is still served but no longer recorded. The owner's log is now incomplete, and nothing in the chain will ever show it | The most urgent storage error: check the data directory. The append never blocks a request (`auditSafely`), which is right, and is why only you see this |
| `Web directory '…' not found` | The consoles will 404 | Check `DAYMARK_WEB_DIR` |
| `DAYMARK_AUTH_TOKEN is not set` | The sync API and every owner route answer 503 | Create the secret (COMPANION_DEPLOYMENT.md §5.2) |
| `relationship sweep: … N could not be removed and are retried next sweep` (WARN) | Copies of shared items that have ended are still on the volume; reads of them are refused, but the bytes are there | Check the volume's ownership (UID 65532), a read-only mount, or a stray directory in `rel/`; the sweep retries every hour |
| `housekeeping job failed` or `housekeeping pass failed` | A scheduled sweep threw; ended copies may be accumulating | Look for the storage error around it; the next pass runs within the hour |

Also watch for something that logs nothing: a token re-issue by email recovery leaves no log line and
no audit entry, so on a server without SMTP it is completely silent (#163).

### 6.4 After any change — the checklist

Run all of this after changing the proxy, the compose file, the image or `.env`.

```sh
# 1. It started, with the configuration you think it has (§2.4).
docker compose logs companion | grep 'Daymark Companion starting on'

# 2. Both probes and the capability route answer through the proxy, not just on loopback.
curl -s https://daymark.example.com/healthz     # {"ok":true}
curl -s https://daymark.example.com/readyz      # {"ok":true}
curl -s https://daymark.example.com/v1/config   # {"smtpEnabled":false}

# 3. Exactly ONE Content-Security-Policy header, and it contains wasm-unsafe-eval.
curl -sI https://daymark.example.com/ | grep -ci '^content-security-policy:'   # must be 1
curl -sI https://daymark.example.com/ | grep -i 'wasm-unsafe-eval'             # must match

# 4. HSTS is present — the proxy adds it; the app deliberately does not.
curl -sI https://daymark.example.com/ | grep -i '^strict-transport-security:'

# 5. An unknown Host is refused at the edge (requirement 5 of the proxy contract).
curl -sI -H 'Host: evil.example' https://daymark.example.com/ | head -1        # not 200

# 6. The trusted-proxy warning is absent. Necessary, not sufficient: a wrong list is silent.
docker compose logs companion | grep -i 'X-Forwarded-For'                      # expect nothing

# 7. The one that matters: lockout isolation from two real addresses (§1.7).
```

If you changed SMTP as well: confirm the process came up at all (a refused TLS mode stops it before it
logs, §3.2), send one invitation to an address you control, and check the link's host is your public
origin, not something taken from a `Host` header.
