#!/usr/bin/env bash
# Đóng gói APK không cần Android Studio/Gradle (dùng aapt2 + javac + dx + apksigner).
# Cần: JDK 17+, và các công cụ trỏ bằng biến môi trường:
#   ANDROID_JAR  = .../platforms/android-33/android.jar
#   AAPT2        = .../build-tools/<ver>/aapt2
#   DX_JAR       = dx.jar (hoặc dùng D8_JAR = r8.jar từ build-tools/lib/d8.jar)
#   APKSIGNER_JAR= .../build-tools/<ver>/lib/apksigner.jar
#   KEYSTORE / KS_PASS / KEY_ALIAS  (khoá ký phát hành — GIỮ CẨN THẬN, cập nhật sau phải cùng khoá)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/app"; OUT="${OUT:-$HERE/build}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"; VERSION_CODE="${VERSION_CODE:-1}"
MIN_SDK=23; TARGET_SDK=33
rm -rf "$OUT" && mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --java "$OUT/gen" "$OUT/res.zip"
find "$APP/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
if [ -n "${D8_JAR:-}" ]; then
  java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api $MIN_SDK --lib "$ANDROID_JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class')
else
  java -jar "$DX_JAR" --dex --min-sdk-version=$MIN_SDK --output="$OUT/classes.dex" "$OUT/classes"
fi
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)
python3 "$HERE/zipalign.py" "$OUT/unsigned.apk" "$OUT/aligned.apk"
java -jar "$APKSIGNER_JAR" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "${KEY_ALIAS:-xanh24}" \
  --min-sdk-version $MIN_SDK --out "$OUT/Xanh24-Kiosk-$VERSION_NAME.apk" "$OUT/aligned.apk"
java -jar "$APKSIGNER_JAR" verify --verbose "$OUT/Xanh24-Kiosk-$VERSION_NAME.apk"
echo "APK: $OUT/Xanh24-Kiosk-$VERSION_NAME.apk"
