# The companion — dialogue, signals, and who may author them

The companion is a presence a person can open and talk to through fixed choices (decided in
[DECISIONS.md](DECISIONS.md) §D1b). This document records its substrate, its format, and the security
properties that must hold when both the app and a clinician may author dialogue.

## What exists

| Piece | Where |
|---|---|
| Branching evaluator | `companion/web/src/lib/instruments/predicate.ts`. Pure, sandboxed, no `eval`; unknown node shapes throw |
| Content as data | Dialogue and instrument definitions are data, not code |
| Honesty gate over that content | `validateDefinition()`, the same gate the catalogue passes, plus `validateDialogueDefinition()` and `validateDialogue()` |
| Signal vocabulary | `companion/web/src/lib/companion/signals.ts`, mirrored in Kotlin as `CompanionSignals` in `app/src/main/java/com/daymark/app/stats/Signals.kt` |
| Dialogue format and planner | `companion/web/src/lib/companion/dialogue.ts` and `walk.ts`; the app's own dialogue is `content.ts` |
| Renderer | `companion/web/src/lib/components/Companion.svelte` — fixed choices, no text input, hides on one press |
| Reception ledger | `offer_records` in the Android app, live since schema v14 |

**The companion is not mounted anywhere yet.** `Companion.svelte` renders the dialogue and is tested,
but no page imports it, and three of its six destinations (check-in, journal, safety plan) do not
exist in the web console; `CompanionSignals` has no production caller on the phone. Wiring it in, and
checking the author partition at both ends when it is: #272.

**The bridge.** `Answers` is `Record<string, unknown>`, so companion facts are injected as
pseudo-answers. `{ ref: 'hardDaysLast7', op: 'gte', value: 3 }` evaluates with no engine changes.

## The signal vocabulary — closed, short, and argued over

This list is the boundary that keeps the companion from becoming the beast §D1 warns about. **Every
signal is a coupling point.** Adding a ninth should require someone to make a case, and the tests
assert the count so that argument cannot be skipped.

| Signal | Type | Derived from |
|---|---|---|
| `daysSinceLastOpen` | int | app usage |
| `daysSinceLastCheckIn` | int | entries |
| `checkInsLast7` | int | entries |
| `hardDaysLast7` | int | entries in the lower mood bands |
| `hasSafetyPlan` | bool | safety plan store |
| `prescribedModules` | string[] | accepted assignments |
| `lastOfferOutcome` | enum | the reception ledger |
| `timeOfDay` | enum | clock |

All local. None inferential. **`hardDaysLast7` is a count of what the person logged, not a judgement
about them** — the reflect-never-label rule of §D1b applies to every use of it. The vocabulary is
versioned (`SIGNAL_VOCABULARY_VERSION`), and a definition records the version it was written against.

## The companion is a view, not the engine

```
signal layer  (8 facts, computed on device)   ─┬─→ arbiter        (may anything interrupt?)
reception ledger                              ─┤
                                               ├─→ companion      (dialogue — one consumer)
                                               ├─→ reminders      (when, how often)
                                               └─→ per-feature suggestion logic
```

**Everything reads the substrate; nothing owns it.** A person who never once opens the companion is
still affected by what the substrate drives, so the protection for that passive person is the
monotonic rule of §D1a: **falling reception may only ever reduce prompting.** The worst case for
someone who ignores everything is that the app gets quieter. No combination of signals makes it
louder without them asking.

## Two editors, one format

A clinician may want every data point and precise control, or only a few plain choices; the second
is more common.

| | **Direct** | **Guided** |
|---|---|---|
| For | someone who wants the full surface | someone who wants to answer a few questions |
| Shows | every signal, predicate, branch, node | a handful of plain-language choices |
| Produces | a definition | **the same definition** |

**The rule: guided mode generates the format direct mode edits.** One artifact, two ways in. Two
formats would fork the honesty gate, the signing path and the security partition, and would
eventually disagree. The format and its validator exist; neither editor exists for dialogue: the
guided editor is #274.

## "Lots of data points" versus the security partition

