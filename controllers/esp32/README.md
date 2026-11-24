# BitVoid ESP32 same-LAN coordinator

> **Compatibility status:** this existing firmware currently implements the legacy mesh identity
> protocol, not the complete Universal Gateway API v1. It is not yet a drop-in replacement for the
> ESP-12E gateway. Implement every required route in `../../docs/gateway-api-v1.md` before marking
> this target API-v1 compatible. The Android app must not branch on this board's model.

This PlatformIO firmware turns an ESP32 into a lightweight BitVoid registry on an **existing
protected Wi-Fi network**. It scans visible Wi-Fi in the background and lets apps with different
private IP addresses/URLs find one another through the common mesh registry.

It does not create an open AP, perform internet NAT, or contain a hidden universal key. A protected
Wi-Fi password must still be provisioned on the controller because no ESP/Pi can join an unknown
secured network without credentials.

## Build and flash

1. Install PlatformIO.
2. Copy `include/bitvoid_config.example.h` to `include/bitvoid_config.h`.
3. Set the existing Wi-Fi SSID/password.
4. Run:

```bash
pio run -t upload
pio device monitor
```

The coordinator serves TCP port `8080`, advertises `_bitvoid._tcp` and `_http._tcp`, and implements:

- `GET /api/node`
- `GET /api/mesh/nodes`
- `POST /api/mesh/register`
- `GET /api/wifi/scan`
- `GET /health`

Open **Node Log** in BitVoid on each Android device. Discovery and coordinator heartbeat are
automatic; users do not enter controller IP addresses or URLs.

> The access point must allow peer-to-peer LAN traffic (disable wireless/client isolation).
