package com.local.multiplatformdownloader.core.download

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

internal data class DeviceTemperatures(
    val cpuCelsius: Float? = null,
    val batteryCelsius: Float? = null,
)

internal enum class TemperatureKind { CPU, BATTERY }

internal enum class TemperatureLevel { NORMAL, WARM, HOT, CRITICAL, UNKNOWN }

/**
 * Reads best-effort device temperatures without privileged APIs. Android exposes battery
 * temperature through a sticky broadcast; CPU temperature is vendor-dependent and may not be
 * readable by ordinary apps, so callers must support a missing value.
 */
internal class DeviceHealthSampler(context: Context) {
    private val applicationContext = context.applicationContext
    private val cpuTemperatureFiles: List<File> by lazy(::findCpuTemperatureFiles)

    fun sampleTemperatures(): DeviceTemperatures = DeviceTemperatures(
        cpuCelsius = cpuTemperatureFiles.mapNotNull(::readTemperature).maxOrNull(),
        batteryCelsius = readBatteryTemperature(),
    )

    private fun findCpuTemperatureFiles(): List<File> {
        val typedZones = File("/sys/class/thermal").listFiles().orEmpty()
            .asSequence()
            .filter { it.name.startsWith("thermal_zone") }
            .filter { zone ->
                val type = runCatching { File(zone, "type").readText().trim() }.getOrDefault("")
                isCpuTemperatureType(type)
            }
            .map { File(it, "temp") }
            .filter(File::canRead)
            .toList()
        if (typedZones.isNotEmpty()) return typedZones

        return CPU_TEMPERATURE_FALLBACKS.map(::File).filter(File::canRead)
    }

    private fun readTemperature(file: File): Float? = runCatching {
        normalizeTemperature(file.bufferedReader().use { it.readLine().toDoubleOrNull() })
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun readBatteryTemperature(): Float? {
        val status = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val raw = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (raw == Int.MIN_VALUE) return null
        return (raw / 10f).takeIf { it in VALID_BATTERY_RANGE }
    }

    private companion object {
        val VALID_BATTERY_RANGE = -20f..100f
        val CPU_TEMPERATURE_FALLBACKS = listOf(
            "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp",
            "/sys/devices/system/cpu/cpu0/cpufreq/FakeShmoo_cpu_temp",
            "/sys/kernel/debug/tegra_thermal/temp_tj",
            "/sys/devices/platform/s5p-tmu/temperature",
            "/sys/htc/cpu_temp",
        )
    }
}

internal fun isCpuTemperatureType(type: String): Boolean {
    val normalized = type.trim().lowercase()
    return normalized.startsWith("cpu") || normalized.contains("cpuss")
}

internal fun normalizeTemperature(raw: Double?): Float? {
    if (raw == null || !raw.isFinite()) return null
    val celsius = if (raw in -50.0..250.0) raw else raw / 1000.0
    return celsius.toFloat().takeIf { it in -50f..250f }
}

internal fun temperatureLevel(value: Float?, kind: TemperatureKind): TemperatureLevel {
    if (value == null || !value.isFinite()) return TemperatureLevel.UNKNOWN
    return when (kind) {
        TemperatureKind.CPU -> when {
            value < 60f -> TemperatureLevel.NORMAL
            value < 75f -> TemperatureLevel.WARM
            value < 85f -> TemperatureLevel.HOT
            else -> TemperatureLevel.CRITICAL
        }
        TemperatureKind.BATTERY -> when {
            value < 38f -> TemperatureLevel.NORMAL
            value < 42f -> TemperatureLevel.WARM
            value < 46f -> TemperatureLevel.HOT
            else -> TemperatureLevel.CRITICAL
        }
    }
}
