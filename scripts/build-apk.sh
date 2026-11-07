#!/usr/bin/env bash
# Builds a signed APK without the Android Gradle Plugin, using Debian/Ubuntu
# packages. Useful where the official SDK download (dl.google.com) is blocked.
#
#   sudo apt-get install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
#   curl -Lo /opt/abuild/android-all.jar https://repo.maven.apache.org/maven2/org/robolectric/android-all/15-robolectric-14663667-2/android-all-15-robolectric-14663667-2.jar
#   curl -Lo /opt/abuild/junit.jar    https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
#   curl -Lo /opt/abuild/hamcrest.jar https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
#   scripts/build-apk.sh
#
# Resources link against the API 23 platform jar (the only one packaged by
# Ubuntu); Java compiles against the full API 35 framework jar from Robolectric.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=ua.kyiv.ballisticwatch
VERSION_CODE=1        # keep in sync with app/build.gradle
VERSION_NAME=0.1.0
MIN_SDK=26
TARGET_SDK=35

RES_JAR=${RES_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
LIBS=${LIBS:-/opt/abuild}
API_JAR=${API_JAR:-$LIBS/android-all.jar}
DX=${DX:-/usr/lib/android-sdk/build-tools/debian/dx}
[ -x "$DX" ] || DX=$(ls /usr/lib/android-sdk/build-tools/*/dx | head -1)

SRC="$ROOT/app/src/main"
OUT="$ROOT/build/manual"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/test-classes" "$OUT/dex"

echo "== resources"
sed "s#<manifest #<manifest package=\"$PKG\" android:versionCode=\"$VERSION_CODE\" android:versionName=\"$VERSION_NAME\" #" \
    "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
aapt package -f -m -J "$OUT/gen" -M "$OUT/AndroidManifest.xml" -S "$SRC/res" -I "$RES_JAR" \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    -F "$OUT/unsigned.apk"

echo "== compile"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn --release 8 -encoding UTF-8 \
    -cp "$API_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== unit tests"
find "$ROOT/app/src/test/java" -name '*.java' > "$OUT/test-sources.txt"
javac -nowarn -encoding UTF-8 -cp "$OUT/classes:$LIBS/junit.jar" -d "$OUT/test-classes" @"$OUT/test-sources.txt"
java -cp "$OUT/test-classes:$OUT/classes:$LIBS/junit.jar:$LIBS/hamcrest.jar:$API_JAR" \
    org.junit.runner.JUnitCore ua.kyiv.ballisticwatch.AlertClassifierTest

echo "== dex"
"$DX" --dex --min-sdk-version=$MIN_SDK --output="$OUT/dex/classes.dex" "$OUT/classes"
(cd "$OUT/dex" && zip -q -u "$OUT/unsigned.apk" classes.dex)

echo "== align + sign"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$ROOT/keystore/debug.jks" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --min-sdk-version $MIN_SDK \
    --out "$OUT/KyivBallisticWatch-$VERSION_NAME.apk" "$OUT/aligned.apk"
apksigner verify --verbose "$OUT/KyivBallisticWatch-$VERSION_NAME.apk" | head -5
echo "APK: $OUT/KyivBallisticWatch-$VERSION_NAME.apk"
