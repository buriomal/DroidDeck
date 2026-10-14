package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Environment
import android.util.Log
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.TarZst
import org.json.JSONObject
import java.io.File

/**
 * Vulkan for the Linux session on a GPU Turnip cannot drive (Mali, Xclipse, PowerVR).
 *
 * The runtime's own driver talks to KGSL, so without an Adreno the guest has no Vulkan at all. Here
 * the guest gets Mesa's Venus driver instead, which serialises every Vulkan call over a unix socket;
 * on the other end a virglrenderer server ({@link com.droiddeck.launcher.session.VenusServerComponent})
 * runs out on the Android side and replays them on the vendor's own driver. Memory crosses as
 * dma-bufs the server allocates outside the driver and has it import, since Mali cannot export one.
 *
 * Mali also has no BC texture formats, which DXVK and vkd3d-proton require, so leegao's bcn_layer is
 * put in front of Venus as an implicit layer: it reports BC support and decodes those textures with
 * compute shaders on the GPU.
 *
 * The guest half (the ICD and the layer, both glibc builds) ships as one asset and is unpacked
 * under files/, which the session sees at the same path, so the manifests can name the libraries
 * absolutely and nothing in the runtime is modified.
 *
 * Download/droiddeck-venus overrides the GPU check: "on" forces this path on an Adreno (for
 * testing), "off" turns it off on a Mali (back to the black screen, but nothing in between).
 */
object MaliVenus {
    private const val TAG = "MaliVenus"
    private const val ASSET = "mali/venus-guest.tzst"
    /** Bumped with the asset, so an app update replaces the unpacked copy. */
    private const val VERSION = "mesa-26.2.3-venus-vtest2+bcn_layer-50993a2d-khr-pool-astclog"
    private const val OVERRIDE_FILE = "Download/droiddeck-venus"
    const val ICD_LIB = "libvulkan_virtio.so"
    const val LAYER_LIB = "libbcn_layer.so"
    private const val MODE_FILE = "Download/droiddeck-mali"
    const val MALI_DEVICE = "/dev/mali0"
    private const val PANVK_ASSET = "mali/panvk-guest.tzst"
    /** Bumped with the asset. */
    private const val PANVK_VERSION = "panvk-kbase-ca16389+droiddeck12"
    const val PANVK_LIB = "libvulkan_panfrost.so"
    private const val PROBE = "droiddeck-vkprobe"

    fun enabled(): Boolean {
        val forced = FileUtils.readString(File(Environment.getExternalStorageDirectory(), OVERRIDE_FILE))
            ?.trim()?.lowercase()
        return when {
            forced == "on" -> true
            forced == "off" -> false
            else -> !DeviceSupport.adreno()
        }
    }

    /**
     * PanVK instead of Venus: Mesa's own Mali driver, built for Android's kbase kernel driver, so the
     * guest drives the GPU itself with nothing relayed. Experimental on the G1 series (Mesa marks
     * v14 "not well-tested"). Download/droiddeck-mali says which: "panvk" or "venus"; with nothing
     * there, PanVK where its asset and the kbase node are present.
     */
    fun panvk(): Boolean {
        val forced = FileUtils.readString(File(Environment.getExternalStorageDirectory(), MODE_FILE))
            ?.trim()?.lowercase()
        return when (forced) {
            "panvk" -> true
            "venus" -> false
            else -> File(MALI_DEVICE).exists()
        }
    }

    /** PanVK's guest half (the ICD and a probe that logs what the loader finds), unpacked and current. */
    fun preparePanvk(context: Context): File? {
        val guest = File(context.filesDir, "mali_panvk")
        val marker = File(guest, ".version")
        if (FileUtils.readString(marker)?.trim() != PANVK_VERSION || !File(guest, PANVK_LIB).isFile) {
            FileUtils.delete(guest)
            if (!TarZst.extractAsset(context, PANVK_ASSET, guest)) {
                Log.e(TAG, "could not unpack $PANVK_ASSET")
                FileUtils.delete(guest)
                return null
            }
            File(guest, PROBE).setExecutable(true, false)
            FileUtils.writeString(marker, PANVK_VERSION)
        }
        val icd = File(guest, "panvk_icd.json")
        FileUtils.writeString(icd, JSONObject()
            .put("file_format_version", "1.0.0")
            .put("ICD", JSONObject()
                .put("library_path", File(guest, PANVK_LIB).absolutePath)
                .put("api_version", "1.4.0"))
            .toString(2))
        Log.i(TAG, "guest PanVK ready at $guest")
        return icd
    }

