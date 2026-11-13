# BitVoid

BitVoid is an offline-first Android local node. A host phone creates an Android **Local Only Hotspot**, keeps it alive with a foreground service, and serves app-scoped files over an embedded NanoHTTPD portal. Peers can connect without a router, SIM, ISP, cloud service, or account.

## Features

- Kotlin, Jetpack Compose, Material 3, coroutines, minSdk 26, target/compile SDK 35
- Persistent connected-device foreground service and low-importance notification
- Public `WifiManager.startLocalOnlyHotspot()` host implementation
- Dynamically resolved hotspot address and portal URL
- Responsive, self-contained dark local web portal on TCP 8080
- RFC 7233 single-range streaming with `206 Partial Content` for media seeking
- Storage Access Framework importer into app-scoped `BitVoid_Shared` folders
- No request logs, no client IP/MAC retention, ephemeral per-response session IDs
- Identifying request headers discarded by the server
- Fixed-size privacy padding header and 4 KiB-block portal body padding
- Peer scanner, manual SSID/passphrase connection, and app-to-network binding
- Sandboxed local WebView with JavaScript/WebRTC, cookies, persistent cache, external hosts, redirects, camera, microphone, and geolocation blocked
- Sanitizing WebView transport that forwards only range semantics and a generic client identity

## Project structure

```text
app/src/main/java/com/bitvoid/network/
├── BitVoidClientManager.kt
├── BitVoidDashboard.kt
├── BitVoidForegroundService.kt
├── BitVoidLocalServer.kt
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
2. In **HOST NODE**, add files. Chosen content is copied into app-scoped `BitVoid_Shared` storage.
3. Press **POWER NODE**. Keep the generated SSID, passphrase, and portal URL visible.
4. On another phone or computer, join that SSID and open the displayed `http://<gateway>:8080` URL.
5. A second BitVoid phone can use **CLIENT NODE** to scan or enter the credentials manually, approve Android's Wi-Fi connection prompt, and open the sanitized viewer.

## Platform notes

- Android's public Local Only Hotspot API generates the SSID and WPA2 credentials. Third-party apps cannot reliably force a custom hotspot name on stock Android, so BitVoid displays the exact system-generated SSID for peers to use.
- Local Only Hotspot availability is device- and policy-dependent. Some managed devices or vendor Wi-Fi implementations can reject the request.
- Android may throttle Wi-Fi scans. BitVoid falls back to the most recent permitted scan results.
- Byte-range responses necessarily expose the requested range and exact range length to make standards-compliant seeking possible; BitVoid pads privacy metadata and block-pads the portal without corrupting media payloads.
- The server binds to the resolved hotspot interface rather than exposing the portal on every phone interface.

BitVoid does not create a VPN, Tor circuit, or internet anonymity service. Its privacy controls apply to this isolated local exchange only.
