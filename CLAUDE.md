# CLAUDE.md — working agreements for this repository

Auto-loaded into every session and every subagent. It is deliberately short: it holds the rules that
keep being re-derived, and a map saying which single document answers which question. Read the one
document you need, not the corpus.

## 0. The prime directive

**No AI, no ML, no generated content, anywhere, ever.** This is a mental-health app; generated text
aimed at someone in distress is the hazard the whole architecture exists to avoid. Every user-facing
word is a fixed, human-written template with the person's own numbers slotted in. "Personalisation"
means rules over the person's own data.

Corollaries: non-diagnostic (screeners are self-checks); descriptive, never interpretive (state what
the data shows, never narrate how they must have felt); crisis resources are offline, static and
user-editable, never auto-escalating. `HANDOFF.md` §0 has the long form.

## 1. Where to look

| Question | Document |
| --- | --- |
| What is this project, end to end | `HANDOFF.md` (long; stale on branch/PR numbers) |
| Where the work currently stands | `docs/SESSION_STATE_2026-08-28.md` — read the **last addendum first** |
| Companion server + web design | `docs/COMPANION_ARCHITECTURE.md`, `docs/COMPANION_PLAN.md` |
| Therapist access, invitations, pairing | `docs/COMPANION_THERAPIST.md`, `docs/COMPANION_SECURITY.md` |
| Copy rules, tone, component patterns | `docs/COMPANION_UX.md`, `docs/COMPANION_DESIGN_SYSTEM.md` |
| Phone-side sync contract | `docs/COMPANION_PHONE_2B.md`, `docs/SYNC_PROTOCOL.md` |
| What changed and when | `CHANGELOG.md` |

## 2. Repo map

- `app/` — the Android app (Kotlin/Compose). Flavors `foss` (default, no INTERNET permission) and
  `sync` (opt-in networking, `applicationIdSuffix ".sync"`). Room schemas committed under
  `app/schemas`.
- `sync-crypto/` — shared Kotlin crypto used by the app (CPace, lazysodium).
- `companion/server/` — Kotlin/Ktor server. `companion/web/` — Svelte owner, therapist and admin
  consoles.
- `docs/` — design and planning corpus. `gradle/libs.versions.toml` — the version catalog.

## 3. Commands, and which oracle actually works here

```
cd companion/web && pnpm test && pnpm check && pnpm build    # vitest, svelte-check, vite build
cd companion/server && ./gradlew test                        # works in this container
```

- **The root `./gradlew` does not run in this container.** Android is verified by CI only.
- **Sum the XML results; never trust a Gradle banner.** "BUILD SUCCESSFUL" has been printed over a
  test task that ran nothing.
- CI: `.github/workflows/build.yml` builds Android on every push to every branch (push-only — a PR
  shows the checks from its head commit, so there is no `pull_request` trigger).
  `.github/workflows/companion.yml` runs on `companion/**`. `.github/workflows/release.yml` fires on
  a pushed `v*` tag and *creates* the GitHub Release with the signed `foss` APK attached.

## 4. Rules that keep recurring

**Copy and UI.** No green, success, tick, score, streak or congratulation, anywhere — a hue window
enforced tree-wide catches new components automatically. A gap in someone's data is never drawn as a
failure. Semantic tokens only, never raw colour. Refusals name a consequence, never a cause the
system cannot know. Never echo a credential back into a message. "Revoking does not un-send what was
already read" appears verbatim at the point of the click.

**Security.** The server never sees content, and logs carry no content. The server vouches for no key
it relays. Only a human report burns an invitation — a wrong pairing code is silent key divergence,
never an error and never a burn. Key tables are insert-only via primary key. A lockout writes one
audit row per episode, on arming, not per probe. The pairing code never leaves the device it was
typed on.

**Process.** Develop on `claude/<topic>-<suffix>`; never push to `main`. Do not open a pull request
unless explicitly asked. Commit subjects are lowercase `type(scope): what changed, stated from the
product's point of view` — see `git log` for the register.

## 5. Testing conventions

- **Every absence assertion is paired with a positive control.** A grep that cannot see a planted
  example proves only that it is blind. This is the repo's single most common bug shape: a check
  written against an assumption goes green when the assumption stops holding.
- **Mutation-test anything load-bearing** before calling it proof: break the property on purpose and
  confirm exactly the test that names it turns red.
