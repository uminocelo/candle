# Developing Candle

Notes for building, testing and debugging Candle (the projector app, `app/`) and Candle Server
(the phone app, `server/`). They share the HTTP and UPnP code in `upnp/` and the build logic in
`buildSrc/`. For using the apps, see the [README](README.md).

## Build and test

Requirements: JDK 17 and the Android SDK (`local.properties` with `sdk.dir=…`).

```sh
./gradlew assembleDebug        # debug builds of both apps
./gradlew assembleRelease      # shrunk release builds, signed with the release key (see below)
./gradlew test                 # unit tests
./gradlew lint
./gradlew connectedAndroidTest # on-device smoke test (uninstalls Candle afterwards)
scripts/test_lib.sh            # offline tests for the adb scripts
scripts/test_memcheck.sh
scripts/test_smoke_check.sh
scripts/test_version.sh        # tests for the shared build logic in buildSrc
```

### Version and release key

Both apps share one version, `candle.version` in `gradle.properties` (`1.2.3` or `1.2.3-rc1`);
`-PcandleVersion=v1.2.3` overrides it, so a git tag can be passed as is. The versionCode is
computed from it and always grows (`1.2.3-rc1` → 1020301, `1.2.3` → 1020399).

Release builds are signed with one release key, which never goes in the repo. Create it once and
back up the keystore and its passwords; without them, installed apps cannot be updated:

```sh
mkdir -p ~/.android-keys
keytool -genkeypair -v -keystore ~/.android-keys/candle-release.jks -alias candle \
  -keyalg RSA -keysize 4096 -validity 36500
```

Then add to `~/.gradle/gradle.properties` (or set `CANDLE_SIGNING_STORE_FILE`,
`CANDLE_SIGNING_STORE_PASSWORD`, `CANDLE_SIGNING_KEY_ALIAS`, `CANDLE_SIGNING_KEY_PASSWORD`):

```properties
candle.signing.storeFile=/Users/<you>/.android-keys/candle-release.jks
candle.signing.storePassword=…
candle.signing.keyAlias=candle
candle.signing.keyPassword=…
```

Without the key, `assembleRelease` stops with "Release signing is not set up"; debug builds, tests
and lint do not need it. A release-signed APK cannot be installed over a debug-signed one (and the
other way round): uninstall first, which clears the app's settings.

### Licences

The apps are GPL-3.0-or-later (`LICENSE`); `THIRD_PARTY.md` lists every library each app ships,
with its version and licence. Release builds check that list against the resolved dependencies
(`verifyThirdParty`), so adding or updating a library means adding its row. Both files are
bundled as assets and shown on each app's About screen.

## Scripts

