#!/usr/bin/env bash
# build-and-install.sh — build the androidmcp debug APK and install it to every
# connected device (or a specific one via -s <serial>).
#
# Prerequisites: JDK 17+, an Android SDK (set ANDROID_HOME or sdk.dir in
# local.properties), and adb on PATH. The Gradle wrapper is committed, so no
# separate Gradle install is needed.
#
# Usage:
#   scripts/build-and-install.sh            # build + install to all devices
#   scripts/build-and-install.sh -s <ser>   # install only to that serial
#   scripts/build-and-install.sh -r         # build a release APK instead of debug
#   scripts/build-and-install.sh -b         # build only, do not install
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VARIANT=debug; TASK=assembleDebug; ONLY=""; INSTALL=1
while getopts ":s:rbh" opt; do
  case "$opt" in
    s) ONLY="$OPTARG";;
    r) VARIANT=release; TASK=assembleRelease;;
    b) INSTALL=0;;
    h) grep '^#' "$0" | sed 's/^#\{1,\} \{0,1\}//'; exit 0;;
    \?) echo "unknown option -$OPTARG" >&2; exit 2;;
  esac
done

# --- prerequisites ---
command -v adb >/dev/null || { echo "ERROR: adb not found on PATH." >&2; exit 1; }
command -v java >/dev/null || { echo "ERROR: java (JDK 17+) not found." >&2; exit 1; }
if [ -z "${ANDROID_HOME:-}" ] && ! grep -q '^sdk.dir=' local.properties 2>/dev/null; then
  echo "ERROR: set ANDROID_HOME or put 'sdk.dir=/path/to/Android/Sdk' in local.properties" >&2
  exit 1
fi

echo "==> building $VARIANT APK…"
./gradlew "$TASK" --console=plain

# Name the artifact instead of globbing. `find ... | head -1` would happily pick up
# app-release-unsigned.apk when no signing key is configured, and then `adb install` fails with
# INSTALL_PARSE_FAILED_NO_CERTIFICATES several steps later, blaming the device.
APK="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
if [ ! -f "$APK" ]; then
  UNSIGNED="app/build/outputs/apk/$VARIANT/app-$VARIANT-unsigned.apk"
  if [ -f "$UNSIGNED" ]; then
    echo "ERROR: only an UNSIGNED APK was produced: $UNSIGNED" >&2
    echo "       Android cannot install it. Configure a signing key (ANDROIDMCP_KEYSTORE etc." >&2
    echo "       in ~/.gradle/gradle.properties) — see app/build.gradle.kts." >&2
  else
    echo "ERROR: no APK at $APK" >&2
  fi
  exit 1
fi
echo "==> built: $APK ($(du -h "$APK" | cut -f1))"

if [ "$VARIANT" = "release" ]; then
  # Worth saying out loud: a release build is NOT debuggable, which is the whole reason the
  # variant exists — the debug build is jdwp-attachable by anything with adb, and these devices
  # keep USB debugging on for Shizuku.
  echo "    (release: not debuggable)"
fi

[ "$INSTALL" = 1 ] || { echo "(build only) done."; exit 0; }

# --- devices ---
mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
[ "${#DEVICES[@]}" -gt 0 ] || { echo "No authorized devices connected (check 'adb devices')." >&2; exit 1; }

for serial in "${DEVICES[@]}"; do
  [ -z "$ONLY" ] || [ "$ONLY" = "$serial" ] || continue
  model="$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  echo "==> installing to $serial ($model)…"
  adb -s "$serial" install -r "$APK" | tail -1
done

echo
echo "Done. On the device: open 'androidmcp' → flip the Server switch on →"
echo "'Generate token' → enable the capabilities you want → paste the shown"
echo "'claude mcp add …' command into your client."
