#!/usr/bin/env bash
set -euo pipefail

apk=$1
out=$2
pkg=com.masharzy.bilaitube
mkdir -p "$out"
trap 'adb logcat -d -b crash -v time > "$out/crash.txt" || true' EXIT
adb install -r "$apk" | tee "$out/install.txt"
adb logcat -c
adb shell wm size 1170x2532
adb shell wm density 480
adb shell settings put system font_scale 1.0

capture() {
  local name=$1 dark=$2 accent=$3 tab=$4
  adb shell am force-stop "$pkg"
  adb shell am start -W -n "$pkg/org.schabi.newpipe.MainActivity" \
    --ez bt.dark "$dark" --es bt.accent "$accent" --es bt.tab "$tab" | tee "$out/$name.launch.txt"
  adb shell uiautomator dump /sdcard/bilai-ui.xml
  adb pull /sdcard/bilai-ui.xml "$out/$name.xml"
  python3 - "$out/$name.xml" "$tab" <<'PY'
import sys
import xml.etree.ElementTree as ET

nodes = {node.get("resource-id"): node for node in ET.parse(sys.argv[1]).iter("node")}
for tag in ("tab_home", "tab_shorts", "tab_subs", "tab_you", "btn_theme", "btn_accent", "btn_search"):
    assert tag in nodes, f"Missing Compose hook: {tag}"
active = "tab_" + sys.argv[2]
assert nodes[active].get("selected") == "true", f"Expected selected tab: {active}"
PY
  adb exec-out screencap -p > "$out/$name.png"
  test -s "$out/$name.png"
}

capture 01_home_dark_amber true amber home
capture 02_home_light_amber false amber home
capture 03_home_dark_red true red home
capture 04_home_light_red false red home
capture 05_tab_shorts true amber shorts
capture 06_tab_subs true amber subs
capture 07_tab_you true amber you
adb logcat -d -v time > "$out/logcat.txt"
