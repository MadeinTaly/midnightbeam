#!/usr/bin/env bash
# Builds midnightbeam.apk without Gradle. Two toolchains:
#  - Debian/Ubuntu packages (default):
#      apt install openjdk-17-jdk-headless dalvik-exchange aapt zipalign apksigner android-sdk-platform-23
#  - Android SDK: BUILD_TOOLS=<sdk>/build-tools/<ver> ANDROID_JAR=<sdk>/platforms/android-<n>/android.jar ./build.sh
#    (uses d8, aapt and zipalign from build-tools)
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
BUILD_TOOLS=${BUILD_TOOLS:-}
tool() { if [ -n "$BUILD_TOOLS" ]; then echo "$BUILD_TOOLS/$1"; else command -v "$1"; fi; }
AAPT=$(tool aapt)
ZIPALIGN=$(tool zipalign)
KEYSTORE=${KEYSTORE:-debug.keystore}
SIGN=${SIGN:-1}   # SIGN=0: stop at the unsigned, aligned APK (build/aligned.apk), e.g. for F-Droid
OUT=build

if [ ! -f vendor/dayrhythm/src/index.js ]; then
    echo "vendor/dayrhythm is missing: run 'git submodule update --init' first" >&2
    exit 1
fi

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/assets/dayrhythm"
# the page loads the library's plain ES modules (no build, nothing minified)
cp app/assets/* "$OUT/assets/"
cp -r vendor/dayrhythm/src "$OUT/assets/dayrhythm/src"

javac -source 8 -target 8 -nowarn -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
    $(find app/src -name '*.java')
if [ -n "$BUILD_TOOLS" ]; then
    "$BUILD_TOOLS/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
else
    DX=${DX:-$(command -v dalvik-exchange || command -v dx)}
    "$DX" --dex --output="$OUT/classes.dex" "$OUT/classes"
fi

"$AAPT" package -f -M app/AndroidManifest.xml -S app/res -A "$OUT/assets" -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
(cd "$OUT" && "$AAPT" add unsigned.apk classes.dex >/dev/null)
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ "$SIGN" = 0 ]; then
    echo "built $OUT/aligned.apk (unsigned)"
    exit 0
fi

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias key \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=midnightbeam" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out midnightbeam.apk "$OUT/aligned.apk"
echo "built midnightbeam.apk ($(stat -c %s midnightbeam.apk) bytes)"
