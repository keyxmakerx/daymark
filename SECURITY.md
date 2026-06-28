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

Because Daymark is local-only and has no backend, the relevant surface is **on-device**:

- The app-lock (PIN/biometric) and how the PIN hash is stored.
- Handling of backup/export files and imported data.
- Local data-at-rest protections and exported Android components (activity, receivers, widget).

Out of scope: there is no server, so there is no server-side surface. The standard sideloading
"unknown app" warning is an Android behavior, not a vulnerability.

## What we already do

- PIN stored as PBKDF2 (random per-PIN salt) in AES-256 `EncryptedSharedPreferences`, with
  failed-attempt lockout/backoff and constant-time comparison.
- `FLAG_SECURE` while the app lock is enabled (keeps content out of screenshots/recents).
- Strong (Class 3) biometrics only.
- No `INTERNET` permission; `allowBackup="false"`.
- R8 minification, immutable `PendingIntent`s, and Gradle wrapper validation in CI.
