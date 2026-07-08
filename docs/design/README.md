# Design mockups

Rendered concept mockups for Daymark's redesign, kept here as the visual
reference we plan against. Each entry is a rendered **`.png`** plus the
self‑contained **`.html`** that produced it (edit the HTML and re‑render to
iterate). These are **concept layouts** — flow, hierarchy, and design language —
not final production screens.

> **How to re‑render** (headless Chromium):
> ```
> chromium --headless=new --hide-scrollbars --force-device-scale-factor=2 \
>   --window-size=<W>,<H> --screenshot=out.png "file://$PWD/<file>.html"
> ```

## Patient app — warm "modern paper" register
See [DESIGN.md](../DESIGN.md) · [COMPANION_DESIGN_SYSTEM.md](../COMPANION_DESIGN_SYSTEM.md)

| File | What it shows |
|---|---|
| [`app-01-home-daily-loop.png`](./app-01-home-daily-loop.png) | The reworked **Home** (one‑tap check‑in, glance, Signals‑as‑router), **Check‑in**, **For you** router, **Insights**, a **Custom tool with provenance**, and the built‑in **Safety plan**. |
| [`app-02-journal-goals-controls.png`](./app-02-journal-goals-controls.png) | **Journal** and **Goals** (weekly progress + implementation intentions), plus the suggestion‑control model: per‑card menu (show‑less / remind‑later / hide / turn‑off) and *Settings › Suggestions* with the **therapist re‑recommend** banner. |
| [`app-03-navigation-motion.png`](./app-03-navigation-motion.png) | The **navigation + motion map** — how you reach every screen, and the three transitions (fade‑through tabs · shared‑axis push · sheet slide‑up). |

## Clinician / admin web console — crisp, dense register
See [COMPANION_WEB_DESIGN.md](../COMPANION_WEB_DESIGN.md)

| File | What it shows |
|---|---|
| [`web-01-console.png`](./web-01-console.png) | The reinvented console: **Team & roles** (editable org, role table, sysadmin has no clinical access) and the **client workspace** (patient + care team + notes with open/private tabs + provenance‑labeled assessments + consent/access trail, all in one view). |

## Still to render
- Patient's **own** console (their care team, grant/revoke, own audit log).
- **Access Guard / behavioral‑IDS** and the **revocation** console.
- The no‑code **builder** (provenance‑aware).
- **Onboarding**, the reworked **More** library, a full **Validated** assessment.
