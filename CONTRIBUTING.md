# Contributing to Daymark

Thanks for your interest in helping build a free, private mood journal.

## Getting started

- **The Android app:** open the repo in Android Studio, or run `./gradlew assembleDebug` and
  `./gradlew test`.
- **The Companion web consoles:** `cd companion/web && pnpm install && pnpm test && pnpm check && pnpm build`.
- **The Companion server:** `cd companion/server && ./gradlew test`.

How the pieces fit together: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The index of every
reference document: [docs/README.md](docs/README.md).

## Ground rules

- **No AI, no machine learning, no generated content, anywhere.** Every word a person reads is fixed
  and human-written, with their own numbers slotted in. This is a mental-health app.
- **Local-first.** The released app has no network permission. Anything networked lives in the
  opt-in `sync` flavour and the self-hosted Companion, never in the core app.
- **FOSS dependencies only.** No Google Play Services, Firebase or trackers. The app aims at F-Droid.
- **Clean room.** Do not copy code, layouts, strings, icons, colour values or other assets from
  Daylio or any other closed-source app. Refer to other apps only by name, for factual comparison.
- **Follow the design rules:** [docs/DESIGN.md](docs/DESIGN.md) for the app,
  [docs/COMPANION_DESIGN_SYSTEM.md](docs/COMPANION_DESIGN_SYSTEM.md) and
  [docs/COMPANION_UX.md](docs/COMPANION_UX.md) for the web consoles. No green, success, tick, score,
  streak or congratulation anywhere, and a gap in someone's data is never drawn as a failure.
- Put testable logic in plain Kotlin with no Android imports (see `stats/` and `sky/`), and test it.

## How work is tracked

All work lives in [GitHub issues](https://github.com/keyxmakerx/daymark/issues), organised under the
[roadmap](https://github.com/keyxmakerx/daymark/issues/132). Each area has a tracking issue (label
`epic`) whose sub-issues are the pieces.

| Label | Meaning |
| --- | --- |
| `area: app`, `area: companion-server`, `area: companion-web`, `area: phone-sync`, `area: build`, `area: docs` | Which part of Daymark it touches. |
| `needs-decision` | Waiting on a choice only the maintainer can make. The issue lays out the options. |
| `needs-a-person` | Needs someone with a real phone, a real deployment or a real clinician. |
| `security` | Touches what the product promises about privacy and access. |
| `epic` | A tracking issue; its sub-issues are the work. |
| `claude-ready` | The maintainer's go-ahead for the unattended `/next` loop. **Only the maintainer adds it.** |

Issue types (Bug, Feature, Task) say what kind of work it is. The issue forms, "Work item" and
"Decision needed", ask for what the `/next` loop needs: the outcome in plain words, why it matters,
where it goes wrong ("Watch out"), and how to check it.

## Where things live

Each kind of information has one home. When something has two homes, one of them goes stale. A stale
document is how this project has repeatedly told a reader something false.

| What | Its one home | Never in |
| --- | --- | --- |
| Work to do: a bug, a feature, a task, anything deferred or "not built" | A GitHub issue | A document, a code comment, the changelog |
| A choice only the maintainer can make | An issue labelled `needs-decision`, closed with the answer | An "open questions" section |
| A lasting product decision | [docs/DECISIONS.md](docs/DECISIONS.md), linking the issue where it was made | A dated decisions file |
| A plan for something larger | A tracking issue (`epic`) with sub-issues | A planning document |
| How the system works today | The reference documents in [docs/README.md](docs/README.md) | An issue, because issues close |
| What changed, and when | [CHANGELOG.md](CHANGELOG.md), git history, closed issues and pull requests | A document's status banner |
| Why a piece of code is the way it is | A comment beside that code, in the present tense | A history of earlier versions |

A reference document describes what exists. Where something designed is not built, it says so in one
line and links the issue. It carries no status banners, dated addenda or to-do lists.
`companion/web/src/lib/docs.test.ts` checks that every path a document names exists.

## What code comments are for

- **Say what the code must keep true, and why.** Cover invariants, threats, the house rule a line
  enforces, and the trap the next editor would fall into. Write in the present tense, about the code
  as it is.
- **The story belongs to git and GitHub.** What was there before, the bug that prompted a change, and
  who found it belong in the commit message and the issue. Cite the issue instead, for example
  `(#101)`.
- **No to-dos.** Something unfinished is an issue, and the comment names its number.
- **Cite reference documents, never plans.** Write `docs/SKY.md §2.2` or `DECISIONS.md §D6`.

Trimming the older comments to this rule is tracked, module by module, in
[#133](https://github.com/keyxmakerx/daymark/issues/133).

## Checking a change

| Part | The check |
| --- | --- |
| Companion web | `pnpm test && pnpm check && pnpm build` in `companion/web` |
| Companion server | `./gradlew test` in `companion/server`. Count the results in the XML reports rather than trusting the banner. |
| App, Android-free packages | `tools/jvm-tests.sh sky`, `tools/jvm-tests.sh stats`, `tools/jvm-source-tests.sh` |
| App, everything else | CI (`.github/workflows/build.yml`) is the final word for anything with an Android import |

Every test that asserts something is absent needs a positive control: show that it catches a planted
example. Break anything load-bearing on purpose and confirm the test that names it fails.

## Commits and pull requests

- Commit subjects are lowercase `type(scope): what changed`, stated from the product's point of view,
  for example `fix(ui/sky): a pinch magnifies the place you are pinching`. The body says why, for
  someone who was not there.
- One focused change per pull request. Say what and why, include screenshots for UI changes, update
  `CHANGELOG.md` under *Unreleased*, and name the issue it closes.
- Automated agents work on `claude/*` branches and never push to `main`.

## Developer Certificate of Origin

By contributing, you agree that your contributions are licensed under **GPL-3.0-only** (inbound =
outbound). Sign off your commits (`git commit -s`) to certify the
[DCO](https://developercertificate.org/). Whether to enforce this with a check is an open decision:
#{D12}.

## Where to start

Issues in the [roadmap](https://github.com/keyxmakerx/daymark/issues/132) without `needs-decision`
or `needs-a-person` are ready to pick up. Small ones say so in their title or body.
