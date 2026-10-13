# Mali / MediaTek (experimental)

Without an Adreno the runtime's Turnip has no GPU to open (it talks to KGSL), so this fork routes the
Linux side's Vulkan to the device's own driver instead:

- **Compositor**: `TurnipDriver` picks the system Vulkan driver when there is no KGSL node. Mali
  drivers from r4x import dma-bufs with DRM format modifiers, which is all the compositor needs.
- **Guest Vulkan**: Mesa 26.2.3's Venus ICD (`libvulkan_virtio.so`, glibc aarch64) talks vtest over
  `files/mali_venus/vtest.sock`. `VenusServerComponent` runs virglrenderer 1.3.0's
  `virgl_test_server --venus` on the Android side (`libvirgl_test_server.so`), which replays the calls
  on `libvulkan.so`. Memory crosses as dma-bufs the driver exports itself. termux's
  virglrenderer-android patches are applied except 0011, whose AHardwareBuffer blobs suit Adreno's
  Android driver but on Mali left the first blob the guest asked for unexportable.
- **BC textures**: Mali has none and DXVK/vkd3d-proton require them, so leegao's `bcn_layer` sits in
  front of Venus as an implicit layer and decodes them with compute shaders.
- **Present**: Venus over vtest cannot import dma-bufs, so windows present through Mesa's software
  WSI (`MESA_VK_WSI_DEBUG=sw`) and the Steam client's interface draws in software.

All of it is wired in `gpu/MaliVenus.kt`. The guest half ships as `assets/mali/venus-guest.tzst`.

## Switches

- `Download/droiddeck-venus`: `on` forces the Venus path on an Adreno, `off` turns it off on a Mali.
- `Download/droiddeck-env` lines override the session's environment, e.g.
  `BL_MALI_CEF_GPU=1` (try the client's GPU interface), `MESA_VK_WSI_DEBUG=` (try hardware present),
  `DISABLE_BCN_COMPUTE=1` (no BC layer), `VN_DEBUG=vtest,init` (more Venus logging).

## Logs

`venus.log` in the session's log folder holds the server's output (`VIRGL_LOG_LEVEL=info`, so a
failed allocation or blob export says why); `session.log` shows which Vulkan
driver the guest loaded. Share logs from Setup as usual.

## Rebuilding the native parts

`tools/mali/` holds the scripts that built the binaries (Mesa with `mesa-venus-vtest.patch` applied): `build-virgl-android.sh` (NDK 27.3),
`build-mesa-venus.sh` and `build-bcn-linux.sh` (cross-built in ubuntu:25.10).
