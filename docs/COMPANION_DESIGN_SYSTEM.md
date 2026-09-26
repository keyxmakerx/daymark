# Daymark Companion — design system

The visual contract for the Companion's four web consoles: the owner console, the clinician portal,
the practice console and the server admin console, all built from `companion/web/`. Every value lives
in `companion/web/src/app.css`, which is the authority; this document says what the values are for and
which rules a test enforces. Flows and copy: [COMPANION_UX.md](COMPANION_UX.md). The phone app's
design: [DESIGN.md](DESIGN.md). Section numbers are kept from the earlier, longer version of this
document because code cites them, so the numbering has gaps.

## 0. The central tension, resolved

The consoles should look modern and still load nothing from anyone else: `default-src 'self'`, no
CDNs, no remote fonts, no analytics, and a server that makes no outbound calls. Most modern web stacks
reach for exactly those things. The resolution is to move sophistication to build time: a compiled
framework and a bundler turn modern authoring into a small, self-contained set of `'self'` files, and
the polish lives in tokens, type and restraint rather than in runtime dependencies.

| The tempting thing | Why not here | What the consoles do |
|---|---|---|
| A web-font CDN | a third-party origin that sees every visit | system font stacks (§2.2) |
| A CSS framework from a CDN | styles from another origin | hand-written token CSS in one `app.css` |
| A virtual-DOM runtime with hydration | a bigger bundle, pressure towards `unsafe-eval` | Svelte, which compiles to plain DOM updates |
| A charting library | a large, audit-heavy dependency | hand-drawn SVG (§5) |
| An icon font or icon CDN | a third-party origin, poor accessibility | text glyphs (§2.5) |
| Analytics or real-user monitoring | outbound calls, telemetry | none, ever |
| `'unsafe-inline'` for convenience | weakens the CSP | none (§7) |

CSP and subresource integrity protect against third parties only. They are not a zero-knowledge
defence against the server that serves the page ([COMPANION_SECURITY.md](COMPANION_SECURITY.md) R5),
which is why the fixed lower-assurance banner says so in the product.

## 1. Frontend stack

### 1.1 The stack

Svelte 5 and TypeScript, bundled by Vite into static files. `companion/web/vite.config.ts` builds four
pages — the owner console, the clinician portal, the practice console and the admin console. The
server image copies the bundle and serves it; the runtime image has no Node and no package manager.
libsodium runs in the browser as WebAssembly, the one reason the CSP carries `'wasm-unsafe-eval'` (§7).

Svelte ships no runtime framework, so the bundle is mostly the product's own code — small enough to
audit, which is a security property here — and it needs no `unsafe-eval`. TypeScript guards the crypto
and data-shape boundaries, where a silent type error can be a confidentiality bug.

### 1.2 Why not the alternatives

SolidJS would have done nearly as well. Lit or plain web components would work at a higher build cost.
React/Next and Vue/Nuxt bring a heavier runtime and server-rendering machinery that fights a static
page doing its crypto in the browser, and server-rendered HTML (htmx) cannot do the client-side
decryption the consoles exist for.

### 1.3 Checks on the built bundle

Not built: CI checks that the built bundle names no external origin, contains no `eval` or
`new Function` and no unhashed inline script, and builds reproducibly: #248.

## 2. Visual language — "modern paper, big screen"

A sibling of the phone app's "modern paper" language ([DESIGN.md](DESIGN.md)) — warm, flat, stationery
surfaces and hairline rules — adapted to a large screen: denser data, real tables, and a visible split
between the machine's chrome and a person's content (§2.3.0).

### 2.1 Art direction

> **Calm, private, trustworthy. Modern but not trendy.** A well-made paper instrument on a clean desk:
> warm off-white sheets, ink-dark text, a quiet serif wordmark, hairline rules and one reserved
> accent. Confident and unhurried — nothing pulses for attention, nothing is "smart", nothing
> performs. No glassmorphism, neon gradients, oversized blur or motion for its own sake. Security is
> shown as composure, not theatre: clear state, legible language, honest limits.

