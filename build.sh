#!/bin/bash
set -e

cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [ ! -d "$ANDROID_HOME/platforms" ]; then
    echo "Error: Android SDK not found at $ANDROID_HOME"
    echo "Run .devcontainer/setup-android-sdk.sh first."
    exit 1
fi

BUILD_TYPE="${1:-assembleDebug}"

echo "Building NewPipe ($BUILD_TYPE)..."
./gradlew "$BUILD_TYPE" -DskipFormatKtlint --parallel --build-cache

if [ "$BUILD_TYPE" = "assembleDebug" ]; then
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$APK_PATH" ]; then
        echo ""
        echo "Build successful!"
        echo "APK: $APK_PATH ($(du -h "$APK_PATH" | cut -f1))"
    fi
elif [ "$BUILD_TYPE" = "assembleRelease" ]; then
    APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
    if [ -f "$APK_PATH" ]; then
        echo ""
        echo "Build successful!"
        echo "APK: $APK_PATH ($(du -h "$APK_PATH" | cut -f1))"
    fi
fi
