package com.daymark.app.data

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `sky_constellations` (`DECISIONS.md` §D11), checked the way `LifeEventSchemaTest` checks
 * `life_events`: the migration's SQL against the entity, and the table's registration. The chain of
 * migrations and their registration as a whole are `LifeEventSchemaTest`'s; once the exported v22
 * schema is committed, `MigrationSchemaExportTest` compares this migration with Room's own words.
 */
class ConstellationSchemaTest {

    private companion object {
        const val ENTITY = "app/src/main/java/com/daymark/app/data/entity/Constellation.kt"
        const val DATABASE = "app/src/main/java/com/daymark/app/data/AppDatabase.kt"
        const val MODULE = "app/src/main/java/com/daymark/app/di/AppModule.kt"
    }

    private val entitySource = strip(repoFile(ENTITY).readText())
    private val databaseSource = strip(repoFile(DATABASE).readText())
    private val moduleSource = strip(repoFile(MODULE).readText())

    @Test
    fun `the migration creates exactly the table the entity declares`() {
        val fromEntity = renderedFromEntity(entitySource)
        assertEquals(
            listOf(
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
                "`name` TEXT NOT NULL",
                "`madeEpochDay` INTEGER NOT NULL",
                "`points` TEXT NOT NULL",
                "`createdAt` INTEGER NOT NULL",
            ),
            fromEntity,
        )
        assertEquals(fromEntity, sqlColumns(migrationSql(databaseSource)))
        assertTrue(migrationSql(databaseSource).startsWith("CREATE TABLE IF NOT EXISTS `sky_constellations` ("))
        // The detector: a dropped or reordered column is a difference this comparison sees.
        assertNotEquals(fromEntity - "`createdAt` INTEGER NOT NULL", sqlColumns(migrationSql(databaseSource)))
        assertNotEquals(listOf(fromEntity[1], fromEntity[0]) + fromEntity.drop(2), fromEntity)
    }

    @Test
    fun `the table is registered, reachable and migrated to`() {
        assertTrue(databaseSource.contains("com.daymark.app.data.entity.Constellation::class"))
        assertTrue(databaseSource.contains("fun constellationDao()"))
        assertTrue(moduleSource.contains("fun provideConstellationDao("))
        assertTrue(moduleSource.contains("AppDatabase.MIGRATION_21_22,"))
        assertTrue(databaseSource.contains("version = 22,"))
    }

    private fun strip(source: String): String = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    private fun renderedFromEntity(source: String): List<String> {
        val params = source.substringAfter("data class Constellation(").substringBefore("\n)")
        return Regex("""(@PrimaryKey\(autoGenerate = true\) )?val (\w+): (\w+)(\??)""")
            .findAll(params)
            .map { m ->
                val (pk, name, type, nullable) = m.destructured
                val affinity = when (type) {
                    "Long", "Int", "Boolean" -> "INTEGER"
                    "String" -> "TEXT"
                    else -> throw AssertionError("unhandled column type $type on $name")
                }
                val notNull = if (nullable.isEmpty()) " NOT NULL" else ""
                if (pk.isNotEmpty()) "`$name` $affinity PRIMARY KEY AUTOINCREMENT$notNull" else "`$name` $affinity$notNull"
            }
            .toList()
    }

    private fun migrationSql(source: String): String =
        Regex("\"([^\"]*)\"")
            .findAll(
                source.substringAfter("val MIGRATION_21_22")
                    .substringBefore("val DEFAULT_ACTIVITIES")
                    .substringBefore("val MIGRATION_"),
            )
            .joinToString("") { it.groupValues[1] }

    private fun sqlColumns(sql: String): List<String> =
        sql.substringAfter("(").substringBeforeLast(")").split(", ").map { it.trim() }
}
