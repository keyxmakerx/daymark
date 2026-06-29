# Daymark Companion — Design System ("Modern Paper, Big Screen")

> ## ⚠️ STATUS: DESIGN ONLY — NO CODE EXISTS YET
>
> **Nothing in this document is implemented.** There is no Companion frontend, no
> Svelte app, no vendored bundle, no design-token CSS, and no chart code in the
> shipping product today. Every stack choice, token, CSS snippet, component, and CSP
> header below describes an **intended, build-ready design contract** to be reviewed
> and built — not a description of working software. Where this document states a
> guarantee, read it as a **requirement on the eventual implementation**, not a claim
> about current behavior.
>
> The flagship Daymark app remains **fully offline, no `INTERNET` permission, no
> server** (see [../PRIVACY.md](../PRIVACY.md)). The Companion, if built, lives only
> in a **separate, opt-in flavor** and never alters that default.

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

The flagship palette is defined in Kotlin (`ui/theme/Color.kt`, restated in
[DESIGN.md](DESIGN.md) and [../HANDOFF.md](../HANDOFF.md) §4). We translate it **1:1**
into platform-agnostic CSS custom properties so the web is an exact-sibling, then add
**web-only** tokens the phone never needed (focus ring, chart series, large-surface
elevation, link).

**Token layering (two tiers):** raw *primitive* tokens (the hex values) → *semantic*
tokens (role names UI uses). UI code references **only semantic tokens**, so theming
is a matter of remapping at `:root` / `[data-theme]`.

```css
/* tokens.css — PRIMITIVES (mirror Color.kt exactly) */
:root {
  /* paper (light) */
  --c-paper:      #F4EFE6;  --c-sheet:    #FCFAF5;
  --c-ink:        #2A2722;  --c-soft:     #6B655B;  --c-faint: #A49C8E;
  --c-hairline:   #E7DFD1;  --c-accent:   #33302A;
  /* night paper (dark) */
  --c-night-bg:   #1B1A17;  --c-night-surface: #24221D;
  --c-night-ink:  #EBE5D8;  --c-night-line:    #34312A;
  /* mood scale awful→rad (NEVER recolored, NEVER dynamic) */
  --c-mood-1: #AE5747; --c-mood-2: #C27C46; --c-mood-3: #C6A24E;
  --c-mood-4: #8FA268; --c-mood-5: #5E8A66;
  /* fixed night-sky surface (Year-in-Stars parity) */
  --c-sky-bg: #16150F; --c-sky-ink: #EBE5D8; --c-sky-faint: #8E887A;
}
```

```css
/* SEMANTIC tokens — light (default) */
:root, [data-theme="light"] {
  --bg:            var(--c-paper);
  --surface:       var(--c-sheet);
  --surface-2:     #FBF7EF;            /* web-only: faintly raised sheet */
  --text:          var(--c-ink);
  --text-muted:    var(--c-soft);
  --text-faint:    var(--c-faint);
  --hairline:      var(--c-hairline);  /* decorative separator — NOT a load-bearing 3:1 boundary; see §2.3.1 */
  --border-strong: #B7AD9B;            /* web-only: ≥3:1 boundary for control/surface edges (WCAG 1.4.11) */
  --accent:        var(--c-accent);
  --on-accent:     var(--c-sheet);
  --link:          #4A5A3F;            /* web-only: an earthy, low-chroma link (≥4.5:1 on paper) */
  --focus-ring:    #3B6FB0;            /* web-only: high-contrast focus, ≥3:1 on paper */
  --danger:        var(--c-mood-1);    /* destructive (revoke/delete) reuses the awful red */
  --success:       var(--c-mood-5);
  --warning:       var(--c-mood-2);
  /* elevation as layered paper, NOT Material shadow */
  --elev-0: none;
  --elev-1: 0 1px 0 var(--border-strong);                 /* visible hairline edge */
  --elev-2: 0 1px 2px rgba(42,39,34,.06), 0 0 0 1px var(--border-strong);
  --elev-3: 0 6px 24px rgba(42,39,34,.10), 0 0 0 1px var(--border-strong); /* modals */
  /* chart series — derived from mood + earthy neutrals, low-chroma, colorblind-mindful */
  --series-1: #5E8A66; --series-2: #C27C46; --series-3: #4A5A3F;
  --series-4: #A98A5B; --series-5: #7A6E60;
}
```

