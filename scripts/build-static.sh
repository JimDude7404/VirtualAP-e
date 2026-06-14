#!/usr/bin/env bash
#
# Build fully-static aarch64 binaries (hostapd, iw, dnsmasq) for VirtualAP.
#
# These replace the Alpine chroot's dynamically-linked tools: a static musl
# binary carries its own libc/libnl, so it runs directly on Android (any phone,
# any bionic) with no chroot. Build runs in an aarch64 Alpine container under
# qemu emulation - reproducible, no host toolchain needed beyond Docker.
#
# Sources are vendored as git submodules under externals/ (our own forks of
# hostap/iw/dnsmasq, so the build survives upstream going away).
#
# Usage:  ./scripts/build-static.sh
# Output: staged into backend/bin/ (also left in scripts/out/)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
IMAGE="alpine:3.23"

# Fetch/refresh the vendored sources on the host (uses the https URLs in
# .gitmodules - no SSH keys needed).
echo "[*] Initializing source submodules..."
git -C "$REPO" submodule update --init externals/hostapd externals/iw externals/dnsmasq

# Register arm64 qemu binfmt handler if emulation isn't available yet.
if ! docker run --rm --platform linux/arm64 "$IMAGE" true 2>/dev/null; then
    echo "[*] Registering arm64 qemu binfmt handler..."
    docker run --rm --privileged tonistiigi/binfmt:latest --install arm64 >/dev/null
fi

echo "[*] Building static aarch64 binaries in $IMAGE ..."
# :z relabels the bind mount for SELinux (Fedora/RHEL hosts). externals/ is
# mounted read-only; the container copies sources out before building.
docker run --rm --platform linux/arm64 \
    -v "$HERE":/work:z \
    -v "$REPO/externals":/externals:ro,z \
    "$IMAGE" sh /work/build-in-container.sh

# Stage into backend/bin (where prepareAssets / the app expects them). Output is
# root-owned (built in-container); cp reads it fine and writes user-owned copies.
echo "[*] Staging into backend/bin ..."
mkdir -p "$REPO/backend/bin"
cp "$HERE/out/"* "$REPO/backend/bin/"

echo
echo "[*] Done:"
ls -lh "$REPO/backend/bin"
