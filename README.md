# BitVoid

BitVoid is a **native Android communication application**. The Android app is the only user interface. The project does not ship a browser client, WebView portal, public website, or PC/ESP dashboard.

## Native Android application

The Jetpack Compose app provides:
- Account access and recovery
- Direct messages, groups, and broadcast channels
- Local media sharing and attachments
- Audio calls and live audio classes
- Native gateway discovery and connection status
- Local-first operation with cloud account/synchronization support

## Backend and gateway architecture

- **Android app:** the only user-facing frontend.
- **Embedded Android service:** local REST/WebSocket API for the native app and connected BitVoid app clients.
- **PC server:** optional headless local API service for the ESP-12E gateway network.
- **Cloud backend:** headless account and synchronization APIs.
- **ESP-12E / ESP32 / Raspberry Pi controllers:** headless network gateways exposing machine-readable APIs only.

All user interactions happen inside the Android application. The local API root returns service metadata; it does not serve HTML. Media, chat, calls, and gateway features remain API-driven and use native Android screens.

## Build

Open the repository in Android Studio and build the `app` module, or run:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

No browser frontend or website build is required.

## Hardware notes

The ESP-12E is a Wi-Fi access point and discovery/health gateway, not a full BitVoid application server. For the current ESP-12E architecture, a PC running the BitVoid PC API service must also be connected to the ESP network. The Android app connects to the API over local Wi-Fi.

Keep gateway trust secrets and local configuration files private. Use HTTPS for cloud API traffic. Do not expose local HTTP APIs directly to the public internet.
