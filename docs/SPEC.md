# BilaiTube — Implementation Spec (for the implementing engineer)

You are building the **entire front end** of BilaiTube, an open-source Android YouTube client, as native **Jetpack Compose**.
Every commit is checked against this document and against the screenshots CI produces.
Anything not matching this spec or the reference file is sent back. Read all of it before writing code.

Source of truth, in order:
1. `docs/reference/prototype.html` — the visual and physics reference. Open it in a phone-width browser. If this spec and the file disagree, **the file wins**, except for the items in section 9 (things that must NOT be copied).
2. This document.
3. Existing code in `android/` — engine only (section 2).

---

## 0. Non-negotiable rules

1. **No PipePipe/NewPipe screens.** Not as a base, not as a fallback, not "temporarily". Every visible pixel is new Compose code under `org.schabi.newpipe.bilai` (or a new package `com.masharzy.bilaitube.ui`). Old Fragments/XML layouts for watch page, search, channel, settings, playlists, history, downloads list, about, drawer, toolbar must be deleted or unreachable, not restyled.
2. **Engine only is reused**: extractor (`PipePipeExtractor/`), `Player`/`PlayerService`/`PlayerHolder`, SABR/PoToken, SponsorBlock, RYD, download manager, local DB (Room), subscription/history/playlist **data layers**. UI never calls engine internals directly; it goes through the facade in section 2.
3. **No debug UI in the app.** No physics HUD, no k/c sliders, no "Drag down ↓" text, no state text.
4. **Match the reference numerically.** Colors, dp sizes, durations, physics constants are given below. Do not "improve", round, or substitute Material3 defaults. Do not use Material3 `BottomAppBar`, `NavigationBar`, `ModalBottomSheet`, `TopAppBar` for these surfaces. Build them to the measurements here.
5. **Smoothness is a requirement, not a nice-to-have**: no per-frame `requestLayout` on the video surface, no allocations in the physics tick, no recomposition of the feed while dragging (see 5.7).
6. **One milestone per commit series.** Push to branch `youtube-ui`. Each push must produce a green GitHub Actions build and the screenshot job must run (section 11). Never leave the branch red.
7. If something in this spec is impossible or ambiguous, write the question in `docs/QUESTIONS.md` and continue with the closest literal reading of the reference. Do not silently deviate.
8. Do not commit generated blobs, APKs, or the ffmpeg aar. Do not add network permissions beyond what exists.

---

## 1. Product summary

- Name `BilaiTube`, applicationId `com.masharzy.bilaitube`, GPL-3.0, zero server cost (everything local / direct from YouTube).
- Look and feel: **YouTube mobile**, with BilaiTube colors (Warm Amber default; Classic Red optional), Light + Dark.
- Guest mode first: real, region-dynamic home feed with YouTube's own chips, Shorts shelf.
- Optional sign-in through cookies, offering Google accounts already on the device (existing `YouTubeLoginWebViewActivity` logic may be reused as a non-visual helper or wrapped; its screen must be reskinned or replaced).
- Features: search, channel page, subscriptions, history, Watch Later, playlists, downloads, Shorts, SponsorBlock, Return YouTube Dislike, background play, PiP.

---

## 2. Architecture

```
UI (Compose, new)  ─┬─ BilaiTheme / components
                    ├─ screens: Home, Shorts, Subs, You, Search, Channel, Watch, Library…
                    └─ PlayerSheet (physics, section 5)
          │
          ▼  only through:
engine facade  (new, Kotlin, in org.schabi.newpipe.bilai.engine)
   interface PlaybackEngine {
     val state: StateFlow<PlaybackState>      // isPlaying, positionMs, durationMs, bufferedMs, speed, videoSize, sponsorSegments, error
     val current: StateFlow<StreamUi?>        // title, channel, avatarUrl, subs, views, uploadDate, description, likes, dislikes(RYD), related, chapters
     fun play(url: String, startMs: Long = 0)
     fun togglePlay(); fun seekTo(ms: Long); fun seekBy(deltaMs: Long)
     fun setSpeed(s: Float); fun setQuality(...); fun setAudioOnly(b: Boolean)
     fun attachSurface(view: PlayerView?)     // single shared video surface
     fun enterPip(); fun startBackground()
     fun stop()
   }
```
- Implement the facade on top of `PlayerHolder` + `PlayerServiceExtendedEventListener` (`onMetadataUpdate`, `onPlaybackUpdate`, `onProgressUpdate`). The seam already exists; do not fork `Player.java`.
- ViewModels expose `StateFlow`; composables are stateless where possible.
- Navigation: a single `MainActivity` hosting one `ComposeView`. Tabs + overlays are Compose state, not Fragments. Back handling: Search/Sheets/Overlays close first; expanded player minimizes; then tab → Home; then exit.
- Data sources: feed via `YoutubeHomeFeedExtractor` (exists); related/comments/channel/search via extractor; library via existing Room DAOs; downloads via existing download manager.
- Images: Coil (or the existing `NetImage`) with memory cache; thumbnails 16:9 `ContentScale.Crop`.
- Delete dead code you replaced. The reviewer greps for leftover `fragment_video_detail`, `DetailHeaderBinder`, `BottomSheetBehavior`, `NavigationHelper.open*Fragment` in reachable paths.

