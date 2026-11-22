# BilaiTube

BilaiTube is a free, open-source Android YouTube client with a YouTube-style interface in a
warm amber colour scheme. Everything runs on your device: videos are extracted, streamed,
downloaded and stored locally. There is no BilaiTube server and no tracking.

## Features

- YouTube-style home feed with category chips and a Shorts shelf (loaded on-device; personalised
  when signed in, regional and generic as a guest)
- Spring-physics watch page: drag the video down and it morphs smoothly into a floating mini
  player that you can drag anywhere and that snaps to the nearest corner; fling sideways to
  dismiss, tap to expand
- Subscriptions, history, local playlists, downloads (video and audio), background playback
  and Picture-in-Picture
- SponsorBlock, Return YouTube Dislike
- Warm Amber dark theme with an optional AMOLED black mode
- Guest mode by default; optional sign-in for a personalised feed and synced subscriptions

## Building

The project is a normal Gradle build and needs JDK 25 and the Android SDK.

```
curl -L -o android/ffmpeg/ffmpeg-kit.aar \
  https://github.com/InfinityLoop1308/PipePipeClient/raw/43ef494c1a1792a774c223e1982ff1e794c49017/ffmpeg/ffmpeg-kit.aar
cd android
./gradlew :app:assembleDebug
```

Every push to the `youtube-ui` branch is built by GitHub Actions
(`.github/workflows/build-apk.yml`); download the APK from the run's `BilaiTube-APK` artifact.

## Repository layout

- `android/` – the Android app (`com.masharzy.bilaitube`)
- `PipePipeExtractor/` – the on-device extractor
- `main` branch – an untouched snapshot of upstream PipePipe, kept for reference and syncing

## Credits and license

BilaiTube is built on [PipePipe](https://github.com/InfinityLoop1308/PipePipe) by InfinityLoop1308,
which is itself based on [NewPipe](https://github.com/TeamNewPipe/NewPipe) and
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) by the NewPipe team and
contributors. The extractor, the playback engine and many other parts come from those projects.
Thank you to everyone who wrote them.

BilaiTube is licensed under the [GNU General Public License v3.0](LICENSE), the same license as
its upstream projects. BilaiTube is not affiliated with or endorsed by YouTube or Google.
