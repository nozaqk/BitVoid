# BitVoid

BitVoid is an offline-first Android local video node. A host phone creates an Android **Local Only Hotspot** or uses an already-running system hotspot, keeps the node alive with a foreground service, and serves public broadcasts or token-gated private rooms over an embedded NanoHTTPD/NanoWSD portal. Peers can connect without a router, SIM, ISP, cloud service, or account.

## Features

- Kotlin, Jetpack Compose, Material 3, coroutines, minSdk 26, target/compile SDK 35
- Persistent connected-device foreground service and low-importance notification
- Open `BitVoid_Net` Local Only Hotspot request using `SoftApConfiguration.SECURITY_TYPE_OPEN` on configurable Android releases
- No generated WPA fallback: unsupported/rejecting devices serve an already configured system hotspot instead
- HTTP/WebSocket server bound to `0.0.0.0:8080` for LocalOnlyHotspot and system-managed hotspot interfaces
- Dynamically resolved interface address, absolute captive-probe redirects, and portal URL
- Prominent live security state plus one-tap sharing of the open SSID, portal, and standard Wi-Fi QR payload
- Thread-safe `StateFlow` media catalog with persisted Public/Private visibility
- Every newly imported video defaults to **Private**
- Host-side visibility switch for every video and a live public-broadcast count
- Public-only `/api/videos` and `/public` portal; private video IDs return `403 Forbidden`
- Three-second public-browser polling so newly public videos appear without a page refresh
- Host-created private Room IDs with optional PBKDF2-hashed passkeys
- Cryptographically random, expiring room tokens held in memory only
- Token-gated private video routes and room APIs using `X-Room-Token`
- Real-time NanoWSD chat at `/ws/chat/{roomId}` with ephemeral RAM-only history
- Host room dashboard showing assigned private videos and active chat participants
- Android, Apple, Windows, and Firefox captive-probe path/host handlers returning `302` to the active local portal
- RFC 7233 single-range streaming with `206 Partial Content` for media seeking
- Storage Access Framework importer into app-scoped `BitVoid_Shared` folders
- No request logs, no client IP/MAC retention, ephemeral per-response session IDs
- Identifying request headers discarded by the server
- Fixed-size privacy padding header and 4 KiB-block portal body padding
- Peer scanner, manual SSID/passphrase connection, and app-to-network binding
- Sandboxed local WebView with room JavaScript enabled but WebRTC injection-blocked; cookies, persistent cache, external hosts, camera, microphone, and geolocation remain disabled

## Project structure

```text
app/src/main/java/com/bitvoid/network/
├── BitVoidClientManager.kt
├── BitVoidDashboard.kt
├── BitVoidForegroundService.kt
├── BitVoidLocalServer.kt
├── BitVoidMediaManager.kt
├── BitVoidRoomManager.kt
└── MainActivity.kt
```

## Build

1. Install Android Studio with Android SDK 35 and JDK 17.
2. Open this repository and let Gradle sync.
3. Build from Android Studio, or run:

```bash
./gradlew assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Use

1. Install BitVoid on the host phone and grant Nearby devices, Location, Notifications, and requested media permissions.
2. In **HOST NODE**, add videos. Chosen content is copied into app-scoped `BitVoid_Shared` storage and starts **Private**.
3. For public broadcasting, switch intended videos to **Public**.
4. For restricted sharing, select **Create Private Room**, choose a Room ID, optional passkey, and one or more Private videos.
5. Press **POWER NODE**. On supported Android releases BitVoid requests `BitVoid_Net` with Open security and no passphrase. If the configurable API is unavailable or rejected, BitVoid does not create a password-protected fallback; enable an Open system hotspot and the server will use that interface.
6. Confirm the Host card reads **SECURITY: OPEN (NO PASSWORD REQUIRED)**, then use **SHARE OPEN SSID + PORTAL** to send friends the SSID, portal URL, and Wi-Fi QR payload.
7. Nearby viewers join the displayed network and open `http://<gateway>:8080`. Captive-probe requests that reach BitVoid are redirected to the portal. The first screen asks for a Private Room ID, optional passkey, and chat alias; the public broadcast remains at `/public`.
8. Authenticated room members receive an in-memory token, can stream only videos assigned to that room, and can exchange real-time local chat messages. The host dashboard displays the live participant count.

## Room security model

- Room IDs, passkey verifiers, client sessions, participant state, and chat history live only in memory. Stopping the host node or ending the app process clears all rooms and chat.
- Passkeys are salted and derived with PBKDF2-HMAC-SHA256. Raw passkeys are not stored.
- Private room APIs require `X-Room-Token`; missing, expired, or room-mismatched tokens receive `401 Unauthorized`.
- Native WebSocket clients can send `X-Room-Token` during the upgrade. Browser WebSocket APIs cannot set arbitrary headers, so the bundled portal carries the same bearer token in the `Sec-WebSocket-Protocol` handshake header.
- HTML video elements also cannot set custom request headers. The bundled portal therefore uses an ephemeral token query parameter for private range requests. Tokens are never persisted, responses use `Referrer-Policy: no-referrer`, and the server keeps no access log.
- Chat is host-relayed over the isolated LAN rather than end-to-end encrypted between peers. An Open hotspot does not provide WPA link encryption: nearby observers can potentially inspect unencrypted HTTP traffic, so room tokens are access controls rather than transport encryption.

## Platform notes

- Android 16/API 36 exposes `startLocalOnlyHotspotWithConfiguration` publicly. Android 13–15 contains a permission-checked configurable overload that BitVoid invokes through an isolated compatibility bridge; an OEM may block it. Android 12 and earlier do not provide a third-party API for a custom Open Local Only Hotspot. BitVoid never falls back to a generated WPA network: on unsupported/rejecting devices, configure an Open system hotspot manually.
- BitVoid returns an absolute `302` portal redirect for common Android, Apple, Windows, Firefox, and Kindle probe paths and known probe Host headers. Those responses only work when the probe reaches BitVoid on port 8080. A normal app cannot replace hotspot DNS, transparently intercept connectivity-check domains on ports 80/443, install a trusted TLS certificate, or force every OEM captive-portal assistant to appear. The displayed/shareable portal URL remains the reliable fallback.
- Local Only Hotspot availability is device- and policy-dependent. Some managed devices or vendor Wi-Fi implementations can reject the request.
- Android may throttle Wi-Fi scans. BitVoid falls back to the most recent permitted scan results.
- An already-open media response cannot be recalled after bytes have been sent. Switching a video to Private removes it from the next public index poll and makes subsequent public direct/range requests return `403`.
- Byte-range responses necessarily expose the requested range and exact range length to make standards-compliant seeking possible; BitVoid pads privacy metadata and block-pads portal HTML without corrupting video payloads.

BitVoid does not create a VPN, Tor circuit, or internet anonymity service. Its privacy controls apply to this isolated local exchange only.
