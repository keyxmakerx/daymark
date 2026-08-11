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
| [`app-04-safety-plan.png`](./app-04-safety-plan.png) | The **safety plan** in three states — empty (the invitation), filled (the read view for a hard moment), and editing (with its **Adapted** provenance badge and disclaimer). Original wording, **not** the Stanley‑Brown form, which requires the authors' written permission to program electronically. The crisis button is data‑driven from the user‑editable `CrisisStore`, so it reads "Call or text 988" by default and can be replaced outside the US. |

## Clinician / admin web console — crisp, dense register
See [COMPANION_WEB_DESIGN.md](../COMPANION_WEB_DESIGN.md)

| File | What it shows |
|---|---|
| [`web-01-console.png`](./web-01-console.png) | The reinvented console: **Team & roles** (editable org, role table, sysadmin has no clinical access) and the **client workspace** (patient + care team + notes with open/private tabs + provenance‑labeled assessments + consent/access trail, all in one view). |
| [`web-02-access-guard-and-builder.png`](./web-02-access-guard-and-builder.png) | The **Access guard** (behavioral‑IDS flags table, a paused session with step‑up + kill‑switch actions, and true revocation shown as live key‑rotation) and the provenance‑aware **builder** (author items, required provenance tier, honesty‑gate run before publish). |
| [`web-03-clinician-redesign.png`](./web-03-clinician-redesign.png) | The **"cool chrome, warm content"** direction and the six clinician screens it generates: sign‑in as a stated contract, **Today**, the **calendar** (four event kinds told apart by shape), the **client record** (band trend, provenance, the boundary in the margin), **Assign** with its four‑step lifecycle, and the dark **server‑admin console** (live log, audit chain, and the "what this console must never show" specification). Plus the extended token system — chrome layer, structural indigo, one alarm hue — and the motion inventory. |
| [`web-04-roles-builders-compliance.png`](./web-04-roles-builders-compliance.png) | The access‑control model from [COMPANION_ACCESS_CONTROL.md](../COMPANION_ACCESS_CONTROL.md) given a UI: the **three planes**, one appointment rendered **four times** (front desk / clinician / org admin / sysadmin, three of them footed `decrypts: nothing`), the **front‑desk console**, the **plan builder**, the **tool builder** with its honesty gate as a live linter, the **prescriber delta**, the **admin identity console** (2FA by factor *kind*, behavioural guard, friction budget), and the **compliance lanes** — what can be automated, what must be a human assessment, and why no compliance score is ever rendered. |

> **Note on re‑rendering 03 and 04:** both are long, scroll‑revealed pages. Render them with
> `--force-prefers-reduced-motion` (which makes the page mark every section visible immediately)
> and `--force-device-scale-factor=1` — at 2× the page exceeds Chromium's ~16 384 px texture limit:
> ```
> chromium --headless=new --hide-scrollbars --force-prefers-reduced-motion \
>   --window-size=1400,15000 --virtual-time-budget=8000 \
>   --screenshot=out.png "file://$PWD/web-03-clinician-redesign.html"
> ```

## Still to render
- Patient's **own** console (their care team, grant/revoke, own audit log) — register TBD (warm app vs. crisp console).
- **Onboarding**, the reworked **More** library, and a full **Validated** assessment (warm app register).
- The **gentle-support offer** at its new frequencies — today it force-navigates to the support
  screen on *every* save at Awful/Bad, which a maintainer reported as genuinely distressing during a
  depressive episode ("the same popup every single time"). Needs a render of the dismissible offer
  and the *Settings → how often* control that replaces the single on/off boolean.
