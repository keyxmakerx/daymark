/*
 * THE PRACTICE CAPABILITY VOCABULARY — what a person can be authorized to DO, annotated with the
 * four facts that decide whether the access-control design's central claim is true.
 *
 * ─── WHAT THIS IS AN EXTENSION OF, AND WHY IT IS NOT A NEW PERMISSION SYSTEM ────────────────────
 *
 * The shipped product already has capabilities: assignments/types.ts defines eight of them
 * (`read.share`, the five `assign.*`, `authorGamePlan`, `suggest.setting`), the owner switches them
 * on per therapist in GrantManager.svelte, one CapabilityRow.svelte per capability, and the result
 * is an owner-signed Grant that the therapist's client verifies against a pinned key. That is the
 * vocabulary. This file WIDENS it rather than starting a second one, because a parallel permission
 * model is how a system ends up with two answers to "may they?" and no way to tell which one the
 * server actually enforced.
 *
 * So `PracticeCapability = Capability | PracticeOnlyCapability`: the existing eight keep their
 * exact ids, their exact copy (describeCapability is delegated to, never re-written here), and
 * their exact meaning. Everything added is a capability the clinical layer needs and the
 * single-therapist product never had — practice membership, roles, revocation, audit review,
 * scheduling, and the read scopes that org-consent and clinical supervision introduce.
 *
 * ─── THE FOUR FACTS, AND WHY EACH ONE IS DECLARED RATHER THAN DERIVED ───────────────────────────
 *
 * docs/COMPANION_ACCESS_CONTROL.md § The three planes states the rule the whole design rests on:
 *
 *     "admins live in the control and monitoring planes, never the data plane. That is how
 *      'an admin can revoke anyone and see who-accessed-what, yet cannot read a single clinical
 *      note' is true, not marketing."
 *
 * A paragraph cannot fail a build. To make that sentence checkable, every capability carries:
 *
 *   plane           which of the three planes exercising it operates in. DECLARED, not inferred
 *                   from the id, because "read.*" being a data-plane thing is exactly the kind of
 *                   inference a future id like `audit.readOwnNotes` would quietly break.
 *
 *   reads           WHOSE clinical content it puts in front of a human: 'none', 'own', or
 *                   'others'. This is deliberately three values and not a boolean, because the
 *                   patient legitimately reads their own record and every admin role legitimately
 *                   reads nobody's. A boolean would force the patient to be an exemption in a
 *                   list, and an exemption list is a hole that grows.
 *
 *   authorization   what actually mints it. § Role catalog: "Roles gate actions (server-enforced).
 *                   Read capability is separate and comes only from a patient grant." A capability
 *                   marked 'grant-only' may therefore never appear in a role preset — that is the
 *                   machine form of "a role never carries a key", and threePlane.test.ts enforces
 *                   it over the whole catalog rather than role by role.
 *
 *   friction        what the § The annoyance budget table charges for it. Carried here because the
 *                   budget's own corollary ("never make the safe direction expensive") is an
 *                   asymmetry between two capabilities, and an asymmetry can only be checked if
 *                   both sides are numbers on the same scale.
 *
 *   mintsRead       whether exercising it creates read capability for SOMEBODY ELSE. The fourth
 *                   fact, and the one that catches what the other three cannot: a capability can be
 *                   control-plane, read nothing, be conferred by a role, and still be the act that
 *                   puts a reader in front of a record — `consent.orgTeam` is exactly that, and
 *                   § Consent model says the patient is always the root of it. Without this,
 *                   "nobody but the patient may authorize a reader" can only be asserted about the
 *                   one capability someone remembered to name.
 *
 * ─── WHY THE TYPE DOES NOT PREVENT THE THING THE TEST FORBIDS ───────────────────────────────────
 *
 * A reader will notice that a role's preset is typed as PracticeCapability[] — wide enough to hold
 * `read.share` — and will be tempted to narrow it to a "role-conferrable capability" type so the
 * compiler rejects the mistake outright. Do not. If the model cannot express the violation, the
 * invariant test can only assert a tautology, and a suite that cannot go red is a suite that
 * measures nothing. The whole point of the exercise upstream of this file was that the claim be a
 * machine-checked FACT; facts have to be falsifiable. Keep the type wide, keep the test sharp.
 */