All scripts talk to the projector over adb (`scripts/server-deploy.sh` to the phone running
Candle Server). Copy `scripts/devices.example` to `scripts/devices.local` (git-ignored) and
fill in `ADB_SERIAL` (the projector, `<projector-ip>:5555`) and `SERVER_SERIAL` (the phone);
environment variables of the same names win over the file. Network debugging must be on (Settings →
Developer options, or Candle's Settings → Network debugging); turn it off again when you are
done, because adb on the HY300 needs no authorization.

| Script | What it does |
| --- | --- |
| `scripts/deploy.sh [--release] [--no-build]` | Build, install, grant all-files access and the settings grants, keep the default home screen, launch |
| `scripts/server-deploy.sh [--release] [--no-build]` | The same for Candle Server on the phone (all-files access, battery exemption) |
| `scripts/home-candle.sh` | Make Candle the home screen; waits until Android has saved the setting, since a reboot before that brings the old home back |
| `scripts/home-htc.sh` | Make the stock HTC launcher (kept installed as the fallback) the home screen again |
| `scripts/memcheck.sh [--no-reboot] [--limit-mb N]` | Reboot, then check Candle's idle memory (PSS) is below 35 MB; deploy with `--release` first, since debug builds run unshrunk code from the APK (~47 MB) |
| `scripts/debloat.sh [--dry-run] [--list <file>]` | Remove the apps in a list (default `debloat-packages.txt`) for user 0 and wait until Android has saved the change (a reboot before that, ~10 s, brings the apps back); refuses any list naming a package in `keep-packages.txt` |
| `scripts/restore.sh [--dry-run] [--list <file>]` | Bring those apps back (also waits until the change is saved) |
| `scripts/smoke-check.sh [--no-reboot] [--before <kB>]` | Reboot, then check the default home is in front and HDMI, projector settings, Play Store and YouTube open; prints `MemAvailable` (and the change since `--before`) and Candle's PSS. `HOME_PACKAGE=…` requires a specific home |

### Debloating the HY300

The phase 2 debloat lists carry location, memory and restore notes on each line:
`debloat-phase2-a.txt` (apps Candle replaces), `-b` (not needed on a projector), `-c` (vendor
extras) and `-d` (optional extras). Run a stage as `smoke-check.sh --no-reboot` →
`debloat.sh --list <file>` → `smoke-check.sh --before <kB>`, and `restore.sh --list <file>` if a
check fails. Before group D, make the Google keyboard the default: LatinIME (in that list) is the
default keyboard. Never reboot within ~15 s of a package change: Android writes it to disk late.

## Debugging

Open files on internal storage with a VIEW intent:

```sh
adb shell am start -a android.intent.action.VIEW -t video/x-matroska \
  -d file:///sdcard/Movies/film.mkv -n com.uminocelo.candle/.player.VideoPlayerActivity
adb shell am start -a android.intent.action.VIEW -t audio/mpeg \
  -d file:///sdcard/Music/album/01.mp3 -n com.uminocelo.candle/.player.NowPlayingActivity
```

Log tags: `CandlePlayer` (decoders in use, dropped frames, each buffering → ready step),
`CandleUsb` (each refresh of the drive list), `CandleBrowse` (how long each folder listing took),
`CandleNetwork` (each DLNA search).

Measured 2026-10-28 against a macOS SMB share over Wi-Fi: folder listed in 0.5 s including login;
a 1080p H.264 file (~3 Mbit/s) started in 1.4 s and played with no stalls or dropped frames; ±10 s
seeks resumed in 0.4–1.4 s. SMB videos stream through a seekable Media3 data source that reads
ahead 512 KB at a time.

### A DLNA server to test with

From a Mac: `brew install minidlna`, then run it in a Terminal window with a config naming the
folder to share:

```
media_dir=/Users/you/Movies
friendly_name=My Mac
db_dir=/tmp/minidlna
port=8200
```

`minidlnad -d -f minidlna.conf` (Ctrl-C stops it).

### Phone remote API

The phone page uses a small JSON API, which also works with curl:

```sh
P=http://<projector-ip>:8765
curl -X POST $P/api/pair/start              # the projector shows a 4-digit PIN for 2 minutes
curl -c jar -d pin=1234 $P/api/pair         # returns a token (also set as the candle_token cookie)
curl -b jar -X POST $P/api/key/ok           # up, down, left, right, ok, back, play_pause,
                                            # seek_back, seek_forward, next, previous
curl -b jar -X POST $P/api/volume/up        # up, down, mute
curl -b jar -X POST $P/api/home             # back to Candle's home screen
curl -b jar $P/api/now-playing              # {"state":"playing","kind":"video","title":…,"positionMs":…}
curl -b jar $P/api/sources                  # USB, SMB and DLNA sources (the DLNA search takes ~3 s, once a minute)
curl -b jar "$P/api/browse?source=<id>&path=<path>"
curl -b jar -d source=<id> -d path=<path> -d name=<name> -d type=video $P/api/play
```

`Authorization: Bearer <token>` works instead of the cookie. Commands without a paired token get
401. Five wrong PINs cancel the PIN. Navigation keys only reach Candle: with another app in front
they return 409. Media keys go to whichever app is playing, and volume always works.

### HY300 notes

- TV Settings declares the standard `SOUND_SETTINGS` screen but its class is missing from the APK
  and it crashes, so Candle opens Allwinner's picture and sound screens directly.
- The device has no all-files-access screen and no system file picker, so Candle's Setup shows
  the adb command for file access.
- `appops set … default` does not take a permission away on this firmware; use `deny` to test.
