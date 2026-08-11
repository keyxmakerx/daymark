# Daymark Companion — Design System ("Modern Paper, Big Screen")

> ## ⚠️ STATUS: PARTLY BUILT — READ THE PER-SECTION MARKERS
>
> This document was originally written as design-only. Some of it has since shipped and
> some has not, so a blanket "nothing is implemented" banner would now be the *false*
> claim. Each major section carries its own status marker. As of this revision:
>
> | | |
> |---|---|
> | **BUILT** | Svelte 5 + TS + Vite app under `companion/web/`. Two-tier token layer (`src/app.css`, §2.3) with the chrome/indigo/clay/amber layer. Eleven `ui/` primitives (§4). Hand-rolled SVG charts (§5). Reduced-motion global (§3.2). Strict CSP served by Ktor (`companion/server/.../SecurityHeaders.kt`, §7). Invariant test suite (`src/lib/components/ui/invariants.test.ts`). |
> | **NOT BUILT** | Vendored Fraunces/Inter woff2 — the `@font-face` rules are commented out and both fall back to system stacks (§2.2). High-contrast mode (§3.1). Pre-paint theme bootstrap and the in-UI theme toggle (§3.1). `--fs-*` type-scale tokens, `--sp-*`/`--r-*` names, `--elev-1..3`, `--series-*` (§2.2, §2.4, §5). Most of the §4 inventory. axe-core in CI and the standalone CSS contrast validator (§1.3, §6.3). |
>
> Where a section describes something unbuilt, read it as a **requirement on the
> eventual implementation**, not a claim about current behavior. Where a section is
> marked BUILT, it describes code you can go read, and the code is the authority — if
> they disagree, that is a bug in one of them and must be fixed, not narrated around.
>
> **One section is normative rather than descriptive: §2.3.6**, the measured
> accessibility corrections. Those are the values the tokens *must* carry, with the
> ratios that justify each. No build gate computes them (§6.3), so they are checked by
> reading `app.css`, not by a green test run.
>
> The flagship Daymark app remains **fully offline, no `INTERNET` permission, no
> server** (see [../PRIVACY.md](../PRIVACY.md)). The Companion lives only in a
> **separate, opt-in flavor** and never alters that default.

**Sibling documents** (relative links):
[COMPANION_README.md](COMPANION_README.md) ·
[COMPANION_SCOPE.md](COMPANION_SCOPE.md) ·
[COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md) ·
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) ·
[COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) ·
[COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) ·
[COMPANION_FEATURES.md](COMPANION_FEATURES.md) ·
[COMPANION_UX.md](COMPANION_UX.md)

Baseline context: [DESIGN.md](DESIGN.md), [FEATURES.md](FEATURES.md),
[INSTRUMENTS.md](INSTRUMENTS.md), [../HANDOFF.md](../HANDOFF.md),
[ARCHITECTURE.md](ARCHITECTURE.md), [PRIVACY.md](PRIVACY.md).

---

## Contents

