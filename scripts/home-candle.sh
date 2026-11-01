#!/usr/bin/env bash
# Make Candle the projector's home screen. Undo with scripts/home-htc.sh.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
connect

if [ "$(package_state com.uminocelo.candle)" != installed ]; then
  echo "error: Candle is not installed; run scripts/deploy.sh first" >&2
  exit 1
fi
set_home com.uminocelo.candle/.MainActivity
