# Companion instrument ledger

> **Stub.** This ledger mirrors the flagship app's `docs/INSTRUMENTS.md` and exists so the
> Companion's questionnaire/testing engine has a single, auditable source of truth for the
> licence status and required attribution of every instrument it ships. It is **empty of
> instruments today** — the Phase-0 viewer (Milestone 1) renders an existing backup and
> ships no assessments. Entries are added with the assessment-runner milestone.

Every instrument the Companion offers MUST appear here before it can be enabled, and MUST be
**public-domain, openly-licensed, or self-authored** — never a licensed battery
(TOVA / Conners / CAARS are explicitly excluded). The runner's CI gate fails the build if a
definition references a ledger row that does not exist, or emits a clinical cutoff /
positive-negative screening flag. Framing is always **non-diagnostic** ("a self-check, not
a diagnosis").

| Instrument | Type | Licence / source | Required attribution | Notes |
|---|---|---|---|---|
| _(none yet)_ | | | | |

See `docs/COMPANION_FEATURES.md` for the engine design and the planned first catalog.
