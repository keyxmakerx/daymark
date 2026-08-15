package com.daymark.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.daymark.app.data.entity.OfferRecord
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the reception ledger ([OfferRecord]).
 *
 * There is no `@Update` and no `@Delete` by row: a ledger line is a fact about a moment that
 * happened, so it is written once and never edited. The only removals are the person's own —
 * [deleteAll], and [deleteOlderThan] for keeping the table from growing without bound.
 *
 * Every read here is deliberately narrow. Callers ask "when did I last ask?" or "how did the last
 * few land?" — the questions a permission gate needs — and nothing wider. Aggregates that would
 * amount to scoring the person (consecutive runs, rates over time, ratios framed as engagement)
 * do not belong in this file.
 */
@Dao
interface OfferRecordDao {
    @Query("SELECT * FROM offer_records ORDER BY offeredAt DESC")
    fun observeAll(): Flow<List<OfferRecord>>

    /** The most recent offer of a kind, or null if that feature has never asked. */
    @Query("SELECT * FROM offer_records WHERE kind = :kind ORDER BY offeredAt DESC LIMIT 1")
    suspend fun latestForKind(kind: String): OfferRecord?

    /** The last [limit] offers of a kind, newest first — what `lastOfferOutcome` reads. */
    @Query("SELECT * FROM offer_records WHERE kind = :kind ORDER BY offeredAt DESC LIMIT :limit")
    suspend fun recentForKind(kind: String, limit: Int): List<OfferRecord>

    /**
     * When this kind last asked, or 0 if never — the `lastOfferedAt` argument
     * `SupportOffer.shouldInterrupt` already takes.
     */
    @Query("SELECT COALESCE(MAX(offeredAt), 0) FROM offer_records WHERE kind = :kind")
    suspend fun lastOfferedAt(kind: String): Long

    /**
     * Whether an outcome has ever been recorded for a kind. Used for the standing "stop asking"
     * preference, which holds until the person lifts it.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM offer_records WHERE kind = :kind AND outcome = :outcome)")
    suspend fun hasOutcome(kind: String, outcome: String): Boolean

    /**
     * How many times an outcome came back for a kind since [since]. The one aggregate the engine
     * needs, and it may only ever be used to ask less.
     */
    @Query(
        "SELECT COUNT(*) FROM offer_records " +
            "WHERE kind = :kind AND outcome = :outcome AND offeredAt >= :since",
    )
    suspend fun countSince(kind: String, outcome: String, since: Long): Int

    @Insert
    suspend fun insert(record: OfferRecord): Long

    @Query("SELECT * FROM offer_records")
    suspend fun getAll(): List<OfferRecord>

    /** Prunes old lines. The ledger is working state, not history worth keeping indefinitely. */
    @Query("DELETE FROM offer_records WHERE offeredAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM offer_records")
    suspend fun deleteAll()
}
