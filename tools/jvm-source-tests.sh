#!/bin/sh
# Compile and run the SOURCE tests that live in packages a plain JVM cannot otherwise build.
#
#   tools/jvm-source-tests.sh
#
# WHY THIS EXISTS, given tools/jvm-tests.sh already does this per package. That script takes a
# package and compiles all of it, which works for `stats/` and `sky/` because they are Android-free
# by design. It cannot work for `data/`: the package is Room entities and DAOs, and compiling one
# file in it drags in the graph.
#
# But several of the most load-bearing tests in this repository live in `data/` and READ SOURCE AS
# TEXT. `PeopleSchemaTest` and `TimedOfferSchemaTest` assert that a migration's SQL matches the
# entities it has to produce, and that it never back-fills a person's hour from a timestamp. They
# import `repoFile` and `TimingGrid` and nothing else — no Room type is reachable from either file.
# They were going to CI to find out whether they passed, at roughly twelve minutes a round trip.
#
# WHAT IT BOUGHT, stated because it is the argument for keeping it. The change that merged two
# version-18 migrations into one broke seven assertions across those two files. The obvious repair
# narrowed a scan to "this feature's own statements" — and made the test named for the forbidden
# back-fill blind to the back-fill. CI would have gone green over that. It was caught by planting
# the statement and watching the wrong tests fail, which is a thing you only do when the answer
# takes a second.
#
# The same holds for the report's copy. `export/ReportCopySourceTest` reads the PDF renderer, which
# draws on an Android Canvas and so cannot be compiled here, as text, and
# `ui/settings/ReportExportSourceTest` reads the Compose settings screen the same way. They import
# `repoFile` and the string helpers in `ui/SourceText.kt`, and none of those reaches Android or Room.
# So does the theme: `ui/theme/ColorSchemeSourceTest` reads Theme.kt and Color.kt, which import
# Compose, holds every colour-scheme role off the mood colours, and holds both schemes to setting
# every surface container with words at 4.5:1 on each (#410); `ui/FaintInkSourceTest` reads
# every production file and holds the faint ink off every word. And the Insights month:
# `ui/insights/MonthGridSourceTest` reads InsightsScreen.kt and the calendar's view model and day
# model, and holds every day off a mood fill, a blend and an average (#397). `ui/WeekDaysSourceTest`
# does the same for Insights → Week and Home's strip (#411), measures the ring on every mood dot
# against Theme.kt and Color.kt, and holds the dots to the order of the day's own list (#412).
# `ui/theme/DynamicColorSourceTest` reads every production file and holds dynamic colour off until a
# person turns it on, with the Settings switch as the one thing that writes it (#309).
# `ui/components/TickAndGreenSourceTest` reads ui/components and the PDF renderer and holds both off a
# tick, and the components off green (#278). `ui/HairlineFillSourceTest` reads every production file
# and holds every word drawn on the hairline fill off the soft and faint inks (#408).
#
# WHAT IT WILL NOT CATCH. Everything tools/jvm-tests.sh cannot: anything outside these files, Room's
# annotation processing, Hilt, resources, R8. And it runs a HAND-LISTED set of test files. A new
# source test is not picked up until somebody adds it below, and CI remains the oracle for all of
# it.
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
# A folder of its own for every run, removed on exit: two runs at once must never compile into, or
# delete, each other's classes.
OUT=$(mktemp -d "${TMPDIR:-/tmp}/daymark-jvm-source-tests.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
GL=/opt/gradle-8.14.3/lib
GC="$HOME/.gradle/caches/modules-2/files-2.1"

# Pinned to `gradle/libs.versions.toml`, so a mismatch is a version error rather than a subtly
# different compile from the one CI does.
KOTLIN=2.0.21
KC="$GL/kotlin-compiler-embeddable-$KOTLIN.jar"
STDLIB="$GL/kotlin-stdlib-$KOTLIN.jar"
[ -f "$KC" ] || { echo "no Kotlin $KOTLIN compiler at $KC — this machine's Gradle may have moved" >&2; exit 3; }

find_jar() {
  found=$(find "$GC/$1" -name "$2" -type f 2>/dev/null | head -1)
  [ -n "$found" ] || { echo "could not find $2 under $GC/$1 — run a Gradle build once to populate the cache" >&2; exit 3; }
  echo "$found"
}
JUNIT=$(find_jar junit/junit "junit-4.13.2.jar")
HAMCREST="$GL/hamcrest-core-1.3.jar"
ANNOT=$(find_jar org.jetbrains/annotations "annotations-13.0.jar")

# The test files this runs, and the helpers they need. Adding a file here is a deliberate act: read
# its imports first and confirm none of them is a Room or Android type. `CompanionSchemaTest` and
# `MigrationSchemaExportTest` import `repoFile`, JUnit and `java.io.File` and nothing else; the
# second reads the exported schemas under app/schemas as JSON text. The two report tests also use
# `ui/SourceText.kt`, which imports nothing from the app, and so do `ColorSchemeSourceTest`, which
# also reads the web's token sheet as text, `FaintInkSourceTest`, which also imports `java.io.File`,
# `MonthGridSourceTest`, which imports `repoFile`, the helpers in `ui/SourceText.kt` and JUnit only,
# and `WeekDaysSourceTest`, which imports the same and `java.util.Locale`.
# `DynamicColorSourceTest` and `TickAndGreenSourceTest` import `repoFile`, `codeOnly`, `java.io.File`
# and JUnit only; `HairlineFillSourceTest` imports `repoFile`, `java.io.File` and JUnit and uses
# `codeOnly` and `withoutComments` from its own package.
TESTS="com.daymark.app.data.PeopleSchemaTest com.daymark.app.data.TimedOfferSchemaTest
com.daymark.app.data.CompanionSchemaTest com.daymark.app.data.MigrationSchemaExportTest
com.daymark.app.export.ReportCopySourceTest com.daymark.app.ui.settings.ReportExportSourceTest
com.daymark.app.ui.theme.ColorSchemeSourceTest com.daymark.app.ui.FaintInkSourceTest
com.daymark.app.ui.insights.MonthGridSourceTest com.daymark.app.ui.WeekDaysSourceTest
com.daymark.app.ui.theme.DynamicColorSourceTest
com.daymark.app.ui.components.TickAndGreenSourceTest
com.daymark.app.ui.HairlineFillSourceTest"
SOURCES="$REPO/app/src/test/java/com/daymark/app/data/PeopleSchemaTest.kt
$REPO/app/src/test/java/com/daymark/app/data/TimedOfferSchemaTest.kt
$REPO/app/src/test/java/com/daymark/app/data/CompanionSchemaTest.kt
$REPO/app/src/test/java/com/daymark/app/data/MigrationSchemaExportTest.kt
$REPO/app/src/test/java/com/daymark/app/export/ReportCopySourceTest.kt
$REPO/app/src/test/java/com/daymark/app/ui/settings/ReportExportSourceTest.kt
$REPO/app/src/test/java/com/daymark/app/ui/theme/ColorSchemeSourceTest.kt
$REPO/app/src/test/java/com/daymark/app/ui/FaintInkSourceTest.kt
$REPO/app/src/test/java/com/daymark/app/ui/insights/MonthGridSourceTest.kt
$REPO/app/src/test/java/com/daymark/app/ui/WeekDaysSourceTest.kt
$REPO/app/src/test/java/com/daymark/app/ui/theme/DynamicColorSourceTest.kt
$REPO/app/src/test/java/com/daymark/app/ui/components/TickAndGreenSourceTest.kt
$REPO/app/src/test/java/com/daymark/app/ui/HairlineFillSourceTest.kt
$REPO/app/src/test/java/com/daymark/app/backup/RepoFile.kt
$REPO/app/src/test/java/com/daymark/app/ui/SourceText.kt"

# `TimedOfferSchemaTest` calls TimingGrid to prove the sentinel is really refused, so `stats/` is
# compiled in — minus the files that import outside it, the same rule tools/jvm-tests.sh applies.
SKIPPED=""
for f in $(find "$REPO/app/src/main/java/com/daymark/app/stats" -name '*.kt'); do
  if grep -E "^import com\.daymark\.app\." "$f" 2>/dev/null | grep -qv "^import com\.daymark\.app\.stats\."; then
    SKIPPED="$SKIPPED $(basename "$f")"
  else
    SOURCES="$SOURCES
$f"
  fi
done


# shellcheck disable=SC2086
java -cp "$KC:$STDLIB:$GL/kotlin-reflect-$KOTLIN.jar:$GL/kotlin-script-runtime-$KOTLIN.jar:$GL/kotlin-daemon-embeddable-$KOTLIN.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar:$GL/annotations-24.0.1.jar:$GL/trove4j-1.0.20200330.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 \
  -nowarn \
  -cp "$STDLIB:$JUNIT:$HAMCREST:$ANNOT" \
  -d "$OUT" \
  $SOURCES 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true

# A compile that produced nothing is not a pass — without this the runner below would find no
# classes, report zero failures and look exactly like success.
CLASSES=$(find "$OUT" -name '*.class' | wc -l)
[ "$CLASSES" -gt 0 ] || { echo "FAILED: nothing compiled" >&2; exit 1; }

[ -z "$SKIPPED" ] || echo "skipped (imports another package, CI still runs these):$SKIPPED"

cd "$REPO"
# shellcheck disable=SC2086
java -cp "$OUT:$STDLIB:$JUNIT:$HAMCREST:$ANNOT" org.junit.runner.JUnitCore $TESTS 2>&1 \
  | grep -v '^Picked up JAVA_TOOL_OPTIONS'
