# Dependency security audit — August 2026

Asked directly: *"do we need to upgrade java, or any other codes for security concerns, if so please
lets make a plan for it"*

Everything below was **measured**, not recalled: audits run, jars and `.aar` files downloaded and
their contents inspected, npm registry metadata fetched. Where something could not be established
here it says so rather than guessing.

---

## The short answer

**Java is not a security concern.** Neither runtime is end-of-life:

| | JDK | Status |
|---|---|---|
| `companion/server` (own Gradle build) | **21** | Supported LTS |
| Docker runtime | distroless **java21** | Supported LTS |
| `:app` / `:sync-crypto` | **17** | Supported LTS |

Java 25 (Sept 2025) is the current LTS and Java 26 is out, so 17 is two LTS generations back — but
"behind" and "unsupported" are different things, and 17 is still receiving security updates. Moving
the server to 25 is **currency, not security**, and should not be sold as the latter.

There is exactly **one** place where upgrading Java is genuinely security-motivated, and there it is
a *means* rather than the goal — see item 1.

---

## Findings, ranked by what actually reaches a user

### 1. The phone ships libsodium 1.0.18, from 2019 — HIGH

Measured by extracting `jni/arm64-v8a/libsodium.so` from the published artifacts:

| Artifact | libsodium bundled | JDK required |
|---|---|---|
| `lazysodium-android` **5.1.0** ← shipping today | **1.0.18** (2019) | 8 |
| `lazysodium-android` **5.2.0** | **1.0.20** (2024) | **21** |

This is the actual C cryptography running on the phone, not a wrapper around it. No specific CVE is
being asserted — 1.0.18 has not been audited for that as part of this — but two releases and roughly
five years behind is the wrong default for the component that encrypts a mental-health journal.

**Why it was missed, which matters more than the finding.** The pinning comment in
`gradle/libs.versions.toml` justifies staying on 5.1.0 partly with *"the crypto conformance vectors
are byte-identical across the two"*. That is true and it is about the **API surface**. It says
nothing about the native library underneath, and the comment reads as though it settles the
question. An earlier analysis in this session repeated the same mistake, comparing bindings and
never once looking at the `.so`.

**Second defect in the same comment.** It states that both artifacts *"MUST stay pinned to the same
version"* to preserve cross-artifact API parity. **That invariant is false today.** At 5.1.0 the
android `LazySodium` exposes 328 public methods and the java one 259 — and the 69-method gap is
precisely the Ristretto surface (see the plan doc §3.7.6). A comment cannot enforce an invariant;
this one silently stopped being true and nothing noticed.

**The blocker.** `:sync-crypto` is on `jvmToolchain(17)` because it shares the root Gradle build with
`:app`, and 5.2.0's Gradle metadata marks it JVM-21-only. Someone has already hit this: the comment
records the exact failure (`":sync-crypto:compileKotlin" — "compatible with JVM 21 or newer"`).

**The untested part** — and the one question that decides everything: `jvmToolchain` is *per-module*,
so `:sync-crypto` does not have to match `:app`. Can `:app` consume a **Java 21** `:sync-crypto`
carrying `lazysodium-android` 5.2.0? That comes down to whether AGP 8.13 / D8 desugars a Java 21
dependency for `compileSdk 36`.

**This cannot be answered on the dev machine.** `dl.google.com` returns 403 through the environment's
proxy, so the Android Gradle Plugin does not resolve at all and the root build cannot even configure.
CI is the only oracle, and it is one run.

### 2. Digest-pinned base images never receive security patches — HIGH

Every `FROM` in `companion/Dockerfile` is pinned by digest. That is correct for reproducibility and
was a deliberate decision. The consequence is easy to forget: **a digest is immutable, so the
running server keeps the OS and JRE it was built with until somebody re-pins it on purpose.**

`gcr.io/distroless/java21-debian13` is the **deployed runtime** — the OS and JRE the server actually
executes on. Patches for it arrive only as a digest bump.

**Dependabot has already opened that bump: PR #66.** It is the single highest-value security merge
available right now, and it is sitting open.

This is also the mechanism behind the earlier "my Docker image is not pulling a newer image"
confusion. Same property, seen from the other side.

### 3. `sqlite-jdbc` is six minor versions behind — MEDIUM

