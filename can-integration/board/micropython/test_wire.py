"""Two implementations, one contract: the firmware (serve.py) and the virtual board must answer
identical requests identically, byte-for-byte in status and JSON. Run in this directory.

The negative control is a virtual board started with --lie: the same replay must then differ,
or this comparison would be incapable of failing.
"""
import http.client
import json
import os
import socket
import subprocess
import sys
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
VIRTUAL = os.path.join(HERE, "..", "..", "tools", "virtual-board.py")
SPECS = ["bar:switch", "spot:level"]

# One drive through the contract: happy paths, refusals, and a read-back at the end.
SCRIPT = [
    ("GET", "/state/bar", None),
    ("POST", "/set/bar", {"power": "on"}),
    ("POST", "/set/spot", {"level": 42}),
    ("POST", "/set/spot", {"level": 0}),
    ("POST", "/set/bar", {"power": "ON"}),
    ("POST", "/set/bar", {"level": 5}),
    ("POST", "/set/spot", {"level": 101}),
    ("POST", "/set/nope", {"power": "on"}),
    ("GET", "/state/nope", None),
    ("POST", "/set/spot", {"power": "on", "uptime_s": 7}),
    ("GET", "/state/spot", None),
    ("GET", "/state/bar", None),
]


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start(argv):
    port = free_port()
    proc = subprocess.Popen(
        [sys.executable] + argv + ["--port", str(port)] + SPECS,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, cwd=HERE,
    )
    deadline = time.time() + 10
    while time.time() < deadline:
        try:
            socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
            return proc, port
        except OSError:
            time.sleep(0.05)
    proc.kill()
    raise RuntimeError("server did not come up: %s" % argv)


def replay(port):
    out = []
    for method, path, body in SCRIPT:
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"} if data else {}
        conn.request(method, path, body=data, headers=headers)
        resp = conn.getresponse()
        raw = resp.read()
        out.append((resp.status, json.loads(raw) if raw else None))
        conn.close()
    return out


class WireTest(unittest.TestCase):

    def run_pair(self, virtual_args):
        fw, fw_port = start([os.path.join(HERE, "serve.py")])
        vb, vb_port = start([VIRTUAL] + virtual_args)
        try:
            return replay(fw_port), replay(vb_port)
        finally:
            fw.kill()
            vb.kill()

    def test_firmware_and_virtual_board_answer_alike(self):
        fw, vb = self.run_pair([])
        self.assertEqual(vb, fw)
        # And not vacuously: the drive really switched things and really got refused.
        self.assertEqual((200, {"power": "on"}), fw[1])
        self.assertEqual([422, 422, 422, 404, 404], [s for s, _ in fw[4:9]])
        self.assertEqual((200, {"power": "on", "level": 0}), fw[-2])

    def test_a_lying_board_is_told_apart(self):
        fw, lie = self.run_pair(["--lie"])
        self.assertNotEqual(lie, fw)
        self.assertEqual((200, {"power": "off"}), lie[1])


if __name__ == "__main__":
    unittest.main()
