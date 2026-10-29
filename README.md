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
├── apk-build.sh       # APK build helper (debug by default, `release` mode)
├── build-apk.sh       # debug APK build helper (no clean)
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
./apk-build.sh              # clean + debug APK (default)
./apk-build.sh release      # clean + signed release APK
./build-apk.sh              # debug APK, no clean
```

### Release signing

Release builds are signed with the permanent Animastor key, which is never
stored in git. Gradle reads the path to a `signing.properties` file from the
environment variable `ANIMASTOR_SIGNING_PROPS`:

```properties
storeFile=/path/to/animastor-release.jks   # absolute, or relative to this file
storePassword=…
keyAlias=animastor
keyPassword=…
```

- On the build server the key and `signing.properties` live in
  `/home/animastor/prod/secrets/android/` (mode 600, included in the
  `secrets.tar.zst` backup). `./apk-build.sh release` uses that file when
  `ANIMASTOR_SIGNING_PROPS` is not set.
- Without the variable/file (or with a missing key field) `assembleRelease`
  fails with an explicit error; debug builds need nothing.
- Output: `frontends/android/app/build/outputs/apk/release/app-release.apk`
  (APK Signature Scheme v2; minify/resource shrinking are off).
- The key must never change: an APK signed with another certificate cannot
  be installed over an existing install. 0.2.1 was the one-time switch from
  the debug certificate.

## Related repositories

Animastor is split into separate repositories:

- [animastor-backend](https://github.com/Animastor/animastor-backend) — API server + orchestration
- [animastor-web](https://github.com/Animastor/animastor-web) — responsive web client
- [animastor-gpu-hub](https://github.com/Animastor/animastor-gpu-hub) — GPU task dispatcher
- [animastor-worker](https://github.com/Animastor/animastor-worker) — GPU workers (ComfyUI)

## License

This project is licensed under the [MIT License](LICENSE).
