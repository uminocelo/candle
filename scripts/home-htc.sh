#!/usr/bin/env bash
# Make the stock HTC launcher the home screen again (the way back from home-candle.sh).
set -euo pipefail
source "$(dirname "$0")/lib.sh"
connect

HTC=com.htc.htclauncherhighenglishd08
if [ "$(package_state "$HTC")" = removed ]; then
  echo "restoring $HTC"
  device shell cmd package install-existing "$HTC" >/dev/null
fi
set_home "$HTC/com.htc.activity.HomeActivity"
