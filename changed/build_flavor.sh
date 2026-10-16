#!/bin/bash
# ============================================================================
# 特殊渠道包构建脚本（不影响主线的代码与构建）
#
# 用法：
#   bash changed/build_flavor.sh <variant> [versionCode] [versionName]
#
#   variant     必填：
#                 qqmusic          —— 第一个包：改包名/图标/应用名/版本号
#                 qqmusic-hidden   —— 第二个包：在第一个包基础上删除启动 Activity 的
#                                     MAIN + LAUNCHER，使其从桌面（启动器）消失
#   versionCode 可选，默认 20260606
#   versionName 可选，默认 QQ音乐
#
# 产物：
#   releases/com.tencent.qqmusicwatchs_20260606.apk
#   releases/com.tencent.qqmusicwatchs_20260606_hidden.apk
#
# 设计原则（务必遵守，避免污染主线）：
#   1. 不修改 app/build.gradle、AndroidManifest.xml、资源等任何被 git 跟踪的文件；
#   2. 全程在临时副本目录（默认 /tmp/biliflavorsrc）里打补丁后构建；
#   3. 通过 Gradle init script 在配置阶段改写 applicationId / 版本号 / 签名，
#      主线源码里读到的仍然是主线版本号（3.0.0 / 20261013）；
#   4. 产物统一落到 releases/，临时副本保留以便增量构建。
# ============================================================================
set -euo pipefail

VARIANT="${1:-}"
case "$VARIANT" in
  qqmusic|qqmusic-hidden) ;;
  *)
    echo "用法: bash changed/build_flavor.sh <qqmusic|qqmusic-hidden> [versionCode] [versionName]" >&2
    exit 2
    ;;
esac

VERSION_CODE="${2:-20260606}"
VERSION_NAME="${3:-QQ音乐}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

WORK_ROOT="${FLAVOR_WORK_ROOT:-/tmp/biliflavorsrc}"
RELEASE_DIR="${FLAVOR_RELEASE_DIR:-$SRC_ROOT/releases}"
PACKAGE_NAME="${FLAVOR_PACKAGE:-com.tencent.qqmusicwatchs}"
APP_NAME="QQ音乐"
MAIN_ICON="$SCRIPT_DIR/icon.png"

echo "==> 主源码  : $SRC_ROOT"
echo "==> 临时副本: $WORK_ROOT"
echo "==> 包名    : $PACKAGE_NAME"
echo "==> 应用名  : $APP_NAME"
echo "==> 版本    : $VERSION_NAME ($VERSION_CODE)"
echo "==> 变体    : $VARIANT"

