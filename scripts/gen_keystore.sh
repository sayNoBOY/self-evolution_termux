#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# gen_keystore.sh — 生成 APK 签名密钥库（发布签名用）
#
# 依赖: JDK (keytool)。例如: sudo apt install openjdk-17-jdk
#
# 用法:
#   ./gen_keystore.sh [输出目录]        默认输出到 ./keystore/release.jks
#
# 重要:
#   - 密钥库口令/别名口令会提示你交互输入；请妥善保管。
#   - 一旦用某个 keystore 签名发布，后续更新必须用同一个 keystore，
#     否则 Android 会拒绝覆盖安装（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
#   - 官方 Termux (F-Droid) 的签名与你无关：无论签名是否相同，
#     只要 applicationId 不变，安装你的包前都必须先卸载官方版。
# ---------------------------------------------------------------------------
set -euo pipefail

if ! command -v keytool >/dev/null 2>&1; then
    echo "[!] 未找到 keytool，请先安装 JDK（如: sudo apt install openjdk-17-jdk）" >&2
    exit 1
fi

OUT_DIR="${1:-keystore}"
mkdir -p "$OUT_DIR"
JKS="$OUT_DIR/release.jks"

if [ -e "$JKS" ]; then
    echo "[!] $JKS 已存在，为避免覆盖现有密钥已终止。" >&2
    echo "    如确要重新生成，请先自行备份并删除该文件。" >&2
    exit 1
fi

keytool -genkeypair \
    -v \
    -keystore "$JKS" \
    -alias termux-release \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000

echo
echo "[*] 密钥库已生成: $JKS"
echo "[*] 接下来在 termux-app/app/build.gradle 中接入（详见指南 §9）:"
cat <<'GRADLE'
    signingConfigs {
        release {
            storeFile file("../keystore/release.jks")
            storePassword System.getenv("KS_PASS") ?: ""
            keyAlias "termux-release"
            keyPassword System.getenv("KS_PASS") ?: ""
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
GRADLE
echo "[*] 构建时通过环境变量传入口令: KS_PASS=xxx ./gradlew assembleRelease"
