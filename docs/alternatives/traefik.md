# Daymark Companion behind Traefik (v3)

Traefik terminates TLS and routes by host. It is a proxy in a container, so run the Companion with the
no-egress override, which removes the published port and leaves the app reachable only on its own
network, `daymark-companion_back` (the compose project `daymark-companion`, its network `back`).

Put these labels on the `companion` service, in a `docker-compose.override.yml` of your own beside
the shipped compose files:

```yaml
services:
  companion:
    labels:
      - "traefik.enable=true"
      - "traefik.docker.network=daymark-companion_back"
      - "traefik.http.routers.daymark.rule=Host(`daymark.example.com`)"
      - "traefik.http.routers.daymark.entrypoints=websecure"
      - "traefik.http.routers.daymark.tls.certresolver=le"
      - "traefik.http.services.daymark.loadbalancer.server.port=8080"
      - "traefik.http.services.daymark.loadbalancer.healthcheck.path=/healthz"
      # HSTS at the terminator; the app deliberately does not send it:
      - "traefik.http.middlewares.daymark-hsts.headers.stsSeconds=31536000"
      - "traefik.http.middlewares.daymark-hsts.headers.stsIncludeSubdomains=true"
      - "traefik.http.routers.daymark.middlewares=daymark-hsts@docker"
```

Start it naming all three files. Compose reads `docker-compose.override.yml` by itself only when no
file is named with `-f`, so without the last one the app starts with no labels and Traefik never
routes to it:

```sh
docker compose -f docker-compose.yml -f docker-compose.no-egress.yml \
  -f docker-compose.override.yml up -d --build
```

Then put Traefik on the app's network. `docker network connect daymark-companion_back traefik` does
it until the Traefik container is next recreated; to keep it, declare the network in Traefik's own
compose file instead:

```yaml
services:
  traefik:
    networks: [default, daymark]    # plus any it already has
networks:
  daymark:
    name: daymark-companion_back
    external: true
```

Do not add a Content-Security-Policy in a headers middleware: the app sends its own, and two are
intersected by the browser, which breaks decryption in the consoles.

## Unknown hosts

The router's `Host` rule is the catch-all that requirement 5 of
[COMPANION_DEPLOYMENT.md §3.1](../COMPANION_DEPLOYMENT.md#31-what-your-proxy-must-do) asks for. A
request for any other name matches no router and gets Traefik's own 404, so only a request for
`daymark.example.com` reaches the app; keep this the only router on the service. An unknown SNI
still completes the handshake, on Traefik's default self-signed certificate, before that 404. To
refuse it in the handshake, as the nginx example does, set `sniStrict: true` on the `default` TLS
options, in a file-provider configuration: labels cannot set TLS options.

## Forwarded headers — two settings, two directions

**Traefik's side.** An entrypoint's `forwardedHeaders.trustedIPs` lists who may send Traefik
`X-Forwarded-*` headers. From anyone else, Traefik deletes those headers and then appends the address
it saw. When Traefik is the edge, trust nobody, as the Caddy example does by leaving Caddy's
`trusted_proxies` unset: the static configuration names no `trustedIPs`. Only if a load balancer or
tunnel sits in front of Traefik, uncomment the block for that one address, and add the same address
to `DAYMARK_TRUSTED_PROXIES` too, or every client looks to the app like the load balancer. Never a
range, and never `forwardedHeaders.insecure`, which trusts everyone.

```yaml
# traefik.yml, Traefik's static configuration: the entry point the labels name
entryPoints:
  websecure:
    address: ":443"
    # forwardedHeaders:
    #   trustedIPs:
    #     - "192.0.2.10/32"    # the one load balancer in front of Traefik, if there is one
```

**The Companion's side.** Set `DAYMARK_TRUSTED_PROXIES` in `.env` to Traefik's own address on
`daymark-companion_back`, as a `/32` — read it off that row of:

```sh
docker inspect traefik \
  --format '{{range $n, $c := .NetworkSettings.Networks}}{{$n}} {{$c.IPAddress}}{{"\n"}}{{end}}'
```

Never a broad range: the Companion reads `X-Forwarded-For` — and no other forwarded header — only
from the addresses listed there, and a range would let any container on it forge a client address and
walk past the lockouts. Then prove it with the lockout-isolation test in
[COMPANION_OBSERVABILITY.md §1.7](../COMPANION_OBSERVABILITY.md#17-how-to-verify--the-lockout-isolation-test).

## Sub-path

Serve the Companion at the root of its own hostname. Sub-path deployment does not work consistently
yet (#176).
