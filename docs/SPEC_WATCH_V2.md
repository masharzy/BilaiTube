# Watch page & player — Spec v2 (supersedes SPEC.md §4.8, §5.9 and the fullscreen parts of §5)

Reference: `docs/reference/watch-preview.html` (open it at phone width; thumbnails are placeholders).
Live preview approved by the owner: docs/reference/watch-preview.html
Everything in SPEC.md that this file does not mention still applies (tokens, physics constants k=320/c=28, morph math, drag thresholds, mini-player behavior, engine boundary).
Where this file and `watch-preview.html` disagree, the HTML wins, except for the removals in §1.

## 1. Remove / never ship
- Any on-screen text that explains gestures or shows numbers: "Release for Fullscreen", "Swipe up …", "N px", "Pull down to exit", "↑ Fullscreen • ↓ Mini", "Swipe down 14–20px…", "Under 14px threshold…", "Expanded with spring physics", the gesture guide strip, the Physics HUD, k/c sliders.
- Mock descriptions/comments that talk about the app's own gestures.
- Toasts that narrate physics. Allowed toasts: Link copied, Saved offline / Removed download, Captions: …, SponsorBlock skipped a sponsor segment, Mini-player dismissed.
- Rotating the video with a transform inside the portrait page. Fullscreen is real (see §2).

