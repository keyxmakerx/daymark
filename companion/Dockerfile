# syntax=docker/dockerfile:1
#
# Daymark Companion — multi-stage build.
#   1. web      — vendored Svelte/Vite static bundle (no third-party origins at runtime)
#   2. server   — Ktor fat jar
#   3. runtime  — minimal JRE, non-root, serves the bundle + /healthz
#
# Hardening note (follow-up): the deployment design targets a distroless runtime with a
# pinned digest and a tiny static healthcheck binary. This scaffold uses temurin-jre +
# curl for clarity; swap to distroless + pinned digests before any public exposure. See
# docs/COMPANION_DEPLOYMENT.md and docs/COMPANION_SECURITY.md.

# ---- 1. Build the web bundle -------------------------------------------------
FROM node:22-alpine AS web
WORKDIR /web
# Activate the exact pnpm version (don't rely on corepack's lazy auto-download, which
# can fail on alpine and would mean an unpinned fetch at build time).
RUN corepack enable && corepack prepare pnpm@10.33.0 --activate
# Install deps first (cached) using only the manifest + lockfile.
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
RUN pnpm build

# ---- 2. Build the server jar -------------------------------------------------
FROM gradle:8.11.1-jdk21 AS server
WORKDIR /src
COPY server/ ./
RUN gradle --no-daemon shadowJar

# ---- 3. Runtime --------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 10001 daymark \
    && useradd -u 10001 -g 10001 -d /app -s /usr/sbin/nologin daymark
WORKDIR /app
COPY --from=server /src/build/libs/daymark-companion.jar /app/app.jar
COPY --from=web /web/dist /app/web
ENV DAYMARK_BIND_ADDR=0.0.0.0 \
    DAYMARK_PORT=8080 \
    DAYMARK_DATA_DIR=/data \
    DAYMARK_WEB_DIR=/app/web \
    DAYMARK_BASE_PATH=/ \
    DAYMARK_LOG_LEVEL=info
EXPOSE 8080
USER 10001:10001
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1
# /tmp is the only writable path needed at runtime (provide it via tmpfs when the
# container runs read-only — see docker-compose.yml).
ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
