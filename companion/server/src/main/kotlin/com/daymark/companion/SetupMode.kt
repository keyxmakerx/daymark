package com.daymark.companion

/**
 * The server's shape (#330): the Companion is one product in three shapes (#288), and each shape
 * switches on only what it needs. `DAYMARK_SETUP_MODE` names one; when none is named,
 * `DAYMARK_THERAPIST_AUTH` decides, as it did before the setting existed ([Config.shape]).
 *
 * | Shape | Route groups on | Pages served |
 * | --- | --- | --- |
 * | [SOLO] | the probes, `/v1/config`, sync, the owner's notifications and recovery | the owner's page, the server console |
 * | [PAIRED] | solo's, and the clinician group | solo's, and the clinician's page with `/therapist` and `/portal/invite` |
 * | [PRACTICE] | paired's, and the practice group | paired's, and the practice page |
 *
 * A group that is off answers every one of its routes with the one answer the whole portal gave with
 * `DAYMARK_THERAPIST_AUTH` off, and a page that is off is refused at every spelling of its path
 * (Application.kt). The shape is not a secret: anyone who can reach the server can read it from the
 * routes, and `/v1/config` publishes it when the operator chose it.
 */
enum class SetupMode(
    /**
     * The setting's value, and what `/v1/config` publishes as `setupMode`: exactly the ids the
     * first-run screen reads (`companion/web/src/lib/setup/shape.ts`).
     */
    val wire: String,
    /** What the shape serves beyond the probes and the server console, for the one line a start logs. */
    val serves: String,
) {
    SOLO("solo", "sync and the owner's page"),
    PAIRED("paired", "sync, the owner's page, and the clinician routes and page"),
    PRACTICE("practice", "sync, the owner's page, and the clinician and practice routes and pages"),
    ;

    /**
     * The clinician group, and the clinician's page with it: invitations, pairing, clinician sign-in,
     * relationships, and what is kept per relationship — keys, endings, the owner's access log and
     * its chain check.
     */
    val clinicianGroup: Boolean get() = this != SOLO

    /** The practice group (routes/OrgRoutes.kt), and the practice page with it. */
    val practiceGroup: Boolean get() = this == PRACTICE

    companion object {
        /**
         * The shape [raw] names, read case-insensitively after trimming, or null when it names none of
         * the three. There are no aliases: a value the server accepted loosely today would be a
         * spelling an operator relies on tomorrow.
         */
        fun parse(raw: String): SetupMode? {
            val wanted = raw.trim().lowercase()
            return entries.firstOrNull { it.wire == wanted }
        }
    }
}
