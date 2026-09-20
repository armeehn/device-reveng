#!/usr/bin/env python3
"""Lazy stdio front for the ARTEMIS MCP bridge (`headunit artemis mcp`).

Claude Code connects every configured MCP server for every session, including the
daemon's pre-warmed spares that churn every few seconds. Each connect used to be an
ssh → pct exec → python import of ARTEMIS on CT 124 (190 MB, seconds of CPU) plus a
secret resolve and an .env rewrite. On 2026-09-17 that left 16 bridges on the farm.

    client ──stdio──► shim ──(first tools/call)──► headunit artemis mcp-real ──► 124

The shim answers the handshake and `tools/list` from a cache written by the last real
session, and starts the real bridge only when a tool is called. With no cache yet it
is a transparent proxy that fills the cache.
"""
import json
import os
import subprocess
import sys
import threading

CACHE = "/var/cache/headunit/artemis-mcp.json"
REAL = ["/usr/local/bin/headunit", "artemis", "mcp-real"]
CACHED_METHODS = ("initialize", "tools/list")
# ARTEMIS registers no resources or prompts, so these are constant. Claude
# Code asks for all three right after initialize; answering them here is
# what keeps a session that never calls a tool from owning a bridge in 124.
EMPTY_LISTS = {"resources/list": {"resources": []},
               "prompts/list": {"prompts": []},
               "resources/templates/list": {"resourceTemplates": []}}
SHIM_INIT_ID = "headunit-shim-init"


def load_cache():
    try:
        with open(CACHE) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_cache(cache):
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    tmp = CACHE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(cache, f)
    os.replace(tmp, CACHE)


def send(fp, msg):
    fp.write(json.dumps(msg) + "\n")
    fp.flush()


class Shim:
    def __init__(self):
        self.cache = load_cache()
        self.init_params = {}
        self.client_init_id = None     # set on a cold start: the client still awaits its answer
        self.real = None
        self.ready = threading.Event()  # the real bridge has finished its handshake
        self.queue = []                 # client messages held until then
        self.pending_lists = set()      # tools/list ids in flight, to refresh the cache
        self.out_lock = threading.Lock()

    def reply(self, msg):
        with self.out_lock:
            send(sys.stdout, msg)

    # -- the real bridge -------------------------------------------------------------

    def start_real(self):
        """Spawn the bridge, replay the client's handshake, then pump its stdout to ours."""
        self.real = subprocess.Popen(REAL, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        send(self.real.stdin, {"jsonrpc": "2.0", "id": SHIM_INIT_ID, "method": "initialize",
                               "params": self.init_params})
        threading.Thread(target=self.pump, daemon=True).start()

    def on_handshake(self, msg):
        if "result" in msg:
            self.cache["initialize"] = msg["result"]
            save_cache(self.cache)
        if self.client_init_id is not None:
            self.reply({"jsonrpc": "2.0", "id": self.client_init_id, **{k: msg[k] for k in ("result", "error") if k in msg}})
            self.client_init_id = None
        send(self.real.stdin, {"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.ready.set()
        for held in self.queue:
            send(self.real.stdin, held)
        self.queue.clear()

    def pump(self):
        for line in self.real.stdout:
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if msg.get("id") == SHIM_INIT_ID:
                self.on_handshake(msg)
                continue
            if msg.get("id") in self.pending_lists:
                self.pending_lists.discard(msg["id"])
                if "result" in msg:
                    self.cache["tools/list"] = msg["result"]
                    save_cache(self.cache)
            self.reply(msg)
        os._exit(0)

    # -- the client side ---------------------------------------------------------------

    def handle(self, msg):
        method = msg.get("method")
        has_id = "id" in msg

        if method == "initialize":
            self.init_params = msg.get("params", {})
        if method == "notifications/initialized":
            return   # the shim sends its own once the real bridge answers

        if self.real is None:
            if method == "ping" and has_id:
                self.reply({"jsonrpc": "2.0", "id": msg["id"], "result": {}})
                return
            if method in CACHED_METHODS and method in self.cache and has_id:
                self.reply({"jsonrpc": "2.0", "id": msg["id"], "result": self.cache[method]})
                return
            if method in EMPTY_LISTS and has_id:
                self.reply({"jsonrpc": "2.0", "id": msg["id"], "result": EMPTY_LISTS[method]})
                return
            if not has_id:
                return   # a notification with no bridge to notify
            self.start_real()
            if method == "initialize":
                self.client_init_id = msg["id"]
                return

        if method == "tools/list" and has_id:
            self.pending_lists.add(msg["id"])
        if self.ready.is_set():
            send(self.real.stdin, msg)
        else:
            self.queue.append(msg)

    def run(self):
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            self.handle(msg)
        if self.real is not None:
            self.real.stdin.close()
            try:
                self.real.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.real.kill()


if __name__ == "__main__":
    Shim().run()
