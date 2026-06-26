# Daymark — "Gentle Support" (in-the-moment help for low mood): Evidence-Based Plan

> Status: **research-complete, not built.** Non-diagnostic, general-wellness — NOT therapy.
> Built from a multi-agent evidence review (June 2026). Every design choice below is tied to
> the research; "sounds nice but unsupported" ideas are explicitly flagged.

## The core idea
When a user logs a low mood (or opts in), optionally offer a **calm, slow, opt-out-first**
moment that **validates first**, then presents a **menu** of short, evidence-based options the
user *chooses* (or declines). Reachable via a **non-intrusive icon**, never forced. Fully
toggleable in Settings. A firm **crisis safety floor** sits underneath.

## The 7 evidence-based design rules (each tied to findings)

1. **Validate FIRST, always.** Lead with acknowledgement ("That sounds like a really hard day —
   it makes sense to feel this way"), let it land, *before* offering any exercise. DBT was
   reformulated specifically because change-only/fix-first made people feel invalidated; every
   reputable app (Calm Harm, How We Feel, MindShift, Woebot) and the toxic-positivity literature
   converge on validation-before-fix. **Never auto-cheer a sad mood.**

2. **Offer a MENU, opt-out first.** The first option is always *"Not right now."* No single forced
   exercise — every evidence-based app uses a user-selected menu. Autonomy-support has real (if
   modest, d≈0.23) evidence; coercive/dark-pattern design disproportionately harms vulnerable
   users. (User's instinct = correct.)

3. **Calm and LOW-stimulation — NOT colorful/animated.** ⚠️ Correction to the original "colorful
   full-screen animation" idea: high visual intensity (saturation, brightness, motion, complexity)
   **overstimulates** distressed and sensory-sensitive people — the exact users this serves. The
   real lever is *low* visual intensity (muted, dim, slow, lots of whitespace, minimal motion),
   which fits the paper aesthetic. NOTE: "blue = calming" hue claims are largely pop-science
   (weak); what's evidence-based is overall *intensity*, not specific colors. Calm-tech +
   trauma-informed design (progressive disclosure, plain language, user pacing) reinforce this.

4. **Non-intrusive entry.** A small icon the user *pulls*, not an in-your-face interrupt. (User's
   instinct = correct.) Slow, optional, gentle.

5. **Default on/off per context — transparently & reversibly.** Defaulting support ON for users
   who indicate depression/anxiety, OFF for e.g. sleep-apnea-only, is ethically defensible IF:
   transparent about what/why, a true one-tap opt-out (never a dark pattern), framed as pro-self,
   and the sensitive condition data is protected (stigma/privacy). (User's instinct = supported,
   with these guardrails.) Always a "turn off forever" switch in Settings.

6. **Honest about effect size.** Unguided self-help yields *small* effects (d≈0.1–0.3); the main
   failure mode is adherence, and the real danger is **false reassurance / treatment delay**.
   Frame as general-wellness support, never treatment; encourage real care for moderate+ distress.

7. **Crisis safety floor (firm).** See below.

## The menu — techniques ranked by evidence (offer the top ones first)

| Technique | Evidence | In-app form | Cautions |
|---|---|---|---|
| **Behavioral Activation** — do one small valued/pleasant thing now | **STRONGEST** (meta SMD −0.74; g≈0.85; non-inferior to CBT; self-administered single-session RCT d=0.18; NICE first-line) | "Pick one tiny thing that usually helps you — want to plan it?" Draw from the user's OWN activities that correlate with their better moods (action, not comparison) | Brief group/online versions sometimes don't beat control — keep it concrete & personally chosen |
| **Slow paced breathing** (~6 breaths/min, ~5 min) | Strong physiological (large HRV effects; ~5 min suffices) | A simple calm breathing pacer | ⚠️ Extending the exhale and box-breathing are **NOT** proven better than plain 6/min — keep it simple, don't overclaim |
| **PMR / brief relaxation** | Solid (d≈0.5–0.6; larger in clinical samples) | Guided muscle release | Single-technique > multi-combo |
| **Cognitive defusion** ("I'm having the thought that…") | Strong for reducing thought believability/distress (analogue) | Reframe a sticky thought | Short-term/analogue evidence |
| **Brief mindfulness** (5–20 min) | Decent for state anxiety (g≈−0.60) | Short breath/awareness | ⚠️ Documented adverse effects (~30% distressing; worse with psychiatric history) → keep optional, short, screen-aware; NOT for everyone |
| **Self-compassion break** | Moderate (depression g=0.66) | Neff's 3-step | ⚠️ Can trigger threat in high self-critics → optional |
| **Affect labeling** ("name the feeling") | Real neural effect; conditional | Name the emotion | ⚠️ Can *increase* distress for low-intensity emotions; can undercut reappraisal |

**Offer but DON'T claim efficacy:** 5-4-3-2-1 grounding (expert-consensus only, no RCT).
**Avoid / exclude:** generic affirmations ("you're amazing" — backfire risk for low self-esteem,
and a 2020 replication found no benefit either); urge-surfing as standalone (weak); cold-water
immersion (⚠️ acute anxiety magnifies the dangerous cold-shock response — only the mild *seated
cold-on-face* form, with a cardiac contraindication, if at all); reminding someone of past "good
days" as evaluative comparison (currently-low people can feel *worse* by contrast — only use own
data as concrete, action-linked, present-tense, never "you used to be happier").

## Crisis safety floor (non-negotiable)
- **Tiered escalation, not a cliff** (Stanley-Brown ladder): coping → support people → professionals
  → **988**, with the emergency option always one tap away (offline, locale-editable default).
- **No abrupt "hotline dump."** Warm, validating handoff ("I can tell this really matters — let's
  slow down and stay with this safely") before showing resources.
- **Crisis resources are SAFE to surface** — asking about / signposting suicide does **not** increase
  ideation (well-evidenced). The only iatrogenic risks are *tone/method* (follow Samaritans media
  guidance) and *covert false-positive labeling*.
- **Do NOT covertly detect/label suicidal ideation.** False-positive harms + ethics. Prefer
  user-initiated triggers + gentle universal signposting. If any detection is ever added, use
  **confirm-before-routing** (Woebot's Language-Detection pattern: remind of limits → ask the user
  to confirm → then offer resources). Keep humans in the loop; offer an opt-out of any monitoring.
- When repeated very-low mood / self-harm signals appear: **validate + surface resources**, never
  cheer, never gamify.

## What the reputable apps do (patterns to emulate)
- **Calm Harm** (DBT-based): "ride the wave" + a *menu* of categories (Comfort / Distract / Express
  / Release), timed & extendable, builds a personal safety net.
- **How We Feel** (Yale/RULER): check-in → place feeling → tag factors → *then* optional strategies;
  "emotions are neither good nor bad" baked into copy.
- **MindShift CBT**: in-the-moment toolkit menu (coping cards, chill zone, thought journal) + crisis
  contact when coping isn't enough.
- **Woebot** (now shut down): confirm-before-routing crisis pattern; explicitly "not a crisis service."

## On games (the "Wordle-style" idea)
- Calm casual games / Tetris have real evidence for *distraction/flow* (Tetris even reduced intrusive
  trauma memories ~62% in an ED RCT). Game-delivered Behavioral Activation has a small positive RCT.
- BUT: **don't load games with affirmations** (no added evidence, carries the affirmation risks); keep
  a game, if any, a neutral "moment to decompress," opt-in. Watch for distraction → avoidance.

## Build sketch (when we build it)
- A `support/` package: a calm full-screen sheet (low-intensity, slow), `SupportViewModel` that
  (a) validates, (b) offers the menu (BA + breathing first), (c) personalizes BA from the user's
  own high-mood activities, (d) routes to a `CrisisResources` screen (offline, editable 988).
- Settings: master on/off ("Gentle support"), condition-based default with transparent reversible
  opt-out, per-technique toggles.
- Triggered by: a low-mood log (offer, never force) OR a pulled non-intrusive icon. All local.
- Lives in **Home** context (per the IA decision) — surfaced contextually, not a bottom-nav tab.

## Naming
"Cope" isn't ideal (user agrees). Candidates: **"Gentle support," "Take a moment," "Steady,"
"A moment for you."** Recommend **"Take a moment"** (action-neutral, non-clinical, non-presumptuous).
