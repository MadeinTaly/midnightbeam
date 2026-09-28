#!/usr/bin/env bash
# Builds overlay-dimmer.apk without Gradle or the Android SDK manager.
# Debian/Ubuntu: apt install openjdk-17-jdk-headless dalvik-exchange aapt zipalign apksigner android-sdk-platform-23
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
DX=${DX:-$(command -v dalvik-exchange || command -v dx)}
KEYSTORE=${KEYSTORE:-debug.keystore}
OUT=build

rm -rf "$OUT" && mkdir -p "$OUT/classes"

javac -source 8 -target 8 -nowarn -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
    $(find app/src -name '*.java')
"$DX" --dex --output="$OUT/classes.dex" "$OUT/classes"

aapt package -f -M app/AndroidManifest.xml -S app/res -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias key \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=overlay-dimmer" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out overlay-dimmer.apk "$OUT/aligned.apk"
echo "built overlay-dimmer.apk ($(stat -c %s overlay-dimmer.apk) bytes)"