---

## 3. Design tokens (exact)

### 3.1 Colors

| token | Light | Dark |
|---|---|---|
| bg | `#FFFFFF` | `#0F0F0F` |
| surface | `#FFFFFF` | `#181716` |
| elevated | `#F2F2F2` | `#272523` |
| elevatedHover | `#E5E5E5` | `#363330` |
| border | `#000000` @ 8% | `#FFFFFF` @ 9% |
| text | `#0F0F0F` | `#F1F1F1` |
| muted | `#606060` | `#AAAAAA` |
| chipActiveBg | `#0F0F0F` | `#F1F1F1` |
| chipActiveText | `#FFFFFF` | `#0F0F0F` |
| sheetBg | `#FFFFFF` | `#1E1D1B` |
| outerBg (tablet/letterbox only) | `#E9E7E4` | `#070707` |

Accent (independent of light/dark):

| accent mode | accent | accentSoft | onAccent |
|---|---|---|---|
| Amber (default) | `#F59E0B` | `#F59E0B` @ 14% | `#1A1205` |
| Red | `#FF0033` | `#FF0033` @ 14% | `#FFFFFF` |

Other fixed colors: SponsorBlock segment `#34D399` (emerald-400); SponsorBlock chip bg `#10B981` @ 90% with black text; toggle "ON" text `#10B981` (emerald-500); video overlays are black at the alphas in the reference (`black/50`, `black/55`, `black/75`, `black/80`, `black/85`, `black/90`); toast bg `#181716`, text white, 1dp border white @15%.

Theme switching animates colors ~220 ms (`animateColorAsState`, `tween(220)`), like `transition: 0.22s` in the file. Theme + accent persist (DataStore/SharedPreferences) and apply before first frame (no flash).

Default theme on first run: Dark + Amber (as in the reference `state.dark = true`, `accentMode = 'amber'`). Do not follow system theme unless a setting says so.

### 3.2 Typography
Font: **Inter** (bundle Regular 400, Medium 500, SemiBold 600, Bold 700, ExtraBold 800 in `res/font`). If you cannot obtain the files, use `FontFamily.SansSerif` and note it in `docs/QUESTIONS.md`.

Tailwind → Compose mapping used in the reference (1 Tailwind spacing unit = 4dp):

| Reference | Compose |
|---|---|
| `text-[10px]` | 10sp |
| `text-[11px]` | 11sp |
| `text-xs` | 12sp / line 16sp |
| `text-[12px]` | 12sp |
| `text-sm` | 14sp / line 20sp |
| `text-[14.5px]` video-card title | 14.5sp, SemiBold, lineHeight ≈ 1.375× (`leading-snug`), maxLines 2 |
| `text-[17px]` watch title | 17sp, Bold, leading-snug |
| `text-[18px]` logo word | 18sp, ExtraBold, letterSpacing −0.025em (`tracking-tight`) |
| `text-base` | 16sp |
| `font-mono` (durations, time) | `FontFamily.Monospace` |

### 3.3 Shapes / spacing
- Header height 48dp, horizontal padding 14dp (`px-3.5`). Bottom nav height 56dp, 4 equal columns, 1dp top border.
- Icon buttons 36dp circles (`w-9 h-9`), icons 19dp in header, 20dp in bottom nav, 14dp in pills.
- Chips: height ≈ 32dp (`py-1.5` + 12sp), radius 8dp (`rounded-lg`), horizontal padding 12dp, gap 8dp, row padding 12dp×10dp, sticky at the top of the Home scroll, bottom border 1dp.
- Video card: 16:9 thumbnail full width (no radius), duration badge bottom-end 8dp inset, `black/85`, radius 4dp, mono 11sp SemiBold white, padding 6×2dp; watched-progress line 3dp tall at bottom in accent. Below: padding top 10dp, row of [avatar 36dp circle, text column, 3-dot 16dp icon with 4dp padding]. Gap 12dp. Card bottom padding 16dp.
- Radii: pills fully round; elevated cards 16dp (`rounded-2xl`); bottom sheet top corners 24dp (menu) / 16dp (description sheet); related thumbs 12dp; Shorts shelf tiles 12dp.
- Icons: use the Lucide-equivalent set (outline, 2dp stroke). Required: sun, moon, palette, link-2, bell, search, home, zap, rss, circle-user-round, arrow-left, x, thumbs-up, thumbs-down, share-2, download, minimize-2, chevron-down, chevrons-up-down, more-vertical, clock, message-square, play, pause, fast-forward. Ship them as vector drawables / `ImageVector`s. Do not substitute Material icons if a Lucide shape exists; if you cannot, tell the reviewer in the PR notes.

