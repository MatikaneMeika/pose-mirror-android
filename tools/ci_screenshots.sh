#!/bin/bash
# UI screenshot pass for the pose-mirror Android app.
# Runs on the CI host with adb connected to the emulator started by
# android-emulator-runner. Produces screenshots/shotNN_*.png.
set -euo pipefail

PKG=com.posemirror.app
OUT=screenshots
APK=app/build/outputs/apk/debug/app-debug.apk
mkdir -p "$OUT"

shot() { # $1 = file stem
  adb shell screencap -p "/sdcard/$1.png" >/dev/null
  adb pull "/sdcard/$1.png" "$OUT/$1.png" >/dev/null
  echo "captured $OUT/$1.png"
}

tap_text() { # $1 = exact button text
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null
  read -r X Y <<<"$(python3 - "$1" <<'EOF'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
for n in ET.parse("/tmp/ui.xml").iter("node"):
    if n.get("text") == want:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
EOF
)"
  if [ -z "${X:-}" ]; then
    echo "button '$1' not found in uiautomator dump" >&2
    return 1
  fi
  adb shell input tap "$X" "$Y"
}

adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.CAMERA

# 1 — fresh launch: starter index auto-download in progress (or just failed).
adb shell am start -n "$PKG/.MainActivity"
sleep 6
shot 01_downloading

# 2 — the default URL has no release yet: failure -> manual URL state.
sleep 25
shot 02_download_failed

# 3 — settings screen.
adb shell am start -n "$PKG/.ui.SettingsActivity"
sleep 4
shot 03_settings

# 4 — seed a tiny valid index, wipe prefs, relaunch -> first-launch dialog.
adb shell pm clear "$PKG" >/dev/null
python3 tools/make_fake_index.py /tmp/fakeindex
adb root >/dev/null
adb wait-for-device
adb push /tmp/fakeindex "/data/data/$PKG/files/posemirror-index" >/dev/null
# Pushed as root: hand ownership/labels back to the app so it can read them.
APP_UID=$(adb shell stat -c %u "/data/data/$PKG/files")
adb shell chown -R "$APP_UID:$APP_UID" "/data/data/$PKG/files/posemirror-index"
adb shell restorecon -R "/data/data/$PKG/files/posemirror-index"
adb shell pm grant "$PKG" android.permission.CAMERA
adb shell am start -n "$PKG/.MainActivity"
sleep 10
shot 04_firstlaunch_dialog

# 5 — dismiss the dialog via its skip button, then the main screen.
tap_text "跳过"
sleep 4
shot 05_main_viewfinder

adb shell am force-stop "$PKG" >/dev/null || true
echo "done: $(ls "$OUT" | wc -l) screenshots in $OUT/"