import type { Capability } from '../assignments/types'
import { ALL_CAPABILITIES } from '../assignments/types'
import { describeCapability } from '../assignments/describe'

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   1. The three planes.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * docs/COMPANION_ACCESS_CONTROL.md § The three planes, transcribed as a type.
 *
 *   data        ciphertext only. Patients hold keys; clinicians/roles hold grants (a wrapped key
 *               the patient authorized). The server never sees plaintext.
 *   control     RBAC + capability management: who may do what, grant/revoke, role and membership
 *               admin. Server-enforced; operates on the capability graph and metadata.
 *   monitoring  the hash-chained audit log + the behavioral guard. Metadata only.
 */
export type Plane = 'data' | 'control' | 'monitoring'

export const PLANES: readonly Plane[] = ['data', 'control', 'monitoring']

export const PLANE_LABEL: Record<Plane, string> = {
  data: 'Data plane',
  control: 'Control plane',
  monitoring: 'Monitoring plane',
}

/**
 * One sentence per plane, for the surfaces that have to explain this to a person who did not read
 * the design document. Phrased as what the plane touches, because "what can this person see" is
 * the question anyone actually has.
 */
export const PLANE_RULE: Record<Plane, string> = {
  data:
    'Encrypted content, and the keys that open it. Patients hold their own keys; anyone else ' +
    'reads only what a patient granted them.',
  control:
    'Who may do what: membership, roles, grants, revocation. Operates on the permission graph ' +
    'and on metadata — never on content.',
  monitoring:
    'The access log and the behavioral guard. Records that something happened, who did it and ' +
    'when. It carries no content, by construction.',
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   2. Whose clinical content, and what mints the capability.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Whose clinical content exercising a capability puts in front of a human.
 *
 * 'own' exists for exactly one capability — the patient holding their own keys — and it earns its
 * place. § Role catalog answers the patient row's "reads clinical content?" with "Their own data —
 * root of trust", which is a categorically different sentence from a clinician's "Yes — for
 * clients who granted them". Collapsing the two into one boolean would have forced the invariant
 * test to special-case the patient by name, and a rule with a named exception is one rename away
 * from covering nothing.
 *
 * NOTE the honest limit of this field: it is a label, and a capability that reads clinical content
 * while claiming 'none' would sail past every test here. Nothing in a data model can prevent that.
 * What the model can do is make the claim explicit and reviewable in a diff, which is why the
 * value is written per capability rather than inferred from a name pattern.
 */
export type ClinicalRead = 'none' | 'own' | 'others'

/**
 * What mints a capability. This is § Role catalog's opening sentence as a type:
 * "Roles gate actions (server-enforced). Read capability is separate and comes only from a
 * patient grant."
 *
 *   role            role membership alone confers it, and the server enforces it. Practice-level
 *                   authority: invite, revoke, review the log, run the box.
 *   role-and-grant  BOTH are required. The role is the ceiling on what class of action this person
 *                   may perform at all in the practice; the patient's signed grant decides whose
 *                   record they may perform it on. A front-desk member does not become able to
 *                   write a clinical note because one patient granted it, and a clinician does not
 *                   become able to write in a record nobody granted them.
 *   grant-only      nothing but a patient's signed grant confers it. No role preset may contain
 *                   one. Every read scope in the catalog is here, which is the point.
 *   break-glass     an emergency override that is not a grant at all.
 */
export type Authorization = 'role' | 'role-and-grant' | 'grant-only' | 'break-glass'

/**
 * What § The annoyance budget charges. Three levels, because the table has three prices:
 *
 *   none      "None — session auth only" / "None beyond session" / "Deliberately cheap".
 *   step-up   "Step-up (MFA)" — the actions that create new read capability or change who can be
 *             granted one.
 *   maximum   "Maximum — justification + loud, immediate notification" — break-glass, and only
 *             break-glass. It "should feel like breaking glass".
 *
 * "Deliberately cheap" and "none" collapse to the same price on purpose. The budget's corollary is
 * not that revoking is cheap in absolute terms but that it is never MORE expensive than granting;
 * expressing that needs an ordering, which is what frictionRank gives.
 */
export type Friction = 'none' | 'step-up' | 'maximum'

export const FRICTION_LABEL: Record<Friction, string> = {
  none: 'Your session only',
  'step-up': 'Confirm it again (step-up)',
  maximum: 'Justification, and everyone is told',
}

/** An ordering, so "the safe direction is never the expensive one" is a comparison and not prose. */
export function frictionRank(f: Friction): number {
  return f === 'none' ? 0 : f === 'step-up' ? 1 : 2
}

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   3. The capabilities the clinical layer adds.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Capabilities the single-therapist product had no need for. Ids follow the existing dotted
 * convention (`read.share`, `assign.questionnaire`) rather than inventing a second shape.
 */
export type PracticeOnlyCapability =
  // data plane
  | 'read.careTeam'
  | 'read.supervision'
  | 'read.breakGlass'
  | 'read.scheduleMetadata'
  | 'notes.author'
  | 'keys.manage'
  // control plane
  | 'grant.issue'
  | 'grant.revoke'
  | 'consent.orgTeam'
  | 'member.invite'
  | 'member.logistics'
  | 'member.manage'
  | 'role.assign'
  | 'access.revoke'
  | 'access.killSwitch'
  | 'schedule.manage'
  | 'referral.request'
  | 'care.relationship.manage'
  | 'team.oversee'
  | 'tool.publishValidated'
  | 'server.operate'
  // monitoring plane
  | 'audit.viewOwn'
  | 'audit.review'
  | 'ops.metrics'
  | 'guard.review'

/** The whole vocabulary: the eight shipped grant capabilities plus the clinical layer's additions. */
export type PracticeCapability = Capability | PracticeOnlyCapability

export interface CapabilityFacts {
  readonly plane: Plane
  readonly reads: ClinicalRead
  readonly authorization: Authorization
  readonly friction: Friction
  /**
   * Does EXERCISING this capability create read capability for somebody OTHER than the actor?
   *
   * Distinct from `reads`, and the distinction is the whole reason the field exists. `reads` asks
   * what the holder sees; this asks what the holder can cause SOMEONE ELSE to see. Every value
   * here is 'none' on `reads` and 'control' on `plane` — signing a consent shows the signer
   * nothing — which is precisely why a rule written against read scopes cannot see them: the
   * dangerous act is not a read, it is the minting of one, and it happens in the control plane
   * where admins legitimately live.
   *
   * The rule this makes checkable is § Consent model's first line: "Patient is always the root of
   * consent. Roles decide who may REQUEST access; the patient's grant is what AUTHORIZES it." So
   * the holders of every capability marked true must be exactly the patient, and threePlane.test.ts
   * asserts that over the whole catalog rather than over `grant.issue` by name — because the day
   * somebody hands an org admin `consent.orgTeam` instead, a by-name rule sees nothing at all while
   * an administrator signs a patient's care-team consent for them.
   *
   * Declared per capability, and required rather than optional, for the reason the file header
   * gives about `plane`: a new capability that mints read has to state so in its own diff. An
   * inferred version — "control-plane and step-up priced" — would have to guess, and the guess a
   * future author disagrees with is the one that silently drops a capability out of the rule.
   */
  readonly mintsRead: boolean
  /**
   * The sections of docs/COMPANION_ACCESS_CONTROL.md this entry is transcribed from, so a reader
   * can diff the code against the specification without guessing which paragraph it came from.
   * threePlane.test.ts checks every name here against the document's actual headings, so a
   * renamed section cannot leave a stale citation behind.
   */
  readonly sections: readonly string[]
}

/*
 * The catalog. Grouped by plane, in that order, because the plane is the fact the whole design
 * turns on and reading the file top to bottom should make the shape of it obvious: everything that
 * touches content is in the first block, and nothing in the first block is conferred by a title.
 */
export const CAPABILITIES: Record<PracticeCapability, CapabilityFacts> = {
  /* ── data plane ─────────────────────────────────────────────────────────────────────────────
     Everything here needs a key, and every key comes from the person whose record it opens. The
     `assign.*` / author / suggest capabilities are 'role-and-grant' rather than 'grant-only'
     because they are the actions § Role catalog puts in the clinician rows' "Manages" column
     ("assignments, notes, plans for granted clients") — the role says a clinician may do clinical
     work at all, the grant says with whom. Read is different, and is never in a preset. */

  'read.share': {
    plane: 'data',
    reads: 'others',
    // The shipped Grant capability. It is grant-only in the code that exists today: the owner
    // signs it in GrantManager.svelte and the therapist's client verifies it against a pinned key.
    // Nothing about a therapist's ROLE produces it, which is the property this whole file is about.
    authorization: 'grant-only',
    friction: 'none',
    mintsRead: false,
    sections: ['Consent model', 'The annoyance budget'],
  },
  'read.careTeam': {
    plane: 'data',
    reads: 'others',
    // § Clinician turnover, constraint 1: the care-team key "carries strictly less than a personal
    // grant — assessment summaries, progress notes, game plans. Never journal free text, never
    // process notes." It comes from the patient's org-consent, which is still the patient's
    // signature; "my care team at Practice X" is a grant with a membership-shaped subject, not a
    // role that reads.
    authorization: 'grant-only',
    friction: 'none',
    mintsRead: false,
    sections: ['Consent model', 'Clinician turnover: what a handover actually is'],
  },
  'read.supervision': {
    plane: 'data',
    reads: 'others',
    // § Consent model: "a supervisor's is a separate, explicit grant". This capability exists in
    // the vocabulary precisely so the test can assert the supervisor role does NOT hold it.
    authorization: 'grant-only',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog', 'Consent model'],
  },
  'read.breakGlass': {
    plane: 'data',
    reads: 'others',
    // The one door that is not a grant. Modelled for two reasons, both about forbidding it:
    // § The annoyance budget prices it at "Maximum — justification + loud, immediate
    // notification", and § Clinician turnover states flatly that "a transfer must never route
    // through break-glass". Neither rule is checkable against a capability that does not exist.
    //
    // Honest limit, and it belongs next to the entry rather than in a footnote: this design has NO
    // key escrow (§ Key recovery — "no backdoor"), so there is no key for break-glass to reach.
    // What is modelled here is the policy slot and its price, not a working override.
    authorization: 'break-glass',
    friction: 'maximum',
    mintsRead: false,
    sections: ['The annoyance budget', 'Clinician turnover: what a handover actually is'],
  },
  'read.scheduleMetadata': {
    plane: 'data',
    // Not clinical content: § Role catalog's front-desk row is "scheduling metadata only, no
    // notes". This entry is why `reads` is its own field instead of being read off the plane — a
    // data-plane capability that reads nothing clinical is a real thing, and minimum necessary
    // (§ Consent model) depends on the distinction being sayable.
    reads: 'none',
    // § Consent model: "a front-desk grant is scheduling metadata". A grant, note — the front desk
    // does not get appointment times because of their title either.
    authorization: 'grant-only',
    friction: 'none',
    mintsRead: false,
    sections: ['Consent model'],
  },
  'notes.author': {
    plane: 'data',
    // Writing is not reading. A clinician who authors a note seals it to the patient; reading it
    // back later needs a read capability like anything else. That is a slightly surprising
    // consequence and it is the correct one — "I wrote it, so I may always read it" is exactly the
    // standing-permission shape this design refuses.
    reads: 'none',
    authorization: 'role-and-grant',
    // § The annoyance budget: "Author a note / game plan | None beyond session | Routine clinical
    // work, auditable, reversible".
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog', 'The annoyance budget', 'HIPAA-readiness checklist'],
  },
  'assign.questionnaire': {
    plane: 'data',
    reads: 'none',
    authorization: 'role-and-grant',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog'],
  },
  'assign.task': {
    plane: 'data',
    reads: 'none',
    authorization: 'role-and-grant',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog'],
  },
  'assign.largeAssessment': {
    plane: 'data',
    reads: 'none',
    authorization: 'role-and-grant',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog'],
  },
  'assign.reminder': {
    plane: 'data',
    reads: 'none',
    authorization: 'role-and-grant',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog'],
  },
  'assign.goal': {
    plane: 'data',
    reads: 'none',
    authorization: 'role-and-grant',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog'],
  },
  authorGamePlan: {
    plane: 'data',
    reads: 'none',
    authorization: 'role-and-grant',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog', 'The annoyance budget'],
  },
  'suggest.setting': {
    plane: 'data',
    reads: 'none',
    authorization: 'role-and-grant',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog'],
  },
  'keys.manage': {
    plane: 'data',
    // The only 'own' in the catalog, and the only data-plane capability any role preset holds.
    // § Role catalog's patient row: "their own keys" / "Their own data — root of trust". A patient
    // holding their own key IS their read capability; there is no separate grant to themselves.
    reads: 'own',
    authorization: 'role',
    // § The annoyance budget: "The patient's own friction is capped hardest. A person in a bad
    // moment must never be locked out of their own data by a security measure meant to constrain
    // someone else."
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog', 'Key recovery', 'The annoyance budget'],
  },

  /* ── control plane ──────────────────────────────────────────────────────────────────────────
     The capability graph and its metadata. Nothing here opens content, which is what makes it
     safe for an admin to hold. */

  'grant.issue': {
    plane: 'control',
    reads: 'none',
    // Issuing a grant is a control-plane act that MINTS a data-plane capability for someone else.
    // Only the patient can perform it — see roles.ts; no clinician and no admin holds this.
    authorization: 'role',
    // § The annoyance budget: "Grant, extend, or widen a share | Step-up (MFA) | Creates new read
    // capability — the actual risk".
    friction: 'step-up',
    // The original of the kind. `reads: 'none'` above is true and is exactly why this field had to
    // exist: the holder sees nothing, and somebody else comes to see everything they signed for.
    mintsRead: true,
    sections: ['Role catalog', 'Consent model', 'The annoyance budget'],
  },
  'grant.revoke': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    // "Revoke / kill switch | Deliberately cheap | Never make the safe direction expensive."
    // Pruning someone off a care-team roster is this capability and not a separate one, so the
    // safe direction is priced once, cheaply, wherever it is reached from.
    friction: 'none',
    mintsRead: false,
    sections: ['Revocation', 'Consent model', 'The annoyance budget'],
  },
  'consent.orgTeam': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    // Org-consent authorizes a whole team's worth of future read capability in one signature, so
    // it is priced with grant.issue rather than below it.
    friction: 'step-up',
    // And weighed the same way, which is the point of the flag rather than of the price. § Consent
    // model: a client consents to "my care team at Practice X" and "membership changes issue/revoke
    // grants automatically" — so this one signature is a standing authorisation for people who have
    // not been named yet and will be chosen by somebody else. That is more read capability per
    // signature than grant.issue creates, not less, and it is why a rule that pinned only
    // `grant.issue` to the patient was pinning the smaller of the two doors.
    mintsRead: true,
    sections: ['Consent model', 'Orgs / practices (the tenant)'],
  },
  'member.invite': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    // Priced at 'none' deliberately, and this is a judgement call worth defending. The budget
    // charges step-up for "Add/remove a practice member, change roles", because that "changes who
    // can be granted". An invitation changes nothing until it is accepted and a membership is
    // created — that acceptance is member.manage, and that is where the price sits. Charging both
    // would put friction where the risk is not, which is the failure the section is written about.
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog', 'Orgs / practices (the tenant)', 'The annoyance budget'],
  },
  'member.logistics': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    friction: 'none',
    // § Role catalog gives the front desk "membership logistics", which is the paperwork of
    // someone joining and not the authority to admit them. Two capabilities rather than one, so
    // the front desk can do the work without holding the power — the distinction the org-admin row
    // exists to draw.
    mintsRead: false,
    sections: ['Role catalog'],
  },
  'member.manage': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    friction: 'step-up',
    // § Orgs: "adding a clinician provisions their grants; removing one triggers revocation + key
    // rotation". § Clinician turnover, constraint 3: "Admission requires step-up and is
    // rate-limited — a hijacked session must not be able to add readers quietly."
    mintsRead: false,
    sections: ['Orgs / practices (the tenant)', 'The annoyance budget', 'Clinician turnover: what a handover actually is'],
  },
  'role.assign': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    friction: 'step-up',
    mintsRead: false,
    sections: ['Role catalog', 'Orgs / practices (the tenant)', 'The annoyance budget'],
  },
  'access.revoke': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    // Cheap, and the test asserts it is never dearer than grant.issue. § Revocation is also the
    // place the honest limit lives: this stops FUTURE access and "cannot un-read" what a clinician
    // already decrypted. The durable half is a re-key, which is a different mechanism.
    friction: 'none',
    mintsRead: false,
    sections: ['Revocation', 'The annoyance budget'],
  },
  'access.killSwitch': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    friction: 'none',
    mintsRead: false,
    sections: ['Revocation', 'The annoyance budget', 'Behavioral guard (IDS)'],
  },
  'schedule.manage': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog'],
  },
  'referral.request': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    // § Cross-provider sharing: "any clinician may request that another see a client's data... This
    // is a request, carries no read capability." That is why a referral is control-plane and free:
    // "Referrals are free; reading requires a grant."
    friction: 'none',
    mintsRead: false,
    sections: ['Cross-provider sharing & referrals'],
  },
  'care.relationship.manage': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    // § Clinician turnover: "A referral and a transfer are the same control-plane object at two
    // points in its life, and neither moves a key... Because none of it mints read capability,
    // reassignment stays cheap — session auth and an audit entry, no step-up."
    friction: 'none',
    // False on the specification's own words, quoted directly above, and it is the entry in this
    // file most worth re-reading if the model is ever extended. Recording that somebody is primary,
    // co-treating or covering moves nothing; the key act that puts a new clinician in a position to
    // read is the grant, wherever it is minted. The document does flag the sharp edge next to it —
    // under the default "team may hand over", any current team member can cryptographically admit
    // another, and it names that safeguard as "detective, not preventive" — but the admitting is a
    // key act by a key-holder, not this row. If a future change ever makes moving a care
    // relationship BY ITSELF cause a grant to be issued, this flag turns true and the invariant
    // test takes this capability away from every role but the patient. That is the correct
    // consequence, and it is why the flag is a fact per capability rather than a list somewhere.
    mintsRead: false,
    sections: ['Clinician turnover: what a handover actually is'],
  },
  'team.oversee': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    friction: 'none',
    // § Role catalog gives the supervisor "oversees a team of clinicians" and, in the same row,
    // read "only via explicit, consented grant... never by title". This capability is the whole of
    // what the title confers.
    mintsRead: false,
    sections: ['Role catalog'],
  },
  'tool.publishValidated': {
    plane: 'control',
    reads: 'none',
    authorization: 'role',
    // Not in the budget's table. Priced by its principle instead — "Rare, high-stakes,
    // hard-to-undo actions should be genuinely hard" — because a provenance claim propagates to
    // every reader of the tool and cannot be recalled from the people who already acted on it.
    // instruments/builder.ts already says this gating belongs at the RBAC layer; this is that row.
    friction: 'step-up',
    mintsRead: false,
    sections: ['Role catalog', 'The annoyance budget'],
  },
  'server.operate': {
    plane: 'control',
    // The sysadmin holds ciphertext and no key. § Role catalog: "No — by design. Ciphertext + ops
    // metadata only." That is the difference between storing data and being in the data plane: the
    // plane is defined by who holds keys, not by whose disk the bytes are on.
    reads: 'none',
    authorization: 'role',
    // Priced by principle, with the honest note that it is the one row the app cannot really
    // charge for: whoever has shell on the box is outside anything this software can price.
    friction: 'step-up',
    mintsRead: false,
    sections: ['Role catalog', 'The annoyance budget'],
  },

  /* ── monitoring plane ───────────────────────────────────────────────────────────────────────
     § The three planes: "the hash-chained audit log + the behavioral guard. Metadata only."
     This is the other half of the sentence the whole design is judged on — an admin CAN see
     who-accessed-what — so these capabilities exist to be held, not to be forbidden. */

  'audit.viewOwn': {
    plane: 'monitoring',
    reads: 'none',
    authorization: 'role',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog', 'The annoyance budget'],
  },
  'audit.review': {
    plane: 'monitoring',
    reads: 'none',
    authorization: 'role',
    friction: 'none',
    // § HIPAA-readiness: "the hash-chained, metadata-only audit log (shipped); extend to org-level
    // review". The org admin's half of the claim.
    mintsRead: false,
    sections: ['Role catalog', 'HIPAA-readiness checklist'],
  },
  'ops.metrics': {
    plane: 'monitoring',
    reads: 'none',
    authorization: 'role',
    friction: 'none',
    mintsRead: false,
    sections: ['Role catalog'],
  },
  'guard.review': {
    plane: 'monitoring',
    reads: 'none',
    authorization: 'role',
    friction: 'none',
    // § Behavioral guard: signals are behavioral, the response is "step up, don't hard-lock", and
    // one of the responses is "freeze pending admin review" — which is the admin this row is for.
    // "Restraint: log the minimum" is why there is no capability here for a rich behavioral store.
    mintsRead: false,
    sections: ['Behavioral guard (IDS)'],
  },
}

