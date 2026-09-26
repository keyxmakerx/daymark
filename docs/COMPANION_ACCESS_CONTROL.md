# Daymark Companion — Access Control (Clinical Layer)

The **clinical layer**: how practices, roles and patient consent fit around the Companion's
per-relationship crypto, grants, pairing and audit log
([COMPANION_SECURITY.md](./COMPANION_SECURITY.md),
[COMPANION_ASSIGNMENTS.md](./COMPANION_ASSIGNMENTS.md)). The direction is settled; much of it is not
built. Each section opens with one status line saying which parts exist. The practice code is tested
against this document (`practice/threePlane.test.ts`), and the consoles name its sections, so its
headings and the role table are not reworded casually.

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
- [The annoyance budget](#the-annoyance-budget)
- [Clinician turnover](#clinician-turnover-what-a-handover-actually-is)
- [Asked and answered](#asked-and-answered-so-this-isnt-re-litigated)
- [Honest limits](#honest-limits)

---

## The three planes

**Status:** built, as the rule the practice code is held to — no practice action lives in the data
plane (`org/OrgRole.kt`, `practice/threePlane.test.ts`).

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

**Status:** built: a practice with members, roles and its own audit chain (`routes/OrgRoutes.kt`,
the practice console). Removing a member ends their membership and live sessions, not any patient's
relationship ([COMPANION_THERAPIST.md](./COMPANION_THERAPIST.md) §9a). Not built: membership changes
issuing or revoking grants (#289, #297); choosing a practice from a list (#298).

Each relationship pairs one owner with one clinician, whatever practice the clinician belongs to. The
clinical layer adds an **editable org**:

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

**Status:** built as a server-enforced catalog of actions (`org/OrgRole.kt`), mirrored in the consoles
(`practice/roles.ts`); the patient and the platform sysadmin are not practice roles. Not built: a
scheduling surface for the front desk (#299).

Roles gate **actions** (server‑enforced). Read capability is *separate* and comes
only from a patient grant. "Can read clinical content?" below means *is normally
granted a key*, not *is technically permitted to hold one by role*.

| Role | Manages | Normally reads clinical content? |
|---|---|---|
| **Patient / owner** | their own keys, grants, consent, audit view | Their own data — root of trust |
| **Psychologist / clinician** | assignments, notes, plans for granted clients | Yes — for clients who granted them |
| **Psychiatrist** | same as clinician; may publish Validated/Adapted tools | Yes — for granted clients |
| **Clinical assistant** | supports a clinician's work | Narrowed — only what's granted |
| **Front desk** | scheduling, invites, membership logistics | **No** — scheduling metadata only, no notes |
| **Supervisor** | oversees a team of clinicians | **Only via explicit, consented grant** (clinical supervision), never by title |
| **Org admin** | practice membership, roles, revocation, audit review | **No** — control/monitoring only |
| **Platform sysadmin** | runs the server/infra | **No — by design.** Ciphertext + ops metadata only |

In an office's own words (#288): the receptionist is the **Front desk**, the office administrator is
the **Org admin**, and a doctor is a **Psychologist / clinician** or a **Psychiatrist**. A doctor
who assesses someone and refers them on does so in an *assessing* care relationship, not in a role
of its own ([Cross‑provider sharing & referrals](#cross-provider-sharing--referrals)).

On a server that runs one practice, one person may hold the platform sysadmin role and the org admin
role, as two separate roles (#208). That is not the god admin: neither role carries a key, and
content still needs a patient's grant. Not built: #314 and #322, which give an administrator an
account of their own.

## Consent model

**Status:** built: per-person consent — the owner invites and pairs each clinician and chooses what to
share with them. Not built: org-consent (#289).

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

**Status:** not built (#291). Today each clinician needs their own invitation from the patient.

A therapist sharing with a psychiatrist (and vice versa) works, with one rule:
**read access always flows from the patient's consent, never from one clinician
handing another their key.**

- **Referral / flag** — any clinician may *request* that another see a client's
  data ("I'd like Dr. X to review this"). This is a request, carries no read
  capability.
- **Read** — the second provider can decrypt only once they hold a **grant**: the
  patient (or the care‑team org‑consent) put them on the team. Then a specific
  assessment/summary/note can be shared to them.
- **Bidirectional and audited** — the same both ways, and opens are recorded by
  the server in the patient‑readable log, which shows tampering, never what was
  left out (#217).
- **Made by people, never by software** — a doctor who assesses someone and
  recommends a therapist does so in an **assessing** care relationship, which
  ends by itself once the person has accepted or declined, unless they keep that
  doctor on. The assessing clinician chooses whom to recommend and the person
  decides; the Companion never suggests, ranks, filters or matches clinicians,
  and has nothing to match on (#288).

Referrals are free; **reading requires a grant.** This keeps the patient the root
of consent while supporting real care‑team collaboration.

## Revocation

**Status:** built: the server-side cutoff (a withdrawn share, or a re-signed `granted:false` grant; the
server answers 410 from then on), the clinician's own exit, and removing a practice member. Not built:
the cryptographic cutoff (#297), the kill switch (#295), and the owner ending a clinician's sign-in
(#210).

Three things must all be possible:

1. **Server‑side cutoff (immediate).** The token/grant instantly stops being
   served — a re‑signed `granted:false` grant, *future‑only*.
2. **Cryptographic cutoff (durable).** Rotate the client's data key and
   re‑wrap it for whoever's still authorized, so a revoked party's old key can't
   read *new* data (see [COMPANION_THERAPIST.md](./COMPANION_THERAPIST.md) §9).
3. **Kill switch.** An org admin (or the behavioral guard) can freeze an account
   or an entire clinician's access at once.

> **Honest limit:** revocation stops *future* access. It **cannot un‑read** data a
> clinician already decrypted. Say this in‑product.

## Key recovery

**Status:** built: server-access recovery, and a recovery code that wraps the web archive's key in the
browser (`lib/recovery/`). Not built: using that code from another device (#258) and split recovery
(#261). On the phone, the journal key's PIN and recovery-code wraps are #109.

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

**Status:** not built (#293).

Compatible with zero‑knowledge because it watches **behavior, not
content**:

- **Signals:** a token pulling hundreds of clients, a new geography, impossible
  travel, off‑hours bulk access, auth‑failure spikes.
- **Response:** **step up, don't hard‑lock** — pause the token, require re‑auth /
  MFA, or freeze pending admin review. A hard lockout could cut off a clinician
  mid‑session with a client in crisis.
- **Restraint:** log the **minimum**; a rich behavioral store is its own target
  and privacy liability. Short retention.

## HIPAA‑readiness checklist

**Status:** a map, not a certification. Neither assessment in the gate below has happened (#284).

Software is **HIPAA‑ready**; a *deployment + an organization* is what's
*compliant*. This maps our safeguards to the Security Rule so a practice *can* be
compliant when they run it right.

- **Access control** — unique user IDs (roles), automatic logoff (session idle
  expiry, shipped), encryption/decryption (E2E, shipped).
- **Audit controls** — the hash‑chained, metadata‑only audit log (shipped), per
  relationship and, separately, per practice for the org admin's review (shipped).
- **Integrity** — signed grants (shipped); signed snapshot manifests (not built: #138);
  clinician notes append‑only/amendable (not built: #300).
- **Person/entity authentication** — a six‑digit code at sign‑in (shipped);
  passkey sign‑in for every account, with codes kept as the fallback (#205; not
  built: #326); a fresh code, checked by the server, before a member is added or a
  role changed (shipped).
- **Transmission security** — TLS at the proxy + E2E payloads (shipped).
- **Administrative/physical** — *out of software's hands*: risk assessments,
  written policies, workforce training, **BAAs** (only if we ever host),
  breach‑notification procedures. Document what the practice must own (#284).

> **The gate:** an external HIPAA Security‑Rule assessment **and** an independent
> crypto/RBAC audit **before any real patient** (#284). Practice use with real
> patients also needs a clinician client the office's server cannot change
> (#319), and no patient typing their passphrase into a page the office serves
> (#174, #321), as decided in #222.

## The annoyance budget

**Status:** the rule is encoded in the practice capability model (`practice/capabilities.ts`,
`frictionRank`) and tested. A server-checked step-up is built for adding members and changing roles:
a fresh, unspent six-digit code (`routes/OrgRoutes.kt`). Opening a share and publishing need the
session only, by decision (#205). Not built: a passkey as step-up (#326), and step-up for admitting
someone to a care team (#289).

Least privilege **will** be annoying. There is no version of this that isn't, and pretending
otherwise is how security designs get quietly gutted the first time someone important is
inconvenienced. So we budget the annoyance deliberately rather than letting it land at random.

**The rule: friction goes where the risk is.** Rare, high-stakes, hard-to-undo actions should be
genuinely hard. Routine, reversible, low-blast-radius actions should be nearly free. A design that
charges the same friction for "read today's note for a client I already treat" and "add a clinician
to the practice" has mispriced both — and users will route around the expensive one.

| Action | Friction | Why |
|---|---|---|
| Read content you already hold a grant for | **None** — session auth only | The grant *was* the decision; charging again teaches people to hate the system |
| Author a note / game plan | None beyond session | Routine clinical work, auditable, reversible |
| Grant, extend, or widen a share | **Step-up (MFA)** when a clinician admits someone to a care team (#289); for the owner, their signature on the grant is the decision | Creates new read capability — the actual risk |
| Add/remove a practice member, change roles | **Step-up (MFA)** | Changes who *can* be granted |
| Revoke / kill switch | **Deliberately cheap** | Never make the safe direction expensive |
| Break-glass / emergency access | **Maximum** — justification + loud, immediate notification | Should feel like breaking glass. It never opens content, a key or anyone's credentials to an administrator (#288) |

Corollaries that follow from the same principle:

- **Never make the safe direction expensive.** Revoking, narrowing a share, and turning something
  off must always be easier than granting, widening, and turning on. Asymmetry is the point.
- **Step up, don't hard-lock.** Already the behavioral guard's rule; it generalises. A hard lockout
  can cut off a clinician mid-session with a client in crisis, which is its own harm.
- **Charge per decision, not per action.** Re-authorising the same standing decision repeatedly
  erodes the effect and trains people to click through. If a prompt is answered the same way every
  time, it is not a control.
- **The patient's own friction is capped hardest.** A person in a bad moment must never be locked out
  of *their own* data by a security measure meant to constrain someone else.

## Clinician turnover: what a handover actually is

**Status:** not built — no `care_relationships` table and no screen (#291).

The org is **one practice**, so the motion that matters is not multi-tenancy — it is people moving:
a GP referring out, a psychiatrist and a psychotherapist co-treating, someone covering a leave, and
a clinician **departing** with clients who must not be stranded.

**A referral and a transfer are the same control-plane object at two points in its life, and
neither moves a key.** One `care_relationships` table (patient, member, `care_role` of
primary/co-treating/covering/supervising/assessing, status, `ended_reason`). A referral *proposes* a
relationship, and a person always makes it, never software (#288); a transfer *ends* one and
proposes another. Because none of it mints read capability, reassignment stays cheap — session auth
and an audit entry, no step-up. That cheapness is the payoff for keeping roles and keys independent
in the first place.

Three hard edges:

- **`covering` must auto-expire.** Without a hard end date, covering a two-week leave quietly
  becomes permanent access.
- **`assessing` ends by itself too**, once the person has accepted or declined the referral, unless
  they choose to keep that doctor on, for example as co-treating (#288).
- **A transfer must never route through break-glass.** A planned departure is not an emergency, and
  that is the one door this design must not let it open.

### The turnover decision

Whether the care team may admit a new clinician, or whether every grant must be minted on the
patient's device, is a genuine trade with no free option:

| | Team may hand over | Patient mints every grant |
|---|---|---|
| Turnover | Works; new clinician reads day one | Strands until the patient acts |
| Compromise | A hijacked clinician account can admit an attacker-controlled one | No access exists the patient didn't authorise |
| Revocation | Needs re-key **and** assurance nobody re-admits | Clean |
| Who pays | The patient pays in control | The patient pays in continuity of care |

**Direction: team may hand over, hardened — with the stricter mode available per patient.** The
deciding argument is that the second column's failure lands hardest on exactly the people least able
to absorb it: someone unreachable for three weeks *because they are unwell* returns to a new
clinician who knows nothing. Four constraints keep the cost of the first column small:

1. **The care-team key carries strictly less than a personal grant** — assessment summaries,
   progress notes, game plans. **Never** journal free text, **never** process notes.
2. **Admission is loud** — adding a clinician notifies the patient immediately and appears in the
   roster they can prune.
3. **Admission requires step-up and is rate-limited** — a hijacked session must not be able to add
   readers quietly. Granting is precisely where the [annoyance budget](#the-annoyance-budget) says
   friction belongs.
4. **The patient can switch to patient-minted-only** — a per-patient setting for anyone who prefers
   the stricter trade, with its cost stated plainly on the same screen.

> **Honest limit, to state in-product:** under the default, the safeguard against a bad admission is
> the audit log and the patient's roster — **detective, not preventive**. Any current team member
> can cryptographically admit another. Do not describe this as "only your therapist can see it".

## Asked and answered (so this isn't re-litigated)

Recurring questions, and where they were already settled:

| Question | Answer | Where |
|---|---|---|
| "Attendants who only handle the time/scheduling piece?" | The **Front desk** role — scheduling, invites, membership logistics; **no** clinical content, scheduling metadata only | [Role catalog](#role-catalog) |
| "Other specialists — psychiatrists, assistants, supervisors?" | All in the catalog. A **supervisor reads only via explicit consented grant, never by title** | [Role catalog](#role-catalog) |
| "A group system that can be changed?" | **Orgs/practices** are the editable tenant; membership changes issue/revoke grants automatically; **org-consent** lets a client consent to "my care team at Practice X" and prune it any time | [Orgs](#orgs--practices-the-tenant), [Consent](#consent-model) |
| "Least privilege without a god admin?" | The **three-plane rule** — admins live in control + monitoring, **never** the data plane | [Three planes](#the-three-planes) |
| "Can a specialist see the safety plan?" | Not today (no `INTERNET` in the default build). If ever: an owner-created, curated, revocable share like anything else — never automatic | This table |

**Groups smaller than a practice** (decided in #301): the one group smaller than a practice is a
person's own care team (#289). There is no group of patients, and none spanning two practices. A
person's circle is their own list of connections (#174).

**Settled, and recorded elsewhere so it isn't reopened:** location/presence sharing is
**permanently excluded on principle**; timed/video/puzzle test items are **not built on the phone**;
tool descriptors are **bundled in the app**, never remotely delivered. The reasoning for all three is
in [COMPANION_ARCHITECTURE.md](./COMPANION_ARCHITECTURE.md), because a bare exclusion gets argued back
in and a reasoned one doesn't.

## Honest limits

- **Metadata leaks** even when content doesn't: which clinician, how many
  clients, when. Minimize and don't over‑log.
- **No forward secrecy** on shares/notes (sealed‑box CEK), carried from
  [COMPANION_SECURITY.md](./COMPANION_SECURITY.md).
- **Revocation can't un‑read** already‑decrypted content.
- **The browser portal is not zero‑knowledge against a hostile server** that
  serves malicious JS — an inherent web‑crypto limit, documented in
  [COMPANION_SECURITY.md](./COMPANION_SECURITY.md). Practice use with real
  patients therefore needs a clinician client the server cannot change (#222;
  not built: #319).
- **"Compliant" is the org's, not the software's.** We provide safeguards.
