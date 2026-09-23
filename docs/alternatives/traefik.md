# Daymark Companion behind Traefik (v3)

Traefik terminates TLS and routes by host. It is a proxy in a container, so run the Companion with the
no-egress override, which removes the published port and leaves the app reachable only on its own
network:

```sh
docker compose -f docker-compose.yml -f docker-compose.no-egress.yml up -d --build
docker network connect daymark-companion_back traefik     # or declare the network in Traefik's compose
```

Then add these labels to the `companion` service, in a `docker-compose.override.yml` of your own:

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

Do not add a Content-Security-Policy in a headers middleware: the app sends its own, and two are
intersected by the browser, which breaks decryption in the consoles.

## Forwarded headers — two settings, two directions

**Traefik's side.** An entrypoint's `forwardedHeaders.trustedIPs` lists who may send Traefik
`X-Forwarded-*` headers. From anyone else, Traefik deletes those headers and then appends the address
it saw. When Traefik is the edge, leave `trustedIPs` unset; list only a load balancer or tunnel that
sits in front of Traefik, by its exact address.

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
