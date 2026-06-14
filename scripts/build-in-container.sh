#!/bin/sh
#
# Runs INSIDE an aarch64 Alpine container (see build-static.sh).
# Produces the static aarch64 binaries for VirtualAP:
#   hostapd + hostapd_cli  - AP daemon (nl80211, WPA2-PSK)
#   iw                     - virtual interface management
#   dnsmasq                - DHCP + DNS for AP clients
#   busybox                - reliable coreutils (Alpine's busybox-static)
#
# Output -> /work/out, source cache -> /work/.src-cache
set -e

OUT=/work/out
SRC=/work/.src-cache
mkdir -p "$OUT" "$SRC"

# Pinned versions (override via env from build-static.sh).
IW_VER="${IW_VER:-6.17}"
DM_VER="${DM_VER:-2.91}"

# Fully static, no PIE: a plain -static link on Alpine yields a static-PIE that
# still needs /lib/ld-musl-*.so.1 at runtime (absent on Android). -no-pie gives
# a true standalone ELF with no interpreter.
LDF="-static -no-pie"
CF="-Os -fno-pie"

echo "### Installing build deps"
apk add --no-cache build-base linux-headers pkgconf git wget xz \
    libnl3-dev libnl3-static openssl-dev openssl-libs-static busybox-static >/dev/null

# --- busybox ---------------------------------------------------------------
# Alpine's busybox-static is already a fully-static binary; just ship it. Used
# by the backend scripts for reliable coreutils (Android's toybox is flaky).
echo "### Staging static busybox"
cp /bin/busybox.static "$OUT/busybox"
strip "$OUT/busybox" 2>/dev/null || true

# --- hostapd ---------------------------------------------------------------
echo "### Building hostapd"
cd "$SRC"
[ -d hostap ] || git clone --depth=1 https://w1.fi/hostap.git
cd hostap/hostapd
cat > .config <<EOF
CONFIG_DRIVER_NL80211=y
CONFIG_LIBNL32=y
CONFIG_IEEE80211N=y
CONFIG_IEEE80211AC=y
CONFIG_IEEE80211AX=y
CONFIG_ACS=y
EOF
make clean >/dev/null 2>&1 || true
# PKG_CONFIG --static pulls libnl-3/libnl-genl-3 + libcrypto static deps.
make -j"$(nproc)" PKG_CONFIG="pkg-config --static" \
    LDFLAGS="$LDF" EXTRA_CFLAGS="$CF" hostapd hostapd_cli
strip hostapd hostapd_cli
cp hostapd hostapd_cli "$OUT"/

# --- iw --------------------------------------------------------------------
echo "### Building iw $IW_VER"
cd "$SRC"
[ -f "iw-$IW_VER.tar.xz" ] || \
    wget -q "https://mirrors.edge.kernel.org/pub/software/network/iw/iw-$IW_VER.tar.xz"
rm -rf "iw-$IW_VER" && tar xf "iw-$IW_VER.tar.xz"
cd "iw-$IW_VER"
make clean >/dev/null 2>&1 || true
# iw's Makefile does `LDFLAGS += $(pkg-config --libs ...)`, so LDFLAGS must come
# from the ENVIRONMENT (not a make-cmdline override, which would suppress the +=).
export LDFLAGS="$LDF"
export PKG_CONFIG="pkg-config --static"
make -j"$(nproc)" V=1 CFLAGS="$CF $(pkg-config --cflags libnl-3.0)"
unset LDFLAGS PKG_CONFIG
strip iw
cp iw "$OUT"/

# --- dnsmasq ---------------------------------------------------------------
echo "### Building dnsmasq $DM_VER"
cd "$SRC"
[ -f "dnsmasq-$DM_VER.tar.xz" ] || \
    wget -q "https://thekelleys.org.uk/dnsmasq/dnsmasq-$DM_VER.tar.xz"
rm -rf "dnsmasq-$DM_VER" && tar xf "dnsmasq-$DM_VER.tar.xz"
cd "dnsmasq-$DM_VER"
# Android has no "root" entry in /etc/passwd and static musl (unlike bionic)
# does not synthesize one, so dnsmasq's getpwnam("root") privilege-drop fails
# with "unknown user or group: root". Skip the lookup when user=root: ent_pw
# stays NULL, the setuid block is guarded by `if (ent_pw ...)`, so dnsmasq
# simply stays root (which it needs anyway to bind :53/:67).
sed -i \
    's/if (daemon->username \&\& !(ent_pw = getpwnam(daemon->username)))/if (daemon->username \&\& strcmp(daemon->username, "root") != 0 \&\& !(ent_pw = getpwnam(daemon->username)))/' \
    src/dnsmasq.c
grep -q 'strcmp(daemon->username, "root")' src/dnsmasq.c || { echo "dnsmasq root-user patch FAILED to apply"; exit 1; }
make clean >/dev/null 2>&1 || true
# Default dnsmasq has no external lib deps; plain static link works.
make -j"$(nproc)" CFLAGS="$CF" LDFLAGS="$LDF"
strip src/dnsmasq
cp src/dnsmasq "$OUT"/

# --- Verify ----------------------------------------------------------------
echo "### Results"
cd "$OUT"
for b in busybox hostapd hostapd_cli iw dnsmasq; do
    printf '%-12s ' "$b"
    file -b "$b"
done
echo "### All binaries built into $OUT"
