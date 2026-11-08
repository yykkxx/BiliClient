#!/usr/bin/env bash
# 纯 JVM 回归：直接编译**生产源码** ReplyLoadState.kt（不复制算法、不依赖 Android/JUnit）。
# 复用 tests/images/run_tests.py 已验证可用的缓存 K2JVMCompiler（Gradle 7.4 dists 里的 kotlin-compiler-embeddable）。
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$REPO/tests/reply"
LIB=$(ls -d "$REPO"/../env/gradle-home/wrapper/dists/gradle-7.4-bin/*/gradle-7.4/lib | head -1)
STDLIB="$LIB/kotlin-stdlib-1.5.31.jar"
ANNOTATIONS="$LIB/annotations-20.1.0.jar"
JAVA="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}/bin/java"
OUT="$HERE/.build"
rm -rf "$OUT"; mkdir -p "$OUT/classes"
"$JAVA" -Xmx256m -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 1.8 \
  -classpath "$STDLIB:$ANNOTATIONS" -d "$OUT/classes" \
  "$REPO/app/src/main/java/com/RobinNotBad/BiliClient/activity/reply/ReplyLoadState.kt" \
  "$HERE/ReplyLoadStateRegression.kt"
"$JAVA" -Dbiliclient.repo="$REPO" -cp "$OUT/classes:$STDLIB" com.RobinNotBad.BiliClient.activity.reply.ReplyLoadStateRegression
