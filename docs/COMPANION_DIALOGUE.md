# The companion — dialogue, signals, and who may author them

Design for the interactive companion agreed in [DECISIONS_2026-08.md](./DECISIONS_2026-08.md) §D1b.
Short by design: most of the machinery already exists, so this records the parts that need deciding
once and the security properties that must hold.

## What already exists

The dialogue engine is not a thing to build. It is a thing to reuse.

| Piece | Status |
|---|---|
| Branching evaluator | **Exists** — `instruments/predicate.ts`. Pure, sandboxed, no `eval`, unknown node shapes throw. |
| Content as authorable data | **Exists** — definitions are data, not code, so a clinician can author with the existing builder. |
| Honesty gate over that content | **Exists** — the same `validateDefinition()` the catalog passes. |
| Runner that walks items and branches | **Exists** — `QuestionnaireRunner.svelte`. |
| Signal vocabulary | **This document.** |
| Reception ledger | **This document.** |

**The bridge is one line of insight:** `Answers` is `Record<string, unknown>`, so companion facts are
injected as pseudo-answers. `{ ref: 'hardDaysLast7', op: 'gte', value: 3 }` evaluates today with zero
engine changes.

## The signal vocabulary — closed, short, and argued over

This list is the boundary that keeps the companion from becoming the beast §D1 warns about. **Every
signal is a coupling point.** Adding a ninth should require someone to make a case.

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
about them** — the reflect-never-label rule from §D1b applies to every use of it.

## The companion is a view, not the engine

**Correction to an earlier framing.** §D1b called the companion "a feature that calls the arbiter",
which reads as though the signals belong to it. They do not. The layering is:

```
signal layer  (8 facts, computed on device)   ─┬─→ arbiter        (may anything interrupt?)
reception ledger                              ─┤
                                               ├─→ companion      (dialogue — one consumer)
                                               ├─→ reminders      (when, how often)
                                               └─→ per-feature suggestion logic
```

**Everything reads the substrate; nothing owns it.** This matters because of a requirement that is
easy to miss: *a person who never once opens the companion is still affected by it* — their reminders
land differently, their suggestions differ, the app asks less when it is being ignored. The companion
is the surface where that becomes visible and steerable, not the place where it happens.

**The safety property that makes this acceptable for a passive user.** Someone who never interacts is
the person most exposed to an adaptive system, because they never get the chance to correct it. The
monotonic rule from §D1a is what protects them: **falling reception may only ever reduce prompting.**
So the worst case for a person who ignores everything is that the app gets *quieter*. There is no
combination of signals that makes it louder without them asking.

## Two editors, one format

The clinician side needs to serve two people: one who wants every data point and precise control, and
one for whom that is immediately too much. Both are real, and the second is more common.

| | **Direct** | **Guided** |
|---|---|---|
| For | someone who wants the full surface | someone who wants to answer a few questions |
| Shows | every signal, predicate, branch, node | a handful of plain-language choices |
| Produces | a definition | **the same definition** |

**The rule: guided mode generates the format direct mode edits.** One artifact, two ways in. A
clinician may start guided and open the result in the direct editor to refine it, and nothing is lost
in translation because there is no translation. Two formats would fork the honesty gate, the signing
path, and the security partition — and would eventually disagree.

Pleasingly, guided mode is itself a dialogue tree that outputs a dialogue tree, so it is built with
the machinery already described here.

## "Lots of data points" versus the security partition

These pull against each other and the tension should be named rather than smoothed over. A clinician
wants rich branching; §Finding 3 says they may only branch on what they are already permitted to see.

**Resolution: the partition is grant-driven, not fixed.** Its *default* is narrow — prescribed
modules, time of day, answers inside their own module, and whatever a `read.share` grant already
covers. That is more than it sounds like. Beyond that, **the person can widen it**, per signal,
explicitly, revocably, in the same place they manage every other capability:

> *"Let your therapist's dialogue respond to how often you check in."* — off by default.

So a clinician gets many data points when the person agrees to it, the oracle stays closed when they
do not, and the mechanism is the consent model that already runs everything else rather than a second
one invented for this.

## Robustness — the failure modes worth designing for now

"Very robust" is concrete here, because authored content outlives the app version it was written
against.

- **Signal vocabulary is versioned.** A definition records which signal-set version it was authored
  against. A definition referencing a signal that no longer exists must fail *closed* and be
  reportable, never silently change behaviour (see Finding 1).
- **No dead ends.** Every node either offers options or terminates explicitly. A node that renders no
  line and no option is a validation error, not a blank panel.
- **Every branch set is total.** A fallback with no predicate is mandatory, so there is always a line
  to say. The dialogue can never fall through to nothing.
- **Missing signals are normal, not exceptional.** A new install has no history. Every predicate must
  behave sensibly when the substrate is empty, and that is a test case rather than an assumption.

## The reception ledger

Persisted, so worth getting right once — migrations are the expensive mistake.

```
kind        TEXT    which feature asked (companion, reminder, assignment, support)
offeredAt   INTEGER when
outcome     TEXT    accepted | dismissed | snoozed | stop
```

That is the whole thing. It is the arbiter's own table (§D1a), and it is what `lastOfferOutcome`
reads. The monotonic rule holds: falling reception may only ever reduce prompting.

---

# Security analysis

Both sides may author dialogue: the app ships some, a clinician may write more. **A clinician
authoring content that executes logic on a patient's device is a genuine trust boundary** and it was
worth checking rather than assuming. Findings below were established by executing the code, not by
reading it.

