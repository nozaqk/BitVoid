#!/usr/bin/env python3
"""BitVoid same-LAN mesh coordinator for Raspberry Pi and Linux.

The coordinator does not route general internet traffic and does not contain a hidden universal
secret. It discovers and registers nodes that answer the public BitVoid local-identity protocol,
then shares one registry with every BitVoid app on the current Wi-Fi/LAN.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import ipaddress
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.error import URLError
from urllib.request import ProxyHandler, Request, build_opener

SERVICE = "bitvoid"
PROTOCOL_VERSION = 2
IDENTITY_MODE = "local-identity-v1"
NODE_TYPE = "pi-coordinator"
DEFAULT_PORT = 8080
REGISTRATION_TTL_SECONDS = 90
MAX_BODY_BYTES = 16_384


def local_ipv4() -> str:
    """Return the preferred LAN IPv4 without sending application traffic to the internet."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("192.0.2.1", 9))  # TEST-NET-1; connect only selects a route.
        candidate = probe.getsockname()[0]
        if ipaddress.ip_address(candidate).is_private:
            return candidate
    except OSError:
        pass
    finally:
        probe.close()
    for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
        candidate = info[4][0]
        if ipaddress.ip_address(candidate).is_private and not candidate.startswith("127."):
            return candidate
    return "127.0.0.1"


def load_or_create_node_id(path: Path) -> str:
    try:
        if path.exists():
            value = path.read_text(encoding="utf-8").strip()
            if value:
                return value
        path.parent.mkdir(parents=True, exist_ok=True)
        value = f"pi-{uuid.uuid4()}"
        path.write_text(value + "\n", encoding="utf-8")
        return value
    except OSError:
        fallback = Path.home() / ".local/share/bitvoid/node-id"
        if fallback != path:
            return load_or_create_node_id(fallback)
        return f"pi-{uuid.uuid4()}"


def is_local_url(raw: str) -> bool:
    try:
        from urllib.parse import urlparse

        parsed = urlparse(raw)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            return False
        if parsed.hostname.endswith(".local"):
            return True
        address = ipaddress.ip_address(parsed.hostname)
        return address.is_private or address.is_loopback
    except (ValueError, TypeError):
        return False


class NodeRegistry:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._nodes: dict[str, dict[str, Any]] = {}

    def update(self, node: dict[str, Any], source_ip: str | None = None) -> bool:
        if node.get("service") != SERVICE:
            return False
        try:
            version = int(node.get("protocolVersion", 0))
        except (TypeError, ValueError):
            return False
        if version < 1 or version > PROTOCOL_VERSION:
            return False
        node_id = str(node.get("nodeId", "")).strip()
        if not node_id or len(node_id) > 128:
            return False
        direct_url = node.get("directPortalUrl")
        if direct_url is not None and (not isinstance(direct_url, str) or not is_local_url(direct_url)):
            direct_url = None
        clean = {
            "service": SERVICE,
            "nodeId": node_id,
            "nodeType": str(node.get("nodeType", "unknown"))[:64],
            "protocolVersion": version,
            "identityMode": IDENTITY_MODE,
            "coordinator": bool(node.get("coordinator", False)),
            "portalAvailable": bool(node.get("portalAvailable", False)),
            "gatewayPriority": int(node.get("gatewayPriority", 100)),
            "directPortalUrl": direct_url,
            "portalUrl": node.get("portalUrl") if isinstance(node.get("portalUrl"), str) else None,
            "address": source_ip or node.get("address"),
            "lastSeenEpochMs": int(time.time() * 1000),
            "_lastSeenMonotonic": time.monotonic(),
        }
        with self._lock:
            self._nodes[node_id] = clean
        return True

    def snapshot(self) -> list[dict[str, Any]]:
        cutoff = time.monotonic() - REGISTRATION_TTL_SECONDS
        with self._lock:
            expired = [key for key, value in self._nodes.items() if value["_lastSeenMonotonic"] < cutoff]
            for key in expired:
                self._nodes.pop(key, None)
            result = []
            for value in self._nodes.values():
                public = {key: item for key, item in value.items() if not key.startswith("_")}
                result.append(public)
            return sorted(result, key=lambda item: (not item["coordinator"], item["nodeId"]))


class WifiScanner:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._networks: list[dict[str, Any]] = []

    def scan(self) -> None:
        networks: list[dict[str, Any]] = []
        try:
            output = subprocess.run(
                ["nmcli", "-t", "-f", "SSID,SIGNAL,SECURITY", "device", "wifi", "list", "--rescan", "yes"],
                capture_output=True,
                text=True,
                timeout=20,
                check=False,
            ).stdout
            for line in output.splitlines():
                parts = line.rsplit(":", 2)
                if len(parts) == 3 and parts[0]:
                    networks.append({"ssid": parts[0].replace("\\:", ":"), "signal": parts[1], "security": parts[2]})
        except (OSError, subprocess.TimeoutExpired):
            pass
        with self._lock:
            self._networks = networks[:100]

    def snapshot(self) -> list[dict[str, Any]]:
        with self._lock:
            return list(self._networks)


