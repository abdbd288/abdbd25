#!/usr/bin/env bash
# يبني ملف Daftari.apk من ملفات الويب في جذر المستودع.
# يحتاج (Ubuntu): apt-get install android-sdk-platform-23 aapt apksigner zipalign dalvik-exchange
# التوقيع: KEYSTORE (افتراضياً android/daftari.keystore) و KS_PASS. احتفظ بالمفتاح نفسه لكل التحديثات.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=..
JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
KEYSTORE=${KEYSTORE:-daftari.keystore}
KS_PASS=${KS_PASS:?set KS_PASS to the keystore password}
B=build; rm -rf "$B"; mkdir -p "$B/classes" "$B/assets/www/icons"

cp "$ROOT"/index.html "$ROOT"/manifest.webmanifest "$B/assets/www/"
cp "$ROOT"/icons/* "$B/assets/www/icons/"

javac -nowarn -Xlint:-options --release 8 -cp "$JAR" -d "$B/classes" $(find src -name '*.java')
dalvik-exchange --dex --output="$B/classes.dex" "$B/classes"

aapt package -f -M AndroidManifest.xml -S res -A "$B/assets" -I "$JAR" -F "$B/app.unsigned.apk"
(cd "$B" && aapt add app.unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$B/app.unsigned.apk" "$B/app.aligned.apk"
apksigner sign --v4-signing-enabled false --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --out "$ROOT/dist/Daftari.apk" "$B/app.aligned.apk"
apksigner verify "$ROOT/dist/Daftari.apk" && echo "OK -> dist/Daftari.apk"