## What holds up

**No HTML injection path.** There is no `{@html}` anywhere in `companion/web/src`. Item text renders
through Svelte interpolation (`{it.body}`), which escapes by default. Authored content cannot inject
markup or script. This is structural, not a filter that could be bypassed.

**No code execution.** The predicate DSL is data — `all` / `any` / `ref` / `op` / `value`. There is no
`eval`, no function reference, no template execution. An authored definition cannot run arbitrary
logic, only ask comparison questions about values the host supplies.

**The honesty gate already applies.** Self-harm references are structurally rejected, non-diagnostic
framing is required, provenance tiers are role-gated, and forbidden licensed instruments are blocked.
Dialogue authored as a definition inherits all of it for free.

## Finding 1 — `ne` fails OPEN on an unknown signal

**Confirmed by execution.** For a predicate referencing a signal that does not exist:

| op | result | direction |
|---|---|---|
| `eq` | `false` | fails closed ✓ |
| `gte` | `false` | fails closed ✓ |
| `in` | `false` | fails closed ✓ |
| **`ne`** | **`true`** | **fails OPEN** ✗ |

`undefined !== 1` is true, so a branch gated on `{ ref: 'typo', op: 'ne', … }` **shows** rather than
hides. The realistic trigger is not malice but drift: a clinician authors against a signal that a
later app version renames or removes, and content silently starts appearing where it should not.

**Fix:** unknown refs should fail closed uniformly. Either return `false` for any leaf whose `ref` is
absent from `answers`, or — better — **validate at authoring time that every `ref` is in the closed
signal vocabulary**, and reject the definition otherwise. Do both: validation catches typos with a
useful message, the runtime guard catches version drift.

## Finding 2 — the predicate evaluator is unbounded

**Confirmed by execution.** A 50,000-deep nesting of `all: [...]` throws
`Maximum call stack size exceeded`. `evalPredicate` recurses with no depth or node-count limit.

Impact is bounded — content is authored by a clinician the person granted, and the crash is on the
person's own device — but a buggy generator or a hostile author can hard-crash the runner.

**Fix:** cap depth and total node count at validation time (depth ≤ 16, nodes ≤ 256 are generous),
and defensively bound the recursion at evaluation time too.

## Finding 3 — branch-shaped side channel (the important one)

**This is a design property, not a bug, and it is the reason both-sides authoring needs a rule.**

Predicates evaluate on the person's device against private data. The therapist never sees the result
directly. **But they can infer it from anything they *can* observe.** If a therapist authors:

> *If `hardDaysLast7 >= 3`, show a module they can accept. Otherwise show nothing.*

then the assignment lifecycle they already watch — delivered, accepted, completed — leaks the branch
condition. **The therapist learns a fact about the person's private mood data that no grant gave
them.** Nothing was transmitted; the inference comes from the shape of the interaction.

This generalises: any authored branch whose arms differ in a therapist-observable way is an oracle for
whatever it branched on.

**The rule that closes it, which falls out of the existing capability model:**

> **A predicate may only reference signals its author is already permitted to see.**

So the signal vocabulary is **partitioned by author**:

| Signal | App-authored | Therapist-authored |
|---|---|---|
| `daysSinceLastOpen` | ✓ | ✗ |
| `daysSinceLastCheckIn` | ✓ | ✗ |
| `checkInsLast7` | ✓ | ✗ |
| `hardDaysLast7` | ✓ | **✗ — the oracle** |
| `hasSafetyPlan` | ✓ | **✗ — never disclosed to a clinician** |
| `prescribedModules` | ✓ | ✓ (they issued them) |
| `lastOfferOutcome` | ✓ | ✗ |
| `timeOfDay` | ✓ | ✓ (carries nothing private) |
| shared scores/bands | ✓ | ✓ **only where a `read.share` grant covers them** |

A clinician can still write genuinely useful branching — on the tool they prescribed, on answers given
*within their own module*, on time of day. They cannot write a branch that turns the person's private
history into something they can observe.

**`hasSafetyPlan` deserves its own line.** Whether someone has written a safety plan is among the most
sensitive bits in the product. It must never be readable by, or inferable by, a clinician-authored
branch.

## Finding 4 — authored dialogue needs the same signing path as assignments

Companion dialogue is content that runs on a patient device. It must travel the existing
**sign-then-seal** path, be verified against the pinned clinician key, and be refused wholesale on
signature failure — exactly as a grant that fails verification renders no granted UI. Content that
arrives unsigned or unverifiable is not degraded, it is not shown.

## Finding 5 — capability and revocation

Authoring dialogue is a **new capability**, not a free rider on `authorGamePlan`. It grants the power
to place branching, interactive content in someone's app, which is materially different from sending
a written plan. It follows the same rules as every other capability: the person grants it, sees it in
the allowed list, and can withdraw it — and withdrawal must remove already-delivered dialogue, not
merely prevent new dialogue.

## Checklist before this ships

- [ ] Unknown `ref` fails closed for every op, including `ne`
- [ ] `ref` validated against the closed signal vocabulary at authoring time
- [ ] Predicate depth and node count bounded at validation *and* evaluation
- [ ] Signal vocabulary partitioned by author, enforced server-side and re-checked on device
- [ ] `hasSafetyPlan` unreachable from any clinician-authored predicate
- [ ] Dialogue signed, verified against the pinned key, refused wholesale on failure
- [ ] A distinct capability, revocable, with revocation removing delivered content
- [ ] No `{@html}` — assert it in the invariant suite so it stays true
