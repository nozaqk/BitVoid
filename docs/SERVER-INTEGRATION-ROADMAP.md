# BitVoid Android — Phase 1 Server Integration Roadmap

**Audit date:** 2026-11-27  
**Audited Android main commit:** [`7f2aa122166991bef1d4ae69be4456cb9abc0ced`](https://github.com/nozaqk/BitVoid/commit/7f2aa122166991bef1d4ae69be4456cb9abc0ced)  
**Change type:** Documentation only. No Android application source or workflow was changed.

The full cross-repository architecture and Server roadmap is maintained in [BitVoid Server — Phase 1 Architecture Audit](https://github.com/nozaqk/Bitvoid-Server/blob/docs/phase1-architecture-audit/docs/PHASE-1-ARCHITECTURE-AUDIT.md).

## 1. Approved client boundary

- Android is the native end-user communication app.
- The installed BitVoid Server desktop application owns the background Server service and all server-wide administration.
- Android must not administer Nodes, service lifecycle, Server settings, backups, network interfaces, or Server updates.
- The app connects to a self-hosted Server over a local hotspot/router LAN, or to a centrally hosted Server over an explicitly trusted VPN/private link.
- Local communication must not require Internet access.
- No website, browser dashboard, Android WebView portal, or ESP-12E dependency is part of the target architecture.

## 2. Current Android integration findings

### Authentication is split into two paths

- `BitVoidAccountAuth.kt` calls `POST /api/v1/account/register`, `/login`, and `/recover`. These routes exist in the current Server.
- `BitVoidViewModel` invokes the account-auth flow and stores its account token using Android Keystore-backed AES-GCM preferences.
- `BitVoidLocalApi.kt` and `BitVoidLocalViewModel.kt` still implement an older device-pairing path. They call `/api/v1/pairing/request`, `/api/v1/users/register`, conversations, and message routes that the current Server does not implement.
- The pairing/device token is stored separately in `BitVoidLocalCredentials.kt`; it is not the same stored credential as the account token in `BitVoidAccountAuth.kt`.

**Required:** use one account login/session, explicitly enroll each Android installation as a device belonging to a Node, and use the resulting revocable device-associated session for REST and WebSocket. Do not require a separate Gateway/admin approval for normal account login.

### API contract mismatches

| Android client call | Current Server support | Required work |
|---|---|---|
| `/api/v1/account/register` | Yes | Keep and contract-test |
| `/api/v1/account/login` | Yes | Keep and contract-test |
| `/api/v1/account/recover` | Yes | Keep; test recovery and session revocation |
| `/api/v1/pairing/request` | No | Replace with device enrollment after account authentication |
| `/api/v1/users/register` | No | Remove duplicate user creation path or map to a single Node profile operation |
| `/api/v1/me` | Yes, returns `data.account` | Update Android parser; the current local parser expects `user`, `profile`, or root-level username fields |
| Conversations/messages/channels/Live Classes/sync | No | Implement incrementally from the single Server-owned contract |
| WebSocket | Authentication and ping/pong only | Add authorized subscriptions, event fan-out, durable IDs, reconnect sync |

The Android `docs/SERVER-API-V1.md` currently describes the older pairing contract and diverges from `Bitvoid-Server/docs/SERVER-API-V1.md`. Keep one authoritative API contract in the Server repository and reference it here.

### Discovery and network profiles

- Android defaults to `http://bitvoid.local:8787`.
- Android README documentation describes `bitvoid-server.local` and `_bitvoid-server._tcp`.
- Android code resolves `bitvoid.local` and scans `_http._tcp.`.
- Server Bonjour currently advertises an HTTP service named `BitVoid Server`; its docs describe a different custom service name and hostname.

Standardize DNS-SD service type, hostname, TXT records, port, and Server ID verification. mDNS should only be used on the same link. For routed VPN/multiple-site access, use a trusted DNS/IP profile plus verified TLS identity; do not rely on mDNS multicast.

The current Android Server URL allowlist is limited to `bitvoid.local`, loopback, and private/link-local IPv4 ranges. It will reject many VPN DNS names, overlay-VPN addresses, and IPv6 ULA. Replace this with explicit trusted Server profiles and certificate/Server-ID verification, not an unrestricted public-host allowlist.

### Legacy Android-hosted Server

The repository still contains `BitVoidLocalServer.kt` (NanoWSD on port 8080) and `BitVoidForegroundService.kt`, which can start an embedded local HTTP server. This is legacy architecture that must be migrated before Android becomes a pure client.

Do not delete these components in one step. First map their data, routes, local media, chat/signaling, notifications, and foreground-service responsibilities to the desktop Server or legitimate Android client background work. Migrate pending messages and local data, test recovery, then remove the embedded Server authority.

### Transport security

The Android manifest currently sets `usesCleartextTraffic="true"`. Keep HTTP only in a controlled development/local test configuration. Production and VPN profiles need HTTPS/WSS with proper certificate validation and trusted Server identity. Never use trust-all TLS.

## 3. Android build and release audit

- The app uses Jetpack Compose, Kotlin 2.2.10, Android Gradle Plugin 9.1.1, minSdk 26, targetSdk 36, and app version 1.5.1 (versionCode 9).
- Android unit tests exist. PR #63 reported 53 passing unit tests and a successful debug APK build at that earlier commit.
- PRs #67 and #68 explicitly say Android build, unit test execution, and physical phone/LAN discovery were not run for those changes.
- The current `.github/workflows/release.yml` is triggered by pushes to all branches, tags, and manual dispatch. It builds a **debug APK**, also builds legacy ESP32/ESP-12E and Raspberry Pi controller components, and deletes older GitHub Releases before creating the next one.
- The workflow must be redesigned for the approved Android-only client scope. In particular, preserve release history; never delete older releases during publication. Keep Server desktop artifacts in the Server repository's separate release workflow.

## 4. Android implementation sequence

### A1 — Unify identity and session handling

1. Make username/password register, login, and recovery the only normal account-access path.
2. Keep the 44-character recovery key one-time display and secure local storage.
3. Add authenticated device enrollment, with a stable device ID and platform/app metadata.
4. Unify the account token and device session lifecycle; support revoke-current-device and revoke-other-device actions.
5. Remove the separate mandatory pairing flow after compatibility tests prove it is no longer used.

### A2 — Align the API client

1. Use the Server API contract as the single source of truth.
2. Align response envelopes, especially `GET /api/v1/me`.
3. Add typed request/response models and contract tests for every Android API call.
4. Add authorization, error mapping, idempotency, cursor sync, and WebSocket reconnection tests.
5. Do not expose a UI feature as ready until the Server endpoint and Android repository both work.

### A3 — Discovery and trusted Server profiles

1. Standardize DNS-SD with the Server implementation.
2. Verify Server ID after discovery; discovery itself is not authentication.
3. Add QR/manual trusted endpoint setup for routed/VPN deployments.
4. Bind local sockets to the selected Wi-Fi network when using a local-only hotspot.
5. Support private VPN DNS/IP and IPv6 after identity/certificate verification.
6. Never silently fall back to a public cloud endpoint when local Server access fails.

### A4 — Migrate client data and remove Android Server authority

1. Define migration for existing local messages, profiles, files, and queued sends.
2. Move authoritative persistence and cross-device messaging to the desktop Server.
3. Keep only Android-appropriate background tasks such as notification delivery, uploads/downloads, reconnect, and calls.
4. Remove the embedded Android HTTP Server only after migration and regression tests pass.

### A5 — Validate end to end

Required scenarios:
- Account create/login/recovery against the current Server.
- One Node logged in on multiple Android devices.
- Session revoke and recovery invalidation.
- Same-PC hotspot without Internet.
- Existing router Wi-Fi and Ethernet LAN.
- Server restart, Android process restart, Wi-Fi loss/reconnect, and queued-message retry.
- Trusted two-site VPN Server profile.
- Certificate mismatch, wrong Server ID, revoked session, and expired token.
- Android release build and signed artifact verification.

## 5. Phase 1 validation status

This is an audit and roadmap only. No Android source, Gradle configuration, manifest, release workflow, or test code was changed. The Android build and tests were not run in this execution environment; historical PR validation statements are not treated as current-main verification. Physical phone, hotspot, router, and VPN tests remain outstanding.

**Next phase:** implement the unified Server account/device session contract and Android contract tests before expanding messaging endpoints or retiring legacy Android server code.
