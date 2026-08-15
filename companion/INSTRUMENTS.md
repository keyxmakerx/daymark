# Companion instrument ledger

This ledger mirrors the flagship app's `docs/INSTRUMENTS.md` and is the single, auditable
source of truth for the licence status and required attribution of every instrument the
Companion ships.

Every instrument the Companion offers MUST appear here before it can be enabled, and MUST be
**public-domain, openly-licensed, or self-authored** — never a licensed battery
(TOVA / Conners / CAARS and the others in `docs/COMPANION_FEATURES.md` §4.2 are explicitly
excluded). The runner's CI gate (`companion/web/src/lib/instruments/`) fails the build if a
definition references a ledger row that does not exist, is not non-diagnostic, contains a
self-harm slot, names a forbidden source, or emits a clinical cutoff / positive-negative
screening flag. Framing is always **non-diagnostic** ("a self-check, not a diagnosis").

## Shipped (Milestone 3)

Both current instruments are **self-authored** (original wording throughout), so no
third-party notice is required. Licensed-with-attribution instruments (e.g. WHO-5, ASRS
with its verbatim WHO notice) can be added later with their notice machinery.

<a id="wellbeing-selfcheck"></a>

### Daily wellbeing self-check — `wellbeing-selfcheck`

- **Type:** questionnaire (5 Likert items, sum, descriptive bands).
- **Licence / source:** Self-authored, original items. GPL-3.0. Not derived from WHO-5 or
  any licensed wellbeing scale.
- **Attribution:** none required (self-authored).
- **Notes:** descriptive within-person bands with a fixed non-diagnostic disclaimer; no
  clinical cutoff; no self-harm item.

<a id="focus-selfcheck"></a>

### Focus & follow-through self-check — `focus-selfcheck`

- **Type:** questionnaire (6 Likert items, sum, descriptive bands).
- **Licence / source:** Self-authored, original items. GPL-3.0. **Not** the ASRS — no ASRS
  item text and no ASRS shaded-box decision rule is reproduced.
- **Attribution:** none required (self-authored).
- **Notes:** our own informal splits (never the source cutoff); explicitly a within-person,
  non-diagnostic reflection; no self-harm item.

<a id="compassion-hard-moment"></a>

### A hard moment — `compassion-hard-moment`

- **Type:** guided exercise (unscored — `scoring.scales` is empty; every writing item is
  `excludeFromScoring`, so nothing here can enter a share).
- **Licence / source:** Self-authored, original wording. GPL-3.0. Draws on compassion-focused
  practice; reproduces no published script.
- **Attribution:** none required (self-authored).
- **Notes:** built to satisfy the rule in docs/EVIDENCE_REVIEW_2026-08.md §2 — never require the
  person to assert a positive claim about themselves they do not believe. Nothing asks anyone to
  say they are good or lovable, which is why it needs no gate before being offered to someone at
  their lowest. No self-harm item.

<a id="values-what-matters"></a>

### What matters to you — `values-what-matters`

- **Type:** guided exercise (unscored; one select for orientation, the rest free writing, all
  `excludeFromScoring`).
- **Licence / source:** Self-authored, original wording. GPL-3.0. Draws on values-clarification
  methods; reproduces no published instrument.
- **Attribution:** none required (self-authored).
- **Notes:** values writing, not self-evaluation — the writing is about what the person cares
  about rather than about them, which is what keeps it clear of the positive-self-statement
  problem. No self-harm item.

See `docs/COMPANION_FEATURES.md` for the engine design and the planned wider catalog.