```css
/* SEMANTIC tokens — dark ("night paper") */
[data-theme="dark"] {
  --bg:            var(--c-night-bg);
  --surface:       var(--c-night-surface);
  --surface-2:     #2A2823;
  --text:          var(--c-night-ink);
  --text-muted:    #B9B2A2;
  --text-faint:    #8E887A;
  --hairline:      var(--c-night-line);  /* decorative separator */
  --border-strong: #5A5648;              /* ≥3:1 control/surface boundary on night paper */
  --accent:        var(--c-night-ink);   /* accent inverts, per DESIGN.md */
  --on-accent:     var(--c-night-bg);
  --link:          #A9BE97;
  --focus-ring:    #8FB8FF;
  --elev-1: 0 0 0 1px var(--border-strong);  /* no drop shadow in dark, per DESIGN.md */
  --elev-2: 0 0 0 1px var(--border-strong);
  --elev-3: 0 8px 28px rgba(0,0,0,.45), 0 0 0 1px var(--border-strong);
}
```

System + manual theme switching:

```css
/* default to system, allow explicit override via [data-theme] on <html> */
@media (prefers-color-scheme: dark) {
  :root:not([data-theme]) { /* re-declare the dark semantic block */ }
}
```

#### 2.3.1 The hairline is decorative; load-bearing edges use `--border-strong`

The signature `--hairline` (`#E7DFD1` on `#FCFAF5`) is a **soft, low-contrast
separator** at roughly **1.16:1** — far below 3:1. That is intentional for *visual
grouping* (the "paper" feel), but it means **the hairline alone must never be the only
way to perceive an interactive control or a meaningful surface boundary.** WCAG 2.2
**1.4.11 Non-text Contrast** requires ≥3:1 for the visual indicators of
*user-interface components* and *states*.

Rule, enforced in CI (§1.3, §6.3):

- **Decorative grouping** (zebra rows, section rules, card-internal dividers) may use
  `--hairline`.
- **Any control edge or surface whose identification depends on its border**
  (buttons, inputs, focusable cards, the modal/sheet boundary, table cell focus) uses
  **`--border-strong`** (`#B7AD9B` light / `#5A5648` dark, both ≥3:1 against their
  surface), or carries an additional non-color cue (label, fill, icon).
- We therefore **do not claim** blanket ≥3:1 non-text contrast for `--hairline`; we
  claim it for `--border-strong`, the focus ring, and control states, and the
  contrast CI gate verifies exactly those.

> **Mood-color invariant preserved.** As in [DESIGN.md](DESIGN.md) /
> [../HANDOFF.md](../HANDOFF.md), mood colors are a fixed, separate set, **never**
> algorithmically recolored, and custom owner mood labels/colors (which ride in
> `BackupData`) override the defaults at render time — the web reads them from the
> decrypted snapshot exactly as the app reads `LocalMoodColors`. Reports must use the
> owner's own palette, not these defaults, when present.

### 2.4 Spacing, scale, radius, elevation

```css
:root {
  /* 4px base spacing scale */
  --sp-1: .25rem; --sp-2: .5rem; --sp-3: .75rem; --sp-4: 1rem;
  --sp-5: 1.5rem; --sp-6: 2rem; --sp-7: 3rem; --sp-8: 4rem;
  /* restrained radii — sibling of Shape.kt (8/12/15/16/22dp) */
  --r-sm: 8px; --r-md: 12px; --r-lg: 16px; --r-xl: 22px; --r-pill: 999px;
  /* layout */
  --measure: 66ch;         /* comfortable reading width */
  --container-max: 1100px; /* big-screen content cap */
  --hairline-w: 1px;
}
```

**Elevation philosophy:** the signature surface is the web analogue of `PaperSurface`
— a flat sheet with a **1px border** and *at most a whisper* of shadow; **no shadow in
dark mode**. Depth comes from borders (`--border-strong` where the edge is
load-bearing), slight value shifts (`--surface` vs `--surface-2`), and a single modal
scrim — never tonal Material elevation tints.

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

