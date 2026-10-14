# gamescope patches carried by the app

Built by `.github/workflows/build-gamescope.yml` on top of the exact gamescope the Linux runtime
ships (3.16.29, Arch Linux ARM's package, same build options), and staged from the apk over
`/usr/local/bin/gamescope` at each session start - the hosted runtime image is never touched.
Its WSI layer (`libVkLayer_FROG_gamescope_wsi_aarch64.so`, loaded into every game) is staged over
the runtime's copy of the same version in `/usr/lib`: WSI patches (0132) live there, and until
gamescope-3.16.29-p9-mali5 only the binary was shipped, so they never reached a game.
The binary's shared-library needs are checked against `runtime-sonames.txt`, the runtime's own
library list, before anything is published.

- `0002-steamcompmgr-fallback-appid-focus.patch` - Armada (armada-os/armada), verbatim.
- `0009-fix-arm64-steam-night-mode.patch` - Armada, verbatim: the ARM64 client packs the
  night-mode property differently; the slider did nothing.
- `0019-steamcompmgr-arm64-virtual-white.patch` - Armada, verbatim: the colour-temperature
  slider's (x, y) arrives as one 64-bit element from the ARM64 client; y is recovered from x.
- `0020-color-p3-red-is-wide-gamut.patch` - Armada, verbatim.
- `0100-realtime-queue-and-gamepad-cursor.patch` - this app, two of Armada's ported by hand onto
  3.16.29: realtime-priority Vulkan queues on request (`GAMESCOPE_FORCE_VULKAN_REALTIME=1`)
  without CAP_SYS_NICE, which proot can never have (a no-op on KGSL Turnip, which has a single
  submit-queue priority); and the gamepad-driven cursor sprite following the X pointer that XTest
  moves (it sat frozen). The X pointer is asked for only while a cursor image is drawn - every
  vblank while shown, every 50 ms while hidden for inactivity - since each ask is a blocking round
  trip to Xwayland on the paint thread; with no image, wlserver's position is used as upstream does.
- `0110-wayland-backend-touch.patch` - this app: the nested Wayland backend bound only the host's
  pointer and keyboard, so a finger on the phone's screen never reached Steam. It now binds
  `wl_touch` too and hands each finger to wlserver's touch path (`wlserver_touchdown` / `motion` /
  `up`) - the one a Steam Deck's touchscreen drives - so what a touch does follows the client's
  touch mode (Steam's Big Picture sets Passthrough: a real touch, rows scroll under a finger).
  Finger ids are offset by one, since the nested pointer already moves wlserver's touch 0.
- `0111-wayland-pointer-warps-in-passthrough.patch` - this app: the nested pointer's motion goes to
  wlserver as touch 0, and in Passthrough (Big Picture's touch mode) a motion for a touch that is not
  down moves nothing - so in the app's touchpad mode the Steam client saw no hover and a click landed
  wherever the pointer had last been. The motion now always warps the real pointer as well
  (`bAlwaysWarpCursor`), which the other touch modes did already.
- `0112-restore-iconified-game-on-resume.patch` - this app: the Steam menu is an overlay that
  takes input without changing the focus window, and a fullscreen wine game minimizes itself when
  it loses input. gamescope only takes a window out of iconic when the focus window changes, and
  wine will not activate a window it believes iconic, so after Resume the game stayed minimized: a
  black screen with its small caption in the top-left corner (Titanfall 2, GE-Proton 11). The
  iconify request is remembered and the window goes back to NormalState before input returns to it,
  then focus is handed over again. `GAMESCOPE_RESTORE_FOCUS_WINDOW` on the root window asks for the
  same restore from outside (the session script's resume watcher).
- `0114-take-override-redirect-from-mapnotify.patch` - upstream (ValveSoftware/gamescope 3829340),
  verbatim; drop it once the runtime's gamescope includes it. Wine creates a game's window
  override-redirect and makes it managed before mapping it, which sends no X event. When gamescope
  read the window's attributes first, it kept the window as override-redirect and left it out of
  `GAMESCOPE_FOCUSABLE_WINDOWS`, so the client kept its loading screen over the running game
  (Skyrim SE, A Plague Tale: Innocence, on the games' own Xwayland). MapNotify now refreshes the flag.

Sixteen more of Armada's patches are DRM/lease/HDR-on-KMS work for a native display, which this
app's Wayland-hosted gamescope never reaches, or need a newer gamescope than the runtime has.
- `0120-no-memo-for-shm-buffers.patch` - this app: gamescope memoizes the texture it makes for each
  wl_buffer, which is right for a dma-buf (the texture shares it) but wrong for a shm buffer, whose
  contents are copied at import. Xwayland without glamor - no GBM on a Mali, where the guest's
  Vulkan is Venus - cycles a few shm buffers, so the Steam client flickered between stale frames
  (its library alternating with the "logging in" screen). Only dma-bufs are memoized now.
- `0130-linear-when-backend-has-no-modifiers.patch` - this app: a game on PanVK (Octopath
  Traveler) asked for a 10-bit A2B10G10R10 swapchain, the nested backend offered no modifier for
  that format (the app's compositor takes 8-bit RGB), and gamescope aborted on
  `assert( !modifiers.empty() )`, ending the session. It falls back to the linear modifier.
- `0131-composite-formats-host-cannot-show.patch` - this app: after 0130 the same game ran, but
  gamescope passed its 10-bit texture straight to the app's compositor as a plane, in a format the
  compositor never advertised, and the screen stayed black while the game drew 35-50 fps. Such a
  texture gets no host buffer now, and a layer without one is composited into the 8-bit output.
- `0132-wsi-hide-10bit-sdr-formats.patch` - this app: with 0131 the 10-bit swapchain was
  composited, but the game still showed black. `GAMESCOPE_WSI_HIDE_10BIT=1` (set by
  droiddeck-game-env for PanVK games) stops the WSI layer offering 10-bit sRGB formats, so DXVK
  presents 8-bit like the Steam client, whose frames show.
