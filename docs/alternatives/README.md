# Reverse-proxy examples

**Nothing in this directory is shipped, started, or tested.** The Companion deployment
(`companion/docker-compose.yml`) runs the application and nothing else; whichever proxy fronts it is
the operator's, and these are references for writing its config.

Read [COMPANION_DEPLOYMENT_HARDENING.md §3](../COMPANION_DEPLOYMENT_HARDENING.md#3-your-reverse-proxy--the-contract)
first. It states the eight requirements the proxy has to meet. These files are illustrations of
those requirements, not substitutes for them — where a file and the contract disagree, the contract
is right.

| File | What it is | Known gaps |
|---|---|---|
| `Caddyfile` | Public deployment with Let's Encrypt. The most complete of the four: catch-all Host refusal, HSTS, access-log redaction filter, body cap and timeouts, with the reasoning inline. | Written for the container-on-a-shared-network topology (`docker-compose.no-egress.yml`). For a proxy on the Docker host, change the upstream to `127.0.0.1:8080`. |
| `Caddyfile.lan` | LAN with no public DNS, using Caddy's internal CA. | You must install Caddy's root on every device. WebAuthn needs a real registrable domain, so passkeys will not work here — TOTP only. |
| `nginx.conf` | nginx equivalent. | **No catch-all `default_server`** — add one, or requirement 5 (refuse unknown `Host`) is unmet and the invite-link poisoning path is reachable. `add_header` also has no set-if-absent form, so be careful not to emit a second CSP (requirement 7). |
| `traefik.md` | Label-driven Traefik notes. | Traefik's Docker provider wants `/var/run/docker.sock`. This deployment mounts no socket anywhere; adding one puts root-equivalent host access next to a container whose whole premise is that the server is untrusted. |

They disagree with each other on **sub-path prefix stripping** — `nginx.conf` says do not strip, the
Caddyfile uses `handle_path`, which does. That is not an oversight to be fixed by editing one of
them: it is why requirement 6 states the rule (`DAYMARK_BASE_PATH` must agree with whatever your
proxy does) rather than leaving it implicit in an example.

**Cosmos Cloud** has no file here because it is configured through its own UI. See
[COMPANION_DEPLOYMENT_HARDENING.md §3.2](../COMPANION_DEPLOYMENT_HARDENING.md#32-worked-examples)
for the two commands and the three settings it needs.

## History

`Caddyfile` and `Caddyfile.lan` used to live in `companion/reverse-proxy/` and were started by the
compose file behind a `bundled-proxy` profile. That proxy was removed on 2026-08-09 — the audience
for a self-hosted therapy tool already runs one, and bundling a second meant shipping ACME, a
certificate volume, a privileged-port binding and a sysctl workaround nobody had ever run. The
reasoning is in §2.0 of the hardening doc. The files moved here rather than being deleted because
the research behind them (verified against primary sources, including several corrections to
widely-repeated wrong advice) is still the best guidance available for an operator choosing a proxy.