---

## 4. Screens (match the file, section by section)

All screens live in a "stage": header (48dp) / content / bottom nav (56dp). Overlays (search, watch, sheets, modals) draw **over the whole stage including header and nav**, exactly as in the file (`z-index` 20…50).

### 4.1 Header
`[logo + "BilaiTube"]` left; right group (gap 2dp): theme toggle (sun in dark / moon in light), accent toggle (palette, tinted accent), open-URL (link-2), bell with 8dp accent dot at top 6dp right 6dp, search. Logo: the cat-with-play mark from the file's SVG path (`logoCatPath`, `logoPlayPath`, viewBox 108, rounded square `elevated` fill, cat in accent, play triangle in onAccent), 28dp. Tapping logo → Home tab, chip "All", scroll to top.
- Theme toggle shows toast "Dark Mode"/"Light Mode"; accent toggle shows "BilaiTube Warm Amber"/"Classic YouTube Red"; bell shows a toast (real notifications can come later).

### 4.2 Bottom nav
Home (home), Shorts (zap), Subscriptions (rss), You (circle-user-round). Active = accent color + Bold 10sp label; inactive = muted + Medium 10sp. Icon 20dp, gap 2dp. Tapping the active tab scrolls to top.

### 4.3 Home tab
- Sticky chip row at the top of the list: chips come **from YouTube** (`YoutubeHomeFeedExtractor.Page.chips`), first chip "All". Active chip = chipActiveBg/Text, others = elevated/text. Selecting a chip loads that chip's continuation and shows shimmer skeleton cards while loading (skeleton = 16:9 block + avatar circle + two text bars, elevated color, shimmer 1.2 s).
- List: first **2** video cards, then the **Shorts shelf**, then the remaining cards, infinite scroll via continuation. Bottom padding 80dp.
- Shorts shelf: top/bottom 1dp border, vertical padding 12dp; header row: zap icon (accent 16dp) + "Shorts" 14sp Bold; right "View all" 12sp SemiBold accent. Tiles: 144dp wide, aspect 9:15, radius 12dp, bottom gradient black 90%→transparent, title 11sp SemiBold white (2 lines), views 10sp `#D1D5DB`. Gap 10dp, side padding 12dp. Tile tap → Shorts tab at that index; "View all" → Shorts tab. Hidden when the "Hide Shorts from Home" setting is on.
- Pull-to-refresh (accent-colored indicator).
- Guest/regional behavior: use device region/locale for `gl`/`hl`; if the logged-out home returns empty, fall back to Trending + category searches (existing behavior). Never show an empty screen without an error/empty state ("Couldn't load. Retry").
- 3-dot on a card → **Video menu sheet** (4.9).
- Tap thumbnail, avatar, or title → open the video (section 5.8).

### 4.4 Shorts tab
Full-bleed black, height = content area. **Vertical pager** (one Short per page, swipe up/down with snap, spring settle) — the file uses Prev/Next buttons only because it is a static mock; on Android implement real vertical paging **and keep the layout of the overlays**:
- Top bar: "Shorts" 16sp Bold white at left; (the Prev/Next pills from the file are NOT shipped).
- Right column, bottom 80dp, right 12dp, gap 16dp: like (thumbs-up in 40dp `black/45` circle + count 11sp SemiBold), comment (message-square + count), share (share-2 + "Share"). Add Dislike and a "more" at the bottom only if YouTube's UI has them for that Short; otherwise stick to three.
- Bottom-left (left 12dp, right 64dp, bottom 24dp): channel avatar 32dp (accent bg, initials fallback when no image), `@handle` 12sp Bold white, "Subscribe" pill (accent bg, 11sp Bold, 12×4dp padding), then the title 14sp Medium white.
- Gradient: bottom `black/90` → transparent, top `black/30`.
- Video plays in the Shorts pager through the engine; tap = pause/resume with a brief play/pause glyph; only the visible page plays; neighbors are preloaded (next page only). Audio stops when leaving the tab.
- Source: Shorts feed from the extractor (shortsLockupViewModel / the Shorts shelf continuation). If unavailable, search/trending filtered to Shorts. Never a blank pager.

### 4.5 Subscriptions tab
- Top strip (padding 12×12dp, gap 12dp, bottom border): first item "All" (48dp circle, 2dp accent border, selected = accent bg, else elevated; label "All" 10sp muted), then each subscribed channel avatar 48dp with a 12dp accent dot (2dp black border) bottom-end, selected = 2dp accent-colored ring; label = first word of name, 10sp muted, max width 54dp.
- Below: feed of video cards (same card as Home) filtered by the selected channel; empty state text "Subscribe to channels to see their latest videos." 12sp muted, centered, 64dp vertical padding.
- Data: local subscriptions (existing DB) → their feeds via extractor. Signed-in: may also pull the account's subscriptions.