class Coordinator:
    def __init__(self, node_id: str, port: int, scan_interval: float) -> None:
        self.node_id = node_id
        self.port = port
        self.scan_interval = max(10.0, scan_interval)
        self.registry = NodeRegistry()
        self.wifi = WifiScanner()
        self.stop_event = threading.Event()
        self.opener = build_opener(ProxyHandler({}))

    def identity(self) -> dict[str, Any]:
        address = local_ipv4()
        return {
            "service": SERVICE,
            "nodeId": self.node_id,
            "nodeType": NODE_TYPE,
            "protocolVersion": PROTOCOL_VERSION,
            "identityMode": IDENTITY_MODE,
            "coordinator": True,
            "portalAvailable": False,
            "gatewayPriority": 10,
            "hostname": "bitvoid-gateway.local",
            "directPortalUrl": f"http://{address}:{self.port}" if address != "127.0.0.1" else None,
            "port": self.port,
            "features": ["mesh-v2", "registry", "background-wifi-scan"],
        }

    def start_background_scan(self) -> None:
        threading.Thread(target=self._scan_loop, name="bitvoid-mesh-scan", daemon=True).start()

    def _scan_loop(self) -> None:
        while not self.stop_event.is_set():
            self.wifi.scan()
            self._discover_lan_nodes()
            self.stop_event.wait(self.scan_interval)

    def _discover_lan_nodes(self) -> None:
        address = local_ipv4()
        if address == "127.0.0.1":
            return
        network = ipaddress.ip_network(f"{address}/24", strict=False)
        hosts = [str(host) for host in network.hosts() if str(host) != address]
        with concurrent.futures.ThreadPoolExecutor(max_workers=32) as executor:
            for node in executor.map(self._probe_host, hosts):
                if node:
                    self.registry.update(node)

    def _probe_host(self, host: str) -> dict[str, Any] | None:
        url = f"http://{host}:8080/api/node"
        request = Request(url, headers={"X-BitVoid-Discovery": "1", "User-Agent": "BitVoid-Pi/2.0"})
        try:
            with self.opener.open(request, timeout=0.45) as response:
                if response.status != 200 or response.headers.get("X-BitVoid-Node") != "1":
                    return None
                if int(response.headers.get("X-BitVoid-Protocol", "1")) >= 2 and response.headers.get(
                    "X-BitVoid-Identity"
                ) != IDENTITY_MODE:
                    return None
                payload = json.loads(response.read(MAX_BODY_BYTES).decode("utf-8"))
                return payload if payload.get("service") == SERVICE else None
        except (OSError, URLError, ValueError, json.JSONDecodeError):
            return None


class BitVoidHandler(BaseHTTPRequestHandler):
    server_version = "BitVoidPi/2"

    @property
    def coordinator(self) -> Coordinator:
        return self.server.coordinator  # type: ignore[attr-defined]

    def _headers(self, status: int = 200, content_type: str = "application/json") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-BitVoid-Node", "1")
        self.send_header("X-BitVoid-Protocol", str(PROTOCOL_VERSION))
        self.send_header("X-BitVoid-Identity", IDENTITY_MODE)

    def _json(self, payload: Any, status: int = 200) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self._headers(status)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self) -> None:  # noqa: N802
        self.do_GET()

    def do_GET(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path == "/api/node":
            self._json(self.coordinator.identity())
        elif path == "/api/mesh/nodes":
            self._json(
                {
                    "service": SERVICE,
                    "protocolVersion": PROTOCOL_VERSION,
                    "identityMode": IDENTITY_MODE,
                    "nodes": [self.coordinator.identity(), *self.coordinator.registry.snapshot()],
                }
            )
        elif path == "/api/wifi/scan":
            self._json({"service": SERVICE, "networks": self.coordinator.wifi.snapshot()})
        elif path == "/health":
            self._json({"status": "ok", "nodeId": self.coordinator.node_id})
        elif path == "/":
            body = (
                "<!doctype html><meta name=viewport content='width=device-width'>"
                "<title>BitVoid Pi Coordinator</title><style>body{background:#0d1117;color:#00ff9c;"
                "font:16px monospace;padding:2rem}</style><h1>BitVoid Pi Coordinator</h1>"
                "<p>Same-LAN mesh registry online. Open Node Log in the BitVoid app.</p>"
            ).encode("utf-8")
            self._headers(200, "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
        else:
            self._json({"error": "not_found"}, 404)

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path != "/api/mesh/register":
            self._json({"error": "not_found"}, 404)
            return
        if self.headers.get("X-BitVoid-Identity") != IDENTITY_MODE:
            self._json({"error": "identity_required"}, 403)
            return
        try:
            length = min(int(self.headers.get("Content-Length", "0")), MAX_BODY_BYTES)
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, json.JSONDecodeError, UnicodeDecodeError):
            self._json({"error": "invalid_json"}, 400)
            return
        if self.coordinator.registry.update(payload, self.client_address[0]):
            self._json({"accepted": True, "nodeId": payload.get("nodeId")})
        else:
            self._json({"error": "invalid_identity"}, 400)

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[bitvoid] {self.client_address[0]} {fmt % args}", flush=True)


class BitVoidServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], coordinator: Coordinator) -> None:
        super().__init__(address, BitVoidHandler)
        self.coordinator = coordinator


def main() -> None:
    parser = argparse.ArgumentParser(description="BitVoid Raspberry Pi same-LAN mesh coordinator")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--scan-interval", type=float, default=30.0)
    parser.add_argument("--node-id-file", type=Path, default=Path("/var/lib/bitvoid/node-id"))
    args = parser.parse_args()

    coordinator = Coordinator(load_or_create_node_id(args.node_id_file), args.port, args.scan_interval)
    coordinator.start_background_scan()
    server = BitVoidServer((args.host, args.port), coordinator)
    print(f"BitVoid Pi coordinator {coordinator.node_id} listening on {args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        coordinator.stop_event.set()
        server.server_close()


if __name__ == "__main__":
    main()
