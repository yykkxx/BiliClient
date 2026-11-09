#!/usr/bin/env bash
set -euo pipefail
# 真实网络链探针：把**生产源码**（CookieRefreshApi/WebRenewalState/AccountStore）接到真实网络跑一遍。
#
# 安全约束（与 tests/auth 一致，只是允许出网）：
# - 凭据只在内存里经环境变量传递，**不写任何文件、不进日志**；
# - 本脚本与探针只输出 code / 字段名 / 长度；
# - 不读磁盘偏好、不碰设备、不装机（本机是用户日常用机，agent.md §3.2）。
ROOT="$(realpath "$(dirname "$0")/../..")"
TEST="$ROOT/tests/webchain"
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
OUT="$TEST/out/run-$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$OUT"
SRC="$ROOT/app/src/main/java/com/RobinNotBad/BiliClient"
SOURCES=("$SRC/api/CookieRefreshApi.kt" "$SRC/api/AppTokenRefreshApi.kt" "$SRC/util/WebRenewalState.kt" "$SRC/util/AccountStore.kt" "$SRC/util/AccountCredentialStore.kt" "$SRC/util/SharedPreferencesUtil.kt")
sha256sum "${SOURCES[@]}" > "$OUT/source-sha256.txt"
printf 'Compiling production sources (real network enabled)\n'
java -Xmx384m -cp "$COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -nowarn -classpath "$DEPS" -d "$OUT/classes" "${SOURCES[@]}" "$TEST"/stubs/*.kt "$TEST/WebChainProbe.kt"
printf 'Running probe against real bilibili endpoints\n'
java -Xmx256m -cp "$OUT/classes:$DEPS" WebChainProbeKt | tee "$OUT/result.txt"