/**
 * Every capability id, in catalog order (data, then control, then monitoring).
 *
 * Derived from the record rather than written out a second time: a hand-maintained parallel list
 * is a drift source, and the one thing this module cannot afford is two disagreeing answers to
 * "what capabilities are there".
 */
export const ALL_PRACTICE_CAPABILITIES = Object.keys(CAPABILITIES) as PracticeCapability[]

export function factsOf(cap: PracticeCapability): CapabilityFacts {
  return CAPABILITIES[cap]
}

/** True when exercising this puts ANOTHER person's clinical content in front of a human. */
export function readsOthersClinical(cap: PracticeCapability): boolean {
  return CAPABILITIES[cap].reads === 'others'
}

/** True when exercising this puts ANY clinical content in front of a human, including one's own. */
export function readsAnyClinical(cap: PracticeCapability): boolean {
  return CAPABILITIES[cap].reads !== 'none'
}

/**
 * The read scopes. This is the set the invariant test filters role presets against, so it is
 * asserted non-empty there before anything is filtered — a detector that finds nothing reports
 * success on every input.
 */
export const READ_CLINICAL_CAPABILITIES: PracticeCapability[] =
  ALL_PRACTICE_CAPABILITIES.filter(readsOthersClinical)

/** True when exercising this creates read capability for somebody other than the actor. */
export function mintsReadForOthers(cap: PracticeCapability): boolean {
  return CAPABILITIES[cap].mintsRead
}

