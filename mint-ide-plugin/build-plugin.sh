#!/usr/bin/env bash
set -euo pipefail

PLUGIN_ROOT="$(cd "$(dirname "$0")" && pwd)"
MINTGRAM_ROOT="$(cd "$PLUGIN_ROOT/.." && pwd)"
WORKTREE="$MINTGRAM_ROOT/worktree"
BUILD="$PLUGIN_ROOT/build"
DIST="$PLUGIN_ROOT/dist"
VENDOR="$PLUGIN_ROOT/vendor"

ANDROID_JAR="/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar"
D8="/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0/d8"
R8_JAR="/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/lib/r8.jar"
ALIUHOOK_JAR="/Users/lu/.gradle/caches/transforms-4/d0b1a5072dc621e78a514371e336d622/transformed/Aliuhook-1.1.4-api.jar"
CHAQUOPY_JAR="/Users/lu/.gradle/caches/modules-2/files-2.1/com.chaquo.python.runtime/chaquopy_java/17.0.0/cf4bf5d25689063eb96195194dcb16b01c3ae599/chaquopy_java-17.0.0.jar"
FRAGMENT_JAR="/Users/lu/.gradle/caches/transforms-4/2f96783efad2b75e6f3da429db90ed40/transformed/fragment-1.8.9-api.jar"
ACTIVITY_JAR="/Users/lu/.gradle/caches/transforms-4/4bb804c79a329376826065cf8b505383/transformed/activity-1.8.1-api.jar"
ANDROIDX_CORE_JAR="/Users/lu/.gradle/caches/transforms-4/c20abafd648d1a9d04b2053fae0464aa/transformed/core-1.16.0-api.jar"
LIFECYCLE_JAR="/Users/lu/.gradle/caches/modules-2/files-2.1/androidx.lifecycle/lifecycle-common/2.6.2/10f354fdb64868baecd67128560c5a0d6312c495/lifecycle-common-2.6.2.jar"
ANNOTATION_JAR="/Users/lu/.gradle/caches/modules-2/files-2.1/androidx.annotation/annotation-jvm/1.8.2/b8a16fe526014b7941c1debaccaf9c5153692dbb/annotation-jvm-1.8.2.jar"
ECJ_VERSION="3.26.0"
ECJ_JAR="$VENDOR/ecj-$ECJ_VERSION.jar"

if [[ ! -f "$ANDROID_JAR" || ! -x "$D8" || ! -f "$R8_JAR" \
  || ! -f "$ALIUHOOK_JAR" || ! -f "$CHAQUOPY_JAR" ]]; then
  echo "Android SDK build tools were not found." >&2
  exit 1
fi

mkdir -p "$BUILD" "$DIST" "$VENDOR"
if [[ ! -f "$ECJ_JAR" ]]; then
  curl -L --fail --silent --show-error \
    "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/$ECJ_VERSION/ecj-$ECJ_VERSION.jar" \
    -o "$ECJ_JAR"
fi

rm -rf "$BUILD/sdk-classes" "$BUILD/engine-api-classes" "$BUILD/ide-classes" "$BUILD/ide-dex" \
  "$BUILD/compiler-dex" "$BUILD/compiler-package" "$BUILD/package"
mkdir -p "$BUILD/sdk-classes" "$BUILD/engine-api-classes" "$BUILD/ide-classes" "$BUILD/ide-dex" \
  "$BUILD/compiler-dex" "$BUILD/compiler-package" \
  "$BUILD/package/assets/compiler" "$BUILD/package/assets/sdk"

find "$PLUGIN_ROOT/sdk-stubs" -name '*.java' -print0 \
  | xargs -0 javac -source 8 -target 8 -encoding UTF-8 \
      -classpath "$ANDROID_JAR" -d "$BUILD/sdk-classes"
jar cf "$BUILD/mintgram-api.jar" -C "$BUILD/sdk-classes" .

APP_JAVA="$WORKTREE/TMessagesProj_App/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
APP_KOTLIN="$WORKTREE/TMessagesProj_App/build/tmp/kotlin-classes/debug"
TG_JAVA="$WORKTREE/TMessagesProj/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
TG_KOTLIN="$WORKTREE/TMessagesProj/build/tmp/kotlin-classes/debug"
TELEGRAM_R="$WORKTREE/TMessagesProj/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar"
PROJECT_CLASSPATH="$ANDROID_JAR:$ALIUHOOK_JAR:$CHAQUOPY_JAR:$FRAGMENT_JAR:$ACTIVITY_JAR:$ANDROIDX_CORE_JAR:$LIFECYCLE_JAR:$ANNOTATION_JAR:$TELEGRAM_R:$APP_JAVA:$APP_KOTLIN:$TG_JAVA:$TG_KOTLIN"

javac -source 8 -target 8 -encoding UTF-8 -classpath "$PROJECT_CLASSPATH" \
  -d "$BUILD/engine-api-classes" \
  "$WORKTREE/TMessagesProj_App/src/main/java/desu/mintgram/plugins/jvm/JvmPluginAdapter.java" \
  "$WORKTREE/TMessagesProj_App/src/main/java/desu/mintgram/plugins/jvm/JvmPluginContext.java" \
  "$WORKTREE/TMessagesProj_App/src/main/java/desu/mintgram/plugins/jvm/JvmActionSetting.java" \
  "$WORKTREE/TMessagesProj_App/src/main/java/desu/mintgram/plugins/jvm/JvmSettings.java"
jar cf "$BUILD/engine-api-overlay.jar" -C "$BUILD/engine-api-classes" .

COMPILE_CLASSPATH="$BUILD/engine-api-overlay.jar:$PROJECT_CLASSPATH:$BUILD/mintgram-api.jar"

find "$PLUGIN_ROOT/src" -name '*.java' -print0 \
  | xargs -0 javac -source 8 -target 8 -encoding UTF-8 \
      -classpath "$COMPILE_CLASSPATH" -d "$BUILD/ide-classes"

jar cf "$BUILD/ide-classes.jar" -C "$BUILD/ide-classes" .
"$D8" --min-api 26 --release --lib "$ANDROID_JAR" \
  --output "$BUILD/ide-dex" "$BUILD/ide-classes.jar"

"$D8" --min-api 26 --release --lib "$ANDROID_JAR" \
  --output "$BUILD/compiler-dex" "$ECJ_JAR" "$R8_JAR"
cp "$BUILD/compiler-dex/"*.dex "$BUILD/compiler-package/"
unzip -q -o "$ECJ_JAR" '*.rsc' '*.properties' -d "$BUILD/compiler-package"
jar cf "$BUILD/compiler.dex.jar" -C "$BUILD/compiler-package" .

cp "$PLUGIN_ROOT/plugin.json" "$BUILD/package/plugin.json"
cp "$BUILD/ide-dex/classes.dex" "$BUILD/package/classes.dex"
cp "$BUILD/compiler.dex.jar" "$BUILD/package/assets/compiler/compiler.dex.jar"
cp "$ANDROID_JAR" "$BUILD/package/assets/sdk/android.jar"
cp "$BUILD/mintgram-api.jar" "$BUILD/package/assets/sdk/mintgram-api.jar"

(
  cd "$BUILD/package"
  zip -q -r "$DIST/mint_ide.plugin" plugin.json classes.dex assets
)

PACKAGE_SIZE="$(stat -f%z "$DIST/mint_ide.plugin")"
if (( PACKAGE_SIZE > 67108864 )); then
  echo "Plugin package exceeds the JVM engine 64 MB limit." >&2
  exit 1
fi

echo "$DIST/mint_ide.plugin"
