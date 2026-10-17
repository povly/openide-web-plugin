#!/usr/bin/env bash
# Build web-frontend-mini plugin zip for OpenIDE (IntelliJ-platform fork, build 262).
# Requirements: JDK 21+ (javac), OpenIDE installed at /usr/share/openide.
# Output: dist/web-frontend-mini.zip  (Install Plugin from Disk)
set -euo pipefail

cd "$(dirname "$0")"

OPENIDE_HOME="${OPENIDE_HOME:-/usr/share/openide}"
RELEASE="${RELEASE:-21}"          # class-file target; JBR 25 runs 21 fine, keep margin
VERSION="$(grep -o '<version>[^<]*' resources/META-INF/plugin.xml | head -1 | cut -d'>' -f2)"
OUT="out"
DIST="dist/web-frontend-mini"

CP="$OPENIDE_HOME/plugins/textmate-plugin/lib/modules/*:$OPENIDE_HOME/lib/*"

rm -rf "$OUT" "$DIST" dist/web-frontend-mini.zip
mkdir -p "$OUT" "$DIST/lib"

echo "[1/4] javac --release $RELEASE"
javac --release "$RELEASE" -cp "$CP" -d "$OUT" src/povly/wfm/*.java

echo "[2/4] jar"
printf 'Manifest-Version: 1.0\nCreated-By: openide-web-plugin build.sh (javac --release %s)\n' "$RELEASE" > manifest.mf
jar cfm "$DIST/lib/web-frontend-mini.jar" manifest.mf -C "$OUT" . -C resources .

echo "[3/4] zip"
(cd dist && jar -cMf web-frontend-mini.zip web-frontend-mini)

echo "[4/4] done: dist/web-frontend-mini.zip (plugin v$VERSION)"
unzip -l "dist/web-frontend-mini.zip"
