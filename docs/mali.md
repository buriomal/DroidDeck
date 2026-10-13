# Mali / MediaTek (experimental)

Without an Adreno the runtime's Turnip has no GPU to open (it talks to KGSL), so this fork routes the
Linux side's Vulkan to the device's own driver instead:

- **Compositor**: `TurnipDriver` picks the system Vulkan driver when there is no KGSL node. Mali
  drivers from r4x import dma-bufs with DRM format modifiers, which is all the compositor needs.
- **Guest Vulkan**: Mesa 26.2.3's Venus ICD (`libvulkan_virtio.so`, glibc aarch64) talks vtest over
  `files/mali_venus/vtest.sock`. `VenusServerComponent` runs virglrenderer 1.3.0's
  `virgl_test_server --venus` on the Android side (`libvirgl_test_server.so`), which replays the calls
  on `libvulkan.so`. Mali imports dma-bufs but cannot export them, so the server allocates
  shared memory from an uncached dma-buf heap (or gralloc, as a BLOB AHardwareBuffer) and has the
  driver import it (`tools/mali/virgl-android-dmabuf.patch`). termux's virglrenderer-android patches
  are applied except 0010 and 0011.
- **BC textures**: Mali has none and DXVK/vkd3d-proton require them, so leegao's `bcn_layer` sits in
  front of Venus as an implicit layer and decodes them with compute shaders.
- **Missing features**: DXVK refuses to create a D3D11 device without geometry shaders, clip and
  cull distances and a few more that Mali lacks, and the game stops with "DX11 feature level 10.0
  is required". The server reports them anyway and leaves them off at `vkCreateDevice`, so the
  game starts and only a pipeline that really uses one fails (missing effects rather than no game).
  `venus.log` lists each one as `feature …: not in the driver`.
- **Present**: Venus over vtest cannot import dma-bufs, so windows present through Mesa's software
  WSI (`MESA_VK_WSI_DEBUG=sw`). The Steam client's interface tries ANGLE-on-Vulkan on Venus and falls
  back to software if the client dies; its own GL windows use llvmpipe.

- **PanVK (experimental, the default where `/dev/mali0` exists)**: Mesa's own Mali driver, built for
  Android's kbase kernel driver (`tools/mali/build-panvk-kbase.sh`), drives the GPU from the guest
  with nothing relayed. Mesa marks the G1 series (v14) "not well-tested", so the session sets
  `PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1`. `/dev/mali0` is bound into the session as its render
  node, and `session.log` carries a `panvk probe` section listing the devices the loader found and
  whether one opened. `Download/droiddeck-mali` containing `venus` goes back to Venus.

All of it is wired in `gpu/MaliVenus.kt`. The guest half ships as `assets/mali/venus-guest.tzst`.

## Switches

- `Download/droiddeck-venus`: `on` forces the Venus path on an Adreno, `off` turns it off on a Mali.
- `Download/droiddeck-env` lines override the session's environment, e.g.
  `BL_MALI_CEF_GPU=0` (the client's interface in software from the start; by default it tries the
  GPU and falls back when the client dies within 90 s), `BL_MALI_GL=zink` (the client's GL windows
  through Zink on Venus instead of llvmpipe), `MESA_VK_WSI_DEBUG=` (try hardware present),
  `DISABLE_BCN_COMPUTE=1` (no BC layer), `VN_DEBUG=vtest,init` (more Venus logging).

## Logs

`venus.log` in the session's log folder holds the server's output (`VIRGL_LOG_LEVEL=info`, so a
failed allocation or blob export says why); `session.log` shows which Vulkan
driver the guest loaded. Share logs from Setup as usual.

## Rebuilding the native parts

`tools/mali/` holds the scripts that built the binaries (Mesa with `mesa-venus-vtest.patch` applied): `build-virgl-android.sh` (NDK 27.3),
`build-mesa-venus.sh` and `build-bcn-linux.sh` (cross-built in ubuntu:25.10).
