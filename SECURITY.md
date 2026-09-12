# Security Policy

## Supported versions

Daymark is pre-1.0; security fixes target the **latest release** and `main`.

## Reporting a vulnerability

Please report security issues **privately** — do not open a public issue for an exploitable
vulnerability.

- Preferred: GitHub's **private vulnerability reporting** ("Report a vulnerability" under the
  repository's *Security* tab).
- We aim to acknowledge reports within a few days and will credit reporters who wish to be named.

## Scope

Daymark has two build flavors, and their surfaces are different.

**The `foss` build is the only build that has been released, and it is local-only.** It declares
no `INTERNET` permission, so it cannot open a network connection, and there is nothing for it to
connect to. Its surface is on-device:

- The app-lock (PIN/biometric) and how the PIN hash is stored.
- Handling of backup/export files and imported data.
- Local data-at-rest protections and exported Android components (activity, receivers, widget).

**The `sync` build and the Companion are in scope, and are unreleased.** The `sync` flavor is a
separate, opt-in build with its own application id (`.sync` suffix); a `foss` install can never
be updated into it. It talks to a Companion server (`companion/server`) and web consoles
(`companion/web`) in this repository. No `sync` build has been tagged, so fixes for this surface
target `main` only, and reports against it are welcome now. The design assumes the server is
untrusted: it must never see journal content, its logs must never carry content, and it vouches
for no key it relays. A report showing any of those does not hold is the one we most want.

Out of scope: the standard sideloading "unknown app" warning is an Android behavior, not a
vulnerability.

## What we already do

- PIN stored as PBKDF2 (random per-PIN salt) in AES-256 `EncryptedSharedPreferences`, with
  failed-attempt lockout/backoff and constant-time comparison.
- `FLAG_SECURE` while the app lock is enabled (keeps content out of screenshots/recents).
- Strong (Class 3) biometrics only.
- No `INTERNET` permission in the `foss` build; `allowBackup="false"`.
- R8 minification, immutable `PendingIntent`s, and Gradle wrapper validation in CI.
