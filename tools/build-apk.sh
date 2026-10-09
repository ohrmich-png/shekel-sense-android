#!/bin/bash
# Manual APK build: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# (Gradle's daemon TCP handshake is blocked in this sandbox; this path uses
# only curl/javac/d8/aapt2/apksigner, which all work.)
set -e
PROJ=~/workspace/shekel-sense-android
SDK=~/workspace/.android-sdk
JH=~/workspace/.jdk/jdk-17.0.20.1+1
export JAVA_HOME=$JH
export PATH=$JH/bin:$PATH
M2=~/workspace/.m2repo
BT=$SDK/build-tools/36.0.0
AJAR=$SDK/platforms/android-36/android.jar
AAPT2=$BT/aapt2
D8=$BT/d8
APKSIGNER=$BT/apksigner
ZIPALIGN=$BT/zipalign
JAVAC=$JH/bin/javac
KEYTOOL=$JH/bin/keytool

APPID=png.ohrmich.shekelsense
BUILD=$PROJ/build/manual
rm -rf $BUILD
mkdir -p $BUILD/{aar,compiled,gen,classes,dex,assets}

echo "== 1. extract AARs =="
> $BUILD/aar-list.txt
while IFS='|' read -r ext g a v path; do
  [ "$ext" = "aar" ] || continue
  d=$BUILD/aar/${a}-${v}
  mkdir -p $d
  unzip -q -o "$path" -d $d
  echo "$d" >> $BUILD/aar-list.txt
done < $M2/artifacts.txt
echo "extracted: $(wc -l < $BUILD/aar-list.txt) AARs"

echo "== 2. aapt2 compile =="
CAPACITOR=$PROJ/node_modules/@capacitor/android/capacitor/src/main
# app res
$AAPT2 compile --dir $PROJ/android/app/src/main/res -o $BUILD/compiled/app.zip
# capacitor library res (its own package: com.getcapacitor.android)
$AAPT2 compile --dir $CAPACITOR/res -o $BUILD/compiled/capacitor.zip
# each AAR res (skip if none)
while read -r d; do
  n=$(basename $d)
  if [ -d "$d/res" ] && [ -n "$(ls -A $d/res 2>/dev/null)" ]; then
    $AAPT2 compile --dir $d/res -o $BUILD/compiled/${n}.zip 2>/dev/null || \
      echo "  (no compilable res in $n)"
  fi
done < $BUILD/aar-list.txt
# merge assets (capacitor's native-bridge.js is required by the WebView bridge)
mkdir -p $BUILD/assets
cp -r $CAPACITOR/assets/* $BUILD/assets/ 2>/dev/null || true
while read -r d; do
  [ -d "$d/assets" ] && cp -r $d/assets/* $BUILD/assets/ 2>/dev/null || true
done < $BUILD/aar-list.txt
echo "assets: $(ls $BUILD/assets | head -5 | tr '\n' ' ')"

echo "== 3. manifest package fix =="
MANIFEST=$BUILD/AndroidManifest.xml
sed -e 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="'"$APPID"'"|' \
    -e 's|\${applicationId}|'"$APPID"'|g' \
  $PROJ/android/app/src/main/AndroidManifest.xml > $MANIFEST
grep -o 'package="[^"]*"' $MANIFEST | head -1

echo "== 4. aapt2 link =="
EXTRAPKGS=""
RARGS=""
for z in $BUILD/compiled/*.zip; do RARGS="$RARGS -R $z"; done
# extra packages = AAR manifest package names + capacitor's own package
for d in $(cat $BUILD/aar-list.txt); do
  m=$(grep -o 'package="[^"]*"' $d/AndroidManifest.xml 2>/dev/null | head -1 | cut -d'"' -f2)
  [ -n "$m" ] && [ "$m" != "$APPID" ] && EXTRAPKGS="$EXTRAPKGS:$m"
done
EXTRAPKGS="$EXTRAPKGS:com.getcapacitor.android"
EXTRAPKGS=${EXTRAPKGS#:}
echo "extra packages: $(echo $EXTRAPKGS | tr ':' ' ' | wc -w)"
$AAPT2 link -o $BUILD/base.apk \
  -I $AJAR \
  --manifest $MANIFEST \
  --min-sdk-version 24 --target-sdk-version 36 \
  --version-code 1 --version-name 1.0 \
  --auto-add-overlay \
  -A $BUILD/assets \
  --java $BUILD/gen \
  ${EXTRAPKGS:+--extra-packages $EXTRAPKGS} \
  $RARGS
echo "R.java files: $(find $BUILD/gen -name R.java | wc -l)"

echo "== 5. javac =="
CP="$AJAR"
# dependency classes
for j in $(find $BUILD/aar -name "classes.jar"); do CP="$CP:$j"; done
for j in $(find $BUILD/aar -path "*/libs/*.jar"); do CP="$CP:$j"; done
while IFS='|' read -r ext g a v path; do
  [ "$ext" = "jar" ] && CP="$CP:$path"
done < $M2/artifacts.txt
find $PROJ/android/app/src/main/java $PROJ/node_modules/@capacitor/android/capacitor/src/main/java \
  $BUILD/gen -name "*.java" > $BUILD/sources.txt
echo "sources: $(wc -l < $BUILD/sources.txt)"
$JAVAC --release 17 -nowarn -encoding UTF-8 -cp "$CP" -d $BUILD/classes @$BUILD/sources.txt
echo "classes: $(find $BUILD/classes -name '*.class' | wc -l)"

echo "== 6. d8 =="
cd $BUILD/classes && $JH/bin/jar cf $BUILD/app-classes.jar . && cd $BUILD
D8INPUTS="$BUILD/app-classes.jar"
for j in $(find $BUILD/aar -name "classes.jar"); do D8INPUTS="$D8INPUTS $j"; done
for j in $(find $BUILD/aar -path "*/libs/*.jar"); do D8INPUTS="$D8INPUTS $j"; done
while IFS='|' read -r ext g a v path; do
  [ "$ext" = "jar" ] || continue
  # Gradle resolves kotlin-stdlib-jdk7/jdk8 as relocations into kotlin-stdlib;
  # including both yields duplicate classes.
  case "$path" in
    *kotlin-stdlib-jdk7*|*kotlin-stdlib-jdk8*) continue;;
  esac
  D8INPUTS="$D8INPUTS $path"
done < $M2/artifacts.txt
$D8 --min-api 24 --lib $AJAR --output $BUILD/dex $D8INPUTS 2>&1 | grep -v "Picked up" | head -5
ls $BUILD/dex/*.dex

echo "== 7. package, align, sign =="
cp $BUILD/base.apk $BUILD/unaligned.apk
# dex files must be STORED (uncompressed) for zipalign + runtime mmap
zip -q -j -0 $BUILD/unaligned.apk $BUILD/dex/classes*.dex
$ZIPALIGN -f 4 $BUILD/unaligned.apk $BUILD/aligned.apk
if [ ! -f $PROJ/debug.keystore ]; then
  $KEYTOOL -genkeypair -keystore $PROJ/debug.keystore -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US" 2>&1 | grep -v "Picked up"
fi
$APKSIGNER sign --ks $PROJ/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out $PROJ/app-debug.apk $BUILD/aligned.apk 2>&1 | grep -v "Picked up"
$APKSIGNER verify --print-certs $PROJ/app-debug.apk 2>&1 | grep -v "Picked up" | head -4
ls -la $PROJ/app-debug.apk
