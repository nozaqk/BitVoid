# BitVoid

BitVoid is an offline-first Android local video node. A host phone creates an Android **Local Only Hotspot** or uses an already-running system hotspot, keeps the node alive with a foreground service, and serves public broadcasts or token-gated private rooms over an embedded NanoHTTPD/NanoWSD portal. Peers can connect without a router, SIM, ISP, cloud service, or account.

## Features

- Kotlin, Jetpack Compose, Material 3, coroutines, minSdk 26, target/compile SDK 35
- Persistent connected-device foreground service and low-importance notification
- Persisted pre-launch selector for **Open Visible** or **WPA2 Visible** hosting; hidden SSIDs are never supported
- WPA2 mode uses Android `startLocalOnlyHotspot()`, verifies the generated AP is visible, and displays its generated SSID/passphrase
- Open mode waits for a user-enabled system hotspot configured with Security None/Open and Hidden Network Off
- Active `ap0`/`wlan0`-style hotspot interface and private `.1` gateway detection
- HTTP/WebSocket server bound to `0.0.0.0:8080` on LocalOnlyHotspot or system Open-hotspot interfaces
- Dynamically resolved interface address, absolute captive-probe redirects, and portal URL
- Prominent live security state plus one-tap sharing of SSID, credentials when applicable, portal, and standard Wi-Fi QR payload
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
├── BitVoidNetworkConfig.kt
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
5. Before starting, choose **Open Network (Visible, No Password)** or **WPA2 Network (Visible, Password Protected)**. The selector locks while the Host engine is active.
6. Press **START BITVOID HOST ENGINE**. WPA2 mode creates Android's visible Local Only Hotspot and displays its generated credentials. Open mode waits for a system hotspot; use **OPEN ANDROID HOTSPOT SETTINGS**, set the name to `BitVoid_Net`, Security to None/Open, and Hidden Network to Off, then return to BitVoid.
7. Confirm the security card and use the join-share button to send friends the SSID, passphrase when applicable, portal URL, and Wi-Fi QR payload.
8. Nearby viewers join the displayed network and open `http://<gateway>:8080`. Captive-probe requests that reach BitVoid are redirected to the portal. The first screen asks for a Private Room ID, optional passkey, and chat alias; the public broadcast remains at `/public`.
9. Authenticated room members receive an in-memory token, can stream only videos assigned to that room, and can exchange real-time local chat messages. The host dashboard displays the live participant count.

## Room security model

- Room IDs, passkey verifiers, client sessions, participant state, and chat history live only in memory. Stopping the host node or ending the app process clears all rooms and chat.
- Passkeys are salted and derived with PBKDF2-HMAC-SHA256. Raw passkeys are not stored.
- Private room APIs require `X-Room-Token`; missing, expired, or room-mismatched tokens receive `401 Unauthorized`.
- Native WebSocket clients can send `X-Room-Token` during the upgrade. Browser WebSocket APIs cannot set arbitrary headers, so the bundled portal carries the same bearer token in the `Sec-WebSocket-Protocol` handshake header.
- HTML video elements also cannot set custom request headers. The bundled portal therefore uses an ephemeral token query parameter for private range requests. Tokens are never persisted, responses use `Referrer-Policy: no-referrer`, and the server keeps no access log.
- Chat is host-relayed over the isolated LAN rather than end-to-end encrypted between peers. An Open hotspot does not provide WPA link encryption: nearby observers can potentially inspect unencrypted HTTP traffic, so room tokens are access controls rather than transport encryption.

## Platform notes

- Android's public API can create a generated secured Local Only Hotspot, but normal third-party apps cannot reliably toggle or reconfigure the device-wide system hotspot as Open across OEMs. Open mode therefore launches Android hotspot settings and waits for a live AP interface rather than silently creating a different network.
- Android does not always expose system-hotspot SSID/security metadata to third-party apps. BitVoid validates Open/visible settings when metadata is available; otherwise the Host banner requires the user to keep Security None/Open and Hidden Network Off.
- BitVoid returns an absolute `302` portal redirect for common Android, Apple, Windows, Firefox, and Kindle probe paths and known probe Host headers. Those responses only work when the probe reaches BitVoid on port 8080. A normal app cannot replace hotspot DNS, transparently intercept connectivity-check domains on ports 80/443, install a trusted TLS certificate, or force every OEM captive-portal assistant to appear. The displayed/shareable portal URL remains the reliable fallback.
- Local Only Hotspot availability is device- and policy-dependent. Some managed devices or vendor Wi-Fi implementations can reject the request.
- Android may throttle Wi-Fi scans. BitVoid falls back to the most recent permitted scan results.
- An already-open media response cannot be recalled after bytes have been sent. Switching a video to Private removes it from the next public index poll and makes subsequent public direct/range requests return `403`.
- Byte-range responses necessarily expose the requested range and exact range length to make standards-compliant seeking possible; BitVoid pads privacy metadata and block-pads portal HTML without corrupting video payloads.

BitVoid does not create a VPN, Tor circuit, or internet anonymity service. Its privacy controls apply to this isolated local exchange only.
