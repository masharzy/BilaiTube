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
adb shell am force-stop "$pkg"
adb shell -T "run-as $pkg sh -c 'cat > sample.mp4'" < /tmp/bilai-sample.mp4
adb shell am start -W -n "$pkg/org.schabi.newpipe.MainActivity" \
  -a com.masharzy.bilaitube.DEBUG_PLAY --es bt.uri "file:///data/data/$pkg/sample.mp4"
sleep 3
adb shell uiautomator dump /sdcard/bilai-watch.xml
adb pull /sdcard/bilai-watch.xml "$out/08_watch_expanded.xml"
grep -q 'player_surface' "$out/08_watch_expanded.xml"
if grep -q "Couldn't play" "$out/08_watch_expanded.xml"; then exit 1; fi
adb exec-out screencap -p > "$out/08_watch_expanded.png"
adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action open_desc
sleep 1
adb exec-out screencap -p > "$out/12_desc_sheet.png"
adb shell input keyevent BACK
adb shell input swipe 585 300 585 1500 2500 &
swipe_job=$!
sleep 1
adb exec-out screencap -p > "$out/09_watch_mid_drag.png"
wait "$swipe_job"
adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action minimize
sleep 1
adb shell uiautomator dump /sdcard/bilai-mini.xml
adb pull /sdcard/bilai-mini.xml "$out/10_mini_br.xml"
grep -q 'player_close' "$out/10_mini_br.xml"
adb exec-out screencap -p > "$out/10_mini_br.png"
adb shell input swipe 900 2100 260 300 1800
sleep 1
adb exec-out screencap -p > "$out/11_mini_tl.png"
adb logcat -d -v time > "$out/logcat.txt"
