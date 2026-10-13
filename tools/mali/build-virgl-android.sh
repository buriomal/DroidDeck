#!/bin/bash
# Builds virglrenderer 1.3.0 (venus over vtest, Android Vulkan via AHardwareBuffer) for arm64 Android
# with termux's virglrenderer-android patches. Runs inside cimg/android (NDK 27.3).
set -euo pipefail
NDK=$ANDROID_HOME/ndk/27.3.13750724
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=29
W=/work/build/virgl; rm -rf $W; mkdir -p $W; cd $W
tar xf /work/src/virglrenderer_1.3.0.orig.tar.bz2
SRC=$(ls -d virglrenderer*/ | head -1); SRC=$W/${SRC%/}
cp -r /work/src/libepoxy $SRC/libepoxy
cd $SRC
# 0010 clashes with NDK 27's libc. 0011 swaps dma_buf for AHardwareBuffer imports, which Adreno's
# Android driver needs; on Mali the guest's first blob failed with it (17T Pro, venus.log).
for p in $(ls /work/src/tp/packages/virglrenderer-android/*.patch.beforehostbuild | grep -Ev '0010|0011'); do
  sed "s|@TERMUX_PREFIX@|/data/data/com.droiddeck.launcher/files/usr|g" "$p" > /tmp/p.patch
  echo "== $(basename $p)"; patch -p1 --forward < /tmp/p.patch || patch -p0 --forward < /tmp/p.patch
done
# Mali imports dma-bufs but cannot export them: allocate them outside the driver instead (no gbm on Android).
patch -p1 < /work/scripts/virgl-android-dmabuf.patch
cat > $W/cross.txt <<X
[binaries]
c = '$TC/bin/aarch64-linux-android$API-clang'
cpp = '$TC/bin/aarch64-linux-android$API-clang++'
ar = '$TC/bin/llvm-ar'
strip = '$TC/bin/llvm-strip'
pkg-config = 'pkg-config'
[built-in options]
c_args = ['-D__TERMUX__', '-Wno-error=gnu-offsetof-extensions']
cpp_args = ['-D__TERMUX__']
c_link_args = ['-Wl,-rpath,\$ORIGIN', '-landroid']
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
X
P=$W/prefix
export PKG_CONFIG_LIBDIR=$P/lib/pkgconfig PKG_CONFIG_PATH=
meson setup $W/epoxy-build libepoxy --cross-file $W/cross.txt --prefix=$P --libdir lib -Degl=yes -Dglx=no -Dx11=false -Dtests=false
ninja -C $W/epoxy-build install
meson setup $W/vb . --cross-file $W/cross.txt --prefix=$P --libdir lib -Dvenus=true -Dplatforms=egl -Dtests=false
ninja -C $W/vb install
mkdir -p /work/out/android; cp -v $P/libexec/virgl_render_server $P/bin/virgl_test_server $P/lib/libvirglrenderer.so* $P/lib/libepoxy.so* /work/out/android/ 2>&1 | tail -5
