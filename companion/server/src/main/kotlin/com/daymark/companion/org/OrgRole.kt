package com.daymark.companion.org

/**
 * Which of the three planes an action lives in (docs/COMPANION_ACCESS_CONTROL.md, "The three
 * planes").
 *
 * [DATA] is declared here even though **nothing in this package is allowed to be in it**, and that
 * is the entire reason it exists. A rule stated only in prose — "admins live in the control and
 * monitoring planes, never the data plane" — is a rule that survives exactly as long as the next
 * person to add an enum entry remembers reading it. Declaring the forbidden plane makes the
 * absence something a test can assert about *every* action, including ones written years from now:
 * `OrgAction.entries.none { it.plane == Plane.DATA }` is a statement with content only because
 * `DATA` is a value the compiler would have accepted.
 *
 * Delete `DATA` and the assertion becomes a tautology that passes for the wrong reason. It is load
 * bearing precisely by being unused.
 */
enum class Plane {
    /** Ciphertext. Patients hold keys, clinicians hold patient-authorised grants. Off limits here. */
    DATA,

    /** The capability graph and its metadata: who may do what, membership, roles, revocation. */
    CONTROL,

    /** The hash-chained audit log. Metadata only, and read-only to whoever may review it. */
    MONITORING,
}

/**
 * The catalog of things a practice member may *do*, and nothing they may *read*.
 *
 * ## What is deliberately not in this enum
 *
 * There is no `READ_NOTES`, no `READ_ASSESSMENTS`, no `OPEN_SHARE`, no `LIST_PATIENTS` — no value
 * of any kind whose exercise would return clinical content or the material that opens it. That is
 * not an oversight to be filled in by a later commit; it is the design:
 *
 *   "Roles gate actions (server-enforced). Read capability is separate and comes only from a
 *    patient grant."
 *
 * Read capability in this system is a *grant*: a key the patient wrapped for a specific person,
 * minted on the patient's own device, delivered as ciphertext this server cannot open. It is not a
 * flag, it is not a row, and there is no arrangement of rows in the control plane that adds up to
 * one. So a role cannot carry read access for the same reason a library card cannot carry a book:
 * the two are different kinds of object, and the control plane only ever handles the card.
 *
 * The practical consequence, which is the point of the whole exercise: an org admin can add anyone,
 * remove anyone, change any role and read every line of the practice's audit history, and still
 * cannot decrypt one sentence a patient wrote. Not "is not permitted to" — *cannot*, because
 * nothing they can reach ever held a key.
 *
 * ## Why every entry names its plane
 *
 * So that the three-plane rule is checked rather than remembered. See [Plane].
 */
enum class OrgAction(val plane: Plane) {
    /**
     * See who is in the practice and what role they hold.
     *
     * Every member has it, including the front desk, because a practice whose members cannot tell
     * who works there is not a practice. What it returns is membership metadata — the thing the
     * spec says a multi-tenant-but-blind server legitimately sees — and deliberately NOT a list of
     * the practice's patients. There is no such list to return: a patient is not owned by the org,
     * so "the org's patients" is not a set this server is entitled to assemble, and a roster route
     * that quietly grew one would have turned an addressing convenience into a registry of who is
     * in therapy where.
     */
    VIEW_ROSTER(Plane.CONTROL),

    /**
     * Add a member, remove a member, change a member's role — for one practice.
     *
     * The whole of what an org admin's authority amounts to, and worth reading twice for what it
     * does not include. Adding a clinician to a practice changes who may be *offered* a grant. It
     * does not create one, it does not ask the patient for one on their behalf, and it does not
     * cause the server to hand the new member anything it is holding. The patient remains the root
     * of consent and the practice remains, in the spec's words, a membership and addressing
     * convenience.
     */
    MANAGE_MEMBERSHIP(Plane.CONTROL),

    /**
     * The front desk's remit: scheduling metadata, and the logistics around it.
     *
     * Named for the boundary rather than for the feature, because the boundary is the point. The
     * role catalog gives front desk "scheduling, invites, membership logistics" and then says, in
     * the only column that matters, **no** clinical content — scheduling metadata only. This
     * capability is that sentence expressed as a value: it is what a front-desk member holds
     * *instead of* anything clinical, not in addition to it.
     *
     * The scheduling surface itself is not part of this slice, so today nothing consumes this. It
     * is declared anyway, because the alternative is a front-desk role whose capability set is
     * identical to a clinician's, which would read as "these roles are the same" to the next person
     * and is exactly the collapse the catalog exists to prevent.
     */
    MANAGE_SCHEDULING_METADATA(Plane.CONTROL),

