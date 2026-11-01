#!/usr/bin/env bash
# Build Candle, install it on the projector, grant all-files access and launch it.
# usage: scripts/deploy.sh [--release] [--no-build]   (ADB_SERIAL overrides the device)
# Measure memory on --release builds: debug builds run unshrunk code straight from the APK.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

PKG=com.uminocelo.candle
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VARIANT=debug
BUILD=1
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --release) VARIANT=release ;;
    -h|--help) echo "usage: $0 [--release] [--no-build]   (ADB_SERIAL=$ADB_SERIAL)"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

require_serial  # before a build that would be wasted

APK="$ROOT_DIR/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
if [ "$BUILD" = 1 ]; then
  task="assemble$(tr '[:lower:]' '[:upper:]' <<<"${VARIANT:0:1}")${VARIANT:1}"
  (cd "$ROOT_DIR" && ./gradlew --quiet "$task")
fi
[ -f "$APK" ] || { echo "error: $APK not found; build first" >&2; exit 1; }

connect
# Reinstalling a home-capable app makes Android forget the default home screen,
# so remember it and set it again after the install.
home="$(resolved_home)"
echo "installing $(basename "$APK") ($(du -h "$APK" | cut -f1))"
device install -r "$APK" >/dev/null
keep_home "$home"

# The HY300 has no system folder picker, so storage access comes from appops instead of SAF.
device shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
mode="$(device shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE | tr -d '\r')"
case "$mode" in
  *allow*) echo "all-files access: allow" ;;
  *) echo "error: all-files access not granted: $mode" >&2; exit 1 ;;
esac

# The phone remote: its PIN overlay, and its "home" command starting Candle from the background.
device shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
case "$(device shell appops get "$PKG" SYSTEM_ALERT_WINDOW | tr -d '\r')" in
  *allow*) echo "draw over apps: allow" ;;
  *) echo "error: SYSTEM_ALERT_WINDOW app op not allowed" >&2; exit 1 ;;
esac

# The settings hub's quick settings: Global/Secure tables, and the System table.
device shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
device shell appops set "$PKG" WRITE_SETTINGS allow
case "$(device shell dumpsys package "$PKG" | tr -d '\r')" in
  *"WRITE_SECURE_SETTINGS: granted=true"*) echo "write secure settings: granted" ;;
  *) echo "error: WRITE_SECURE_SETTINGS not granted" >&2; exit 1 ;;
esac
case "$(device shell appops get "$PKG" WRITE_SETTINGS | tr -d '\r')" in
  *allow*) echo "write settings: allow" ;;
  *) echo "error: WRITE_SETTINGS app op not allowed" >&2; exit 1 ;;
esac

device shell am start -n "$PKG/.MainActivity" >/dev/null
echo "launched $PKG"
