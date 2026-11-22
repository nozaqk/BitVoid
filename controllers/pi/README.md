# BitVoid Raspberry Pi coordinator

> **Compatibility status:** this existing service currently implements the legacy mesh identity
> protocol, not the complete Universal Gateway API v1. It is not yet a drop-in replacement for the
> ESP-12E gateway. Implement every required route in `../../docs/gateway-api-v1.md` before marking
> this target API-v1 compatible. The Android app must not branch on this board's model.

This service joins the Pi's **existing Wi-Fi/LAN** and coordinates BitVoid apps on the same `/24`
network. It does not provide internet NAT, switch Wi-Fi networks, or store a universal hidden key.
Identity is the public `local-identity-v1` protocol selected for this deployment.

## Run

```bash
python3 bitvoid_gateway.py --host 0.0.0.0 --port 8080
```

Open **Node Log** in each Android app. Apps automatically discover the Pi, register a heartbeat,
exchange known node endpoints, and deterministically choose an available Android app gateway.
Different private IP addresses and portal URLs are normalized by the registry.

## Install as a service

```bash
sudo useradd --system --home /var/lib/bitvoid --shell /usr/sbin/nologin bitvoid
sudo install -d -o bitvoid -g bitvoid /opt/bitvoid /var/lib/bitvoid
sudo install -m 0755 bitvoid_gateway.py /opt/bitvoid/bitvoid_gateway.py
sudo install -m 0644 bitvoid-gateway.service /etc/systemd/system/bitvoid-gateway.service
sudo systemctl daemon-reload
sudo systemctl enable --now bitvoid-gateway
```

`NetworkManager`'s `nmcli` is used for background Wi-Fi visibility. If unavailable, mesh discovery
still operates and `/api/wifi/scan` returns an empty list.

## Endpoints

- `GET /api/node` — coordinator identity.
- `GET /api/mesh/nodes` — live app/controller registry.
- `POST /api/mesh/register` — app heartbeat.
- `GET /api/wifi/scan` — latest background Wi-Fi scan.
- `GET /health` — liveness.

Allow inbound TCP port `8080` from the private LAN. Client isolation must be disabled on the Wi-Fi
access point, otherwise devices on the same SSID cannot contact one another.
