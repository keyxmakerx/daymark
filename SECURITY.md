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

**The `sync` build and the Companion are in scope.** The `sync` flavor is a separate, opt-in build
with its own application id (`.sync` suffix); a `foss` install can never be updated into it. It
talks to a Companion server (`companion/server`), whose web consoles (`companion/web`) also work on
their own: it pairs, sends and fetches an encrypted copy, pairs with clinicians and receives their
game plans; the rest of the phone's half is #138. No `sync` APK has been released, so fixes to it
target `main`. The Companion image is published with every release tag, so fixes to the server and
consoles target `main` and ship in the next release. Reports against either are welcome now. The design assumes the server is untrusted: it must never see journal content, its
logs must never carry content, and it vouches for no key it relays. A report showing any of those
does not hold is the one we most want. The Companion's threat model is
[docs/COMPANION_SECURITY.md](docs/COMPANION_SECURITY.md).

Out of scope: the standard sideloading "unknown app" warning is an Android behavior, not a
vulnerability.

## What we already do

- The journal database is encrypted at rest (SQLCipher) under a random key that is wrapped by the
  phone's hardware keystore, so a copy of the app's files taken off the phone cannot be opened. An
  existing unencrypted journal is migrated on first launch; a phone whose keystore cannot make a key
  keeps an unencrypted one. Wrapping that key under the PIN or a written-down recovery code is built
  but not switched on (#109).
- PIN stored as PBKDF2 (random per-PIN salt) in AES-256 `EncryptedSharedPreferences`, with
  failed-attempt lockout/backoff and constant-time comparison.
- `FLAG_SECURE` while the app lock is enabled (keeps content out of screenshots/recents).
- Strong (Class 3) biometrics only.
- No `INTERNET` permission in the `foss` build; `allowBackup="false"`.
- R8 minification, immutable `PendingIntent`s, and Gradle wrapper validation in CI.
