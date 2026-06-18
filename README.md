# Epiphyte Android (Kotlin Native)

End-to-end encrypted messenger over Tor. Native Android app built with Kotlin + Jetpack Compose.

**Fully compatible with the Windows desktop version** - same protocol, same crypto, same wire format.

## Features

- **E2E Encryption**: X3DH key exchange + Double Ratchet (Signal protocol compatible)
- **Tor Network**: Built-in Tor with bridge support (obfs4, snowflake, meek)
- **Disappearing Messages**: Per-conversation self-destruct timer
- **Panic Wipe**: Kill-switch passphrase destroys all data
- **Decoy Mode**: Alternate passphrase shows fake app
- **Group Chat**: Sender Keys protocol with forward secrecy
- **File Transfer**: Chunked, encrypted, with integrity verification
- **Traffic Padding**: Constant-rate dummy traffic defeats analysis
- **Screenshot Protection**: FLAG_SECURE prevents captures

## Build (WSL)

No Android Studio needed. Build entirely from WSL command line:

```bash
cd epiphyte-android-kotlin
chmod +x setup_and_build.sh
./setup_and_build.sh
```

The script automatically:
1. Installs OpenJDK 17 if needed
2. Downloads Android SDK & command-line tools
3. Installs required SDK packages
4. Generates Gradle wrapper
5. Builds the debug APK

Output: `./epiphyte-debug.apk`

### Prerequisites

- WSL (Ubuntu/Debian)
- ~5GB disk space (for SDK)
- Internet connection (first build downloads dependencies)

### Install on device

```bash
# Via ADB (USB debugging enabled)
adb install ./epiphyte-debug.apk

# Or copy to Windows desktop
cp ./epiphyte-debug.apk /mnt/c/Users/$USER/Desktop/
```

## Protocol Compatibility

This app uses the exact same wire format as the desktop version:

- **Message framing**: `length(4 BE) + CRC32(4 BE) + data`
- **Protocol messages**: `version(1) + type(1) + msg_id(8 LE) + timestamp(8 LE) + payload_len(4 LE) + payload + sig_len(2 LE) + sig`
- **Key exchange**: X3DH with Ed25519 signing + X25519 DH
- **Message encryption**: Double Ratchet with ChaCha20-Poly1305 AEAD
- **Key derivation**: HKDF-SHA256 with same salt/info strings

Desktop and Android users can communicate seamlessly.

## Architecture

```
app/src/main/java/org/epiphyte/app/
├── crypto/          # CryptoEngine, DoubleRatchet
├── protocol/        # Wire protocol, Session, key exchange
├── network/         # TorManager, NetworkManager
├── storage/         # EncryptedStorage (scrypt + ChaCha20)
├── stealth/         # PanicWipe, Disappearing, Decoy, TrafficPadder
├── group/           # GroupManager with Sender Keys
├── filetransfer/    # Chunked encrypted file transfer
├── controller/      # AppController (main logic)
├── service/         # TorService (foreground service)
└── ui/              # Jetpack Compose screens
    ├── theme/       # Dark theme colors
    └── screens/     # Login, Main, Chat, Settings
```

## Security

- All data encrypted at rest (scrypt + ChaCha20-Poly1305)
- FLAG_SECURE prevents screenshots
- No logs, no analytics, no telemetry
- Tor hidden service for anonymity
- Forward secrecy via Double Ratchet
- 3-pass secure wipe on panic
