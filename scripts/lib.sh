# Shared helpers for the adb scripts. Source it; do not run it.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_PACKAGE_LIST="$SCRIPT_DIR/debloat-packages.txt"
PACKAGE_LIST="${PACKAGE_LIST:-$DEFAULT_PACKAGE_LIST}"
KEEP_LIST="${KEEP_LIST:-$SCRIPT_DIR/keep-packages.txt}"
DRY_RUN=0

# The devices' adb serials are not in git: they come from the environment, else from
# scripts/devices.local (git-ignored; copy scripts/devices.example). SERIAL_NAME is the
# variable a script reads its device from, for the error message.
DEVICES_FILE="${DEVICES_FILE:-$SCRIPT_DIR/devices.local}"
SERIAL_NAME=ADB_SERIAL
load_devices() {
  local env_adb="${ADB_SERIAL:-}" env_server="${SERVER_SERIAL:-}"
  if [ -f "$DEVICES_FILE" ]; then source "$DEVICES_FILE"; fi
  ADB_SERIAL="${env_adb:-${ADB_SERIAL:-}}"
  SERVER_SERIAL="${env_server:-${SERVER_SERIAL:-}}"
}
load_devices

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      -n|--dry-run) DRY_RUN=1 ;;
      --list)
        [ $# -ge 2 ] || { echo "error: --list needs a file" >&2; exit 2; }
        PACKAGE_LIST="$(resolve_list "$2")" || exit 2
        shift ;;
      -h|--help) echo "usage: $0 [-n|--dry-run] [--list <file>]   (ADB_SERIAL=$ADB_SERIAL)"; exit 0 ;;
      *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
  done
}

# A list path as given, or else relative to scripts/ (so `--list debloat-phase2-b.txt` works anywhere).
resolve_list() {
  if [ -f "$1" ]; then echo "$1"
  elif [ -f "$SCRIPT_DIR/$1" ]; then echo "$SCRIPT_DIR/$1"
  else echo "error: package list not found: $1" >&2; return 1
  fi
}

# Package names in a list file (default: $PACKAGE_LIST), without comments and blank lines.
packages() {
  sed -e 's/#.*//' -e 's/[[:space:]]//g' "${1:-$PACKAGE_LIST}" | grep -v '^$' || true
}

# Prints each package in $PACKAGE_LIST that the keep list protects; a keep entry
# ending in .* matches every package with that prefix.
kept_packages() {
  local pkg keep
  for pkg in $(packages); do
    # read, not a for loop over $(...): a for loop would glob-expand "com.x.*" against files.
    while read -r keep; do
      # shellcheck disable=SC2254  # the keep entry is a pattern on purpose
      case "$pkg" in $keep) echo "$pkg"; break ;; esac
    done < <(packages "$KEEP_LIST")
  done
}

# Exits before anything is removed when the list names a kept package.
refuse_kept_packages() {
  local kept
  kept="$(kept_packages)"
  if [ -n "$kept" ]; then
    echo "error: $(basename "$PACKAGE_LIST") lists packages in $(basename "$KEEP_LIST"); nothing removed:" >&2
    echo "$kept" | sed 's/^/  /' >&2
    exit 1
  fi
}

# Reads `dumpsys meminfo <pkg>` on stdin and prints the total PSS in kB; prints
# nothing when the process is not running.
parse_pss_kb() {
  awk '/TOTAL PSS:/ { print $3; exit } /^ *TOTAL +[0-9]/ { print $2; exit }'
}

device() {
  adb -s "$ADB_SERIAL" "$@"
}

# Stops with a clear message when no device serial is set.
require_serial() {
  if [ -z "$ADB_SERIAL" ]; then
    echo "error: no device: set $SERIAL_NAME (see scripts/devices.example)" >&2
    exit 1
  fi
}

connect() {
  require_serial
  case "$ADB_SERIAL" in *:*) adb connect "$ADB_SERIAL" >/dev/null 2>&1 ;; esac
  if [ "$(device get-state 2>/dev/null)" != "device" ]; then
    echo "error: $ADB_SERIAL is not reachable over adb" >&2
    exit 1
  fi
}

# Installed for user 0 → "installed"; present on the system but removed for
# user 0 → "removed"; not on the device at all → "absent".
package_state() {
  if device shell pm list packages "$1" | tr -d '\r' | grep -qx "package:$1"; then
    echo installed
  elif device shell pm list packages -u "$1" | tr -d '\r' | grep -qx "package:$1"; then
    echo removed
  else
    echo absent
  fi
}

run() {
  if [ "$DRY_RUN" = 1 ]; then echo "  [dry-run] adb shell $*"; else device shell "$@"; fi
}

set_animations() {
  for key in window_animation_scale transition_animation_scale animator_duration_scale; do
    run settings put global "$key" "$1"
  done
}

# "package/.Activity" of the activity in the foreground.
resumed_activity() {
  # awk reads all input: stopping early (grep -m1) would SIGPIPE the pipeline under pipefail.
  device shell dumpsys activity activities | tr -d '\r' \
    | awk '/mResumedActivity/ && !seen { seen = 1; print }' | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+'
}

