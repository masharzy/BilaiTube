#!/usr/bin/env bash
set -euo pipefail

apk=$1
out=$2
pkg=com.masharzy.bilaitube
mkdir -p "$out"
capture_logs() {
  adb logcat -d -b crash -v time > "$out/crash.txt" || true
  adb logcat -d -v time > "$out/logcat.txt" || true
}
trap capture_logs EXIT
adb install -r "$apk" | tee "$out/install.txt"
adb logcat -c
adb shell wm size 1170x2532
adb shell wm density 480
adb shell settings put system font_scale 1.0
adb shell settings put secure immersive_mode_confirmations confirmed

dump_ui() {
  local dst=$1 tag=$2 remote=/sdcard/bilai-ui.xml
  for attempt in {1..8}; do
    adb shell rm -f "$remote"
    if adb shell uiautomator dump "$remote" &&
        adb pull "$remote" "$dst" && grep -q "$tag" "$dst"; then
      return 0
    fi
    if [ -s "$dst" ] && grep -Eq 'android:id/immersive_cling_title|Pixel Launcher isn.t responding' "$dst"; then
      point=$(python3 - "$dst" <<'PYCODE'
import re, sys, xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).iter("node"):
    if node.get("resource-id") in ("android:id/ok", "android:id/aerr_wait"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PYCODE
)
      if [ -n "$point" ]; then adb shell input tap $point; fi
    fi
    sleep 2
  done
  echo "Could not capture Compose node: $tag" >&2
  return 1
}

capture() {
  local name=$1 dark=$2 accent=$3 tab=$4
  adb shell am force-stop "$pkg"
  adb shell am start -W -n "$pkg/org.schabi.newpipe.MainActivity" \
    --ez bt.dark "$dark" --es bt.accent "$accent" --es bt.tab "$tab" | tee "$out/$name.launch.txt"
  sleep 2
  dump_ui "$out/$name.xml" "tab_$tab"
  python3 - "$out/$name.xml" "$tab" <<'PY'
import sys
import xml.etree.ElementTree as ET

nodes = {node.get("resource-id"): node for node in ET.parse(sys.argv[1]).iter("node")}
for tag in ("tab_home", "tab_shorts", "tab_subs", "tab_you", "btn_url", "btn_search"):
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
# Start through the regular launcher first so the debug action is delivered to
# an initialized, foreground activity, even on slower emulator cold starts.
capture_page() {
  local name=$1 action=$2 label=$3
  adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action "$action"
  dump_ui "$out/$name.xml" "$label"
  adb exec-out screencap -p > "$out/$name.png"
  adb shell input keyevent BACK
}
capture_page 14_search open_search Search
capture_page library_history open_history History
capture_page library_watch_later open_watch_later 'Watch Later'
capture_page library_playlists open_playlists Playlists
capture_page library_downloads open_downloads 'Offline Downloads'
capture_page app_settings open_app_settings Appearance
capture_page account_sign_in open_account 'Choose account'
capture 00_player_host true amber home
adb shell -T "run-as $pkg sh -c 'cat > sample.mp4'" < /tmp/bilai-sample.mp4
adb shell am start -W -n "$pkg/org.schabi.newpipe.MainActivity" \
  -a com.masharzy.bilaitube.DEBUG_PLAY --es bt.uri "file:///data/data/$pkg/sample.mp4" | tee "$out/player.launch.txt"
sleep 3
dump_ui "$out/08_watch_expanded.xml" player_surface
if grep -q "Couldn't play" "$out/08_watch_expanded.xml"; then exit 1; fi
adb exec-out screencap -p > "$out/08_watch_expanded.png"
adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action fullscreen
sleep 2
dump_ui "$out/fullscreen_landscape.xml" player_surface
adb exec-out screencap -p > "$out/fullscreen_landscape.png"
adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action open_settings
sleep 1
dump_ui "$out/fullscreen_settings_drawer.xml" 'Player settings'
adb exec-out screencap -p > "$out/fullscreen_settings_drawer.png"
adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action exit_fullscreen
sleep 2
adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action open_settings
sleep 1
dump_ui "$out/settings_portrait.xml" 'Player settings'
adb exec-out screencap -p > "$out/settings_portrait.png"
adb shell input keyevent BACK
adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action open_comments
sleep 1
adb exec-out screencap -p > "$out/comments_open.png"
adb shell input keyevent BACK
adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action open_desc
sleep 1
adb exec-out screencap -p > "$out/12_desc_sheet.png"
adb shell input keyevent BACK
capture_page 13_video_menu open_menu 'Video options'
adb shell dumpsys gfxinfo "$pkg" reset >/dev/null
adb shell input swipe 585 300 585 1500 2500 &
swipe_job=$!
sleep .35
for i in {01..10}; do
  adb exec-out screencap -p > "$out/drag_$i.png"
  sleep .06
done
cp "$out/drag_05.png" "$out/09_watch_mid_drag.png"
wait "$swipe_job"
adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action minimize
sleep 1
dump_ui "$out/10_mini_br.xml" player_close
adb exec-out screencap -p > "$out/10_mini_br.png"
adb shell input swipe 900 2100 260 300 1800
sleep 1
adb exec-out screencap -p > "$out/11_mini_tl.png"
if adb shell pm list features | grep -q android.software.picture_in_picture; then
  adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action enter_pip
  sleep 2
  adb shell dumpsys activity activities > "$out/pip_activity.txt"
  grep -q 'com.masharzy.bilaitube.*mode=pinned' "$out/pip_activity.txt"
  adb logcat -d -s BilaiPip:I > "$out/pip_callback.txt"
  grep -q 'Mode changed: true' "$out/pip_callback.txt"
  adb exec-out screencap -p > "$out/player_pip.png"
  adb shell am start -W -n "$pkg/org.schabi.newpipe.MainActivity"
  dump_ui "$out/player_pip_restored.xml" player_surface
  adb logcat -d -s BilaiPip:I > "$out/pip_callback.txt"
  grep -q 'Mode changed: false' "$out/pip_callback.txt"
fi
adb logcat -d -v time > "$out/logcat.txt"
adb shell dumpsys gfxinfo "$pkg" > "$out/gfxinfo.txt"