## 2. True fullscreen (Android)
- Enter: fullscreen button, or swipe up on the video by **≥ 14 dp** of finger travel (no visual text; the video may lift ≤ 10 dp under the finger and spring back if released under the threshold), or the device is physically rotated to landscape while system auto-rotate is on.
- On enter: request landscape (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`), go immersive (hide status + navigation bars, transient on swipe), hide header/tabs/details, and make the **same** video surface fill the window. The surface is never recreated. Animate the size/position with the same spring (k=320, c=28) and a short scale-in (0.94 → 1, ~280 ms).
- Video fit: `RESIZE_MODE_FIT` (letterbox) in fullscreen; portrait/mini use crop-to-16:9 as before.
- Exit: chevron-down, fullscreen button, system Back, swipe down on the video by **≥ 14 dp**, or physically rotating back to portrait when auto-rotate is on. On exit restore the previous orientation setting, bars, and the expanded layout.
- Fullscreen controls (same widgets as portrait, bigger hit targets): top-left chevron + title + channel; top-right CC and settings; center rewind-10 / play-pause / forward-10; bottom time, quality·speed pill, fullscreen toggle, seek bar. All use the auto-hide behavior in §3.
- Settings in fullscreen open as a **right-side drawer inside the window** (width 288 dp, max 75% of the window width, slide-in 340 ms `cubic-bezier(.22,1,.36,1)`, scrim black/55, tap scrim to close, own scroll). It must never be clipped by system bars or the bottom of the screen.
- Settings in portrait open as a **bottom sheet** over the whole stage (slide-up 380 ms, same easing, scrim black/60, handle, close x). Rows: Quality, Playback speed, Captions, SponsorBlock, Loop video (add Sleep timer, Audio only, Stable volume below these if the engine supports them). Values on the right (accent when On).

## 3. Controls behavior
- Auto-hide after 3 s of playing with no interaction; fade 220 ms. Tap on the video toggles visibility (single tap resolves after 300 ms to allow double-tap). Paused → controls stay visible. Opening settings keeps them visible.
- Double-tap left/right half: seek ∓/± 10 s, pill flash (black/70, fast-forward icon in accent, text `+10s`; consecutive taps within 700 ms accumulate `+20s`, `+30s`…), 600 ms scale/fade animation.
- Seek bar: 20 dp touch height, 4 dp visible track (white/25), buffered (white/35), played (accent), SponsorBlock segments `#34D399`, thumb 12 dp accent circle. Scrubbing disables auto-hide; releasing seeks.
- Mini player (morphed ≥ 55%): hide top bar, center buttons, time row; show close (x) top-right, a 24 dp play/pause bottom-left, and a 3 dp progress line along the bottom edge.
- Centered controls must not steal drags: touch handling starts on the surface, not on buttons; buttons keep their own taps.

## 4. Watch details (replaces SPEC §4.8 wording where different)
Order: title block (taps → description sheet) · channel row with Subscribe/Subscribed · action pills (Like|Dislike, Share, Download, **Float**) · comments preview card · "UP NEXT" list.
- "Float" pill = minimize to the mini player (label is just "Float").
- Like button animates: icon scale 1 → 1.5 → 0.92 → 1 over 380 ms (overshoot curve) when turning on; accent color when active.
- **Up Next is endless**: load the next page from the extractor's related-items continuation when the sentinel is within 400 dp of the viewport; show 3 skeleton rows (shimmer 1.2 s) while loading; de-duplicate by URL. If the extractor has no continuation, fall back to a search seeded from the video title/channel so the list never ends abruptly.
- Opening another video from Up Next keeps the player expanded and re-runs the open spring (morph .85 → 0, velocity −2.4), resets scroll to top, and replaces the related list.

## 5. Comments sheet (must feel like YouTube)
Opens from the preview card. Sheet sits below the video (its top edge = video bottom), over a black/40 scrim; slide-up 380 ms `cubic-bezier(.22,1,.36,1)`; the video keeps playing above it.
- Header: drag handle 40×4 dp white/25, title `Comments · 2.9K`, close x. **Dragging the header down dismisses with velocity awareness**: release past 110 dp or faster than 650 dp/s closes; otherwise springs back. Scrim tap and Back also close.
- Sort chips under the title: **Top** / **Newest** (active chip = text color on bg inverted; inactive = pill).
- Composer at top of the scroll area: 36 dp avatar + underlined field "Add a comment…". Focus reveals Cancel / Comment buttons; Comment enabled (accent) only with non-empty text. Posting inserts the comment at the top with a 320 ms slide-down/fade, clears the field, and increments the counts (sheet title + preview card). Replying prefills `@handle ` and focuses the field.
- Each comment: avatar 36 dp (24 dp for replies), line "@handle • age" (handle semibold), optional "Pinned by creator" row with pin icon, text 13 sp (clamped to 3 lines with **Read more / Show less** when long), action row: Like (thumb + count, accent + filled when active, pop animation), Dislike (filled when active), Reply, "hearted by creator" badge (small creator avatar + red heart).
- Replies: a "▾ N replies" accent button with a rotating chevron expands an inset list with a 300 ms height animation (grid-rows 0fr → 1fr equivalent: animate height with the same easing). Replies use the same row layout, 24 dp avatars, and are paginated by the extractor's reply continuation.
- **Endless**: load the next comments page when the end sentinel is within 300 dp; 2 skeleton comment rows (shimmer) while loading; stable keys; keep scroll position.
- Data: real comments via the extractor (`CommentsInfo`, `CommentsInfoItem`: author, avatar, text, like count, hearted, pinned, reply count + reply page token, upload date). Like/dislike/post/reply on a signed-out session only mutate local UI state and are not persisted; when signed in with cookies, call the account endpoints if the engine supports them, otherwise keep them local. Never claim a comment was posted to YouTube when it was not — show the posted comment with a subtle "Local" marker in that case.

## 6. Description sheet
Same sheet shell as comments (title `Description`, no sort row). Body: title, 3-column stat box (Likes / Views / Published), full description with newlines preserved; links, hashtags and `m:ss` timestamps are tappable (timestamps seek, closing the sheet).

## 7. Home feed
Endless: when the feed sentinel is within 400 dp of the bottom, request the next continuation, show 2 skeleton cards (shimmer), append, de-duplicate by URL. Chip change resets the list and scroll. Pull-to-refresh as before. If a continuation is missing, fall back to the category search with page tokens. The Shorts shelf appears once after the first 2 videos and again every ~12 videos (hidden when "Hide Shorts" is on).

## 8. Gestures — final table (portrait expanded)
| Gesture on video | Result |
|---|---|
| Drag down | Morph to mini (SPEC §5.5, unchanged constants) |
| Drag up ≥ 14 dp, release | Fullscreen |
| Drag up < 14 dp, release | Spring back, nothing happens |
| Tap | Toggle controls |
| Double-tap left/right | Seek −10 s / +10 s |
| In fullscreen: drag down ≥ 14 dp | Exit fullscreen |
| Mini: drag | 2D float, corner snap with inertia, off-edge fling dismisses |
| Mini: tap | Expand with kick −1.4 |
Brightness (left edge) and volume (right edge) vertical drags from SPEC §5.9 remain, using the outer 20% edge zones; they never trigger morph/fullscreen.

## 9. Answers to docs/QUESTIONS.md
- Inter font: not required. Keep the SansSerif fallback; bundle Inter later when files are available. No action.
- §5.5 vs §5.6 mini fling: §5.6 is right. A fast upward fling on the mini expands the player.
- Search/Open URL deferral: accepted. Open URL is part of M2 completion (it is the debug-free way to test real playback); Search is M5.
- Tabs may stay empty only until their milestone; do not add mock content.

## 10. What to do now (order)
1. Finish M2 against this file: fullscreen (§2), controls (§3), settings sheet/drawer (§2), actions (like animation, subscribe, download state, share).
2. Comments sheet (§5) + description sheet (§6) with real data.
3. Endless Up Next (§4) and endless Home (§7).
4. Update debug hooks so CI can capture: `bt.action=open_comments|open_desc|open_settings|fullscreen|exit_fullscreen|minimize|expand` and add screenshots for: `watch_expanded`, `watch_controls_hidden`, `comments_open`, `comments_replies_open`, `desc_open`, `settings_portrait`, `fullscreen_landscape`, `fullscreen_settings_drawer`, `mini_br`, `mini_tl`, plus a drag sequence.
5. Report with files changed, what to test by hand, known gaps. Do not start M4 until the reviewer accepts M2.

---

# Addendum (owner feedback after preview review) — these override anything above

`docs/reference/watch-preview.html` is updated; the HTML wins. Preview: docs/reference/watch-preview.html

## A1. Double-tap seek animation (YouTube style, not a pill)
- Double-tap left/right half → a translucent **white/30 half-oval** fills that half of the video (curved inner edge: radii 50%/50% of the zone, zone width 42% of video width, full height), fades in 160 ms, clipped by the video's own corner radius.
- Inside it, vertically centered: **three chevron triangles** (22 dp, white) pulsing in sequence (opacity .2 → 1 → .2, 900 ms loop, 150 ms stagger; chevrons point toward the tapped side) and below them the text `10 seconds` (13 sp SemiBold white). Consecutive taps (any gap < 700 ms while the zone is still showing) accumulate: `20 seconds`, `30 seconds`… Changing side resets the count.
- A **tap ripple**: a 60 dp white/28 circle at the finger point scales .3 → 5 while fading out over 550 ms.
- The center play/rewind/forward buttons fade out while the zone is showing. The zone fades out 750 ms after the last tap. Hidden in the mini player.
- The center rewind/forward buttons use the same zone animation with 10 s.

## A2. Comment reply opens inline
Tapping **Reply** on a comment (or a reply) expands a composer **directly under that comment** (height animation 300 ms): 24 dp avatar, underlined field with placeholder `Reply to @handle…`, Cancel / Reply buttons (Reply enabled in accent only with text). Only one reply box is open at a time. The list must **not** scroll to the top; only scroll the box into view if it is clipped.
Posting inserts the reply at the end of that comment's replies, auto-expands the replies with a 320 ms slide-in, scrolls it into view, and bumps both counts. Replying to a reply prefixes `@handle `. (Not posted to YouTube unless signed in and supported; mark local-only replies with a subtle "Local" tag.)

## A3. Captions
- The **CC button is an on/off toggle only** (accent-filled when on, dark circle when off). It never cycles languages. Toast: `Captions on · <language>` / `Captions off`.
- Language is chosen in **Settings → Captions** (a sub-page): `Off`, then the available tracks (manual first, then auto-generated, then auto-translate); selected row has a check and accent text. Choosing returns to the main settings page.
- While on, draw the current cue at the bottom of the video (13 sp portrait, 18 sp fullscreen, white on black/75, rounded); it moves down when controls auto-hide. Use the engine's subtitle tracks (`StreamInfo.subtitles`).

## A4. Playback speed — free choice up to 4×
Settings → **Playback speed** sub-page: large value (`3.05x`), slider **0.25×–4×, step 0.05**, − / + buttons (±0.05), preset chips `0.25x 0.5x 0.75x Normal 1.25x 1.5x 2x 3x 4x` (active chip = accent). Applies instantly (pitch-corrected via the engine). The speed chip/pill on the video shows the current value. No click-to-cycle anywhere.

## A5. Autoplay (animated)
- A **switch in the player's top bar** (left of CC): 36×20 dp track, 16 dp white thumb carrying a tiny play icon (on) / pause icon (off); thumb slides with a springy overshoot curve `cubic-bezier(.34,1.56,.64,1)` 260 ms; track turns accent when on. Also a row in Settings (same switch). Persisted.
- When a video ends and autoplay is on: dark end card over the video with `Up next`, thumbnail + title of the first related video, an accent **countdown ring (5 s, linear)**, `Cancel` and `Play now`. At 0 it opens the next video (stays in fullscreen if it was fullscreen; otherwise runs the normal open spring). Cancel → shows a big replay button. Autoplay off → shows the replay button immediately. Not shown in the mini player (there it just autoplays/stops).
- The next video is the first item of Up Next (extractor related items).

## A6. Settings is a paged menu (YouTube style)
Main page rows: Autoplay (switch), Captions (value ›), Quality (value ›), Playback speed (value ›), SponsorBlock (switch), Loop video (switch). Sub-pages (Captions, Quality, Speed) slide in (240 ms, from the right) with a back arrow in the header. Quality list: Auto, 2160p, 1440p, 1080p60, 720p, 480p, 360p, 240p, 144p (only what the stream offers), check on the active one. Same content in the portrait bottom sheet and the landscape right drawer.
