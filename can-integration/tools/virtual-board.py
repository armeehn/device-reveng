#!/usr/bin/env python3
"""A virtual accessory board, for driving the launcher before the real one exists.

Speaks exactly `can-integration/docs/ACCESSORY_BOARD.md`, from the standard library alone, so it
runs on x (or any laptop on the car's network) with no install:

    python3 virtual-board.py --port 8765 bar:switch spot:level antenna:level
    python3 virtual-board.py --port 8765 --lie bar:switch     # answers 200 and does nothing

Point the launcher's Accessories page at http://<host>:8765 and every command it sends prints
here. `--lie` exists to prove the launcher refuses a board that confirms nothing: with it, the
switch on the screen must NOT move.

This file is also the plain-language reference for the firmware. If the ESP32 and this script
disagree about the contract, the launcher's transport tests are the authority for both.
"""
import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KINDS = {"switch", "level"}
POWER_WORDS = {"on", "off"}


def parse_accessories(specs):
    """'bar:switch' → {'bar': {'kind': 'switch', 'power': 'off'}}; a level also carries level 0."""
    out = {}
    for spec in specs:
        if ":" not in spec:
            sys.exit(f"bad accessory '{spec}': want id:switch or id:level")
        ident, kind = spec.split(":", 1)
        if kind not in KINDS:
            sys.exit(f"bad kind '{kind}' for '{ident}': want switch or level")
        state = {"kind": kind, "power": "off"}
        if kind == "level":
            state["level"] = 0
        out[ident] = state
    return out


def public(state):
    """What the wire carries: power, and level for a level accessory. Never the kind."""
    body = {"power": state["power"]}
    if "level" in state:
        body["level"] = state["level"]
    return body


class Board(BaseHTTPRequestHandler):
    accessories = {}
    lie = False

    def _send(self, code, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(code)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if data:
            self.wfile.write(data)

    def _target(self, prefix):
        if not self.path.startswith(prefix):
            return None
        ident = self.path[len(prefix):]
        return ident if ident in self.accessories else None

    def do_GET(self):
        ident = self._target("/state/")
        if ident is None:
            return self._send(404)
        self._send(200, public(self.accessories[ident]))

    def do_POST(self):
        ident = self._target("/set/")
        if ident is None:
            return self._send(404)

        length = int(self.headers.get("Content-Length", "0"))
        try:
            req = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return self._send(400)

        state = self.accessories[ident]
        print(f"{ident}: {req}" + ("  (ignored: --lie)" if self.lie else ""), flush=True)

        if not self.lie:
            if "power" in req:
                if req["power"] not in POWER_WORDS:
                    return self._send(422)
                state["power"] = req["power"]
            if "level" in req:
                if "level" not in state or not isinstance(req["level"], int) or not 0 <= req["level"] <= 100:
                    return self._send(422)
                state["level"] = req["level"]
                state["power"] = "on" if req["level"] > 0 else "off"

        # Apply, then report the state as it now IS. A lying board reports the old state — and the
        # launcher is expected to notice.
        self._send(200, public(state))

    def log_message(self, fmt, *args):
        pass


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--lie", action="store_true", help="answer 200 to every command and change nothing")
    ap.add_argument("accessories", nargs="+", help="id:switch or id:level")
    args = ap.parse_args()

    Board.accessories = parse_accessories(args.accessories)
    Board.lie = args.lie
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Board)
    print(f"virtual board on :{args.port}  {', '.join(Board.accessories)}" + ("  LYING" if args.lie else ""), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
