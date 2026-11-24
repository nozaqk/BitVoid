# BitVoid

**BitVoid is an offline-first, local-network communication and media-sharing platform for Android.**

It turns a nearby Android phone or compatible BitVoid gateway into a private local hub. People connected to the same Wi-Fi network can discover the hub, browse shared videos, join private rooms, exchange messages and files, and participate in audio calls or live audio classes — without requiring the public internet, a cloud account, or a third-party messaging server.

BitVoid is designed around local ownership: the host controls the content, rooms, participants, and network. The Android application opens directly to its conversation interface, automatically prefers a healthy campus gateway, can fall back to a configured compatible HTTPS deployment, and queues messages on device when neither is available. The app can operate as both a host and a client. The wider project also includes hardware gateway controllers so a dedicated board can eventually provide the central network and server role.

> **Project status:** The Android application already contains the local hosting, media, rooms, chat, audio-call, Live Class, portal, and mesh-discovery systems described below. The Universal Gateway API v1 and ESP-12E controller are an active hardware-portability implementation. Secure gateway pairing, complete API-v1 support on every controller, and the final automatic Android-to-hardware Wi-Fi onboarding flow are not yet complete. See [Hardware gateway roadmap](#hardware-gateway-and-board-independence).

---

## Contents

- [What BitVoid is](#what-bitvoid-is)
- [Project goals](#project-goals)
- [How BitVoid works](#how-bitvoid-works)
- [Main application features](#main-application-features)
- [Application areas](#application-areas)
- [Hosting and connection modes](#hosting-and-connection-modes)
- [Media sharing and privacy](#media-sharing-and-privacy)
- [Rooms, chat, calls, and Live Classes](#rooms-chat-calls-and-live-classes)
- [Groups and broadcast Channels](#groups-and-broadcast-channels)
- [Space API and persistence migration](#space-api-and-persistence-migration)
- [Node Log and network discovery](#node-log-and-network-discovery)
- [Automatic conversations and backend selection](#automatic-conversations-and-backend-selection)
- [Local web portal](#local-web-portal)
- [Architecture](#architecture)
- [Hardware gateway and board independence](#hardware-gateway-and-board-independence)
- [Security and privacy model](#security-and-privacy-model)
- [Technology stack](#technology-stack)
- [Repository structure](#repository-structure)
- [Requirements](#requirements)
- [Build and run](#build-and-run)
- [Using BitVoid](#using-bitvoid)
- [Network behavior and platform limitations](#network-behavior-and-platform-limitations)
- [Current implementation status](#current-implementation-status)
- [Development principles](#development-principles)
- [Contributing](#contributing)

---

## What BitVoid is

BitVoid is not a conventional cloud-dependent social network. It is a local-first application that creates or joins a private network and provides communication services inside that network.

The project combines several functions in one system:

- A local media library and video-broadcasting service.
- Public broadcasts and access-controlled private rooms.
- Real-time channel chat and direct messaging.
- Local file and attachment sharing.
- Audio-only voice calls.
- Audio-only live classes for hosts and students.
- A browser portal for devices that do not have the Android application.
- Automatic discovery of BitVoid Android nodes and compatible hardware controllers.
- A planned universal gateway interface that separates the Android app from the physical board running the gateway.

A BitVoid network can be created by a host Android phone or, as the hardware platform matures, by a dedicated BitVoid gateway. Other phones and computers connect as clients through the same local network.

BitVoid does not require a public server to deliver its local-network features. If the local network has no internet connection, BitVoid's local features can still work as long as the participating devices can communicate with one another.

## Project goals

1. **Offline-first communication:** local media and communication should not depend on an internet connection or cloud relay.
2. **One local environment:** users should experience one BitVoid network rather than separate services tied to individual board vendors.
3. **Privacy by default:** imported media starts private, private rooms require authorization, and transient communication state is not retained as a permanent cloud history.
4. **Hardware independence:** the Android application should communicate through a stable BitVoid protocol rather than board-specific code.
5. **Multi-device access:** Android users and supported browser clients should be able to use the same local services.
6. **Capability-aware operation:** a small microcontroller should expose only what it can reliably run; a more capable board can provide a larger service set.
7. **No hidden universal credentials:** identity, Wi-Fi access, and application authorization are separate concerns.

## How BitVoid works

At a high level, a BitVoid session has three parts:

1. **Host or gateway:** provides the local network endpoint and runs the available BitVoid services.
2. **Client devices:** connect to the same Wi-Fi/LAN and use the Android application or browser portal.
3. **Local services:** provide media catalogues, room authorization, messaging, attachments, calls, classes, and device discovery according to the host's capabilities.

### Android-hosted operation

In the current Android-hosted mode, one phone starts the BitVoid host engine. Android provides a local Wi-Fi network where supported, and the app's foreground service keeps the local server running while the host is active. The server exposes HTTP APIs and WebSocket endpoints for the portal and connected clients.

### Dedicated gateway operation

The hardware-gateway design moves the central network/server role from the phone to a dedicated controller. Android then acts as a client of that gateway. The long-term target is that an ESP32, Raspberry Pi, or another conforming board can be substituted without changing the Android application.

The current ESP-12E implementation is an initial access-point and discovery target. It is not yet a complete BitVoid application server and does not provide the full Android-hosted feature set.

---

## Main application features

### Local media library

- Import video files through Android's Storage Access Framework.
- Copy selected files into app-scoped `BitVoid_Shared` storage.
- Keep a local media catalogue with Public or Private visibility.
- New imports default to **Private**.
- The host can change each video's visibility.
- The host interface displays the number of videos currently available for public broadcast.
- Media playback supports HTTP byte-range requests so compatible players can seek through a video.

### Public video broadcasts

- Public videos are listed in the public portal.
- Clients can browse and play the videos over the local network.
- The public browser polls periodically for catalogue changes.
- Private videos are not included in the public listing.
- Direct requests for private video resources are rejected by the server.

### Private rooms

- The host creates a room and assigns private videos to it.
- A room may use an optional passkey.
- Passkeys are processed with PBKDF2-HMAC-SHA256; raw passkeys are not stored.
- Successful room access receives an expiring, random room token.
- Private room APIs and media routes require the room token.
- Room membership and authorization are scoped to the active host session.

### Real-time communication

- A conversation-first Chats screen with search, previews, timestamps, unread counters, presence, and delivery state.
- Channel-based chat over WebSockets.
- Direct messages between users who are active on the same BitVoid node.
- Stable client message IDs, idempotent replay, duplicate suppression, preserved timestamps, and ordered history merging.
- Bounded on-device conversation history and a durable outgoing queue. Android-hosted group/channel definitions, memberships, invitation hashes, media mappings, and bounded channel histories are stored in a versioned SQLite database; scoped authorization sessions remain ephemeral.
- File attachments shared through the local service.
- Android notifications for incoming remote channel and direct messages while the foreground service is active.

### Groups

- Create a group with a name, description, optional profile picture, and members selected by username.
- The creator is the owner; groups support owner, administrator, and member roles.
- Owners and administrators can add or remove members and edit group information; only the owner can promote or demote administrators and delete the group.
- Every current member can send messages and supported attachments. Non-members cannot obtain a group session or read its information.
- Members may leave; the owner must delete the group rather than leaving it ownerless.

### Broadcast Channels

- Create public/discoverable or private/invitation-only Channels with a name, description, and optional profile picture.
- Channels support owner, administrator, and subscriber roles. Only the owner and administrators can publish posts or attachments; this is enforced by the embedded server for REST, uploads, and WebSockets.
- Users can search public Channels, subscribe, unsubscribe, join a private Channel with a limited-use expiring invitation, and view subscriber/administrator information.
- The owner can change visibility, manage administrators, or delete the Channel. Owners and administrators can create shareable private invitations.
- Groups and subscribed Channels appear in the existing conversation list and use the existing conversation, attachment, notification, cache, and retry paths.

### Audio calls

- Local audio-only WebRTC calls.
- Microphone mute controls and speaker routing.
- Host-relayed signaling through the local BitVoid server.
- No external STUN or TURN service is configured; the intended media path is the local network.

### Live Classes

- Audio-only classroom sessions.
- Host and student participant roles.
- Shareable class identifiers in the `CLS-XXXX-XX` format.
- Host-controlled visible or hidden Hub listings.
- Microphone controls and synchronized participant state.
- Native Android and browser meeting interfaces.
- Students can leave; the host can end the class.

### File and attachment sharing

- Clients can send file attachments through the local communication service.
- The browser portal displays attachment cards with file information and supported previews.
- Open and download actions are served from the local host.
- Upload progress is presented in the browser interface.

### Local network discovery

- BitVoid identifies local nodes through its public mesh identity protocol.
- Android Node Log records discovery, coordinator heartbeats, gateway selection, reconnects, and errors in memory.
- The app can discover compatible Android, ESP32, and Raspberry Pi nodes on the same LAN.
- The system supports manual local IP/URL entry as a fallback when automatic discovery is unavailable.

---

## Application areas

The names below describe the functional areas of the current application. Exact navigation and screen presentation may evolve while the underlying services and APIs remain compatible.

### Host Node

The host area manages the content and services provided by the current Android host:

- Import and manage videos.
- Change media visibility.
- Create and manage private rooms.
- Assign private videos to rooms.
- View public broadcast status.
- Start or stop the host engine.
- View connected room participants and relevant host activity.

### Hub

The Hub is the communication and live-session area:

- Browse available channels and participants.
- Open channel conversations.
- Start direct messages with active local users.
- Start audio calls.
- Create or join Live Classes.
- View participant and microphone state.

### Node Log

Node Log is the local connectivity and diagnostics area:

- View detected Wi-Fi/network information.
- Review local node discovery and identity checks.
- See ESP32/Pi coordinator heartbeats and gateway selection.
- Inspect connection, retry, and error events.
- Refresh discovery when needed.
- Use a verified manual local endpoint when automatic discovery fails.
- View the optional direct hardware connection status where supported.

### Settings

Settings contains local host and application configuration, including the visible network name and protected network credentials where the Android platform allows the app to configure them. Android system dialogs and restrictions remain authoritative for Wi-Fi operations.

### Browser portal

The browser portal allows devices on the same local network to use supported BitVoid features without installing the Android app. Available portal functions depend on the host and the service session.

---

## Hosting and connection modes

### Android Local Only Hotspot

Where supported by the device and Android version, BitVoid requests an Android Local Only Hotspot. Android supplies the hotspot credentials. BitVoid displays the network name and passphrase to the host so they can be shared with invited clients.

This is a local network; it does not automatically provide internet access.

### Android Wi-Fi Direct group

BitVoid also attempts a configured protected Wi-Fi Direct group on supported devices. Actual support depends on the Android device, operating system, and vendor implementation.

### Existing system hotspot or Wi-Fi LAN

The project includes support paths for using an already-running system hotspot or a shared local Wi-Fi network. The phone and clients must be able to reach each other; client isolation on the router or access point can prevent communication.

### Dedicated hardware access point

The ESP-12E target currently creates a protected Wi-Fi access point with a configured SSID and password. Its default development address is `192.168.4.1`. Its current firmware exposes discovery and health information, not the full application services.

### Connection sharing

The host can share the network name, passphrase when applicable, portal URL, and a standard Wi-Fi QR payload. Android may display its own confirmation prompts when joining a network. The app cannot silently bypass Android's Wi-Fi approval and security controls.

---

## Media sharing and privacy

BitVoid separates public media from private media at the server boundary.

- Imported videos start as Private.
- The host explicitly changes a video to Public when it should appear in the public broadcast.
- Private videos are assigned to rooms and are served only after room authorization.
- Public catalogue responses do not expose private video IDs.
- Private media requests without a valid room token are rejected.
- Media uses HTTP range responses for seeking where supported.

The media catalogue and its visibility state are managed by the active host. This project does not currently describe a cloud-synchronized library or automatic backup service.

## Rooms, chat, calls, and Live Classes

### Room access lifecycle

1. The host creates a room and chooses its ID.
2. The host optionally configures a passkey.
3. The host assigns one or more Private videos to that room.
4. A client enters the room ID, optional passkey, and a chat alias in the portal.
5. The server validates the request and issues an expiring in-memory room token.
6. The client uses that token for private room APIs and media access.
7. When the host session ends, transient room state and tokens are cleared.

### Chat and direct messages

Channel and direct chat use WebSocket connections to the selected backend. Direct messaging and calls require the intended username to be active on that BitVoid node. The Android client keeps a bounded local conversation cache and durable outgoing queue. Android-hosted group/channel history is now bounded and durable in `bitvoid_spaces.db`; direct-message, room, presence, and authorization-session state remains process-local.

### Voice and Live Classes

WebRTC handles audio media, while the BitVoid host coordinates signaling and session state. The current configuration does not use public STUN/TURN servers. As a result, calls and classes are intended for devices that can establish a media path over the local network.

## Groups and broadcast Channels

Groups and Channels extend the existing channel transport; they do not create a disconnected messaging stack. A successful join issues an encrypted, process-scoped channel session containing the current role and space type. Membership and role changes revoke affected sessions so the client must rejoin with fresh authorization.

- **Groups:** membership is explicit. A username not present in the membership table cannot join, read group details, post, or upload.
- **Public Channels:** discovery returns public broadcast Channels. Subscription creates a subscriber membership before opening the conversation.
- **Private Channels:** discovery does not reveal them. Joining requires an unexpired, unused invitation. Only the invitation hash is retained by the server.
- **Publishing:** owner/administrator authorization is checked by the server for HTTP message posts, multipart uploads, and incoming WebSocket frames. Subscriber-only clients also hide the composer, but that UI check is not a security boundary.
- **Conflicts and replay:** messages retain client UUIDs, original timestamps, `Idempotency-Key` support, and stable-ID deduplication. Membership and settings mutations are applied transactionally to the local SQLite store. A role change invalidates old tokens instead of attempting to merge stale privileges.
- **Offline behavior:** already-open Android conversations retain their bounded local cache and outgoing queue. Queued group messages retry after a valid member session is reopened. Subscribers are never allowed to queue Channel posts.

Current identity note: the embedded local gateway issues a 256-bit, expiring `X-BitVoid-Identity-Token` when the active app/browser username registers. Creation, typed-space joins, subscriptions, and invitation joins require that token to match the submitted active username; later mutations are bound to a validated scoped space token. This protects request identity within one gateway session. A separate production cloud service must still authenticate durable account ownership before enrollment (the local first-use username registry is not a password service); node identity discovery alone is not account authentication.

## Space API and persistence migration

The embedded service exposes these JSON routes in addition to the existing `/api/channels/{id}/chat`, `/upload`, and `/ws/chat/{id}` transports:

| Method | Route | Purpose |
|---|---|---|
| `POST` | `/api/spaces/groups` | Create a group and initial username membership |
| `POST` | `/api/spaces/channels` | Create a public or private broadcast Channel |
| `GET` | `/api/spaces/discover?q=` | Search public broadcast Channels |
| `GET` | `/api/spaces/{id}` | Read authenticated space information and members |
| `POST` | `/api/spaces/{id}/subscribe` | Subscribe to a public Channel |
| `POST` | `/api/spaces/{id}/unsubscribe` | Unsubscribe with the current scoped token |
| `POST` | `/api/spaces/invitations/join` | Join a private Channel invitation |
| `POST` | `/api/spaces/{id}/invitations` | Create a limited-use invitation as owner/admin |
| `POST` | `/api/spaces/{id}/members/add` | Add a group member as owner/admin |
| `POST` | `/api/spaces/{id}/members/remove` | Remove an authorized member/subscriber |
| `POST` | `/api/spaces/{id}/members/role` | Promote/demote an administrator as owner |
| `POST` | `/api/spaces/{id}/settings` | Update name, description, picture, or owner-only visibility |
| `POST` | `/api/spaces/{id}/leave` | Leave a group or unsubscribe from a Channel |
| `POST` | `/api/spaces/{id}/delete` | Owner-only permanent deletion |

The Android-hosted database is `bitvoid_spaces.db`, schema version **1**. It contains normalized `spaces`, `space_members`, `space_invitations`, `space_media`, and `channel_messages` tables with cascading foreign keys. This is the first durable-space schema; upgrading from earlier releases requires no destructive migration because those releases kept channel definitions in process memory. Existing accounts, direct conversations, rooms, and media files are not rewritten. Session tokens and live WebSocket presence are intentionally not persisted and are safely reissued after restart. Future schema versions must use forward-only `SQLiteOpenHelper.onUpgrade` migrations and must not drop conversation or membership data.

SQLite durability is local to the Android host. Campus/cloud synchronization uses the selected backend and existing idempotent message replay, but this repository does not include a multi-node consensus service or production account-authenticated cloud database. Deployments that synchronize one space across independent gateways must provide canonical account identity, revision/conflict policy, and the same authorization contract server-side rather than merging SQLite files.

---

## Node Log and network discovery

BitVoid uses a shared local identity protocol so different types of nodes can be recognized without trusting a board-specific secret.

The current mesh identity protocol uses:

- Service name: `bitvoid`
- Identity mode: `local-identity-v1`
- Legacy mesh protocol version: `2`
- Identity endpoint: `GET /api/node`
- Registry endpoint: `GET /api/mesh/nodes`
- Registration endpoint: `POST /api/mesh/register`

Identity checks help identify BitVoid-compatible nodes on the local network. They are **not cryptographic authentication**. A public identity response must not be treated as proof that a device is trusted or authorized to access private services.

Android discovery can scan the permitted local subnet, exchange coordinator registries, and select an available app gateway. Discovery behavior is subject to Android Wi-Fi scan permissions, throttling, multicast handling, and network topology.

---

## Automatic conversations and backend selection

BitVoid loads the **Chats** interface immediately; connectivity checks never block navigation. In the background it uses existing mesh discovery, the configurable campus LAN fallback, and health checks to select a backend in this order:

1. A healthy discovered or configured campus gateway.
2. The configured compatible HTTPS Render backend.
3. Offline mode with queued sending and cached conversation history.

The coordinator responds to Android network changes, releases campus process-network binding before cloud use, reconnects with capped exponential backoff, reopens the active conversation, and replays pending messages. Client UUIDs, original timestamps, `Idempotency-Key`, and server stable-ID checks make REST/WebSocket replay idempotent. The cache stores bounded messages and delivery states, not passwords or backend session tokens.

Set a build-time cloud default without committing credentials:

```bash
BITVOID_CLOUD_BASE_URL=https://your-bitvoid-service.onrender.com ./gradlew assembleDebug
```

The value may also be supplied as a Gradle property. Users can edit both endpoints under **Settings → Automatic connection**. Campus configuration accepts HTTP only for private/local hosts; cloud configuration requires HTTPS. This repository does not contain a production Render URL, so cloud fallback stays disabled until a real compatible endpoint is configured.

Calls remain audio-only. Internet calls additionally require the cloud service to implement the existing signaling contract and deploy suitable ICE/STUN/TURN infrastructure; the Android repository does not invent or hardcode those credentials.

---

## Local web portal

The embedded portal is served by the active Android host. It provides browser access to supported public and private features.

Portal behavior includes:

- A public broadcast page.
- Private room entry and room-scoped content.
- Browser-based chat and audio meeting interfaces.
- Group/Channel creation, public discovery, private invitation joining, role-aware publishing, and prompt-based information/member/administrator controls.
- Attachment cards and supported file previews.
- Local-only navigation restrictions.
- Captive-network probe redirects for common Android, Apple, Windows, Firefox, and other platform paths where the request reaches the local server.

The portal is not a public website and is not hosted on the internet. Clients must be able to reach the local BitVoid network.

---

## Architecture

BitVoid is organized into layers:

### Android application layer

Provides the native user interface, host/client workflows, permissions, local media import, and lifecycle integration.

### Host service layer

The Android foreground service manages the active host lifecycle and keeps the local server operating while Android permits it.

### Local API and transport layer

The embedded HTTP server handles REST-style requests, static portal resources, media responses, and WebSocket upgrades for chat and signaling.

### Feature services

Feature packages manage media, rooms, channels, direct messages, calls, classes, and client state. Services should use common interfaces and avoid depending on the physical gateway board.

### Discovery and mesh layer

Provides node identity, local discovery, coordinator registration, heartbeats, and endpoint selection.

### Hardware adapter layer

Board-specific firmware handles hardware operations such as Wi-Fi access point creation, local networking, storage, and peripheral interfaces. The goal is for each adapter to expose the same versioned BitVoid Gateway API.

### Architectural direction

The long-term system separates the BitVoid application protocol from its physical deployment:

```text
                    BitVoid Android App
                            |
                  Universal Gateway API
                            |
                  BitVoid Gateway Core
                            |
                   Hardware Adapter
                            |
          +-----------------+------------------+
          |                 |                  |
       ESP-12E             ESP32           Raspberry Pi
     (limited)        (capability-based)    (fuller host)
```

This is the target architecture. Not every board currently implements every layer or service.

---

## Hardware gateway and board independence

The goal is to make the Android application independent of the board that hosts BitVoid.

Android should communicate with a stable, versioned API and discover the gateway's capabilities at runtime. It should not contain separate feature implementations for ESP-12E, ESP32, Raspberry Pi, or future hardware.

### Universal Gateway API v1

The protocol contract is documented in [`docs/gateway-api-v1.md`](docs/gateway-api-v1.md).

The initial contract defines:

- `GET /api/v1/gateway/info` — gateway identity, protocol version, hardware metadata, and capabilities.
- `GET /api/v1/health` — health and readiness.
- `GET /api/v1/services` — available services.
- `GET /api/v1/devices` — connected or registered devices.
- `GET /api/node` — legacy mesh identity compatibility.
- Planned pairing endpoints and optional event transport.

Android must use advertised capabilities to decide which features are available. It must not assume that a gateway supports media, storage, messaging, rooms, or WebRTC merely because it is a BitVoid gateway.

### Controller targets

| Controller | Intended role | Current status |
|---|---|---|
| ESP-12E / ESP8266 | Low-resource Wi-Fi access point and discovery gateway | Initial API-v1 discovery/health firmware added; not a full app server |
| ESP32 | Wi-Fi controller and local coordinator | Existing legacy mesh controller; API-v1 adapter still required |
| Raspberry Pi | More capable central gateway and application host | Existing legacy coordinator; API-v1 adapter still required |
| Future boards | Compatible gateway hardware | Must implement the published protocol and declare actual capabilities |

### What board replacement means

When a replacement board implements the same Gateway API version and the capabilities required by the app, Android should not need board-specific code changes. The replacement board still needs compatible firmware.

Gateway identity and stored data do not automatically move with the physical board. If the gateway identity, keys, or data must survive a hardware replacement, they need an explicit secure migration or backup/restore process.

### Current ESP-12E limitations

The ESP-12E is based on the ESP8266 and has constrained memory and processing resources. The current firmware is intended to validate the Wi-Fi access point, gateway identity, API discovery, and health flow.

It does not currently implement:

- Secure application device pairing and token authentication.
- The full BitVoid media server.
- Persistent media storage and streaming.
- Full room, chat, and attachment services.
- WebRTC signaling and audio media hosting.

The firmware returns HTTP 501 for the pairing endpoints until the secure pairing flow is implemented. Do not treat the current ESP-12E build as a production trusted BitVoid server. A Raspberry Pi-class system is the more appropriate target for the full application workload.

### Automatic Android connection

The intended onboarding flow is:

1. Discover the gateway through an available local discovery method.
2. Verify its BitVoid protocol version and gateway identity.
3. Ask Android to connect to the protected gateway Wi-Fi when needed.
4. Complete first-time user approval in the Android system interface.
5. Pair the app with the gateway using a device-specific authorization process.
6. Store the resulting credentials securely on Android.
7. Reconnect using the saved gateway configuration when Android permits it.

Android controls Wi-Fi approval and may show a system prompt. BitVoid cannot guarantee silent connection to an arbitrary protected network. The complete automatic connection and secure pairing flow remains future implementation work.

---

## Security and privacy model

BitVoid is designed to reduce unnecessary data collection and keep local activity within the local network. The current implementation includes the following protections and limitations.

### Implemented protections

- Private media is excluded from public catalogue responses.
- Private room APIs require an expiring room token.
- Optional room passkeys are derived with PBKDF2-HMAC-SHA256; raw passkeys are not stored.
- Room tokens, identity/space authorization tokens, WebSocket presence, direct-message history, and room history are held in memory.
- Group/Channel membership, hashed invitations, settings, media mappings, and bounded Channel history are durable in the Android host's private SQLite database.
- Typed-space joins and identity-bearing mutations require a matching expiring gateway identity token; role and membership mutations require a scoped space token.
- The server does not retain request logs or client IP/MAC histories in its application request flow.
- Identifying request headers are discarded by the server.
- The portal restricts navigation to local addresses and disables persistent cookies/cache and browser access to camera, microphone, and geolocation.
- WebRTC media uses WebRTC's encrypted media transport.
- The mesh identity protocol does not embed a universal secret in the APK or firmware.

### Important distinctions

- **Wi-Fi security** controls who can join the wireless network.
- **Gateway authentication** proves that an app/device is authorized to use gateway services. This is not complete on the current ESP-12E firmware.
- **Room authorization** controls access to private room resources within an active Android-hosted session.
- **Mesh identity** identifies a node; it is not cryptographic authentication.

### Data lifecycle

Room IDs, passkey verifiers, room tokens, identity/space sessions, direct-message and room history, and participant presence are held in memory by the active host. Stopping the host or ending the app process clears that transient state. Group/Channel definitions, membership, hashed invitations, settings, media mappings, and bounded Channel posts survive in `bitvoid_spaces.db`. Imported media files remain in app-scoped storage until removed by the user or the application.

BitVoid is not a VPN, Tor client, internet anonymity system, or end-to-end cloud backup service. Its privacy protections apply to the local services and data flows described in this project.

---

## Technology stack

### Android

- Kotlin
- Jetpack Compose
- Material 3
- Kotlin Coroutines and StateFlow
- Android Wi-Fi, Wi-Fi Direct, Local Only Hotspot, and network APIs
- Android foreground services
- NanoHTTPD and NanoWSD for embedded HTTP/WebSocket hosting
- OkHttp for client networking
- Android SQLite (`SQLiteOpenHelper`) for durable Groups/Channels
- Android Media3 / ExoPlayer for media playback
- WebRTC for audio calls and Live Classes
- ZXing for Wi-Fi QR payload generation
- Bouncy Castle cryptographic utilities

### Hardware and gateway

- ESP-12E / ESP8266 firmware: PlatformIO, Arduino framework, ESP8266 Wi-Fi libraries, ArduinoJson, LittleFS support.
- ESP32 controller: PlatformIO and ESP32 Arduino framework.
- Raspberry Pi controller: Python service and Linux networking tools.
- Gateway API: versioned HTTP/JSON contract with optional WebSocket events.

---

## Repository structure

```text
BitVoid/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/bitvoid/network/
│           ├── MainActivity.kt
│           ├── call/          # WebRTC calls and signaling
│           ├── channel/       # Channel conversations and participants
│           ├── client/        # Client networking and discovery
│           ├── connection/    # Dedicated gateway connection and API client
│           ├── direct/        # Direct messages
│           ├── media/         # Media catalogue and visibility
│           ├── mesh/          # Shared local node identity and mesh protocol
│           ├── model/         # App and network data models
│           ├── room/          # Private room authorization
│           ├── server/        # Embedded HTTP/WebSocket server
│           ├── service/       # Foreground host lifecycle
│           └── ui/            # Compose screens and theme
├── controllers/
│   ├── esp12e/                # ESP8266 access point and Gateway API v1 starter
│   ├── esp32/                 # ESP32 legacy mesh coordinator
│   └── pi/                    # Raspberry Pi/Linux legacy coordinator
├── docs/
│   └── gateway-api-v1.md      # Hardware-independent gateway contract
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
└── README.md
```

---

## Requirements

### Android development

- Android Studio.
- JDK 17.
- Android SDK 36.
- Gradle wrapper included in the repository.
- Android device running Android 8.0 (API 26) or newer for the current minimum SDK.
- Wi-Fi hardware for local hosting and client connectivity.
- Microphone permission for audio calls and Live Classes.

### Hardware development

For ESP-12E firmware:

- ESP-12E / ESP8266 development board.
- Stable regulated 3.3 V power supply capable of handling Wi-Fi current peaks.
- USB-to-serial adapter with correct 3.3 V logic levels.
- PlatformIO.
- A unique device configuration for each deployed gateway.

For ESP32 and Raspberry Pi targets, see the controller-specific README files.

---

## Build and run

### Android application

1. Clone the repository.
2. Open it in Android Studio.
3. Install Android SDK 36 and JDK 17 if they are not already installed.
4. Allow Gradle to sync.
5. Build the debug application:

```bash
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

You can also build and run the app directly from Android Studio.

### ESP-12E firmware

1. Open `controllers/esp12e/` in PlatformIO.
2. Copy `src/bitvoid_config.example.h` to `src/bitvoid_config.h`.
3. Set a unique gateway ID, SSID, display name, and strong WPA2 password.
4. Do not commit the real device configuration or deployment credentials.
5. Build and flash:

```bash
pio run
pio run -t upload
pio device monitor
```

6. Connect to the configured access point and test:

```text
http://192.168.4.1/api/v1/gateway/info
http://192.168.4.1/api/v1/health
```

The ESP-12E example firmware refuses to start with the placeholder password. This firmware is a development/discovery target, not the full BitVoid service host.

---

## Using BitVoid

### Start a local Android host

1. Install BitVoid on the Android phone that will host the session.
2. Grant the permissions requested for nearby Wi-Fi, notifications, media access, and microphone features when you choose to use them.
3. Open the host area and import the videos you want to make available.
4. Review visibility. New videos are Private; explicitly mark only intended broadcast videos as Public.
5. Create private rooms when restricted media or private participation is required.
6. Configure the visible Wi-Fi network settings available on the device.
7. Start the BitVoid host engine.
8. Review the active network/security information displayed by the app.
9. Share the network details and local portal address with invited participants.

### Join from another device

1. Connect the client device to the same Wi-Fi network as the host.
2. Open the BitVoid application or a browser.
3. Use the local portal address shown by the host. The preferred local hostname is `http://bitvoid.local`; use the displayed IP address and port if hostname discovery is unavailable.
4. For private content, enter the room ID, passkey if configured, and a chat alias.
5. Use the available media, chat, attachment, call, or classroom features.

### Use Node Log

1. Connect the Android device to the same local network as the other BitVoid nodes.
2. Open Node Log.
3. Allow the Wi-Fi/network permissions requested by Android.
4. Review detected nodes, gateway status, and connection events.
5. Use the refresh action if an immediate discovery retry is needed.
6. If discovery cannot find a node, use the manual endpoint only after verifying that it belongs to your local BitVoid network.

---

## Network behavior and platform limitations

- Android Local Only Hotspot availability varies by device, Android release, and device policy.
- Android may require explicit user approval to connect to a protected Wi-Fi network.
- Android can throttle Wi-Fi scans or restrict nearby-network information depending on permissions and OS version.
- Multicast DNS and local broadcast discovery may be restricted by routers, access points, or client isolation.
- Devices on separate isolated SSIDs cannot communicate unless the network infrastructure routes or bridges traffic between them.
- A local-only hotspot does not automatically provide internet access or route internet traffic through the phone.
- A normal Android application cannot replace hotspot DHCP/DNS, transparently intercept every connectivity-check domain, or force every vendor captive-portal assistant to open.
- The app's foreground service can maintain local networking while Android allows it, but an explicit force-stop terminates it.
- Without a cloud relay, clients cannot receive local messages while disconnected from the host network.
- The current hardware gateway API rollout is incomplete; the ESP32 and Raspberry Pi controllers still need API-v1 adapters before they are drop-in replacements for the ESP-12E target.
- ESP-12E resource limits make it unsuitable for the complete media and WebRTC workload.

---

## Current implementation status

| Area | Status |
|---|---|
| Android native application and Compose UI | Implemented |
| Conversation-first Chats UI and compact Home telemetry | Implemented |
| Campus → HTTPS cloud → offline coordinator | Implemented; production cloud URL not bundled |
| Stable message IDs, deduplication, and durable Android outbox/cache | Implemented |
| Android-hosted local HTTP/WebSocket server | Implemented |
| Media import, public/private visibility, and local streaming | Implemented |
| Private rooms and room-token authorization | Implemented |
| Channel chat and direct messaging | Implemented |
| Telegram-style Groups and role/member management | Implemented on Android-hosted backend and clients |
| Broadcast Channels, discovery, subscriptions, invitations, and administrator publishing | Implemented on Android-hosted backend and clients |
| Durable Android-hosted space schema and bounded Channel history | SQLite schema v1 implemented |
| Local file attachments | Implemented |
| Audio-only WebRTC calls | Implemented |
| Audio-only Live Classes | Implemented |
| Browser portal | Implemented |
| Legacy local mesh identity and Node Log discovery | Implemented |
| ESP-12E Wi-Fi AP and Gateway API v1 discovery starter | Initial implementation added; hardware build/test pending |
| Android Gateway API v1 metadata parser | Added; full UI/connection integration pending |
| ESP32 Gateway API v1 adapter | Pending |
| Raspberry Pi Gateway API v1 adapter | Pending |
| Secure hardware gateway pairing and device authentication | Pending |
| Fully integrated Android automatic hardware Wi-Fi onboarding | Pending |
| Hardware replacement compatibility test suite | Pending |

This table describes the repository's intended and implemented scope at the time of this revision. It is not a claim that every feature has been validated on every Android device or hardware board.

### Release validation matrix

Before shipping against a production cloud deployment, run `./gradlew testDebugUnitTest assembleDebug` and complete this two-device matrix with the actual Render URL:

- campus Wi-Fi discovery and configured LAN fallback;
- outside-campus HTTPS cloud selection and authentication;
- local failure → cloud, internet failure → campus, and both unavailable → offline;
- Wi-Fi ↔ mobile transitions and campus recovery preference;
- process restart with offline send, replay, stable ordering, and no duplicates;
- existing channel/direct histories before and after backend switching;
- two-account incoming, outgoing, accepted, rejected, muted, speaker, and ended audio calls.

Known deployment limitations: the repository contains no production Render URL or cloud credentials; backend authorization sessions are reopened rather than persisted; direct-message and room histories remain memory-only on the embedded server; SQLite space persistence is local to one Android host rather than a cross-node consensus database; and internet WebRTC needs compatible cloud signaling plus deployed STUN/TURN infrastructure.

---

## Development principles

- Preserve existing working Android features when extending the project.
- Keep board-specific code inside controller firmware and hardware adapters.
- Version public APIs and maintain compatibility deliberately.
- Negotiate capabilities instead of assuming every board supports every feature.
- Do not embed reusable secrets in the Android APK or public firmware.
- Keep private media and room authorization enforced by the server, not only hidden in the UI.
- Prefer local operation and minimize unnecessary data collection.
- Document platform limitations rather than promising behavior Android or hardware cannot guarantee.
- Test changes on real Android devices and target boards in addition to unit/build tests.

## Contributing

Contributions should preserve the local-first design and hardware-independent application boundary.

Before adding a feature:

1. Identify whether it belongs in the Android UI, shared service/API layer, or a board-specific hardware adapter.
2. Avoid adding board-model checks to Android feature code.
3. Update the relevant API or protocol documentation when a public contract changes.
4. Update controller capability declarations when hardware support changes.
5. Add tests for authorization, capability negotiation, and backward compatibility where applicable.
6. Record device-specific Android limitations and required permissions.

For hardware work, begin with [the Universal Gateway API v1 contract](docs/gateway-api-v1.md) and the README inside the relevant `controllers/` directory.

---

**BitVoid — local connection, shared experiences, and control over your own network.**
