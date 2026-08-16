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
FROM gradle:9.7.0-jdk21@sha256:86e1c174075288adf17b93172e07a4f018073735ae317134986c0ad55d0244db AS server
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
