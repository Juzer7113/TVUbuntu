#!/bin/bash
set -e

echo "=== Ubuntu Controller APK Build ==="

if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "Error: ANDROID_HOME or ANDROID_SDK_ROOT not set"
    echo "Please install Android SDK and set the environment variable"
    echo "  export ANDROID_HOME=/path/to/android/sdk"
    exit 1
fi

SDK_DIR="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

if [ ! -f "$SDK_DIR/platform-tools/adb" ] && [ ! -f "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "Warning: Android SDK may not be properly configured at $SDK_DIR"
fi

cd "$(dirname "$0")/.."

chmod +x gradlew 2>/dev/null || true

if [ -f "gradlew" ]; then
    ./gradlew assembleRelease
else
    echo "gradlew not found, using system gradle..."
    gradle assembleRelease
fi

APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
if [ -f "$APK_PATH" ]; then
    echo ""
    echo "=== Build Success ==="
    echo "APK: $APK_PATH"
    echo "Install: adb install $APK_PATH"
else
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
    if [ -f "$APK_PATH" ]; then
        echo ""
        echo "=== Build Success ==="
        echo "APK: $APK_PATH"
        echo "Install: adb install $APK_PATH"
    else
        echo "Error: APK not found after build"
        exit 1
    fi
fi
