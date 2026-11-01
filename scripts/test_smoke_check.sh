#!/usr/bin/env bash
# Offline tests for smoke-check.sh result parsing. Run: scripts/test_smoke_check.sh
set -uo pipefail
source "$(dirname "$0")/smoke-check.sh"
set +e  # smoke-check.sh enables errexit; these tests expect failing return codes
fails=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$3], got [$2]"; fails=$((fails+1)); fi; }

check "foreground match passes" "$(check_foreground "YouTube launches" com.google.android.youtube.tv com.google.android.youtube.tv/.Main)" \
  "ok   YouTube launches (com.google.android.youtube.tv/.Main)"
check_foreground "x" com.a com.a/.Main >/dev/null; check "match returns 0" "$?" "0"
check "any of several packages passes" "$(check_foreground "HDMI input opens" "com.softwinner.awsource com.softwinner.awlivetv" com.softwinner.awlivetv/.MainActivity)" \
  "ok   HDMI input opens (com.softwinner.awlivetv/.MainActivity)"
check "failure names the check and what was in front" "$(check_foreground "Play Store opens" com.android.vending com.htc.htclauncherhighenglishd08/com.htc.activity.HomeActivity)" \
  "FAIL: Play Store opens: expected com.android.vending in front, found com.htc.htclauncherhighenglishd08/com.htc.activity.HomeActivity"
check_foreground "x" com.a com.b/.Main >/dev/null; check "mismatch returns 1" "$?" "1"
check "nothing in front fails" "$(check_foreground "home screen is Candle" com.uminocelo.candle "")" \
  "FAIL: home screen is Candle: expected com.uminocelo.candle in front, found nothing"
check "a package prefix is not a match" "$(check_foreground "x" com.a com.ab/.Main | cut -c1-5)" "FAIL:"
check "several expected packages are listed with or" "$(check_foreground "HDMI" "com.x com.y" com.z/.A)" \
  "FAIL: HDMI: expected com.x or com.y in front, found com.z/.A"

meminfo='MemTotal:         954312 kB
MemFree:           43120 kB
MemAvailable:     459660 kB'
check "parses MemAvailable" "$(parse_mem_available <<<"$meminfo")" "459660"
check "no MemAvailable prints nothing" "$(parse_mem_available <<<"MemTotal: 1 kB")" ""
check "report with a baseline shows the change" "$(memory_report 430540 450000)" "MemAvailable: before 450000 kB, after 430540 kB (-19460 kB)"
check "report without a baseline suggests one" "$(memory_report 430540)" "MemAvailable: 430540 kB (pass --before 430540 to the next run)"

[ "$fails" = 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
