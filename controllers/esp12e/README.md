# BitVoid ESP-12E gateway (ESP8266)

This is the first hardware target for the **Universal Gateway API v1**. It creates a WPA2
access point and exposes the common discovery/health/capability endpoints at
`http://192.168.4.1`.

## Setup

1. Install PlatformIO.
2. Copy `src/bitvoid_config.example.h` to `src/bitvoid_config.h`.
3. Change the SSID, unique gateway ID, and WPA2 password. Do not commit the real config file.
4. Build and flash:

```sh
pio run
pio run -t upload
pio device monitor
```

5. Connect a phone running BitVoid to the configured SSID and approve Android's Wi-Fi connection prompt if shown.
6. Verify the headless API from a development machine on the same network:

```sh
curl --fail http://192.168.4.1/api/v1/gateway/info
curl --fail http://192.168.4.1/api/v1/health
```

The firmware does not provide an HTML dashboard, captive portal, or browser interface.

## Implemented capabilities

- WPA2 Wi-Fi access point (max clients configured in the local config header)
- mDNS hostname `bitvoid.local`
- Legacy BitVoid mesh identity at `/api/node` (protocol v2)
- Universal Gateway API v1 discovery, health, service and device listing

## Deliberate limitations

ESP-12E is a small ESP8266 microcontroller. This first firmware is a connectivity and discovery
gateway, **not** a full BitVoid application server. It does not implement user pairing/authentication,
persistent media storage, chat, rooms, WebRTC, or file streaming. Those capabilities are not
advertised. The pairing endpoints return HTTP 501 until a secure physical pairing flow is added.
Use a Raspberry Pi-class gateway for the full BitVoid server workload.

The password in the example config is a placeholder and firmware refuses to boot until changed.
Each deployed device must have a unique gateway ID and Wi-Fi passphrase. The Wi-Fi password is not
an application authentication token.

## Board replacement rule

The Android app must consume only the versioned Gateway API and advertised capabilities. An ESP32
or Pi implementation that implements this same contract can replace this board without an Android
code change. A board swap still requires compatible firmware and, unless identity/storage are
migrated, first-time pairing again.
