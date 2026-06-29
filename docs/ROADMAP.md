# Roadmap

Milestones are indicative, not dated. Done items live in [CHANGELOG.md](../CHANGELOG.md).

## v0.1 — MVP (done)
Mood logging, calendar, statistics, daily reminder, PIN + biometric lock, JSON backup/restore.

## Current — paper redesign, features & hardening (mostly done)
- "Modern paper" design + original icon set ✔
- Journal, Year-in-Pixels, Goals, CSV export, backup merge, home-screen widget ✔
- Security hardening (encrypted/PBKDF2 PIN + lockout, re-lock, FLAG_SECURE, strong biometrics,
  R8, release signing, wrapper validation, Dependabot) ✔
- Renamed to **Daymark** ✔

## Next
- **First-run onboarding wizard** (skippable): reminder + notification permission, optional PIN,
  first entry / theme.
- **Haptics & micro-animations** on mood select and key interactions.
- Tap a calendar day to view/edit it; global search across mood notes.
- Bundle the paper typefaces; accessibility (TalkBack labels, large fonts).
- Room **migration tests** (instrumented / Robolectric).

## v1.0 — feature parity & store-readiness
- Customizable mood scale, labels & colors; custom activity icons.
- **Encrypted backups** and optional **database encryption** (Keystore-wrapped key).
- Localization / translations.
- Richer stats (day-of-week / time-of-day), "on this day" memories.
- **F-Droid** submission: verify FOSS deps, reproducible build, fastlane metadata, release
  signing; publish APK SHA-256 in release notes.

## Beyond
- Opt-in self-hosted sync (Nextcloud/WebDAV) behind a separate flavor so the F-Droid core stays
  trackerless; photo attachments; shareable monthly summary; Wear OS quick-tile.
- **Daymark Companion** (optional, self-hosted, zero-knowledge server; Docker/Compose) — design
  complete, not yet built. Four pillars: E2EE multi-device sync; expanded "sit-down" user features
  (questionnaire engine + non-diagnostic cognitive/attention testing); revocable MFA-protected
  therapist access; and therapist-authored "game plans" — all in a modern, fully-vendored,
  strict-CSP web UI. See [COMPANION_README.md](COMPANION_README.md). Likely sequencing: **sync +
  owner report viewer + assessment runner first**, with the owner→therapist sharing/game-plan
  surfaces gated on maintainer sign-off (see the open questions in
  [COMPANION_SCOPE.md](COMPANION_SCOPE.md)).
