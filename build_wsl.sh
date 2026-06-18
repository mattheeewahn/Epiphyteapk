#!/bin/bash
# =============================================================
# Epiphyte Android - WSL Build Script
# Build APK without Android Studio using WSL
# =============================================================

set -e

echo "🌿 Epiphyte Android Build (WSL)"
echo "================================"

# Configuration
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT ANDROID_HOME
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# Check Java
if ! command -v java &> /dev/null; then
    echo "[!] Java not found. Installing OpenJDK 17..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq openjdk-17-jdk unzip wget
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "[!] Java 17+ required. Current: $JAVA_VERSION"
    echo "    Run: sudo apt install openjdk-17-jdk"
    exit 1
fi

echo "[+] Java OK (version $JAVA_VERSION)"

# Install Android SDK if not present
if [ ! -d "$ANDROID_SDK_ROOT/cmdline-tools" ]; then
    echo "[*] Installing Android SDK..."
    mkdir -p "$ANDROID_SDK_ROOT"

    CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    wget -q "$CMDLINE_TOOLS_URL" -O /tmp/cmdline-tools.zip
    unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_SDK_ROOT/cmdline-tools-tmp"
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    mv "$ANDROID_SDK_ROOT/cmdline-tools-tmp/cmdline-tools/"* "$ANDROID_SDK_ROOT/cmdline-tools/latest/"
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools-tmp" /tmp/cmdline-tools.zip

    echo "[+] SDK command-line tools installed"
fi

# Accept licenses and install required packages
echo "[*] Accepting licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true

echo "[*] Installing SDK packages..."
sdkmanager --install \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    "ndk;26.1.10909125" \
    2>/dev/null

echo "[+] Android SDK ready"

# Create local.properties
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

# Make gradlew executable
if [ ! -f "gradlew" ]; then
    echo "[*] Generating Gradle wrapper..."
    # Create minimal gradlew
    cat > gradlew << 'GRADLEW'
#!/bin/sh
exec gradle "$@"
GRADLEW
    chmod +x gradlew
fi

# Install gradle if needed
if ! command -v gradle &> /dev/null; then
    echo "[*] Installing Gradle..."
    GRADLE_VERSION="8.5"
    wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -O /tmp/gradle.zip
    sudo unzip -q /tmp/gradle.zip -d /opt/
    sudo ln -sf "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle
    rm /tmp/gradle.zip
    echo "[+] Gradle $GRADLE_VERSION installed"
fi

echo "[*] Building APK..."
gradle assembleDebug --no-daemon -q

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo ""
    echo "✅ Build successful!"
    echo "   APK: $APK_PATH"
    echo ""
    echo "   To install on device:"
    echo "   adb install $APK_PATH"
    echo ""
    # Copy to project root for easy access
    cp "$APK_PATH" "./epiphyte-debug.apk"
    echo "   Also copied to: ./epiphyte-debug.apk"
else
    echo ""
    echo "❌ Build failed. Check errors above."
    exit 1
fi
