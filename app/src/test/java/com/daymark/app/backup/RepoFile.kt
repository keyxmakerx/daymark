package com.daymark.app.backup

import java.io.File

/**
 * Finds a file in this repo by walking up from the working directory, because Gradle runs unit
 * tests with `user.dir` at the module directory while some IDEs use the repo root. Not finding it
 * throws: an assertion suite that cannot locate its subject is exactly the shape of a guard that
 * reports green forever.
 *
 * ## Why this is its own file
 *
 * A dozen source-scanning tests across `stats/`, `data/` and `ui/` call this, and it used to live
 * at the top of `BackupReplaceSourceTest.kt` — a file that, to be compiled at all, drags in
 * `BackupManager` and through it `kotlinx.serialization` and the whole Room entity graph. That cost
 * nothing under Gradle, which compiles the module in one go. It cost something real to
 * `tools/jvm-tests.sh`, which exists precisely to compile ONE Android-free package on a plain JVM
 * without that graph: the helper every source test needs was welded to the heaviest file in the
 * suite. Moving it changes no behaviour and no import — same package, same `internal` visibility —
 * and makes the pure packages compilable on their own, which is the difference between a
 * twelve-minute oracle and a two-second one.
 */
internal fun repoFile(rel: String): File {
    var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
    val tail = rel.substringAfter("app/")
    while (dir != null) {
        for (candidate in listOf(File(dir, rel), File(dir, tail))) {
            if (candidate.isFile) return candidate
        }
        dir = dir.parentFile
    }
    throw AssertionError("could not find $rel from ${System.getProperty("user.dir")}")
}
