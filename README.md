# VirtualAP

VirtualAP is a software utility designed to configure a virtual access point on rooted Android devices.

> [!NOTE]
> This application is a proof of concept. The front-end user interface is developed with the assistance of an AI companion. The backend routing engine is derived from the pre-existing [Ubuntu-Chroot](https://github.com/ravindu644/Ubuntu-Chroot) project.

## Navigation

* [Features](#features)
* [Repository Layout](#repository-layout)
* [Build Instructions](#build-instructions)
* [Android Application Lifecycle](#android-application-lifecycle)
* [Routing and Architecture](#routing-and-architecture)
* [License](#license)

## Features

* **Configurable Gateway IP**: Unlike the default Android hotspot, the gateway address remains static. This ensures that port forwards, bookmarks, and SSH configurations remain valid.
* **Selectable Upstream Interface**: Direct traffic through Mobile Data, Wi-Fi, Ethernet, or virtual interfaces like WireGuard tun0 to tunnel all connected clients automatically.
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

```
client ➔ ap0 (Gateway IP, hostapd)
       ➔ ip rule pref 7010: from all iif ap0 lookup <upstream table>
       ➔ MASQUERADE (-s <subnet> ! -d <subnet>)
       ➔ Internet or VPN tunnel
replies ➔ ip rule pref 7000: to <subnet> lookup main ➔ ap0
```

The routing rules are configured with high priority (7000 and 7010) to sit above the Android netd rule range. This prevents VPN configuration overrides from hijacking client traffic and bypasses the native unreachable guard rules.

## License

This project is licensed under the MIT License. Copyright (c) 2026 ravindu644.
