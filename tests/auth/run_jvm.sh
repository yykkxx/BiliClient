#!/usr/bin/env bash
set -euo pipefail
# Bounded JVM compilation only: no Gradle/Android build, downloads, device access or credential files.
ROOT="$(realpath "$(dirname "$0")/../..")"
TEST="$ROOT/tests/auth"
CACHE="${AUTH_JVM_CACHE:-/root/workspace/BiliClient/env/gradle-home/caches/modules-2/files-2.1}"
jar() {
    local group="$1" artifact="$2" version="$3" files
    files=("$CACHE/$group/$artifact/$version"/*/"$artifact-$version.jar")
    test -f "${files[0]}" || { printf 'Missing cached dependency: %s %s\n' "$artifact" "$version" >&2; return 2; }
    printf '%s' "${files[0]}"
}
KVER=1.7.20
STDLIB="$(jar org.jetbrains.kotlin kotlin-stdlib "$KVER")"
ANNOTATIONS="$(jar org.jetbrains annotations 13.0)"
COMPILER="$(jar org.jetbrains.kotlin kotlin-compiler-embeddable "$KVER"):$(jar org.jetbrains.kotlin kotlin-reflect "$KVER"):$(jar org.jetbrains.kotlin kotlin-script-runtime "$KVER"):$(jar org.jetbrains.intellij.deps trove4j 1.0.20200330):$STDLIB:$ANNOTATIONS"
DEPS="$STDLIB:$ANNOTATIONS:$(jar com.squareup.okhttp3 okhttp 4.11.0):$(jar com.squareup.okio okio-jvm 3.2.0):$(jar org.json json 20180813):$(jar org.jsoup jsoup 1.10.2)"
# All generated outputs stay inside the authorized tests/auth scope; each invocation has a fresh directory.
OUT="$TEST/out/run-$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$OUT"
SRC="$ROOT/app/src/main/java/com/RobinNotBad/BiliClient"
SOURCES=("$SRC/api/CookieRefreshApi.kt" "$SRC/api/AppTokenRefreshApi.kt" "$SRC/util/WebRenewalState.kt" "$SRC/util/AccountStore.kt" "$SRC/util/AccountCredentialStore.kt" "$SRC/util/SharedPreferencesUtil.kt")
sha256sum "${SOURCES[@]}" > "$OUT/source-sha256.txt"
printf 'Compiling six actual production Kotlin files with cached OkHttp/JSON/Jsoup\n'
java -Xmx384m -cp "$COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn -classpath "$DEPS" -d "$OUT/classes" "${SOURCES[@]}" "$TEST"/stubs/*.kt "$TEST/AuthRenewalJvmRegression.kt"
printf 'Running JVM suite (network blocked except synthetic interceptor responses)\n'
java -Xmx256m -cp "$OUT/classes:$DEPS" AuthRenewalJvmRegressionKt | tee "$OUT/result.txt"