| Mode | Mechanism | Notes |
|---|---|---|
| **Light** | `[data-theme="light"]` or default | the paper identity |
| **Dark** | `[data-theme="dark"]` | "night paper"; no drop shadows |
| **System** | no attribute + `prefers-color-scheme` | the default on first load |
| **High-contrast** | `[data-contrast="high"]` and/or `@media (prefers-contrast: more)` | thicker borders, ink↑/paper↑ separation, ≥7:1 text, ≥3:1 non-text |
| **Reduced-motion** | `@media (prefers-reduced-motion: reduce)` | disables non-essential transition/animation |

```css
@media (prefers-contrast: more) {
  :root {
    --hairline:       #C9BFAD;   /* darker separator */
    --border-strong:  #8A8170;   /* push control edges well past 3:1 */
    --text-muted:     #4A463E;
    --focus-ring:     #1F4FA0;
    --hairline-w:     1.5px;
  }
}
```

> **Token-validation note (must-fix from review):** the high-contrast block above uses
> well-formed 6-digit hex values only. The CI color-token validator (§1.3, §6.3)
> rejects any malformed value (e.g. a stray space or odd digit count) so a typo like
> `#C9BFA d` can never ship — it would fail the build, not silently render an invalid
> custom property.

A user toggle in settings persists choice to `localStorage` (no cookie, no server
round-trip) and reflects it on `<html>` **before first paint** to avoid a flash of
wrong theme. **The pre-paint bootstrap is shipped as an external `'self'` module**
(`/assets/theme-bootstrap.js`, loaded synchronously in `<head>`), **not** an inline
script — so the committed CSP needs **no** script hash and **no** `unsafe-inline`. (If
a future build instead inlines it for a single round-trip saving, it MUST be a
**CSP-hashed** inline script — `script-src 'self' 'sha256-…'` — and the hash committed
alongside the header; CI asserts there is no *unhashed* inline `<script>` in `dist/`.
We pick the external-module path as the default to keep §7's header free of per-build
hashes.)

### 3.2 Motion (CSS-only, no JS animation libraries)

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

### 4.1 Primitives & layout
- `PaperSurface` (the signature card: `--border-strong` edge, optional whisper shadow,
  `--r-lg`), `Sheet`, `Stack`/`Cluster`/`Grid` layout helpers, `Divider` (decorative
  hairline), `Container` (capped to `--container-max`), `VisuallyHidden`, `SkipLink`.

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
- `Modal`/`Dialog` (focus-trapped, `Esc` to close, `aria-modal`, scrim = `--elev-3`),
  `ConfirmDialog` (used for **revoke share**, **delete**, **key rotate** — high-stakes,
  requires explicit confirm), `Drawer`, `Tooltip` (non-essential info only; never the
  sole carrier of meaning), `Popover`.