A clinician wants rich branching; Finding 3 says they may only branch on what they are already
permitted to see. **The partition is grant-driven, not fixed.** Its default is narrow — prescribed
modules, time of day, answers inside their own module, and whatever a `read.share` grant already
covers — and the person could widen it per signal, explicitly and revocably, where they manage every
other capability (*"Let your clinician's dialogue respond to how often you check in"*, off by
default). `hasSafetyPlan` can never be widened. Per-signal consent is not built: #279.

## Robustness — the failure modes designed for

Authored content outlives the app version it was written against.

- **Signal vocabulary is versioned.** A definition referencing a signal that no longer exists must
  fail *closed* and be reportable, never silently change behaviour (Finding 1).
- **No dead ends.** Every node either offers options or ends explicitly. A node that renders no line
  and no option is a validation error, not a blank panel.
- **Every branch set is total.** A fallback with no predicate is mandatory, so there is always a line
  to say. The dialogue can never fall through to nothing.
- **Missing signals are normal, not exceptional.** A new install has no history. Every predicate must
  behave sensibly when the substrate is empty, and that is a test case rather than an assumption.

All four are structural in `dialogue.ts` and tested in `dialogue.test.ts` and `walk.test.ts`.

## The reception ledger

The arbiter's own table (§D1a), in the Android app, and what `lastOfferOutcome` reads.

```
kind        TEXT    which feature asked (companion, reminder, assignment, support)
offeredAt   INTEGER when
outcome     TEXT    accepted | dismissed | snoozed | stop
```

Those three columns are the ledger this document specifies (`OfferRecord.kt`, schema v14). Schema
v18 added three facts about the app's own behaviour at the moment it asked — the hour and weekday
(`-1` when unrecorded, never back-filled) and whether anything came back — for the timing layer
(FEATURES.md). There is no note, no answer, no dialogue text, no mood and no free-text column, and
there must never be one. Rows are immutable: the DAO has no update path. `kind` and `outcome` are text
keys, not ordinals, so a row from an older or newer version always reads back; an unrecognised key is
read as quieter, never louder (`InterruptionBudgetTest`). The ledger is in no backup, export or
report, and never reaches a clinician.

---

# Security analysis

Both sides may author dialogue: the app ships some, and a clinician may write more. **A clinician
authoring content that executes logic on a patient's device is a genuine trust boundary.** The
findings below were established by executing the code.

## What holds up

**No HTML injection path.** There is no `{@html}` anywhere in `companion/web/src`; item text renders
through Svelte interpolation, which escapes. Authored content cannot inject markup or script. Nothing
yet asserts that `{@html}` stays absent, so this is a fact about today's tree rather than a guarantee
about tomorrow's: #266.

**No code execution.** The predicate language is data — `all` / `any` / `ref` / `op` / `value`. There
is no `eval`, no function reference, no template execution. A definition can only ask comparison
questions about values the host supplies.

**The honesty gate applies.** Self-harm references are rejected, non-diagnostic framing is required,
every tool declares its provenance tier, and licensed instruments are blocked. Dialogue authored as a
definition inherits all of it.

## Finding 1 — an unknown signal must fail closed, for every operator

For a predicate naming a signal that does not exist, `eq`, `gte` and `in` returned `false`, but `ne`
returned `true`: `undefined !== 1`, so a branch gated on `{ ref: 'typo', op: 'ne', … }` **showed**
instead of hid. The realistic trigger is drift, not malice: a clinician writes against a signal that a
later app version renames, and content starts appearing where it should not.

Both halves hold, because they catch different things:

- **At run time, every operator fails closed.** A leaf whose `ref` is absent returns `false` before
  the operator is reached (`evalPredicate` in `predicate.ts`). "Absent" means not an own property:
  the check is `Object.hasOwn`, never `in`, because `in` walks the prototype chain and names like
  `constructor` or `toString` would read as present. The tests sweep inherited names across `eq` and
  `ne`, and distinguish an explicitly `undefined` value from an absent one.
