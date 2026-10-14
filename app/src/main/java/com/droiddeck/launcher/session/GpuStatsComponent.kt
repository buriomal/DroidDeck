package com.droiddeck.launcher.session

import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.RandomAccessFile

/**
 * The Adreno's stats as mangoapp (Deck mode's performance overlay) reads them.
 *
 * MangoHud takes an Adreno's load, clock and temperature from `gpu_busy_percentage`, `clock_mhz`
 * and `temp` in `/sys/class/kgsl/kgsl-3d0`, and ends the process on one of them it may not read -
 * not on one that is missing, which it skips. An enforcing SELinux policy (every retail phone)
 * refuses apps some or all of KGSL's sysfs, each vendor differently, so mangoapp died on start and
 * the session ran with no overlay. Where any of the three is refused, a directory of our own is
 * bound there instead. A value with no
 * readable source is left out, and the overlay shows no line for it. Where all three are
 * readable, nothing is done.
 *
 * Valve's build also reads load and clock where a mainline msm kernel has them (debugfs
 * `perf_now`, the GPU's devfreq `cur_freq` in Hz) - the paths LinuxRuntime.bindAdrenoStats points
 * at KGSL's own files when it may - so the same values are fed there too, in the same formats
 * ([binds]).
 *
 * A Mali (MediaTek, kbase) has no KGSL, and what MangoHud reads for a Mali's driver (panfrost: DRM
 * fdinfo and a gpu_thermal hwmon) kbase does not provide. There the same directory is fed from what
 * the vendor kernel has instead (GED, the Mali's own sysfs, devfreq, the GPU's thermal zones; see
 * [maliLoad], [maliClock], [maliTemp]) and bound at KGSL's path, and LinuxRuntime.bindGpuNode then
 * names the render node's driver msm_drm, so MangoHud reads it as it reads an Adreno's. Where none
 * is readable, nothing is bound and the node stays panfrost, as before.
 */