- [0. The central tension, resolved](#0-the-central-tension-resolved)
- [1. Frontend stack](#1-frontend-stack)
- [2. Visual language — "Modern Paper, Big Screen"](#2-visual-language--modern-paper-big-screen)
- [3. Theming, motion & micro-interactions](#3-theming-motion--micro-interactions)
- [4. Component library inventory](#4-component-library-inventory)
- [5. Charts approach (dependency-light, vendored)](#5-charts-approach-dependency-light-vendored)
- [6. Responsive & accessibility (WCAG 2.2 AA)](#6-responsive--accessibility-wcag-22-aa)
- [7. CSP example the stack satisfies](#7-csp-example-the-stack-satisfies)
- [8. Cross-surface UX consistency](#8-cross-surface-ux-consistency)
- [9. Build-ready checklist](#9-build-ready-checklist)

> **Scope of this doc.** This is the build-ready contract for the **look & feel** of
> the self-hosted Companion's web surfaces: the **expanded user features**
> (questionnaire/instrument engine + sit-down cognitive/attention testing) and the
> **security-as-a-feature** UX, plus the cross-surface UX shared with the therapist
> portal (covered in [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md)). Interaction
> flows and copy live in [COMPANION_UX.md](COMPANION_UX.md); feature behavior lives
> in [COMPANION_FEATURES.md](COMPANION_FEATURES.md).

---

## 0. The central tension, resolved

The brief asks for something **genuinely modern and sleek** while honoring a
**zero-third-party, vendored, strict-CSP** posture: `default-src 'self'`, no CDNs,
**no Google Fonts**, no analytics, no third-party origins, and a server that is
**zero-knowledge** and makes **no outbound connections** (see
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) and [COMPANION_SCOPE.md](COMPANION_SCOPE.md)).
Most "modern & sleek" web stacks reach for exactly the things we forbid (CDN-hosted
fonts, runtime component-library CSS, charting libs pulling D3/Chart.js, icon-font
CDNs, analytics-driven design).

**Resolution: move sophistication to build time, not runtime.** A *compiled*
framework (Svelte) plus a *bundling* build (Vite) lets us author with a modern DX and
ship a small, auditable, **fully self-contained** set of `'self'` assets. The polish
lives in **design tokens, typography, motion, and craft** — none of which require a
single third-party origin at runtime. Concretely:

| The tempting "modern" thing | Why it's forbidden here | What we do instead |
|---|---|---|
| Google Fonts / Fontsource CDN | third-party origin; `font-src` leak; phones home | **Vendor** Fraunces + Inter variable woff2, subset, `'self'` only |
| Tailwind/Bootstrap via CDN | CDN origin; `style-src` from elsewhere | Tailwind compiled **at build time** into one vendored `app.css` (or hand-authored token CSS) |
| React/Vue runtime + hydration | larger bundle, runtime eval pressure, often wants `unsafe-eval` | **Svelte** compiles components to imperative DOM ops — no VDOM runtime to ship |
| Chart.js / D3 / ECharts | heavy deps, large bundle, audit burden | **Hand-rolled SVG charts** (a few hundred lines, vendored) |
| Icon fonts / Font Awesome CDN | third-party origin; icon-font a11y issues | **Original inline SVG** sprite, vendored, themeable via `currentColor` |
| Product analytics / RUM | outbound calls; violates no-telemetry | **None.** Ever. |
| `unsafe-inline` styles for convenience | weakens CSP | Hashed/external `'self'` stylesheet only |

The result satisfies the success criterion in
[COMPANION_SCOPE.md](COMPANION_SCOPE.md): *"the web UI loads zero third-party
origins"* and *"static analysis confirms the container makes zero outbound network
calls."* CSP becomes **trivially strict** because there is nothing external to allow.

> **Honesty carried through (per [COMPANION_SECURITY.md](COMPANION_SECURITY.md) R5 /
> §9.4):** CSP and SRI are **not** counted as a zero-knowledge defense against the
> *first-party* origin that serves both the HTML and the assets. They harden against
> third-party tampering only. The design system therefore also owns the **product
> copy** that tells owners *"don't type your master passphrase here — use the phone
> Sync flavor,"* and renders the **fixed, non-server-supplied** non-diagnostic
> banners.

---

## 1. Frontend stack

### 1.1 Recommendation (decisive)

**Svelte 5 + TypeScript, bundled by Vite, output as fully-vendored static assets.**

```
authoring:   Svelte components (.svelte) + TS  ──compile──▶  imperative DOM ops, no runtime framework
styling:     design-token CSS (hand-authored) + optional build-time Tailwind  ──▶  one vendored app.css
fonts:       Fraunces + Inter variable woff2 (subset)  ──vendored──▶  served 'self'
icons:       original SVG  ──build──▶  inlined sprite, vendored
crypto:      libsodium-wasm (vendored .wasm, see §1.4)  ──▶  'wasm-unsafe-eval' only
output:      app/dist/  →  copied into the container image, served as static files by Ktor
```

Why this exact stack:

- **Svelte** has *no runtime framework* — components compile to small, direct DOM
  updates, so the shipped JS is dominated by *your* code, not a library. This makes
  the bundle **small and auditable** (a security feature here, not just perf), and it
  needs **no `unsafe-eval`** in production (no runtime template compilation).
- **TypeScript** gives type-safety for the crypto/state boundaries (wrapped-CEK
  handling, share-bundle schemas, instrument-result shapes) where a silent type error
  could mean a confidentiality or correctness bug.
- **Vite** does tree-shaking, code-splitting, asset hashing, and **vendors every
  dependency into `dist/`** — no runtime `npm`/CDN fetch (satisfies
  [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6 / T6 supply-chain: *"vendored web
  assets in-repo, no runtime npm/CDN fetch"*).
- Static `dist/` is **trivial to ship and audit**: it's a directory of `'self'`
  files; a CI step can assert there are no absolute `http(s)://` origins in the build
  output.

### 1.2 Justification vs. alternatives

| Option | Verdict | Reasoning |
|---|---|---|
| **Svelte + TS + Vite** (chosen) | ✅ | Smallest auditable bundle of the ergonomic options; no VDOM runtime; no `unsafe-eval`; first-class TS; CSP-clean by construction; great DX so the polish is actually achievable. |
| **SolidJS + Vite** | ✅ strong runner-up | Also compiled, tiny, fast, fine-grained reactivity. Slightly larger runtime than Svelte; smaller ecosystem of accessible primitives. A defensible alternative if the team prefers JSX. |
| **Lit / vanilla Web Components + TS** | ◻︎ viable, more labor | Zero-framework, standards-based, excellent longevity and auditability. But you hand-build more (forms, focus management, charts, a11y) — higher effort to reach "sleek." Good if minimalism/longevity is valued over velocity. |
| **React/Next, Vue/Nuxt** | ✗ | Heavier runtime; SSR frameworks invite server complexity that fights the zero-knowledge / static-serve model; hydration patterns sometimes want `unsafe-eval`; bigger audit surface. Over-tooled for a static, in-browser-crypto portal. |
| **Tailwind via CDN / DaisyUI CDN** | ✗ | CDN origin breaks `default-src 'self'`. (Tailwind *compiled at build time* into a vendored stylesheet is fine; the CDN delivery is the problem.) |
| **htmx / server-rendered HTML** | ✗ for the crypto surfaces | The portal must run **client-side crypto** (libsodium-wasm, in-browser decrypt). A server-render-centric approach contradicts the zero-knowledge boundary. |

### 1.3 Bundle-size & auditability targets (build-enforced)

> **STATUS: the CI gates below are a target list, not a description of the pipeline.**
> `.github/workflows/companion.yml` is the authority for what actually runs. The
> `pnpm check` / `pnpm test` oracles are real and run locally; the **CSS-token
> validation + contrast check** in the last bullet does **not** exist (see §6.3), and
> the token contract is currently held by `invariants.test.ts` instead. Treat an
> unbuilt gate as an open item, never as evidence.

- **Budget:** app shell (JS+CSS, gzipped) **≤ ~120 KB** excluding the wasm crypto
  blob; fonts subset to the glyphs actually used (Latin + the few symbols we draw).
  Charts add ~5–10 KB (hand-rolled). libsodium-wasm is the largest single asset and is
  justified by the crypto contract.
- **CI gates (additive to [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6
  supply-chain):**
  - assert **no `http://` / `https://` / `//cdn`** origins anywhere in `dist/` (no
    third-party fetch can sneak in).
  - assert **no `eval(` / `new Function(`** in shipped JS (Svelte prod build won't
    emit them; this catches a regressed dep).
  - assert **no unhashed inline `<script>`** in shipped HTML (see §3.1 / §7).
  - lockfile with hashes; `npm ci --offline` from a vendored cache; **no network in
    the image build's final layer.**
  - SBOM (CycloneDX) over the *build* deps; the *runtime* image ships only static
    files + Ktor, no Node, no package manager.
  - byte-for-byte **reproducible `dist/`** so the served bundle can be matched to
    source (supports the "pinned/installed or OOB-pinned bundle" mitigation in
    [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §9.4).
  - **CSS-token validation + contrast check** (see §2.3 / §6.3): a build step parses
    `tokens.css`, rejects malformed color values, and asserts every text/background
    semantic pair and the focus ring meet their stated contrast ratios.

### 1.4 The one allowed CSP relaxation: `wasm-unsafe-eval`

The crypto contract ([COMPANION_SECURITY.md](COMPANION_SECURITY.md) §4) mandates
**libsodium-wasm** in the browser. Instantiating WebAssembly requires
`script-src 'self' 'wasm-unsafe-eval'` (the **narrow, modern** directive — *not* the
blanket `unsafe-eval`). This is exactly the CSP already specified in
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6, so the design system stays
consistent with the committed security posture. **No other relaxation is permitted.**

---

## 2. Visual language — "Modern Paper, Big Screen"

A coherent **sibling** of the flagship app's [DESIGN.md](DESIGN.md) "modern paper"
language — warm, flat, stationery surfaces, hairline rules instead of heavy shadows, a
serif "journal" voice for headings, a clean sans for body/numbers — **adapted for a
large screen and a web feel**: more horizontal layout, denser data, real tables and
charts, generous reading measure, and elevation that reads as *layered paper on a
desk* rather than Material drop-shadows.

### 2.1 Art-direction statement (the north star)

> **Calm, private, trustworthy. Modern but not trendy.**
> Daymark Companion looks like a well-made paper instrument on a clean desk: warm
> off-white sheets, ink-dark text, a quiet serif wordmark, hairline rules, and one
> reserved accent. It is **confident and unhurried** — nothing pulses for attention,
> nothing is "smart," nothing performs. It avoids of-the-moment fashions
> (glassmorphism, neon gradients, oversized blur, motion for motion's sake) that date
> a product in two years. Security is shown as **composure**, not theater: clear
> state, legible language, honest limits. It should feel the same in 2026 and 2036 —
> like good stationery, it **ages well**.

### 2.2 Typography (self-hosted variable fonts)

> **STATUS: PARTLY BUILT.** The font *stacks* ship as `--font-display`, `--font-text`
> and `--font-mono` in `app.css`. The **woff2 binaries are not vendored** —
> `companion/web/fonts/` contains only a README, and the two `@font-face` rules in
> `app.css` are **commented out**, so both faces silently fall back to the system stack
> today. That is deliberate (it keeps the build offline and CSP-clean) but it means the
> product does not currently render in Fraunces or Inter. Dropping the subset files in
> and uncommenting the rules is a separate, network-dependent commit.
>
> The `--fs-*` type-scale tokens below were **never implemented**. Type sizes are
> currently set per-component against the `16px` body base. Either build the scale or
> delete it from this document — do not cite it as though it exists.

Mirrors the flagship's intended pairing ([../HANDOFF.md](../HANDOFF.md) §9 TODO:
**Fraunces** + **Inter**), now as **vendored variable woff2**, served `'self'`, with
system fallbacks so a font failure degrades gracefully.

- **Display / headings / wordmark:** **Fraunces** (variable, OFL). The "journal" serif
  voice; used for page titles, report headings, and the diary-italic accent.
- **Body / UI / data / numbers:** **Inter** (variable, OFL) with `tabular-nums` for
  charts, tables, scores, and timers (so digits don't jitter during the cognitive
  test).
- **Self-hosting rules:** subset to used glyphs; preload the two woff2 files;
  `font-display: swap`; **fallback stacks** so first paint never blocks and a missing
  font still reads as paper.

```css
/* fonts.css — vendored, 'self' only, NO Google Fonts */
@font-face {
  font-family: "Fraunces";
  src: url("/assets/fonts/Fraunces-var.woff2") format("woff2");
  font-weight: 100 900; font-style: normal; font-display: swap;
}
@font-face {
  font-family: "Fraunces";
  src: url("/assets/fonts/Fraunces-Italic-var.woff2") format("woff2");
  font-weight: 100 900; font-style: italic; font-display: swap; /* diary-note voice */
}
@font-face {
  font-family: "Inter";
  src: url("/assets/fonts/Inter-var.woff2") format("woff2");
  font-weight: 100 900; font-style: normal; font-display: swap;
}
:root {
  --font-serif: "Fraunces", Georgia, "Times New Roman", serif;          /* fallback: platform serif */
  --font-sans:  "Inter", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  --font-mono:  ui-monospace, "SFMono-Regular", Menlo, Consolas, monospace; /* fingerprints/SAS codes */
}
```

**Type scale** (fluid, large-screen-friendly; `rem`-based, respects user zoom):

| Token | Size (rem) | Use |
|---|---|---|
| `--fs-display` | 2.75 | report cover, big section titles (Fraunces) |
| `--fs-h1` | 2.0 | page title (Fraunces) |
| `--fs-h2` | 1.5 | section (Fraunces) |
| `--fs-h3` | 1.25 | subsection (Fraunces or Inter 600) |
| `--fs-body` | 1.0 | body (Inter) — base 16px, reading measure ~66ch |
| `--fs-small` | 0.875 | captions, metadata |
| `--fs-mono` | 0.95 | SAS / fingerprint / hashes (mono, `tabular-nums`) |

### 2.3 Color system & design tokens

> **STATUS: BUILT.** Lives in `companion/web/src/app.css` — one file, both tiers, all
> three theme states. That file is the authority; this section describes it. The
> invariants below are enforced by `companion/web/src/lib/components/ui/invariants.test.ts`.

#### 2.3.0 The generating rule: **cool chrome, warm content**

One sentence generates the whole palette, and every token below is a consequence of it:

> **Chrome is cool, dense and monospaced. A person's content stays warm paper.**

**Chrome** is the machine talking about itself — navigation rails, top bars, table
heads, timestamps, IDs, digests, counts, admin and console surfaces. It sits on a
grey-blue ground (`--chrome*`), labels itself in tracked-out uppercase mono
(`--chrome-soft`), and takes its structure from `--indigo`. Chrome **recedes**; it is
never the subject.

**Content** is the warm "Modern Paper" palette mirrored from the flagship's
`ui/theme/Color.kt` — entries, moods, notes, answers, scores. Warm ink on warm paper,
generous spacing, display type. Content **is** the subject.

This is what fixes the "the whole UI is one colour" problem at the root rather than by
adding hues: the interface and the data now live on visibly different grounds, so a
person's mood can be coloured without the surrounding furniture competing with it.

#### 2.3.1 Invariant 1 — **the mood ramp is DATA ONLY**

`--mood-1..--mood-5` and `--mood-1-wash..--mood-5-wash` encode **a person's reported
experience and nothing else.** They are never borrowed to signal interface state —
selection, hover, validity, progress, severity, success. Interface state is the job of
`--chrome-*`, `--indigo-*`, `--clay*` and `--amber*`.

**The classification rule.** Every `var(--mood-*)` outside a chart is exactly one of
two things, and there is no third category:

- **DATA** — a score, a band, a mood dot, a trend, a person's own answer rendered back
  to them. It keeps the ramp.
- **STATE** — a banner, a warning, an error, a selected tab, a link, a focus ring, a
  border on a callout, a confirmation. It moves to chrome/indigo/clay/amber.

If you cannot tell which one you are looking at, read what renders it.

**The test behind it:** `companion/web/src/lib/components/ui/invariants.test.ts`,
group **(c) "the mood ramp is data, and BandTag is its only consumer"**. It asserts, on
comment-stripped source:

- `BandTag.svelte` actually references all five solid ramp tokens — so the suite cannot
  be satisfied by *deleting* the ramp's owner and leaving it unused and unowned;
- no other file in `src/lib/components/ui/` names a `--mood-*` token;
- no other file in `ui/` names `--mood-4` or `--mood-5` even in a comment — the green
  end is what a hurried commit reaches for when a row needs to look "good".

**Scope, stated honestly.** That test polices `ui/` only. Outside `ui/` the ramp is
legitimately used by the surfaces that render a person's data — `charts/Sparkline.svelte`,
`Dashboard.svelte`, `Overview.svelte`, `QuestionnaireRunner.svelte` — and a blanket
"only BandTag" grep over the whole tree would be wrong, not stricter. Those sites each
carry an in-file `/* DATA — do not migrate */` comment naming why. Extending machine
enforcement past `ui/` needs a data/state classifier the test does not have; until then
the boundary is `ui/` plus code review.

#### 2.3.2 Invariant 2 — **there is deliberately NO `--success` token**

The original §2.3 mapped semantic colour straight onto the mood ramp
(`--danger: var(--c-mood-1); --success: var(--c-mood-5); --warning: var(--c-mood-2)`).
The implementation rejected that mapping and this section is the amendment.

**Why there is no green:** [COMPANION_UX.md](COMPANION_UX.md) §10.1 forbids painting
the trust strip green because served portal JS is lower-assurance regardless of network
state — and a green tick anywhere else is the same overclaim one layer down.

A green mark is a claim: *we checked, it held, you may stop reading.* This product is
not in a position to make it. So **"completed", "verified", "confirmed" and "done" are
solid ink** — `--ink-text` / `--ink-accent`. Healthy is the *absence* of colour, not the
presence of green.

Note the ban is on the **name**, not the value: `--mood-5` is green and stays green,
because it means "this person reported a good day", not "the system is fine".
`invariants.test.ts` group **(d)** asserts no token is declared whose name asserts
health (`--success|ok|positive|good|healthy|safe|pass|valid|verified|secure|green…`),
that no token the status ramp is built from *resolves* to a green hue in any of the
three theme states, and that no `ui/` component paints a green literal or CSS colour
keyword. Group **(e)** pins `StatusPill`'s `completed` variant to the ink token.

> **Open contradiction with [COMPANION_UX.md](COMPANION_UX.md) §10, flagged not fixed.**
> That document's §10 reserves a green **`--trust-locked`** token "for surfaces where
> assurance is genuinely high", while its §10.1 then rules that no served page qualifies.
> `--trust-locked` **does not exist** in `app.css` and, under this section, must not be
> added: a token defined for a case the same document says never occurs is a green tick
> waiting for someone to find a use for it. COMPANION_UX.md §10 needs the same amendment
> §2.3 just received. That is a change to a sibling document and is deliberately left to
> its owner rather than made here — but it should not be left long.

#### 2.3.3 The two-tier model is REAL, and where it lives

`companion/web/src/app.css`, in this order:

```
TIER 1 — PRIMITIVES   --c-*     raw hex, both themes side by side, named for what
   ↓                             they ARE. This is the layer mirroring Color.kt.
TIER 2 — SEMANTIC     role names, named for what they are FOR. Components
                       reference ONLY these.
```

Theming is remapping at `:root` / `[data-theme]`, never editing a component. **A
component that reaches for a `--c-*` primitive has skipped the layer where the meaning
lives and should be treated as a bug.**

All three theme states are handled, and `invariants.test.ts` group **(a)** asserts each:
bare `:root` carries the complete light palette; `@media (prefers-color-scheme: dark)`
guarded as `:root:not([data-theme="light"])`; and `:root[data-theme="dark"]` so an
explicit toggle wins in both directions. The two dark blocks are asserted to agree, and
no colour may be defined *only* inside a media or `[data-theme]` block.

#### 2.3.4 The semantic contract

These are the real token names. The old `--bg` / `--surface` / `--text` / `--text-muted`
/ `--accent` / `--elev-1..3` / `--series-*` names in earlier drafts of this document were
never implemented and do not exist; do not reach for them.

**Content — a person's data. Do not repurpose.**

| Token | Role |
|---|---|
| `--paper-bg`, `--paper-sheet` | page ground, sheet |
| `--ink-text`, `--ink-soft`, `--ink-faint` | the three-tier ink scale |
| `--text-subtle` | small print that must still pass AA (see §2.3.6) |
| `--hairline` | decorative separator — **not** a load-bearing edge (§2.3.7) |
| `--border-strong` | every load-bearing control/surface edge, held to 3:1 |
| `--ink-accent`, `--on-accent` | accent fill and its foreground |
| `--elevation` | the layered-paper shadow, held whole |
| `--mood-1..5`, `--mood-1-wash..5-wash` | **DATA ONLY** (§2.3.1) |

**Chrome — navigation, tables, metadata, controls, admin.**

| Token | Role |
|---|---|
| `--chrome`, `--chrome-2` | the cool grey-blue ground and its second step |
| `--chrome-hair` | chrome's divider |
| `--chrome-ink` | chrome body text |
| `--chrome-soft` | the uppercase mono micro-label (`.u-label`) |

**Structural accent — never encodes data.**

| Token | Role |
|---|---|
| `--indigo` | active nav, links, selected item, structural marks, focus |
| `--indigo-deep` | pressed/emphasis |
| `--indigo-wash` | info-callout ground |

`--indigo` is *structure*. It never means "good", never means "this value is high",
never carries a datum. If an indigo surface would change colour because a number
changed, it is the wrong token.

**Alarm and warn — two hues, sharply separated.**

| Token | Role |
|---|---|
| `--clay`, `--clay-wash` | **the single alarm hue**: error, refusal, destructive, revocation, overdue, needs-a-human |
| `--amber`, `--amber-wash` | **warn severity only**: the lower-assurance banner, "Custom" provenance, console warn rows |

There is exactly one red, because **the moment there are two reds, neither means
anything.** `--danger` / `--danger-wash` exist as *aliases* of `--clay` / `--clay-wash`
so code has the role name this document originally promised — they are not a second
alarm hue, and they need no per-theme redefinition because re-pointing `--clay` carries
them.

Amber is **not** alarm, **not** generic "attention", and never a stand-in for success.

**Interaction:** `--focus-ring` (also the `:focus-visible` outline), `--link`.

#### 2.3.5 Pinned severity decisions — already made, do not re-litigate

| Surface | Token | Why |
|---|---|---|
| Fixed **non-diagnostic** caveat / note-strip | `--chrome` ground, `--chrome-ink`/`--text-subtle` text, `--chrome-hair` border, **no coloured left rail** | It is **information, not a warning** — part of the instrument, never "something is wrong". This is what frees amber for genuine warnings. |
| **Lower-assurance** banner | `--amber` / `--amber-wash` | A genuinely degraded assurance path. Earns a severity hue. |
| Error, refusal, destructive action, revocation, "refused to trust the grant" | `--clay` | The single alarm hue. |
| Active tab, selected item, link, focus ring, structural emphasis | `--indigo` | Structure, not data. |
| Success / confirmation / "completed" / "verified" | **solid ink** (`--ink-text` / `--ink-accent`) | Never green. §2.3.2. |
| Charts, `Sparkline`, band tags, mood dots, score bars | the **mood ramp** | That is the point of the ramp. |

The trust strip specifically sits on the **chrome** ground and **may never be painted
green** under any state.

The fixed copy on those surfaces — the non-diagnostic banners, the lower-assurance
banner, the provenance disclaimers, the audit caveats, the crisis/safety copy — is a
**non-server-supplied constant**. Restyling its container is allowed. **Rewording,
shortening, "improving" or reflowing its text is not**, at any layer, including this
document.

#### 2.3.6 The measured accessibility corrections

**This subsection is normative.** The values below are the values these tokens must
carry. They are recorded because they look arbitrary and are not: each was solved
**numerically** — hue and saturation held, lightness walked until the pair cleared its
WCAG threshold — against a ground the product actually renders it on. Reverting any of
them to the prettier original reintroduces a measured, reproducible defect.

An audit found the palette shipped with seven failing pairs and one invariant
violation. Four tokens are involved: `--chrome-soft`, `--clay` and `--amber` *failed*
contrast; `--focus-ring` *collided* with the data ramp. `--border-strong` was found by
the same audit and was the worst of the set.

Each correction must be carried in `app.css` with its reasoning attached — a bare hex
with no note is indistinguishable from a typo and will be "tidied" back. Keep the
per-token table in a `CONTRAST CORRECTIONS` comment block in that file.

Thresholds: **4.5:1** body text (SC 1.4.3) · **3:1** focus indicators and control
boundaries (SC 1.4.11).

| Token | Theme | Before → after | Ratio | Failing ground |
|---|---|---|---|---|
| `--chrome-soft` | light | `#626D7D` → `#586170` | 4.23 → **5.04** | `--chrome` (worst ground 4.55 on `--chrome-2`) |
| `--clay` | light | `#A8574A` → `#9A5044` | 3.93 → **4.50** | `--clay-wash` |
| `--clay` | dark | `#C9806F` → `#CB8473` | 4.33 → **4.51** | `--clay-wash` |
| `--amber` | light | `#9C7128` → `#866122` | 3.53 → **4.52** | `--amber-wash` |
| `--border-strong` | light | `#cbc1ae` → `#9B8864` | 1.56 → **3.01** | `--paper-bg` |
| `--border-strong` | dark | `#4a463c` → `#726B5C` | 1.69 → **3.01** | `--paper-sheet` |
| `--focus-ring` | light | `#5E8A66` → `#3F5F7F` | n/a → **5.37** | `--chrome` (worst ground 4.84 on `--chrome-2`) |
| `--focus-ring` | dark | `#8FA268` → `#7FA3C8` | n/a → **5.99** | `--chrome` (worst ground 4.83) |

Three of these deserve their own sentence:

- **`--chrome-soft`** is every `.u-label` in the app — nav group labels, table headers,
  timestamps. It was below AA on the ground it is defined to sit on.
- **`--amber`** is the "Custom" provenance badge, which renders on *every row* of the
  catalogue. It was the worst failure of the set at 3.53:1.
- **`--focus-ring` was not a contrast failure — it was an invariant violation.** It
  shipped **byte-identical to `--mood-5`** in light (`#5e8a66`) and to `--mood-4` in
  dark (`#8fa268`), so **a focus ring rendered in the exact green of a "rad" mood bar**:
  interface state wearing a person's data, the one thing the ramp forbids. Nobody chose
  this; it was a coincidence that survived because nothing was watching for it, which
  is exactly the argument for §2.3.1 having a test rather than a paragraph. It must
  carry the indigo this document already names for focus — while keeping its **own**
  primitives (`--c-focus-day` / `--c-focus-night`) rather than aliasing `--c-indigo-*`.
  A coincidence of *value* with indigo is fine; a *dependency* on it is not, because
  focus must be free to move without dragging structure — or the data ramp — along
  with it.

**Deliberately not changed**, having been flagged and judged correct:

- `--ink-faint` as text (2.37 light / 3.81 dark). Decorative by contract, mirrors
  `Color.kt`; raising it to 4.5:1 lands it on `--text-subtle` and collapses the
  three-tier ink scale. Components using it as *real* text are component defects, fixed
  by switching those to `--text-subtle`.
- `--mood-*` as a text colour (1.98–4.48). The ramp is a person's data and is fixed by
  invariant 1; recolouring it to pass a check would change what a person's 3 looks like.
  Every site doing this was a **STATE** misuse and migrated to clay/amber/ink.
- `--mood-*` bars and dots as graphical objects (1.98–2.67). `BandTag`, `TrustBar` and
  the caveat banners each render an always-present text label carrying the full meaning,
  with the mark `aria-hidden`. Redundant encoding, so SC 1.4.11's "required to
  understand" does not attach.
- `--hairline` and `--chrome-hair` as rules (1.16–1.39). Dividers and the borders of
  static, text-labelled pills — not controls. See §2.3.7.

#### 2.3.7 The hairline is decorative; load-bearing edges use `--border-strong`

The signature `--hairline` (`#e7dfd1` on `#fcfaf5`) is a **soft, low-contrast
separator** at roughly **1.16:1** — far below 3:1. That is intentional for *visual
grouping* (the "paper" feel), but it means **the hairline alone must never be the only
way to perceive an interactive control or a meaningful surface boundary.** WCAG 2.2
**1.4.11 Non-text Contrast** requires ≥3:1 for the visual indicators of
*user-interface components* and *states*.

The rule:

- **Decorative grouping** (zebra rows, section rules, card-internal dividers) may use
  `--hairline`. `--chrome-hair` is the same contract on the chrome ground.
- **Any control edge or surface whose identification depends on its border**
  (buttons, inputs, textareas, selects, focusable cards, the modal/sheet boundary,
  table cell focus) uses **`--border-strong`**, or carries an additional non-colour cue
  (label, fill, icon).
- We therefore **do not claim** blanket ≥3:1 non-text contrast for `--hairline`; we
  claim it for `--border-strong`, the focus ring, and control states.

`--border-strong` must be `#9B8864` light / `#726B5C` dark, both measured at **3.01:1**
against their surface (§2.3.6). The values this document previously specified
(`#B7AD9B` / `#5A5648`) were never implemented; the values that *were* implemented
(`#cbc1ae` / `#4a463c`) measured **1.56:1** and **1.69:1** — under half the
requirement. Found by audit, not by specification.

This matters more than it looks. Every button, input, textarea and select fills with
`--paper-sheet` on a `--paper-bg` page — a ratio of **1.10:1** — so the border is the
*sole* visual evidence that they are controls at all. At 1.56:1 the product was, in
effect, drawing invisible controls on invisible sheets.

> **Not enforced by a build gate.** §1.3 and §6.3 describe a CSS-parsing contrast
> validator in CI. It does not exist. `invariants.test.ts` pins *token values* in all
> three theme states, which catches a silent re-point of an existing token but not a
> newly-introduced failing pair, and computes no ratios. **Consequence for the reader:
> do not infer from a green `pnpm test` that §2.3.6 holds — check the values.**
> `cd companion/web && pnpm check && pnpm test` is the current pair of oracles;
> building the validator is open work.

> **Mood-color invariant preserved.** As in [DESIGN.md](DESIGN.md) /
> [../HANDOFF.md](../HANDOFF.md), mood colors are a fixed, separate set, **never**
> algorithmically recolored, and custom owner mood labels/colors (which ride in
> `BackupData`) override the defaults at render time — the web reads them from the
> decrypted snapshot exactly as the app reads `LocalMoodColors`. Reports must use the
> owner's own palette, not these defaults, when present.

### 2.4 Spacing, scale, radius, elevation

> **STATUS: BUILT, under different names than this section originally specified.** The
> shipped names are below; `--sp-*`, `--r-sm/md/lg/xl/pill`, `--measure`,
> `--container-max` and `--hairline-w` do **not** exist. Neither do `--elev-0..3` — the
> shadow ships as a single `--elevation` token held whole (the shadow *is* the
> primitive, so it cannot be half-applied).

```css
/* app.css — themeless foundations, declared once, never redefined per theme */
:root {
  /* 4px base spacing scale */
  --space-1: 0.25rem; --space-2: 0.5rem; --space-3: 0.75rem; --space-4: 1rem;
  --space-5: 1.5rem;  --space-6: 2rem;   --space-8: 3rem;
  /* restrained radii — sibling of Shape.kt */
  --radius: 0.625rem; --radius-sm: 0.375rem;
  /* layout */
  --maxw: 64rem;      /* big-screen content cap */
}
```

There is no `--space-7`; the scale skips from `2rem` to `3rem` deliberately rather than
carrying a step nothing uses. A reading-measure token (`--measure`) and a pill radius
are still worth adding when a surface needs them — they are absent, not rejected.

**Elevation philosophy:** the signature surface is the web analogue of `PaperSurface`
— a flat sheet with a **1px border** and *at most a whisper* of shadow; **no drop
shadow in dark mode** (the dark `--elevation` is near-black and reads as depth, not as
a Material tint). Depth comes from borders (`--border-strong` where the edge is
load-bearing), the paper/sheet value shift, the chrome/content ground shift, and a
single modal scrim — never tonal Material elevation tints.

### 2.5 Iconography (vendored, original)

- **Original 24×24 stroke-style SVGs**, the web continuation of the app's hand-drawn
  `ic_*` set (mood faces, activities, nav/UI glyphs), **GPL-3.0 with the project** —
  *no third-party icon packs, no icon fonts, no emoji* (consistent with
  [DESIGN.md](DESIGN.md) and the licensing discipline in [../HANDOFF.md](../HANDOFF.md)
  §10).
- Shipped as a **build-inlined SVG sprite** (`<symbol>` + `<use>`), or as Svelte
  components. Color via `currentColor` so one asset serves light/dark/high-contrast.
- Every meaningful icon has an accessible name; decorative icons are
  `aria-hidden="true"`. Icon-only buttons carry a visible-on-focus label or
  `aria-label`.

### 2.6 Imagery

Minimal and **original**. No stock photography (third-party origin + tone risk on a
mental-health surface). The only "imagery" is: the Fraunces wordmark, original SVG
spot illustrations (a continuation of the app's hand-drawn pose/badge style if
needed), the mood-tinted dataviz, and the dark **night-sky** surface reused for the
report cover / "year in stars" parity. Everything is `'self'` and license-clean.

---

## 3. Theming, motion & micro-interactions

### 3.1 Theme modes

| Mode | Mechanism | Status |
|---|---|---|
| **Light** | bare `:root`, or `[data-theme="light"]` opting out of the dark media query | **BUILT** — the paper identity |
| **Dark** | `@media (prefers-color-scheme: dark)` guarded as `:root:not([data-theme="light"])` | **BUILT** |
| **Dark, explicit** | `:root[data-theme="dark"]` | **BUILT** — redefines all 30 themed roles, so a toggle wins in both directions |
| **System** | no attribute | **BUILT** — the default on first load |
| **High-contrast** | `[data-contrast="high"]` and/or `@media (prefers-contrast: more)` | **NOT BUILT** — see below |
| **Reduced-motion** | `@media (prefers-reduced-motion: reduce)` | **BUILT** — global, in `app.css` |

`:root[data-theme="light"]` and `:root[data-theme="dark"]` also set `color-scheme` so
the UA draws matching form controls and scrollbars; bare `:root` declares
`color-scheme: light dark`.

> **The explicit-dark block used to be a trap.** Before the token work,
> `:root[data-theme='dark']` set `color-scheme: dark` **and nothing else** — so an
> explicit dark choice produced the *light* palette with dark form controls. The in-UI
> toggle a comment described as "future" would not have worked. It now redefines every
> themed role. There is still **no toggle UI**; the mechanism is ready and unwired.

> **High-contrast mode is not implemented.** There is no `prefers-contrast` block and no
> `[data-contrast]` attribute anywhere in `companion/web/src`. The block this section
> previously showed — and the tokens it named (`--text-muted`, `--hairline-w`) — never
> existed. Nothing in the product currently responds to a user's high-contrast
> preference. Recorded as open work, not as a delivered mode, and §6.2's "high-contrast
> mode pushes toward AAA" is a goal, not a fact.

> **The pre-paint theme bootstrap is not implemented.** There is no
> `/assets/theme-bootstrap.js` and no `localStorage` theme persistence; `index.html`
> loads exactly one module, `/src/main.ts`. The design remains as stated — when a
> toggle is built, the bootstrap ships as an **external `'self'` module** loaded
> synchronously in `<head>`, *not* an inline script, so the committed CSP needs no
> script hash and no `unsafe-inline`. (If a future build inlines it for a round-trip
> saving, it MUST be a **CSP-hashed** inline script — `script-src 'self' 'sha256-…'` —
> with the hash committed alongside the header.) The external-module path stays the
> default so §7's header carries no per-build hashes.

### 3.2 Motion (CSS-only, no JS animation libraries)

> **STATUS: PARTLY BUILT.** The global `prefers-reduced-motion` block ships in
> `app.css` exactly as written below and covers every element. The **motion tokens do
> not exist** — no `--ease-standard`, `--ease-entrance`, `--dur-fast/base/slow`, no
> `.route-enter`. The few transitions in the product are hand-written per component
> (e.g. `transition: background 120ms ease` on `button`). Lifting them onto tokens is
> open work; the reduced-motion guarantee holds regardless, because the global block
> neutralises durations rather than relying on each site opting in.

Per the brief: **tasteful motion via CSS, no heavy JS animation libs.** This mirrors
the app's "purposeful directional slide (~240ms, FastOutSlowIn)" from
[DESIGN.md](DESIGN.md).

```css
:root {
  --ease-standard: cubic-bezier(.2,0,0,1);   /* ≈ FastOutSlowIn */
  --ease-entrance: cubic-bezier(.05,.7,.1,1);
  --dur-fast: 120ms; --dur-base: 200ms; --dur-slow: 280ms;
}
/* directional route transition, sibling of the app's shared-axis slide */
.route-enter { animation: slide-in var(--dur-slow) var(--ease-entrance) both; }
@keyframes slide-in { from { opacity:0; transform: translateX(12px); } to { opacity:1; transform:none; } }

/* honor reduced-motion EVERYWHERE — essential motion only */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration:.001ms !important; animation-iteration-count:1 !important;
                           transition-duration:.001ms !important; scroll-behavior:auto !important; }
}
```

Micro-interactions kept **calm and meaningful**: button press (subtle scale), focus
ring fade-in, hairline-underline on links, gentle fade on data updating, a soft
progress sweep on the questionnaire stepper. **Nothing pulses or bounces** — that would
read as "smart"/attention-seeking and contradict the art direction and the
[../HANDOFF.md](../HANDOFF.md) "no streak-shaming, gentle by design" ethos.

> **Cognitive-test caveat:** the sit-down attention/CPT-style task
> ([../HANDOFF.md](../HANDOFF.md) §9 Phase 2) needs **precise, frame-stable** stimulus
> timing and may **bypass** decorative motion entirely (it has its own controlled
> render loop), while still respecting `prefers-reduced-motion` for *non-stimulus*
> chrome. Timing accuracy is a correctness requirement, not a flourish.

---

## 4. Component library inventory

All components are token-driven (no hardcoded color/spacing), keyboard-operable, and
themed via semantic tokens. Grouped by purpose.

> **STATUS: a first cut exists.** `companion/web/src/lib/components/ui/` contains
> **eleven** primitives and nothing else from this inventory. They were chosen as what
> the redesign's six screens actually need, not as an attempt at the whole list.
>
> | Built (`ui/`) | Notes |
> |---|---|
> | `AppShell.svelte` | Rail + topbar + body, owning the stacking context (see §4.9). |
> | `NavRail.svelte` | Grouped nav; `disabled` is a property, so the rail shows its limits rather than hiding them. |
> | `PageHeader.svelte` | Title + `children` + `trailing` slot. |
> | `Card.svelte` | The paper sheet: `title`, `tone: default \| quiet`, header/footer slots; the footer is the note-strip the fixed caveats sit in. |
> | `Callout.svelte` | `tone: info \| warn \| critical` — the banner substrate. Info = `--indigo-wash`, warn = `--amber-wash`, critical = `--clay-wash`, each with a form signal and a visually-hidden severity prefix so severity never rests on hue. |
> | `Chip.svelte` | `tone: neutral \| accent \| warn \| critical`. Mono, uppercase, tabular. |
> | `StatusPill.svelte` | The seven assignment lifecycle states. `completed` is **ink**, per §2.3.2. `declined` and `noResponse` are drawn differently on purpose — silence is not refusal. |
> | `ProvenanceBadge.svelte` | Validated / Adapted / Custom, from `instruments/provenance.ts`. "Custom" is the amber case. |
> | `BandTag.svelte` | **The only `ui/` primitive licensed to touch the mood ramp** (§2.3.1). |
> | `DataTable.svelte` | Mono uppercase heads, `tabular-nums`, card-collapse on narrow. |
> | `EmptyState.svelte` | `title` + optional body and action. |
>
> Shipped alongside them: `index.ts` (barrel), and `status.ts` / `nav.ts` / `table.ts`,
> which exist as plain `.ts` because a Svelte 5 instance script cannot export types.
>
> **Everything else in §4 is unbuilt as a library primitive.** Nothing named below
> exists in `ui/` unless it is in the table above. Several §4 *behaviours* do exist as
> one-off feature components elsewhere in `src/lib/components/` — `QuestionnaireRunner`,
> `AttentionTask` (the `CognitiveTaskRunner` of §4.8), `TrustBar`, the owner and
> therapist `NonDiagnosticBanner` / `LowerAssuranceBanner` pairs, `ShareBuilder`,
> `GrantManager`, `AuditList`, `InvitePanel`, `StepUpDialog`, `Sparkline` — but those
> are screens, not a component library, and they are not interchangeable, reusable or
> individually documented. Do not read their existence as §4 being delivered.

### 4.1 Primitives & layout
- `PaperSurface` (the signature card: `--border-strong` edge, optional whisper shadow,
  `--radius`), `Sheet`, `Stack`/`Cluster`/`Grid` layout helpers, `Divider` (decorative
  hairline), `Container` (capped to `--maxw`), `VisuallyHidden`, `SkipLink`.

### 4.2 Actions & inputs
- `Button` (variants: **primary** = accent fill, **secondary** = `--border-strong`
  outline, **ghost**, **danger** = revoke/delete), `IconButton`, `LinkButton`.
- `TextField`, `TextArea`, `NumberField` (tabular-nums), `Select`, `Combobox`,
  `Checkbox`, `Radio`, `RadioScale` (the questionnaire 0–3 / 0–5 option row), `Switch`,
  `Slider`, `SegmentedControl` (Week/Month/Year-style toggles), `DateRangePicker`
  (share curation), `FileDropZone` (the **Phase 0 drag-in report viewer** — drag a
  backup JSON, decrypt in-browser, render).
- All inputs: visible label, `aria-describedby` help/error, error text +
  `aria-invalid`, no color-only error signaling; all control edges meet ≥3:1
  (`--border-strong`).

### 4.3 Containers & navigation
- `AppShell` (responsive: side nav on wide, top bar + drawer on narrow), `NavRail`,
  `Tabs`, `Breadcrumbs`, `Accordion`, `Card`, `StatCard` (web sibling of the app's
  `StatCard`), `EmptyState`, `Banner`/`Callout` (info/warn/danger), `Toast`/`Snackbar`
  (with the app's undo pattern where relevant).

### 4.4 Overlays
- `Modal`/`Dialog` (focus-trapped, `Esc` to close, `aria-modal`, raised with
  `--elevation` over a scrim),
  `ConfirmDialog` (used for **revoke share**, **delete**, **key rotate** — high-stakes,
  requires explicit confirm), `Drawer`, `Tooltip` (non-essential info only; never the
  sole carrier of meaning), `Popover`.

### 4.5 Data display
- `Table` (sortable, sticky header, zebra via `--chrome-2` — a table is chrome,
  not content — responsive card-collapse
  on narrow, full keyboard + SR semantics), `DefinitionList` (key/value, e.g. share
  metadata), `Badge`/`Chip` (activity tags, bands), `KeyValueGrid`,
  `Timeline`/`EntryRow` (web sibling of the app timeline), `ScorePill`/`BandTag`
  (renders a check-in score **with its non-clinical caveat**).

### 4.6 Charts / dataviz (reports) — see §5
- `LineChart`, `BarChart`, `MoodCalendarGrid` (month, mood-tinted), `YearInPixelsGrid`,
  `YearInStars` (night-sky parity), `ConsistencyHeatmap` (single-accent, distinct from
  mood grid — matches [FEATURES.md](FEATURES.md)), `DistributionBar`, `Sparkline`,
  `TrendChart`. All hand-rolled SVG, all with a text/table fallback (`<table>` + `aria`
  description) so dataviz is screen-reader accessible.

### 4.7 Security-as-a-feature components (first-class, the "(4)" pillar)
- `FingerprintDisplay` / `SasCodeBlock` — renders the 4–6 word BLAKE2b SAS / QR in
  **mono**, large, with a **compare-and-confirm** affirmative action; designed so OOB
  verification is *easy to do and hard to skip* (per
  [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) §5.2).
- `PairingWizard` — stepwise mutual-OOB-pairing flow with clear "verified ✓ / not
  verified" states.
- `ShareScopeBuilder` — date range + record-type + per-record exclude + note-strip,
  with a **live "what the therapist will see" preview** and the structural-absence of
  the self-harm item made visible.
- `RevokeControl`, `ExpiryCountdown`, `AccessLogTable` (events-not-content, IP off by
  default), `SessionBadge` (idle/absolute timeout indicators).
- `SecurityCallout` / `LowerAssuranceBanner` — the **fixed, non-server-supplied**
  honesty surfaces: *"This browser portal is a lower-assurance convenience path. Don't
  type your master passphrase here — use the phone Sync app."* and the non-diagnostic
  banner.
- `NonDiagnosticBanner` — fixed copy, present on **every** instrument/report/share
  surface: *"Self-check, not a diagnosis; scores are not clinical thresholds."*

### 4.8 Instrument / test-runner components (the "(2)" pillar)
- `QuestionnaireRunner` — drives a license-clean instrument (the bundled PHQ-9 /
  GAD-7 / WHO-5, plus self-authored sleep self-checks; future ASRS v1.1 / IPIP per
  [../HANDOFF.md](../HANDOFF.md) §9 — **never** TOVA/Conners/CAARS, per
  [INSTRUMENTS.md](INSTRUMENTS.md) and [COMPANION_SCOPE.md](COMPANION_SCOPE.md)).
  - `QuestionCard`, `RadioScale`, `ProgressStepper`, `BackNext`, `AutosaveIndicator`
    (in-memory; persisted only into the E2EE snapshot), `ResultSummary` (score + band
    **+ fixed non-diagnostic caveat**, in-app attribution/citation per
    [INSTRUMENTS.md](INSTRUMENTS.md)).
  - **Scores-only invariant** enforced in the result model: store **score + band**,
    never individual item answers; **PHQ-9 item-9 / self-harm scoring structurally
    absent** from the wire schema (per [COMPANION_SCOPE.md](COMPANION_SCOPE.md),
    [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) §2). If item-9 is non-zero the UI
    shows the **offline crisis-resources** flow — never a risk verdict, never an
    auto-escalation ([../HANDOFF.md](../HANDOFF.md) §0).
- `CognitiveTaskRunner` — the sit-down attention/CPT-style task that *doesn't fit on a
  phone* ([../HANDOFF.md](../HANDOFF.md) §9 Phase 2). Big-screen, keyboard/spacebar-
  driven, **frame-accurate stimulus timing**, fixation point, practice block, fixed
  instructions (human-written, no generated content — [../HANDOFF.md](../HANDOFF.md)
  §0), results stored as aggregate metrics into the snapshot, **framed
  non-diagnostically** ("an attention exercise, not a clinical test"). Honors
  reduced-motion for chrome, controls timing itself for stimuli.

### 4.9 The stacking context belongs to `AppShell`

A hovered cell that lifts with `z-index` will paint its shadow over the top bar and the
rail unless the body isolates its descendants. `AppShell` owns this and must keep
owning it:

```
topbar { position: relative; z-index: 6 }
rail   { z-index: 7 }
body   { position: relative; z-index: 1; isolation: isolate }
```

Asserted by `invariants.test.ts` group **(f)**: the three rules are present, the body
isolates, and the rail sits above the header which sits above the body. It is recorded
here because it is the kind of fix that gets "tidied" out by someone who cannot see
what it prevents.

Each component ships with: states (default/hover/focus/active/disabled/error/loading),
light and dark tokens applied, keyboard map, and SR semantics. (High-contrast tokens
are listed in §3.1 as a mode that does **not** exist yet, so no component applies them.)

---

## 5. Charts approach (dependency-light, vendored)

**Hand-rolled SVG, no charting library.** Reports need: line trend, bar distribution,
mood-tinted calendar grids, year-in-pixels, year-in-stars, consistency heatmap,
sparklines (mirroring [FEATURES.md](FEATURES.md) Insights). These are *simple
geometric mappings*, not interactive analytics dashboards, so a charting lib
(Chart.js/D3/ECharts: large, audit-heavy, sometimes CDN-default) is unjustified.

> **STATUS: PARTLY BUILT, and the token story changed.** Hand-rolled SVG is real —
> `charts/Sparkline.svelte` plus inline SVG in `Dashboard.svelte` and `Overview.svelte`.
> **`--series-1..5` were never implemented and should not be.** A chart of a person's
> mood is *data*, so it is drawn on the **mood ramp** — that is invariant 1 working as
> intended, not a violation of it: an "awful" bar has to be the colour an "awful" mood
> is everywhere else in the product, because in these charts **the ramp is the legend**.
> Recolouring them to a neutral series palette would delete the legend.
>
> A `--series-*` set is still the right answer for any future chart plotting something
> that is *not* mood (counts, durations, adherence). It does not exist yet; when it is
> added it must be low-chroma, CVD-mindful, and **must not** overlap the ramp's meaning.
> `--text-muted` in the snippet below is likewise not a token — the shipped name is
> `--ink-soft`.

Design:

- A tiny **scale/axis helper** (~150–250 LOC TS: linear scale, nice-ticks, path
  builder) feeds Svelte components that emit **plain SVG** styled by tokens (the mood
  ramp for mood series, `--border-strong` / `--hairline` for axes, `--ink-soft` for
  labels).
- **Accessibility-first:** every chart has (a) a `role="img"` + `aria-label` summary,
  **and** (b) an associated visually-hidden or toggle-revealable `<table>` of the
  underlying numbers — so the data is never trapped in pixels. This also makes the
  PDF/print path trivial.
- **Theming:** colors come only from tokens, so charts retheme with
  light/dark/high-contrast automatically. Series colors are low-chroma and chosen to
  stay distinguishable in grayscale/CVD (shape/label backup, never color-only).
- **Motion:** an optional one-shot reveal on draw, gated by `prefers-reduced-motion`.
- **Print/report:** the same SVG renders to a clean printable report (`@media print`
  flattens to paper-white, ink-black, hairline rules) — the web analogue of the app's
  PDF report.

```svelte
<!-- LineChart.svelte (sketch) — pure SVG, tokens, a11y table fallback -->
<figure role="group" aria-label={summary}>
  <svg viewBox={`0 0 ${w} ${h}`} role="img" aria-label={summary}>
    <!-- DATA. A mood series is drawn on the mood ramp; see the status note above. -->
    <path d={linePath} fill="none" stroke="var(--mood-5)" stroke-width="2"
          vector-effect="non-scaling-stroke" />
    <!-- baseline in var(--hairline); labels in var(--ink-soft), tabular-nums -->
  </svg>
  <table class="visually-hidden">…underlying values for screen readers…</table>
</figure>
```

---

## 6. Responsive & accessibility (WCAG 2.2 AA)

### 6.1 Responsive
- **Mobile-first, scales up to a desk monitor.** Breakpoints via CSS `min-width` +
  container queries: narrow (drawer nav, stacked cards, tables collapse to cards),
  medium (two-column), wide (side nav + content capped at `--maxw`,
  multi-column report). The *expanded user features* assume a large screen but never
  break on a phone-width browser.
- Fluid type/space; everything in `rem` so OS/browser zoom and large-font settings
  work (WCAG 1.4.4 / 1.4.10 reflow to 320px, no loss of content at 200% / 400% zoom).

### 6.2 Accessibility targets (WCAG 2.2 AA, with relevant 2.2-new criteria)
- **Contrast:** body text ≥ 4.5:1, large text ≥ 3:1, **non-text/UI control & state
  indicators ≥ 3:1** (1.4.3, 1.4.11) — delivered by `--border-strong`, the focus ring,
  and control fills, **not** by the decorative `--hairline` (see §2.3.7).
  **The palette was not "chosen to pass" — it was audited and several tokens failed.**
  `--chrome-soft`, `--clay`, `--amber` and `--border-strong` were all below their
  threshold on grounds the product actually renders them on, and `--focus-ring`
  collided byte-for-byte with the mood ramp. The measured before/after ratios and the
  reasoning for each are in **§2.3.6**, which is normative: those are the values the
  tokens must carry, and reverting one reintroduces a specific, reproducible defect.
  High-contrast mode does **not** exist (§3.1), so nothing currently pushes toward AAA.
- **Keyboard:** every interaction reachable and operable by keyboard; logical tab
  order; no traps except intentional modal focus-traps with `Esc` exit (2.1.1, 2.1.2).
  Roving tabindex for `RadioScale`, `Tabs`, `Table`.
- **Focus visible & not obscured:** a strong `--focus-ring` (≥3:1), and sticky
  headers/toasts must not hide the focused element (WCAG 2.2 **2.4.11 Focus Not
  Obscured**). Focus ring uses `:focus-visible`.
- **Target size:** interactive targets ≥ 24×24px (WCAG 2.2 **2.5.8**); the mood picker
  / option rows use the app's generous-tap-target pattern.
- **Dragging alternatives:** the share-scope `DateRangePicker` and any drag
  interaction provide a non-drag (input/buttons) alternative (WCAG 2.2 **2.5.7**).
- **Consistent help & no redundant entry:** help/disclaimer placement consistent
  across surfaces (2.2 **3.2.6**); the pairing/enroll flow doesn't force re-entering
  info already provided (2.2 **3.3.7**).
- **Accessible authentication:** WebAuthn passkey path satisfies WCAG 2.2 **3.3.8** (no
  memory/transcription puzzle); the SAS *comparison* is recognition (compare two
  displayed strings), not recall.
- **Screen reader:** semantic HTML first, ARIA only to fill gaps; live regions for
  async results (instrument scored, share published, "verified ✓"); charts have
  text/table equivalents (§5); icons labeled or hidden.
- **Motion/seizure:** no flashing > 3/s (2.3.1); all decorative motion gated by
  `prefers-reduced-motion`; the cognitive task warns and offers settings before any
  rapid stimulus.
- **Reduced reliance on color:** bands/series/errors always carry text/shape too
  (1.4.1).

### 6.3 Built-in a11y & token verification

**What exists.** `companion/web/src/lib/components/ui/invariants.test.ts` — a source-
reading suite, run by `pnpm test`, that is the working gate on the token contract. It
asserts, in groups:

| Group | Asserts |
|---|---|
| *subject* | the `ui/` directory, its components and `app.css` were actually found — so no assertion below can pass on an empty set |
| **(a)** | all three theme states defined; bare `:root` carries every token; both dark blocks redefine every themed role and agree; no colour defined only inside a media/`[data-theme]` block; chrome/indigo/clay/amber carry their contract values; the solid mood ramp is identical in every theme |
| **(b)** | no `ui/` component hardcodes a hex |
| **(c)** | the mood ramp is data — `BandTag` uses it, nothing else in `ui/` names it (§2.3.1) |
| **(d)** | no `--success`-shaped token name is declared; no status token *resolves* to a green hue in any theme; no `ui/` component paints a green literal or keyword (§2.3.2) |
| **(e)** | `StatusPill`'s `completed` is ink, and carries its meaning in form and words, not hue |
| **(f)** | the `AppShell` stacking-context fix survives (§4.9) |

**Every guard proves its subject exists before filtering it.** This is the point, not a
flourish: a grep-shaped assertion's worst failure mode is going green because it matched
nothing, and a green test removes the appetite to write a real one. The suite was
**mutation-tested** — injecting `var(--mood-4)` into `Card.svelte` fails two tests,
injecting `#22c55e` fails two others, and both mutations were confirmed to have applied
to the file before the suite ran. A first attempt patched a selector that did not exist,
the suite passed, and that pass meant nothing. **Any future change to these tests must
be re-proven the same way.**

Token *preservation* across the two-tier refactor was checked in a **real CSS engine** —
three pages rendered in headless Chromium and read back with `getComputedStyle` — not by
parsing. A hand-rolled resolver reported 18 false mismatches because it matched `:root`
by first occurrence and collapsed all three blocks onto the primitives.

**What does not exist.** Both of these are open work and neither should be cited as a
current guarantee:

- **axe-core in CI.** Not present. There is no rendered-route accessibility run and no
  component-rendering harness in the project at all; every assertion above is a property
  of source text. Manual keyboard + SR passes remain a release gate on paper only.
- **The standalone CSS token validator** of §1.3 — the build step that parses
  `app.css`, rejects malformed colour values, and *computes* contrast for every
  load-bearing pair. `invariants.test.ts` pins known token **values**, which catches a
  silent re-point of an existing token but not a newly-introduced failing pair, and
  computes no ratios. The §2.3.6 ratios were measured by hand during the audit. Until
  the validator exists, that table is the record.

---

## 7. CSP example the stack satisfies

This is the header from [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6, which the
chosen stack meets **without exception** (the only relaxation is the mandated
`wasm-unsafe-eval`; the pre-paint theme bootstrap is an external `'self'` module, so no
script hash is required in the committed header — see §3.1):

```http
Content-Security-Policy:
  default-src 'self';
  script-src 'self' 'wasm-unsafe-eval';
  style-src 'self';
  img-src 'self' data: blob:;
  font-src 'self';
  connect-src 'self';
  worker-src 'self';
  object-src 'none';
  base-uri 'none';
  frame-ancestors 'none';
  form-action 'self';
  upgrade-insecure-requests
```

> **`img-src` resolution (must-fix from review).** Decrypted backup snapshots can
> contain owner photos. After in-browser decryption these are materialized as
> **`blob:` object URLs** (`URL.createObjectURL` over a `Uint8Array`), which is the
> efficient, memory-friendly path and is **same-origin / no network**. The committed
> CSP therefore lists **`img-src 'self' data: blob:`** so those images actually
> render. `data:` remains allowed for small inline SVG/data-URI assets. Both `data:`
> and `blob:` here are produced **in the browser from already-decrypted bytes** — they
> introduce **no third-party origin and no outbound fetch**, so they do not weaken the
> zero-third-party guarantee. (If a future build commits to `data:`-only, document the
> larger base64 memory cost and drop `blob:`; we choose `blob:` for the photo path.)

Companion security headers (also from [COMPANION_SECURITY.md](COMPANION_SECURITY.md)
§6), which the design system assumes are present:

```http
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
X-Frame-Options: DENY
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Resource-Policy: same-origin
Permissions-Policy: geolocation=(), camera=(), microphone=()   /* WebAuthn (publickey-credentials-get) allowed */
Strict-Transport-Security: max-age=63072000; includeSubDomains  /* with a real cert */
```

Why the stack satisfies it:
- **No `unsafe-inline` for scripts:** Svelte's production output is external `'self'`
  JS; the pre-paint theme bootstrap is an external `'self'` module (default) or a
  **hashed** inline script (alternative) — never blanket inline. CI asserts no
  *unhashed* inline `<script>` in `dist/`.
- **No `unsafe-inline` for styles:** styles ship as vendored `'self'` CSS; Svelte
  scoped styles compile into the external stylesheet. If any critical inline style were
  ever needed it would be **hashed**, not `unsafe-inline`.
- **`font-src 'self'`:** **no Google Fonts**, no `fonts.gstatic.com` — the directive is
  satisfied trivially today because no webfont is loaded at all (§2.2: the `@font-face`
  rules are commented out pending vendored woff2). It stays satisfied when they land,
  because the files will be served `'self'`.
- **`img-src 'self' data: blob:`:** SVG icons inline or `'self'`; decrypted backup
  photos render as in-browser `blob:` (or `data:`) URLs — **no network** (see note
  above).
- **`connect-src 'self'`:** the only fetches are to the Companion's own API; **no
  telemetry, no third-party, no CDN.**
- **`object-src 'none'`, `base-uri 'none'`, `frame-ancestors 'none'`,
  `form-action 'self'`:** the app embeds no plugins, fixes its base, refuses framing,
  and posts only to itself.

A CI check asserts `dist/` contains **zero external origins** and that the served CSP
has no `unsafe-eval` / third-party hosts — making the *"web UI loads zero third-party
origins"* success criterion ([COMPANION_SCOPE.md](COMPANION_SCOPE.md)) **verifiable**.

---

## 8. Cross-surface UX consistency

- **One token set, three audiences:** owner report viewer, owner share/curation +
  security surfaces, and the therapist portal
  ([COMPANION_THERAPIST.md](COMPANION_THERAPIST.md)) all consume the *same*
  tokens/components, so the system reads as one product with appropriate role-scoping.
  The therapist surfaces get **no softer** non-diagnostic framing
  ([COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) §2).
- **Fixed, non-server-supplied safety copy:** `NonDiagnosticBanner`,
  `LowerAssuranceBanner`, and crisis-resources copy are **baked into the bundle**, not
  fetched, so a hostile operator can't strip them (consistent with
  [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) §2's "fixed UI banners, not
  server-supplied").
- **Security shown as composure:** clear verified/not-verified states, honest expiry
  countdowns, honest "lower-assurance browser path" language, events-not-content access
  log — security is a *legible, calm* part of the UI, the "(4)" pillar.
- **Flows & copy:** detailed interaction flows live in
  [COMPANION_UX.md](COMPANION_UX.md); this doc owns the visual/token contract those
  flows render with.

---

## 9. Build-ready checklist

A box is ticked only if you can go read the thing. Where a line is split, it is split
because half of it shipped and half did not — resist the urge to round up.

- [x] Svelte 5 + TS + Vite under `companion/web/`. — [ ] `dist/` reproducibility, SBOM,
      and the `eval` / external-origin / unhashed-inline assertions as CI gates.
- [x] Runtime image: static files + Ktor only; **no Node, no package manager**; base
      pinned by digest (per [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6).
- [ ] Vendored Fraunces + Inter variable woff2, subset, preloaded, `font-display:swap`;
      **no font CDN**. — `web/fonts/` holds a README; `@font-face` is commented out and
      both faces fall back to system stacks (§2.2). *System fallbacks are in place.*
- [x] Two-tier tokens (primitives → semantic roles), light/dark/system all driven from
      tokens; `--border-strong` (not `--hairline`) carries every load-bearing edge
      (§2.3). — [ ] high-contrast mode (§3.1, not built).
- [x] Reduced-motion honored **globally** in `app.css` (§3.2); cognitive-task stimulus
      timing handled separately. — [ ] motion tokens (`--ease-*`, `--dur-*`) do not exist.
- [x] The mood-ramp-is-data and no-`--success` invariants, enforced and mutation-tested
      (`ui/invariants.test.ts`, §2.3.1, §2.3.2, §6.3).
- [ ] Color-token validator + computed-contrast gate in CI (§1.3, §6.3). The §2.3.6
      ratios were measured by hand; nothing recomputes them on change.
- [ ] Original vendored SVG icon sprite (`currentColor`), GPL-3.0, no third-party
      packs/emoji.
- [ ] Pre-paint theme bootstrap and an in-UI theme toggle (§3.1). The `[data-theme]`
      mechanism is complete and unwired.
- [ ] Component library per §4. **Eleven of it exists** (§4 status table); the rest,
      and per-component state/keyboard-map documentation, does not.
- [x] Hand-rolled SVG charts, no charting library (§5). — [ ] table/aria fallbacks and
      print styles are not uniformly present; verify per chart before claiming them.
- [ ] WCAG 2.2 AA gate: axe-core in CI and a manual SR pass. Neither exists; there is no
      component-rendering harness. Contrast was audited by hand once (§2.3.6).
- [x] CSP exactly per [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6 (`'self'` +
      `wasm-unsafe-eval`; `img-src 'self' data: blob:`), served from
      `companion/server/.../SecurityHeaders.kt`; security headers present.
- [x] **Scores-only** instrument results; **item-9 structurally absent**; offline
      crisis flow, never a verdict; fixed non-diagnostic + lower-assurance banners.
      *(Their copy is a non-server-supplied constant — restyle the container, never the
      prose.)*
- [x] License-clean instruments only ([INSTRUMENTS.md](INSTRUMENTS.md)); attribution /
      citation in-app; no TOVA/Conners/CAARS; cognitive task is **original**,
      non-diagnostic, no generated content ([../HANDOFF.md](../HANDOFF.md) §0).

---

*Sibling docs:*
[COMPANION_README.md](COMPANION_README.md) ·
[COMPANION_SCOPE.md](COMPANION_SCOPE.md) ·
[COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md) ·
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) ·
[COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) ·
[COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) ·
[COMPANION_FEATURES.md](COMPANION_FEATURES.md) ·
[COMPANION_UX.md](COMPANION_UX.md)