### 4.6 You tab
Padding 16dp, spacing 20dp:
1. Profile row: 56dp circle (accent bg, "BT" 18sp ExtraBold or account avatar), title "Guest Mode"/account name 16sp Bold, subtitle "100% Local SQLite • Zero Server" / "Synced via local SAPISID cookie" 12sp muted; right pill "Sign In"/"Sign Out" (elevated, 12sp Bold). "Sign In" opens the **device-account chooser** (section 8).
2. Appearance pills (horizontal scroll): "Switch to Light/Dark" (sun/moon icon), "Warm Amber"/"Classic Red" (accent-soft bg, palette icon). **No "Physics HUD" pill.**
3. History carousel: header "History" 14sp Bold + "N watched" 12sp muted; cards 144dp wide: 16:9 thumb radius 12dp + duration badge (mono 9sp), title 11sp SemiBold two lines.
4. Elevated rounded-16 list with 1dp dividers: Offline Downloads (download icon accent, "N videos" right), Watch Later (clock icon accent, "N videos"), toggles: SponsorBlock Auto-Skip, Return YouTube Dislike, Hide Shorts from Home — each right side "ON" (`#10B981` Bold) / "OFF" (muted Bold), whole row tappable. Rows 12×16dp padding, 12sp.
5. Tapping Offline Downloads / Watch Later / History header / playlists opens full screens (milestone 5) built in the same design language (top bar 48dp with back arrow, cards as in Home).
Settings (everything else the engine supports) is reachable from a gear icon at the right of the profile row; **built last, in the same design**, as grouped rows like step 4 — not PipePipe's preference screens.

### 4.7 Search overlay
Full stage overlay (bg). Top bar 48dp: back arrow (36dp), text field (36dp tall, full-round, elevated, 14sp, placeholder "Search BilaiTube..."), clear (x) when non-empty. Body: results list padding 12dp, gap 12dp; each result: 144dp 16:9 thumb radius 12dp with duration badge (mono 10sp) + text column: title 12sp SemiBold 2 lines, "channel • views" 11sp muted. Real behavior: suggestions while typing (extractor suggestion API) shown as rows with a search icon and a ↖ fill button; submit → results with infinite scroll; recent searches (local) when empty; filters (Videos/Shorts/Channels/Playlists) as chips under the bar. Tap result → opens the video (search closes). Back closes search first.

### 4.8 Watch page details layer
Sits under the video (see 5), top = video height. Padding 14dp horizontal, 12dp top, 144dp bottom; vertical gap 14dp.
1. Title block (tappable → Description sheet): title 17sp Bold; below "views • age" 12sp muted + "...more" 12sp SemiBold text color.
2. Channel row: avatar 36dp, name 14sp SemiBold (ellipsis), subscribers 11sp muted; right pill **Subscribe** (accent bg, onAccent text, 12sp Bold, 16×6dp padding) / **Subscribed** (elevated bg, text color). Tapping channel area opens the Channel page.
3. Action pills (horizontal scroll, gap 8dp, vertical padding 2dp), all elevated, 12sp:
   - Like|Dislike split pill: [thumbs-up + count][1dp divider 16dp tall][thumbs-down + count]. Like active = accent color, SemiBold. Dislike active = accent. Dislike count from **Return YouTube Dislike**; shows "—" when the RYD setting is off. Counts formatted `1.2K` style (the file uses `n>=1000 → x.xK`; extend with M/B properly).
   - Share (share-2) → system share sheet with the watch URL + toast "Link copied" if clipboard path.
   - Download (download icon) label "Download"/"Saved"; tap toggles (starts a real download via the download manager; "Saved" when completed; progress ring while downloading).
   - **Float** (minimize-2, accent-soft bg, accent text) → minimizes the player with the button spring (5.6).
   - Add: Background (audio-only background play), PiP, Save (Watch Later/playlist picker). Keep the same pill style.
4. Comments preview card: elevated, radius 16, padding 12dp: header "Comments" 12sp Bold + count muted + chevrons-up-down icon; below avatar 24dp + top comment 12sp, 2 lines, 90% opacity. Tap → Comments sheet (real comments via extractor, paginated).
5. "UP NEXT" label (12sp Bold, uppercase, tracking wider, muted) then related list: 144dp 16:9 thumb radius 12dp + duration badge; text: title 12sp SemiBold 2 lines, channel 11sp muted, "views • age" 10sp muted. Gap 10dp. Tapping replaces the current video (re-runs open animation? **No** — keep the player expanded, just switch media; the file calls `openVideo` which restarts the open spring from morph .85; match that: it re-springs from 0.85 → 0 with initial velocity −2.4).