    /**
     * Read the practice's own control-plane history: who was added, removed, promoted, refused.
     *
     * Monitoring, not control — it changes nothing — and monitoring, not data: the entries are
     * events, never content, and the log this reads is a different database from the one that
     * carries a patient's relationship history, so an org admin reviewing their practice cannot
     * page sideways into a patient's own audit trail.
     */
    REVIEW_ORG_AUDIT(Plane.MONITORING),
}

/**
 * The role catalog from docs/COMPANION_ACCESS_CONTROL.md, as the set of roles a **practice member**
 * may hold.
 *
 * ## Two roles from the catalog table are missing, on purpose
 *
 * **Patient / owner.** The catalog lists them first, and they are still not here, because a patient
 * is not a member of a practice. "A patient/client is *not* owned by the org. They own their keys;
 * the org is a membership/addressing convenience." If `PATIENT` were assignable then an org admin
 * could add a person to their practice as a patient — which is to say, an administrator could
 * assert a clinical relationship that the patient never consented to, from the control plane, with
 * one row. The refusal has to be structural: there is no value here to write into the column, so
 * there is no request body that expresses it and no code path that could be talked into it.
 *
 * **Platform sysadmin.** They run the server and the infrastructure. They are not in anybody's
 * practice, so making them a member of one would be a lie about where their authority comes from,
 * and it would quietly create the god admin the design exists to rule out — someone who is inside
 * a tenant *and* outside every tenant at once.
 *
 * ## Why a role is a set of actions and never a key
 *
 * Each entry below declares the actions it may perform. There is no second field, no "reads"
 * column, no grant reference and no place to put one — deliberately, since a role that could name
 * a key would be a role that carries one, and the moment a role carries a key, membership grants
 * read and the whole separation collapses. The supervisor is the sharp case and the spec calls it
 * out by name: a supervisor oversees a team of clinicians and reads their clients' material **only
 * via an explicit, consented grant, never by title**. So [SUPERVISOR] below holds exactly what
 * [CLINICIAN] holds, which looks redundant and is not — it is the assertion that seniority is not
 * access, written where a future reader will trip over it.
 */
enum class OrgRole(val wire: String, val capabilities: Set<OrgAction>) {
    /** Psychologist / clinician. Reads for the clients who granted them — through the grant, never through this. */
    CLINICIAN("clinician", setOf(OrgAction.VIEW_ROSTER)),

    /** Psychiatrist. Same control-plane standing as a clinician; the differences are clinical, and clinical is not here. */
    PSYCHIATRIST("psychiatrist", setOf(OrgAction.VIEW_ROSTER)),

    /**
     * Supports a clinician's work, on a narrower grant than the clinician's.
     *
     * "Narrowed" is a property of the grant the patient minted, decided on the patient's device
     * under minimum-necessary. It is not something this enum can express, tighten or widen, which
     * is why the assistant's control-plane capabilities are the same as everyone else's.
     */
    THERAPIST_ASSISTANT("therapist_assistant", setOf(OrgAction.VIEW_ROSTER)),

    /** Scheduling, invites, membership logistics. No clinical content — scheduling metadata only. */
    FRONT_DESK("front_desk", setOf(OrgAction.VIEW_ROSTER, OrgAction.MANAGE_SCHEDULING_METADATA)),

    /**
     * Oversees a team of clinicians, and reads nothing by virtue of doing so.
     *
     * Identical to [CLINICIAN] here. If that ever stops being true, the change has almost certainly
     * been made in the wrong file: clinical supervision is a separate, explicit, consented grant,
     * and it is issued by the patient, not conferred by this table.
     */
    SUPERVISOR("supervisor", setOf(OrgAction.VIEW_ROSTER)),

    /**
     * Practice membership, roles, revocation, audit review — **for this practice only**.
     *
     * Not a global super-admin: authority is per-membership, so "org admin" is never a property of
     * a person, only of a person *in a practice*. Someone who administers two practices holds two
     * rows and is checked against the one named in the request. Someone who administers one holds
     * one, and every other practice on this server answers them the way it answers a stranger.
     */
    ORG_ADMIN("org_admin", setOf(OrgAction.VIEW_ROSTER, OrgAction.MANAGE_MEMBERSHIP, OrgAction.REVIEW_ORG_AUDIT)),
    ;

    fun can(action: OrgAction): Boolean = action in capabilities

    companion object {
        /** Parse a wire value, or null. Unknown roles are refused rather than defaulted — fail closed. */
        fun fromWire(s: String): OrgRole? = entries.firstOrNull { it.wire == s }
    }
}
