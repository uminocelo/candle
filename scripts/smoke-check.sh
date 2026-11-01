#!/usr/bin/env bash
# Reboot the projector and check the things a debloat stage must not break:
# Candle is home, HDMI input and projector settings open, the Play Store opens and
# YouTube launches. Prints MemAvailable and Candle's PSS.
# usage: scripts/smoke-check.sh [--no-reboot] [--before <MemAvailable kB>]   (HOME_PACKAGE overrides the home check)
#   Run it once before a stage, then pass that MemAvailable as --before afterwards.
# Exits non-zero naming the first failing check.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

PKG=com.uminocelo.candle
# Empty means the device's default home (Candle since 2026-10-29); set it to require a specific home.
HOME_PACKAGE="${HOME_PACKAGE:-}"
SETTLE_SECONDS=20
OPEN_SECONDS=4

# check_foreground <name> <expected packages, space-separated> <resumed "pkg/activity">:
# prints the result line; returns 1 when another app (or nothing) is in front.
check_foreground() {
  local name="$1" expected="$2" resumed="$3" pkg
  for pkg in $expected; do
    case "$resumed" in "$pkg"/*) echo "ok   $name ($resumed)"; return 0 ;; esac
  done
  echo "FAIL: $name: expected ${expected// / or } in front, found ${resumed:-nothing}"
  return 1
}

# Reads /proc/meminfo on stdin and prints MemAvailable in kB.
parse_mem_available() {
  awk '/^MemAvailable:/ { print $2; exit }'
}

# memory_report <after kB> [before kB]: the stage's memory line.
memory_report() {
  local after="$1" before="${2:-}"
  if [ -n "$before" ]; then
    echo "MemAvailable: before ${before} kB, after ${after} kB ($((after - before)) kB)"
  else
    echo "MemAvailable: ${after} kB (pass --before ${after} to the next run)"
  fi
}

# open_and_check <name> <expected package> <am start args...>
open_and_check() {
  local name="$1" expected="$2"
  shift 2
  device shell am start "$@" >/dev/null 2>&1 || true
  sleep "$OPEN_SECONDS"
  check_foreground "$name" "$expected" "$(resumed_activity)"
  device shell input keyevent KEYCODE_HOME
  sleep 2
}

main() {
  local reboot=1 before=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --no-reboot) reboot=0 ;;
      --before) before="${2:?--before needs a kB value}"; shift ;;
      -h|--help) echo "usage: $0 [--no-reboot] [--before <kB>]   (ADB_SERIAL=$ADB_SERIAL)"; exit 0 ;;
      *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
  done

  connect
  if [ "$reboot" = 1 ]; then
    echo "rebooting ${ADB_SERIAL}..."
    reboot_and_wait
  fi
  sleep "$SETTLE_SECONDS"

  local home="$HOME_PACKAGE"
  [ -n "$home" ] || home="$(real_home "$(resolved_home)")"
  if [ -z "$home" ]; then
    echo "FAIL: no default home screen (Android shows its picker); run scripts/home-candle.sh"
    return 1
  fi
  home="${home%%/*}"
  device shell input keyevent KEYCODE_HOME
  sleep 3
  check_foreground "home screen is ${home:-unknown}" "${home:-unknown}" "$(resumed_activity)"

  # AwSource hands the HDMI picture straight to AwLiveTv.
  open_and_check "HDMI input opens" "com.softwinner.awsource com.softwinner.awlivetv" -n com.softwinner.awsource/.MainActivity
  open_and_check "projector settings open" com.htc.htcsettings -a action.htcsettings.prjsettings
  open_and_check "Play Store opens" com.android.vending \
    -a android.intent.action.VIEW -d "market://details?id=com.google.android.youtube.tv"
  open_and_check "YouTube launches" com.google.android.youtube.tv \
    -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p com.google.android.youtube.tv

  local available pss
  available="$(device shell cat /proc/meminfo | tr -d '\r' | parse_mem_available)"
  pss="$(device shell dumpsys meminfo "$PKG" | tr -d '\r' | parse_pss_kb)"
  echo "Candle PSS: ${pss:-n/a} kB"
  memory_report "$available" "$before"
  echo "all checks passed"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
