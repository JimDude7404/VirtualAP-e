#!/usr/bin/env bash
#
# Build fully-static aarch64 binaries (hostapd, iw, dnsmasq) for VirtualAP.
#
# These replace the Alpine chroot's dynamically-linked tools: a static musl
# binary carries its own libc/libnl, so it runs directly on Android (any phone,
# any bionic) with no chroot. Build runs in an aarch64 Alpine container under
# qemu emulation - reproducible, no host toolchain needed beyond Docker.
#
# Usage:  ./scripts/build-static.sh
# Output: ./scripts/out/{hostapd,hostapd_cli,iw,dnsmasq}
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE="alpine:3.23"

# Register arm64 qemu binfmt handler if emulation isn't available yet.
if ! docker run --rm --platform linux/arm64 "$IMAGE" true 2>/dev/null; then
    echo "[*] Registering arm64 qemu binfmt handler..."
    docker run --rm --privileged tonistiigi/binfmt:latest --install arm64 >/dev/null
fi

echo "[*] Building static aarch64 binaries in $IMAGE ..."
# :z relabels the bind mount for SELinux (Fedora/RHEL hosts).
docker run --rm --platform linux/arm64 \
    -v "$HERE":/work:z \
    "$IMAGE" sh /work/build-in-container.sh

echo
echo "[*] Done:"
ls -lh "$HERE/out"
