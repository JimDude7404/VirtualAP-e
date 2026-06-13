# VirtualAP

VirtualAP is a software utility designed to configure a virtual access point on rooted Android devices.

> [!NOTE]
> This application is a proof of concept. The front-end user interface is developed with the assistance of an AI companion. The backend routing engine is derived from the pre-existing [Ubuntu-Chroot](https://github.com/ravindu644/Ubuntu-Chroot) project.

## Navigation

* [System Requirements](#system-requirements)
* [Features](#features)
* [Repository Layout](#repository-layout)
* [Build Instructions](#build-instructions)
* [Android Application Lifecycle](#android-application-lifecycle)
* [Routing and Architecture](#routing-and-architecture)
* [License](#license)

## System Requirements

* **Root Access**: Root permissions are required to perform network routing operations, control iptables, and manage virtual interfaces.
* **Architecture**: Aarch64 (ARM64-v8a) CPU architecture.
* **Android Version**: Android 8.0 (SDK 26) or higher.

## Features

* **Configurable Gateway IP**: Unlike the default Android hotspot, the gateway address remains static. This ensures that port forwards, bookmarks, and SSH configurations remain valid.
* **Selectable Upstream Interface**: Direct traffic through Mobile Data, Wi-Fi, Ethernet, or virtual interfaces like WireGuard tun0 to tunnel all connected clients automatically.
* **Wi-Fi Repeater Mode**: Connect your phone to any Wi-Fi network and share it as a hotspot simultaneously. The phone acts as a wireless repeater, allowing other devices to access the network without additional hardware.
* **VPN Hotspot**: Set a VPN tunnel interface (such as WireGuard tun0) as the upstream. All devices connected to the hotspot are automatically routed through the VPN, turning your phone into a portable VPN access point.
* **Managed Mode (Container-Routed Hotspot)**: Hand the hotspot's LAN to a running [Droidspaces](https://github.com/ravindu644/Droidspaces) container with `-K`. VirtualAP keeps only the wireless and Layer-2 plumbing while the container owns DHCP, DNS, NAT, and firewalling. A single OpenWrt container can therefore route the Wi-Fi hotspot and its Droidspaces gateway-mode containers at the same time, all from one LuCI control plane.
* **Automatic Upstream Detection**: Reads the default network routing rules from the Android netd system to identify the active internet connection.
* **DHCP and DNS Services**: Powered by dnsmasq inside the chroot environment to serve local clients.
* **Same-Channel Concurrency**: The access point dynamically follows the Wi-Fi station channel. This addresses stability issues with 5GHz connectivity.
* **Minimal Footprint**: Relies on a 4.4MB Alpine rootfs containing only hostapd, dnsmasq, and iw. Firewall and routing tasks leverage the native Android iptables and ip tools.

## Repository Layout

```
VirtualAP/
├── Android/           ← Companion application (Root validation, installer, AP control)
├── backend/           ← Shell backend: vap.sh (Chroot controller) and start-ap (AP engine)
└── rootfs-builder/    ← Dockerfile and build scripts for the Alpine rootfs tarball
```

## Build Instructions

### 1. Build the Alpine rootfs
The rootfs build process requires Docker. It utilizes binfmt to cross-compile for arm64:
```bash
./rootfs-builder/build_rootfs.sh
```

### 2. Build the Android APK
Compile the Android application using Gradle:
```bash
cd Android && ./gradlew assembleRelease
```
The Gradle `prepareAssets` task executes automatically before compilation to copy `backend/vap.sh`, `backend/start-ap`, `backend/bin/busybox`, and the compiled rootfs tarball into the application assets.

## Android Application Lifecycle

Upon first execution, the application validates root privileges and deploys the backend environment to `/data/local/virtualap/`. The installation process extracts the bundled Alpine rootfs, copy the control scripts, and configures file permissions. The application operates independently without requiring Magisk modules or system reboots.

## Routing and Architecture

VirtualAP runs in one of two modes. In **routed mode** (the default), VirtualAP owns all Layer-3 for the hotspot: it assigns the gateway IP to `ap0`, serves DHCP/DNS via its own dnsmasq, and NATs client traffic to the selected upstream using Android's native `ip`/`iptables`. In **managed mode** (`-K <container>`), a Droidspaces container owns Layer-3 instead, and VirtualAP retains only the wireless and Layer-2 plumbing.

### Routed Mode

```
Client Outbound:
client ➔ ap0 (Gateway IP, hostapd)
       ➔ ip rule pref 7010: from all iif ap0 lookup <upstream table>
       ➔ MASQUERADE (-s <subnet> ! -d <subnet>)
       ➔ Internet or VPN tunnel

Client Inbound / Replies:
replies ➔ ip rule pref 7000: to <subnet> lookup main ➔ ap0
```

The routing rules are configured with high priority (7000 and 7010) to sit above the Android netd rule range. This prevents VPN configuration overrides from hijacking client traffic and bypasses the native unreachable guard rules.

### Container Port Forwarding Integration

To support accessing containerized services (such as those running inside Droidspaces) from devices connected to the VirtualAP hotspot, the routing engine mirrors the access point subnet into Android's default local network routing table (table 97):

```
Client ➔ Gateway IP (Port Forwarded Port)
       ➔ DNAT (host port redirected to container IP 172.28.0.0/16)
       ➔ Container responds to Client IP (<subnet>)
       ➔ Android Rule 6095 matches: from 172.28.0.0/16 lookup local_network (table 97)
       ➔ Route lookup matches mirrored subnet route: <subnet> dev ap0
       ➔ Packet successfully routed back to Client via ap0
```

Without mirroring the route to table 97, Android's policy routing for the container subnet would fall through to the physical WAN interface table, causing reply packets to leak to the external WAN and breaking the port-forwarding connection.

### Managed Mode (Droidspaces Container Integration)

Passing `-K <container>` hands the hotspot's LAN to a running Droidspaces container. VirtualAP no longer assigns any IP, runs no dnsmasq, and installs no NAT rules of its own. Instead it builds a neutral Layer-2 path and lets the container be the router:

* `ap0` is enslaved to a host bridge `vap-br0` that carries no IP address.
* A veth pair is created; the host side joins `vap-br0`, and the peer is moved into the container's network namespace and renamed `vaplan0`.
* The container provides DHCP, DNS, NAT, and firewalling for every connected Wi-Fi client.

```
Client Outbound (managed mode):
client ➔ ap0 (L2 bridge port, no IP)
       ➔ vap-br0 ➔ vaplan0 (inside the container)
       ➔ container LAN (DHCP / DNS / firewall, e.g. OpenWrt 192.168.40.1)
       ➔ container NAT ➔ container WAN ➔ Internet
```

When the target is an OpenWrt container, VirtualAP auto-provisions it over UCI: a static `vaplan` interface (`192.168.40.1/24`), a DHCP pool, and a masqueraded firewall zone toward the WAN. Non-OpenWrt containers simply receive `vaplan0` and are expected to configure it themselves.

Because the container owns the LAN, a single OpenWrt instance can route the Wi-Fi hotspot **and** one or more Droidspaces gateway-mode containers concurrently — the hotspot clients arrive on `vaplan0` while the containers arrive on OpenWrt's gateway LAN interface (`eth1`). Both segments are managed from the same OpenWrt instance and its LuCI web interface, turning the phone into a self-contained router for physical Wi-Fi clients and containerized workloads alike.

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
