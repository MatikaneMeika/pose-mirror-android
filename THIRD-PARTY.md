# Third-party notices

pose-mirror-android is MIT licensed (see `LICENSE`). The following
third-party works ship with the app; their licenses are retained below.

## Fonts

**Space Grotesk** — used for the wordmark, headings, and numeric scores
(`app/src/main/res/font/space_grotesk_*.ttf`).

- Copyright 2020 The Space Grotesk Project Authors
  (https://github.com/floriankarsten/space-grotesk)
- License: SIL Open Font License, Version 1.1 — full text in `fonts/OFL.txt`.

## Icons

Vector drawables under `app/src/main/res/drawable/ic_*.xml` are original
redraws in the style of Google's Material Symbols
(https://fonts.google.com/icons), Apache License 2.0.

## Animations

`app/src/main/res/raw/{dl_arrow,success_check,empty_frame}.json` are
original Lottie animations authored for this project (no third-party
source); they ship under the project's MIT license.

## Libraries (Gradle dependencies, Apache License 2.0)

- AndroidX CameraX, Lifecycle, WorkManager — https://developer.android.com/jetpack
- Material Components for Android — https://github.com/material-components/material-components-android
- MediaPipe Tasks Vision — https://github.com/google-ai-edge/mediapipe
- Lottie for Android — https://github.com/airbnb/lottie-android

## Index images

Gallery images are never bundled with the app. The downloadable index
bundles contain only freely-licensed images (CC0 / CC BY / CC BY-SA);
per-image author, source, and license are stored in each bundle's
`manifest.json` and shown in the in-app detail view.