`3.47.1.0` → `3.53.2.1` (PR #68). This artifact bundles a **native SQLite build**, so it is the same
shape of risk as item 1: the version string names a Java wrapper, and the thing doing the work is C.
Every server record — invites, TOTP credentials, sessions, the audit chain, the durable attempt
counters added in PR #75 — goes through it.

### 4. Nine dev-toolchain vulnerabilities; **production is clean** — MEDIUM (and not what it looks like)

```
pnpm audit         -> 9 vulnerabilities: 1 critical, 4 high, 4 moderate
pnpm audit --prod  -> No known vulnerabilities found
```

Every one of them is build and test tooling — `vitest`, `vite`, `esbuild`, `postcss`, `nanoid`,
`launch-editor`. **Nothing in the shipped bundle has a known vulnerability.** The "critical" is
Vitest's UI server, which is a dev affordance and is never run in CI (`vitest run` is headless) and
never deployed.

That does not make them ignorable — a compromised developer machine or CI runner is a supply-chain
route into a product that ships cryptography — but it is a different urgency class from anything a
user is exposed to, and the distinction should not be flattened in either direction.

### 5. Build-stage `node:24-alpine` — LOW

PR #49 (`24-alpine` → `26-alpine`). Build stage only; it does not ship in the runtime image. Alpine
CVE currency, worth taking with the other Docker work.

---

## Explicitly **not** security, so they do not jump the queue

- **Java 25 for the server.** Currency. `gcr.io/distroless/java25-debian13` exists (verified), so it
  is easy — but easy is not urgent, and it should not ride in on a security argument.
- **The browser's libsodium.** `libsodium-wrappers-sumo` 0.7.16 was published **2025-12-29**; 0.8.4
  is April 2026. Eight months and one minor line — currency. Worth stating plainly because it is the
  natural assumption after item 1, and it is **not** the same problem: the browser's crypto is
  recent, the phone's is from 2019.
- **AGP 9 / androidx / Kotlin / TypeScript 7 / Hilt.** Deliberately held, each with a documented
  reason in `libs.versions.toml` and `docs/TOOLCHAIN.md`. Several are mutually gated: AGP 9 drags
  Gradle 9.5+, Kotlin ≥ 2.2.10 and Hilt ≥ 2.59 simultaneously, and the androidx group additionally
  needs `compileSdk 37` → AGP 9.2 → Kotlin > 2.3.21, which Dagger caps. These are real engineering
  projects, not neglect.
- **CI action major bumps** (`checkout`, `setup-java`, `setup-node`, `docker/*`). Hygiene.

---

## The plan

Ordered so that what reaches a user moves first, and so that nothing expensive starts before a cheap
question that could invalidate it has been answered.

### Step A — Merge the runtime patch. Today.

**PR #66**, the distroless digest bump. This is the OS and JRE the server runs on, the fix already
exists, and every day it stays open is a day the deployed runtime is missing patches. Take **PR #49**
(node base) with it if CI is green.

*Acceptance:* the companion image rebuilds and the health probe passes.

### Step B — Answer the one question that decides item 1. One CI run.

Raise `:sync-crypto` to `jvmToolchain(21)`, move both lazysodium artifacts to 5.2.0, push, and read
CI. That is the whole experiment. It cannot be run locally — the Android plugin does not resolve
here — so it must be a branch and a CI run, and it should be done **before** any CPace work commits
to a lazysodium version.

- **If CI is green:** current crypto on the phone, the Ristretto binding on both sides, and the
  split-version pin proposed in PR #75 becomes unnecessary. Three problems close together.
- **If CI is red:** we are pinned to 2019 crypto until Android's toolchain moves. That is a
  significant, quiet limitation and it belongs in the plan doc's *"only a device can settle these"*
  list — loudly — rather than being rediscovered next year.

Either way, **fix the two defects in the pinning comment**: it justifies the pin with an API
comparison that does not address the native library, and it asserts a same-version parity invariant
that measurement disproves.

### Step C — `sqlite-jdbc`, and a test that outlives the comment.

Merge **PR #68**. The server suite (280 tests) covers this path well, so the risk is low and the
verification is real.

Then replace the parity *comment* with a **reflection test** asserting that `LazySodium`'s public
method set matches across the java and android artifacts. The invariant is currently enforced by
prose, which is how it managed to be false without anyone noticing. This is the same lesson as the
vacuous restart test in PR #75: a property nothing checks is a property that has already stopped
being true somewhere.

### Step D — Dev toolchain, batched.

Take the `vitest` / `vite` / `postcss` line together — they are one dependency cluster and moving
them piecemeal means running the 835-test suite once per PR for no benefit. Hold **PR #54**
(TypeScript 5 → 7) out of the batch; a major TypeScript bump is its own change with its own failure
modes.

*Acceptance:* `pnpm audit` reports zero, `pnpm audit --prod` still reports zero, 835 tests green.

### Step E — Currency, unhurried.

CI action majors, `jna`, greenmail, shadow. Then, separately and on its own branch, the server
JDK 21 → 25 with the `java25-debian13` base. Not urgent, not security, genuinely nice to have.

---

## What this audit did not cover

Stated so the gaps are known rather than assumed away:

- **No CVE lookup was performed against libsodium 1.0.18 specifically.** The recommendation rests on
  it being two releases and five years behind, not on a named advisory. Someone should check
  whether 1.0.19 or 1.0.20 carried a security fix that matters to the primitives actually used
  (XChaCha20-Poly1305, Argon2id, BLAKE2b, `crypto_kx`) — that would sharpen Step B from
  "good hygiene" to "do it now" or relax it.
- **No transitive audit of the Android dependency tree.** There is no `gradle dependencies` output
  here, because the root build cannot resolve at all in this environment. It needs a CI job.
- **Nothing was verified on a device.** Consistent with the plan doc's standing list — no PDF has
  been rendered, no migration has run on hardware, and now no ristretto path has been exercised on
  real Android.