    /**
     * The guest environment for a PanVK session. PanVK has no BC texture formats either, so the
     * Venus asset's bcn_layer goes in front of it the same way (unpacked by [prepare]).
     */
    fun panvkGuestEnv(context: Context, icd: File): List<String> {
        val layerGuest = prepare(context)?.parentFile
        return listOf(
            LinuxVulkanDriver.ENV + "=" + icd.absolutePath,
            // Mesa refuses v14 (the G1 series) without it: "panvk is not well-tested on v14".
            "PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1",
            // Read by the session script: log what the loader finds before anything depends on it.
            "BL_MALI_PANVK=" + File(icd.parentFile, PROBE).absolutePath,
            // Which driver build this is, in the log: two test APKs once looked the same from outside.
            "BL_MALI_PANVK_BUILD=$PANVK_VERSION",
        ) + if (layerGuest != null && File(layerGuest, LAYER_LIB).isFile) listOf(
            "VK_ADD_IMPLICIT_LAYER_PATH=" + File(layerGuest, "implicit_layer.d").absolutePath,
            // Games only (droiddeck-game-env turns the layer on): loaded into the Steam client's
            // own Vulkan it broke Big Picture's drawing.
            "BL_BCN_GAMES=1",
        ) else emptyList()
    }

    fun dir(context: Context): File = File(context.filesDir, "mali_venus")

    /** The socket the server listens on and the guest's Venus connects to; the same path on both sides. */
    fun socket(context: Context): File = File(dir(context), "vtest.sock")

    /** The guest's ICD manifest, unpacked and current, or null when the asset could not be unpacked. */
    fun prepare(context: Context): File? {
        val root = dir(context)
        val guest = File(root, "guest")
        val marker = File(guest, ".version")
        if (FileUtils.readString(marker)?.trim() != VERSION || !File(guest, ICD_LIB).isFile) {
            FileUtils.delete(guest)
            if (!TarZst.extractAsset(context, ASSET, guest)) {
                Log.e(TAG, "could not unpack $ASSET")
                FileUtils.delete(guest)
                return null
            }
            FileUtils.writeString(marker, VERSION)
        }
        val icd = File(guest, "venus_icd.json")
        FileUtils.writeString(icd, JSONObject()
            .put("file_format_version", "1.0.0")
            .put("ICD", JSONObject()
                .put("library_path", File(guest, ICD_LIB).absolutePath)
                .put("api_version", "1.3.0"))
            .toString(2))
        val layers = File(guest, "implicit_layer.d").apply { mkdirs() }
        FileUtils.writeString(File(layers, "bcn_layer.json"), JSONObject()
            .put("file_format_version", "1.0.0")
            .put("layer", JSONObject()
                .put("name", "VK_LAYER_BCN_BCnLayer")
                .put("type", "GLOBAL")
                .put("library_path", File(guest, LAYER_LIB).absolutePath)
                .put("api_version", "1.3.0")
                .put("implementation_version", "1")
                .put("description", "BCn Decompression Layer")
                .put("functions", JSONObject()
                    .put("vkGetInstanceProcAddr", "BCnLayer_GetInstanceProcAddr")
                    .put("vkGetDeviceProcAddr", "BCnLayer_GetDeviceProcAddr"))
                .put("enable_environment", JSONObject().put("ENABLE_BCN_COMPUTE", "1"))
                .put("disable_environment", JSONObject().put("DISABLE_BCN_COMPUTE", "1")))
            .toString(2))
        Log.i(TAG, "guest Venus ready at $guest")
        return icd
    }

    /**
     * The guest environment for a Venus session. BL_VK_DRIVER goes through the session script's own
     * import check, so a missing library falls back to the runtime's driver with a line in the log.
     */
    fun guestEnv(context: Context, icd: File): List<String> {
        val guest = icd.parentFile!!
        return listOf(
            LinuxVulkanDriver.ENV + "=" + icd.absolutePath,
            "VN_DEBUG=vtest,init",
            "VTEST_SOCKET_NAME=" + socket(context).absolutePath,
            // Venus over vtest cannot import a dma-buf, so a window's buffers cannot be handed to
            // Xwayland or gamescope by DRI3. Mesa's software present copies them instead.
            "MESA_VK_WSI_DEBUG=sw",
            // Read by the session script: the Steam client's own GL windows draw with llvmpipe, and its
            // interface tries ANGLE-on-Vulkan before falling back to software.
            "BL_MALI=1",
        ) + if (File(guest, LAYER_LIB).isFile) listOf(
            "VK_ADD_IMPLICIT_LAYER_PATH=" + File(guest, "implicit_layer.d").absolutePath,
            "ENABLE_BCN_COMPUTE=1",
        ) else emptyList()
    }
}