# Reboots the device and waits until it reconnects and finishes booting.
reboot_and_wait() {
  device reboot
  sleep 10
  for _ in $(seq 1 60); do
    case "$ADB_SERIAL" in *:*) adb connect "$ADB_SERIAL" >/dev/null 2>&1 ;; esac
    if [ "$(device shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      return 0
    fi
    sleep 3
  done
  echo "error: $ADB_SERIAL did not come back after reboot" >&2
  return 1
}

# Reads `cmd package resolve-activity --brief` output for HOME on stdin and prints
# the "package/activity" Android would start (the last line naming a component).
parse_resolved_home() {
  awk '/\// { last = $0 } END { if (last != "") print last }'
}

# "package/activity" of the current default home screen.
resolved_home() {
  device shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tr -d '\r' | parse_resolved_home
}

# Reads package-restrictions.xml on stdin and prints the component of the saved
# (preferred) HOME activity; prints nothing when no home preference is saved.
saved_home_component() {
  awk '
    /<preferred-activities>/ { inside = 1 }
    /<\/preferred-activities>/ { inside = 0 }
    inside && /<item name="/ { split($0, a, "\""); item = a[2] }
    inside && /<\/item>/ { item = "" }
    inside && item != "" && /name="android.intent.category.HOME"/ { print item; exit }
  '
}

HOME_SAVE_TIMEOUT="${HOME_SAVE_TIMEOUT:-30}"

# Reads package-restrictions.xml on stdin and prints the packages removed for the
# user (inst="false"), one per line.
saved_removed_packages() {
  awk '/<pkg name="/ && / inst="false"/ { split($0, a, "\""); print a[2] }'
}

# wait_packages_saved <removed|installed> <package>...: waits until Android has written
# every package's state to disk. The write lags ~10 s behind pm, and a reboot before it
# undoes the change (this is what brought phase 2 groups B, A and D back on 2026-10-29).
wait_packages_saved() {
  local want="$1" waited=0 pending pkg removed
  shift
  [ $# -gt 0 ] || return 0
  while :; do
    removed="$(device shell su 0 cat /data/system/users/0/package-restrictions.xml | tr -d '\r' | saved_removed_packages)"
    pending=""
    for pkg in "$@"; do
      if printf '%s\n' "$removed" | grep -qx "$pkg"; then
        [ "$want" = removed ] || pending="$pending $pkg"
      else
        [ "$want" = installed ] || pending="$pending $pkg"
      fi
    done
    if [ -z "$pending" ]; then echo "saved to disk ($want): $*"; return 0; fi
    if [ "$waited" -ge "$HOME_SAVE_TIMEOUT" ]; then
      echo "error: not saved after ${HOME_SAVE_TIMEOUT}s:$pending; do not reboot yet" >&2
      return 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
}

# Waits until Android has written <package> as the home preference to disk: a reboot
# before that write (it lags a few seconds) brings the previous home back.
wait_home_saved() {
  local pkg="$1" saved="" waited=0
  while [ "$waited" -lt "$HOME_SAVE_TIMEOUT" ]; do
    saved="$(device shell su 0 cat /data/system/users/0/package-restrictions.xml | tr -d '\r' | saved_home_component)"
    case "$saved" in "$pkg"/*) echo "home preference saved: $saved"; return 0 ;; esac
    sleep 2
    waited=$((waited + 2))
  done
  echo "error: home preference not saved after ${HOME_SAVE_TIMEOUT}s (saved: ${saved:-none}); do not reboot yet" >&2
  return 1
}

# Prints <component> when it names a real default home; nothing for Android's
# "choose a home app" picker (no default set).
real_home() {
  case "$1" in
    */*ResolverActivity | "") ;;
    */*) echo "$1" ;;
  esac
}

# keep_home <component from before a change>: sets it as home again if the change
# (an install, or a debloat stage on 2026-10-29) made Android forget it, and waits
# until that is saved.
keep_home() {
  local before now
  before="$(real_home "$1")"
  [ -n "$before" ] || return 0
  now="$(resolved_home)"
  [ "$now" = "$before" ] && return 0
  echo "home screen was reset (now: ${now:-none}); setting $before again"
  device shell cmd package set-home-activity "$before" >/dev/null
  wait_home_saved "${before%%/*}"
}

# Makes <package/activity> the default home screen, checks it is in front, and waits
# until the preference is on disk so it survives a reboot.
set_home() {
  local component="$1"
  device shell cmd package set-home-activity "$component" >/dev/null
  device shell input keyevent KEYCODE_HOME
  sleep 3
  local now
  now="$(resumed_activity)"
  case "$now" in
    "${component%%/*}"/*) echo "home screen: $now" ;;
    *) echo "error: expected ${component%%/*} in front, found ${now:-nothing}" >&2; return 1 ;;
  esac
  wait_home_saved "${component%%/*}"
}
