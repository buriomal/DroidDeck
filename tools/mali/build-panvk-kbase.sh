#!/bin/bash
# Cross-builds PanVK on Android's kbase kernel driver (wonderkast02/panvk-g720-kbase-csf, with
# DroidDeck's patches) as an aarch64 glibc ICD for the Linux runtime, in ubuntu:25.10.
# /work/panvk/src holds the source: https://github.com/wonderkast02/panvk-g720-kbase-csf.git,
# branch g720-development at ca163891e8d3, with tools/mali/panvk-patches/*.patch applied (from
# emir34-34/DroidDeck's Mali fork: the kbase node as DRM render/primary device, and a 64-bit
# fix for the provoking-vertex helper's address). The probe (vkprobe.c) is built beside it with
# aarch64-linux-gnu-gcc -I<src>/include and packed with the driver into assets/mali/panvk-guest.tzst.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
mkdir -p /etc/ssl/certs && cat /ca.crt >> /etc/ssl/certs/ca-certificates.crt
[ -n "${APT_PROXY:-}" ] && echo "Acquire::https::Proxy \"$APT_PROXY\";" > /etc/apt/apt.conf.d/proxy
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
LLVM=${LLVM:-20}
apt-get install -y -qq --no-install-recommends crossbuild-essential-arm64 build-essential meson ninja-build pkg-config \
  python3-mako python3-yaml python3-packaging python3-pycparser python3-ply glslang-tools bison flex \
  llvm-$LLVM-dev clang-$LLVM libclang-$LLVM-dev libclang-cpp$LLVM-dev libclc-$LLVM-dev libllvmspirvlib-$LLVM-dev spirv-tools \
  libdrm-dev libexpat1-dev zlib1g-dev libzstd-dev \
  libdrm-dev:arm64 libwayland-dev:arm64 wayland-protocols libx11-dev:arm64 libxcb1-dev:arm64 libx11-xcb-dev:arm64 libxcb-dri3-dev:arm64 libxcb-present-dev:arm64 \
  libxcb-shm0-dev:arm64 libxcb-randr0-dev:arm64 libxcb-sync-dev:arm64 libxshmfence-dev:arm64 libxrandr-dev:arm64 libxext-dev:arm64 libxxf86vm-dev:arm64 \
  zlib1g-dev:arm64 libexpat1-dev:arm64 libwayland-bin >/dev/null
export PATH=/usr/lib/llvm-$LLVM/bin:$PATH
S=/work/panvk/src
H=/work/build/panvk-host; rm -rf $H
meson setup $H $S -Dplatforms=[] -Dgallium-drivers=[] -Dvulkan-drivers=[] -Dtools=panfrost \
  -Dprecomp-compiler=enabled -Dinstall-precomp-compiler=true -Dllvm=enabled -Dmesa-clc=enabled -Dinstall-mesa-clc=true \
  -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dopengl=false -Dgles1=disabled -Dgles2=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dbuild-tests=false
ninja -C $H src/compiler/clc/mesa_clc src/compiler/spirv/vtn_bindgen2 src/panfrost/clc/panfrost_compile
mkdir -p /tmp/hb; for t in src/compiler/clc/mesa_clc src/compiler/spirv/vtn_bindgen2 src/panfrost/clc/panfrost_compile; do ln -sf $H/$t /tmp/hb/; done
export PATH=/tmp/hb:$PATH
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
B=/work/build/panvk; rm -rf $B
PKG_CONFIG_LIBDIR=/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig \
meson setup $B $S --cross-file /tmp/cross.txt --prefix=/usr --buildtype=release -Db_ndebug=true \
  -Dplatforms=x11,wayland -Dglx=disabled -Dgbm=disabled -Degl=disabled -Dopengl=false -Dgles1=disabled \
  -Dgles2=disabled -Dglvnd=disabled -Dvalgrind=disabled -Dgallium-drivers= -Dzstd=disabled \
  -Dmesa-clc=system -Dprecomp-compiler=system -Dvulkan-drivers=panfrost -Dllvm=disabled \
  -Dpanfrost-kmds=kbase,panthor,panfrost -Dlibunwind=disabled -Dexpat=enabled -Dvideo-codecs= -Dvulkan-layers= -Dbuild-tests=false
ninja -C $B src/panfrost/vulkan/libvulkan_panfrost.so
mkdir -p /work/out/linux; cp -v $B/src/panfrost/vulkan/libvulkan_panfrost.so /work/out/linux/
aarch64-linux-gnu-strip /work/out/linux/libvulkan_panfrost.so
