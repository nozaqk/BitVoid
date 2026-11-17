# BitVoid

BitVoid is an offline-first Android local video node. A host phone creates an Android **Local Only Hotspot** or uses an already-running system hotspot, keeps the node alive with a foreground service, and serves public broadcasts or token-gated private rooms over an embedded NanoHTTPD/NanoWSD portal. Peers can connect without a router, SIM, ISP, cloud service, or account.

## Features

- Kotlin, Jetpack Compose, Material 3, coroutines, minSdk 26, target/compile SDK 36
- Polished dark responsive interface with adaptive launcher branding, accessible status states, and clear host/client workflows
- Persistent connected-device foreground service and low-importance notification
- Persisted pre-launch selector for **Open Visible** or **WPA2 Visible** hosting; hidden SSIDs are never supported
- WPA2 mode uses Android `startLocalOnlyHotspot()`, verifies the generated AP is visible, and displays its generated SSID/passphrase
- Open mode waits for a user-enabled system hotspot configured with Security None/Open and Hidden Network Off
- Active `ap0`/`wlan0`-style hotspot interface and private `.1` gateway detection
- HTTP/WebSocket server bound to `0.0.0.0:8080` on LocalOnlyHotspot or system Open-hotspot interfaces
- Stable mDNS gateway hostname at `http://bitvoid.local`, with automatic port-80 serving and hostname/IP fallbacks
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
- Real-time NanoWSD channel and direct chat with ephemeral RAM-only history and arbitrary file attachments
- Local-only, audio-only WebRTC voice calls with microphone mute, speaker routing, and no external STUN/TURN servers
- Audio-only Live Classes with host/student roles, shareable `CLS-XXXX-XX` IDs, visible or hidden Hub listings, microphone controls, and synchronized participant state
- Native Compose and browser audio meeting stages backed by the host's authenticated `/ws/signaling` endpoint
- Responsive browser audio controls and participant layouts that use the full meeting stage
- Professional browser attachment cards with responsive video/audio/image previews, file metadata, secure open/download actions, and upload progress
- Ephemeral same-node username presence: direct chat and calls open only when the target is active on the current BitVoid Wi-Fi gateway
- Host room dashboard showing assigned private videos and active chat participants
- Android, Apple, Windows, and Firefox captive-probe path/host handlers returning `302` to the active local portal
- RFC 7233 single-range streaming with `206 Partial Content` for media seeking
- Storage Access Framework importer into app-scoped `BitVoid_Shared` folders
- No request logs, no client IP/MAC retention, ephemeral per-response session IDs
- Identifying request headers discarded by the server
- Fixed-size privacy padding header and 4 KiB-block portal body padding
- Client Node is reduced to a Wi-Fi scanner: it never joins or switches networks, and only links through the Wi-Fi already connected to the phone
- Silent same-Wi-Fi BitVoid discovery probes the local subnet, identifies `/api/node`, and automatically routes the app to a discovered gateway without showing discovery progress
- Manual **Connect Node** validates the selected SSID against the phone's current Wi-Fi and discovers the host URL automatically—no gateway URL or Wi-Fi passphrase entry is required
- Sandboxed local WebView with room JavaScript enabled but WebRTC injection-blocked; cookies, persistent cache, external hosts, camera, microphone, and geolocation remain disabled

## Project structure

```text
app/src/main/java/com/bitvoid/network/
├── MainActivity.kt
├── call/        # Audio-only WebRTC media and signaling
├── channel/     # Host channels and participant state
├── client/      # Peer discovery and client networking
├── direct/      # Ephemeral direct chat
├── media/       # Imported media catalog
├── model/       # Host/client and network configuration models
├── room/        # Private room authorization
├── server/      # Local HTTP and WebSocket portal
├── service/     # Foreground host engine and node state
└── ui/          # Responsive Compose screens and theme
```

## Standalone ESP32 / ESP-12E companion connection

The Android client includes an application-scoped hardware integration under
`com.bitvoid.network.connection`:

- `BitVoidConnectionManager` watches for the open `BitVoid_Node` Wi-Fi, binds the Android process
  to that Wi-Fi, detects the `192.168.4.1` captive redirect, and resolves `bitvoid.local` through
  Android mDNS, a raw multicast-DNS fallback, or DNS-SD `_http._tcp` discovery.
- `BitVoidNodeClient` supplies persistent local-only HTTP APIs, reconnecting
  `ws://bitvoid.local/ws/chat/{channelId}` messaging, and streaming HTTP `Range` requests for MP4
  playback. Its DNS and socket factories are pinned to the selected ESP Wi-Fi network.
- `BitVoidHardwareConnectionCard` is shown in Client Node mode and offers zero-config status,
  retry controls, and an embedded WebView dashboard. The WebView retries `192.168.4.1` if the
  platform WebView cannot resolve `.local`, while blocking navigation outside local addresses.

