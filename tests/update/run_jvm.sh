#!/usr/bin/env bash
set -euo pipefail
# Use only pre-existing cached jars: never run Gradle, install software or read credential files.
ROOT="$(realpath "$(dirname "$0")/../..")"
TEST="$ROOT/tests/update"
CACHE="${UPDATE_JVM_CACHE:-/root/workspace/BiliClient/env/gradle-home/caches/modules-2/files-2.1}"
jar() {
    local group="$1" artifact="$2" version="$3" files
    files=("$CACHE/$group/$artifact/$version"/*/"$artifact-$version.jar")
    test -f "${files[0]}" || { printf 'Missing cached jar: %s %s\n' "$artifact" "$version" >&2; return 2; }
    printf '%s' "${files[0]}"
}
KVER=1.7.20
STDLIB="$(jar org.jetbrains.kotlin kotlin-stdlib "$KVER")"
ANNOTATIONS="$(jar org.jetbrains annotations 13.0)"
COMPILER="$(jar org.jetbrains.kotlin kotlin-compiler-embeddable "$KVER"):$(jar org.jetbrains.kotlin kotlin-reflect "$KVER"):$(jar org.jetbrains.kotlin kotlin-script-runtime "$KVER"):$(jar org.jetbrains.intellij.deps trove4j 1.0.20200330):$STDLIB:$ANNOTATIONS"
DEPS="$STDLIB:$ANNOTATIONS:$(jar com.squareup.okhttp3 okhttp 4.11.0):$(jar com.squareup.okio okio-jvm 3.2.0):$(jar org.json json 20180813):$(jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.6.4)"
# Generated classes, fixture files and receipts stay in the authorized test scope.
OUT="$TEST/out/run-$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$OUT"
SRC="$ROOT/app/src/main/java/com/RobinNotBad/BiliClient"
SOURCES=("$SRC/model/update/GiteeRelease.kt" "$SRC/model/update/GiteeReleasePolicy.kt" "$SRC/model/update/UpdateVersion.kt" "$SRC/api/GiteeReleaseApi.kt" "$SRC/activity/update/GiteeApkDownload.kt")
sha256sum "${SOURCES[@]}" > "$OUT/source-sha256.txt"
printf 'Compiling five actual production Kotlin files; Android boundaries are test-only stubs\n'
java -Xmx384m -cp "$COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn -classpath "$DEPS" -d "$OUT/classes" "${SOURCES[@]}" "$TEST"/stubs/*.kt "$TEST/UpdateJvmRegression.kt" "$TEST/PublicLatestProbe.kt"
if [[ "${1:-}" == "--public-latest" ]]; then
    # Explicit opt-in. Only public fixed Gitee URLs; no tokens, cookies, device or account fields.
    java -Xmx256m -cp "$OUT/classes:$DEPS" PublicLatestProbeKt | tee "$OUT/public-latest-result.txt"
else
    java -Xmx256m -cp "$OUT/classes:$DEPS" UpdateJvmRegressionKt "$ROOT" "$OUT" | tee "$OUT/result.txt"
fi
# Detect concurrent source edits rather than implying that an old compilation tests current files.
sha256sum -c "$OUT/source-sha256.txt"
printf 'Receipt directory: %s\n' "$OUT"