On a console that means five working rules:

1. **Hairlines and space, not shadows and boxes.** Regions are separated by 1px rules and whitespace.
   A sheet (`Card`) has a hairline edge and at most one whisper of shadow (`--elevation`); there is no
   page texture and no stacked drop shadow.
2. **Tables, not card grids.** Lists of records are tables (`DataTable`): mono uppercase heads,
   hairline rows, tabular numerals. A table too wide for the screen scrolls inside its own box, which
   becomes a focusable, labelled region only when something is actually off-screen.
3. **Monospace for the technical.** IDs, fingerprints, digests, timestamps, counts and log rows are set
   in `--font-mono` (the `.u-mono` and `.u-label` utilities). In a security tool that reads as precise.
4. **Status is a word with a mark, never a colour alone.** A small mark — a dot, a ring, `StatusPill`'s
   outline, tint, solid or hatched form — sits beside the written state, and the mark is `aria-hidden`.
5. **The serif is spent sparingly**, on the wordmark and on titles (§2.2) — never on navigation,
   labels, table heads or data.

The consoles have no global search and no breadcrumb bar (#263): search lives inside the screen
whose data it searches (#245).

### 2.2 Typography

`app.css` declares three stacks: `--font-display`, `--font-text` and `--font-mono`. The display
face is Fraunces, bundled and served from the Companion's own origin, and all other text is the
platform's own sans (`system-ui` and its fallbacks), for good; Inter is dropped (#251). Not built:
#364. Today no font file is bundled and the two `@font-face` rules are commented out, so
`--font-display` renders in its fallbacks (Iowan Old Style, Georgia and the platform serif),
`--font-text` still names Inter ahead of `system-ui`, and nothing is fetched.

**Where the serif goes.** The display face is the content voice naming its subject: the wordmark, page
titles (`PageHeader`), section, card and empty-state titles, and a few large display figures.
Navigation items, labels, table heads, metadata and data are sans or mono, never serif.

Sizes: a 16px body, `h1` 1.75rem, `h2` 1.3rem, `h3` 1.05rem; everything else is set per component.
Not built: a named type scale (the `--fs-*` family) in rem, with body text at 1rem and nothing below
0.75rem: #365.

### 2.3 Colour and design tokens

Lives in `companion/web/src/app.css`: both tiers, all three theme states. Enforced by
`ui/invariants.test.ts` (the twelve primitives) and `components/invariants.tree.test.ts` (every file
under `src/`); §6.3 lists what each asserts.

#### 2.3.0 The generating rule: cool chrome, warm content

> **Chrome is cool, dense and monospaced. A person's content stays warm paper.**

**Chrome** is the machine talking about itself — navigation rails, top bars, table heads, timestamps,
IDs, digests, counts, admin surfaces. It sits on a grey-blue ground (`--chrome*`), labels itself in
tracked-out uppercase mono (`--chrome-soft`, `.u-label`) and takes its structure from `--indigo`.
Chrome recedes. **Content** is the warm paper palette mirrored from the phone app's
`ui/theme/Color.kt` — entries, moods, notes, answers, scores — and it is the subject. Because
interface and data live on visibly different grounds, a person's mood can be coloured without the
furniture competing with it.

#### 2.3.1 Invariant 1 — the mood ramp is data only

`--mood-1` to `--mood-5` and their `-wash` variants encode a person's reported experience and nothing
else. Interface state — selection, hover, validity, progress, severity, success — is the job of the
chrome, indigo, clay and amber tokens.

Every use of a mood token is exactly one of two things. **DATA** — a score, a band, a mood dot, a
trend, a person's own answer shown back to them — keeps the ramp. **STATE** — a banner, warning,
error, selected tab, link, focus ring, callout border or confirmation — moves to chrome, indigo, clay
or amber. If you cannot tell which, read what renders it.

Enforced tree-wide. `components/invariants.tree.test.ts` group (a) allows seven files to name a mood
token — `app.css` and `lib/mood.ts`, which define the ramp, and five surfaces that draw a person's own
data: `charts/Sparkline.svelte`, `Dashboard.svelte`, `QuestionnaireRunner.svelte`, `ui/BandTag.svelte`
and `calendar/MoodMark.svelte`. It fails on any other file, and inside those files it fails on a mood
token used under a state selector (`:hover`, `.active`, `[aria-selected]` and the like).
`ui/invariants.test.ts` group (c) adds that inside `ui/` only `BandTag` names the ramp, that it uses
all five steps, and that no other primitive names `--mood-4` or `--mood-5` even in a comment.

**A person's own names and colours.** The phone lets a person rename and recolour their moods, and the
backup carries both. On their own data — a backup opened in the viewer or the owner console — the web
draws their moods in their own words and colours, and a level they left alone keeps the shipped word
and ramp (#280). A share carries neither, so the clinician's views draw the shipped words and ramp,
and the clinician's view of a share tells the dashboard `ownData={false}` so it would even if a bundle
one day carried them. A person's colour can be any colour, the greens included, so it is held to one
rule more than the ramp: it fills a mood mark and nothing else, and a mood mark always sits beside its
word. It travels one road. `ownMoodColours` (`lib/mood.ts`) reads the backup's integers into an opaque
palette, and every colour out is `#rrggbb` built by arithmetic, so a crafted backup writes no CSS. The
only way out is `ownMoodFill`, whose value is set as one custom property, `--mood-fill`, inline on the
mood mark itself — a leaf, so nothing inherits it — and read only by rules whose subject is
`.mood-mark`, as its fill or background. The marks that take it are the mood distribution's bars and
the month calendar's check-in squares (`calendar/MoodMark.svelte`); the association bars, the mood
line and the self-check trend keep the shipped ramp, because none is one mood beside its word.
`components/ownMoodColour.tree.test.ts` holds every step.

#### 2.3.2 Invariant 2 — there is deliberately no success colour

A green mark is a claim — *we checked, it held, you may stop reading* — and this product is in no
position to make it. The rule starts at the trust strip: a served page is never painted green, because
served JavaScript is lower-assurance whatever the network is doing
([COMPANION_UX.md](COMPANION_UX.md) §10.1). A green tick anywhere else is the same overclaim one layer
down. So "completed", "verified", "confirmed" and "done" are solid ink (`--ink-text` /
`--ink-accent`): healthy is the absence of colour.

Never declare `--success:`, `--warning:`, `--trust-locked:`, `--trust-caution:` or `--trust-open:`.
The first two would turn the mood ramp into a severity palette, which an early draft of this design
did and the implementation rejected; the `--trust-*` family would give the trust strip a colour
vocabulary, and assurance is worded, not hued. The ban is on the name and the role, not the value:
`--mood-5` is green and stays green, because it means "this person reported a good day", not "the
system is fine".

Enforced by `ui/invariants.test.ts` group (d) and `invariants.tree.test.ts` group (b): no declared or
referenced token name claims health (success, ok, positive, good, healthy, safe, pass, valid,
verified, secure, trusted, green…); no token the status colours are built from resolves to a green hue
in any theme; the only green values in the palette are the ramp's top two steps; and no component
paints a green literal or colour keyword. Group (e) of the `ui/` suite pins `StatusPill`'s
`completed` state to the ink token.

#### 2.3.3 Two tiers

```
TIER 1 — PRIMITIVES   --c-*    raw hex, both themes side by side, named for what they ARE
   ↓                           (the layer that mirrors Color.kt)
TIER 2 — SEMANTIC     roles    named for what they are FOR; components reference only these
```

Theming is remapping at `:root` and `[data-theme]`, never editing a component. A component that
reaches for a `--c-*` primitive has skipped the layer where the meaning lives, and
`invariants.tree.test.ts` group (c) fails it — as it fails any reference to a token `app.css` does
not define, which is how a silent fallback onto an undefined token gets caught.

All three theme states are handled: bare `:root` carries the complete light palette;
`@media (prefers-color-scheme: dark)` is guarded as `:root:not([data-theme="light"])`; and
`:root[data-theme="dark"]` repeats the dark roles, so an explicit choice wins in both directions.
`ui/invariants.test.ts` group (a) asserts that both dark blocks redefine every themed role and agree,
and that no colour is defined only inside a media or `[data-theme]` block — a role that existed only
in dark would render unstyled in daylight.

#### 2.3.4 The semantic contract

**Content — a person's data. Do not repurpose.**

| Token | Role |
|---|---|
| `--paper-bg`, `--paper-sheet` | page ground, sheet |
| `--ink-text`, `--ink-soft`, `--ink-faint` | the three-step ink scale (`--ink-faint` is decorative) |
| `--text-subtle` | small print that must still pass AA (§2.3.6) |
| `--hairline` | decorative separator — **not** a load-bearing edge (§2.3.7) |
| `--border-strong` | every load-bearing control or surface edge, held to 3:1 |
| `--ink-accent`, `--on-accent` | accent fill and its foreground |
| `--elevation` | the layered-paper shadow, held whole |
| `--mood-1..5`, `--mood-1-wash..5-wash` | **data only** (§2.3.1) |

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
| `--indigo` | active nav, links, selected item, structural marks |
| `--indigo-deep` | pressed or emphasis |
| `--indigo-wash` | the info-callout ground |

Indigo is structure. It never means "good", never means "this value is high", never carries a datum:
if an indigo surface would change colour because a number changed, it is the wrong token.

**Alarm and warn — two hues, sharply separated.**

| Token | Role |
|---|---|
| `--clay`, `--clay-wash` | **the single alarm hue**: error, refusal, destructive, revocation, overdue, needs a human |
| `--amber`, `--amber-wash` | **warn severity only**: the lower-assurance banner, "Custom" provenance, console warn rows |

There is exactly one red, because the moment there are two, neither means anything. `--danger` and
`--danger-wash` are aliases of `--clay` and `--clay-wash`, so code has the role name without a second
alarm hue; re-pointing `--clay` carries them in every theme. Amber is not alarm, not generic
"attention", and never a stand-in for success.

**Interaction:** `--focus-ring` (the `:focus-visible` outline) and `--link`.

#### 2.3.5 Pinned severity decisions — already made, do not re-litigate

| Surface | Token | Why |
|---|---|---|
| Fixed **non-diagnostic** caveat or note-strip | `--chrome` ground, `--chrome-ink` / `--text-subtle` text, `--chrome-hair` border, **no coloured rail** | It is information, part of the instrument, never "something is wrong". This is what frees amber for real warnings. |
| **Lower-assurance** banner | `--amber` / `--amber-wash` | A genuinely degraded assurance path. It earns a severity hue. |
| The trust strip | `--chrome` ground, **never green**, no amber state | It describes the surface, permanently; see [COMPANION_UX.md](COMPANION_UX.md) §10.1. |
| A consented, ongoing share (the sharing strip) | `--chrome` ground with a 3px `--indigo` rule; clay only on the confirm that ends it | A standing, consented fact is none of clay's meanings, and an alarm that is always on is not an alarm. |
| Somebody else's ordinary decision (a clinician leaving) | chrome, never clay | Painting it as an alarm would read as an accusation nobody made. |
| Error, refusal, destructive action, revocation | `--clay` | The single alarm hue. |
| Active tab, selected item, link, focus ring | `--indigo` | Structure, not data. |
| Success, confirmation, "completed", "verified" | **solid ink** (`--ink-text` / `--ink-accent`) | Never green (§2.3.2). |
| Charts, `Sparkline`, band tags, mood dots | the **mood ramp** | That is what the ramp is for. |

The fixed copy on these surfaces — the non-diagnostic banners, the lower-assurance banner, the
provenance disclaimers, the audit caveats, the crisis and safety copy — is baked into the bundle,
never supplied by the server, so a hostile operator cannot strip it. Restyling its container is
allowed. **Rewording, shortening, "improving" or reflowing its text is not**, at any layer, including
this document. `invariants.tree.test.ts` group (e) pins it verbatim.

#### 2.3.6 The measured accessibility corrections

**This subsection is normative**: these are the values the tokens must carry. Each looks arbitrary and
is not — each was solved numerically, hue and saturation held and lightness walked until the pair
cleared its WCAG threshold on a ground the product actually renders it on. Reverting one to the
prettier original reintroduces a measured defect. `app.css` repeats this table in its
`CONTRAST CORRECTIONS` comment, and each value must keep its note there: a bare hex with no reason is
indistinguishable from a typo and will be "tidied" back.

Thresholds: **4.5:1** body text (SC 1.4.3) · **3:1** focus indicators and control boundaries (SC 1.4.11).

| Token | Theme | Before → after | Ratio | Ground |
|---|---|---|---|---|
| `--chrome-soft` | light | `#626D7D` → `#586170` | 4.23 → **5.04** | `--chrome` (worst ground 4.55 on `--chrome-2`) |
| `--clay` | light | `#A8574A` → `#9A5044` | 3.93 → **4.50** | `--clay-wash` |
| `--clay` | dark | `#C9806F` → `#CB8473` | 4.33 → **4.51** | `--clay-wash` |
| `--clay-wash` | light | `#F2DED9` → `#F3E0DB` | 4.46 → **4.54** | `--ink-soft` on it — the body copy of every error banner (`--clay` on it rises to 4.58) |
| `--amber` | light | `#9C7128` → `#866122` | 3.53 → **4.52** | `--amber-wash` |
| `--border-strong` | light | `#cbc1ae` → `#9B8864` | 1.56 → **3.01** | `--paper-bg` |
| `--border-strong` | dark | `#4a463c` → `#726B5C` | 1.69 → **3.01** | `--paper-sheet` |
| `--focus-ring` | light | `#5E8A66` → `#3F5F7F` | n/a → **5.37** | `--chrome` (worst ground 4.84 on `--chrome-2`) |
| `--focus-ring` | dark | `#8FA268` → `#7FA3C8` | n/a → **5.99** | `--chrome` (worst ground 4.83) |

- **`--chrome-soft`** is every `.u-label` in the product — nav group labels, table heads, timestamps.
- **`--amber`** is the "Custom" provenance badge, which renders on every row of the catalogue.
- **`--focus-ring` was an invariant violation, not a contrast failure.** It was byte-identical to
  `--mood-5` in light and `--mood-4` in dark, so focus was drawn in the exact green of a "rad" mood
  bar: interface state wearing a person's data. It now carries the indigo, through its own primitives
  (`--c-focus-day`, `--c-focus-night`) rather than an alias of indigo, so focus can move without
  dragging structure or the ramp with it.

**Deliberately not changed:** `--ink-faint` as text (2.37 light, 3.81 dark) — it is decorative by
contract, and a component using it for real text is a component defect fixed by switching to
`--text-subtle`; `--mood-*` as text or as marks (1.98–4.48) — the ramp is a person's data and is never
recoloured to pass a check, and every mark carries a text label beside it; `--hairline` and
`--chrome-hair` as rules (1.16–1.39) — dividers, not controls (§2.3.7).

What holds these values: `ui/invariants.test.ts` group (a) pins the chrome, indigo, clay and amber
values in both themes. No test pins `--border-strong` or `--focus-ring`, and nothing computes a ratio,
so a green `pnpm test` is not evidence that this table holds or that a new pair passes — read
`app.css`. Not built: a computed contrast check in CI: #254.

#### 2.3.7 The hairline is decorative; load-bearing edges use `--border-strong`

`--hairline` (`#e7dfd1` on `#fcfaf5`) is a soft separator at about **1.16:1**. That is right for
grouping and wrong for anything a person must perceive as a control. So:

- decorative grouping — zebra rows, section rules, card-internal dividers — may use `--hairline`
  (`--chrome-hair` is the same contract on the chrome ground);
- any control or surface whose identity depends on its edge — buttons, inputs, textareas, selects,
  focusable cards, a dialog's boundary — uses `--border-strong`, or carries another non-colour cue
  (a label, a fill, a glyph);
- no claim of 3:1 is made for the hairline; it is made for `--border-strong`, the focus ring and
  control states.

This matters more than it looks: every button, input and select fills with `--paper-sheet` on a
`--paper-bg` page, a ratio of **1.10:1**, so the border is the only visual evidence that it is a
control at all.

### 2.4 Spacing, radius, elevation

```css
/* app.css — themeless, declared once, never redefined per theme */
:root {
  --space-1: 0.25rem; --space-2: 0.5rem; --space-3: 0.75rem; --space-4: 1rem;
  --space-5: 1.5rem;  --space-6: 2rem;   --space-8: 3rem;   /* no step 7: nothing uses one */
  --radius: 0.625rem; --radius-sm: 0.375rem;
  --maxw: 64rem;                                           /* the big-screen content cap */
}
```

Elevation is the web's version of the phone's `PaperSurface`: a flat sheet with a 1px edge and at most
a whisper of shadow, held whole in `--elevation`. In dark mode the shadow is near-black, so it reads as
depth rather than as a tinted Material surface. Depth otherwise comes from edges, the paper/sheet value
shift, the chrome/content ground shift and a single modal scrim.

### 2.5 Icons

There is no icon set, sprite, pack or icon font, and no emoji (#263). Marks are text glyphs — the
provenance glyphs ✓ ◐ ✎, the sharing strip's ring — drawn in the surrounding text colour and never
green. A screen that needs a mark no glyph can make draws a small inline SVG in `currentColor`,
beside a word and `aria-hidden`. A decorative mark is `aria-hidden`; an icon-only button carries an
accessible name (`ui/FieldHelp.svelte` names its "i" as a whole question).

### 2.6 Imagery

No stock photography and no third-party images — both a third-party origin and a tone risk on a
mental-health surface. The only images are the product's own mark and its charts.

## 3. Theming and motion

### 3.1 Theme modes

| Mode | Mechanism | Status |
|---|---|---|
| Light | bare `:root`, or `[data-theme="light"]` opting out of the dark query | built — the paper identity |
| Dark | `@media (prefers-color-scheme: dark)` on `:root:not([data-theme="light"])` | built |
| Dark, explicit | `:root[data-theme="dark"]` | built — redefines every themed role |
| System | no attribute | built — the default |
| High contrast | `prefers-contrast` | Not built: #253 |
| Reduced motion | `@media (prefers-reduced-motion: reduce)` | built — global, in `app.css` |

`:root[data-theme="light"]` and `:root[data-theme="dark"]` also set `color-scheme`, so the browser
draws matching form controls and scrollbars. The admin console sets `data-theme="dark"` on mount and is
dark in both themes. Not built: a light/dark choice in the consoles with no flash on load: #256. When
it is built, the pre-paint loader is an external `'self'` module (or, at most, a CSP-hashed script),
never `'unsafe-inline'`.

### 3.2 Motion

Transitions are written per component and kept short (120–180 ms on background and border colour).
Nothing pulses or bounces: that would read as attention-seeking and contradict §2.1. The global block
in `app.css` neutralises every animation and transition for anyone who asks for less motion, so the
guarantee does not depend on each component opting in. Not built: motion tokens (`--ease-standard:`,
`--ease-entrance:`, `--dur-fast:`, `--dur-base:`, `--dur-slow:`): #366.

## 4. Components

`companion/web/src/lib/components/ui/` holds twelve primitives, chosen for what the screens actually
needed:

| Primitive | Notes |
|---|---|
| `AppShell.svelte` | Rail, top bar and body; owns the stacking context (§4.9). |
| `NavRail.svelte` | Grouped navigation; `disabled` is a property, so the rail shows its limits rather than hiding them. |
| `PageHeader.svelte` | Title, chips and a trailing slot; wraps rather than truncates. |
| `Card.svelte` | The paper sheet: `title`, `tone: default \| quiet`, header and footer slots; the footer is the note-strip the fixed caveats sit in. |
| `Callout.svelte` | `tone: info \| warn \| critical` — the banner substrate: indigo, amber and clay washes, each with a form signal and a visually hidden severity prefix. |
| `Chip.svelte` | `tone: neutral \| accent \| warn \| critical`; mono, uppercase, tabular. |
| `StatusPill.svelte` | The seven assignment states. `completed` is ink (§2.3.2); `declined` and `noResponse` are drawn differently on purpose — silence is not refusal. |
| `ProvenanceBadge.svelte` | Validated, Adapted, Custom, from `instruments/provenance.ts` ([PROVENANCE.md](PROVENANCE.md)). Custom is the amber case. |
| `BandTag.svelte` | The only primitive licensed to use the mood ramp (§2.3.1). |
| `DataTable.svelte` | Mono uppercase heads, tabular numerals, scrolls inside its own box when too wide. |
| `EmptyState.svelte` | Title, optional body and action. |
| `FieldHelp.svelte` | The "what is this field?" button beside a label: a real, focusable toggle for a panel of fixed help copy, never a hover tooltip. |

`index.ts` is the barrel; `status.ts`, `nav.ts` and `table.ts` exist as plain `.ts` because a Svelte 5
instance script cannot export types. Feature components elsewhere in `src/lib/components/` — the
questionnaire runner, the attention task, the trust and sharing strips, the banner pairs, the share
builder — are screens, not a library. A piece moves into `ui/` when a second screen needs it, and
not before (#263).

### 4.9 The stacking context belongs to `AppShell`

A hovered calendar cell lifts itself with `z-index` so its shadow rises above its neighbours. Unless
the body isolates its descendants, that shadow paints over the top bar, the rail and the page title.
`AppShell` and `PageHeader` own the fix and must keep it:

```
topbar      { position: relative; z-index: 6 }
rail        { z-index: 7 }
body        { position: relative; z-index: 1; isolation: isolate }
page-header { position: relative; z-index: 6 }
```

`ui/invariants.test.ts` group (f) asserts the rules are present, the body isolates, and the rail sits
above the header, which sits above the body. The bug shows only while hovering one kind of cell on one
screen, so nothing else would notice it coming back.

## 5. Charts

Hand-drawn SVG, no charting library: `charts/Sparkline.svelte` and inline SVG in the dashboards.
A chart of a person's mood is drawn on the mood ramp, because there **the ramp is the legend**: an
"awful" bar must be the colour an awful day is everywhere else. A chart of something that is not mood
(counts, durations) would need its own low-chroma, colour-blind-safe series palette that does not
overlap the ramp's meaning; none exists yet. Every chart should have a text or table equivalent and
print cleanly. The month calendar has both: its day panel lists the chosen day in words, and **Print
this month** prints the month alone, ink on white (`calendar/print.ts`). Not built for every chart:
#259.

## 6. Responsive and accessibility

### 6.1 Responsive

Content is capped at `--maxw`, layouts reflow rather than truncate, and wide content (tables, key
material) scrolls inside its own box (`.u-scroll-x`, `DataTable`) so the page never scrolls sideways.

### 6.2 Accessibility

The target is WCAG 2.2 AA. What the code holds: text at 4.5:1 and control edges and focus at 3:1 on
the grounds measured in §2.3.6; a `:focus-visible` outline in `--focus-ring`; colour never the only
signal (§2.1 rule 4); decorative marks `aria-hidden`, with a visually hidden prefix where a sighted
reader would get context from position. Not verified by any tool — there is no rendered accessibility
check and no computed contrast: #254.

### 6.3 What the tests enforce, and what they cannot

Both suites read source text; there is no component-rendering harness. Run with
`cd companion/web && pnpm test`.

`ui/invariants.test.ts` — the twelve primitives and `app.css`:

| Group | Asserts |
|---|---|
| subject | the `ui/` directory, its components and `app.css` were found, so nothing below passes on an empty set |
| (a) | all three theme states defined; bare `:root` carries every token; both dark blocks redefine every themed role and agree; no colour only inside a media or `[data-theme]` block; chrome, indigo, clay and amber carry their pinned values; the solid ramp is identical in every theme |
| (b) | no primitive hardcodes a colour |
| (c) | the mood ramp is data: `BandTag` uses it, nothing else in `ui/` names it (§2.3.1) |
| (d) | no health-claiming token name; no status token resolves to green in any theme; no green literal (§2.3.2) |
| (e) | `StatusPill`'s `completed` is ink, and carries its meaning in form and words |
| (f) | the `AppShell` stacking-context fix survives (§4.9) |

`components/invariants.tree.test.ts` — every file under `src/`, with comments stripped before
structural checks, so an explanation of a rule cannot satisfy the rule:

| Group | Asserts |
|---|---|
| subject | the walk found the tree, the comment stripper works, the exemptions are live |
| (a) | only the seven allowlisted data files name a mood token, and never under a state selector (§2.3.1) |
| (b) | no health-claiming token name, no green literal or keyword in any component, and the only green values are the ramp's top two steps (§2.3.2) |
| (c) | every token a component references is defined in `app.css`, and no component reaches for a `--c-*` primitive (§2.3.3) |
| (d) | no component hardcodes a colour |
| (e) | the fixed honesty copy — non-diagnostic banners, lower-assurance banners, the audit caveat, the share builder's scores-only framing, the provenance disclaimers — is verbatim (§2.3.5) |

`components/ownMoodColour.tree.test.ts` — a person's own colours (§2.3.1), over every file under `src/`,
parsing components with Svelte's own parser: a backup's colours are read only through `ownMoodColours`;
`ownMoodFill` is called only as the value of a `style:--mood-fill` directive; that directive sits only
on a non-interactive leaf `span` or `rect` with the class `mood-mark` and a `.mood-word` beside it;
every rule naming the property has a mood mark as its subject, no interface state or chrome in its
selector, and spends it on a fill; only the clinician's view says `ownData={false}`.

`components/calendar/monthCalendar.test.ts` — the person's own month: a day is drawn the same whatever
it holds; no grade, figure or arrow; the day panel and controls are left off the paper and every word
stays on it; the month is mounted only behind the dashboard's own-data gate; the clinician's calendar
draws no mood.

Every guard proves its subject exists before it filters, because a grep-shaped test that matches
nothing goes green. Any change to these suites is mutation-tested before it is trusted (CLAUDE.md §5).

**Three human gates** remain, because no test can do them:

1. **The fixed-copy diff.** Group (e) pins the surfaces it lists; it cannot tell you a reworded
   banner elsewhere is worse. After touching any file with non-diagnostic, lower-assurance,
   provenance, audit-caveat or crisis copy, read its `git diff` and confirm no prose changed.
2. **The DATA/STATE call.** The tree suite enforces an allowlist of files and rejects state
   selectors; it cannot tell you that a given mood token inside an allowlisted file is data. Only
   reading what renders it does (§2.3.1).
3. **Computed contrast.** Nothing recomputes the §2.3.6 ratios; check a new colour pair by hand.

## 7. Content-Security-Policy

The served policy and the other security headers are quoted once, from
`companion/server/src/main/kotlin/com/daymark/companion/SecurityHeaders.kt`, in
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6. What this design relies on: everything is `'self'`;
the one relaxation is `'wasm-unsafe-eval'`, for libsodium, never a blanket `unsafe-eval`; there is no
`'unsafe-inline'` for scripts or styles, so styles ship in the bundled stylesheet; `img-src` also
allows `blob:` and `data:` for images decrypted in the browser, which fetch nothing; and
`connect-src 'self'` means a page can only talk to the server that served it. The app deliberately
sets no `Strict-Transport-Security`: TLS ends at the reverse proxy, which sets it
([COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md)).