The ESP firmware should use SSID `BitVoid_Node`, DHCP gateway `192.168.4.1`, hostname
`bitvoid.local`, HTTP port 80, and (ideally) advertise a `_http._tcp` DNS-SD service whose name
contains `BitVoid`. It should redirect captive probes such as `/generate_204` to
`http://bitvoid.local`.

## Build

1. Install Android Studio with Android SDK 36 and JDK 17.
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
5. In **Settings**, choose a visible Wi-Fi broadcast name and a WPA2 passphrase of 8–63 characters. BitVoid does not provide a password-free network mode.
6. Press **START BITVOID HOST ENGINE**. BitVoid creates a password-protected Wi-Fi Direct group or Android Local Only Hotspot and displays the active credentials. If Android returns an unsecured hotspot, BitVoid refuses it and keeps the vault in server-only mode.
7. Confirm the security card and use the join-share button to send friends the SSID, required passphrase, portal URL, and WPA Wi-Fi QR payload.
8. Nearby viewers join the displayed network and open `http://bitvoid.local`. BitVoid advertises that stable name over local multicast DNS, while the displayed IP endpoint remains available as a fallback. Captive-probe requests that reach BitVoid are redirected to the friendly hostname. The first screen asks for a Private Room ID, optional passkey, and chat alias; the public broadcast remains at `/public`.
9. In another BitVoid app, open **CLIENT NODE**, scan, choose the Wi-Fi already connected to that phone, and tap **CONNECT NODE**. BitVoid does not switch Wi-Fi; it discovers the gateway through the current network. It also performs this discovery silently while the app is running and links automatically when a BitVoid node appears.
10. Authenticated room members receive an in-memory token, can stream only videos assigned to that room, exchange real-time local chat messages, and share files.
11. From **HUB**, start an audio call with a channel or username, or create a named audio-only Live Class. Share its `CLS-XXXX-XX` ID with students on the same BitVoid network.
12. During a Live Class, the host and students use microphone audio and the participant stage; students can leave and the host can end the class from either the native app or local browser portal.

## Room, call, and classroom security model

- Room IDs, passkey verifiers, client sessions, participant state, and chat history live only in memory. Stopping the host node or ending the app process clears all rooms and chat.
- Passkeys are salted and derived with PBKDF2-HMAC-SHA256. Raw passkeys are not stored.
- Private room APIs require `X-Room-Token`; missing, expired, or room-mismatched tokens receive `401 Unauthorized`.
- Native WebSocket clients can send `X-Room-Token` during the upgrade. Browser WebSocket APIs cannot set arbitrary headers, so the bundled portal carries the same bearer token in the `Sec-WebSocket-Protocol` handshake header.
- HTML video elements also cannot set custom request headers. The bundled portal therefore uses an ephemeral token query parameter for private range requests. Tokens are never persisted, responses use `Referrer-Policy: no-referrer`, and the server keeps no access log.
- Chat and WebRTC signaling are host-relayed over the isolated LAN. WebRTC media is peer-to-peer and encrypted by WebRTC. BitVoid-created wireless networks require a WPA2/WPA3 password; room tokens provide an additional application-level access control.
- Call and classroom signaling accepts only local-node requests and validates channel, direct-chat, or Live Class sessions. The peer connection is configured without external ICE servers, so media remains on the local network.
- Live Class IDs map to in-memory host sessions. Ending the class, stopping the host, or ending the app process revokes the session and closes its signaling peers.

## Platform notes

- Android's public API can create a generated secured Local Only Hotspot. BitVoid also attempts a configured, password-protected Wi-Fi Direct group where supported.
- BitVoid never requests or advertises a password-free hotspot. An unsecured system hotspot is rejected as a BitVoid broadcast, and the local vault remains available only through server-only mode until a protected network is present.
- BitVoid returns an absolute `302` portal redirect for common Android, Apple, Windows, Firefox, and Kindle probe paths and known probe Host headers. It advertises `bitvoid.local` with mDNS and serves the same portal on port 80 when Android permits that binding; otherwise the hostname is advertised with port 8080. A normal app cannot replace hotspot DHCP/DNS, transparently intercept every connectivity-check domain, install a trusted TLS certificate, or force every OEM captive-portal assistant to appear. The displayed hostname and IP fallback remain available.
- Local Only Hotspot availability is device- and policy-dependent. Some managed devices or vendor Wi-Fi implementations can reject the request.
- Android may throttle Wi-Fi scans. BitVoid falls back to the most recent permitted scan results.
- An already-open media response cannot be recalled after bytes have been sent. Switching a video to Private removes it from the next public index poll and makes subsequent public direct/range requests return `403`.
- Byte-range responses necessarily expose the requested range and exact range length to make standards-compliant seeking possible; BitVoid pads privacy metadata and block-pads portal HTML without corrupting video payloads.

BitVoid does not create a VPN, Tor circuit, or internet anonymity service. Its privacy controls apply to this isolated local exchange only.
