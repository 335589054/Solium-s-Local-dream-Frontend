package io.github.localdream.frontend.data

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.lang.reflect.Method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** One temperature reading taken from a kernel thermal zone. */
data class ThermalZone(
    val name: String,
    val tempC: Float,
)

/** A point-in-time snapshot of the device's thermal state. */
data class DeviceStats(
    val cpuTempC: Float? = null,
    // Temperature shown for the NPU row. Many SoCs expose no dedicated NPU
    // zone, in which case this mirrors the CPU temperature of the same die.
    val npuTempC: Float? = null,
    val npuIsFallback: Boolean = false,
    val gpuTempC: Float? = null,
    val hottest: ThermalZone? = null,
    val elevated: Boolean = false,
    val source: String = SOURCE_APP,
    val note: String? = null,
) {
    companion object {
        const val SOURCE_APP = "应用直读"
        const val SOURCE_SHIZUKU = "Shizuku"
    }
}

/**
 * Reads kernel temperature zones. Every value goes through [cat] so one switch
 * decides whether reads use the app's own unprivileged uid or a shell uid
 * obtained through Shizuku (which can reach nodes denied to normal apps).
 */
object ShellAccess {
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun isShizukuInstalled(): Boolean {
        val context = appContext ?: return false
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun isShizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        if (!isShizukuAvailable()) return false
        if (Shizuku.isPreV11()) false else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        runCatching {
            if (Shizuku.isPreV11()) return
            Shizuku.requestPermission(requestCode)
        }
    }

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    /** Reads a file path, preferring an elevated shell when one is available. */
    fun cat(path: String): String? {
        if (isPermissionGranted()) {
            shizukuCat(path)?.let { return it }
        }
        return runCatching {
            val file = File(path)
            if (file.canRead()) file.readText().trim() else null
        }.getOrNull()
    }

    private fun shizukuCat(path: String): String? = runCatching {
        val method = newProcessMethod ?: return null
        val process = method.invoke(null, arrayOf("sh", "-c", "cat $path 2>/dev/null"), null, null) as? Process
            ?: return null
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.trim().ifEmpty { null }
    }.getOrNull()

    /**
     * Shizuku's process spawner is a hidden member, so it has to be reached by
     * reflection; when that fails the caller silently falls back to reading the
     * file as the app's own uid.
     */
    private val newProcessMethod: Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
}

/**
 * Polls the kernel thermal zones on an interval and exposes them as a
 * [StateFlow] for the monitor bar and the settings preview.
 */
class DeviceMonitor(
    private val scope: CoroutineScope,
    private val log: LogRepository,
    private val intervalMs: Long = 2_000L,
) {
    private val _stats = MutableStateFlow(DeviceStats())
    val stats: StateFlow<DeviceStats> = _stats.asStateFlow()

    private var pollJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                _stats.value = withContext(Dispatchers.IO) { sample() }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshOnce() {
        scope.launch {
            _stats.value = withContext(Dispatchers.IO) { sample() }
        }
    }

    private fun sample(): DeviceStats {
        val zones = readThermalZones()
        val cpuTemp = pickZone(zones, CPU_KEYWORDS)
        val npuZone = pickZone(zones, NPU_KEYWORDS)
        val gpuTemp = pickZone(zones, GPU_KEYWORDS)
        val hottest = zones.maxByOrNull { it.tempC }

        return DeviceStats(
            cpuTempC = cpuTemp,
            npuTempC = npuZone ?: cpuTemp,
            npuIsFallback = npuZone == null && cpuTemp != null,
            gpuTempC = gpuTemp,
            hottest = hottest,
            elevated = hottest != null && hottest.tempC >= ELEVATED_TEMP_C,
            source = if (ShellAccess.isPermissionGranted()) DeviceStats.SOURCE_SHIZUKU else DeviceStats.SOURCE_APP,
            note = if (zones.isEmpty()) "未能读取热区，设备可能限制了 /sys/class/thermal 访问" else null,
        )
    }

    private fun readThermalZones(): List<ThermalZone> {
        val names = runCatching {
            File(THERMAL_ROOT).list()?.filter { it.startsWith("thermal_zone") }
        }.getOrNull() ?: return emptyList()

        val zones = mutableListOf<ThermalZone>()
        names.forEach { entry ->
            val name = ShellAccess.cat("$THERMAL_ROOT/$entry/type")?.trim() ?: return@forEach
            if (!isUsableZone(name)) return@forEach

            val raw = ShellAccess.cat("$THERMAL_ROOT/$entry/temp") ?: return@forEach
            val value = raw.lineSequence().firstOrNull()?.trim()?.toFloatOrNull() ?: return@forEach
            // Drivers report either millidegrees Celsius or plain Celsius; a
            // reading above 1000 can only be the former (real dies never sit
            // above 1000 degrees).
            val celsius = if (value > 1000f) value / 1000f else value
            if (celsius > MIN_TEMP_C && celsius < MAX_TEMP_C) {
                zones.add(ThermalZone(name, celsius))
            }
        }
        return zones
    }

    /**
     * Rejects zones that are not live temperature readings. In particular
     * `cpu-hw-trip-0` reports the fixed hardware trip threshold (95 °C on many
     * Qualcomm parts), and `*-bcl-lvl*` / `vbat` / `sdr*` report flags,
     * voltages or unpopulated sensors that would otherwise be mistaken for
     * very hot silicon.
     */
    private fun isUsableZone(name: String): Boolean {
        val lower = name.lowercase()
        return EXCLUDED_ZONE_MARKERS.none { lower.contains(it) }
    }

    private fun pickZone(zones: List<ThermalZone>, keywords: List<String>): Float? =
        zones.filter { zone ->
            val lower = zone.name.lowercase()
            keywords.any { lower.contains(it) }
        }.maxByOrNull { it.tempC }?.tempC

    private companion object {
        const val THERMAL_ROOT = "/sys/class/thermal"
        /** Sustained load normally keeps these parts well under 90 °C. */
        const val ELEVATED_TEMP_C = 90f

        // Below freezing means an unpopulated sensor (some report -273 °C).
        // Silicon junction temperatures top out around 105-110 °C, so anything
        // past 120 °C is a bogus reading rather than a hot chip.
        const val MIN_TEMP_C = -20f
        const val MAX_TEMP_C = 120f

        val EXCLUDED_ZONE_MARKERS = listOf(
            "trip", // fixed hardware trip thresholds
            "lvl", // bcl-lvl / ibat-lvl level flags
            "vbat", // battery voltage
            "ibat", // battery current
            "sdr", // unpopulated modem sensors (-273 °C)
            "mmw", // unpopulated modem sensors (-273 °C)
            "batt", // battery pack, not the SoC
        )

        // `nsp` covers Qualcomm's Hexagon NSP (the NPU) zones on this device.
        val NPU_KEYWORDS = listOf("nsp", "npu", "apu", "nna")
        // `cpu-0-` / `cpullc` match the live CPU rails without also matching
        // `cpu-hw-trip-*`, which is filtered out by name anyway.
        val CPU_KEYWORDS = listOf("cpu_therm", "cpullc", "cpu-0-", "cpu-1-", "soc", "tsens", "cluster")
        val GPU_KEYWORDS = listOf("gpuss", "gpu", "kgsl", "mali", "adreno")
    }
}
