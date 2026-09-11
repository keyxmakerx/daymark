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