### 4.9 Sheets & modals
- **Description sheet** (bottom, top = video bottom edge, radius 16 top, border-top, shadow): header 16×12dp, border-bottom, title "Description" / "Comments (1.2K)" 14sp Bold, close x. Body padding 16dp, 12sp, line-height relaxed: video title 14sp Bold; a 3-column stat box (elevated radius 12, py 10dp): likes (accent, 14sp Bold) / views / published date, labels 10sp muted; then the description (preserve newlines, 90% opacity, links/hashtags/timestamps tappable — timestamps seek). Comments sheet: avatar 28dp + "@handle • age" 11sp SemiBold muted + text 12sp.
  Dismiss: tap x, drag down, or back. Sheet appears with spring slide-up (same k/c).
- **3-dot Video menu sheet**: scrim `black/60` full stage; sheet bg `sheetBg`, top radius 24dp, padding 16dp (bottom 24dp), 1dp border-top; 40×4dp handle `#6B7280` @40% centered, 12dp below; rows 12dp padding, 14sp Medium, icon 20dp, gap 14dp: "Save to Watch Later" (clock), "Download video (Offline)" (download), "Share" (share-2); then full-width "Cancel" pill (elevated, 12sp SemiBold, py 10dp). Add rows the extractor supports if natural ("Add to playlist", "Not interested", "Don't recommend channel") in the same style. Toasts: "Saved to Watch Later", "Saved to Offline Downloads", "Video link copied".
- **Open URL modal**: scrim `black/70` (+blur on API 31+), centered card radius 16, border, padding 16dp: title "Open Any YouTube URL" 14sp Bold + x; helper text 11sp muted (keep the Bengali sentence from the file); input (elevated, radius 12, 12sp, placeholder `https://www.youtube.com/watch?v=dQw4w9WgXcQ`); right-aligned "Cancel" (muted) + "Play Video" (accent bg pill, 12sp Bold). Accept watch/youtu.be/shorts/embed/playlist/channel links; invalid → toast "Valid YouTube URL দাও". Also register the real Android intent filters for youtube.com links.
- **Toast**: bottom 64dp, centered, pill, 12sp SemiBold, white on `#181716`, 1dp white@15% border, 2.2 s, fade in/out.

---

## 5. The player and its physics (MOST IMPORTANT)

Reproduce the reference's `physics` object **exactly**. All distances are in dp; velocities in dp/s; convert Android pixel velocities by dividing by `density`.

### 5.1 Layers (bottom → top, all in one full-stage container)
1. Tabs content (header, content, bottom nav).
2. **watchBackdrop** (bg color, full stage, `alpha = detailsOpacity`, no touch).
3. **watchDetails** (section 4.8) — top = `fullH`, bottom = stage bottom, `alpha = detailsOpacity`, `translationY = clampedT × 90dp`, touchable only when `clampedT < 0.2`.
4. **videoSurface** — the single video surface, black bg, clipped, z above details.
5. Overlays above the video, in this order: search overlay (z=40, above the video as in the file), description/comments sheet (z=40), video menu sheet / URL modal / toast (z=50).

The video surface is **one** `PlayerView`/`TextureView` hosted once at the top of the container and never recreated while a video is open. It is the only thing that moves and resizes.

### 5.2 Geometry (`getStageDims`)
```
w = stage width (dp), h = stage height (dp)         // stage = whole window content area
fullH  = round(w * 9/16)
miniW  = round(min(186, w * 0.47))
miniH  = round(miniW * 9/16)
pad = 12, topSafe = 58, bottomSafe = 68
corners:
  TL = (pad,                 topSafe)
  TR = (w - miniW - pad,     topSafe)
  BL = (pad,                 h - miniH - bottomSafe)
  BR = (w - miniW - pad,     h - miniH - bottomSafe)
```
Edge-to-edge/system bars: add the real status bar inset to `topSafe` and the navigation-bar inset to `bottomSafe` if the app draws edge-to-edge; the reference numbers are for a stage with no system insets.

### 5.3 Morph rendering (`applyGeometry`)
State: `morph` (m), `miniX`, `miniY` (absolute target mini position in stage coordinates).
```
t         = clamp(m, 0, 1.12)
clampedT  = clamp(t, 0, 1)
x         = miniX * t            // note: uses t (can overshoot to 1.12) → springy overshoot
y         = miniY * t
width     = w + (miniW - w) * clampedT
height    = fullH + (miniH - fullH) * clampedT
radius    = 14dp * clampedT
shadow    = clampedT > 0.05 ? (offsetY 16*clampedT, blur 36*clampedT, black alpha 0.55*clampedT) : none
detailsOpacity = max(0, 1 - clampedT * 1.45)   // applied to watchBackdrop AND watchDetails
details.top = fullH ; details.translationY = clampedT * 90dp
details touchable iff clampedT < 0.2
isMiniVisual = clampedT > 0.55:
   hide: top bar (chevron-down, SponsorBlock chip, speed chip), drag pill, time row ("00:00 / 00:00")
   show: mini close (x) button, 24dp circle, top 6dp right 6dp, black/80
```
**Implementation requirement:** because `fullH/w == miniH/miniW == 9/16`, the surface may be drawn as a uniform scale of the full-size surface: `scale = width / w`, `transformOrigin = (0,0)`, `translationX = x`, `translationY = y`, with corner radius compensated (`radius / scale`) and the control overlays drawn in a **separate unscaled layer** positioned from the computed width/height, so icons/text don't shrink. Do **not** call `requestLayout`/change `LayoutParams` per frame.

