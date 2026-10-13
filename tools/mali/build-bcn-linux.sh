#!/bin/bash
# Cross-builds leegao/bcn_layer for the aarch64 glibc runtime, in ubuntu:25.10.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
cp /ca.crt /usr/local/share/ca-certificates/ccr.crt && update-ca-certificates >/dev/null
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
apt-get install -y -qq --no-install-recommends crossbuild-essential-arm64 cmake make glslc xxd libvulkan-dev:arm64 >/dev/null
export PATH=/work/src/slang/bin:$PATH LD_LIBRARY_PATH=/work/src/slang/lib
B=/work/build/bcn-linux
cmake -S /work/src/bcn_layer -B $B -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++ \
  -DLIB_INSTALL_DIR=lib -DCMAKE_CXX_FLAGS="-include mutex -include cstdint"
cmake --build $B -j"$(nproc)"
mkdir -p /work/out/linux
cp -v $B/libbcn_layer.so /work/out/linux/
aarch64-linux-gnu-strip /work/out/linux/libbcn_layer.so
