#!/usr/bin/env bash
# Bring back every app removed by debloat.sh and turn animations back on.
# usage: scripts/restore.sh [--dry-run] [--list <file>]   (default list: debloat-packages.txt)
set -euo pipefail
source "$(dirname "$0")/lib.sh"
parse_args "$@"
connect
home="$(resolved_home)"

changed=()
for pkg in $(packages); do
  case "$(package_state "$pkg")" in
    removed)   echo "restoring $pkg"; run cmd package install-existing "$pkg"; changed+=("$pkg") ;;
    installed) echo "already installed: $pkg" ;;
    absent)    echo "not on device:     $pkg (reinstall it from its APK or the Play Store)" ;;
  esac
done
[ "$DRY_RUN" = 1 ] || { wait_packages_saved installed ${changed[@]+"${changed[@]}"}; keep_home "$home"; }

if [ "$PACKAGE_LIST" = "$DEFAULT_PACKAGE_LIST" ]; then
  echo "enabling animations"
  set_animations 1
fi
