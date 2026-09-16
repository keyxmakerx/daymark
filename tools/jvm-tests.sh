#!/bin/sh
# Compile and run one Android-free package's unit tests on a plain JVM, outside Gradle.
#
#   tools/jvm-tests.sh stats
#   tools/jvm-tests.sh sky
#
# WHY THIS EXISTS. `CLAUDE.md` §3 says the root `./gradlew` does not run in the dev container and
# CI is the only oracle. That is true of the Android build, and it has been read as though it were
# true of everything — so changes to packages that contain no Android at all have been going to CI
# to find out whether they compile, at roughly twelve minutes a round trip. Several packages are
# Android-free BY DESIGN and say so in their headers: `stats/` ("pure and Android-free — no Room
# types, no report types, no clock"), `sky/` ("import-free … no Room types, no LocalDate, no clock,
# no Context"). Those are exactly the packages a plain `kotlinc` can build, and the compiler is
# already on this machine inside Gradle's own distribution.
#
# So this is not a replacement for CI. It is an oracle for the packages whose whole discipline is
# that they do not need one, and it turns a twelve-minute answer into a two-second answer for them.
# Everything with an Android import — `ui/`, `data/`, `backup/` — still goes to CI, and CI remains
# the final word for all of it.
#
# WHAT IT WILL NOT CATCH. Anything outside the package: a call site elsewhere that your signature
# change broke, Room's annotation processing, resource references, R8. Green here means "this
# package is internally consistent", never "the app builds".
set -eu

PKG="${1:-}"
if [ -z "$PKG" ]; then
  echo "usage: tools/jvm-tests.sh <package under com/daymark/app>   e.g. stats, sky" >&2
  exit 2
fi

REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT="${TMPDIR:-/tmp}/daymark-jvm-tests/$PKG"
GL=/opt/gradle-8.14.3/lib
GC="$HOME/.gradle/caches/modules-2/files-2.1"

# Kotlin 2.0.21 — pinned to match `gradle/libs.versions.toml`, so a mismatch here shows up as a
# version error rather than as a subtly different compile from the one CI does.
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

MAIN="$REPO/app/src/main/java/com/daymark/app/$PKG"
TEST="$REPO/app/src/test/java/com/daymark/app/$PKG"
[ -d "$MAIN" ] || { echo "no such package: $MAIN" >&2; exit 2; }

# `repoFile` lives alone in its own file precisely so source-scanning tests can be compiled without
# dragging in the Room entity graph — see the header of RepoFile.kt.
HELPERS="$REPO/app/src/test/java/com/daymark/app/backup/RepoFile.kt"

# Some tests in an otherwise pure package reach into another one — `stats/SignalsTest.kt` imports a
# Room entity's enum, for instance. Those cannot be compiled here without dragging in the graph this
# tool exists to avoid, so they are skipped. They are NAMED when skipped and never dropped quietly:
# a coverage gap you cannot see is worse than one you can, and CI still runs every one of them.
reaches_out() {
  grep -E "^import com\.daymark\.app\." "$1" 2>/dev/null | grep -qv "^import com\.daymark\.app\.$PKG\."
}

SKIPPED=""
KEEP_MAIN=""
DROPPED_CLASSES=""
for f in $(find "$MAIN" -name '*.kt'); do
  if reaches_out "$f"; then
    SKIPPED="$SKIPPED $(basename "$f")"
    DROPPED_CLASSES="$DROPPED_CLASSES $(basename "$f" .kt)"
  else
    KEEP_MAIN="$KEEP_MAIN $f"
  fi
done

KEEP=""
for f in $([ -d "$TEST" ] && find "$TEST" -name '*.kt' || true); do
  base=$(basename "$f" .kt)
  subject=${base%Test}
  drop=no
  reaches_out "$f" && drop=yes
  for c in $DROPPED_CLASSES; do [ "$subject" = "$c" ] && drop=yes; done
  if [ "$drop" = yes ]; then SKIPPED="$SKIPPED $(basename "$f")"; else KEEP="$KEEP $f"; fi
done

rm -rf "$OUT"; mkdir -p "$OUT"

# shellcheck disable=SC2046
java -cp "$KC:$STDLIB:$GL/kotlin-reflect-$KOTLIN.jar:$GL/kotlin-script-runtime-$KOTLIN.jar:$GL/kotlin-daemon-embeddable-$KOTLIN.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar:$GL/annotations-24.0.1.jar:$GL/trove4j-1.0.20200330.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 \
  -nowarn \
  -cp "$STDLIB:$JUNIT:$HAMCREST:$ANNOT" \
  -d "$OUT" \
  $KEEP_MAIN \
  $KEEP \
  "$HELPERS" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true

# A compile that produced nothing is not a pass. Without this the runner below would find no
# classes, report zero failures, and look exactly like success — the vacuous-green shape this
# repository has been bitten by before (see the Room schema step in .github/workflows/build.yml).
CLASSES=$(find "$OUT" -name '*.class' | wc -l)
[ "$CLASSES" -gt 0 ] || { echo "FAILED: nothing compiled" >&2; exit 1; }

[ -z "$SKIPPED" ] || echo "skipped (imports another package, CI still runs these):$SKIPPED"
TESTS=$(for f in $KEEP; do case "$f" in *Test.kt) basename "$f" .kt | sed "s/^/com.daymark.app.$PKG./";; esac; done)
[ -n "$TESTS" ] || { echo "compiled $CLASSES classes; no tests in $PKG" ; exit 0; }

cd "$REPO"
# shellcheck disable=SC2086
java -cp "$OUT:$STDLIB:$JUNIT:$HAMCREST:$ANNOT" org.junit.runner.JUnitCore $TESTS 2>&1 \
  | grep -v '^Picked up JAVA_TOOL_OPTIONS'
