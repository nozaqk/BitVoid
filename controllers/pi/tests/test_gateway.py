import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bitvoid_gateway import (  # noqa: E402
    BitVoidServer,
    Coordinator,
    IDENTITY_MODE,
    NodeRegistry,
    load_or_create_node_id,
)


class RegistryTests(unittest.TestCase):
    def test_node_id_is_stable(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "node-id"
            first = load_or_create_node_id(path)
            self.assertEqual(first, load_or_create_node_id(path))
            self.assertTrue(first.startswith("pi-"))

    def test_registry_accepts_local_identity_and_rejects_other_services(self):
        registry = NodeRegistry()
        self.assertFalse(registry.update({"service": "other", "nodeId": "x", "protocolVersion": 2}))
        self.assertTrue(
            registry.update(
                {
                    "service": "bitvoid",
                    "nodeId": "android-test",
                    "nodeType": "android-client",
                    "protocolVersion": 2,
                    "portalAvailable": False,
                },
                "192.168.1.20",
            )
        )
        self.assertEqual("android-test", registry.snapshot()[0]["nodeId"])


class HttpProtocolTests(unittest.TestCase):
    def setUp(self):
        self.coordinator = Coordinator("pi-test", 8080, 30)
        self.server = BitVoidServer(("127.0.0.1", 0), self.coordinator)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_identity_headers_and_registry_registration(self):
        with urlopen(f"{self.base}/api/node", timeout=2) as response:
            identity = json.loads(response.read())
            self.assertEqual("1", response.headers["X-BitVoid-Node"])
            self.assertEqual(IDENTITY_MODE, response.headers["X-BitVoid-Identity"])
            self.assertEqual("pi-coordinator", identity["nodeType"])
            self.assertFalse(identity["portalAvailable"])

        payload = json.dumps(
            {
                "service": "bitvoid",
                "nodeId": "android-1",
                "nodeType": "android-client",
                "protocolVersion": 2,
                "identityMode": IDENTITY_MODE,
                "portalAvailable": False,
            }
        ).encode()
        request = Request(
            f"{self.base}/api/mesh/register",
            data=payload,
            method="POST",
            headers={"Content-Type": "application/json", "X-BitVoid-Identity": IDENTITY_MODE},
        )
        with urlopen(request, timeout=2) as response:
            self.assertTrue(json.loads(response.read())["accepted"])

        with urlopen(f"{self.base}/api/mesh/nodes", timeout=2) as response:
            node_ids = {node["nodeId"] for node in json.loads(response.read())["nodes"]}
            self.assertEqual({"pi-test", "android-1"}, node_ids)

    def test_registration_requires_identity_header(self):
        request = Request(
            f"{self.base}/api/mesh/register",
            data=b"{}",
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(HTTPError) as failure:
            urlopen(request, timeout=2)
        self.assertEqual(403, failure.exception.code)


if __name__ == "__main__":
    unittest.main()
