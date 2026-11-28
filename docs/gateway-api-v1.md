# AirMesh Universal Gateway API v1

This document is the hardware boundary for every AirMesh gateway. Android talks only to this
versioned HTTP/WebSocket contract. ESP-12E, ESP32, Raspberry Pi, and future boards may use
different internal implementations, but they must expose the same routes and JSON semantics.

## Compatibility rules

- Base URL is the discovered gateway origin; default port is 80 for a dedicated AP.
- JSON is UTF-8 and every response includes `Content-Type: application/json`.
- `GET /api/v1/gateway/info` is mandatory and is the first capability negotiation request.
- `protocol.name` is `bitvoid-gateway`; `protocol.major` is `1`. A client must reject an
  unsupported major version and may ignore unknown fields/minor capabilities.
- Capabilities describe what the board can actually provide. Android must not infer capabilities
  from a board/model name.
- Hardware replacement must preserve gateway identity and data only when the operator explicitly
  migrates the identity/storage. A new blank board is a new gateway and requires pairing again.
- No shared universal secret may be compiled into the APK or firmware.

## Required endpoints

### GET /api/v1/gateway/info

```json
{
  "service": "bitvoid",
  "gatewayId": "bv-unique-device-id",
  "displayName": "AirMesh Gateway",
  "hardware": { "family": "esp8266", "model": "esp-12e" },
  "protocol": { "name": "bitvoid-gateway", "major": 1, "minor": 0 },
  "identity": { "mode": "local-identity-v1", "verified": true },
  "capabilities": ["wifi-ap", "discovery", "health"],
  "limits": { "maxClients": 4 },
  "links": { "health": "/api/v1/health", "services": "/api/v1/services" }
}
```

### GET /api/v1/health

Returns `{ "ok": true, "status": "ready", "uptimeSeconds": 12 }`.

### GET /api/v1/services

Returns `{ "services": [{ "id": "discovery", "available": true }] }`.

### GET /api/v1/devices

Returns `{ "devices": [] }`. Empty is valid.

### GET /api/node (legacy mesh identity)

Must return HTTP 200 and headers:
- `X-BitVoid-Node: 1`
- `X-BitVoid-Protocol: 2`
- `X-BitVoid-Identity: local-identity-v1`

The JSON body must include `service: "bitvoid"`, `protocolVersion: 2`, `nodeId`,
`nodeType`, `coordinator`, and `portalAvailable`.

## Authentication and pairing contract

A gateway must expose `POST /api/v1/pairing/request` and
`POST /api/v1/pairing/approve` before it is considered production-ready.

1. The user explicitly opens a short physical pairing window on the gateway (button, admin
   console, or one-time QR code). The gateway issues a random, single-use pairing challenge.
2. The app submits a generated device ID and public key. The gateway approves only while the
   physical pairing window is open.
3. The gateway returns a device-scoped bearer token once. Android stores it using Android Keystore
   backed encrypted storage; gateway stores only a verifier/hash.
4. Authenticated routes require `Authorization: Bearer <device-token>`. Tokens are revocable.
5. Wi-Fi credentials and application authentication are separate security layers.

A board that has not implemented pairing must advertise `pairing` as unavailable and must be
treated as a development/discovery gateway, not a trusted production server.

## Events and feature services

Optional WebSocket endpoint: `/api/v1/events`. Messages are JSON envelopes with
`type`, `eventId`, `timestamp`, and `data`. Feature routes are capability-gated. Do not
advertise media, storage, messaging, rooms, or WebRTC unless the board implements the corresponding
AirMesh service end-to-end.

## Hardware portability guarantee

The Android app must depend on this API and negotiated capabilities, never on ESP8266/ESP32/Pi
board identifiers. Replacing a board with another conforming implementation must not require an
APK change. A firmware change is required on the replacement board. The guarantee does not mean
all boards have equal CPU, RAM, storage, client-count, or media-streaming capacity.
