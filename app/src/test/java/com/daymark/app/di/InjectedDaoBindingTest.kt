package com.daymark.app.di

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every DAO taken as a constructor parameter has an `@Provides` method in [AppModule].
 *
 * ## Why this is worth a test rather than a habit
 *
 * Dagger already checks it — that is the point. It checks it during annotation processing, inside
 * `:app:hiltJavaCompileFossDebug`, which is three minutes into a CI run and cannot be reproduced on
 * a machine without the full Android toolchain. So the feedback for forgetting a binding is a red
 * build a long way from the edit, and the message names a generated Java file
 * (`DaymarkApp_HiltComponents.java:185`) rather than the line anyone wrote.
 *
 * This test is the same question asked in the unit-test task, which runs first and runs anywhere.
 *
 * ## The bug it was written for
 *
 * `SkyRepository` was the first class to take `GoalStepDao` as a **constructor** parameter.
 * `GoalRepository` had been reaching the same DAO through `database.goalStepDao()` in its class
 * body — an ordinary method call on an injected database, which needs no binding at all. So the
 * graph had been complete *by accident*: the DAO was in use, its accessor existed on
 * `AppDatabase`, and nothing anywhere had ever needed `AppModule` to provide it. Adding one
 * constructor parameter turned an absence nobody could see into a compile error.
 *
 * That shape recurs: the next DAO to move from a class body into a constructor will do the same
 * thing, and the person moving it will have no reason to think about Hilt.
 *
 * ## Non-vacuity
 *
 * A scan that finds no injection sites would pass this trivially, so the counts are asserted before
 * the property is: [theScanFindsInjectionSitesAndProviders] fails if either list comes back empty
 * or implausibly short, and the DAO names are cross-checked against the files that actually exist
 * in `data/dao/` so a regex that matched a suffix (`LifeEventDao` read as `EventDao`) cannot
 * quietly inflate either side.
 */
class InjectedDaoBindingTest {

    private companion object {
        const val MODULE = "app/src/main/java/com/daymark/app/di/AppModule.kt"
        const val A_DAO = "app/src/main/java/com/daymark/app/data/dao/GoalDao.kt"
    }

    // Both roots are derived from a file rather than named as a directory, because `repoFile`
    // resolves files only — it walks up looking for `isFile`, so handing it a directory throws.

    /** The DAO type names that actually exist, which is what both scans are filtered against. */
    private val realDaos: Set<String> by lazy {
        repoFile(A_DAO).parentFile.listFiles().orEmpty()
            .filter { it.name.endsWith(".kt") }
            .map { it.name.removeSuffix(".kt") }
            .toSet()
    }

    private fun mainSources(): List<File> {
        val root = generateSequence(repoFile(MODULE)) { it.parentFile }.first { it.name == "java" }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * DAO types appearing as a constructor parameter anywhere in main.
     *
     * Matched on `: <Type>` with an optional package prefix, then filtered against [realDaos] —
     * the filter is what stops `com.daymark.app.data.dao.LifeEventDao` from also being read as a
     * parameter of type `EventDao`, which is exactly what a hand-run of this scan did before the
     * filter was added.
     */
    private fun injectedDaos(): Set<String> {
        val param = Regex(""":\s*(?:[a-zA-Z_][a-zA-Z0-9_]*\.)*([A-Za-z0-9_]+Dao)\b""")
        val injected = mutableSetOf<String>()
        for (file in mainSources()) {
            if (file.path.endsWith("AppModule.kt")) continue
            if (file.path.contains("/data/dao/")) continue
            if (file.path.endsWith("AppDatabase.kt")) continue
            val text = file.readText()
            // Only the constructor's parameter list: everything between `@Inject constructor(` and
            // the matching `)`. A DAO named in a method body or a KDoc is not a binding request.
            for (match in Regex("""@Inject\s+constructor\s*\(""").findAll(text)) {
                val open = match.range.last
                val close = matchingParen(text, open)
                if (close < 0) continue
                for (p in param.findAll(text.substring(open, close))) {
                    val name = p.groupValues[1]
                    if (name in realDaos) injected += name
                }
            }
        }
        return injected
    }

    /** DAO types returned from an `@Provides` function in [AppModule]. */
    private fun providedDaos(): Set<String> {
        val text = repoFile(MODULE).readText()
        val returns = Regex("""fun\s+\w+\([^)]*\)\s*:\s*(?:[a-zA-Z_][a-zA-Z0-9_]*\.)*([A-Za-z0-9_]+Dao)\b""")
        return returns.findAll(text).map { it.groupValues[1] }.filter { it in realDaos }.toSet()
    }

    /** Index of the `)` closing the `(` at [open], or -1. */
    private fun matchingParen(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    @Test
    fun theScanFindsInjectionSitesAndProviders() {
        assertTrue("no DAO files found — the scan is looking in the wrong place", realDaos.size >= 10)
        val injected = injectedDaos()
        val provided = providedDaos()
        // Plain lower bounds. Exact counts would make this a second thing to update on every DAO
        // added, which is the maintenance failure the guards in this repo keep dying of.
        assertTrue("found no constructor-injected DAOs at all: $injected", injected.size >= 5)
        assertTrue("found no @Provides DAO methods at all: $provided", provided.size >= 10)
        // The detector: the scan can see a DAO it is meant to see. GoalStepDao is the one this file
        // was written for, and it is injected by SkyRepository.
        assertTrue("the scan cannot see GoalStepDao being injected", "GoalStepDao" in injected)
        assertTrue("the scan cannot see GoalStepDao being provided", "GoalStepDao" in provided)
    }

    @Test
    fun everyConstructorInjectedDaoHasAProvider() {
        val missing = (injectedDaos() - providedDaos()).sorted()
        assertEquals(
            "these DAOs are constructor-injected but AppModule never provides them, which fails as " +
                "a Dagger MissingBinding three minutes into the build: $missing",
            emptyList<String>(),
            missing,
        )
    }
}
