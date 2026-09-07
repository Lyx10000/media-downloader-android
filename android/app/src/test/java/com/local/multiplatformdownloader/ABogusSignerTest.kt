package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.platform.douyin.ABogusSigner
import com.local.multiplatformdownloader.platform.douyin.ABogusRandom
import com.local.multiplatformdownloader.platform.douyin.Sm3


import org.junit.Assert.assertEquals
import org.junit.Test

class ABogusSignerTest {
    @Test
    fun `sm3 matches the Python gmssl baseline`() {
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            Sm3.digest("abc".toByteArray()).toHex(),
        )
        assertEquals(
            "bc123c90c9b8e9a44d2075e9c202c4638c63f8f6355c30c5365ff25d613f8adc",
            Sm3.digest(Sm3.digest("abc".toByteArray())).toHex(),
        )
    }

    @Test
    fun `signature matches deterministic Python baseline`() {
        val randomValues = ArrayDeque(listOf(0.1234, 0.5678, 0.9012))
        val clockValues = ArrayDeque(listOf(1_700_000_000_123L, 1_700_000_000_124L))
        val signer = ABogusSigner(
            clockMillis = { clockValues.removeFirst() },
            randomSource = ABogusRandom { randomValues.removeFirst() },
        )

        val signature = signer.sign(
            params = "device_platform=webapp&aid=6383&channel=channel_pc_web&" +
                "aweme_id=7670091606150329338",
            userAgent = "Mozilla/5.0 Test UA",
            fingerprint = "1280|900|1304|980|0|0|0|0|1280|900|1920|1040|" +
                "1280|900|24|24|Win32",
        )

        assertEquals(
            "E7mhBdu2krjihxWT56KLfY3q65r3YBVI0SVkMD2fsx3NqL39HMTa9exoIBGvXFSj" +
                "wG/-IeYjy4hbYNQprQCj01wfHSko/2AMmDSkKl5Q5xSSs1XJtyUgJUkNmktISlc2" +
                "5k3-EKi8qXCaSY8kAnAJ5kIlO62-zo0/9WE=",
            signature,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
