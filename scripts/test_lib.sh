#!/usr/bin/env bash
# Offline tests for lib.sh (no device needed). Run: scripts/test_lib.sh
set -uo pipefail
source "$(dirname "$0")/lib.sh"
fails=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$3], got [$2]"; fails=$((fails+1)); fi; }

tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
printf '# header\n\ncom.a   # comment\n  com.b\t\ncom.c#tight\n' > "$tmp"
check "packages skips comments, blanks, whitespace" "$(PACKAGE_LIST="$tmp" packages | tr '\n' ' ')" "com.a com.b com.c "
check "real package list has 8 entries" "$(packages | wc -l | tr -d ' ')" "8"

DRY_RUN=0; parse_args --dry-run; check "--dry-run sets DRY_RUN" "$DRY_RUN" "1"
DRY_RUN=0; parse_args -n;        check "-n sets DRY_RUN" "$DRY_RUN" "1"
(parse_args --bogus 2>/dev/null); check "unknown option exits 2" "$?" "2"
check "dry-run prints instead of running" "$(DRY_RUN=1 run pm uninstall x)" "  [dry-run] adb shell pm uninstall x"

# --list
check "--list finds a name relative to scripts/" "$(cd / && parse_args --list debloat-phase2-b.txt && echo "$PACKAGE_LIST")" "$SCRIPT_DIR/debloat-phase2-b.txt"
PACKAGE_LIST="$DEFAULT_PACKAGE_LIST"; parse_args --list "$tmp" -n
check "--list takes a path and combines with -n" "$PACKAGE_LIST $DRY_RUN" "$tmp 1"
(parse_args --list /nope/missing.txt 2>/dev/null); check "missing list file exits 2" "$?" "2"
check "missing list file names it" "$( (parse_args --list missing.txt) 2>&1)" "error: package list not found: missing.txt"
(parse_args --list 2>/dev/null); check "--list without a file exits 2" "$?" "2"

comments="$(mktemp)"; printf '# only
  # comments

' > "$comments"
check "comment-only list has no packages" "$(PACKAGE_LIST="$comments" packages | wc -l | tr -d ' ')" "0"
check "comment-only list keeps nothing" "$(PACKAGE_LIST="$comments" kept_packages)" ""

# keep list
keep="$(mktemp)"; printf 'com.keep.me   # kept
com.android.providers.*
' > "$keep"
list="$(mktemp)"; printf 'com.remove.me
com.keep.me
com.android.providers.media
com.android.providersx
' > "$list"
check "keep list matches names and prefixes" "$(KEEP_LIST="$keep" PACKAGE_LIST="$list" kept_packages | tr '\n' ' ')" "com.keep.me com.android.providers.media "
(KEEP_LIST="$keep" PACKAGE_LIST="$list" refuse_kept_packages 2>/dev/null); check "a kept package aborts with 1" "$?" "1"
check "the abort names the kept packages" "$( (KEEP_LIST="$keep" PACKAGE_LIST="$list" refuse_kept_packages) 2>&1 | tail -1)" "  com.android.providers.media"
(KEEP_LIST="$keep" PACKAGE_LIST="$comments" refuse_kept_packages); check "a clean list passes" "$?" "0"
# A file named like a keep pattern must not change the match (no glob expansion).
decoys="$(mktemp -d)"; touch "$decoys/com.android.providers.x"
check "keep patterns are not file globs" "$(cd "$decoys" && KEEP_LIST="$keep" PACKAGE_LIST="$list" kept_packages | tr '\n' ' ')" "com.keep.me com.android.providers.media "
rm -rf "$decoys"
for l in debloat-packages.txt debloat-phase2-a.txt debloat-phase2-b.txt debloat-phase2-c.txt debloat-phase2-d.txt; do
  check "$l keeps no kept package" "$(PACKAGE_LIST="$SCRIPT_DIR/$l" kept_packages)" ""
done
check "the HTC launcher is kept" "$(printf 'com.htc.htclauncherhighenglishd08\n' > "$list"; PACKAGE_LIST="$list" kept_packages)" "com.htc.htclauncherhighenglishd08"
rm -f "$comments" "$keep" "$list"

# home screen
resolved='priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
com.uminocelo.candle/.MainActivity'
check "resolved home is the component line" "$(parse_resolved_home <<<"$resolved")" "com.uminocelo.candle/.MainActivity"
check "resolved HTC launcher as home" "$(parse_resolved_home <<<"isDefault=true
com.htc.htclauncherhighenglishd08/com.htc.activity.HomeActivity")" "com.htc.htclauncherhighenglishd08/com.htc.activity.HomeActivity"
check "no component resolves to nothing" "$(parse_resolved_home <<<"No activity found")" ""

