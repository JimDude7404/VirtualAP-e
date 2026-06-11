#!/bin/bash
# Build the flashable VirtualAP module zip.
# Bundles the latest rootfs tar.xz from out/ and extracts a static busybox
# from it (Alpine's busybox-static) so the zip is fully self-contained.
set -e

for dep in rsync zip; do
    command -v "$dep" >/dev/null 2>&1 || { echo "$dep is required but not installed."; exit 1; }
done

TAG=${1:-dev}
if [[ "$TAG" != "dev" && ! "$TAG" =~ ^v[0-9] ]]; then
    echo "Tag must start with 'v' followed by a number, e.g., v1 or v1.5"
    exit 1
fi

DATE=$(date +%Y%m%d)
ZIP_NAME="out/VirtualAP-${TAG}-${DATE}.zip"
mkdir -p out
rm -f "$ZIP_NAME"

# Latest rootfs tarball
ROOTFS_TAR=$(ls -t out/VirtualAP-rootfs-*.tar.xz 2>/dev/null | head -n1)
if [ -z "$ROOTFS_TAR" ]; then
    echo "No rootfs tarball found in out/ - run rootfs-builder/build_rootfs.sh first."
    exit 1
fi
echo "Bundling rootfs: $ROOTFS_TAR"

# Extract static busybox from the rootfs (host-side unshare/nsenter helper).
# Alpine's busybox-static package installs /bin/busybox.static.
mkdir -p backend/bin
if [ ! -f backend/bin/busybox ]; then
    echo "Extracting static busybox from rootfs..."
    tar -xJf "$ROOTFS_TAR" -O bin/busybox.static > backend/bin/busybox 2>/dev/null \
        || tar -xJf "$ROOTFS_TAR" -O ./bin/busybox.static > backend/bin/busybox
    [ -s backend/bin/busybox ] || { echo "Failed to extract busybox.static from rootfs"; exit 1; }
    chmod 755 backend/bin/busybox
fi

TMP_DIR=$(mktemp -d)

rsync -a \
    --exclude='.git*' \
    --exclude='out' \
    --exclude='rootfs-builder' \
    --exclude='build_zip.sh' \
    --exclude='README.md' \
    --exclude='Screenshots' \
    --exclude='Android' \
    "$PWD/" "$TMP_DIR/"

cp "$ROOTFS_TAR" "$TMP_DIR/"

# Stamp version into module.prop
VERSION_CODE=$(echo "$TAG" | sed 's/v//' | awk '{print int($1 * 1000)}')
[ "$TAG" = "dev" ] && VERSION_CODE=0
printf '%s\n' \
    "version=${TAG}" \
    "versionCode=${VERSION_CODE}" \
    >> "$TMP_DIR/module.prop"

ORIGINAL_PWD=$PWD
cd "$TMP_DIR"
zip -r -9 "$ORIGINAL_PWD/$ZIP_NAME" .
cd "$ORIGINAL_PWD"
rm -rf "$TMP_DIR"

echo "Created $ZIP_NAME ($(du -h "$ZIP_NAME" | cut -f1))"
