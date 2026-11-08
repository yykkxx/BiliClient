#!/usr/bin/env bash
# =============================================================================
# 用「MT 管理器默认签名」那套密钥给 APK 签名（自动化替代手工 MT 操作）
#
# 用法:
#   bash changed/sign_platform.sh <input.apk> <output.apk>
#   bash changed/sign_platform.sh releases/<ver>/com.tencent.qqmusicwatchs_20260606.apk \
#                                releases/<ver>/com.tencent.qqmusicwatchs_20260606_sign.apk
#
# 为什么用它：用户手工用 MT 管理器「默认签名」签的官方包，证书是
#   SHA-256 a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc
#   SHA-1   61ed377e85d386a8dfee6b864bd85b0bfaa5af81
# 实测这套就是 **AOSP testkey**（build/target/product/security/testkey.pk8 + testkey.x509.pem），
# NOT `platform`（platform 的 SHA-256 是 c8a2e9bc…，两者别搞混——本轮就是因为先取了 platform
# 才发现指纹对不上，才定位到真正的 testkey）。
# keystore 由 pk8 + x509 转成 JKS，落在 env/keys/testkey/testkey.jks。
#
# 主线零改动：不碰 app/build.gradle、不碰 AndroidManifest、不碰任何被 git 跟踪的构建文件，
# 只在 input.apk 的副本上重签。
# =============================================================================
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "用法: bash changed/sign_platform.sh <input.apk> <output.apk>" >&2
  exit 2
fi

INPUT="$1"
OUTPUT="$2"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KEYSTORE="$ENV_ROOT/env/keys/testkey/testkey.jks"
[ -n "${FLAVOR_ENV:-}" ] && [ -f "$FLAVOR_ENV" ] && source "$FLAVOR_ENV"
[ -f "$ENV_ROOT/env/env.sh" ] && source "$ENV_ROOT/env/env.sh"

APKSIGNER_JAR="/opt/android-sdk/build-tools/34.0.0/lib/apksigner.jar"
EXPECT_SHA256="a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"

[ -f "$INPUT" ] || { echo "找不到输入 APK: $INPUT" >&2; exit 2; }
[ -f "$KEYSTORE" ] || { echo "找不到 keystore: $KEYSTORE（见 notes/platform_sign_20261108.md）" >&2; exit 2; }
[ -f "$APKSIGNER_JAR" ] || { echo "找不到 apksigner.jar: $APKSIGNER_JAR" >&2; exit 2; }

echo "==> 输入 : $INPUT"
echo "==> 输出 : $OUTPUT"

# 签名会重写 zip，输入必须保持原样：先拷到临时文件再签。
TMP="$(mktemp /tmp/platform_sign_XXXXXX.apk)"
trap 'rm -f "$TMP"' EXIT
cp "$INPUT" "$TMP"

# v1+v2+v3 全开，与 MT 管理器默认（"勾选 v1/v2"）最接近；min-sdk 必须与工程一致（29）。
java -jar "$APKSIGNER_JAR" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias testkey --min-sdk-version 29 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUTPUT" "$TMP"

echo "==> 证书回验 =="
CERTS="$(java -jar "$APKSIGNER_JAR" verify --print-certs "$OUTPUT" 2>&1)"
echo "$CERTS" | grep -E "DN:|SHA-256 digest|SHA-1 digest"
ACTUAL="$(echo "$CERTS" | sed -nE 's/.*SHA-256 digest: ([0-9a-f]+).*/\1/p' | head -1)"
if [ "$ACTUAL" != "$EXPECT_SHA256" ]; then
  echo "证书指纹不匹配！期望 $EXPECT_SHA256，实际 $ACTUAL" >&2
  exit 1
fi

echo "==> verify =="
java -jar "$APKSIGNER_JAR" verify --min-sdk-version 29 "$OUTPUT" && echo "  签名验证通过"
echo "==> 完成: $OUTPUT"
sha256sum "$OUTPUT"
