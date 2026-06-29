# Daymark Companion — Cross-Surface UX & Information Architecture

> ## ⚠️ STATUS: DESIGN ONLY — NO CODE EXISTS YET
>
> **Nothing in this document is implemented.** There is no Companion web app, no report
> viewer, no assessment runner, and no therapist portal in the shipping product today.
> This is a **build-ready UX/IA contract** — a document to be reviewed, sequenced, and
> possibly built. It may change or be dropped.
>
> This document specifies the UX and information architecture tying together the
> Companion's three surfaces. It is **subordinate to** and consistent with
> [COMPANION_README.md](COMPANION_README.md),
> [COMPANION_SCOPE.md](COMPANION_SCOPE.md),
> [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md),
> [COMPANION_SECURITY.md](COMPANION_SECURITY.md),
> [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md),
> [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md),
> [COMPANION_FEATURES.md](COMPANION_FEATURES.md),
> [COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md),
> [INSTRUMENTS.md](INSTRUMENTS.md), [DESIGN.md](DESIGN.md), [FEATURES.md](FEATURES.md),
> and the prime directive in [../HANDOFF.md](../HANDOFF.md) §0. **Where any wording here
> appears to grant a stronger guarantee than those docs, those docs win.** The flagship
> phone build stays fully offline (no `INTERNET` permission); everything below lives only
> in the opt-in Sync flavor plus the self-hosted container.

## Contents

