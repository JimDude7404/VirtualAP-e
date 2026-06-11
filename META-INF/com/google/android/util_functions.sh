#!/system/bin/sh

# VirtualAP installation functions

TMPDIR=/dev/tmp
VAP_DIR="/data/local/virtualap"

# Detect root method
detect_root() {
    if command -v magisk >/dev/null 2>&1; then
        ROOT_METHOD="magisk"
        echo -e "- Magisk detected\n"
        echo -e "- NOTE: the WebUI needs KernelSU/APatch or a WebUI launcher app (e.g. WebUI-X) on Magisk.\n"
    elif command -v ksud >/dev/null 2>&1; then
        ROOT_METHOD="kernelsu"
        echo -e "- KernelSU detected\n"
    elif command -v apd >/dev/null 2>&1; then
        ROOT_METHOD="apatch"
        echo -e "- APatch detected\n"
    else
        ROOT_METHOD="unknown"
        echo -e "- Unknown root method detected. Proceed with caution.\n"
    fi
}

setup_busybox() {
    mkdir -p "$VAP_DIR/bin"

    if unzip -oj "$ZIPFILE" 'backend/bin/busybox' -d "$VAP_DIR/bin" >&2 \
        && chmod 755 "$VAP_DIR/bin/busybox"; then
        echo "- Busybox extracted successfully" >&2
        export BUSYBOX="$VAP_DIR/bin/busybox"
    else
        echo "- Failed to extract busybox, falling back to system busybox" >&2
        if ! command -v busybox >/dev/null 2>&1; then
            echo "- System busybox not found. Aborting." >&2
            exit 1
        fi
        export BUSYBOX="busybox"
    fi
}

# Extract core tools
setup_tools() {
    mkdir -p "$VAP_DIR"
    setup_busybox
    unzip -oj "$ZIPFILE" 'backend/vap.sh' -d "$VAP_DIR" >&2
    unzip -oj "$ZIPFILE" 'backend/start-ap' -d "$VAP_DIR" >&2
    echo "- Core tools extracted"
}

# Find rootfs file in ZIP
find_rootfs_file() {
    unzip -l "$ZIPFILE" 2>/dev/null | grep -E '\.tar\.xz$' | head -1 | while read -r line; do
        echo "$line" | rev | cut -d' ' -f1 | rev
    done
}

# Extract the Alpine rootfs (plain directory - no sparse image)
extract_rootfs() {
    echo "- Setting up Alpine rootfs..."

    local rootfs_file rootfs_dir
    rootfs_file=$(find_rootfs_file)
    rootfs_dir="$VAP_DIR/rootfs"

    if [ -z "$rootfs_file" ]; then
        echo "- No rootfs file found in ZIP archive..Skipping extraction..."
        return 0
    fi

    if [ -d "$rootfs_dir" ]; then
        echo "- Rootfs already exists. Skipping extraction..."
        return 0
    fi

    echo "- Found rootfs file: $rootfs_file"
    mkdir -p "$rootfs_dir" "$TMPDIR"
    if unzip -oq "$ZIPFILE" "$rootfs_file" -d "$TMPDIR" \
        && tar -xpf "$TMPDIR/$rootfs_file" -C "$rootfs_dir"; then
        echo "- Alpine rootfs extracted successfully"
        return 0
    else
        echo "- Rootfs extraction failed"
        rm -rf "$rootfs_dir"
        return 1
    fi
}

# Create command symlink
create_symlink() {
    mkdir -p "$MODPATH/system/bin"
    if ln -sf "$VAP_DIR/start-ap" "$MODPATH/system/bin/virtualap"; then
        echo "- Created symlink for 'virtualap' command"
    else
        echo "- Failed to create symlink for 'virtualap' command"
        exit 1
    fi
}
