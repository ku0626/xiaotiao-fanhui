#!/bin/bash
# 小条返回 PillBar · 无需 Gradle 的构建脚本
#
# 使用前配置下面 4 个变量（示例为 Windows + Git Bash 风格路径）：
#   JAVA_HOME : JDK 17 根目录
#   SDK_DIR   : Android SDK 根目录（内含 build-tools/34.0.0 与 platforms/android-34）
#   APP_DIR   : 本仓库的 app/ 目录（绝对路径）
#   OUT_DIR   : 构建产物输出目录
#
# 可选：版本号直接改下面 --version-code / --version-name 两处。

set -e
export PATH="/usr/bin:/bin:/mingw64/bin:$PATH"

JAVA_HOME="C:/path/to/jdk17"
SDKW="C:/path/to/android-sdk"
APPW="C:/path/to/repo/app"
OUTW="C:/path/to/out"

# Windows 风格路径转 MSYS 风格（如果脚本在真实 Linux/macOS 上跑，把 /c/ 前缀改成对应写法即可）
SDKP="$SDKW"
APPP="$APPW"
OUTP="$OUTW"
BTW="$SDKW/build-tools/34.0.0"
BTP="$BTW"
PLATW="$SDKW/platforms/android-34/android.jar"
PLATP="$PLATW"

rm -rf "$OUTP"
mkdir -p "$OUTP/res" "$OUTP/gen" "$OUTP/classes" "$OUTP/dex"

echo "[1/6] 编译资源"
"$BTP/aapt2" compile --dir "$APPW/res" -o "$OUTW/res/res.zip"

echo "[2/6] 链接资源 + 生成 R.java"
"$BTP/aapt2" link -o "$OUTW/base.apk" -I "$PLATW" \
  --manifest "$APPW/AndroidManifest.xml" \
  --java "$OUTW/gen" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 25 --version-name 1.24 \
  "$OUTW/res/res.zip" --auto-add-overlay

echo "[3/6] javac"
find "$APPP/src" "$OUTP/gen" -name "*.java" > "$OUTP/sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$PLATP" -classpath "$PLATP" -d "$OUTW/classes" @"$OUTP/sources.txt"

echo "[4/6] d8 转 dex"
find "$OUTP/classes" -name "*.class" > "$OUTP/classes.txt"
"$JAVA_HOME/bin/java" -cp "$BTP/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$PLATP" --output "$OUTW/dex" @"$OUTP/classes.txt"

echo "[5/6] 打包 + 对齐"
cp "$OUTP/base.apk" "$OUTP/unsigned.apk"
"$JAVA_HOME/bin/jar" uf "$OUTW/unsigned.apk" -C "$OUTW/dex" classes.dex
"$BTP/zipalign" -f -p 4 "$OUTW/unsigned.apk" "$OUTP/aligned.apk"

echo "[6/6] 签名"
KS="$OUTP/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BTP/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUTP/pillbar.apk" "$OUTP/aligned.apk"

"$BTP/apksigner" verify --print-certs "$OUTP/pillbar.apk" | head -3
ls -lh "$OUTP/pillbar.apk"
echo BUILD_OK
