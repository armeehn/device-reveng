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