### 4.5 Data display
- `Table` (sortable, sticky header, zebra via `--surface-2`, responsive card-collapse
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

Each component ships with: states (default/hover/focus/active/disabled/error/loading),
light+dark+high-contrast tokens applied, keyboard map, and SR semantics.

---

## 5. Charts approach (dependency-light, vendored)

**Hand-rolled SVG, no charting library.** Reports need: line trend, bar distribution,
mood-tinted calendar grids, year-in-pixels, year-in-stars, consistency heatmap,
sparklines (mirroring [FEATURES.md](FEATURES.md) Insights). These are *simple
geometric mappings*, not interactive analytics dashboards, so a charting lib
(Chart.js/D3/ECharts: large, audit-heavy, sometimes CDN-default) is unjustified.

Design:

- A tiny **scale/axis helper** (~150–250 LOC TS: linear scale, nice-ticks, path
  builder) feeds Svelte components that emit **plain SVG** styled by tokens
  (`--series-*`, `--border-strong`, `--text-muted`).
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
    <path d={areaPath} fill="var(--series-1)" opacity=".12" />
    <path d={linePath} fill="none" stroke="var(--series-1)" stroke-width="2"
          vector-effect="non-scaling-stroke" />
    <!-- hairline axes from var(--border-strong); labels in var(--text-muted), tabular-nums -->
  </svg>
  <table class="visually-hidden">…underlying values for screen readers…</table>
</figure>
```

---

## 6. Responsive & accessibility (WCAG 2.2 AA)

### 6.1 Responsive
- **Mobile-first, scales up to a desk monitor.** Breakpoints via CSS `min-width` +
  container queries: narrow (drawer nav, stacked cards, tables collapse to cards),
  medium (two-column), wide (side nav + content capped at `--container-max`,
  multi-column report). The *expanded user features* assume a large screen but never
  break on a phone-width browser.
- Fluid type/space; everything in `rem` so OS/browser zoom and large-font settings
  work (WCAG 1.4.4 / 1.4.10 reflow to 320px, no loss of content at 200% / 400% zoom).

### 6.2 Accessibility targets (WCAG 2.2 AA, with relevant 2.2-new criteria)
- **Contrast:** body text ≥ 4.5:1, large text ≥ 3:1, **non-text/UI control & state
  indicators ≥ 3:1** (1.4.3, 1.4.11) — delivered by `--border-strong`, the focus ring,
  and control fills, **not** by the decorative `--hairline` (see §2.3.1). The
  paper/ink palette is chosen to pass; high-contrast mode pushes toward AAA.
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
- CI runs axe-core (build-time, in-repo) over rendered routes; manual keyboard + SR
  passes are a release gate (extends the [../HANDOFF.md](../HANDOFF.md) §9
  accessibility-pass TODO to the Companion).
- The **token validator** (§1.3) parses `tokens.css` + theme overrides, rejects
  malformed color values, and computes contrast for every semantic text/background pair
  plus `--border-strong`/surface and `--focus-ring`/surface — failing the build if any
  load-bearing pair drops below its required ratio.

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
- **`font-src 'self'`:** fonts are vendored woff2 — **no Google Fonts**, no
  `fonts.gstatic.com`.
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

- [ ] Svelte 5 + TS + Vite; `dist/` vendored; CI asserts no external origins, no
      `eval`/`new Function`, no unhashed inline `<script>`, reproducible build, SBOM
      over build deps.
- [ ] Runtime image: static files + Ktor only; **no Node, no package manager**; base
      pinned by digest (per [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6).
- [ ] Vendored Fraunces + Inter variable woff2, subset, preloaded, `font-display:swap`,
      system fallbacks; **no font CDN**.
- [ ] Two-tier tokens (primitives mirror `Color.kt` 1:1 + semantic roles);
      light/dark/system/high-contrast/reduced-motion all driven from tokens;
      `--border-strong` (not `--hairline`) carries every load-bearing edge.
- [ ] Color-token validator + contrast gate in CI: rejects malformed hex, verifies
      every load-bearing text/border/focus pair meets its ratio.
- [ ] Original vendored SVG icon sprite (`currentColor`), GPL-3.0, no third-party
      packs/emoji.
- [ ] CSS-only motion; `prefers-reduced-motion` honored globally; cognitive-task
      stimulus timing handled separately and frame-accurately.
- [ ] Pre-paint theme bootstrap is external `'self'` (default) **or** explicitly hashed
      inline; committed CSP header matches the choice.
- [ ] Component library per §4, each with full states + a11y + keyboard map.
- [ ] Hand-rolled SVG charts with table/aria fallbacks and print styles.
- [ ] WCAG 2.2 AA gate: contrast, keyboard, focus-not-obscured, target size, drag
      alternative, accessible auth, SR semantics; axe-core in CI + manual SR pass.
- [ ] CSP exactly per [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6 (`'self'` +
      `wasm-unsafe-eval`; `img-src 'self' data: blob:`); security headers present;
      `dist/` zero-third-party verified in CI.
- [ ] **Scores-only** instrument results; **item-9 structurally absent**; offline
      crisis flow, never a verdict; fixed non-diagnostic + lower-assurance banners on
      every surface.
- [ ] License-clean instruments only ([INSTRUMENTS.md](INSTRUMENTS.md)); attribution /
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
