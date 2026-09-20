"""The shim must not start the ARTEMIS bridge for list methods it can answer.

Claude Code sends resources/list, prompts/list and resources/templates/list
right after initialize. Each carried an id, so the shim treated them as
"real work" and spawned the bridge: 12 sessions on x, 12 bridges in 124,
none of them ever calling a tool (2026-09-20). ARTEMIS registers no
resources or prompts, so the answers are constant empty lists.

    python3 launcher/tools/test_mcp_shim.py
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SHIM = os.path.join(HERE, "mcp_shim.py")


def run_shim(messages, marker):
    """Run the shim with a fake bridge that touches `marker` when spawned."""
    fake = os.path.join(os.path.dirname(marker), "fake-real.sh")
    with open(fake, "w") as fh:
        fh.write("#!/bin/sh\ntouch %s\ncat >/dev/null\n" % marker)
    os.chmod(fake, 0o755)
    # A warm cache, as on x: initialize and tools/list never reach the bridge.
    cache = os.path.join(os.path.dirname(marker), "cache.json")
    with open(cache, "w") as fh:
        json.dump({"initialize": {"protocolVersion": "2025-06-18",
                                  "capabilities": {"tools": {}},
                                  "serverInfo": {"name": "fake", "version": "0"}},
                   "tools/list": {"tools": []}}, fh)
    code = ("import sys; sys.path.insert(0, %r); import mcp_shim; "
            "mcp_shim.REAL = [%r]; mcp_shim.CACHE = %r; mcp_shim.Shim().run()"
            % (HERE, fake, cache))
    stdin = "".join(json.dumps(m) + "\n" for m in messages)
    out = subprocess.run([sys.executable, "-c", code], input=stdin,
                         capture_output=True, text=True, timeout=20).stdout
    return [json.loads(l) for l in out.splitlines() if l.strip()]


INIT = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                   "clientInfo": {"name": "test", "version": "0"}}}
LISTS = [{"jsonrpc": "2.0", "id": i, "method": m, "params": {}}
         for i, m in enumerate(("resources/list", "prompts/list",
                                "resources/templates/list"), 2)]
CALL = {"jsonrpc": "2.0", "id": 9, "method": "tools/call",
        "params": {"name": "mobile_diagnose", "arguments": {}}}

failures = []


def expect(cond, msg):
    print(("ok   " if cond else "FAIL ") + msg)
    if not cond:
        failures.append(msg)


with tempfile.TemporaryDirectory() as td:
    marker = os.path.join(td, "spawned")
    replies = run_shim([INIT] + LISTS, marker)
    by_id = {r.get("id"): r for r in replies}
    expect(not os.path.exists(marker), "list methods do not start the bridge")
    expect(by_id.get(2, {}).get("result") == {"resources": []},
           "resources/list answered with an empty list")
    expect(by_id.get(3, {}).get("result") == {"prompts": []},
           "prompts/list answered with an empty list")
    expect(by_id.get(4, {}).get("result") == {"resourceTemplates": []},
           "resources/templates/list answered with an empty list")

with tempfile.TemporaryDirectory() as td:
    marker = os.path.join(td, "spawned")
    run_shim([INIT, CALL], marker)
    expect(os.path.exists(marker), "control: tools/call still starts the bridge")

print()
if failures:
    print("%d FAILED" % len(failures))
    sys.exit(1)
print("all passed")