Only the video morphs. The page is **not** scaled or clipped as a whole (a previous attempt did that and was rejected).

### 5.4 Spring (`stepSpring`) — unit mass, explicit semi-implicit Euler
```
k = 320, c = 28          // damping ratio = c / (2√k) ≈ 0.78 (slightly underdamped → small overshoot)
dt = min(0.032, frameDeltaSeconds)
// when NOT dragging:
a_m  = -k*(m - mTarget) - c*mVel;     mVel += a_m*dt;   m += mVel*dt
  settle if |m - mTarget| <= 0.0015 and |mVel| <= 0.005 → m = mTarget, mVel = 0
for axis in (miniX, miniY):
  a = -k*(pos - posTarget) - c*vel;   vel += a*dt;  pos += vel*dt
  settle if |pos - posTarget| <= 0.3 and |vel| <= 1.5 → pos = posTarget, vel = 0
loop runs on Choreographer/`withFrameNanos` while anything is active or dragging; stops when settled.
```
Units: `m`/`mVel` are normalized (`mVel` in 1/s); `miniX/Y` in dp, velocities in dp/s.
Compose's `spring(dampingRatio = 0.78f, stiffness = 320f)` is mathematically the same system and is acceptable **only if** velocity can be injected and the same thresholds are used; otherwise port the loop above literally. Either way, the reviewer will compare video frames against the reference.

### 5.5 Gestures
Pointer on the video surface (not on its buttons). Track with the platform `VelocityTracker`, **or** replicate the file: keep samples within the last **90 ms** (always keep ≥2), velocity = (last − first)/(Δt), ignore if Δt ≤ 4 ms. Use the same 90 ms window result for all decisions below.

On **down**: `dragging = true`, `movedDist = 0`, record start x,y, `startMorph = m`, `startMiniX/Y = miniX/Y`, `dragType = (m < 0.5) ? MORPH : FLOAT`. (No cancel of in-flight springs besides pausing integration while dragging → interruptible: grabbing mid-flight continues from the current `m`.)

On **move**:
- MORPH: `travel = max(240, h - fullH - 90)`; `raw = startMorph + dy / travel`; if `raw < 0` → `raw *= 0.18`; if `raw > 1` → `raw = 1 + (raw - 1) * 0.22`; `m = raw`. (Only vertical component used.)
- FLOAT: `miniX = startMiniX + dx`, `miniY = startMiniY + dy` (free 2D drag; `m` stays ~1).
- `movedDist = hypot(dx, dy)`.

On **up/cancel** (`vel` from the tracker):
- If `movedDist < 8` → it is a tap: if mode is minimized → `springToExpanded(-1.4)`; return. (In expanded mode a tap on the surface toggles controls visibility; see 5.9.)
- MORPH drag end:
  - `if (vel.y > 420 || (m > 0.34 && vel.y > -250))` → `springToMinimized(initialMorphVel = max(0.8, vel.y / travel))`
  - else → `springToExpanded(initialMorphVel = min(-0.8, vel.y / travel))`
- FLOAT drag end:
  - dismiss if `miniX < -miniW*0.42 || vel.x < -1100 || miniX > w - miniW*0.58 || vel.x > 1100` → close the player entirely (stop playback), toast "Mini-player dismissed".
  - else snap: `target = nearestCorner(miniX + vel.x*0.18, miniY + vel.y*0.18)`; remember the corner label; `miniTargetX/Y = target`; `miniVx = vel.x*0.65`, `miniVy = vel.y*0.65`; run the loop.

Mode transitions:
- `springToExpanded(v)`: mode=expanded, `mTarget=0`, `mVel=v`; hide description sheet.
- `springToMinimized(v, vx=0, vy=0)`: target corner = remembered corner (default BR); mode=minimized; `mTarget=1`; `mVel=v`; `miniTarget = corner`; `miniVx/Vy = vx/vy`; hide description sheet.
- Buttons: chevron-down (top-left of the video) and the "Float" pill → `springToMinimized(2.5)`. Mini close (x) → close player.
- Opening a video (`openVideo`): reset mini position to the **remembered corner (BR first time)**; show layers; `m = 0.85`; `springToExpanded(-2.4)` (so it **grows out of the corner** with a soft overshoot); start playback.
- While minimized, the feed behind is fully interactive (the details layer is non-touchable and invisible). Tapping the feed's video opens a video again (same open spring).
- Back press: expanded → `springToMinimized(2.5)`; minimized → default back behavior.
- Rotation / fullscreen: landscape or the fullscreen button hides the stage chrome and shows a native-feeling fullscreen player (immersive, seek bar, speed/quality/subtitles, double-tap seek); exiting returns to the previous mode. Gestures in 5.5 apply only in portrait.

