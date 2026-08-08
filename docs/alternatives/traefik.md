# Daymark Companion behind Traefik (v3)

Traefik terminates TLS and routes by host. Attach these labels to the `companion`
service in `docker-compose.yml` (and remove the `ports:` block so the container is only
reachable through Traefik on the shared network).

```yaml
services:
  companion:
    # ...existing config...
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.daymark.rule=Host(`daymark.example.com`)"
      - "traefik.http.routers.daymark.entrypoints=websecure"
      - "traefik.http.routers.daymark.tls.certresolver=le"
      - "traefik.http.services.daymark.loadbalancer.server.port=8080"
      - "traefik.http.services.daymark.loadbalancer.healthcheck.path=/healthz"
      # HSTS at the terminator:
      - "traefik.http.middlewares.daymark-hsts.headers.stsSeconds=31536000"
      - "traefik.http.middlewares.daymark-hsts.headers.stsIncludeSubdomains=true"
      - "traefik.http.routers.daymark.middlewares=daymark-hsts@docker"
    networks: [proxy]
```

## Trusted-proxy / forwarded headers

Configure Traefik's entrypoint to trust **only** Traefik's own forwarded headers and to
strip client-supplied ones. In the static config:

```yaml
entryPoints:
  websecure:
    address: ":443"
    forwardedHeaders:
      # Trust X-Forwarded-* only from these source IPs (Traefik itself / your LB).
      trustedIPs:
        - "10.89.0.0/24"
```

Never set this to a broad range; the Companion derives rate-limit identity and the
WebAuthn origin from forwarded headers **only** when they come from the pinned proxy.

## Sub-path

To serve under `/daymark`, set `DAYMARK_BASE_PATH=/daymark` and add a
`PathPrefix(\`/daymark\`)` to the router rule (no `stripPrefix` — the app expects the
prefix).