/**
 * The capabilities whose exercise authorizes somebody else's future reading.
 *
 * The set the invariant test pins to the patient, and the answer to "who may put a reader in front
 * of this record?" for any surface that has to explain the model to a person. Asserted non-empty
 * and asserted to contain more than one member before it is used to filter anything — a rule
 * quantified over an accidentally-empty set is a rule that passes on every catalog.
 */
export const READ_MINTING_CAPABILITIES: PracticeCapability[] =
  ALL_PRACTICE_CAPABILITIES.filter(mintsReadForOthers)

/**
 * Capabilities no role may confer: they come only from a patient's signed grant, or (break-glass)
 * from an emergency door no title opens.
 */
export const PATIENT_GRANT_ONLY_CAPABILITIES: PracticeCapability[] = ALL_PRACTICE_CAPABILITIES.filter(
  (c) => CAPABILITIES[c].authorization === 'grant-only' || CAPABILITIES[c].authorization === 'break-glass',
)

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   4. Copy.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * Titles and descriptions for the capabilities the clinical layer adds.
 *
 * The shipped eight are NOT here: describePracticeCapability delegates to
 * assignments/describe.ts for those, so the string a person reads next to `read.share` in the
 * practice UI is the same string they read in GrantManager.svelte. Copying it would let the two
 * surfaces drift into describing the same permission differently, which is worse than either
 * wording alone.
 *
 * Style is inherited from that file: mechanical, non-diagnostic, and honest about the limit rather
 * than reassuring. Where a capability sounds broader than it is, the description says what it is
 * NOT — because "read the care team's copy" and "read everything" are the same sentence to someone
 * skimming a permissions list.
 */
