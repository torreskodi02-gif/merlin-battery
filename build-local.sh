#!/bin/bash
# Manual APK build — no Gradle daemon, no dependency downloads.
# Uses kotlinc + aapt2 + d8 + zipalign + apksigner directly.
set -e
unset JDK_JAVA_OPTIONS
export JAVA_HOME=$HOME/workspace/tools/jdk-17.0.21+9
export PATH=$JAVA_HOME/bin:$HOME/workspace/tools/kotlinc/bin:$PATH
SDK=$HOME/workspace/tools/android-sdk
AJAR=$SDK/platforms/android-33/android.jar
BT=$SDK/build-tools/33.0.2
APP=$HOME/workspace/battery-app
OUT=/tmp/battery-build
rm -rf $OUT && mkdir -p $OUT/classes $OUT/dex

echo "== kotlinc (all sources vs android.jar) =="
kotlinc $APP/app/src/main/java/app/merlinbattery/*.kt \
  -classpath $AJAR -jvm-target 1.8 -d $OUT/classes

echo "== aapt2 link (manifest only — no XML layouts, all Views in code) =="
$BT/aapt2 link -o $OUT/base.apk \
  --manifest $APP/app/src/main/AndroidManifest.xml \
  -I $AJAR --version-code 10001 --version-name 1.0.0 \
  --auto-add-overlay

echo "== d8 dex =="
$BT/d8 --lib $AJAR --release --output $OUT/dex \
  $OUT/classes $HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar

echo "== add classes.dex to APK =="
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" << 'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("added classes.dex")
PY

echo "== zipalign + sign (stable Merlin key) =="
$BT/zipalign -f -p 4 $OUT/base.apk $OUT/aligned.apk
base64 -d $APP/app/merlin.keystore.b64 > /tmp/battery-key.keystore
$BT/apksigner sign --ks /tmp/battery-key.keystore \
  --ks-pass pass:merlinbattery --key-pass pass:merlinbattery \
  --out $APP/merlin-battery.apk $OUT/aligned.apk
rm -f /tmp/battery-key.keystore

echo "== verify =="
$BT/apksigner verify --verbose $APP/merlin-battery.apk
$BT/aapt2 dump badging $APP/merlin-battery.apk | head -8
ls -lh $APP/merlin-battery.apk
echo "BUILD OK"
