package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.download.TemperatureKind
import com.local.multiplatformdownloader.core.download.TemperatureLevel
import com.local.multiplatformdownloader.core.download.isCpuTemperatureType
import com.local.multiplatformdownloader.core.download.normalizeTemperature
import com.local.multiplatformdownloader.core.download.temperatureLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHealthSamplerTest {
    @Test
    fun `thermal node values normalize to celsius`() {
        assertEquals(48f, normalizeTemperature(48_000.0))
        assertEquals(48f, normalizeTemperature(48.0))
        assertNull(normalizeTemperature(Double.NaN))
    }

    @Test
    fun `cpu thermal types exclude unrelated sensors`() {
        assertTrue(isCpuTemperatureType("cpu-0-4-1"))
        assertTrue(isCpuTemperatureType("cpuss-0-0"))
        assertEquals(false, isCpuTemperatureType("battery"))
    }

    @Test
    fun `cpu and battery temperatures use separate thresholds`() {
        assertEquals(TemperatureLevel.NORMAL, temperatureLevel(50f, TemperatureKind.CPU))
        assertEquals(TemperatureLevel.WARM, temperatureLevel(65f, TemperatureKind.CPU))
        assertEquals(TemperatureLevel.HOT, temperatureLevel(43f, TemperatureKind.BATTERY))
        assertEquals(TemperatureLevel.CRITICAL, temperatureLevel(47f, TemperatureKind.BATTERY))
    }
}