export const PRACTICE_CAPABILITY_COPY: Record<PracticeOnlyCapability, { title: string; desc: string }> = {
  'read.careTeam': {
    title: 'Read as the care team',
    desc:
      'Read what the care-team key covers for a client who consented to a practice care team: ' +
      'assessment summaries, progress notes, game plans. Never journal free text, never process notes.',
  },
  'read.supervision': {
    title: 'Read for clinical supervision',
    desc:
      'Read a client’s clinical content for supervision. A separate grant the client signs — ' +
      'holding a supervisor title never produces it.',
  },
  'read.breakGlass': {
    title: 'Break-glass emergency read',
    desc:
      'Emergency access outside the normal grant path: a written justification, and the client is ' +
      'told immediately and loudly. No role carries this, and a handover must never use it.',
  },
  'read.scheduleMetadata': {
    title: 'Read scheduling metadata',
    desc: 'Appointment times and attendance for a client who granted it. No notes, no answers, no entries.',
  },
  'notes.author': {
    title: 'Write clinical notes',
    desc:
      'Write and amend clinical notes for a client who granted it. Notes are added to and amended, ' +
      'never rewritten in place.',
  },
  'keys.manage': {
    title: 'Hold and rotate your own keys',
    desc:
      'Hold your own encryption keys, keep recovery codes, and rotate the key that opens your data. ' +
      'The server never holds a recovery secret, so you can recover and it still cannot read.',
  },
  'grant.issue': {
    title: 'Grant, extend or widen a share',
    desc: 'Authorise a named person to read part of your record, or widen what they already hold.',
  },
  'grant.revoke': {
    title: 'Revoke or narrow a share',
    desc:
      'Withdraw a grant, narrow it, or prune someone off your care team. It stops future access; ' +
      'it cannot un-read what somebody already opened.',
  },
  'consent.orgTeam': {
    title: 'Consent to a practice care team',
    desc:
      'Consent to “my care team at Practice X”, so the practice manages who is on it. Revocable, ' +
      'and the team is a roster you can see and prune at any time.',
  },
  'member.invite': {
    title: 'Invite someone to the practice',
    desc: 'Send an invitation to join the practice. An invitation confers nothing until it is accepted.',
  },
  'member.logistics': {
    title: 'Handle membership paperwork',
    desc:
      'The logistics of someone joining or leaving: forms, onboarding times, chasing an unaccepted ' +
      'invitation. No authority to add, remove or re-role anyone.',
  },
  'member.manage': {
    title: 'Add and remove practice members',
    desc:
      'Add a member to the practice or remove one. Removing triggers revocation and a re-key, and ' +
      'adding one to a care team tells the affected clients.',
  },
  'role.assign': {
    title: 'Set a member’s role',
    desc: 'Change which role a practice member holds. It changes who can be granted; it grants nothing.',
  },
  'access.revoke': {
    title: 'Revoke a member’s access',
    desc: 'Stop the server serving a member’s grants, at once and for the future.',
  },
  'access.killSwitch': {
    title: 'Freeze access at once',
    desc: 'Freeze an account, or one clinician’s access across the whole practice, in a single action.',
  },
  'schedule.manage': {
    title: 'Manage the calendar',
    desc: 'Book, move and cancel appointments. Times and attendance only.',
  },
  'referral.request': {
    title: 'Ask another clinician to look',
    desc:
      'Ask that another clinician be put on a client’s team. A request only — it carries no read ' +
      'capability, and the client decides.',
  },
  'care.relationship.manage': {
    title: 'Record a care relationship',
    desc:
      'Record who is primary, co-treating, covering or supervising for a client, and end one on ' +
      'departure. Covering ends on a fixed date. It moves no key.',
  },
  'team.oversee': {
    title: 'Oversee a team of clinicians',
    desc:
      'Supervise a team: who is on it, and what is outstanding. Reading a client’s content needs ' +
      'that client’s separate supervision grant.',
  },
  'tool.publishValidated': {
    title: 'Publish a Validated or Adapted tool',
    desc: 'Publish a tool under a Validated or Adapted provenance claim, which every reader of it will see.',
  },
  'server.operate': {
    title: 'Operate the server',
    desc:
      'Run the server and its storage. Ciphertext and operational metadata only — there is no key ' +
      'here to open content with.',
  },
  'audit.viewOwn': {
    title: 'Read your own access log',
    desc: 'Every open of your own record: who, what, and when. Metadata only.',
  },
  'audit.review': {
    title: 'Review the practice access log',
    desc:
      'Review the hash-chained access log for the practice — who opened what, and when. The log ' +
      'carries no content, so reviewing it shows none.',
  },
  'ops.metrics': {
    title: 'Read operational metadata',
    desc:
      'Liveness, storage writability, request rates. No client content, and no record of who shared ' +
      'what with whom.',
  },
  'guard.review': {
    title: 'Review behavioural guard signals',
    desc:
      'Review what the guard flagged and pause a token pending review. Behaviour, not content, and ' +
      'kept only briefly.',
  },
}

/** True for the eight capabilities that already existed before the clinical layer. */
export function isGrantVocabularyCapability(cap: PracticeCapability): cap is Capability {
  return (ALL_CAPABILITIES as readonly string[]).includes(cap)
}

/**
 * Title + description for any capability in the vocabulary, old or new.
 *
 * The delegation is the point: one capability, one wording, whichever surface renders it.
 */
export function describePracticeCapability(cap: PracticeCapability): { title: string; desc: string } {
  return isGrantVocabularyCapability(cap)
    ? describeCapability(cap)
    : PRACTICE_CAPABILITY_COPY[cap as PracticeOnlyCapability]
}
