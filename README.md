<p align="center"><img src="docs/icon-512.png" width="120" alt="BilaiTube logo"></p>

# BilaiTube

BilaiTube is a free, open-source Android YouTube client with a YouTube-style interface in a warm
amber colour scheme. Everything runs on your phone: videos are found, streamed, downloaded and
stored locally. There is no BilaiTube server, no ads and no tracking.

## Download

Get the latest APK from [Releases](https://github.com/masharzy/BilaiTube/releases/latest).
Pick the file that matches your phone; almost every modern phone uses **arm64-v8a**
(`armeabi-v7a` for old 32-bit phones, `x86_64` for emulators and Chromebooks).

Once installed, BilaiTube checks for new versions by itself (You → Check for updates). Tap
**Update now**; Android shows one confirmation screen and the update installs.

## Features

- **Home** with YouTube's own category chips, endless scrolling and Shorts shelves; personalised
  when signed in, regional as a guest. It refreshes when you come back after a while.
- **Watch page** with spring physics: drag the video down and it shrinks into a floating mini
  player you can throw into any corner; flick it sideways to close, tap to open again.
  Swipe up on the video for fullscreen, swipe down to leave it. Double-tap to skip 10 seconds.
- **Player settings**: quality, captions (on/off plus language), speed from 0.25x to 4x,
  autoplay with an up-next countdown, loop, picture-in-picture and background play.
- **Shorts** in a full-screen vertical feed.
- **Search** with history, suggestions and mixed results (videos, Shorts, channels, playlists).
- **Downloads** in the quality you choose, or audio only. Files are saved to
  `Movies/BilaiTube` (video) and `Music/BilaiTube` (audio) so they show up in your gallery,
  and they play offline inside the app.
- **Subscriptions** feed with filters, **history**, **Watch Later** and **playlists**.
- **Optional Google sign-in**: your home feed, Watch Later, Liked videos and playlists come from
  your account, and likes, subscriptions and comments go to YouTube. The session is stored
  encrypted on the phone only.
- **SponsorBlock**: automatically skips sponsor segments inside videos (community-marked parts
  where the creator reads an advert). YouTube's own ads are never shown.
- **Return YouTube Dislike**: shows estimated dislike counts.
- Light and dark themes, Warm Amber or Classic Red accent.

## Building

The project is a normal Gradle build and needs JDK 25 and the Android SDK.

```
curl -L -o android/ffmpeg/ffmpeg-kit.aar \
  https://github.com/InfinityLoop1308/PipePipeClient/raw/43ef494c1a1792a774c223e1982ff1e794c49017/ffmpeg/ffmpeg-kit.aar
cd android
./gradlew :app:assembleDebug
```

GitHub Actions builds every push (`.github/workflows/build-apk.yml`). Pushing a tag such as
`v1.0.1` runs `.github/workflows/release.yml`, which builds signed release APKs and publishes a
GitHub Release that the in-app updater picks up. Put release notes in `docs/releases/<tag>.md`.

### Release signing

Add four repository secrets so every release has the same signature (needed for in-app updates):
`KEYSTORE_BASE64` (the keystore file, base64-encoded), `KEY_STORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`. Never commit a keystore to the repository.

## Repository layout

- `android/` – the Android app (`com.masharzy.bilaitube`); the user interface is in
  `android/app/src/main/java/org/schabi/newpipe/bilai/` (Jetpack Compose)
- `PipePipeExtractor/` – the on-device YouTube extractor
- `docs/` – design specs, reference prototypes and release notes

## Privacy

BilaiTube talks only to YouTube/Google (to load videos and, if you sign in, your account data),
to SponsorBlock and Return YouTube Dislike (by video ID) and to GitHub (to check for updates).
Nothing is sent to the BilaiTube developers.

## Credits and license

BilaiTube is built on [PipePipe](https://github.com/InfinityLoop1308/PipePipe) by
InfinityLoop1308, which is based on [NewPipe](https://github.com/TeamNewPipe/NewPipe) and
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) by the NewPipe team and
contributors. The extractor, playback engine and downloader come from those projects. Thank you
to everyone who wrote them.

BilaiTube is licensed under the [GNU General Public License v3.0](LICENSE), the same license as
its upstream projects. You may use, study, share and modify it; modified versions you distribute
must also be GPL-3.0 and include their source code. BilaiTube is not affiliated with or endorsed
by YouTube or Google. "YouTube" is a trademark of Google LLC.
