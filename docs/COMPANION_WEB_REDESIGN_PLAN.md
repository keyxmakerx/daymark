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
- [Spec corrections beyond §2.3](#spec-corrections-beyond-23)
- [Phase 3 — the missing screens](#phase-3--the-missing-screens)
- [Phase 4 — the admin console](#phase-4--the-admin-console)
- [Explicitly not in this plan](#explicitly-not-in-this-plan)
- [Verification](#verification)

**Status:** Phases 1 and 2 landed and closed — both oracles are green at HEAD (`pnpm check` 413
files / 0 errors, `pnpm test` 403 passed / 5 skipped), and the two known-stale assertions that were
the last blockers have been updated rather than reverted. Phases 3 and 4 are unstarted. All counts
in this document were recomputed at HEAD; the ones that had drifted are corrected in place and say
so.

---

## Where we actually are

Three corrections to assumptions made while producing the renders, all verified against the tree.

**The design system is not missing — it is unimplemented.**
[COMPANION_DESIGN_SYSTEM.md](./COMPANION_DESIGN_SYSTEM.md) is web-specific and, after the §2.3
amendment and the corrections below, **1160 lines** (813 when this plan was written). §4 already
specifies the full component inventory (`Card`, `EmptyState`, `AppShell`, `Table`, `Banner`,
`ScorePill`/`BandTag`, `NonDiagnosticBanner`, …) with accessibility requirements per component. §2.3
specifies a **two-tier** token model: primitives (hex, mirroring `Color.kt`) → semantic tokens, with
UI code referencing only the semantic layer.

None of that had shipped when this plan was written: `src/app.css` was a single flat tier
(`--paper-bg`, `--ink-text`, `--mood-N`) with no primitive/semantic split, `src/lib/components/ui/`
did not exist, and all **36** components hand-rolled a local `<style>` block. Phase 1 built the
two-tier layer and the eleven primitives; that paragraph is history now, and the table below carries
the current numbers.

**The tool builder already exists.** `src/lib/components/ToolBuilder.svelte` (231 lines then, 242 at
HEAD after the Phase 2 migration) is wired to
`instruments/builder.ts` and runs `validateDraft()` live. The deck-two render labelled it "new UI,
real engine" — half wrong. The engine *and* a UI exist; what the render proposes is a different
presentation of the same gate (checks phrased as claims, tier picker showing locked tiers, band
contiguity called out inline).

**`docs/design/` already had a render convention**, with a README, a re-render command, and six
prior mockups (`app-01`…`app-04`, `web-01`, `web-02` — recounted; this document said four). The two
new decks were filed into it as `web-03` and `web-04` rather than starting a new location.

### The measured problem

The complaint that the UI has "only one colour" is precise and countable. **Every figure below was
recomputed at HEAD**, and the baseline column was recounted from the tree at `a061e2c` (the commit
before Phase 1) rather than copied forward:

| Measure | Baseline | After Phases 1–2 |
|---|---|---|
| Svelte components in `companion/web/src` | 36 | 47 (36 + 11 `ui/` primitives) |
| …that hand-roll their own `<style>` block | 36 | 47 — every component still has one |
| …that reference the mood ramp in **code** | 32 | **5**, all data surfaces |
| Total `var(--mood-*)` references in code | 105 | **22**, all data |
| Components in `src/lib/components/ui/` | 0 | **11** |

Three counts in this table were wrong and are corrected above. The baseline was **32** components,
not 31 — so Phase 2's stated scope was one file short. And the post-migration figures depend on
whether you count comments: a raw text grep finds mood tokens in **6** components and **23**
references, but one of those, in `ToolBuilder.svelte`, is the comment recording that the site used to
resolve to `var(--mood-5)` and no longer does. **Counted as code — which is what ships, and what the
invariant suite measures — it is 5 components and 22 references.** The five are
`charts/Sparkline.svelte`, `Overview.svelte`, `Dashboard.svelte`, `QuestionnaireRunner.svelte` and
`ui/BandTag.svelte`. (`app.css` defines the ramp and `lib/mood.ts` maps a level onto it; both are
allowlisted and neither is a component.)

That distinction is not pedantry here — it is the same one the tree-wide suite is built on, and
getting it wrong in either direction is a documented failure mode: a guard satisfied by the comment
explaining a rule instead of by the rule.

The mood scale was doing double duty — a person's score *and* every banner, warning, capability row
and trust strip. That is the entire cause of the flat, one-note feel, and it is also a correctness
problem: a palette that encodes data cannot simultaneously encode interface state without the two
becoming unreadable from each other.

The residual 22 references are the point of the exercise, not a remainder to grind down: they are
the charts, the band tags and the scored-result edge, where the ramp *is* the legend. A future
count of zero would mean the product had stopped colouring a person's data.

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
Phase 2 touches 32 files. §2.3 is amended by this document rather than silently diverged from; the
amendment should be written back into COMPANION_DESIGN_SYSTEM.md once Phase 1 lands.

### Resolved — the amendment has landed

[COMPANION_DESIGN_SYSTEM.md](./COMPANION_DESIGN_SYSTEM.md) **§2.3 has been rewritten** and now
records the renders' model as the specification. The `--danger: var(--c-mood-1)` /
`--success: var(--c-mood-5)` / `--warning: var(--c-mood-2)` mapping is gone from the spec, not
merely diverged from in code. What §2.3 now carries:

- **§2.3.0** the generating rule, "cool chrome, warm content".
- **§2.3.1** the mood ramp is DATA ONLY, as an invariant with a named test —
  `companion/web/src/lib/components/ui/invariants.test.ts`, group (c) — plus the DATA/STATE
  classification rule and an honest statement of the test's scope (`ui/` only; the data surfaces
  outside it use the ramp legitimately).
- **§2.3.2** there is deliberately no `--success`, and why in one sentence.
- **§2.3.3** the two-tier model, now real, and where it lives.
- **§2.3.4** the chrome / indigo / clay / amber contract, with indigo never encoding data and
  clay the single alarm hue. `--danger` survives as an alias of `--clay`, so code has the role
  name the old spec promised without a second red entering the system.
- **§2.3.5** the pinned severity decisions, including the non-diagnostic strip as *information*
  on the chrome ground rather than a warning.
- **§2.3.6** the measured accessibility corrections with their ratios, marked normative.
- **§2.3.7** the hairline/`--border-strong` rule, with `--border-strong`'s corrected values.

Sections that contradicted the amendment were fixed with it: §2.2, §2.4, §3.1, §3.2, §4, §5, §6.2,
§6.3, §9, and the document's front-matter status banner. See "Spec corrections beyond §2.3" below.

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

### Phase 1 — landed

Verified independently of the agents that produced it: `pnpm check` 403 files / 0 errors,
`pnpm test` 195 passed / 5 skipped / 0 failed (baseline was 387/0 and 172/5). 11 components,
3 type modules, a barrel, and 23 invariant tests.

**The invariants were mutation-tested, not assumed.** Injecting `var(--mood-4)` into `Card.svelte`
fails 2 tests; injecting `#22c55e` fails 2 others. Both mutations were confirmed to have actually
applied to the file before the suite was run — a first attempt patched a selector that did not exist,
the suite passed, and that "pass" meant nothing. Any future change to these tests must be re-proven
the same way.

**Token preservation was checked in a real CSS engine**, not by parsing. Three pages (plain,
`data-theme="dark"`, and a copy with only the media *condition* neutralised) were rendered in headless
Chromium and `getComputedStyle` was read for all 36 pre-existing tokens. Light and system-dark are
byte-identical to `HEAD`. This method replaced a hand-rolled resolver that reported 18 false
mismatches because it matched `:root` by first occurrence and collapsed all three blocks onto the
primitives.

**One deliberate behaviour change, which the agent's "no values changed" summary understated.**
`:root[data-theme='dark']` previously set `color-scheme: dark` and nothing else — the explicit dark
toggle produced *light* tokens with a dark form-control hint. It now redefines all 30 themed tokens.
The in-UI theme toggle the old comment called "future" would not have worked; now it will.

### Carried into Phase 2 — resolved

Each of these was carried forward from Phase 1 and each is now closed. Resolutions are recorded
here rather than deleted, because the *reasoning* is the part worth keeping.

- **~~A live invariant violation predating this work.~~ Resolved.** `ToolBuilder.svelte:214,224`
  used `var(--accent, var(--mood-5))` against an `--accent` defined nowhere, so the fallback was
  live and a selected segmented-control button and a link were painted from the mood ramp. Both
  moved to `--indigo`, which is the token that exists for exactly this role. The hardcoded `#fff`
  on line 214 went with them.
  *Note the shape of this bug:* it was invisible because the code named a plausible token. A
  fallback that fires is indistinguishable from an intended value at the call site, and only
  resolving it in a browser shows the ramp. Prefer no fallback over a mood-ramp fallback.
- **~~`--focus-ring` resolves to `#5E8A66`, the same value as `--mood-5`.~~ Resolved, and it was
  worse than "a coincidence".** Light `--focus-ring` was byte-identical to `--mood-5` and dark to
  `--mood-4`, so **the focus ring rendered as a "rad" mood bar** — interface state wearing a
  person's data, which is precisely what invariant 1 forbids. It now carries the indigo, keeping
  its own `--c-focus-*` primitives so focus can move without dragging structure or the ramp along.
  Recorded permanently in design-system §2.3.6.
- **~~Only 2 hardcoded colour literals remain in non-`ui/` components.~~ Resolved** with the rest
  of the migration; the guard is now tree-wide rather than a count in a document.

Two items were **added** to Phase 2 by the audit that ran alongside it:

- **Seven failing contrast pairs**, in `--chrome-soft`, `--clay`, `--amber` and `--border-strong`.
  `--border-strong` was the serious one at 1.56:1 — it is the sole boundary of every button, input,
  textarea and select, drawn on a sheet that is 1.10:1 against the page. All were solved
  numerically and the ratios are pinned in design-system §2.3.6, which is normative.
- **~~Eleven components use `--ink-faint` as real text~~ (2.37:1 light). Resolved.** Not by changing
  the token — `--ink-faint` is decorative by contract and raising it collapses the three-tier ink
  scale — but by switching those call sites to `--text-subtle`. Recounted at HEAD: **no `.svelte`
  file references `var(--ink-faint)` at all**, and eleven reference `--text-subtle`. The token is
  still defined and still decorative; the single remaining mention of the name in a component is the
  comment at `owner/GrantManager.svelte:129` recording why the site moved.

## Phase 2 — the migration

**Scope: 32 components, 105 `var(--mood-*)` references** (recounted; this line said 31). Mechanical but not blind — each
reference is either *data* (stays mood) or *state* (moves to the new vocabulary), and only reading
what renders it decides which. Migrate by directory (`owner/`, then `therapist/`, then top-level),
one commit each, `pnpm check && pnpm test` green between them. Delete each component's local
`<style>` block as its primitives land.

Two known bugs to fix while in there:

- **~~`describeAssignment()` renders *"Assign the Daily wellbeing self-check **self-check**, every
  week."*~~ Fixed** at `lib/assignments/describe.ts:40-41`: the noun is appended only when the title
  does not already end in it, and the title is trimmed so an empty one cannot leave a doubled space.
- **`web/fonts/` contains only a README** — still true at HEAD. `app.css` names Fraunces and Inter in
  `--font-display` / `--font-text` and both silently fall back to the system stack; the two
  `@font-face` blocks remain commented out (`app.css:48-63`), which is the honest state while the
  binaries are absent. Vendoring the two subset woff2 files is a separate, network-dependent commit.

### Phase 2 — what it settled

The durable output of Phase 2 is not the diff, it is the **classification**. Every `var(--mood-*)`
in the tree was read and sorted into exactly two bins:

- **DATA — kept the ramp.** `charts/Sparkline.svelte` (the plotted mood series), `Overview.svelte`
  and `Dashboard.svelte` (mood-count bars, activity-delta bars, trend line),
  `QuestionnaireRunner.svelte` (the band edge on a scored result), and `ui/BandTag.svelte`. In
  these, **the ramp is the legend** — an "awful" bar must be the colour an "awful" mood is
  everywhere else, so recolouring them to a neutral series palette would delete meaning rather
  than add clarity. Each site carries an in-file `/* DATA — do not migrate */` note naming why,
  because the next reader's instinct will be to "fix" it.
- **STATE — moved.** Banners, errors, selected tabs, links, focus, callout rails, capability rows,
  the trust strip. These went to `--chrome-*` / `--indigo` / `--clay` / `--amber` per the pinned
  decisions in design-system §2.3.5.

Three specific outcomes worth naming:

- **The trust strip sits on the chrome ground.** It previously drew `--mood-3`. It is not a
  warning and not a data surface — it is the instrument describing its own posture — so it takes
  the quiet chrome ground. It may never be painted green under any state.
- **The fixed non-diagnostic banners became chrome, not amber.** They are *information*, part of
  the instrument, and reading as "something is wrong" was itself a small dishonesty. This is what
  frees amber to mean warning. The **lower-assurance banner stayed a warning** and took amber.
- **All fixed copy is byte-identical.** The non-diagnostic banners, the lower-assurance banner, the
  provenance disclaimers, the audit caveats and the crisis/safety copy are non-server-supplied
  constants. Containers were restyled; **no prose changed**. Verify with `git diff` on any file
  carrying such copy — this is a release gate, not a code-review preference.

### Phase 2 — verification

Both oracles must be green, per the standard at the bottom of this document. **A phase is not done
until they are, and "the migration is written" is not the same claim.**

```
cd companion/web
pnpm check     # svelte-check — 387/0 baseline; 403/0 after Phase 1; 413/0 at HEAD
pnpm test      # vitest — 172/5 baseline; 195/5 after Phase 1; 403 passed / 5 skipped at HEAD
```

The HEAD figures are higher than Phase 2's because the companion substrate landed after it
(signal vocabulary, dialogue format and planner, companion content and their suites). They are the
current gate; the Phase-1 and Phase-2 numbers are kept as history, not as thresholds to check
against.

The guard grew with the migration. `ui/invariants.test.ts` polices the eleven primitives by
reading its own directory, which cannot become a tree-wide claim; Phase 2 made a claim about
*every* file, so it needs a guard over the whole tree —
`src/lib/components/invariants.tree.test.ts`. That suite walks all of `src/` and asserts, with
every list proven non-empty before it is filtered: no file outside the allowlisted data surfaces
names a mood token; no style block contains a hex; no component references a token `app.css` does
not define; and the fixed copy still reads exactly as it reads.

> **Two failure modes this suite is built against, both of which have already happened here.**
> A grep-shaped guard goes green when it matches *nothing* — rename a directory and
> `expect(offenders).toEqual([])` reports success on the empty set, and being green it removes the
> appetite to write a real one. And a guard run over raw text is satisfied by the *comment
> explaining* the rule instead of by the rule, since the migration left "this used to be
> `--mood-5-wash`" in nearly every file it touched. Structural assertions therefore run over
> comment-stripped code; copy assertions run over markup with script and style removed, so a
> sentence quoted in a comment cannot stand in for the sentence a person reads.

**Known-stale assertions to clear before the phase closes — both cleared.** Two pre-existing tests
encoded the *old* state and failed correctly-changed code. Neither was a defect in the migration;
both needed the assertion updated, not the code reverted. Recorded because the shape recurs:

- **`ui/invariants.test.ts` pinned `LIGHT_VALUES` / `DARK_VALUES` at the pre-audit hexes**
  (`--chrome-soft: #626d7d`, `--clay: #a8574a` / `#c9806f`, `--amber: #9c7128`), and the §2.3.6
  corrections failed five of them. **Repinned at the corrected values**
  (`invariants.test.ts:253-273`), each carrying the ratio it was moved for in a comment. It is still
  a pin, which is the point — the pin is what stops a silent re-point.
- **`trustbar.test.ts` asserted `expect(trustBarCode).toMatch(/--mood-3/)`**, written to prove the
  strip was not green by showing it was amber-ish; once the strip moved to the chrome ground the
  positive half became wrong. **Now `trustbar.test.ts:55,64`**: `not.toMatch(/--mood-5/)` kept, and
  the positive half replaced with `toMatch(/background:\s*var\(--chrome\)/)`.

## Spec corrections beyond §2.3

Writing the §2.3 amendment meant reading the rest of
[COMPANION_DESIGN_SYSTEM.md](./COMPANION_DESIGN_SYSTEM.md) against the code. It contradicted the
implementation in more places than the one this plan was chartered to settle. All were corrected in
the spec; none required a code change, because in every case the *code* was right and the document
was describing something that had never been built or had been built under another name.

| Where | The contradiction | Correction |
|---|---|---|
| Front matter | "⚠️ STATUS: DESIGN ONLY — NO CODE EXISTS YET." An app, a token layer, eleven primitives, charts and a served CSP all exist. | Replaced with a BUILT / NOT BUILT table and per-section status markers. A blanket "nothing is implemented" is now the *false* claim. |
| §2.2 | A `--fs-display … --fs-mono` type scale. | Never implemented. Sizes are set per-component off the 16px base. Marked unbuilt. |
| §2.2 | Fraunces + Inter "vendored, served `'self'`". | `web/fonts/` holds a README; the `@font-face` rules in `app.css` are **commented out** and both faces fall back to system stacks. The product does not currently render in either font. |
| §2.3 | `--bg`, `--surface`, `--surface-2`, `--text`, `--text-muted`, `--text-faint`, `--accent`, `--elev-0..3`, `--c-sky-*`. | None exist. Replaced with the real names (`--paper-bg`, `--paper-sheet`, `--ink-text`, `--ink-soft`, `--ink-faint`, `--ink-accent`, `--elevation`). |
| §2.3.1 → §2.3.7 | `--border-strong` specified as `#B7AD9B` / `#5A5648`. | Never implemented; what shipped was `#cbc1ae` / `#4a463c` at **1.56:1** and **1.69:1**. Corrected to `#9B8864` / `#726B5C` at 3.01:1, with the reasoning. |
| §2.4 | `--sp-1..8`, `--r-sm/md/lg/xl/pill`, `--measure`, `--container-max`, `--hairline-w`. | Shipped as `--space-1..8` (no `-7`), `--radius`, `--radius-sm`, `--maxw`. The rest do not exist. |
| §3.1 | A high-contrast mode via `[data-contrast="high"]` / `@media (prefers-contrast: more)`, with a CSS block. | **Nothing in the product responds to a high-contrast preference.** No such block, no such attribute. Marked NOT BUILT, and §6.2's "high-contrast mode pushes toward AAA" demoted from fact to goal. |
| §3.1 | A pre-paint theme bootstrap at `/assets/theme-bootstrap.js` and a `localStorage` toggle. | Neither exists; `index.html` loads one module. The `[data-theme]` mechanism *is* complete — it is ready and unwired. |
| §3.2 | `--ease-standard`, `--ease-entrance`, `--dur-fast/base/slow`, `.route-enter`. | No motion tokens exist. The global `prefers-reduced-motion` block **does**, and covers every element — so the reduced-motion guarantee holds without them. |
| §4 | A ~60-component inventory read as delivered. | Eleven exist. A status table names them; the note warns that feature components elsewhere in the tree are screens, not a library, and must not be counted. |
| §5 | Charts "styled by tokens (`--series-*`, …)". | `--series-1..5` were never implemented **and should not be**. A mood chart is data and is drawn on the mood ramp — the ramp is the legend. A neutral series palette is right only for future non-mood charts. `--text-muted` in the same sentence is likewise not a token. |
| §6.3 | "CI runs axe-core over rendered routes" and a CSS contrast validator. | Neither exists, and there is no component-rendering harness at all. Replaced with a description of `invariants.test.ts` — what it does assert, and the two things it cannot. |
| §9 | An all-unticked checklist that read as a plan. | Rewritten so a box is ticked only if you can go read the thing, with split lines where half shipped. |

One theme runs through the whole table: **the document repeatedly named tokens that were never
built, and the code repeatedly built tokens the document never named.** A two-tier token model
makes that class of drift cheap to detect — the semantic tier is a short, finite list — which is
part of the argument for §2.3.3 beyond theming convenience.

### One contradiction left open, in a document this plan does not own

[COMPANION_UX.md](./COMPANION_UX.md) §10 reserves a green **`--trust-locked`** token "for surfaces
where assurance is genuinely high" — and then §10.1 rules that no served page ever qualifies,
which is the very rule the no-`--success` decision generalises from. The token does not exist in
`app.css` and, under design-system §2.3.2, must never be added: **a green token defined for a case
the same document says never occurs is a green tick waiting for someone to find a use for it.**

COMPANION_UX.md §10 needs the amendment §2.3 just received. It is flagged in design-system §2.3.2
and left to that document's owner rather than changed here — but it is the one place a future
"success green" could still enter the system with a citation behind it, so it should not sit long.

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
pnpm check     # svelte-check: 413 files, 0 errors at HEAD (387/0 baseline, 403/0 after Phase 1)
pnpm test      # vitest: 403 passed, 5 skipped across 32 files at HEAD (172/5 baseline, 195/5 after Phase 1)
```

Of those, the two invariant suites are 54 tests: `ui/invariants.test.ts` 23, over the eleven
primitives; `components/invariants.tree.test.ts` 31, over the whole of `src/`.

**Any phase that does not leave both green is not done.** Not "done with a known failure", not
"done pending a test update" — the counts are the gate, and a phase summary that reports work
completed without reporting the oracle is not a summary. CI (`.github/workflows/companion.yml`)
remains the authority for the server, the container and the egress assertions.

Three things the oracles do **not** cover, so they stay human gates:

- **Fixed copy.** No test can tell you that a reworded banner is worse. After touching any file
  carrying non-diagnostic, lower-assurance, provenance, audit-caveat or crisis copy, run `git diff`
  on it and confirm no prose changed.
- **The DATA/STATE call.** The tree-wide guard enforces an *allowlist* of files permitted to name
  the mood ramp. It cannot tell you a given reference inside an allowlisted file is data. Only
  reading what renders it does that.
- **Computed contrast.** Nothing recomputes the design-system §2.3.6 ratios. A green `pnpm test`
  is not evidence that a new colour pair passes.