restrictions='<package-restrictions>
    <preferred-activities>
        <item name="com.android.htmlviewer/.HTMLViewerActivity" match="600000" always="false" set="0">
            <filter>
                <action name="android.intent.action.VIEW" />
                <cat name="android.intent.category.DEFAULT" />
            </filter>
        </item>
        <item name="com.uminocelo.candle/.MainActivity" match="100000" always="true" set="3">
            <set name="com.htc.htclauncherhighenglishd08/com.htc.activity.HomeActivity" />
            <set name="com.uminocelo.candle/.MainActivity" />
            <filter>
                <action name="android.intent.action.MAIN" />
                <cat name="android.intent.category.HOME" />
                <cat name="android.intent.category.DEFAULT" />
            </filter>
        </item>
    </preferred-activities>
    <persistent-preferred-activities />
</package-restrictions>'
check "saved home is the HOME item, not another preference" "$(saved_home_component <<<"$restrictions")" "com.uminocelo.candle/.MainActivity"
check "no saved home prints nothing" "$(saved_home_component <<<'<preferred-activities>
        <item name="com.a/.B" match="1" always="true" set="0">
            <filter><cat name="android.intent.category.DEFAULT" /></filter>
        </item>
    </preferred-activities>')" ""
check "a HOME filter outside preferred-activities is ignored" "$(saved_home_component <<<'<persistent-preferred-activities>
        <item name="com.x/.Y"><filter><cat name="android.intent.category.HOME" /></filter></item>
    </persistent-preferred-activities>')" ""

# wait_home_saved against a fake device
fake_xml="$restrictions"
device() { printf '%s\n' "$fake_xml"; }
sleep() { :; }
check "wait passes when the package is saved" "$(wait_home_saved com.uminocelo.candle)" "home preference saved: com.uminocelo.candle/.MainActivity"
(HOME_SAVE_TIMEOUT=4 wait_home_saved com.htc.htclauncherhighenglishd08 >/dev/null 2>&1); check "wait times out with 1 when another home is saved" "$?" "1"
check "the timeout names what is saved" "$( (HOME_SAVE_TIMEOUT=4 wait_home_saved com.htc.htclauncherhighenglishd08) 2>&1)" \
  "error: home preference not saved after 4s (saved: com.uminocelo.candle/.MainActivity); do not reboot yet"
fake_xml=""
check "the timeout says none when nothing is saved" "$( (HOME_SAVE_TIMEOUT=2 wait_home_saved com.uminocelo.candle) 2>&1)" \
  "error: home preference not saved after 2s (saved: none); do not reboot yet"

# wait_packages_saved against a fake device
fake_xml='<package-restrictions>
    <pkg name="com.gone" inst="false" stopped="true" nl="true" />
    <pkg name="com.here" ceDataInode="47589">
    </pkg>
</package-restrictions>'
check "removed packages are the inst=false ones" "$(saved_removed_packages <<<"$fake_xml")" "com.gone"
check "nothing to wait for passes" "$(wait_packages_saved removed)" ""
check "a saved removal passes" "$(wait_packages_saved removed com.gone)" "saved to disk (removed): com.gone"
check "a saved install passes" "$(wait_packages_saved installed com.here)" "saved to disk (installed): com.here"
(HOME_SAVE_TIMEOUT=2 wait_packages_saved removed com.gone com.here >/dev/null 2>&1); check "an unsaved removal times out with 1" "$?" "1"
check "the timeout names the unsaved packages" "$( (HOME_SAVE_TIMEOUT=2 wait_packages_saved installed com.gone com.here) 2>&1)" \
  "error: not saved after 2s: com.gone; do not reboot yet"
unset -f device sleep

check "a launcher is a real home" "$(real_home com.uminocelo.candle/.MainActivity)" "com.uminocelo.candle/.MainActivity"
check "the picker is no home" "$(real_home android/com.android.internal.app.ResolverActivity)" ""
check "nothing is no home" "$(real_home "")" ""

devices="$(mktemp)"; printf 'ADB_SERIAL=192.0.2.10:5555\nSERVER_SERIAL=phone-from-file\n' > "$devices"
lib="$(dirname "$0")/lib.sh"
check "serials come from devices.local" "$(env -u ADB_SERIAL -u SERVER_SERIAL DEVICES_FILE="$devices" bash -c "source $lib; echo \$ADB_SERIAL \$SERVER_SERIAL")" "192.0.2.10:5555 phone-from-file"
check "the environment wins over devices.local" "$(ADB_SERIAL=192.0.2.99:5555 DEVICES_FILE="$devices" bash -c "source $lib; echo \$ADB_SERIAL \$SERVER_SERIAL")" "192.0.2.99:5555 phone-from-file"
check "no serial at all: connect stops with a clear message" "$(env -u ADB_SERIAL DEVICES_FILE=/nonexistent bash -c "source $lib; connect" 2>&1; echo "exit $?")" \
  "error: no device: set ADB_SERIAL (see scripts/devices.example)
exit 1"
check "server-deploy names its own variable" "$(env -u ADB_SERIAL -u SERVER_SERIAL DEVICES_FILE=/nonexistent "$(dirname "$0")/server-deploy.sh" 2>&1 | tail -1)" \
  "error: no device: set SERVER_SERIAL (see scripts/devices.example)"
rm -f "$devices"

[ "$fails" = 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
