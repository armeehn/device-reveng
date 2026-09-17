# launcher/tools

## `headunit`

Drives the CarLauncher on the emulated head unit farm: boot an AVD, build and install
a worktree, screenshots and UI dumps, the carsim vehicle, the ARTEMIS automation,
the e2e suite. `headunit help` lists it. It is Proxmox-shaped (`pct exec` into the
farm container and the build container, over ssh when the farm is on another host).

Site values never live in this file: put them in `/etc/headunit.env` (or point
`HEADUNIT_ENV` at one). The header of the script lists every variable. Without the
file the farm is assumed to be on this host and the consoles unpublished.

Install on the operator host:

```
install -m755 launcher/tools/headunit /usr/local/bin/headunit
```

Edit here, then reinstall; the installed copy is not read back.

## `mcp_shim.py`

The stdio front `headunit artemis mcp` runs. Claude Code opens every configured MCP
server for every session, spares included, so without it each session cost an
ssh → `pct exec` → ARTEMIS import on the farm. The shim answers `initialize`,
`tools/list` and `ping` from `/var/cache/headunit/artemis-mcp.json` and starts the
real bridge (`headunit artemis mcp-real`) on the first `tools/call`. No cache yet:
it proxies transparently and writes one.

```
install -d /usr/local/lib/headunit /var/cache/headunit
install -m644 launcher/tools/mcp_shim.py /usr/local/lib/headunit/mcp_shim.py
```
