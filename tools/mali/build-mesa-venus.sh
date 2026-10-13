#!/bin/bash
# Cross-builds Mesa's Venus Vulkan ICD (libvulkan_virtio.so) for the aarch64 glibc runtime, in ubuntu:25.10.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
cp /ca.crt /usr/local/share/ca-certificates/ccr.crt && update-ca-certificates >/dev/null
echo 'Acquire::https::Proxy "http://127.0.0.1:40131";' > /etc/apt/apt.conf.d/proxy
dpkg --add-architecture arm64
cat > /etc/apt/sources.list.d/ubuntu.sources <<X
Types: deb
URIs: http://archive.ubuntu.com/ubuntu
Suites: questing questing-updates
Components: main universe
Architectures: amd64
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: http://ports.ubuntu.com/ubuntu-ports
Suites: questing questing-updates
Components: main universe
Architectures: arm64
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
X
apt-get update -qq
apt-get install -y -qq --no-install-recommends crossbuild-essential-arm64 meson ninja-build pkg-config python3-mako python3-yaml python3-packaging glslang-tools bison flex \
  libdrm-dev:arm64 libwayland-dev:arm64 wayland-protocols libx11-dev:arm64 libxcb1-dev:arm64 libx11-xcb-dev:arm64 libxcb-dri3-dev:arm64 libxcb-present-dev:arm64 \
  libxcb-shm0-dev:arm64 libxcb-randr0-dev:arm64 libxcb-sync-dev:arm64 libxshmfence-dev:arm64 libxrandr-dev:arm64 libxext-dev:arm64 libxxf86vm-dev:arm64 \
  libzstd-dev:arm64 zlib1g-dev:arm64 libexpat1-dev:arm64 libwayland-bin >/dev/null
cat > /tmp/cross.txt <<X
[binaries]
c = 'aarch64-linux-gnu-gcc'
cpp = 'aarch64-linux-gnu-g++'
ar = 'aarch64-linux-gnu-ar'
strip = 'aarch64-linux-gnu-strip'
nm = 'aarch64-linux-gnu-nm'
pkg-config = 'pkg-config'
[properties]
pkg_config_libdir = '/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig'
[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
X
B=/work/build/mesa-venus; rm -rf $B
PKG_CONFIG_LIBDIR=/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig \
meson setup $B /work/src/mesa --cross-file /tmp/cross.txt --prefix=/usr --buildtype=release -Db_ndebug=true \
  -Dvulkan-drivers=virtio -Dgallium-drivers= -Dplatforms=x11,wayland -Dglx=disabled -Degl=disabled -Dgbm=disabled \
  -Dopengl=false -Dgles1=disabled -Dgles2=disabled -Dllvm=disabled -Dvideo-codecs= -Dvulkan-layers= -Dtools= -Dbuild-tests=false
ninja -C $B
mkdir -p /work/out/linux; cp -v $B/src/virtio/vulkan/libvulkan_virtio.so /work/out/linux/
aarch64-linux-gnu-strip /work/out/linux/libvulkan_virtio.so
