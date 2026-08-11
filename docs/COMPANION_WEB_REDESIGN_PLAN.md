# Daymark Companion — web redesign: implementation plan

> Companion to the renders in [`design/web-03-clinician-redesign.png`](./design/web-03-clinician-redesign.png)
> and [`design/web-04-roles-builders-compliance.png`](./design/web-04-roles-builders-compliance.png).
> This document is the bridge between those mockups and the code: what is actually true today,
> what the renders propose that **contradicts** the existing design system, and the phased work
> that follows. It plans the *web* only — the Android app is out of scope here.

## Contents

- [Where we actually are](#where-we-actually-are)
- [The one contradiction that needs a decision](#the-one-contradiction-that-needs-a-decision)
- [Phase 1 — token layer + primitives](#phase-1--token-layer--primitives)
- [Phase 2 — the migration](#phase-2--the-migration)
- [Phase 3 — the missing screens](#phase-3--the-missing-screens)
- [Phase 4 — the admin console](#phase-4--the-admin-console)
- [Explicitly not in this plan](#explicitly-not-in-this-plan)
- [Verification](#verification)

---

## Where we actually are

Three corrections to assumptions made while producing the renders, all verified against the tree.

**The design system is not missing — it is unimplemented.**
[COMPANION_DESIGN_SYSTEM.md](./COMPANION_DESIGN_SYSTEM.md) is 813 lines and web-specific. §4 already
specifies the full component inventory (`Card`, `EmptyState`, `AppShell`, `Table`, `Banner`,
`ScorePill`/`BandTag`, `NonDiagnosticBanner`, …) with accessibility requirements per component. §2.3
specifies a **two-tier** token model: primitives (hex, mirroring `Color.kt`) → semantic tokens, with
UI code referencing only the semantic layer.

None of that shipped. `src/app.css` is a single flat tier (`--paper-bg`, `--ink-text`, `--mood-N`)
with no primitive/semantic split, and `src/lib/components/ui/` does not exist. All **36** components
hand-roll a local `<style>` block.

**The tool builder already exists.** `src/lib/components/ToolBuilder.svelte` (231 lines) is wired to
`instruments/builder.ts` and runs `validateDraft()` live. The deck-two render labelled it "new UI,
real engine" — half wrong. The engine *and* a UI exist; what the render proposes is a different
presentation of the same gate (checks phrased as claims, tier picker showing locked tiers, band
contiguity called out inline).

**`docs/design/` already had a render convention**, with a README, a re-render command, and four
prior mockups. The two new decks were filed into it as `web-03` and `web-04` rather than starting a
new location.

### The measured problem

The complaint that the UI has "only one colour" is precise and countable:

| Measure | Count |
|---|---|
| Svelte components in `companion/web/src` | 36 |
| …that hand-roll their own `<style>` block | 36 |
| …that reference the mood ramp (`--mood-*`) | 31 |
| Total `var(--mood-*)` references | 105 |
| Components in `src/lib/components/ui/` | 0 |

The mood scale is doing double duty — a person's score *and* every banner, warning, capability row
and trust strip. That is the entire cause of the flat, one-note feel, and it is also a correctness
problem: a palette that encodes data cannot simultaneously encode interface state without the two
becoming unreadable from each other.

---

## The one contradiction that needs a decision

**§2.3 of the design system explicitly maps semantic colour onto the mood ramp:**

```css
--danger:  var(--c-mood-1);   /* destructive (revoke/delete) reuses the awful red */
--success: var(--c-mood-5);
--warning: var(--c-mood-2);
```

**The renders reject that mapping.** They propose the mood ramp become *data-only*, with the
interface given its own vocabulary:

| Role | Render proposes | Rationale |
|---|---|---|
| Structure, focus, active nav, links | `--indigo #3F5F7F` | Never encodes data. Deepened from the existing `--link`. |
| Alarm — needs a human | `--clay #A8574A` | The single alarm hue in the whole system. |
| Warn severity (console, provenance) | `--amber #9C7128` | Reserved; appears in exactly two places. |
| Chrome ground — rails, tables, bars | `--chrome #E4E7EC` | Cool grey-blue. The "cool chrome, warm content" split. |
| Data — scores, bands, trends | `--mood-1…5` | Unchanged, and from now on *only* this. |
| "Completed" | solid ink, **not** green | In a product whose trust strip may never be green, a green tick is a claim. |

There is no `--success` in the proposal. That is deliberate and it is the sharp edge of the
decision: [COMPANION_UX.md](./COMPANION_UX.md) §496 forbids painting the trust strip green, and the
renders generalise that — healthy is the *absence* of colour, everywhere, including the admin
console.

**This plan proceeds on the renders' model.** If that is wrong, it must be reversed now, before
Phase 2 touches 31 files. §2.3 is amended by this document rather than silently diverged from; the
amendment should be written back into COMPANION_DESIGN_SYSTEM.md once Phase 1 lands.

---

## Phase 1 — token layer + primitives

Foundation. Nothing user-visible changes; everything after this depends on it.

**1a. `src/app.css` becomes two-tier.**
Primitives (`--c-*`, mirroring `Color.kt`) → semantic. Adds the chrome layer, `--indigo`, `--clay`,
`--amber`, and a status ramp whose meaning is carried mostly by *form* (outline → tint → solid →
hatched), not hue. Every existing flat token name (`--paper-bg`, `--ink-text`, …) is kept as an alias
pointing at its semantic equivalent, so the 36 existing components keep compiling unchanged and
Phase 2 can migrate them file by file rather than in one commit.

All three theme states must be handled: bare `:root` (complete light palette), `@media
(prefers-color-scheme: dark)` guarded as `:root:not([data-theme="light"])`, and `:root[data-theme="dark"]`.

**1b. `src/lib/components/ui/` primitives.** A first cut of §4, only what the six rendered screens
actually use — not the whole inventory:

| File | Notes |
|---|---|
| `Card.svelte` | The paper sheet: header slot, body, optional footer note-strip. |
| `PageHeader.svelte` | Title + chips + trailing slot; the topbar in every render. |
| `StatusPill.svelte` | `scheduled · delivered · accepted · completed · declined · noResponse · overdue`. Form-first. |
| `Chip.svelte` | Neutral / accent / warn / critical. Mono, uppercase, tabular. |
| `Callout.svelte` | info / warn / critical. The banner substrate. |
| `EmptyState.svelte` | The thing the product has none of. Every list surface gets one. |
| `DataTable.svelte` | Mono uppercase heads, `tabular-nums`, hover row, card-collapse on narrow. |
| `AppShell.svelte` | Rail + topbar + body, with the stacking context fixed (see below). |
| `ProvenanceBadge.svelte` | Validated ✓ / Adapted ◐ / Custom ✎, from `instruments/provenance.ts`. |
| `BandTag.svelte` | The one primitive licensed to use the mood ramp. |

**1c. Invariant tests.** These are the point of the exercise, not decoration:

- No status token resolves to a green hue — asserted against the token values, so a future
  "success green" cannot be reintroduced quietly.
- `BandTag` and the chart components are the *only* modules permitted to reference `--mood-*`;
  a test greps the component tree and fails on any other match. (It must fail when the subject is
  absent, not pass on an empty grep.)
- Every primitive renders in both themes without a colour defined solely inside a media or
  `[data-theme]` block.

**1d. The stacking-context fix.** A hovered calendar cell lifts with `z-index`, and without an
isolated stacking context on the body it painted its shadow over the top bar and rail. `AppShell`
owns this: `topbar { position: relative; z-index: 6 }`, `rail { z-index: 7 }`,
`body { position: relative; z-index: 1; isolation: isolate }`. Fixed in the render; must not be
re-broken in the component.

## Phase 2 — the migration

31 components, 105 references. Mechanical but not blind — each reference is either *data* (stays
mood) or *state* (moves to the new vocabulary), and only reading it decides which. Migrate by
directory (`owner/`, then `therapist/`, then top-level), one commit each, `pnpm check && pnpm test`
green between them. Delete each component's local `<style>` block as its primitives land.

Two known bugs to fix while in there:

- `describeAssignment()` in `lib/assignments/describe.ts` renders *"Assign the Daily wellbeing
  self-check **self-check**, every week."* — it appends a noun the title already ends in. One
  template string, plus the unit test that should have caught it.
- `web/fonts/` contains only a README. `app.css` declares Fraunces and Inter and both silently fall
  back. Vendoring the two subset woff2 files is a separate, network-dependent commit.

## Phase 3 — the missing screens

In dependency order. Each is new; none exists in any form today.

1. **Today** — attention strip (exceptions only) over a quiet roster. Needs an `EmptyState` for the
   first-week case.
2. **Calendar** — month grid, four event kinds distinguished by *shape* so the surface survives
   colour-blindness at 4×11 px; agenda rail; 12-week completion ribbon. No calendar exists anywhere
   in `companion/web/src`; cadence is the product's spine and has never had a surface.
3. **Client record** — band trend with a band-labelled y-axis (never a raw sum, which invites
   reading a cut-off that does not exist), provenance per row, and the "edge of this screen" margin.
4. **Sign-in** — the two-column contract, with the pinned image digest promoted from footnote to
   control. The fixed `LowerAssuranceBanner` copy is not editable.
5. **Assign** — lifecycle in the composer rather than discovered later.

## Phase 4 — the admin console

Separate route, separate credential, dark in both themes. Ships **after** Phases 1–3, because it is
the surface where getting it wrong is most expensive.

Non-negotiables carried from the render:

- Operational health, auth pressure and chain integrity only. The relationship audit log is
  **owner-readable**; the server admin is not the owner and does not get it here.
- No compliance score, no percentage, no green shield. Ever.
- The chain verdict states *internal consistency*, never completeness — a hostile server can decline
  to append, or truncate to a shorter run that verifies perfectly. That caveat gets body text under
  the verdict, not small print.
- Known-bad news is surfaced to the operator, not buried: WebAuthn is a 501 stub, and `totp.secret_b64`
  stores the seed in cleartext.

## Explicitly not in this plan

Named so nobody assumes they are coming with it:

- **Orgs, roles, RBAC, front desk, org admin, prescriber.** Deck four renders
  [COMPANION_ACCESS_CONTROL.md](./COMPANION_ACCESS_CONTROL.md), which is a *specification*. The
  running system is one owner ↔ one pinned therapist, roles `OWNER`/`THERAPIST`. Building the org
  model is a server-side architecture project, not a UI sprint, and it should not be started by
  drawing its screens first.
- **Compliance integrations.** Wazuh / OpenSCAP / SRA Tool / OSCAL are a verified shortlist, not a
  decision. Nothing has been evaluated in this codebase.
- **WebAuthn.** Still a 501 stub. It is the single highest-value security item on the board and it
  is its own piece of work.
- **The phone → server sync screen.** The missing middle, unchanged by this plan.

## Verification

Both oracles run locally in this repo — there is no need to wait on CI for the web:

```
cd companion/web
pnpm install --frozen-lockfile
pnpm check     # svelte-check: 387 files, 0 errors at baseline
pnpm test      # vitest: 172 passed, 5 skipped at baseline
```

Any phase that does not leave both green is not done. CI (`.github/workflows/companion.yml`) remains
the authority for the server, the container and the egress assertions.
