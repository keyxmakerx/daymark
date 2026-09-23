# Reverse-proxy examples

**Nothing in this directory is shipped, started or tested.** The Companion deployment
(`companion/docker-compose.yml`) runs the application and nothing else; the proxy in front of it is the
operator's, and these files are references for writing its configuration.

Read [COMPANION_DEPLOYMENT.md §3](../COMPANION_DEPLOYMENT.md#3-your-reverse-proxy--the-contract)
first: it states the nine requirements a proxy has to meet, and §4.0 there says how to set
`DAYMARK_TRUSTED_PROXIES`. These files illustrate those requirements; where a file and the contract
disagree, the contract is right.

| File | What it is | Known gaps |
|---|---|---|
| `Caddyfile` | A public deployment with Let's Encrypt. The most complete of the four: a catch-all refusing unknown `Host`, HSTS, a redaction filter on the access log, a body cap and timeouts, with the reasoning inline. | Written for a proxy in a container (the no-egress override). For a Caddy on the Docker host, change the upstream to `127.0.0.1:8080`. |
| `Caddyfile.lan` | A LAN with no public DNS, using Caddy's internal CA. | Every device must trust Caddy's root certificate. |
| `nginx.conf` | The nginx equivalent. | **No catch-all `default_server`**, and it forwards the client's `Host` — add a catch-all and pin the host, or requirement 5 (refuse unknown `Host`) is unmet (#{O23}). Its upstream name is `companion`, where the shipped container is `daymark-companion`. `add_header` cannot set a header only if absent, so do not emit a second CSP (requirement 7). |
| `traefik.md` | Label-driven Traefik. | Traefik's Docker provider wants `/var/run/docker.sock`. This deployment mounts no socket anywhere; adding one puts root-equivalent access to the host next to a server whose whole premise is that it is untrusted. |

Serve the Companion at the root of its own hostname. The sub-path notes at the foot of `nginx.conf`
predate the finding that sub-path deployment does not work consistently (#{O10}).

**Cosmos Cloud** has no file here because it is configured through its own interface; the two
commands and the settings it needs are in
[COMPANION_DEPLOYMENT.md §3.2](../COMPANION_DEPLOYMENT.md#32-worked-examples).

These files used to be started by the compose file as a bundled proxy. That was removed because the
people who self-host a therapy tool already run a proxy of their own; the files stayed because the
reasoning in them is still the best guidance for choosing and configuring one.