### 5.6 Feel checklist the reviewer will test
1. Slow drag down: video shrinks continuously from full width to mini while the details fade and slide down 90dp; nothing jumps.
2. Fast flick down: lands in the corner with a single small overshoot (<~6%), no wobble.
3. Release a slow drag before 34% morph without downward velocity: springs back to full.
4. Flick up from mini → expands; tap on mini → expands with the −1.4 kick.
5. Grab the video while it is still settling: it follows the finger immediately from where it is, no snap.
6. Drag the mini diagonally: follows 1:1, on release flies to the nearest corner **including inertia**, settling with the spring.
7. Fling the mini horizontally off the edge: dismisses.
8. 60 fps in `adb shell dumpsys gfxinfo` (jank < 5%) during the above with the home feed behind.

### 5.7 Performance rules
- Physics state lives in plain fields / a `MutableState<Float>` read **only** in draw/layout-phase lambdas (`graphicsLayer {}`, `Modifier.offset { }`, `drawBehind`) so dragging causes zero recomposition of the feed.
- No object allocation per frame (reuse sample ring buffer of fixed size).
- The feed is a `LazyColumn` with stable keys and `contentType`; images are decoded at display size.
- Details content is composed once per video, not per frame.

### 5.8 Opening a video
Any tap on a video entry (Home, Subs, You history, Search, Related, Channel, Library) calls `engine.play(url)` and triggers `openVideo`. The player sheet must be reachable from anywhere; there is exactly one player instance. `PlayerService` keeps playing in the background and the notification works as before.

### 5.9 Video surface controls (expanded)
Per the file: top-left chevron-down (32dp, black/50, blur), top-right SponsorBlock chip (shown only when the setting is on and the video has segments; `#10B981`@90% bg, black 10sp Bold) + speed chip (black/55, 10sp Bold white, shows `1.0x`; tap cycles **1.0 → 1.25 → 1.5 → 2.0 → 0.75**; long-press/tap-and-hold opens a full speed + quality picker sheet), center play/pause (48dp, black/55, 1dp white/20 border, 20dp icon), drag pill (40×4dp white/50, top 8dp, center), bottom gradient (black/90 → black/35 → transparent) containing the time row (`mm:ss / mm:ss`, mono 10sp white/90, left) and the progress bar: 4dp tall, track white/25, played = accent, buffered = white/40, **SponsorBlock segments painted `#34D399` on the track**; the bar is **scrubbable** (touch target ≥ 24dp tall, thumb appears while scrubbing) — the file's bar is display-only, real one must seek.
- Controls auto-hide after 3 s while playing (not in the file; standard YouTube behavior), tap shows them.
- Double-tap right half = +10 s, left half = −10 s, with the seek flash badge (`black/75` pill, fast-forward icon in accent + "+10s", 0.55 s scale 0.85→1.05→1.1 with fade). Consecutive taps accumulate (+20s, +30s…).
- Left-edge vertical drag = brightness, right-edge vertical drag = volume (with a small HUD pill). Must not conflict with the minimize drag: minimize drag is only recognized from the **top 60% of the surface center zone**, edge zones (outer 20% each side) are brightness/volume; a drag starting in the edge zone never morphs.
- Real SponsorBlock: when the setting is on and playback enters a segment, skip to its end and show toast "SponsorBlock skipped sponsor segment" (use the engine's existing SponsorBlock logic, don't reimplement).
- Quality, audio-only, captions, loop, sleep timer, stable volume live in the "⋮" bottom sheet reached from the surface (top-right, next to the speed chip) using the same sheet style as 4.9.

### 5.10 Mini-mode contents
Mini surface shows only: video, close (x), and (on tap) nothing else. Play/pause on the mini is a small 24dp circle bottom-left (`black/55`) so users can pause without expanding; a thin 2dp progress line at the bottom in accent.

---

## 6. Data model mapping (reference mock → real)

| Reference | Real source |
|---|---|
| `VIDEOS[]` home items | `YoutubeHomeFeedExtractor.fetchHome/fetchContinuation` |
| `CATS` chips | feed `Page.chips` |
| `SHORTS[]` shelf/tab | extractor Shorts shelf / Shorts feed |
| `state.subs` | local subscription table |
| `state.history` | local history table (watch positions → progress line) |
| `state.downloads` | download manager |
| `state.watchLater` | local playlist "Watch Later" (exists) |
| `liked/disliked` | local like state (+ signed-in rating via cookie session if available) |
| `dislikes` | Return YouTube Dislike API |
| progress/time/speed | `PlaybackEngine.state` |
| `topComment`, comments sheet | `CommentsInfo` from the extractor |
| Up Next | `StreamInfo.relatedItems` |
| avatar initials + colors | real avatar image; if missing, initials on a color derived from the channel name hash among `amber-600 #D97706, orange-600 #EA580C, emerald-600 #059669, sky-600 #0284C7, indigo-600 #4F46E5` |

