#!/usr/bin/env bash
# 用本机 Android SDK 直接出 APK，不依赖 Gradle / Android Studio / 网络。
# 依赖：aapt2、javac、d8、zipalign、apksigner、python3
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/dev/android-sdk}}"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"
OUT="$ROOT/build/manual"
APK_NAME="douyin-web-shell.apk"

MIN_SDK=26
TARGET_SDK=36
VERSION_CODE=1
VERSION_NAME=0.1.0

for f in "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" "$BUILD_TOOLS/apksigner" "$ANDROID_JAR"; do
  [ -e "$f" ] || { echo "缺少构建工具: $f" >&2; exit 1; }
done

# macOS 自带 /usr/bin/keytool 只是个存根，必须用 JDK 里的那个
JAVA_BIN="$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$(command -v javac)")"
KEYTOOL="$JAVA_BIN/keytool"
[ -x "$KEYTOOL" ] || { echo "找不到 keytool: $KEYTOOL" >&2; exit 1; }

echo "SDK        : $SDK"
echo "build-tools: $(basename "$BUILD_TOOLS")"
echo "platform   : $(basename "$PLATFORM")"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "[1/6] 编译资源"
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"

echo "[2/6] 链接资源与清单"
# aapt2 要求 <manifest package="...">；AGP 走 namespace 时不允许写，
# 所以这里生成一份临时清单，两套构建互不干扰
python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding="utf-8").read()
head = s.split(">", 1)[0]
if "package=" not in head:
    s = s.replace("<manifest ", '<manifest package="com.example.douyinweb" ', 1)
open(dst, "w", encoding="utf-8").write(s)
PY
"$BUILD_TOOLS/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  -A "$ROOT/app/src/main/assets" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$OUT/res.zip"

echo "[3/6] 编译 Java"
find "$ROOT/app/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 17 -Xlint:-options -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "[4/6] 转 dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
# shellcheck disable=SC2046
"$BUILD_TOOLS/d8" --release --lib "$ANDROID_JAR" --min-api "$MIN_SDK" \
  --output "$OUT/dex" $(cat "$OUT/classes.txt")

echo "[5/6] 打包并对齐"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
python3 - "$OUT/base.apk" "$OUT/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  echo "  -> 已生成 debug.keystore（自用签名，勿外发）"
fi
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$ROOT/$APK_NAME" "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$ROOT/$APK_NAME" >/dev/null

echo
echo "完成: $ROOT/$APK_NAME  ($(du -h "$ROOT/$APK_NAME" | cut -f1))"
echo "安装: adb install -r \"$ROOT/$APK_NAME\""
