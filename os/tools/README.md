# The debug toolbelt

What a Kali box keeps in reach, carried by Riposte OS itself so the unit can be probed from
its own panel or a plain `adb shell`, without a laptop in the loop.

```
tools.lock ──fetch.sh──▶ share/carlauncher/os/tools/unit ──build.sh --tools──▶ the image
                                                          └─install-data.sh──▶ /data/local/riposte/bin
```

| On the unit | What |
|---|---|
| `/system/bin/{nmap,ncat,nping,tcpdump,socat,strace,gdb,gdbserver,busybox}` | on PATH for every shell; links into `/system/riposte/bin` |
| `/system/riposte/bin/bb/*` | one link per busybox applet (358); `export PATH=$PATH:/system/riposte/bin/bb` or `busybox <applet>` |
| `/system/riposte/nmap/` | the portable nmap tree; `/system/bin/nmap` is a wrapper that sets `NMAPDIR` |
| Termux (`com.termux`, product app) | the terminal on the panel; `pkg install python openssh git` and so on over the unit's Wi-Fi. ssh/sshd come from here: a static musl OpenSSH dies on Android's missing `/etc/passwd` |
| `/data/local/riposte/bin/frida-server` | data side, 100 MB: pushed by `install-data.sh`, survives a no-wipe flash |

`adb shell` is already root on the GSI (`u:r:su:s0`), so `tcpdump -i wlan0 -w /data/local/tmp/x.pcap`,
`strace -p <pid>` and `frida-server &` need no `su`. Inside Termux use `tsu` (phh-su answers it).

## Sources

Static aarch64 builds, pinned by sha256 in `tools.lock`: ernw/static-toolbox (nmap, tcpdump,
socat, strace, gdb), Termux's own arm64 release APK, Frida's android-arm64 server, and
the busybox Magisk ships in its APK (`lib/arm64-v8a/libbusybox.so`), the only static arm64
busybox published for bionic. busybox.net offers 32-bit ARM only.

## Adding a tool

1. Add a line to `tools.lock` (name, kind, sha256, url). Kinds: `bin`, `tar`, `apk`, `data`.
2. `tools/fetch.sh` on x (root), then `bench-cycle.sh <vc>`.
3. A new busybox: regenerate `busybox.applets` with `busybox --list` on the unit.

Size budget: the four GSI images sit 200 MB under the 6 GiB super. Anything past a few MB
goes in as `data`, not into `/system`.
