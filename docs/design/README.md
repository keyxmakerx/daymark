# Design mockups

Concept renders made while designing Daymark, kept as history. Each is a self-contained **`.html`**
file, most with the **`.png`** rendered from it. They show flow, hierarchy and direction; they are not
specifications. Where a mockup differs from the product, the code and the design documents win:
[DESIGN.md](../DESIGN.md) for the phone app, [COMPANION_DESIGN_SYSTEM.md](../COMPANION_DESIGN_SYSTEM.md)
and [COMPANION_UX.md](../COMPANION_UX.md) for the web consoles.

> **How to re-render** (headless Chromium):
> ```
> chromium --headless=new --hide-scrollbars --force-device-scale-factor=2 \
>   --window-size=<W>,<H> --screenshot=out.png "file://$PWD/<file>.html"
> ```
> The long, scroll-revealed pages (web-03 and web-04) need `--force-prefers-reduced-motion`, which
> marks every section visible at once, and `--force-device-scale-factor=1`, because at 2× they exceed
> Chromium's ~16 384 px texture limit:
> ```
> chromium --headless=new --hide-scrollbars --force-prefers-reduced-motion \
>   --window-size=1400,15000 --virtual-time-budget=8000 \
>   --screenshot=out.png "file://$PWD/web-03-clinician-redesign.html"
> ```

## Phone app — the warm "modern paper" register

| File | What it shows |
|---|---|
| [`app-01-home-daily-loop.png`](./app-01-home-daily-loop.png) | The reworked **Home** (one-tap check-in, glance, Signals as a router), **Check-in**, the **For you** router, **Insights**, a **custom tool with its provenance**, and the built-in **safety plan**. |
| [`app-02-journal-goals-controls.png`](./app-02-journal-goals-controls.png) | **Journal** and **Goals** (weekly progress and implementation intentions), plus the suggestion controls: the per-card menu (show less / remind later / hide / turn off) and *Settings › Suggestions* with a therapist "re-recommend" banner. |
| [`app-03-navigation-motion.png`](./app-03-navigation-motion.png) | The **navigation and motion map** — how you reach every screen, and the three transitions (fade-through tabs · shared-axis push · sheet slide-up). |
| [`app-04-safety-plan.png`](./app-04-safety-plan.png) | The **safety plan** in three states — empty (the invitation), filled (the read view for a hard moment) and editing (with its **Adapted** provenance badge and disclaimer). Original wording, not the Stanley-Brown form ([INSTRUMENTS.md](../INSTRUMENTS.md)); the crisis button reads from the user-editable crisis contact, "Call or text 988" by default. |

## Web consoles

All four web consoles — the owner's included — now use one system, "cool chrome, warm content"
(web-03); the warm register belongs to the phone app.

| File | What it shows |
|---|---|
| [`web-01-console.png`](./web-01-console.png) | The first "crisp, dense" console direction, superseded by web-03: **Team & roles** (an editable organisation, a role table, a sysadmin with no clinical access) and the **client workspace** (patient, care team, notes, provenance-labelled assessments and the consent and access trail in one view). |
| [`web-02-access-guard-and-builder.png`](./web-02-access-guard-and-builder.png) | Same direction: the **access guard** (behavioural flags, a paused session with step-up and kill-switch actions, revocation shown as key rotation) and the provenance-aware **tool builder** (required tier, honesty gate run before publish). |
| [`web-03-clinician-redesign.png`](./web-03-clinician-redesign.png) | **"Cool chrome, warm content"** — the direction the design system adopted — and the six clinician screens it generates: sign-in as a stated contract, **Today**, the **calendar** (four event kinds told apart by shape), the **client record**, **Assign** with its lifecycle, and the dark **server-admin console**. Plus the token system (chrome layer, structural indigo, one alarm hue) and a motion inventory. |
| [`web-04-roles-builders-compliance.png`](./web-04-roles-builders-compliance.png) | The access-control model of [COMPANION_ACCESS_CONTROL.md](../COMPANION_ACCESS_CONTROL.md) given a UI: the **three planes**, one appointment rendered four times (front desk, clinician, org admin, sysadmin), the front-desk console, the plan and tool builders, the prescriber delta, the admin identity console, and the **compliance lanes** — what can be automated, what needs a human assessment, and why no compliance score is ever rendered. |
| [`web-05-companion.html`](./web-05-companion.html) (HTML only) | **The companion dialogue** — "It asks, then it listens": the same person on different weeks getting different conversations from their own signals, a clinician writing dialogue with the tool builder they already have, and what it costs to let someone else's logic run on your phone (the findings in [COMPANION_DIALOGUE.md](../COMPANION_DIALOGUE.md)). |
| [`web-06-report-projects.html`](./web-06-report-projects.html) (HTML only) | **The report and projects** — the PDF report as two sheets, four sides, four jobs (a between-session summary, detail, the person's own words, provenance and verification); goals as projects, with the rules engine offering steps and showing its working; and three candidate names for the clinician platform. |
