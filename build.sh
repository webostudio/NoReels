#!/usr/bin/env bash
# Zostaví NoReels.apk. Build beží v C:\Users\Hp\AndroidTools\build (cesta bez diakritiky).
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
T="$HOME/AndroidTools"
export JAVA_HOME="$T/jdk-17.0.21+9"; export PATH="$JAVA_HOME/bin:$PATH"
B="$T/build/NoReels"
mkdir -p "$B" && rm -rf "$B/app/src"
cp -r "$SRC/app" "$SRC/settings.gradle" "$SRC/build.gradle" "$SRC/gradle.properties" "$B"/
echo "sdk.dir=$(cygpath -m "$T/sdk")" > "$B/local.properties"
(cd "$B" && "$T/gradle-8.11.1/bin/gradle" assembleRelease --console=plain -q)
cp "$B/app/build/outputs/apk/release/app-release.apk" "$SRC/NoReels.apk"
echo "Hotovo: $SRC/NoReels.apk"
