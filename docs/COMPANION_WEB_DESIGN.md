# Daymark Companion — Web Console Design Language

> ## ⚠️ STATUS: DESIGN — DIRECTION APPROVED, NOT YET IMPLEMENTED
>
> This is the **crisp, dense, professional** style for the self‑hosted web
> console (owner + clinician + admin surfaces). It **replaces an earlier
> "card‑bubble" pass** that read as generic/AI‑built. It is a *different register*
> from the patient Android app, which keeps its warm "modern paper" system
> ([DESIGN.md](./DESIGN.md), [COMPANION_DESIGN_SYSTEM.md](./COMPANION_DESIGN_SYSTEM.md)).
> Reference mockups live in [`docs/design/`](./design/) — see especially
> [`web-01-console.png`](./design/web-01-console.png).

---

## Contents

- [Two registers, one product](#two-registers-one-product)
- [Principles (the anti‑"bubble" rules)](#principles-the-anti-bubble-rules)
- [Tokens](#tokens)
- [Components](#components)
- [Accessibility & CSP](#accessibility--csp)
- [Reference mockups](#reference-mockups)
- [External references](#external-references)

---

## Two registers, one product

Daymark deliberately uses **two visual registers** for two very different users:

| | Patient app (Android) | Clinician / admin web console |
|---|---|---|
| Feel | warm, calm, "modern paper" | crisp, dense, precise |
| Users | someone tracking their wellbeing | clinicians, front‑desk, admins at work |
| System | `DESIGN.md`, `COMPANION_DESIGN_SYSTEM.md` | **this document** |
| Shared thread | the teal accent · the wordmark · non‑diagnostic + provenance honesty | ← same |

A consumer surface should feel gentle; a professional tool should feel efficient.
Forcing one look on both is what made the console read as generic. The shared
accent, wordmark, and honesty language keep them the same *product*.

## Principles (the anti‑"bubble" rules)

The failure mode we're avoiding: everything floating in rounded, shadowed cards,
a pill for every value, a textured background — the generic "AI dashboard" look.
The rules that replace it:

1. **Hairlines + whitespace, not shadows + cards.** Separate regions with 1px
   rules and space. No page texture, no fake browser frame, no drop shadows on
   content.
2. **Tables, not card grids.** Lists of records are tables with hairline rows and
   tabular, right‑aligned numerics.
3. **Monospace for the technical.** IDs, key fingerprints, emails, timestamps,
   and the audit/consent log render in mono — in a security tool, that reads as
   precise and trustworthy.
4. **Tiny status dots + text, not pills.** Ration color to one accent plus small
   semantic signals. Roles are inline editable text; destructive actions are
   quiet red links.
5. **Tight type, low weights.** A clear scale, negative tracking on headings,
   weights in the 400–560 band. Type carries the hierarchy, not boxes.
6. **Data proximity.** Related facts share a screen — on the client workspace the
   patient, care team, note, assessments, and access trail are all in view, so a
   clinician never hunts across pages. (An EHR‑usability principle.)
7. **Density in behavior, not pixels.** Visually sparse, interaction‑dense:
   ⌘K search, hover/focus states, keyboard navigation. Craft the microstates.

## Tokens

Light theme (the console is light by default; a dark theme mirrors these):

```
--bg      #F3F4F4   page (flat, faintly cool)
--surface #FFFFFF   working surface
--panel   #FAFBFB   sidebar / section tint
--ink     #14171A   primary text
--ink2    #565D63   secondary
--ink3    #888F95   tertiary / labels
--line    #E7E9EB   hairline (1px)
--line2   #D6DADD   stronger hairline / control borders
--accent  #0F7A66   the one accent (teal — shared with the app)
--accent2 #0B5B4C   accent text on light
--accentw #E9F2EF   accent wash
--ok #2E8B5B  --warn #B0812A  --danger #BB4A31  --neutral #8A9198   (semantic only)
```

- **Radius:** controls `6px`, app frame `~9px`, full‑round reserved for avatars
  and status dots. Three values, used with restraint.
- **Type:** system sans stack (Inter‑class); mono stack for technical text; the
  **serif is used only for the wordmark**. Headings track tight (−.015 to −.02em);
  body ~13px; uppercase micro‑labels at 9.5px / .1em tracking.
- **Hairlines:** 1px `--line`; never a shadow where a hairline will do.

## Components

- **App frame** — flat: 1px `--line2` border, `9px` radius, at most a 1px hairline
  shadow. No traffic‑light chrome.
- **Sidebar** — wordmark + host (mono), nav grouped under uppercase labels
  (Practice / Security), active item = a 2px accent bar on the left + subtle tint,
  a user chip pinned at the bottom.
- **Top bar** — breadcrumb (`Practice / Clients / Maya R.`), ⌘K search, nothing
  else. 44px, hairline underline.
- **Section label** — uppercase micro + a hairline rule; the primary structural
  device instead of card headers.
- **Table** — uppercase micro headers over a hairline; hairline rows; numerics
  `tabular-nums`, right‑aligned; inline‑editable fields (e.g. role with a caret);
  quiet red text for destructive actions.
- **Status** — a 6px dot + text (`● Active`, `● Invited`), never a filled pill.
- **Tag** (provenance / kind) — a small dot + small‑caps label, no fill
  (`● VALIDATED`, `● CUSTOM`).
- **Log / trail** — mono timestamps, tiny keyed prefixes (`REQ`, `SHARE`,
  `GRANT`), hairline rows. This is how audit and consent read as trustworthy.
- **Buttons** — solid accent primary (small, `6px`); ghost secondary is a 1px
  border on surface.
- **Callout** — an accent left‑keyline + faint tint on a single line, not a
  rounded banner bubble.
- **Sparkline** — a thin accent polyline with an emphasized endpoint; a faint
  baseline only if needed.

## Accessibility & CSP

- **WCAG 2.2 AA** contrast is a CI‑gated deliverable, consistent with
  [COMPANION_DESIGN_SYSTEM.md](./COMPANION_DESIGN_SYSTEM.md). **Never encode
  meaning in color alone** — every status is dot **plus** text.
- Visible keyboard focus on all interactive elements; ⌘K and full keyboard nav.
- **Strict CSP, fully vendored** (no CDN / no Google Fonts), matching the existing
  console. Use the system font stack or self‑hosted/inlined faces — never a
  remote font URL.

## Reference mockups

In [`docs/design/`](./design/) (rendered PNG + the HTML that produced it):

- [`web-01-console.png`](./design/web-01-console.png) — **this language**: Team &
  roles table + the client workspace.
- [`app-01-home-daily-loop.png`](./design/app-01-home-daily-loop.png) — the
  patient app (warm register) for contrast.
- [`app-02-journal-goals-controls.png`](./design/app-02-journal-goals-controls.png)
- [`app-03-navigation-motion.png`](./design/app-03-navigation-motion.png)

See [`docs/design/README.md`](./design/README.md) for the full index.

## External references

The direction is grounded in how serious tools are built:

- **Linear / Stripe / Vercel** — hairline borders instead of shadows, a tiny
  radius vocabulary, tinted neutrals, low font‑weights, "density in the behavior,
  not the pixels," typography as brand.
- **EHR / clinical UX research** — data proximity, hierarchy aligned to clinical
  reasoning, density that reflects real usage, low cognitive load.
- **HashiCorp Vault / 1Password** — a clean audit trail and clear policy views
  *are* the trust in a security tool.
