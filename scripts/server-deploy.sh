#!/usr/bin/env bash
# Build Candle Server, install it on the phone over wireless debugging, grant its access and launch it.
# usage: scripts/server-deploy.sh [--release] [--no-build]   (SERVER_SERIAL overrides the phone)
# SERVER_SERIAL is best the phone's wireless-debugging name (`adb mdns services`), which stays
# the same when its port changes; adb finds it once Wireless debugging is on and the phone was paired.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
ADB_SERIAL="$SERVER_SERIAL"
SERIAL_NAME=SERVER_SERIAL

PKG=com.uminocelo.candle.server
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VARIANT=debug
BUILD=1
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --release) VARIANT=release ;;
    -h|--help) echo "usage: $0 [--release] [--no-build]   (SERVER_SERIAL=$ADB_SERIAL)"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

require_serial  # before a build that would be wasted

APK="$ROOT_DIR/server/build/outputs/apk/$VARIANT/server-$VARIANT.apk"
if [ "$BUILD" = 1 ]; then
  task=":server:assemble$(tr '[:lower:]' '[:upper:]' <<<"${VARIANT:0:1}")${VARIANT:1}"
  (cd "$ROOT_DIR" && ./gradlew --quiet "$task")
fi
[ -f "$APK" ] || { echo "error: $APK not found; build first" >&2; exit 1; }

connect
echo "installing $(basename "$APK") ($(du -h "$APK" | cut -f1))"
device install -r "$APK" >/dev/null

# Documents are not media, so the server reads the shared folders with all-files access.
device shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
case "$(device shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE | tr -d '\r')" in
  *allow*) echo "all-files access: allow" ;;
  *) echo "error: all-files access not granted" >&2; exit 1 ;;
esac

# Samsung puts unused apps to sleep; the server must keep running with the screen off.
device shell dumpsys deviceidle whitelist "+$PKG" >/dev/null
case "$(device shell dumpsys deviceidle whitelist | tr -d '\r')" in
  *"$PKG"*) echo "battery optimisation: exempt" ;;
  *) echo "error: battery exemption not applied" >&2; exit 1 ;;
esac
device shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

device shell am start -n "$PKG/.SettingsActivity" >/dev/null
echo "launched $PKG"
