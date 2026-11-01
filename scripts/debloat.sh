#!/usr/bin/env bash
# Remove unneeded apps for user 0 and turn off animations.
# usage: scripts/debloat.sh [--dry-run] [--list <file>]   (default list: debloat-packages.txt)
# Refuses any list that names a package in keep-packages.txt.
# Reversible with restore.sh; a factory reset also brings everything back.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
parse_args "$@"
refuse_kept_packages
connect
home="$(resolved_home)"

changed=()
for pkg in $(packages); do
  case "$(package_state "$pkg")" in
    installed) echo "removing $pkg"; run pm uninstall --user 0 "$pkg"; changed+=("$pkg") ;;
    removed)   echo "already removed: $pkg" ;;
    absent)    echo "not on device:   $pkg" ;;
  esac
done
# Rebooting before Android writes the removals to disk brings the apps back.
[ "$DRY_RUN" = 1 ] || { wait_packages_saved removed ${changed[@]+"${changed[@]}"}; keep_home "$home"; }

# Phase 2 lists only remove packages; the animation setting lives in the settings hub too.
if [ "$PACKAGE_LIST" = "$DEFAULT_PACKAGE_LIST" ]; then
  echo "disabling animations"
  set_animations 0
fi
