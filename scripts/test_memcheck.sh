#!/usr/bin/env bash
# Offline tests for memcheck.sh parsing and budget check. Run: scripts/test_memcheck.sh
set -uo pipefail
source "$(dirname "$0")/memcheck.sh"
set +e  # memcheck.sh enables errexit; these tests expect failing return codes
fails=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$3], got [$2]"; fails=$((fails+1)); fi; }

table_output='** MEMINFO in pid 4321 [com.uminocelo.candle] **
                   Pss  Private  Private  SwapPss     Rss     Heap     Heap     Heap
                 Total    Dirty    Clean    Dirty   Total     Size    Alloc     Free
        TOTAL    27338    16696     3144        0   27338    12586     8878     3707

 App Summary
           TOTAL PSS:    27338            TOTAL RSS:    61234       TOTAL SWAP PSS:        0'
summary_only='           TOTAL PSS:    31002            TOTAL RSS:    70000'
not_running='No process found for: com.uminocelo.candle'

check "parses PSS from the TOTAL row"        "$(parse_pss_kb <<<"$table_output")" "27338"
check "parses PSS from the App Summary line" "$(parse_pss_kb <<<"$summary_only")" "31002"
check "prints nothing when not running"      "$(parse_pss_kb <<<"$not_running")" ""

check_budget 30000 35840 >/dev/null; check "under the limit passes"   "$?" "0"
check_budget 35840 35840 >/dev/null; check "at the limit fails"       "$?" "1"
check_budget 40000 35840 >/dev/null; check "over the limit fails"     "$?" "1"
check_budget ""    35840 >/dev/null; check "not running fails with 2" "$?" "2"
check "failure names the budget" "$(check_budget 40000 35840)" "FAIL: PSS 40000 kB is not below the 35840 kB budget"

[ "$fails" = 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
