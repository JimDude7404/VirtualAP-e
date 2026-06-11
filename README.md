# VirtualAP

Turn your phone's WiFi chip into a **real router**.

VirtualAP creates a virtual access point (`ap0`) directly on the phone's WiFi hardware with:

- **Static gateway IP** (`192.168.42.1`) — unlike Android's built-in hotspot, the gateway
  address never changes. Port forwards, bookmarks, and SSH configs stay valid forever.
- **Selectable upstream** — Mobile Data, WiFi, Ethernet… or `wg0`: start a tunnel in the
  official WireGuard app (kernel backend), pick `wg0` as upstream, and every connected
  client is tunneled automatically.
- **Auto upstream detection** — reads netd's default-network routing rule (the kernel's
  ground truth for "which network has internet").
- **DHCP + DNS** for clients via dnsmasq.
- **5GHz channel-following** — the AP follows the STA's current channel (same-channel
  concurrency), the root cause of why 5GHz "randomly failed" in older implementations.
- **~15MB Alpine rootfs** — carries only `hostapd`, `dnsmasq`, and `iw` (many phones ship
  no `iw` binary); routing and firewall use Android's own `ip`/`iptables`.

## Repo layout

```
VirtualAP/
├── Android/           ← companion app (root check, installer, AP control)
├── backend/           ← shell backend: vap.sh (chroot core) + start-ap (AP engine)
└── rootfs-builder/    ← Dockerfile + script to build the Alpine rootfs tarball
```

## Build

```bash
# 1. Build the Alpine rootfs (needs Docker; cross-builds arm64 via binfmt)
./rootfs-builder/build_rootfs.sh -a aarch64

# 2. Build the Android APK (requires Droidspaces Ubuntu-24.04 container for JDK)
cd Android && ./gradlew assembleDebug
```

The Gradle `prepareAssets` pre-build task automatically copies `backend/vap.sh`,
`backend/start-ap`, `backend/bin/busybox`, and the latest rootfs tarball from `out/`
into the APK assets before each build.

## Android app

On first launch the app checks root access, then installs the backend to
`/data/local/virtualap/` via root shell (extracts the bundled Alpine rootfs, deploys the
shell scripts, sets permissions). After that it works entirely standalone — no module to
flash, no reboot required.

## CLI

The backend can also be driven directly from a root shell:

```bash
/data/local/virtualap/start-ap start -s "My AP" -p "password123"
/data/local/virtualap/start-ap start -s "My AP" -p "password123" -o wg0   # WireGuard
/data/local/virtualap/start-ap start -s "My AP" -p "password123" -b 5     # 5 GHz
/data/local/virtualap/start-ap status
/data/local/virtualap/start-ap stop
```

## How it routes

```
client → ap0 (192.168.42.1, hostapd)
       → ip rule pref 7010: from all iif ap0 lookup <upstream table>
       → MASQUERADE (-s 192.168.42.0/24 ! -d 192.168.42.0/24)
       → internet (or WireGuard tunnel)
replies → ip rule pref 7000: to 192.168.42.0/24 lookup main → ap0
```

Pinned rule priorities sit above netd's entire range (10000+), so VPN catch-all rules can
never hijack AP client traffic — and above Android's `32000: from all unreachable` guard
that silently killed unpinned rules in older implementations.

## License

MIT — Copyright (c) 2026 ravindu644
