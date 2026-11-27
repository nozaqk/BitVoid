# BitVoid

BitVoid is a **native Android communication application**. The Android app is the only user interface. The project does not ship a browser client, WebView portal, public website, or hardware-specific dashboard.

## Native Android application

The Jetpack Compose app provides the native interface for account access, direct and group messaging, broadcast channels, media, audio calls, Live Classes, local discovery, and offline-first synchronization.

## Network architecture

- **Android app:** the native end-user client for accounts, messaging, channels, Live Classes, media, and notifications.
- **BitVoid Server:** a cross-platform Windows, Linux, and macOS desktop application containing the background application service and its native administration dashboard.
- **Direct hotspot:** the Server computer may host a Wi-Fi hotspot when supported by its operating system and network adapter. No separate hardware Gateway is required.
- **Existing LAN:** the Server may use an existing Wi-Fi router or Ethernet network.
- **Multi-location:** remote clients and Servers may communicate over configured VPN or private network links. TLS and trusted Server identity verification are required for untrusted or remote paths.
- **Optional Gateway:** a separate Gateway may provide network connectivity or discovery, but it is not required to run BitVoid and never owns accounts, messages, or application data.
- **Cloud services:** optional. Local discovery, authentication, and communication must work without Internet access.

The Server API listens on TCP port `8787` by default and advertises DNS-SD `_bitvoid-server._tcp`. The preferred local hostname is `bitvoid-server.local`. Discovery is advisory: the app verifies the Server identity before trusting an endpoint and binds local traffic to the selected Android Wi-Fi Network. A Gateway address is never treated as the application Server address.

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

- Optional Gateway firmware/hardware: not required for the desktop Server deployment.
- Shared Server API contract: synchronized with BitVoid Server v1.1 in `docs/SERVER-API-V1.md`.
- Android direct Server HTTP/WebSocket transport: present; authentication refresh-token persistence is being integrated, with discovery trust and feature-repository migration still in progress.
- Cross-platform desktop Server: implementation is under review in [BitVoid Server PR #21](https://github.com/nozaqk/Bitvoid-Server/pull/21).
- Physical end-to-end communication: not yet tested.

Optional Gateway firmware instructions are maintained separately; no Gateway hardware is required for the core BitVoid Server.
