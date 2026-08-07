package com.daymark.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line of the owner's safety plan — a plan written while things are steady, so that a harder
 * moment doesn't have to start from a blank page.
 *
 * There is deliberately **no parent row**: the plan *is* the set of items, and an empty table
 * means "no plan yet" rather than "an empty plan". [detail] is `""` except on `people` rows, where
 * it holds the relationship ("sister", "therapist").
 *
 * Each item is its own row rather than a CSV column. `ThoughtRecord.distortions` stores a list as
 * CSV, but those are a fixed catalog of our own comma-free keys; safety-plan items are free text a
 * person may easily write a comma into ("Call Sam, then walk"), which `split(",")` would silently
 * shred. A child table also gives ordering and the two-field people rows for free.
 */
@Entity(tableName = "safety_plan_items", indices = [Index("section")])
data class SafetyPlanItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val section: String,
    val position: Int,
    val text: String,
    val detail: String = "",
)

/**
 * The sections of the plan, with our own wording.
 *
 * This is **not** the Stanley-Brown Safety Planning Intervention form, which is © Stanley & Brown
 * and requires written permission to program into an electronic record. These are our own titles
 * and prompts, informed by the general *method* of safety planning — which is why the tool is
 * labelled **Adapted**, never Validated. See `docs/SAFETY_PLAN_FEATURE_PLAN.md`.
 *
 * There is deliberately **no means-restriction section**. Stanley-Brown includes making the
 * environment safe; we omit it, independently required by `PROVENANCE.md` rule 4 (no self-harm
 * item slot in any tier's shareable output). A person may of course write whatever they like in
 * their own words — we simply never prompt for it.
 */
enum class SafetyPlanSection(
    val key: String,
    val title: String,
    val prompt: String,
    /** Placeholder on the inline "add one more" field. */
    val addHint: String,
    /** Optional sections stay absent until filled, rather than sitting empty as a reproach. */
    val optional: Boolean = false,
    /** People rows carry a second field; every other section is a single line of text. */
    val hasDetail: Boolean = false,
) {
    WARNING_SIGNS(
        key = "warning_signs",
        title = "Warning signs I notice",
        prompt = "What it looks like when things start to slip",
        addHint = "Add a warning sign…",
    ),
    THINGS_THAT_HELP(
        key = "things_that_help",
        title = "Things that help",
        prompt = "What has actually worked before",
        addHint = "Add something that helps…",
    ),
    PEOPLE(
        key = "people",
        title = "People I can reach",
        prompt = "Who you'd be glad to hear from",
        addHint = "Add someone…",
        hasDetail = true,
    ),
    REASONS(
        key = "reasons",
        title = "Reasons I want to stay",
        prompt = "Anything worth staying for — however small",
        addHint = "Add a reason…",
        optional = true,
    ),
    ;

    companion object {
        /** Unknown keys (a newer backup, a hand-edited file) are skipped rather than crashing. */
        fun fromKey(key: String): SafetyPlanSection? = entries.firstOrNull { it.key == key }

        /** The three sections offered up front; [REASONS] is opt-in. */
        val required: List<SafetyPlanSection> = entries.filterNot { it.optional }
    }
}
