#!/usr/bin/env bash

set -e

echo "Building APK..."

# Ensure Android SDK is configured (Gradle looks for local.properties
# in the project root where build.gradle.kts lives = frontends/android/)
ANDROID_DIR="frontends/android"
if [ ! -f "$ANDROID_DIR/local.properties" ]; then
    echo "sdk.dir=${HOME}/Android/Sdk" > "$ANDROID_DIR/local.properties"
fi

# Режим: ./apk-build.sh            → debug (как раньше)
#        ./apk-build.sh release    → release, подписанный ключом из
#        ANIMASTOR_SIGNING_PROPS (по умолчанию prod/secrets/android/signing.properties)
MODE="${1:-debug}"
case "$MODE" in
    debug) TASK=assembleDebug ;;
    release)
        TASK=assembleRelease
        DEFAULT_PROPS="${HOME}/prod/secrets/android/signing.properties"
        if [ -z "${ANIMASTOR_SIGNING_PROPS:-}" ] && [ -f "$DEFAULT_PROPS" ]; then
            export ANIMASTOR_SIGNING_PROPS="$DEFAULT_PROPS"
        fi
        if [ -z "${ANIMASTOR_SIGNING_PROPS:-}" ] || [ ! -f "$ANIMASTOR_SIGNING_PROPS" ]; then
            echo "release: ANIMASTOR_SIGNING_PROPS не задан или файл не найден" >&2
            exit 1
        fi
        ;;
    *) echo "usage: $0 [debug|release]" >&2; exit 2 ;;
esac

# Clean build — кеш компилятора часто даёт артефакты при изменении ViewModel логики
cd "$ANDROID_DIR"
./gradlew clean "$TASK"
if [ "$MODE" = release ]; then
    echo "APK: $ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
fi

echo "Done."

