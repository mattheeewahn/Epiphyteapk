#!/bin/bash
# =============================================================
# Epiphyte Android - Complete Setup & Build for WSL
# Run this script in WSL to build the APK from scratch.
# =============================================================

set -e

echo "🌿 Epiphyte Android - Setup & Build"
echo "====================================="
echo ""

# --- 1. Check & install prerequisites ---

install_if_missing() {
    if ! command -v "$1" &> /dev/null; then
        echo "[*] Installing $2..."
        sudo apt-get update -qq
        sudo apt-get install -y -qq $2
    fi
}

# Java 17
JAVA_OK=false
if command -v java &> /dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VER" -ge 17 ] 2>/dev/null; then
        JAVA_OK=true
    fi
fi

if [ "$JAVA_OK" = false ]; then
    echo "[*] Installing OpenJDK 17..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq openjdk-17-jdk
fi
echo "[+] Java OK"

install_if_missing wget wget
install_if_missing unzip unzip

# --- 2. Android SDK ---

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

if [ ! -f "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "[*] Downloading Android command-line tools..."
    mkdir -p "$ANDROID_SDK_ROOT"
    TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    wget -q --show-progress "$TOOLS_URL" -O /tmp/cmdline-tools.zip
    unzip -qo /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    cp -r /tmp/cmdline-tools-extract/cmdline-tools/* "$ANDROID_SDK_ROOT/cmdline-tools/latest/"
    rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-extract
    echo "[+] Android cmdline-tools installed"
fi

echo "[*] Installing SDK components (this may take a few minutes)..."
yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --install \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    2>&1 | grep -v "Warning"

echo "[+] Android SDK ready at $ANDROID_SDK_ROOT"

# --- 3. Gradle wrapper ---

if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "[*] Downloading Gradle wrapper..."
    mkdir -p gradle/wrapper
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar"
    # Alternative: generate via gradle init
    if command -v gradle &> /dev/null; then
        gradle wrapper --gradle-version 8.5 --no-daemon 2>/dev/null
    else
        # Download gradle to generate wrapper
        if [ ! -f "/tmp/gradle-8.5-bin.zip" ]; then
            wget -q --show-progress "https://services.gradle.org/distributions/gradle-8.5-bin.zip" -O /tmp/gradle-8.5-bin.zip
        fi
        if [ ! -d "/tmp/gradle-8.5" ]; then
            unzip -qo /tmp/gradle-8.5-bin.zip -d /tmp/
        fi
        /tmp/gradle-8.5/bin/gradle wrapper --gradle-version 8.5 --no-daemon 2>/dev/null
    fi
    echo "[+] Gradle wrapper ready"
fi

chmod +x gradlew 2>/dev/null || true

# --- 4. local.properties ---

echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
echo "[+] local.properties configured"

# --- 5. Build ---

echo ""
echo "[*] Building debug APK..."
echo ""

./gradlew assembleDebug --no-daemon --warning-mode=none

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    cp "$APK" "./epiphyte-debug.apk"
    SIZE=$(du -h "$APK" | cut -f1)
    echo ""
    echo "====================================="
    echo "✅ Build successful!"
    echo "   APK: ./epiphyte-debug.apk ($SIZE)"
    echo ""
    echo "   Install on device:"
    echo "   adb install ./epiphyte-debug.apk"
    echo ""
    echo "   Or copy to Windows:"
    echo "   cp ./epiphyte-debug.apk /mnt/c/Users/\$USER/Desktop/"
    echo "====================================="
else
    echo ""
    echo "❌ Build failed."
    exit 1
fi