- **A mutation that might not be a mutation is a flaky test.** This repository's second most common
  bug shape, found four times on 2026-09-12 alone. A test draws a random value, changes it at a
  fixed position, and asserts the result is refused — but the "change" is a no-op whenever the
  drawn value already held the new one there, and the test then asserts that something valid is
  invalid. It fails at exactly the collision rate and reproduces for nobody:

  | Where | The change | Failed |
  | --- | --- | --- |
  | `components/recovery/groups.test.ts` | a whole 5-symbol group replaced with a fixed string | 1 in 31 |
  | `therapist/pairingAccept.test.ts` | a literal `'Z'` appended as the check symbol | 1 in 31 |
  | `therapist/pinRecord.test.ts` | SAS words 0 and 1 swapped | 1 in 256 |

  **Derive the mutation from the value, never write it as a literal**: `x === 'Z' ? 'Y' : 'Z'`, or
  find the first position that actually differs, or skip the case (`groups.test.ts`'s transposition
  sweep does the last). Then assert the mutated value really is different before asserting it is
  refused — one line, and it converts a mystery into a clear failure.

  Related: know what the primitive actually guarantees. A check symbol catches every SINGLE-symbol
  substitution and every adjacent transposition; it catches a multi-symbol change only 30 times in
  31. Asserting the stronger property is asserting something untrue.
- Web tests run in node with no DOM. Component properties are asserted **structurally over the
  `.svelte` source**; sequencing lives in ports-and-transitions modules (see
  `companion/web/src/lib/pairing/ownerCeremony.ts`) so ORDER is a node test.
- `companion/web/src/lib/docs.test.ts` resolves every backticked path in `docs/` and root markdown
  against the tree. A path you write in a doc must exist, or be declared absent with a reason.

## 6. Context discipline

Long sessions have repeatedly burned context re-deriving what is already written down. So:

- Start from §1's table and open one document. Do not read the corpus.
- Prefer one task per module; `companion/web` and `companion/server` have independent test oracles.
- Use subagents for fan-out search and keep their conclusion, not their file dumps.
- Anything worth keeping must be committed and pushed — the container is ephemeral.

## 7. The division of labour

Five jobs recur in this repository and four of them produce far more output than conclusion. Those
are delegated, so their noise never enters the main conversation. Definitions live in
`.claude/agents`; the commands a person types live in `.claude/skills`.

| Agent | Model | Can use | For |
| --- | --- | --- | --- |
| `verifier` | haiku | Bash, Read | Running the suites; returns counts and failures, never logs |
| `designer` | fable | Read only | One visible decision — layout, wording, what a screen says at its worst moment |
| `adviser` | fable | Read only | One bounded high-stakes call — a security trade-off, an architecture or product choice |
| `skeptic` | opus | read-only | Attacking a claim before it is believed; breaks the property and re-runs the test |
| `browser-pilot` | sonnet | Bash, Read, Write | Driving the consoles in Chromium and reporting what a person sees |

Commands: `/tests`, `/ux`, `/challenge`, `/walkthrough`, `/wrapup`.

**Briefing is the lead's job, not the agent's.** A forked agent sees none of this conversation, and
`designer` in particular has no search tools and a turn cap — deliberately, so it cannot wander.
Paste the actual source and state the decision you want back. A vague brief wastes the whole call,
and on the expensive model it wastes it visibly.

**Match the model to the shape of the job.** Fable decides — bounded judgement stated fully in a
brief, where the cost of being wrong is high and the cost of reading is low; it has no search tools
precisely so it cannot turn a decision into an investigation. Opus investigates, because that needs
context. Haiku counts. Sonnet drives the product. Reaching for the strongest model on a mechanical
job is the common waste; reaching for a cheap one on a judgement call is the expensive one.

**Scale.** Three to five agents is the useful range here; beyond that the coordination costs more
than the parallelism returns. Workflow scripts (`.claude/workflows`) exist for fan-out across
dozens of files and are almost never the right tool for this repository — reach for one only when
the same narrow question must be asked of many files at once.

**`/verify` is not ours.** A bundled skill owns that name and wins the invocation; it drives the
running app rather than the suites, which is the more valuable check and worth keeping. The suite
runner is `/tests`.

## 8. Working unattended

The backlog is GitHub issues in `keyxmakerx/daymark` labelled `claude-ready`. `/next` takes the
lowest-numbered one, does it, drops the label, and then either starts a fresh session for the next
item or goes quiet if none remain. The maintainer is not a programmer and is usually not
watching, so every report is written for someone who has not opened the repository.

**One issue per session, then hand off.** Context is re-sent every turn, so a session that wanders
costs many times one that finishes. Finishing and starting fresh is cheaper than continuing, which
is why the loop spawns a new session rather than carrying on in an old one.

**The list is the budget.** Dropping the label is how an item leaves the queue; only a person can
put one back. Nothing here can extend its own runway, and nothing may spin on the same issue twice.

**The line that is not yours to cross.** Push to a `claude/*` branch and stop there. Never push to
`main`, never open a pull request, never merge. The maintainer ships their own work.

**Stop early and say so** when a decision belongs to them — a trade-off about their product, their
users, or their money. Write the options into an issue comment and wait. A half-finished piece with
honest notes is a good session; a guess dressed as a decision is not.
