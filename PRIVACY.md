# Privacy

The full privacy statement lives in **[docs/PRIVACY.md](docs/PRIVACY.md)**.

In short: Daymark is **100% local**. It has **no `INTERNET` permission** and makes no network
connections, so nothing you log ever leaves your device unless **you** export it. There are no
accounts, analytics, ads, or trackers. See [docs/PRIVACY.md](docs/PRIVACY.md) for where data
lives, the (unencrypted) local database vs. the encrypted PIN hash, the breathing check, and a
table explaining every permission.

This describes the `foss` build, which is the only build released. A separate, opt-in `sync` build
that can share data with a Companion server is in development and unreleased; it has its own
application id, is never installed by updating the `foss` build, and will ship with its own
privacy statement before it is released.
