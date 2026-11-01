#!/usr/bin/env bash
# Reboot the projector, let Candle settle on the home screen, and check its memory
# against the budget (below the HTC launcher's ~41 MB).
# usage: scripts/memcheck.sh [--no-reboot] [--limit-mb N]
set -euo pipefail
source "$(dirname "$0")/lib.sh"

PKG=com.uminocelo.candle
LIMIT_KB=$((35 * 1024))
SETTLE_SECONDS=20

# check_budget <pss_kb> <limit_kb>: 0 when below the limit, 1 at or above it,
# 2 when there is no PSS value (process not running).
check_budget() {
  local pss="$1" limit="$2"
  if [ -z "$pss" ]; then
    echo "FAIL: $PKG is not running"
    return 2
  elif [ "$pss" -ge "$limit" ]; then
    echo "FAIL: PSS ${pss} kB is not below the ${limit} kB budget"
    return 1
  fi
  echo "OK: PSS ${pss} kB is below the ${limit} kB budget"
}

main() {
  local reboot=1
  while [ $# -gt 0 ]; do
    case "$1" in
      --no-reboot) reboot=0 ;;
      --limit-mb) LIMIT_KB=$(($2 * 1024)); shift ;;
      -h|--help) echo "usage: $0 [--no-reboot] [--limit-mb N]   (ADB_SERIAL=$ADB_SERIAL)"; exit 0 ;;
      *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
  done

  connect
  if [ "$reboot" = 1 ]; then
    echo "rebooting ${ADB_SERIAL}..."
    reboot_and_wait
  fi

  # Measure Candle as the home screen; start it if it is not the default home.
  device shell input keyevent KEYCODE_HOME
  sleep 3
  case "$(resumed_activity)" in
    "$PKG"/*) ;;
    *) device shell am start -n "$PKG/.MainActivity" >/dev/null ;;
  esac
  sleep "$SETTLE_SECONDS"

  local pss available
  pss="$(device shell dumpsys meminfo "$PKG" | tr -d '\r' | parse_pss_kb)"
  available="$(device shell grep MemAvailable /proc/meminfo | tr -d '\r' | awk '{ print $2 }')"
  echo "candle PSS:   ${pss:-n/a} kB"
  echo "MemAvailable: ${available} kB"
  check_budget "$pss" "$LIMIT_KB"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
