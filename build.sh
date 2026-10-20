#!/usr/bin/env bash
# Build web-frontend-mini plugin zip for OpenIDE (IntelliJ-platform fork, build 262).
# Requirements: JDK 21+ (javac), OpenIDE installed at /usr/share/openide.
# Output: dist/web-frontend-mini.zip  (Install Plugin from Disk)
set -euo pipefail

cd "$(dirname "$0")"

# --- test target: plain-java harness for pure logic; not part of the default build ---
if [ "${1:-}" = "test" ]; then
  OUT_TEST=out-test
  rm -rf "$OUT_TEST" && mkdir -p "$OUT_TEST"
  javac --release 21 -d "$OUT_TEST" src/povly/wfm/WfmCssModules.java tests/povly/wfm/WfmCssModulesTest.java
  java -ea -cp "$OUT_TEST" povly.wfm.WfmCssModulesTest
  exit $?
fi

OPENIDE_HOME="${OPENIDE_HOME:-/usr/share/openide}"
RELEASE="${RELEASE:-21}"          # class-file target; JBR 25 runs 21 fine, keep margin
VERSION="$(grep -o '<version>[^<]*' resources/META-INF/plugin.xml | head -1 | cut -d'>' -f2)"
OUT="out"
DIST="dist/web-frontend-mini"

CP="$OPENIDE_HOME/plugins/textmate-plugin/lib/modules/*:$OPENIDE_HOME/lib/*"

rm -rf "$OUT" "$DIST" dist/web-frontend-mini.zip
mkdir -p "$OUT" "$DIST/lib"

echo "[0/4] plugin.xml + resources sanity"
python3 -c "import sys, xml.etree.ElementTree as ET; ET.parse('resources/META-INF/plugin.xml')" \
  || { echo "FATAL: resources/META-INF/plugin.xml is not valid XML"; exit 1; }
# Every bundled XML (tmPreferences) and JSON (grammars) must parse — v1.13.0 shipped a
# tmPreferences with </key> instead of </string> and broke the bundle registration.
python3 - <<'VALIDATE' || { echo "FATAL: bundled resources failed XML/JSON validation"; exit 1; }
import glob, json, sys, xml.etree.ElementTree as ET
bad = []
for f in glob.glob('resources/bundle/**/*.tmPreferences', recursive=True) + \
         glob.glob('resources/bundle/**/*.plist', recursive=True) + \
         glob.glob('resources/colorScheme/*.xml'):
    try:
        ET.parse(f)
    except Exception as e:
        print(f'BAD XML {f}: {e}'); bad.append(f)
for f in glob.glob('resources/bundle/Syntaxes/*.json'):
    try:
        json.load(open(f))
    except Exception as e:
        print(f'BAD JSON {f}: {e}'); bad.append(f)
sys.exit(1 if bad else 0)
VALIDATE

echo "[1/4] javac --release $RELEASE"
javac --release "$RELEASE" -cp "$CP" -d "$OUT" src/povly/wfm/*.java

echo "[2/4] jar"
printf 'Manifest-Version: 1.0\nCreated-By: openide-web-plugin build.sh (javac --release %s)\n' "$RELEASE" > manifest.mf
jar cfm "$DIST/lib/web-frontend-mini.jar" manifest.mf -C "$OUT" . -C resources .

echo "[3/4] zip"
(cd dist && jar -cMf web-frontend-mini.zip web-frontend-mini)

echo "[4/4] done: dist/web-frontend-mini.zip (plugin v$VERSION)"
unzip -l "dist/web-frontend-mini.zip"