- **At authoring time, refs are checked against the closed vocabulary** (`assertRefsAllowed` in
  `signals.ts`, reached from `validateDialogueDefinition` in `validate.ts` and `validateDialogue` in
  `dialogue.ts`).

A fail-closed guard has to be tested against the ways a value can appear to be there, not only
against the ways it can be missing.

## Finding 2 — the evaluator must be bounded

An unbounded predicate — a 50,000-deep nesting of `all: [...]` — would exhaust the stack and crash
the runner. Both places are bounded:

- **Evaluation**: `MAX_PREDICATE_DEPTH = 16`, with a named error rather than a stack overflow.
- **Validation**: `MAX_PREDICATE_NODES = 256`, and depth reuses the evaluator's constant so the two
  cannot drift. Validation counts levels from 1 and evaluation from 0, so anything validation accepts
  sits strictly inside what evaluation enforces.
- **The validator does not recurse.** `measurePredicate` keeps its stack on the heap, so it survives
  the input it exists to reject, and it terminates on a hand-built cyclic predicate.

`dialogue.ts` applies the same two bounds to a dialogue's own predicates.

## Finding 3 — the branch-shaped side channel

**This is a design property, not a bug, and it is the reason both-sides authoring needs a rule.**

Predicates evaluate on the person's device against private data, and the clinician never sees the
result. **But they can infer it from anything they can observe.** If a clinician authors:

> *If `hardDaysLast7 >= 3`, show a module they can accept. Otherwise show nothing.*

then the assignment lifecycle they already watch — delivered, accepted, completed — leaks the branch
condition. Nothing was transmitted; the inference comes from the shape of the interaction. Any
authored branch whose arms differ in a clinician-observable way is an oracle for whatever it branched
on. The rule that closes it:

> **A predicate may only reference signals its author is already permitted to see.**

| Signal | App-authored | Clinician-authored |
|---|---|---|
| `daysSinceLastOpen` | ✓ | ✗ |
| `daysSinceLastCheckIn` | ✓ | ✗ |
| `checkInsLast7` | ✓ | ✗ |
| `hardDaysLast7` | ✓ | **✗ — the oracle** |
| `hasSafetyPlan` | ✓ | **✗ — never disclosed to a clinician** |
| `prescribedModules` | ✓ | ✓ (they issued them) |
| `lastOfferOutcome` | ✓ | ✗ |
| `timeOfDay` | ✓ | ✓ (carries nothing private) |
| shared scores and bands | ✓ | ✓ **only where a `read.share` grant covers them** |

A clinician can still write useful branching — on the tool they prescribed, on answers given *within
their own module*, on time of day. They cannot write a branch that turns the person's private history
into something they can observe.

**`hasSafetyPlan` deserves its own line.** Whether someone has written a safety plan is among the most
sensitive bits in the product. It must never be readable by, or inferable by, a clinician-authored
branch. It carries `neverDisclosable: true` as well as an app-only author list, so no future widening
by consent can reach it.

**Where it is enforced.** Each signal's spec in `signals.ts` carries its `authors`;
`assertRefsAllowed` is the single source of truth and fails closed on an author role it does not
recognise, because that value can arrive from stored or transmitted content. The Kotlin mirror
`CompanionSignals` carries the same partition and is unit-tested. Today the rule is enforced at the
authoring gate in the browser, which is the only place a definition can be validated: no transport
carries a clinician's definition to a device, so there is no server-side check and no on-device
re-check yet (#272).

## Finding 4 — authored dialogue needs the assignment signing path

Companion dialogue is content that runs on a patient's device. It must travel the existing
sign-then-seal path, be verified against the pinned clinician key, and be refused whole on a signature
failure, exactly as a grant that fails verification renders nothing. Content that arrives unsigned or
unverifiable is not degraded; it is not shown. Not built: #268.

## Finding 5 — a capability of its own, and revocation that removes

Authoring dialogue is a new capability, not a free rider on `authorGamePlan`: it places branching,
interactive content in someone's app, which is different from sending a written plan. The person
grants it, sees it in the allowed list, and can withdraw it, and withdrawal must remove dialogue
already delivered, not merely stop new dialogue. Not built: #270.