class GpuStatsComponent(val dir: File) : SessionPart() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val feeds = ArrayList<Feed>()

    /** One of MangoHud's files and where its value comes from. */
    private class Feed(
        val file: File, val source: String, val read: () -> Long?, val format: String, val percent: Boolean = false,
    )

    /** Where Valve's msm reader looks, and the feed for it, as host:guest binds. */
    fun binds(): List<String> {
        val list = mutableListOf(dir.path + ":" + KGSL)
        if (File(dir, "perf_now").exists()) list.add(File(dir, "perf_now").path + ":" + MSM_LOAD)
        if (File(dir, "cur_freq").exists()) list.add(File(dir, "cur_freq").path + ":" + MSM_CLOCK)
        return list
    }

    /** Writes the directory; true when it should be bound over [KGSL]. */
    fun prepare(): Boolean {
        val mali = !File(KGSL).exists() && File(MALI_DEVICE).exists()
        if (!mali && (!File(KGSL).exists() || MANGOHUD_FILES.none { File(KGSL, it).let { f -> f.exists() && !f.canRead() } })) return false
        return try {
            dir.mkdirs()
            dir.listFiles()?.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
            feeds.clear()
            if (mali) {
                maliLoad()?.let { addLoad(Feed(File(dir, "gpu_busy_percentage"), it.path, it.read, "%3d %%\n")) }
                maliClock()?.let { addClock(Feed(File(dir, "clock_mhz"), it.path, it.read, "%5d\n")) }
                maliTemp()?.let { feeds.add(Feed(File(dir, "temp"), it.path, it.read, "%6d\n")) }
            } else {
                loadFeed()?.let { addLoad(it) }
                pick("clock_mhz", clockSources(), "%5d\n") { mhz(it) }?.let { addClock(it) }
                pick("temp", tempSources(), "%6d\n") { milliCelsius(it) }?.let { feeds.add(it) }
            }
            val sources = MANGOHUD_FILES.joinToString { name ->
                "$name " + (feeds.firstOrNull { it.file.name == name }?.source ?: "none")
            }
            if (mali && feeds.isEmpty()) {
                // As before Mali support: nothing bound, and the overlay has no GPU lines.
                Log.i(TAG, "hud: mali gpu stats: no source this app may read; the overlay shows none")
                return false
            }
            feeds.forEach { feed -> feed.read()?.let { write(feed, it) } }
            Log.i(TAG, (if (mali) "hud: mali gpu stats" else "hud: kgsl stats") + " from the session's own: " + sources)
            true
        } catch (e: Exception) {
            Log.w(TAG, "hud: could not stand in for kgsl stats: $e")
            false
        }
    }

    private fun addLoad(load: Feed) {
        feeds.add(load)
        // KGSL's gpu_busy_percentage reads "37 %"; padded after, so a shorter value
        // written over a longer one leaves no digits behind.
        feeds.add(Feed(File(dir, "perf_now"), load.source, load.read, "%-6s\n", percent = true))
    }

    private fun addClock(clock: Feed) {
        feeds.add(clock)
        feeds.add(Feed(File(dir, "cur_freq"), clock.source, { clock.read()?.times(1_000_000) }, "%-10d\n"))
    }

    override fun start() {
        if (feeds.isEmpty()) return
        running = true
        thread = Thread({
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (!running) break
                for (feed in feeds) feed.read()?.let { write(feed, it) }
            }
        }, "gpu-stats").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** Load: a file that states it in percent, else KGSL's busy and total of the last sample. */
    private fun loadFeed(): Feed? {
        pick("gpu_busy_percentage", LOAD_SOURCES, "%3d %%\n") { it.coerceIn(0, 100) }?.let { return it }
        val gpubusy = "$KGSL/gpubusy"
        val read = {
            runCatching {
                val parts = File(gpubusy).readText().trim().split(Regex("\\s+"))
                val total = parts[1].toLong()
                if (total > 0) (parts[0].toLong() * 100 / total).coerceIn(0, 100) else 0L
            }.getOrNull()
        }
        return if (read() != null) Feed(File(dir, "gpu_busy_percentage"), gpubusy, read, "%3d %%\n") else null
    }

    /** The first of [sources] that reads as a number, its value put in MangoHud's unit by [unit]. */
    private fun pick(name: String, sources: List<String>, format: String, unit: (Long) -> Long?): Feed? {
        for (source in sources) {
            val read = { firstNumber(source)?.let(unit) }
            if (read() != null) return Feed(File(dir, name), source, read, format)
        }
        return null
    }

    /** In place and at one length, so a reader that keeps the file open sees each value whole. */
    private fun write(feed: Feed, value: Long) {
        try {
            val text = if (feed.percent) String.format(feed.format, "$value %") else String.format(feed.format, value)
            RandomAccessFile(feed.file, "rw").use { it.seek(0); it.write(text.toByteArray()) }
        } catch (e: Exception) {
        }
    }

    /** A Mali value's first readable source, and a read of it in MangoHud's unit. */
    class Source(val path: String, val read: () -> Long?)

    companion object {
        private const val TAG = "SessionService"
        const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        private const val MALI_DEVICE = "/dev/mali0"
        /** Must match LinuxRuntime.bindAdrenoStats. */
        const val MSM_LOAD = "/sys/kernel/debug/dri/0/perf_now"
        const val MSM_CLOCK = "/sys/devices/platform/soc@0/3d00000.gpu/devfreq/3d00000.gpu/cur_freq"
        private const val PERIOD_MS = 500L
        private val MANGOHUD_FILES = listOf("gpu_busy_percentage", "clock_mhz", "temp")
        /** Qualcomm's own GPU summary, beside KGSL in vendor kernels, under the plain sysfs label. */
        private const val QCOM_GPU = "/sys/kernel/gpu"
        private val LOAD_SOURCES = listOf(
            "$KGSL/gpu_busy_percentage", "$KGSL/devfreq/gpu_load", "$KGSL/gpuload", "$QCOM_GPU/gpu_busy",
        )
        /** The GPU's devfreq node is named by its register address, which differs between Snapdragons. */
        private val DEVFREQ_NODES = listOf(
            "kgsl-3d0", "3d00000.qcom,kgsl-3d0", "2c00000.qcom,kgsl-3d0", "5000000.qcom,kgsl-3d0", "5900000.qcom,kgsl-3d0",
        )

        private fun clockSources(): List<String> {
            val sources = mutableListOf(
                "$KGSL/clock_mhz", "$KGSL/gpu_clock", "$KGSL/gpuclk", "$KGSL/devfreq/cur_freq", "$QCOM_GPU/gpu_clock",
            )
            DEVFREQ_NODES.forEach { sources.add("/sys/class/devfreq/$it/cur_freq") }
            File("/sys/class/devfreq").list()?.sorted()?.forEach { node ->
                if (node.contains("kgsl") || node.contains("gpu")) sources.add("/sys/class/devfreq/$node/cur_freq")
            }
            return sources.distinct()
        }

        private fun tempSources(): List<String> =
            listOfNotNull("$KGSL/temp", "$KGSL/devfreq/temp", "$QCOM_GPU/temp", LinuxRuntime.gpuTempSource()).distinct()

        /** MediaTek's GPU DVFS driver; most of its files are refused to apps on some ROMs, not on others. */
        private const val GED = "/sys/kernel/ged/hal"
        private const val MALI_SYSFS = "/sys/class/misc/mali0/device"

        /**
         * A Mali's load in percent: GED's gpu_utilization ("loading block idle"), the Mali's own
         * utilization, a GPU devfreq node's load ("busy@freqHz"); the first number of each.
         */
        fun maliLoad(): Source? {
            val sources = mutableListOf("$GED/gpu_utilization", "/sys/module/ged/parameters/gpu_loading", "$MALI_SYSFS/utilization")
            list("/sys/devices/platform").filter { it.contains("mali") }.forEach { sources.add("/sys/devices/platform/$it/utilization") }
            sources.add("$QCOM_GPU/gpu_busy")
            maliDevfreq().forEach { sources.add("$it/load") }
            return first(sources.distinct(), ::firstNumberIn) { it.coerceIn(0, 100) }
        }

        /**
         * A Mali's clock in MHz: GED's current_freqency (sic; "index kHz", so its last number),
         * GED's gpu_cur_freq, a GPU devfreq node's cur_freq, then MediaTek's gpufreq dump.
         */
        fun maliClock(): Source? =
            first(listOf("$GED/current_freqency"), ::lastNumberIn) { mhz(it)?.takeIf { v -> v > 0 } }
                ?: first(listOf("/sys/module/ged/parameters/gpu_cur_freq") + maliDevfreq().map { "$it/cur_freq" } + "$QCOM_GPU/gpu_clock",
                    ::firstNumberIn) { mhz(it) }
                ?: first(listOf("/proc/gpufreqv2/gpufreq_status", "/proc/gpufreq/gpufreq_var_dump"), ::gpufreqIn) { mhz(it) }

        /** A Mali's temperature in m°C: the thermal zones named for the GPU (gpu1, gpu_thermal, mali...), by name. */
        fun maliTemp(): Source? {
            val zones = java.util.TreeMap<String, String>()
            list("/sys/class/thermal").filter { it.startsWith("thermal_zone") }.forEach { zone ->
                val type = runCatching { File("/sys/class/thermal/$zone/type").readText().trim().lowercase() }.getOrNull()
                if (type != null && (type.startsWith("gpu") || type.contains("mali"))) zones.putIfAbsent(type, "/sys/class/thermal/$zone/temp")
            }
            return first(zones.values.toList(), ::firstNumberIn) { milliCelsius(it) }
        }

        /** The Mali's devfreq nodes, where the policy lets the app list them. */
        private fun maliDevfreq(): List<String> {
            val nodes = list("$MALI_SYSFS/devfreq").map { "$MALI_SYSFS/devfreq/$it" }.toMutableList()
            list("/sys/class/devfreq").filter { n ->
                (n.contains("mali") || n.contains("gpu")) && listOf("bus", "bw", "memlat").none { n.contains(it) }
            }.forEach { nodes.add("/sys/class/devfreq/$it") }
            return nodes
        }

        private fun list(dir: String): List<String> = runCatching { File(dir).list()?.sorted() }.getOrNull().orEmpty()

        /** The first of [paths] that reads (read, not exists(): SELinux decides) as a value. */
        private fun first(paths: List<String>, parse: (String) -> Long?, unit: (Long) -> Long?): Source? {
            for (path in paths) {
                val read = { runCatching { parse(File(path).readText()) }.getOrNull()?.let(unit) }
                if (read() != null) return Source(path, read)
            }
            return null
        }

        private fun firstNumberIn(text: String): Long? = Regex("-?\\d+").find(text)?.value?.toLong()

        private fun lastNumberIn(text: String): Long? = Regex("\\d+").findAll(text).lastOrNull()?.value?.toLong()

        /** The first "Freq: 880000"-like figure in MediaTek's gpufreq dump. */
        private fun gpufreqIn(text: String): Long? =
            Regex("(?i)freq[^0-9\\n]{0,16}(\\d{3,})").find(text)?.groupValues?.get(1)?.toLong()

        /** The first integer in the file ("550", "37 %", "550000000"), or null. */
        private fun firstNumber(path: String): Long? =
            runCatching { Regex("-?\\d+").find(File(path).readText())?.value?.toLong() }.getOrNull()

        /** Hz, kHz or MHz to MHz, as the files differ; a stopped clock reads 0. */
        private fun mhz(raw: Long): Long? = when {
            raw < 0 -> null
            raw > 10_000_000 -> raw / 1_000_000
            raw > 10_000 -> raw / 1_000
            else -> raw
        }

        /** °C or m°C to MangoHud's m°C; nothing a GPU reads at is below 1 °C or above 1000. */
        private fun milliCelsius(raw: Long): Long? = when {
            raw <= 0 -> null
            raw < 1000 -> raw * 1000
            else -> raw
        }
    }
}