[ -f "$MAIN_ICON" ] || { echo "缺少图标文件: $MAIN_ICON" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. 同步一份干净的源码副本
# ---------------------------------------------------------------------------
echo "==> 同步源码副本 …"
mkdir -p "$WORK_ROOT"
for item in build.gradle settings.gradle gradle.properties gradlew gradle \
            ijkplayer-java DanmakuFlameMaster brotlij local.properties; do
  if [ -e "$SRC_ROOT/$item" ]; then
    cp -a "$SRC_ROOT/$item" "$WORK_ROOT/" 2>/dev/null || true
  fi
done
# app 单独复制，排除 build/（上次构建产物，避免用到过期的 class）
mkdir -p "$WORK_ROOT/app"
for sub in src libs proguard-rules.pro build.gradle; do
  if [ -e "$SRC_ROOT/app/$sub" ]; then
    cp -a "$SRC_ROOT/app/$sub" "$WORK_ROOT/app/" 2>/dev/null || true
  fi
done

# ---------------------------------------------------------------------------
# 2. 打补丁（仅作用于副本）
# ---------------------------------------------------------------------------
echo "==> 写入图标 …"
cp -f "$MAIN_ICON" "$WORK_ROOT/app/src/main/res/mipmap-nodpi/icon.png"

# 2.1 应用名改为 APP_NAME（仅改副本里的 strings.xml）
python3 - "$WORK_ROOT/app/src/main/res/values/strings.xml" "$APP_NAME" <<'PY_EOF'
import re, sys
path, app_name = sys.argv[1], sys.argv[2]
with open(path, encoding='utf-8') as f:
    text = f.read()
new, n = re.subn(r'<string name="app_name">.*?</string>',
                 f'<string name="app_name">{app_name}</string>', text, count=1, flags=re.S)
if n != 1:
    raise SystemExit("未能改写 app_name")
with open(path, 'w', encoding='utf-8') as f:
    f.write(new)
print(f"  app_name -> {app_name}")
PY_EOF

# 2.2 FileProvider authority 同步改成新包名（否则与实际 applicationId 不一致）
python3 - "$WORK_ROOT/app/src/main/AndroidManifest.xml" "$PACKAGE_NAME" <<'PY_EOF'
import sys
path, pkg = sys.argv[1], sys.argv[2]
with open(path, encoding='utf-8') as f:
    text = f.read()
old = 'android:authorities="com.RobinNotBad.BiliClient.FileProvider"'
new = f'android:authorities="{pkg}.FileProvider"'
if old not in text:
    raise SystemExit("未找到 FileProvider authority")
with open(path, 'w', encoding='utf-8') as f:
    f.write(text.replace(old, new, 1))
print(f"  FileProvider authority -> {pkg}.FileProvider")
PY_EOF

# 2.3 第二个包：删除启动 Activity 的 MAIN / LAUNCHER
if [ "$VARIANT" = "qqmusic-hidden" ]; then
  echo "==> 删除启动 Activity（LauncherActivity）的 MAIN + LAUNCHER（从桌面消失）…"
  python3 - "$WORK_ROOT/app/src/main/AndroidManifest.xml" <<'PY_EOF'
import re, sys
path = sys.argv[1]
with open(path, encoding='utf-8') as f:
    text = f.read()

pattern = re.compile(
    r'(<activity\b[^>]*android:name="\.activity\.video\.LauncherActivity".*?</activity>)', re.S)

def strip_launcher(match):
    block = match.group(1)
    cleaned = re.sub(
        r'\s*<intent-filter[^>]*>\s*<action android:name="android\.intent\.action\.MAIN"\s*/>\s*'
        r'<category android:name="android\.intent\.category\.LAUNCHER"\s*/>\s*</intent-filter>',
        '', block, flags=re.S)
    if cleaned == block:
        raise SystemExit("未能定位 LauncherActivity 的 MAIN/LAUNCHER intent-filter")
    return cleaned

new_text, count = pattern.subn(strip_launcher, text)
if count != 1:
    raise SystemExit(f"LauncherActivity 匹配数量异常: {count}")
with open(path, 'w', encoding='utf-8') as f:
    f.write(new_text)
print("  已移除启动器入口（MAIN + LAUNCHER）")
PY_EOF
fi

# ---------------------------------------------------------------------------
# 3. 改写副本里的 app/build.gradle（applicationId / 版本号 / release 签名）
#    注意：AGP 不允许在配置完成后（如 init script 的 projectsEvaluated 里）
#    再改 applicationId，所以这里直接改副本文件，主线文件保持原样。
# ---------------------------------------------------------------------------
echo "==> 改写副本 build.gradle …"
python3 - "$WORK_ROOT/app/build.gradle" "$PACKAGE_NAME" "$VERSION_CODE" "$VERSION_NAME" <<'PY_EOF'
import re, sys
path, pkg, code, name = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
with open(path, encoding='utf-8') as f:
    text = f.read()

text, n1 = re.subn(r'applicationId\s+"[^"]*"', f'applicationId "{pkg}"', text, count=1)
text, n2 = re.subn(r'versionCode\s+\d+', f'versionCode {code}', text, count=1)
text, n3 = re.subn(r'versionName\s+"[^"]*"', f'versionName "{name}"', text, count=1)
if (n1, n2, n3) != (1, 1, 1):
    raise SystemExit(f"build.gradle 改写失败: applicationId={n1} versionCode={n2} versionName={n3}")

# release 未配置签名时会产出 unsigned 包，这里在 buildTypes.release 里显式
# 使用 debug 签名，保证特殊包可直接安装。
# 注意：必须改 buildTypes 里的 release，不能误改 signingConfigs 里的 release，
# 也不能靠全局搜索 signingConfigs.debug 判断（debug 块里本来就有这一行）。
bt = text.find('buildTypes {')
if bt < 0:
    raise SystemExit("未找到 buildTypes 块")
rel = text.find('release {', bt)
if rel < 0:
    raise SystemExit("未在 buildTypes 中找到 release 块")
line_end = text.find('\n', rel) + 1
release_head = text[rel:line_end]
if 'signingConfig signingConfigs.debug' not in release_head:
    text = text[:line_end] + '            signingConfig signingConfigs.debug\n' + text[line_end:]

with open(path, 'w', encoding='utf-8') as f:
    f.write(text)
print(f"  applicationId -> {pkg}")
print(f"  versionCode   -> {code}")
print(f"  versionName   -> {name}")
print("  release 签名  -> debug")
PY_EOF

# ---------------------------------------------------------------------------
# 4. 构建 release APK（限制并行与内存，避免占满机器）
# ---------------------------------------------------------------------------
echo "==> 构建 release APK …"
pushd "$WORK_ROOT" >/dev/null
set +e
sh ./gradlew :app:assembleRelease --offline --max-workers=2
BUILD_STATUS=$?
set -e
popd >/dev/null
[ "$BUILD_STATUS" -eq 0 ] || { echo "构建失败" >&2; exit "$BUILD_STATUS"; }

APK_SRC="$WORK_ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK_SRC" ] || { echo "未找到产物: $APK_SRC" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 5. 收集产物到 releases/
# ---------------------------------------------------------------------------
mkdir -p "$RELEASE_DIR"
SUFFIX=""
[ "$VARIANT" = "qqmusic-hidden" ] && SUFFIX="_hidden"
APK_DST="$RELEASE_DIR/${PACKAGE_NAME}_${VERSION_CODE}${SUFFIX}.apk"
cp -f "$APK_SRC" "$APK_DST"

echo "==> 完成: $APK_DST"
ls -la "$APK_DST"
