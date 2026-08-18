#!/usr/bin/env bash
# 把 tgwear-android-patch 的所有改动应用到 DrKLO/Telegram 源码
#
# 用法（在已 clone DrKLO/Telegram 的目录里）：
#   cd /path/to/Telegram
#   bash /path/to/tgwear-android-patch/apply_patches.sh
#
# 前置条件：
#   1. 已经 git clone https://github.com/DrKLO/Telegram.git
#   2. 已 checkout 到目标 tag（推荐 TMessagesProj_v12.9.2）
#   3. 工作区干净（git status 无变更）
#
# 完成后：
#   - patches/*.patch 已应用（build.gradle / AndroidManifest / ApplicationLoader 等）
#   - src/org/telegram/tgwear/ 已复制到 TMessagesProj/src/main/java/org/telegram/tgwear/
#   - 修改 applicationId 为 com.hrk.tgwear 后即可编译

set -euo pipefail

PATCH_ROOT="$(cd "$(dirname "$0")" && pwd)"
TELEGRAM_ROOT="$(pwd)"

echo "==> patch root : $PATCH_ROOT"
echo "==> telegram   : $TELEGRAM_ROOT"

if [ ! -d "TMessagesProj" ]; then
  echo "ERROR: 当前目录不是 DrKLO/Telegram 根目录（找不到 TMessagesProj/）"
  exit 1
fi

# 1. 应用 git patch
echo "==> applying patches..."
for p in "$PATCH_ROOT"/patches/*.patch; do
  [ -f "$p" ] || continue
  echo "  - $(basename "$p")"
  git apply --whitespace=nowarn "$p" || {
    echo "WARN: $p 应用失败，可能已经应用过，跳过"
  }
done

# 2. 复制新增源码到 TMessagesProj/src/main/java/org/telegram/tgwear/
DEST="$TELEGRAM_ROOT/TMessagesProj/src/main/java/org/telegram/tgwear"
echo "==> copying new sources to $DEST"
mkdir -p "$DEST"
cp -r "$PATCH_ROOT/src/org/telegram/tgwear/." "$DEST/"

# 3. 复制统一签名到 TMessagesProj/signing/
SIGN_DEST="$TELEGRAM_ROOT/TMessagesProj/signing"
echo "==> copying signing materials to $SIGN_DEST"
mkdir -p "$SIGN_DEST"
cp "$PATCH_ROOT/signing/tgwear.jks" "$SIGN_DEST/"
cp "$PATCH_ROOT/signing/tgwear.p12" "$SIGN_DEST/"
cp "$PATCH_ROOT/signing/tgwear.pem" "$SIGN_DEST/"

# 4. 给予可执行权限
chmod +x "$PATCH_ROOT"/apply_patches.sh 2>/dev/null || true

echo ""
echo "==> Done. 接下来："
echo "  1. 检查 TMessagesProj/build.gradle 中的 applicationId 是否已改为 com.hrk.tgwear"
echo "  2. 把小米穿戴 interconnect SDK 的 aar 放到 TMessagesProj/libs/（参考 README.md）"
echo "  3. 在 TMessagesProj/ 下执行 ./gradlew :TMessagesProj:assembleRelease"
echo "  4. 签名已通过 patches/0005-signing-config.patch 配置好，与手表端快应用签名一致"
