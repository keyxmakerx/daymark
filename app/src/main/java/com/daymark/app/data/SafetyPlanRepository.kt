package com.daymark.app.data

import com.daymark.app.data.dao.SafetyPlanDao
import com.daymark.app.data.entity.SafetyPlanItem
import com.daymark.app.data.entity.SafetyPlanSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The owner's safety plan. Local-only: nothing here syncs, and nothing reads it but the person who
 * wrote it. See `docs/SAFETY_PLAN_FEATURE_PLAN.md` for why sharing, if it ever ships, has to be an
 * owner-granted, time-boxed share and never an automatic one.
 */
@Singleton
class SafetyPlanRepository @Inject constructor(
    private val dao: SafetyPlanDao,
) {
    /** Every item, grouped by section and in the order the person added them. */
    fun observePlan(): Flow<Map<SafetyPlanSection, List<SafetyPlanItem>>> =
        dao.observeAll().map { items ->
            items.groupBy { SafetyPlanSection.fromKey(it.section) }
                .mapNotNull { (section, rows) -> section?.let { it to rows } }
                .toMap()
        }

    /**
     * Appends a line. Blank text is ignored rather than stored — an empty chip is never something
     * the person meant to add. Returns the new row id, or `null` if nothing was added.
     */
    suspend fun add(section: SafetyPlanSection, text: String, detail: String = ""): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return dao.insert(
            SafetyPlanItem(
                section = section.key,
                position = dao.nextPosition(section.key),
                text = trimmed,
                detail = if (section.hasDetail) detail.trim() else "",
            ),
        )
    }

    suspend fun update(item: SafetyPlanItem) = dao.update(item)

    suspend fun delete(item: SafetyPlanItem) = dao.delete(item)
}