1. [Design principles for this layer](#1-design-principles-for-this-layer)
2. [The three surfaces at a glance](#2-the-three-surfaces-at-a-glance)
3. [Shared design language & token deltas](#3-shared-design-language--token-deltas)
4. [Surface A — Owner report viewer / dashboard (incl. Phase-0)](#4-surface-a--owner-report-viewer--dashboard-incl-phase-0)
5. [Surface B — Assessment / Test runner](#5-surface-b--assessment--test-runner)
6. [Surface C — Therapist portal](#6-surface-c--therapist-portal)
7. [Key flows (step lists)](#7-key-flows-step-lists)
8. [Report & dataviz UX](#8-report--dataviz-ux)
9. [Consent & sharing UX (no dark patterns)](#9-consent--sharing-ux-no-dark-patterns)
10. [Privacy-by-design microcopy & trust signals](#10-privacy-by-design-microcopy--trust-signals)
11. [Empty / loading / error / offline states](#11-empty--loading--error--offline-states)
12. [Accessibility, i18n, motion](#12-accessibility-i18n-motion)
13. [Component & route inventory](#13-component--route-inventory)
14. [Build checklist](#14-build-checklist)

---

## 1. Design principles for this layer

These extend the project principles to the cross-surface UI specifically.

1. **The phone is home; the server is a window.** Every server surface labels itself as a *replica/viewer*, never the source of truth. The owner's phone (Sync flavor) is always named as the authoritative copy and the only secret-handling owner path.
2. **Honesty is a UI feature, not fine print.** Every documented retraction (no PFS, honest-server-only revocation, lower-assurance browser portal, audit-suppression) has a *plain-language surfaced* counterpart in-product. We never let the UI imply a guarantee the crypto doesn't make — including the trap of a served page claiming to police *itself*.
3. **Non-diagnostic by framing, everywhere.** Per [../HANDOFF.md](../HANDOFF.md) §0 and [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) §2, every score/band/plan surface carries fixed, **client-rendered** "self-check, not a diagnosis; scores are not clinical thresholds" framing. No risk verdicts, no auto-escalation, association-not-causation language on all correlations.
4. **Consent is explicit, granular, time-boxed, revocable — and reversible-looking.** No pre-checked boxes, no "select all," no dim "decline," no urgency, no "your therapist is waiting." The safe default is *share less, for less time*.
5. **The big screen earns its keep.** The sit-down surfaces do what a phone can't: year overviews, side-by-side period comparison, a long-form journal reader, a focus/attention task, and print/export. Layout is multi-column and keyboard-navigable.
6. **Strict-CSP native.** No CDNs, fonts, analytics, or third-party origins. Fonts are vendored (Fraunces + Inter OFL TTFs, the same as the flagship's planned bundle), WASM crypto is same-origin, and the UI degrades gracefully where a feature can't be self-hosted. **CSP and SRI are not counted as zero-knowledge defenses against the first-party origin** (COMPANION_SECURITY R5) — and the UI says so.

---

## 2. The three surfaces at a glance

| | **A — Owner viewer/dashboard** | **B — Assessment/Test runner** | **C — Therapist portal** |
|---|---|---|---|
| **Who** | The owner, at a desk (browser) | The owner, sitting down (browser, focused) | The owner's clinician (browser; pinned/installed preferred) |
| **Data source** | Phase 0: a dropped-in backup JSON, decrypted in-browser. Phase 1+: latest synced snapshot, decrypted in-browser | The runner *produces* results that flow into the same E2EE `BackupData` snapshot and sync symmetrically | A single curated, owner-signed **share** blob, decrypted in-memory only |
| **Auth** | Phase 0: none (local file). Phase 1+: server login (passkey) to fetch ciphertext; passphrase decrypts **in browser, discouraged** vs native | Same session as A (it's part of the owner web app) | Passkey (WebAuthn-PRF) primary; TOTP fallback (weaker, flagged) |
| **Writes?** | No (read/render/export only) | Yes — writes results into the snapshot; syncs back | No raw-record writes; may author **game plans** (signed, one-way, owner must accept) |
| **Trust posture** | Lower-assurance convenience path (server-served JS); native phone is the secret-handling path | Same as A | Bounded, read-only, expiring, revocable; out-of-band-pinned identity |
| **First open** | "Drop your backup file" (Phase 0) / "Unlock to view your synced data" (Phase 1+) | "Choose a check-in or test" picker with non-diagnostic intro | One-link invite → passkey → mutual fingerprint verify → empty inbox until a share arrives |

All three share one shell (header wordmark, theme toggle, lock/sign-out, a persistent **Trust strip**) so the security posture is always visible. The therapist portal is a *separate authenticated context* on the same origin; an owner session can never see therapist keys and vice-versa.

---

## 3. Shared design language & token deltas

The Companion web UI is the flagship "modern paper" language (see [DESIGN.md](DESIGN.md) and [COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md)) ported to the web, **honestly recolored for the web's lower assurance** (a subtle but real signal that this is the convenience path, not the phone).

```css
/* companion-tokens.css — vendored, no @import, no web-font CDN */
:root {
  /* paper (light) — mirrors DESIGN.md Color.kt */
  --paper:    #F4EFE6;  --sheet:   #FCFAF5;  --ink:     #2A2722;
  --soft:     #6B655B;  --faint:   #A49C8E;  --hairline:#E7DFD1;  --accent: #33302A;
  /* mood scale awful→rad — NEVER recolored, NEVER dynamic */
  --mood-1:#AE5747; --mood-2:#C27C46; --mood-3:#C6A24E; --mood-4:#8FA268; --mood-5:#5E8A66;
  /* security semantics — used ONLY for trust signals, never decoratively */
  --trust-locked:  #5E8A66;  /* "encrypted / can't be read by server" */
  --trust-caution: #C27C46;  /* "lower-assurance / leaves your device / metadata visible" */
  --trust-open:    #AE5747;  /* "plaintext to a person / unrevocable-once-read" */
  --radius-card:15px; --radius-chip:22px; --hair:1px solid var(--hairline);
}
:root[data-theme="night"]{  /* "night paper" */
  --paper:#1B1A17; --sheet:#24221D; --ink:#EBE5D8; --hairline:#34312A; --accent:#EBE5D8;
}
```

```html
<!-- vendored fonts only; matches the flagship's planned Fraunces+Inter bundle -->
<style>
@font-face{font-family:"Fraunces";src:url("/assets/fonts/Fraunces.woff2") format("woff2");font-display:swap}
@font-face{font-family:"Inter";src:url("/assets/fonts/Inter.woff2") format("woff2");font-display:swap}
</style>
```

**CSP (must match [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6 verbatim):**

```
Content-Security-Policy:
  default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self';
  img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none';
  base-uri 'none'; frame-ancestors 'none'; form-action 'self'
```

- **`PaperSurface` → `.paper-surface`**: flat sheet, 1px hairline, no tonal elevation, at most a whisper of shadow (none in night). Serif (Fraunces) for headings/wordmark and the italic "diary-note" journal voice; sans (Inter) for body/labels/numbers.
- **Mood faces, activity glyphs, star renderer, pose figures** are reused as the same original vector assets (no emoji, no third-party packs).
- **Security semantic colors are reserved.** A green "locked" dot *only ever* means "this is end-to-end encrypted; the server can't read it." We never use the mood greens for trust or vice-versa.
- **Honest caveat on trust color (R5):** the served browser portal is lower-assurance *regardless of network state*, because the origin serves the JS that handles the data. We therefore reserve `--trust-locked` (green) for surfaces where assurance is genuinely high (e.g. an integrity-verified, self-contained context), and we do **not** paint a served page green merely because it claims to be offline. See §10.1.

---

## 4. Surface A — Owner report viewer / dashboard (incl. Phase-0)

### 4.1 IA / navigation

A left rail (collapsible to icons on narrow widths), a top **Trust strip**, and a main canvas.

```
┌ Daymark · Companion ───────────────────────[ ◐ theme ] [ 🔒 lock ]─┐
│ 🛈 Trust strip: "Decrypted in your browser · this device only · …" │
├──────────┬─────────────────────────────────────────────────────────┤
│ Overview │   (main canvas — the selected section)                   │
│ Year     │                                                          │
│ Periods  │                                                          │
│ Mood↔…   │                                                          │
│ Journal  │                                                          │
│ Check-ins│                                                          │
│ Tests    │   ← Surface B lives under here as a peer section         │
│ Sleep    │                                                          │
│ Report   │                                                          │
│ ──────── │                                                          │
│ Share ▸  │   ← consent flow (Surface→C bridge); de-emphasized,      │
│ Plans ▸  │     never the first thing the eye lands on               │
│ Access   │                                                          │
│ Settings │                                                          │
└──────────┴─────────────────────────────────────────────────────────┘
```

Top-level sections:

- **Overview** — the dashboard landing: a compact "year in pixels/stars" thumbnail, headline counts (entries, average mood band, current streak — descriptive only), the latest check-in bands, and a *"For you"* strip rendered from the **same deterministic Signals rules** as the phone (no new model, ever). Cards link into deeper sections.
- **Year** — full-screen year overview (pixels + stars toggle), the "Review my year" walkthrough.
- **Periods** — this-period-vs-last comparison with a Week/Month/Year scale toggle.
- **Mood ↔ factors** — correlations ("lifts you up / weighs you down"), day-of-week and time-of-day, **always labeled association, never cause**, with the same ≥5-occurrence / ≥14-day sample gates as the app.
- **Journal** — the long-form reader (see §8.4).
- **Check-ins** — PHQ-9/GAD-7/WHO-5 score+band history and trends (scores/bands only; item-9 structurally absent).
- **Tests** — entry point into Surface B (the runner).
- **Sleep** — sleep diary, derived metrics, before/after, the descriptive sleep↔mood line.
- **Report** — assemble + print/export (PDF/JSON/CSV).
- **Share / Plans / Access** — the therapist-relationship area (bridge to Surface C); deliberately lower in the rail.
- **Settings** — theme, lock, device/pairing, retention, danger zone.

### 4.2 First open

**Phase 0 (offline drag-in, no server, no network) — recommended first ship:**

A single centered card on `--paper`:

```
        ┌────────────────────────────────────────────┐
        │            Daymark · Sit-down review        │
        │                                             │
        │     ┌───────────────────────────────┐       │
        │     │   ⤓  Drop your backup file     │       │
        │     │   ( .json exported from the    │       │
        │     │     Daymark app )  or  Browse  │       │
        │     └───────────────────────────────┘       │
        │                                             │
        │  🛈 This build is intended to run entirely  │
        │     on your computer. Before trusting it    │
        │     with an encrypted backup, verify you    │
        │     have an authentic copy (see "Verify     │
        │     this build" below).                     │
        │                                             │
        │  Encrypted backup? You'll be asked for the  │
        │  passphrase. In an authentic offline build  │
        │  it is used here only and never sent — but  │
        │  a page can't prove that about itself, so   │
        │  trust comes from the build's integrity,    │
        │  not from a promise on this screen.         │
        │                                             │
        │            [ Verify this build ▸ ]          │
        └────────────────────────────────────────────┘
```

- The page is a **static, vendored local web app** that can be opened from disk. An authentic build makes **zero network requests** (enforced at design time by `connect-src 'self'` and a build with no remote references).
- **Honest framing (R5, reviewer must-fix):** we do **not** claim a runtime "offline self-test" that detects or blocks exfiltration, and we do **not** tell the user "if the test fails, don't enter your passphrase." A hostile-repackaged page runs any such self-test *itself*, and CSP does not stop navigation / `<a>` / `<form>` / `img`-`data:` exfiltration paths. Instead, the **"Verify this build"** affordance shows the expected **reproducible-build hash/signature** and instructions to compare it, so trust comes from *integrity of the artifact you opened*, never from a self-assertion the page makes about its own behavior. This is the same first-party-origin error the docs retract in R5, and we refuse to reproduce it at the UI layer.
- Drop a plaintext JSON → render immediately. Drop/select an encrypted blob → an **in-page** passphrase prompt; decryption (Argon2id + XChaCha20-Poly1305, WASM) runs locally with a progress indicator (Argon2id at ≥256 MiB is intentionally slow — show "Unlocking… this is deliberately slow to protect you").
- No "remember me," no storage of the passphrase, no IndexedDB cache of plaintext by default. A "Done / clear" button zeroizes in-memory state.

**Phase 1+ (server-backed):** first open after login shows **Overview**, populated from the latest synced snapshot fetched as ciphertext and decrypted in-browser. The Trust strip names this as the lower-assurance path and points to the native phone app for anything secret. **Entering the master passphrase in the browser is discouraged in copy** (COMPANION_SECURITY R5); the preferred Phase-1 owner reading path keeps the snapshot key handling on the native app and treats the browser as a viewer of an already-unlocked export the owner pushes, *or* an explicitly-acknowledged lower-assurance unlock.

---

## 5. Surface B — Assessment / Test runner

Per [../HANDOFF.md](../HANDOFF.md) §9 (Phase 2), [COMPANION_FEATURES.md](COMPANION_FEATURES.md), and [INSTRUMENTS.md](INSTRUMENTS.md), the runner offers **license-clean self-reports** and **one original, self-authored attention/CPT-style "sit-down" task** — never TOVA/Conners/CAARS or any copyrighted instrument. It is the home of pillar (2): the "sit-down" cognitive/attention testing that doesn't fit on a phone.

### 5.1 IA

```
Tests
├─ Questionnaires (sit-down self-reports)
│   ├─ PHQ-9  · GAD-7 · WHO-5     (bundled, license-clean; same as app)
│   ├─ ASRS v1.1 (public-domain)  · IPIP scales (public-domain)   [Companion-only]
│   └─ Daymark sleep self-checks  (original, app-authored)
├─ Focus & attention (original task)
│   └─ "Steady Attention" — a self-authored CPT-style task
└─ History  (per-instrument trend; same scores-only invariant)
```

> **License gate (hard, reviewer must-fix dependency):** the runner's catalog is built **only** from public-domain or self-authored instruments tracked in [INSTRUMENTS.md](INSTRUMENTS.md). **Before this gate can be enforced, INSTRUMENTS.md must be amended to list ASRS v1.1 and the IPIP scales** with their license/status and required attributions (ASRS WHO notice; IPIP public-domain citation), because two of the instruments this design bundles are Companion-only and are not yet in the named source of truth. Once listed, a code comment + CI check forbids adding ISI/ESS/STOP-Bang/PSQI/PANAS/WEMWBS/DASS-21/PSS/ACT measures or TOVA/Conners/CAARS. The "Steady Attention" task is original and described in our own words.

### 5.2 Pre-test intro (mandatory, fixed copy)

Every test starts with a non-skippable intro card:

```
┌ Steady Attention — a focus check ───────────────────────────────┐
│ This is a self-check, not a diagnosis. It does not detect,      │
│ diagnose, or treat any condition. Results describe how this one │
│ sitting went — nothing more.                                    │
│                                                                  │
│ • ~8 minutes · quiet room · one sitting                          │
│ • Only your summary results are saved — never a verdict          │
│ • You can stop anytime; a stopped run is discarded               │
│                                                                  │
│  [ Not now ]                         [ I understand — begin ]    │
└──────────────────────────────────────────────────────────────────┘
```

### 5.3 Runner shell (questionnaire)

- One item per screen (or a short paged group), large tap/keyboard targets, a thin top progress bar, Back always available, no timers that pressure the user, autosave of in-progress answers **in memory only** (no server, no plaintext on disk).
- **Item-9 / self-harm scoring stays structurally absent** from anything that could ride into a share. PHQ-9 follows the app's exact behavior: store **score + band only**, never item answers; if item-9 is non-zero, surface the **same offline, static crisis resources** — never a risk verdict, never an auto-escalation, never a network call.
- In-app attribution is shown on each instrument screen (PHQ-9/GAD-7 free to reproduce; WHO-5 © WHO non-commercial with attribution; ASRS/IPIP public-domain citations) — sourced from [INSTRUMENTS.md](INSTRUMENTS.md).

### 5.4 Runner shell ("Steady Attention" attention task)

- Pre-task: a **practice block** with feedback, then "Ready?" gate. A full-screen, distraction-free canvas (vendored, Canvas/2D, no audio recording, no camera).
- The task records only **derived summary metrics** (e.g. mean/SD reaction time, omission/commission counts, a consistency index) — never a clinical interpretation, never the word "ADHD," never a cutoff. The result card uses descriptive, association-not-causation language and the standard disclaimer band.
- Interruption handling: tab blur / lost focus marks the run **invalid** and offers a clean restart (so a noisy run is never silently scored). A stopped run is discarded.

### 5.5 Results → snapshot (symmetric sync)

On completion, the runner writes an `AssessmentResult`-shaped record (score/band/summary-metrics only) into the **same E2EE `BackupData` snapshot** and lets it sync symmetrically (Flow A) — identical treatment to phone-authored data. The result then appears in **Tests → History**, in **Overview** trends, and is eligible for inclusion in a therapist share as **scores/bands only**. There is **no separate test-results channel**; the snapshot is the one spine.

---

## 6. Surface C — Therapist portal

Designed for a **non-technical clinician**. Goal: get from a single link to "I can see what my client chose to show me" with minimal friction and maximal honesty, and let them write back a plan that the owner must accept. See [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) for the underlying protocol.

### 6.1 IA

```
┌ Daymark · Shared with you ──────────────[ who am I? ] [ sign out ]─┐
│ 🛈 Read-only · expires {date} · {client alias} chose what to show  │
├────────────┬───────────────────────────────────────────────────────┤
│ This share │  (curated records: moods, journal excerpts, check-in   │
│ Timeline   │   scores/bands, sleep — exactly what was shared)        │
│ Check-ins  │                                                         │
│ Notes      │                                                         │
│ ────────── │                                                         │
│ Game plan  │  ← author / view plans (signed, one-way, client accepts)│
│ Verify ID  │  ← fingerprint pinning status (always reachable)        │
└────────────┴───────────────────────────────────────────────────────┘
```

There is exactly **one** relationship in view (v1 lock: one therapist, one keypair, one device). No patient list, no enumeration, no search across other people — and the UI says so.

### 6.2 First open (one-link invite → passkey → verify)

The clinician receives a **link + a short verification phrase out-of-band** (read aloud in session, on paper, or QR in person — never emailed/SMS'd by the server; the container makes no outbound calls). On open:

1. **Welcome / what this is** — plain copy: *"{Client} wants to share a slice of their Daymark journal with you, read-only, until a date they set. You'll set up a secure key on this device. There's no password to remember and no account."*
2. **Enter the verification phrase** (from the invite, out-of-band). Validates against the server's Argon2id hash; capped backoff (not burn-after-5).
3. **Create your passkey** — a single biometric/PIN prompt ("Use Face ID / fingerprint / device PIN"). Plain framing: *"This is your key. It lives on this device. We never see it."* Keypairs (X25519 + Ed25519) generate **in-client**; only public keys + the wrapped private blob + `prfSalt` upload.
4. **Verify each other (the safety step)** — see §6.3.
5. Land on an **empty "This share" inbox**: *"You're set up. Nothing's shared yet — {Client} will send records when they're ready. We'll show them here."*

### 6.3 Out-of-band fingerprint verification, made approachable

The hardest concept (TOFU + mutual SAS) is presented as a **"read these words to each other"** moment, because it is one — ideally done live in session.

```
┌ One safety step ────────────────────────────────────────────────┐
│ So no one can impersonate either of you, read these words aloud  │
│ to {Client} and check they match on both screens.                │
│                                                                  │
│   Your client should see, on their phone:                        │
│     ┌──────────────────────────────────────────┐                 │
│     │  river · candle · seven · maple · quiet   │   [ ▢ QR ]      │
│     └──────────────────────────────────────────┘                 │
│                                                                  │
│   They will read you THEIR words. Do these match what they say?  │
│                                                                  │
│   [ They don't match — stop ]        [ They match — continue ]   │
└──────────────────────────────────────────────────────────────────┘
```

- **Mutual and mandatory** ([COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) §5.2): the therapist confirms the owner's words *and* reads their own for the owner to confirm. QR is offered for in-person scans. The `recipientFp` inside a payload is necessary-but-insufficient — the spoken SAS comparison is the binding trust step.
- "They don't match" is a **prominent, safe** choice (not a tiny link) and routes to a clear stop: *"Don't continue. Contact {Client} another way. Someone may be intercepting."* The server never vouches; the UI makes skipping verification impossible (no share is sealed to an unpinned key; no plan is accepted from one).
- A persistent **"Verify ID"** section lets either party re-check the pinned fingerprint anytime, and shows a loud banner if a key ever appears changed (re-pair required).

### 6.4 The read-only share view

- A clearly-bounded canvas: a header band stating **read-only · curated by {Client} · expires {date}**, and *"You're seeing only what {Client} chose to share — a slice, not their whole journal."*
- Renders the materialized subset: mood timeline (faces + notes the owner kept), selected journal excerpts, **check-in scores/bands only** with the standard non-diagnostic disclaimer (never cutoffs), sleep, selected trackers. Nothing outside the bundle exists to click into.
- **Owner signature is verified before anything renders** ([COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) §5.4). On verify-fail: refuse to render and show *"We couldn't confirm this came from {Client}. For your safety, nothing is shown."*
- **Thin, in-memory viewer**: no default disk cache; re-fetch per session. A subtle footer states *"This view lives only in this browser session and clears when you close it."*
- An honest expiry/revocation banner (see §10) and a one-screen *"What I can and can't see"* explainer (mirrors the [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) §6 table).

### 6.5 Game-plan authoring

- A structured composer: goals (optional weekly target), exercises, between-session tasks (optional due/recurrence), free-text notes, optional review cadence.
- **Fixed disclaimer the clinician cannot remove** (server can't strip it either — it's client-rendered): *"This is guidance you're sending to your client. Daymark isn't a medical record and isn't making any diagnosis. Your client can accept, ignore, or delete it."*
- On send: a **step-up passkey "sign-off"** (fresh assertion bound to the live session). The plan is Ed25519-signed, sealed to the owner's pinned X25519 key, posted as one blob. The composer shows: *"Sent. {Client} will see this as a proposal they can accept on their phone. You can send an updated version anytime; you can't edit or recall what they've already accepted."* (one-way, append-only, supersede-not-mutate, honest about no-recall).

---

## 7. Key flows (step lists)

### 7.1 First-run / setup (owner)

1. Owner self-hosts the container (`docker compose up` behind their own reverse proxy) — out of scope for UI (see [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md)), but the **first web page** is a friendly *"Your Companion is running"* status with a link to the deployment docs and a clear *"this server can't read your data"* statement.
2. Owner opens the portal; **creates a server login passkey** (this authenticates *to the server* and is separate from the E2EE passphrase — copy says so explicitly).
3. Owner is told, prominently: **"Set your sync passphrase in the Daymark phone app, not here."** The native Sync flavor is the secret-handling path; the browser is a viewer.
4. On the phone (Sync flavor): owner sets/derives the sync passphrase (Argon2id), which **never leaves the device**; the app shows the keycheck + a one-time *"write this down — if you lose it, your backups are unrecoverable (your phone keeps the original)"* warning.
5. Owner returns to the web Overview; ciphertext is fetched and decrypted in-browser (lower-assurance acknowledgment shown once).
6. Optional: owner runs the **Phase-0 offline viewer** with no server at all by dropping an exported backup file (after verifying the build's integrity per §4.2).

### 7.2 Pairing a device for sync (owner, second device)

> v1 sync is **single-writer, last-snapshot-wins** (no row-merge). The UI must teach this honestly.

1. On the new device, install the **Sync flavor** and choose **"Sync with my Companion."**
2. Enter the server address; authenticate with a **passkey** (or register one for this device).
3. Enter the **sync passphrase** (same one; never transmitted — it derives the key locally). A keycheck token confirms it's correct without revealing it.
4. The device pulls the latest snapshot and shows a **plain conflict explainer** if local data exists: *"This device has its own entries. v1 sync keeps the newest full snapshot and replaces older ones — it does not merge two devices yet. Choose which becomes the source."* Options: **Use server's newest** (replace local) / **Push this device's data as newest** (with a clear "this replaces the server's current snapshot; history is kept") / **Export first** (escape hatch).
5. Anti-rollback is silent-but-honest: the app refuses to regress past its **local trust watermark** and explains if it blocks a stale snapshot. Server "expected hash" lists are never trusted without local manifest verification.
6. Done: a **device list** in Settings shows each paired device label, last sync time, and a **"remove device"** control.

### 7.3 Run a questionnaire/test and review results (owner)

1. **Tests** → pick an instrument or "Steady Attention."
2. Read the **non-diagnostic intro** → "I understand — begin."
3. Complete the runner (questionnaire one-item-paged, or the focus task with a practice block). Stop anytime → discarded.
4. On finish: a **result card** — score/band or summary metrics, descriptive copy, the standard disclaimer, and (PHQ-9 item-9 non-zero) the offline crisis flow. **No verdict.**
5. Result writes into the snapshot and **syncs symmetrically**; it appears in **History**, **Overview** trends, and becomes eligible for sharing as scores/bands only.
6. Optional: **"Add to a report"** or **"Include in a share"** (both land in their own consent-gated flows; nothing auto-shares).

### 7.4 Build + send a therapist share (owner) — consent UX

> The whole flow defaults to *less data, less time*. See §9 for the anti-dark-pattern rules.

1. **Share ▸ → New share** (only enabled once a therapist is paired & verified; otherwise the CTA is **"Invite your therapist first"**).
2. **Who** — confirm the pinned, verified therapist (shows the verified fingerprint + "verified on {date}"). If unpinned, the flow stops and routes to verification.
3. **What** — granular pickers, all **opt-in / unchecked by default**:
   - **Date range** (default: a conservative "last 30 days," not "all time"; choose a window or explicit from/to).
   - **Record types** (mood, journal, check-in **scores/bands only**, sleep, trackers) — each a deliberate toggle, no "select all."
   - **Per-record exclude** and **"strip free-text notes"** options.
   - Self-harm item is **structurally absent** and labeled as such (not a toggle).
4. **Preview exactly what they'll see** — a faithful render of the materialized bundle (this is what the therapist gets, nothing more). A running **"X entries · Y journal notes · scores only · no self-harm item"** summary.
5. **For how long** — an explicit expiry (default a *short* window, e.g. 14 days; max is the owner's choice). Copy: *"Access ends automatically on {date}. There's no auto-renew."*
6. **The honest consent screen** (§9.2) — plain statements of what this does and its real limits (plaintext to a person; can't be un-seen once opened; revocation works against an honest server; metadata exists). A single **"Share this"** primary action; **"Cancel"** equally weighted.
7. **Sign-off** — the native app signs (Ed25519) and seals the CEK to the therapist's pinned key; uploads version 1. A success state shows the active share with **expiry countdown**, **"what they can see,"** and a prominent **"Revoke now."**
8. **Refresh (optional, later):** as new data is logged, owner can publish version N+1 to the *same scope* (narrow/shift-forward only); never auto-widens, never auto-renews.

### 7.5 Review a therapist game plan & accept/decline (owner)

1. **Plans ▸** shows a **PROPOSED** item badged *"From {Therapist} · proposal · not yet added."*
2. Open it: the owner's device has already **verified the Ed25519 signature against the pinned therapist fingerprint** and checked `context` + `recipientOwnerFp == self`. A signature-valid-but-wrong-fingerprint plan is shown as **"⚠ We couldn't confirm this is from your therapist — treat as suspicious,"** never auto-accepted.
3. Read-only preview of goals/exercises/tasks/notes/cadence, with the **fixed non-diagnostic disclaimer**: *"This is guidance from your real clinician. Daymark isn't diagnosing you. You can accept, ignore, or delete this."*
4. **Decline** → recorded locally, never written as a plan; no notification leaves the device. **Accept** → inserted **immutable** into the `game_plans` table (DB v13); the therapist body stays read-only; the owner tracks progress in a separate `game_plan_progress` layer.
5. Optional, **only on explicit accept**: *"Also add a goal/treatment marker for this?"* — guidance never auto-lands in owner data.
6. Updates arrive as **new signed versions** (supersede-not-mutate); progress carries forward by `(lineageId, itemRef)`. A signed **withdrawal tombstone** turns a plan into read-only history; prior owner progress notes are kept as the owner's own data.

### 7.6 Revoking access (owner)

1. **Share ▸** (or the active-share card) → **Revoke**.
2. A confirm sheet states the **honest scope** plainly: *"This stops your therapist from fetching this share from your server going forward. It can't reach back into anything they already opened — like a PDF you already handed over."*
3. Choose **Revoke** (sets `revoked=1`, invalidates the capability token/session, stops re-publishing) and optionally **"Also delete the shared copy from the server"** (hard-delete blob bytes; a metadata tombstone remains for the log).
4. For **real future-data revocation**, offer **"Re-pair with a new key"**: *"For the strongest protection, re-verify your therapist with a new key. Future shares use the new key; the old one can't read new data."* (This is the only colluding-server-proof primitive; the UI says so honestly.)
5. **Access** section logs a `REVOKED` event (owner-readable, metadata-only).

---

## 8. Report & dataviz UX

All dataviz reuses the flagship's analytical posture (descriptive, association-not-causation, sample-gated) on a desk-sized canvas.

### 8.1 Year overview

- Two views toggled: **Year in Pixels** (dense analysis grid, each day tinted by mood mean) and **Year in Stars** (the night-sky keepsake render, fixed dark palette regardless of theme — bg `#16150F`).
- A **"Review my year"** horizontal walkthrough (intro → quarter chapters with star clusters → finale stats), all **factual** strings ("26 days · mostly Good"), never "you felt…."
- Legend + summarizing text alternative for accessibility; a **"Save keepsake (PNG)"** export.

### 8.2 Period comparison

- A genuine side-by-side (the big-screen win): **This period vs. last**, Week/Month/Year toggle, deltas shown as neutral up/down with **descriptive** captions ("average mood Good → Great; 4 more entries"), never a value judgment, never causal.
- A small-multiples row (mood distribution, entries/day, top factors) for the two periods aligned for comparison.

### 8.3 Correlations ("what goes with your mood")

- Two ranked lists, **"lifts you up / weighs you down,"** each item a factor with effect size and sample count, behind the **≥5-occurrence (activity) / ≥14-day (tracker)** gate.
- A persistent banner: **"Association, not cause — these go together; one doesn't necessarily cause the other."** By-day-of-week and by-time-of-day strips alongside.
- A descriptive **sleep ↔ mood** observation when sleep data exists ("on nights you slept better, your mood tended to be higher"), explicitly never-causal.

### 8.4 Journal reader

- A **long-form reading view** the phone can't do well: a left index (date-grouped, search, filter by mood/activity/template), a center column in the italic "diary-note" serif voice at a comfortable measure (~66ch), and a right rail of context (that day's mood face, activities, photo thumbnail).
- Global note search across mood notes + journal. Keyboard navigation (j/k or arrows between entries). Read-only; editing stays on the phone.
- A subtle per-entry "this day in other years" memory surfacer (descriptive, opt-in).

### 8.5 Printable / exportable report

- **Report** section assembles a selectable report: choose sections (Overview, Year, Periods, Correlations, Check-in trends, Sleep, an "In review" recap), a date range, and an option to **include or exclude free-text**.
- **Print** uses a dedicated print stylesheet (paper palette, hairlines, page breaks, the "In review" rules-based recap section) — no network, no remote fonts.
- **Export**: **PDF** (client-rendered, with the app's QR authenticity affordance), **JSON** (a backup), **CSV** (entries). A clear, honest export warning: *"An exported file is plaintext on your disk — store it like you would a printed journal."*
- A **"Build a therapist share instead"** secondary link reminds the owner that a scoped, expiring, revocable share is safer than handing over a static export (the core "strictly better than a plaintext PDF" value prop).

```css
/* print.css gist */
@media print {
  :root{ --paper:#fff; --sheet:#fff; }
  .rail, .trust-strip, .actions { display:none }
  .report-section { break-inside: avoid; border-top:1px solid var(--hairline) }
  .disclaimer { display:block } /* non-diagnostic framing always prints */
}
```

---

## 9. Consent & sharing UX (no dark patterns)

This is a first-class security feature. The owner is sovereign; the UI must make the *safe* choice the *easy* one.

### 9.1 Hard rules (enforced in design review)

| Rule | Why |
|---|---|
| **No pre-checked share toggles; no "select all."** Every record type/range is opt-in. | Granular, deliberate consent. |
| **Default to less.** Default range is short (e.g. last 30 days), default expiry short (e.g. 14 days). | Minimization is the default, not the exception. |
| **"Cancel" / "Decline" are visually equal to the primary action** — same size, weight, contrast. | No dark-pattern asymmetry. |
| **No urgency, no social pressure.** Never "your therapist is waiting," no countdowns to *create* a share, no nagging. | Consent must be unpressured. |
| **Preview before send is mandatory** and shows *exactly* the materialized bundle. | "What I see is what they get." |
| **Expiry is required and visible; no auto-renew, no silent extension.** | Time-boxing is real and legible. |
| **Revoke is always one tap from the active share** and never buried. | Revocability is real and reachable. |
| **Honest limits are shown at consent time, not hidden in a doc.** | No overclaiming (R2/R3/R5/R12). |
| **The self-harm item is absent, and labeled absent** — never a toggle that could imply it's shareable. | Structural non-diagnostic guarantee. |

The materialized-subset preview is not cosmetic: it enforces **crypto-layer minimization** — even a buggy or hostile portal can't leak beyond what the owner's device actually sealed.

### 9.2 The honest consent screen (copy)

```
┌ Before you share ───────────────────────────────────────────────┐
│ You're about to share, with {Therapist} (verified ✓):           │
│   • {N} mood entries, {M} journal notes (last 30 days)          │
│   • Check-in results as scores/bands only — no individual       │
│     answers, and never the self-harm question                   │
│   • Access ends automatically on {date}                          │
│                                                                  │
│ Honest about what this can and can't do:                         │
│   • You're sending real entries to another person. Once they    │
│     open it, you can't un-see it for them — like a PDF you hand  │
│     over.                                                        │
│   • You can revoke anytime. That blocks future access on an      │
│     honest server. For the strongest protection against a       │
│     tampered server, re-pair with a new key.                    │
│   • Your server can see that a share exists and its size/timing  │
│     (not its contents). We pad and minimize this, but can't     │
│     fully hide that you have a therapist.                        │
│                                                                  │
│   [ Cancel ]                                  [ Share this ]     │
└──────────────────────────────────────────────────────────────────┘
```

---

## 10. Privacy-by-design microcopy & trust signals

### 10.1 The Trust strip (persistent, every surface)

A thin band under the header that states, in plain words, the *current* assurance — color-coded by the reserved trust palette and **honest about the surface's real posture**.

> **Reviewer must-fix applied:** the Phase-0 offline viewer is **not** painted `--trust-locked` (green). Per R5, served portal JS is lower-assurance regardless of network state, and a served page handling the master passphrase cannot provide a zero-knowledge guarantee — so green would overclaim. Phase 0 uses the same **caution** treatment as Phase 1, paired with an **integrity-verification call to action** rather than a self-asserted "offline" promise.

| Surface / state | Strip color | Copy |
|---|---|---|
| Phase-0 offline viewer | **caution (amber)** | *"This build is meant to run offline. A page can't prove that about itself — [verify this build's integrity] before unlocking an encrypted backup."* |
| Phase-1 owner web viewer | caution (amber) | *"Decrypted in your browser. This is the convenience view — your phone app is the secure one."* |
| Therapist share view | caution (amber) | *"Read-only · curated by {Client} · expires {date} · clears when you close this."* |
| Active share (owner) | open (red-ish) | *"You're sharing real entries with a person. [Revoke]"* |
| Decryption in progress | neutral | *"Unlocking… deliberately slow to protect your passphrase."* |

### 10.2 "What the server can / can't see" panel

Reachable from every surface (Settings → Privacy, and a 🛈 in the Trust strip). A two-column table generated from the architecture docs, stated honestly:

| The server **can** see | The server **cannot** see |
|---|---|
| Encrypted blobs; their sizes (padded) and timing; that a therapist relationship exists; public keys/fingerprints; login events | Your journal, moods, notes, scores, or game plans (all encrypted; we hold no key); your passphrase; which entries a therapist viewed; a forged share or plan (signatures prevent it) |

Plus the four **plain-language honest limits** (each links to the relevant doc section):

- *"No forward secrecy: if your therapist's key is ever stolen, old shares sealed to it could be read. We bound how long the server keeps them to limit this."* (R2)
- *"Revoke works against an honest server. A tampered server plus a stolen key can still read an already-sent share. Re-pairing with a new key is the strongest fix."* (R3)
- *"This browser view is the convenience path. The phone app is the one that handles your secrets safely; we can't promise zero-knowledge for a page served from a server."* (R5)
- *"The access log shows when your therapist opened a share, but a dishonest server could hide an event until we add tamper-proof logging. Audit IP is off by default (it would geolocate the clinic)."* (R12)

### 10.3 Microcopy patterns

- **Encryption, named, never bragged.** "Encrypted on your device" beats "military-grade." Always say *who can't read it* ("the server can't read this").
- **No absolute claims.** Never "fully private," "totally secure," "cannot be hidden," or "fully revocable." Always scope to the honest threat model.
- **Loss is loud.** Passphrase setup and export both carry the *"lose this and it's unrecoverable; your phone keeps the original"* warning.
- **Non-diagnostic, fixed, client-rendered.** Every score/band/plan/test surface carries the identical disclaimer band, never server-supplied (so a hostile server can't strip it).
- **Association, not cause** on every correlation/insight.
- **No auto-anything.** "We never auto-renew, auto-escalate, or send anything without you tapping."
- **No page polices itself.** Trust in the offline viewer comes from build integrity (a hash you can verify), never from a promise the page makes about its own network behavior.

---

## 11. Empty / loading / error / offline states

| State | Surface | Treatment |
|---|---|---|
| **Empty — no data yet** | Owner viewer | *"No entries to show yet. Open a backup file, or log a few days in the app."* (Phase-0: drop-zone front and center.) |
| **Empty — therapist inbox** | Therapist | *"You're set up and verified. Nothing's shared yet — {Client} will send records when ready."* |
| **Empty — no plans** | Plans | *"No game plans yet. If your therapist sends one, it'll appear here as a proposal you can accept or decline."* |
| **Loading — decrypting** | Owner / therapist | Skeleton paper-surfaces + *"Unlocking… deliberately slow (Argon2id) to protect you."* Never a spinner with no explanation. |
| **Loading — fetching ciphertext** | Owner / therapist | Skeleton + *"Fetching encrypted data…"* |
| **Error — wrong passphrase** | Owner | *"That passphrase didn't unlock this backup. It's case-sensitive. We can't reset it — there's no recovery by design."* (generic, non-enumerating, no lockout-leak) |
| **Error — signature failed** | Therapist (share) / Owner (plan) | Refuse to render. *"We couldn't confirm this came from {the other party}. For safety, nothing is shown."* |
| **Error — expired/revoked** | Therapist | *"This share has ended. Ask {Client} to share again if you still need it."* (403, no detail leak) |
| **Error — fingerprint changed** | Either | Loud banner: *"The other person's security key looks different than before. Stop and re-verify in person — someone may be intercepting."* |
| **Error — server unreachable** | Owner web | *"Can't reach your Companion server. Your phone still has all your data."* (reassert phone-as-source-of-truth) |
| **Integrity not verified (Phase-0)** | Owner | A persistent caution prompt: *"You haven't verified this build's hash. You can browse a plaintext file, but verify the build before unlocking an encrypted backup."* (No false claim that the page can self-detect tampering.) |
| **Disk-full / quota (server)** | Owner | *"Your server is out of space. Syncing is paused; your phone copy is safe."* (fail-closed, honest) |

All error bodies are **generic and non-enumerating** (no stack traces, no "user not found" vs "wrong password" distinction), matching the hardening posture. Lockout/rate-limit are server-side per credential and per IP and never leak via the UI.

---

## 12. Accessibility, i18n, motion

- **Keyboard-first** on every desk surface: full tab order, visible focus rings (hairline + accent), arrow/j-k navigation in the journal reader and report.
- **Screen reader:** every chart has a text alternative (the same descriptive sentence the visual conveys); the Year grid exposes a summarizing description; trust signals are announced (the padlock is not the only cue — text accompanies color).
- **Color is never the only signal** — trust states pair color with an icon + text; mood tints pair with the mood face/label.
- **Large-font / zoom safe** layouts (the runner and consent screens reflow without truncation).
- **Motion:** purposeful directional slide (~240ms, ease) for section changes, mirroring the app; **respects `prefers-reduced-motion`** (crossfade or none). No motion on consent/security screens that could be read as pressure.
- **i18n scaffolding** from day one (externalized strings), even if v1 ships English — consent/security copy must be translatable without layout breakage.
- **WebAuthn UV** is the inherence factor; `Permissions-Policy` disables camera/mic/geolocation except WebAuthn (no QR-by-camera dependency for verification — QR is offered but the word-list path is always available for accessibility).

---

## 13. Component & route inventory

### 13.1 Routes (owner web app)

```
/                      → Overview (or Phase-0 drop-zone if no server/data)
/year                  → Year overview (pixels/stars) + /year/review (walkthrough)
/periods               → Period comparison
/factors               → Correlations / day-of-week / time-of-day
/journal               → Journal reader (+ ?q= search, ?entry= deep link)
/checkins              → Check-in trends (scores/bands only)
/tests                 → Surface B catalog
/tests/run/:instrument → Runner (questionnaire or "steady-attention")
/tests/history         → Per-instrument history
/sleep                 → Sleep diary + insights
/report                → Assemble/print/export
/share                 → Relationship overview + active shares
/share/new             → Consent-gated share builder (steps 7.4)
/plans                 → PROPOSED + accepted game plans (owner)
/access                → Owner-readable audit log
/settings              → Theme, lock, devices/pairing, retention, privacy panel, danger zone
```

### 13.2 Routes (therapist portal — separate authenticated context, same origin)

```
/t/invite/:inviteId    → Enroll (verify phrase → passkey → keygen)
/t/verify              → Mutual fingerprint verification (mandatory before payloads)
/t/share               → Read-only curated share view (timeline/checkins/notes)
/t/plan                → Game-plan composer + sent-plan history
/t/identity            → "Verify ID" pinning status (always reachable)
```

### 13.3 Shared components

`AppShell` (header + Trust strip + rail), `PaperSurface`, `TrustStrip`, `BuildIntegrityCallout` (Phase-0 verify-this-build), `ServerCanSeePanel`, `MoodFaceIcon`, `YearPixelsGrid`, `YearStarsGrid`, `PeriodCompare`, `FactorList` (assoc-not-cause), `JournalReader`, `CheckinTrend`, `RunnerShell` (+ `SteadyAttentionCanvas`), `NonDiagnosticBanner` (fixed, client-rendered), `ConsentSheet`, `ShareBuilder`, `SharePreview`, `ExpiryBadge`, `RevokeSheet`, `FingerprintVerify` (word-list + QR), `PlanComposer`, `ProposedPlanCard`, `AuditList`, `EmptyState`, `DecryptProgress`, `ErrorBoundary` (generic, non-enumerating).

---

## 14. Build checklist

**Cross-surface shell & trust**

- [ ] One vendored web app, strict CSP exactly per [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6; fonts self-hosted (Fraunces+Inter OFL); zero third-party origins; no analytics.
- [ ] Persistent **Trust strip** on every surface, honest per surface (offline-but-lower-assurance / lower-assurance browser / therapist read-only / active-share).
- [ ] **"What the server can/can't see"** panel reachable everywhere, carrying the four honest limits (no PFS, honest-server-only revoke, lower-assurance portal, audit-suppression).
- [ ] Reserved trust-color semantics (locked/caution/open); color never the sole signal; **Phase-0 is caution, not green**.

**Owner viewer (Surface A)**

- [ ] **Phase-0 offline drag-in viewer**: static, vendored, no remote references; in-page passphrase decrypt (WASM Argon2id+XChaCha20); **build-integrity verification call-to-action (reproducible hash) — NOT a self-asserted runtime "offline self-test"**; no plaintext cached by default; "clear" zeroizes.
- [ ] Overview/Year/Periods/Factors/Journal/Check-ins/Sleep/Report sections; correlations sample-gated + assoc-not-cause; descriptive-only copy (no "you felt…").
- [ ] Report assemble + print stylesheet + PDF/JSON/CSV export with plaintext-on-disk warning.

**Assessment runner (Surface B)**

- [ ] **Amend [INSTRUMENTS.md](INSTRUMENTS.md) to list ASRS v1.1 and IPIP** (license/status + ASRS WHO notice + IPIP public-domain citation) **before** wiring the CI/code-comment gate to it.
- [ ] Catalog limited to public-domain/self-authored (INSTRUMENTS.md); CI/code-comment guard against licensed instruments; original "Steady Attention" task only.
- [ ] Non-diagnostic intro gate; scores/bands/summary-metrics only; item-9 structurally absent; offline crisis flow on non-zero item-9; stop=discard; invalid-run handling for the focus task.
- [ ] Results write into the **same E2EE `BackupData` snapshot** and sync symmetrically (Flow A).

**Therapist portal (Surface C)**

- [ ] One-link invite (no server email/SMS; OOB phrase, capped backoff); passkey enroll; keys generated in-client.
- [ ] **Mandatory mutual** fingerprint verification (word-list + QR), approachable "read these aloud" UX; "don't match → stop" prominent; persistent re-verify + key-change alarm.
- [ ] Clearly-bounded read-only share view; owner-signature verified before render; thin in-memory viewer; honest expiry/revocation banners; single-relationship (v1 lock), no enumeration.
- [ ] Game-plan composer with fixed non-removable disclaimer; step-up sign-off; one-way/append-only/no-recall messaging.

**Consent & flows**

- [ ] Share builder: opt-in everything, default-less (range+expiry), no "select all," equal-weight Cancel, mandatory preview, required visible expiry, no auto-renew.
- [ ] Honest consent screen with plain limits; revoke one tap from the active share; re-pair offered as the real future-data revocation.
- [ ] Game-plan PROPOSED accept/decline gate; wrong-fingerprint = hostile, never auto-accept; optional adopt-as-goal only on explicit accept.
- [ ] Pairing-a-device flow teaches single-writer last-snapshot-wins honestly (no silent merge); device list + remove.

**States, a11y, motion**

- [ ] Empty/loading(decrypt-explained)/error(generic, non-enumerating)/offline states per §11; signature-fail and fingerprint-change alarms.
- [ ] Keyboard-first; chart text alternatives; color+icon+text for trust; reduced-motion respected; i18n-ready strings; no motion-as-pressure on security screens.

---

*Design status: **DESIGN ONLY — no code exists yet.** Consistent with the committed Companion
scope/architecture/security/therapist/deployment/features docs, INSTRUMENTS.md, DESIGN.md,
FEATURES.md, and the non-diagnostic prime directive ([../HANDOFF.md](../HANDOFF.md) §0). Where this
doc and those differ, those win.*
