# Daymark — "My safety plan": Design & Licensing Notes

> **Status: built.** The rendered mockup is
> [`design/app-04-safety-plan.html`](./design/app-04-safety-plan.html) (PNG alongside it).
> Shipped in `ui/safety/SafetyPlanScreen.kt` + `SafetyPlanViewModel.kt`, over
> `data/SafetyPlanRepository.kt`, `data/dao/SafetyPlanDao.kt`, and
> `data/entity/SafetyPlanItem.kt` (Room v13). Storage rules are covered by
> `app/src/test/java/com/daymark/app/data/SafetyPlanRepositoryTest.kt`.

A plan the person writes **while things are steady**, so that a harder moment doesn't have to start
from a blank page. Local-only, offline, and never pushed at anyone.

---

## Contents

- [Licensing: this is NOT the Stanley-Brown form](#licensing-this-is-not-the-stanley-brown-form)
- [Provenance tier](#provenance-tier)
- [The sections](#the-sections)
- [The crisis step](#the-crisis-step)
- [Where it lives](#where-it-lives)
- [Schema](#schema)
- [Sharing with a clinician](#sharing-with-a-clinician)
- [Open questions](#open-questions)

---

## Licensing: this is NOT the Stanley-Brown form

The Stanley-Brown Safety Planning Intervention is the reference safety-planning instrument, and
[SUPPORT_FEATURE_PLAN.md](./SUPPORT_FEATURE_PLAN.md) already cites its escalation ladder. **We are
not reproducing its form.**

The form is © Barbara Stanley, PhD and Gregory K. Brown, PhD (2008, 2021). Individual use of the
form is permitted, but **written permission from the authors is required to change it, to translate
it, or to program it into an electronic record.** Building their form into this app is precisely the
"program the form for use in the electronic medical record" case that requires permission.

Two options existed:

1. Seek written permission from the authors, and ship the form faithfully as a **Validated** tool.
2. Ship **our own wording**, drawing on the general *method* of safety planning, as **Adapted**.

**We take option 2** unless and until someone actually obtains permission. This is not a workaround
of the licence — it is the difference between reproducing an instrument and being informed by a
method, and the provenance label states plainly which one this is.

Practical consequences:

- Our own section titles and helper copy. No copied wording, no copied ordering claim.
- **No means-restriction step.** Stanley-Brown includes making the environment safe; we omit it.
  Independently required by [PROVENANCE.md](./PROVENANCE.md) rule 4 — *no self-harm item slot in any
  tier's shareable output* — so this omission is a house rule, not just a licensing artifact.
  A person can of course write whatever they want in their own words; we do not prompt for it.
- If permission is ever obtained, this becomes a **Validated** tool and the ledger row changes.

## Provenance tier

**Adapted (◐)** — "built on an evidence-based method but modified. Names the method it draws from."

In-product disclaimer, shown on the editor:

> **A plain safety plan in our own words.** It draws on the general idea of safety planning. It is
> not a validated or clinical instrument, and not for diagnosis.

Needs a row in the instruments ledger ([INSTRUMENTS.md](./INSTRUMENTS.md)) like any other tool.

## The sections

Three required, one optional:

| Section | Prompt | Notes |
|---|---|---|
| **Warning signs I notice** | "What it looks like when things start to slip" | |
| **Things that help** | "What has actually worked before" | |
| **People I can reach** | "Who you'd be glad to hear from" | Free-text name + optional detail (relationship) |
| **Reasons I want to stay** *(optional)* | — | **Appears only once something is added**, like the others |

The fourth section is a documented part of safety planning that an early three-section sketch had
missed. It is **optional by deliberate decision**: it is the heaviest thing on the screen to write,
and a permanently visible empty one risks reading as a reproach on a bad day. It is offered, not
demanded — the same rule as everything else here.

Empty state is an invitation ("Write it while things are steady…"), never a scold.

## The crisis step

**Data-driven from `CrisisStore`. Do not hardcode a number in this screen.**

`CrisisStore` already holds one **user-editable** resource, defaulting to
`"Call or text 988"` / `"988 Suicide & Crisis Lifeline (US)"`, stored locally. So the button renders
the stored `contact` as its label and the stored `label` beneath it. A US user gets a one-tap 988
button out of the box; anyone else can replace it with their local line. There was never a conflict
between "hardcode 988" and "use what the person saved" — the store already reconciles them.

Footer copy states the limit plainly: **a plan is not a person — reaching one is the point.** The
JITAI/digital-safety-plan literature is explicit that automated support must not be positioned as a
substitute for human contact, since that can worsen isolation.

## Where it lives

- **More → My safety plan** — the library, alongside the other tools. The baseline entry point.
- **A quiet row on the support screen** — the place you'd actually want it in a hard moment, inside
  the existing menu list, not shouting above it.
- **Explicitly NOT a Signals/Home card.** Signals are chosen by rules over mood data; surfacing a
  safety plan that way means the app inferring that someone looks like they need one. That is the
  covert-labeling failure [SUPPORT_FEATURE_PLAN.md](./SUPPORT_FEATURE_PLAN.md) already rules out,
  and it is the same nagging problem in a different costume.

## Schema

Room **v13**, one new child table. `AppDatabase` is at v12; add `MIGRATION_12_13` following the
existing pattern (`CREATE TABLE IF NOT EXISTS` + index, existing data preserved).

```
safety_plan_items(
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  section  TEXT NOT NULL,   -- warning_signs | things_that_help | people | reasons
  position INTEGER NOT NULL,
  text     TEXT NOT NULL,
  detail   TEXT NOT NULL    -- '' except on people rows (relationship/how to reach)
)
```

**Do not use the CSV-in-a-TEXT-column trick.** `ThoughtRecord.distortions` stores a list as CSV, but
that is a fixed catalog of our own comma-free keys. Safety-plan items are **free text a person may
easily write a comma into** ("Call Sam, then walk"), which a naive `split(",")` would silently
shred. A child table also handles ordering and the two-field people rows without further contortion.

There is no parent row: the plan *is* the set of items. An empty table means no plan yet.

Must round-trip through `BackupManager` like every other entity.

## Sharing with a clinician

**Local-only for now**, like the rest of the app — the default build has no `INTERNET` permission at
all, and CI fails if one appears.

Sharing a safety plan with a therapist is clinically normal — safety plans are often written *with*
one — so the schema should not make it impossible later. But if it ever ships, it follows the
existing rules in [COMPANION_ACCESS_CONTROL.md](./COMPANION_ACCESS_CONTROL.md) with no exceptions:
an owner-created, curated, time-boxed, revocable share; consent rooted at the patient; never
automatic and never on by default. A clinician may *recommend*; only the owner grants.

## Decided while building

- **The optional fourth section is *offered*, not empty.** "Absent until filled" left no way to ever
  add it, so where the section would sit there is instead a single quiet card — *"Add 'Reasons I
  want to stay'? Optional. Only if you'd want it there."* — that becomes the real section on tap.
  No empty prompt sits there on a bad day, and the door is still visible.
- **The support-screen row appears only once a plan exists.** This was not in the original design
  and it matters: a row that leads to a blank page in a hard moment is the exact failure the plan
  was built to prevent. The invitation to *write* one lives in More, on a steady day. Implemented
  as `SupportViewModel.hasSafetyPlan`.
- **The crisis row hands off, it does not dial.** It navigates to the existing crisis screen rather
  than placing a call, matching `CrisisResourcesScreen`'s own statement that Daymark "can't call for
  you and isn't a crisis service".
- **Read and edit are one screen.** In a hard moment nobody should have to find an edit button, and
  the plan is short enough that a separate read view would only add a tap.

## Still open

- Should editing the plan be reachable *from* the crisis screen? Currently one-way (plan → crisis),
  on the assumption that editing is not what anyone wants mid-crisis.
- Print/export: valuable (a paper copy in a wallet works when a phone doesn't) but it leaves the
  device. Behind an explicit action only. The plan does round-trip through `BackupManager` today,
  which covers *not losing it* but not *carrying it*.
- `app/schemas/…/13.json` is generated by the Room processor at build time and still needs to be
  committed from a machine with the Android SDK, like the versions before it.
