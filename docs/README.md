# Daymark documentation

These documents describe Daymark as it is built today. Work that is not done yet is tracked in
[GitHub issues](https://github.com/keyxmakerx/daymark/issues), under the
[roadmap](https://github.com/keyxmakerx/daymark/issues/132), not here. Where a document mentions
something designed but not built, it links the issue. [CONTRIBUTING.md](../CONTRIBUTING.md) explains
which kind of information lives where.

## Start here

| Document | What it answers |
| --- | --- |
| [README.md](../README.md) | What Daymark is, its status, and how to build it |
| [ARCHITECTURE.md](ARCHITECTURE.md) | How the app is put together: modules, flavours, data, encryption, tests, CI |
| [DECISIONS.md](DECISIONS.md) | Why the product is the way it is. The numbered decisions code cites |
| [CHANGELOG.md](../CHANGELOG.md) | What changed |

## The Android app

| Document | What it answers |
| --- | --- |
| [FEATURES.md](FEATURES.md) | Every feature, and the rules each one keeps |
| [SKY.md](SKY.md) | "Your sky": how acts become stars, and why it never grades a day |
| [DESIGN.md](DESIGN.md) | The app's visual design system |
| [USER_GUIDE.md](USER_GUIDE.md) | How to use each screen, for the people who use the app |
| [FAQ.md](FAQ.md) | Short answers for people who use the app |
| [INSTRUMENTS.md](INSTRUMENTS.md) | Which questionnaires are bundled, under what licence, and which are excluded |
| [PROVENANCE.md](PROVENANCE.md) | How every tool declares what it is: Validated, Adapted, or Custom |
| [TOOLCHAIN.md](TOOLCHAIN.md) | How the build toolchain is pinned, and what is held back and why |

## The Companion

| Document | What it answers |
| --- | --- |
| [companion/README.md](../companion/README.md) | Running the Companion: images, quick start, health checks |
| [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md) | The shapes, roles, trust boundaries, components, and the honest limits |
| [COMPANION_SECURITY.md](COMPANION_SECURITY.md) | The threat model, cryptography, sign-in, hardening and audit |
| [COMPANION_PAIRING.md](COMPANION_PAIRING.md) | How an owner and a clinician pair, down to the bytes |
| [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) | The clinician's side: shares, game plans, leaving |
| [COMPANION_ACCESS_CONTROL.md](COMPANION_ACCESS_CONTROL.md) | Practices, roles, consent and revocation. Its unbuilt sections link their issues |
| [COMPANION_ASSIGNMENTS.md](COMPANION_ASSIGNMENTS.md) | Capabilities and the clinician-to-owner assignment channel |
| [COMPANION_DIALOGUE.md](COMPANION_DIALOGUE.md) | The companion dialogue: its content rules and security findings |
| [COMPANION_FEATURES.md](COMPANION_FEATURES.md) | The self-check engine and its honesty gate |
| [COMPANION_PHONE.md](COMPANION_PHONE.md) | What the phone must do to join the Companion |
| [SYNC_PROTOCOL.md](SYNC_PROTOCOL.md) | The encrypted sync wire format |
| [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) | The operator's guide: topology, proxy, configuration, backup and restore |
| [COMPANION_OBSERVABILITY.md](COMPANION_OBSERVABILITY.md) | What the server exposes and logs, and the operator's runbook |
| [COMPANION_UX.md](COMPANION_UX.md) | The consoles' UX and copy rules |
| [COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md) | The consoles' visual contract, and the rules the tests enforce |
| [alternatives/README.md](alternatives/README.md) | Worked reverse-proxy configurations |
| [companion/INSTRUMENTS.md](../companion/INSTRUMENTS.md) | The Companion's instrument ledger, which a test reads as data |

## Also here

- [design/README.md](design/README.md) — the concept mockups, kept as design history.
- [prototypes/your-sky.html](prototypes/your-sky.html) — the signed-off look of the Sky.

## Project files

[CONTRIBUTING.md](../CONTRIBUTING.md), [SECURITY.md](../SECURITY.md), [PRIVACY.md](../PRIVACY.md),
[CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md), and [CLAUDE.md](../CLAUDE.md), the working agreement
for the agents that work on this repository.
