# Daymark Companion — Access Control (Clinical Layer)

> ## ⚠️ STATUS: DESIGN — DIRECTION LOCKED, LARGELY NOT YET IMPLEMENTED
>
> This is the **clinical layer** that turns the single‑therapist Companion into a
> multi‑client, multi‑role platform ([PRODUCT_DIRECTION.md](./PRODUCT_DIRECTION.md)).
> It **builds on** the shipped crypto, capability grants, pairing, and audit log
> (see [COMPANION_SECURITY.md](./COMPANION_SECURITY.md),
> [COMPANION_ASSIGNMENTS.md](./COMPANION_ASSIGNMENTS.md)) and **extends** their
> two‑role model to orgs and multiple roles. The org model, multi‑role RBAC,
> behavioral guard, org‑consent, and break‑glass are **net‑new** (Phase 3+).

---

## Contents

- [The three planes](#the-three-planes)
- [Orgs / practices (the tenant)](#orgs--practices-the-tenant)
- [Role catalog](#role-catalog)
- [Consent model](#consent-model)
- [Cross‑provider sharing & referrals](#cross-provider-sharing--referrals)
- [Revocation](#revocation)
- [Key recovery](#key-recovery)
- [Behavioral guard (IDS)](#behavioral-guard-ids)
- [HIPAA‑readiness checklist](#hipaa-readiness-checklist)
- [Honest limits](#honest-limits)

---

## The three planes

The system separates into three planes, and **who touches which plane is the
whole design**:

1. **Data plane** — ciphertext only. Patients hold keys; clinicians/roles hold
   *grants* (a wrapped key the patient authorized). The server never sees
   plaintext.
2. **Control plane** — RBAC + capability management: who may do what, grant/
   revoke, role and membership admin. Server‑enforced; operates on the
   **capability graph and metadata**, never plaintext.
3. **Monitoring plane** — the hash‑chained audit log + the behavioral guard.
   Metadata only.

> **The rule that makes it coherent:** admins live in the **control** and
> **monitoring** planes, **never** the data plane. That is how "an admin can
> revoke anyone and see who‑accessed‑what, yet cannot read a single clinical
> note" is *true*, not marketing.

## Orgs / practices (the tenant)

Today the system is per‑pairing: one owner ↔ one pinned therapist, roles
`OWNER`/`THERAPIST` only. The clinical layer adds an **editable org**:

- An **Org (Practice)** is the tenant. It has **members** (with roles) and an
  **Org Admin** who manages *its* membership and roles — **scoped to that
  practice**, not a global super‑admin.
- The server is **multi‑tenant but blind** — it sees org structure and
  membership metadata, never clinical content.
- **Editable membership** drives access: adding a clinician provisions their
  grants; removing one (they leave the practice) triggers revocation + key
  rotation (see [Revocation](#revocation)).
- A **patient/client** is *not* owned by the org. They own their keys; the org is
  a **membership/addressing convenience**. A provider reads a client's data only
  via a grant the client (or an org‑consent they signed) authorized.

There is **no single "god" admin** who both manages everyone and can read
everything. Management authority (org admin) and read capability (a patient
grant) are deliberately separate — see the three‑plane rule.

## Role catalog

Roles gate **actions** (server‑enforced). Read capability is *separate* and comes
only from a patient grant. "Can read clinical content?" below means *is normally
granted a key*, not *is technically permitted to hold one by role*.

| Role | Manages | Normally reads clinical content? |
|---|---|---|
| **Patient / owner** | their own keys, grants, consent, audit view | Their own data — root of trust |
| **Psychologist / clinician** | assignments, notes, plans for granted clients | Yes — for clients who granted them |
| **Psychiatrist** | same as clinician; may publish Validated/Adapted tools | Yes — for granted clients |
| **Therapist assistant** | supports a clinician's work | Narrowed — only what's granted |
| **Front desk** | scheduling, invites, membership logistics | **No** — scheduling metadata only, no notes |
| **Supervisor** | oversees a team of clinicians | **Only via explicit, consented grant** (clinical supervision), never by title |
| **Org admin** | practice membership, roles, revocation, audit review | **No** — control/monitoring only |
| **Platform sysadmin** | runs the server/infra | **No — by design.** Ciphertext + ops metadata only |

## Consent model

- **Patient is always the root of consent.** Roles decide who *may request*
  access; the patient's grant is what *authorizes* it.
- **Org‑consent** (net‑new): to make real clinics workable, a client can consent
  at intake to "**my care team at Practice X**." The practice then manages who is
  on that team; membership changes issue/revoke grants automatically. This is a
  convenience over per‑person consent — it must be **explicit, revocable, and
  auditable**, and the client can always see and prune the current team.
- **Minimum necessary** governs every grant: a front‑desk grant is scheduling
  metadata; an assistant's is narrower than the clinician's; a supervisor's is a
  separate, explicit grant.
- Consent flows use **no dark patterns** (carried over from
  [COMPANION_UX.md](./COMPANION_UX.md)).

## Cross‑provider sharing & referrals

A therapist sharing with a psychiatrist (and vice versa) works, with one rule:
**read access always flows from the patient's consent, never from one clinician
handing another their key.**

- **Referral / flag** — any clinician may *request* that another see a client's
  data ("I'd like Dr. X to review this"). This is a request, carries no read
  capability.
- **Read** — the second provider can decrypt only once they hold a **grant**: the
  patient (or the care‑team org‑consent) put them on the team. Then a specific
  assessment/summary/note can be shared to them.
- **Bidirectional and audited** — the same both ways, and every open is recorded
  in the patient‑readable audit log.

Referrals are free; **reading requires a grant.** This keeps the patient the root
of consent while supporting real care‑team collaboration.

## Revocation

Two things must both be possible:

1. **Server‑side cutoff (immediate).** The token/grant instantly stops being
   served — already shipped as a re‑signed `granted:false` grant, *future‑only*.
2. **Cryptographic cutoff (durable) — net‑new.** Rotate the client's data key and
   re‑wrap it for whoever's still authorized, so a revoked party's old key can't
   read *new* data. This is the piece the current design explicitly does **not**
   have yet (see [COMPANION_THERAPIST.md](./COMPANION_THERAPIST.md) §9).
3. **Kill switch.** An org admin (or the behavioral guard) can freeze an account
   or an entire clinician's access at once.

> **Honest limit:** revocation stops *future* access. It **cannot un‑read** data a
> clinician already decrypted. Say this in‑product.

## Key recovery

E2E's hardest UX problem: a lost passphrase currently means lost data, and the
design deliberately has **no key escrow** (no backdoor). We keep no‑escrow and
add **user‑held recovery**:

- **Recovery codes** — printed/stored by the user at setup.
- **Optional social / Shamir recovery** — the user splits recovery across people
  or devices *they* choose.
- The **server never holds** a recovery secret. The *user* can recover; the
  *server* still can't read. This preserves zero‑knowledge while removing the
  "one forgotten passphrase = total loss" cliff.

This is distinct from the already‑shipped **access‑token** recovery, which only
restores *server access*, never the encryption key.

## Behavioral guard (IDS)

Net‑new, and compatible with zero‑knowledge because it watches **behavior, not
content**:

- **Signals:** a token pulling hundreds of clients, a new geography, impossible
  travel, off‑hours bulk access, auth‑failure spikes.
- **Response:** **step up, don't hard‑lock** — pause the token, require re‑auth /
  MFA, or freeze pending admin review. A hard lockout could cut off a clinician
  mid‑session with a client in crisis.
- **Restraint:** log the **minimum**; a rich behavioral store is its own target
  and privacy liability. Short retention.

## HIPAA‑readiness checklist

Software is **HIPAA‑ready**; a *deployment + an organization* is what's
*compliant*. This maps our safeguards to the Security Rule so a practice *can* be
compliant when they run it right.

- **Access control** — unique user IDs (roles), automatic logoff (session idle
  expiry, shipped), encryption/decryption (E2E, shipped).
- **Audit controls** — the hash‑chained, metadata‑only audit log (shipped);
  extend to org‑level review.
- **Integrity** — signed manifests/grants (shipped); notes append‑only/amendable
  ([CLINICAL_NOTES.md](./CLINICAL_NOTES.md)).
- **Person/entity authentication** — finish **WebAuthn** (today a 501 stub); MFA
  everywhere; step‑up for sensitive actions.
- **Transmission security** — TLS at the proxy + E2E payloads (shipped).
- **Administrative/physical** — *out of software's hands*: risk assessments,
  written policies, workforce training, **BAAs** (only if we ever host),
  breach‑notification procedures. Document what the practice must own.

> **The gate:** an external HIPAA Security‑Rule assessment **and** an independent
> crypto/RBAC audit **before any real patient** — see
> [PRODUCT_DIRECTION.md](./PRODUCT_DIRECTION.md#the-compliance-gate-non-negotiable).

## Honest limits

- **Metadata leaks** even when content doesn't: which clinician, how many
  clients, when. Minimize and don't over‑log.
- **No forward secrecy** on shares/notes (sealed‑box CEK), carried from
  [COMPANION_SECURITY.md](./COMPANION_SECURITY.md).
- **Revocation can't un‑read** already‑decrypted content.
- **The browser portal is not zero‑knowledge against a hostile server** that
  serves malicious JS — an inherent web‑crypto limit, documented in
  [COMPANION_SECURITY.md](./COMPANION_SECURITY.md).
- **"Compliant" is the org's, not the software's.** We provide safeguards.
