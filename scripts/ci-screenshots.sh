#!/usr/bin/env bash
# Runs inside the emulator job. Never fails the job: every step is best-effort and logged.
# usage: ci-screenshots.sh <apk> <outdir>
APK="$1"; OUT="$2"; PKG=com.masharzy.bilaitube
ACT=org.schabi.newpipe.MainActivity
mkdir -p "$OUT"
log() { echo "[$(date +%T)] $*" | tee -a "$OUT/steps.log"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>>"$OUT/steps.log" && log "shot $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
start() { adb shell am start -W -n "$PKG/$ACT" "$@" >>"$OUT/steps.log" 2>&1; sleep 4; }

adb install -r -g "$APK" >>"$OUT/steps.log" 2>&1 || { log "install failed"; exit 0; }
adb shell settings put global window_animation_scale 1
read W H < <(adb shell wm size | tail -1 | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/')
log "screen ${W}x${H}"
adb logcat -c

# 1. theme/accent matrix (needs debug hooks; falls back to default UI if not implemented yet)
n=1
for combo in "true amber" "false amber" "true red" "false red"; do
  set -- $combo
  adb shell am force-stop $PKG
  start --ez bt.dark "$1" --es bt.accent "$2" --es bt.tab home
  shot "$(printf '%02d' $n)_home_${1}_${2}"; n=$((n+1))
done
dump home

# 2. other tabs
for tab in shorts subs you; do
  adb shell am force-stop $PKG
  start --ez bt.dark true --es bt.accent amber --es bt.tab $tab
  shot "$(printf '%02d' $n)_tab_$tab"; n=$((n+1))
done

# 3. player + physics with a local sample (debug hook)
adb shell am force-stop $PKG
start --ez bt.dark true --es bt.accent amber --es bt.tab home
if [ -f /tmp/sample.mp4 ]; then adb push /tmp/sample.mp4 /sdcard/Download/sample.mp4 >/dev/null 2>&1; fi
adb shell am start -a com.masharzy.bilaitube.DEBUG_PLAY --es bt.uri file:///sdcard/Download/sample.mp4 >>"$OUT/steps.log" 2>&1
sleep 5
shot 08_watch_expanded; dump watch_expanded

cx=$((W/2)); y0=$((H*22/100))
# slow drag to ~50% and hold -> mid-drag screenshot (swipe with long duration, capture while it runs)
adb shell input swipe $cx $y0 $cx $((H*55/100)) 1600 &
for i in 1 2 3 4 5 6 7 8; do sleep 0.18; adb exec-out screencap -p > "$OUT/drag_$i.png"; done
wait
shot 09_after_slow_drag

# fast flick down -> minimized bottom-right
adb shell input swipe $cx $y0 $cx $((H*70/100)) 120
sleep 1.5; shot 10_mini_after_flick; dump mini

# drag the mini to top-left and release with inertia
mx=$((W*75/100)); my=$((H*80/100))
adb shell input swipe $mx $my $((W*20/100)) $((H*15/100)) 200
sleep 1.5; shot 11_mini_tl

# tap mini to expand
adb shell input tap $((W*20/100)) $((H*15/100)); sleep 1.5; shot 12_expanded_again

# jank stats
adb shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo.txt" 2>&1
adb logcat -d -v time > "$OUT/logcat.txt" 2>&1
adb logcat -d -b crash -v time > "$OUT/crash.txt" 2>&1
log "done"
exit 0