Number formats: views "142K views", "1.4M views"; age "2 days ago" (relative, from upload date or extractor text); durations `mm:ss` or `h:mm:ss`; "LIVE" badge in accent for live streams.

---

## 7. State & persistence
- Persist: theme (dark/light), accent, SponsorBlock on/off, RYD on/off, Hide Shorts, last mini corner, playback speed, region.
- Process death: restore tab, chip, and the player in minimized mode if media was playing.
- Everything works signed-out.

---

## 8. Sign-in (cookie-based)
- "Sign In" (You tab) opens a screen in the same design: list of Google accounts on the device (via `AccountManager`), each as a 56dp row with avatar initial + email + "Continue". Tapping one runs the existing silent token → cookie flow (`YouTubeLoginWebViewActivity` logic). If silent flow fails, fall back to the Google web sign-in in an in-app WebView (reskinned top bar), then capture cookies.
- Store cookies encrypted with Android Keystore (AES-GCM); never log them.
- After login: Home uses the account feed; You tab shows name/avatar; subscriptions sync; "Sign Out" clears cookies and returns to guest.

---

## 9. Do NOT copy from the reference
- "Physics HUD" pill, HUD panel, k/c sliders.
- "Drag down ↓" text in the video overlay.
- Mock `VIDEOS`/`SHORTS`/`CATS` arrays, base64 thumbs, `SPEEDS` cycle is kept, the YouTube iframe, `setInterval` fake playback, fake progress.
- Tailwind class names, `lucide` runtime, HTML-based rendering.
- Prev/Next buttons on Shorts (replaced by a real vertical pager).

---

## 10. Milestones (do them in order; one reviewable push each)

**M1 — Shell & theme.** Delete the old shell. Compose host, `BilaiTheme` (tokens above, light/dark, amber/red, animated, persisted), header, 4-tab bottom nav, toasts, empty tab placeholders. Screenshots: dark+amber, light+amber, dark+red, light+red of Home placeholder.

**M2 — Player sheet & physics + watch page (with real engine).** Facade, single video surface, full section 5, watch details layer (4.8), description/comments sheets, 3-dot sheet, URL modal. Opening a video via URL modal and via a deep link must work. Screenshots: expanded; mid-drag (~0.5); minimized in each corner; description sheet.

**M3 — Home feed (real).** Chips from YouTube, cards, skeletons, Shorts shelf after 2 cards, infinite scroll, pull-to-refresh, empty/error states, region.

**M4 — Shorts tab, Subscriptions, You.** Vertical pager, subs strip + feed, You tab with toggles wired to the engine settings.

**M5 — Search, Channel, Library.** Search overlay with suggestions/filters; Channel page (banner, avatar, Subscribe, tabs Videos/Shorts/Playlists/About); History, Watch Later, Playlists, Downloads (with progress, offline playback) screens.

**M6 — Sign-in, Settings, polish.** Device-account chooser, Settings in the new design, notification/PiP polish, release signing config, per-ABI APK splits.

After each milestone, update `docs/PROGRESS.md` with: what is done, what is stubbed, known issues, and the CI run link. No claims of "done" for anything the screenshots don't show.

---

## 11. Definition of done (every milestone)
1. CI green on `youtube-ui`.
2. Screenshot job ran; screenshots are on branch `ci-screenshots` (the reviewer reads them).
3. No leftover legacy UI reachable.
4. No TODO placeholders visible to the user; no lorem; no debug text.
5. APK size: ship per-ABI splits (`arm64-v8a`, `armeabi-v7a`, `x86_64`) — the arm64 APK should be well below 100 MB; report the size.
6. A short note in the push message: files changed, what to test by hand, known gaps.

---

## 12. CI test hooks (debug builds only)
The screenshot job drives the app with `adb`. Add these to **debug** builds only (guard with `BuildConfig.DEBUG`), implemented in M1/M2:
- `adb shell am start -n com.masharzy.bilaitube/org.schabi.newpipe.MainActivity --ez bt.dark true|false --es bt.accent amber|red --es bt.tab home|shorts|subs|you` → sets theme/accent/tab before first frame.
- `adb shell am start -a com.masharzy.bilaitube.DEBUG_PLAY --es bt.uri <file:// or https:// media URL>` → opens the player sheet with that media through the normal engine path (used with a small bundled/pushed MP4 so physics can be tested without YouTube).
- `adb shell am broadcast -a com.masharzy.bilaitube.DEBUG_UI --es bt.action open_search|open_desc|open_menu|minimize|expand` → triggers the same code paths as the UI buttons.
- Stable content-descriptions / test tags on: `tab_home`, `tab_shorts`, `tab_subs`, `tab_you`, `btn_theme`, `btn_accent`, `btn_search`, `player_surface`, `player_minimize`, `player_close`. Compose: `Modifier.semantics { testTagsAsResourceId = true }` + `testTag`, so `uiautomator dump` shows them as resource-ids.
