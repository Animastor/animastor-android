<p align="center">
  <img src="frontends/android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Animastor" width="96" />
</p>

<h1 align="center">Animastor Android</h1>

<p align="center">
  <strong>Native Android client for the Animastor platform</strong><br/>
  Browse and read multimedia books with an offline-capable media player.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT" /></a>
  <img src="https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
</p>

---

## About

This repository is the **Android client** of Animastor (Kotlin). It provides the
native reader and player, and stays in parity with the web client (see
[`ANDROID_WEB_PARITY.md`](ANDROID_WEB_PARITY.md)). It talks to the backend API.

## Repository layout

```
animastor-android/
├── frontends/
│   └── android/       # Android application (Kotlin, Gradle)
├── apk-build.sh       # APK build helper
├── build-apk.sh       # Release APK build helper
├── ANDROID_WEB_PARITY.md
└── LICENSE
```

## Build

The Android app lives under `frontends/android`:

```bash
cd frontends/android
./gradlew assembleDebug     # debug APK
```

Or use the helper scripts from the repository root:

```bash
./build-apk.sh              # release APK (requires Android SDK)
./apk-build.sh
```

## Related repositories

Animastor is split into separate repositories:

- [animastor-backend](https://github.com/Animastor/animastor-backend) — API server + orchestration
- [animastor-web](https://github.com/Animastor/animastor-web) — responsive web client
- [animastor-gpu-hub](https://github.com/Animastor/animastor-gpu-hub) — GPU task dispatcher
- [animastor-worker](https://github.com/Animastor/animastor-worker) — GPU workers (ComfyUI)

## License

This project is licensed under the [MIT License](LICENSE).
