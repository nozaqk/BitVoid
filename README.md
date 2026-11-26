# BitVoid

BitVoid is a **native Android communication application**. The Android app is the only user interface. The project does not ship a browser client, WebView portal, public website, or hardware-specific dashboard.

## Native Android application

The Jetpack Compose app provides the native interface for account access, direct and group messaging, broadcast channels, media, audio calls, Live Classes, local discovery, and offline-first synchronization.

## Network architecture

- **Android app:** the only user-facing client.
- **Gateway:** provides local Wi-Fi, gateway health, and discovery. It does not store or process BitVoid application data.
- **Application Server:** a separate headless service. The app connects directly to it over the same local Wi-Fi network.
- **Cloud services:** optional. Local communication must not depend on internet access.

The current Gateway hardware test stage uses an ESP-12E access point at `192.168.4.1`. That address belongs to the Gateway, not the application Server. The planned application Server listens on TCP port `8787` and is discovered separately (DNS-SD `_bitvoid-server._tcp`, preferred hostname `bitvoid-server.local`). The app must bind local sockets to the selected Android Wi-Fi Network.

## Communication protocol

The shared API contract is documented in [docs/SERVER-API-V1.md](docs/SERVER-API-V1.md).

- HTTP/REST for pairing, authentication, conversations, messages, channels, Live Classes, and synchronization.
- Authenticated WebSocket at `/api/v1/events` for real-time events.
- Bearer device tokens for app authentication.
- Idempotency keys and client-generated message IDs for safe retries.
- Durable sync cursors recover events missed while offline.

The Android module includes a direct Server transport client. It is a transport layer, not a claim that the application Server has already been implemented or deployed. Existing Gateway discovery/health code remains separate.

## Build

Open the repository in Android Studio and build the `app` module, or run:

```powershell
.\\gradlew.bat :app:assembleDebug
.\\gradlew.bat :app:testDebugUnitTest
```

No browser frontend or website build is required.

## Current implementation status

- Gateway firmware and hardware testing: current prerequisite.
- Shared Server API contract: defined in `docs/SERVER-API-V1.md`.
- Android direct Server HTTP/WebSocket transport: added, awaiting integration with Server discovery, secure token storage, and feature repositories.
- Headless application Server: not yet implemented.
- Physical end-to-end communication: not yet tested.

See the Gateway repository for firmware setup and hardware test instructions.
