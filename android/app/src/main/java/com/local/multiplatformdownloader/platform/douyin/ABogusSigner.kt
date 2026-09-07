package com.local.multiplatformdownloader.platform.douyin

import com.local.multiplatformdownloader.core.network.values

import java.nio.charset.StandardCharsets
import kotlin.math.floor
import kotlin.random.Random

/**
 * Kotlin port of the a_bogus implementation previously vendored from f2.
 * Original project: https://github.com/Johnserf-Seed/f2 (Apache-2.0).
 */
internal class ABogusSigner(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val randomSource: ABogusRandom = KotlinABogusRandom,
) {
    fun sign(
        params: String,
        userAgent: String,
        fingerprint: String = BrowserFingerprint.generate(randomSource),
    ): String {
        val crypto = ABogusCrypto()
        val values = mutableMapOf<Int, Int>(
            8 to 3,
            18 to 44,
            66 to 0,
            69 to 0,
            70 to 0,
            71 to 0,
        )
        val start = clockMillis()
        val paramsHash = Sm3.digest(Sm3.digest((params + SALT).toByteArray()))
        val bodyHash = Sm3.digest(Sm3.digest(SALT.toByteArray()))
        val encryptedUa = crypto.rc4Encrypt(UA_KEY, userAgent)
        val encodedUa = crypto.base64Encode(encryptedUa, CHARACTER_2)
        val uaHash = Sm3.digest(encodedUa.toByteArray(StandardCharsets.UTF_8))
        val end = clockMillis()

        values.putTimestamp(20, 24, start)
        values[26] = 0
        values[27] = 0
        values[28] = 0
        values[29] = 0
        values[30] = 0
        values[31] = 1
        values[32] = 0
        values[33] = 0
        values[34] = 0
        values[35] = 0
        values[36] = 0
        values[37] = 14
        values[38] = paramsHash[21].unsigned
        values[39] = paramsHash[22].unsigned
        values[40] = bodyHash[21].unsigned
        values[41] = bodyHash[22].unsigned
        values[42] = uaHash[23].unsigned
        values[43] = uaHash[24].unsigned
        values.putTimestamp(44, 49, end)
        values[48] = values.getValue(8)
        values[51] = 0
        values[52] = 0
        values[53] = 0
        values[54] = 0
        values[55] = 0
        values[56] = AID
        values[57] = AID and 0xff
        values[58] = (AID ushr 8) and 0xff
        values[59] = (AID ushr 16) and 0xff
        values[60] = (AID ushr 24) and 0xff
        values[64] = fingerprint.length
        values[65] = fingerprint.length

        val payload = SORT_INDEX.mapTo(mutableListOf()) { values[it] ?: 0 }
        payload += fingerprint.map(Char::code)
        payload += SORT_INDEX_2.map { values[it] ?: 0 }.reduce(Int::xor)

        val randomPrefix = buildList {
            repeat(3) {
                val value = floor(randomSource.nextDouble() * 10_000).toInt()
                add(((value and 0xff) and 0xaa) or 1)
                add(((value and 0xff) and 0x55) or 2)
                add(((value ushr 8) and 0xaa) or 5)
                add(((value ushr 8) and 0x55) or 40)
            }
        }
        return crypto.abogusEncode(randomPrefix + crypto.transformBytes(payload), CHARACTER)
    }

    private fun MutableMap<Int, Int>.putTimestamp(
        byteStartIndex: Int,
        highStartIndex: Int,
        value: Long,
    ) {
        this[byteStartIndex] = ((value ushr 24) and 0xff).toInt()
        this[byteStartIndex + 1] = ((value ushr 16) and 0xff).toInt()
        this[byteStartIndex + 2] = ((value ushr 8) and 0xff).toInt()
        this[byteStartIndex + 3] = (value and 0xff).toInt()
        this[highStartIndex] = (value / 256L / 256L / 256L / 256L).toInt()
        this[highStartIndex + 1] = (value / 256L / 256L / 256L / 256L / 256L).toInt()
    }

    private val Byte.unsigned: Int get() = toInt() and 0xff

    companion object {
        private const val AID = 6383
        private const val SALT = "cus"
        private val UA_KEY = byteArrayOf(0, 1, 14)
        private const val CHARACTER =
            "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"
        private const val CHARACTER_2 =
            "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"
        private val SORT_INDEX = intArrayOf(
            18, 20, 52, 26, 30, 34, 58, 38, 40, 53, 42, 21, 27, 54, 55, 31, 35,
            57, 39, 41, 43, 22, 28, 32, 60, 36, 23, 29, 33, 37, 44, 45, 59, 46,
            47, 48, 49, 50, 24, 25, 65, 66, 70, 71,
        )
        private val SORT_INDEX_2 = intArrayOf(
            18, 20, 26, 30, 34, 38, 40, 42, 21, 27, 31, 35, 39, 41, 43, 22, 28,
            32, 36, 23, 29, 33, 37, 44, 45, 46, 47, 48, 49, 50, 24, 25, 52, 53,
            54, 55, 57, 58, 59, 60, 65, 66, 70, 71,
        )
    }
}

internal fun interface ABogusRandom {
    fun nextDouble(): Double

    fun nextInt(from: Int, through: Int): Int =
        from + floor(nextDouble() * (through - from + 1)).toInt().coerceAtMost(through - from)
}

private object KotlinABogusRandom : ABogusRandom {
    override fun nextDouble(): Double = Random.Default.nextDouble()

    override fun nextInt(from: Int, through: Int): Int = Random.Default.nextInt(from, through + 1)
}

private object BrowserFingerprint {
    fun generate(random: ABogusRandom): String {
        val innerWidth = random.nextInt(1024, 1920)
        val innerHeight = random.nextInt(768, 1080)
        val outerWidth = innerWidth + random.nextInt(24, 32)
        val outerHeight = innerHeight + random.nextInt(75, 90)
        val screenY = if (random.nextInt(0, 1) == 0) 0 else 30
        val sizeWidth = random.nextInt(1024, 1920)
        val sizeHeight = random.nextInt(768, 1080)
        val availableWidth = random.nextInt(1280, 1920)
        val availableHeight = random.nextInt(800, 1080)
        return listOf(
            innerWidth, innerHeight, outerWidth, outerHeight, 0, screenY, 0, 0,
            sizeWidth, sizeHeight, availableWidth, availableHeight, innerWidth, innerHeight,
            24, 24, "Win32",
        ).joinToString("|")
    }
}

private class ABogusCrypto {
    private val permutation = intArrayOf(
        121, 243, 55, 234, 103, 36, 47, 228, 30, 231, 106, 6, 115, 95, 78, 101,
        250, 207, 198, 50, 139, 227, 220, 105, 97, 143, 34, 28, 194, 215, 18, 100,
        159, 160, 43, 8, 169, 217, 180, 120, 247, 45, 90, 11, 27, 197, 46, 3, 84,
        72, 5, 68, 62, 56, 221, 75, 144, 79, 73, 161, 178, 81, 64, 187, 134, 117,
        186, 118, 16, 241, 130, 71, 89, 147, 122, 129, 65, 40, 88, 150, 110, 219,
        199, 255, 181, 254, 48, 4, 195, 248, 208, 32, 116, 167, 69, 201, 17, 124,
        125, 104, 96, 83, 80, 127, 236, 108, 154, 126, 204, 15, 20, 135, 112, 158,
        13, 1, 188, 164, 210, 237, 222, 98, 212, 77, 253, 42, 170, 202, 26, 22, 29,
        182, 251, 10, 173, 152, 58, 138, 54, 141, 185, 33, 157, 31, 252, 132, 233,
        235, 102, 196, 191, 223, 240, 148, 39, 123, 92, 82, 128, 109, 57, 24, 38,
        113, 209, 245, 2, 119, 153, 229, 189, 214, 230, 174, 232, 63, 52, 205, 86,
        140, 66, 175, 111, 171, 246, 133, 238, 193, 99, 60, 74, 91, 225, 51, 76,
        37, 145, 211, 166, 151, 213, 206, 0, 200, 244, 176, 218, 44, 184, 172, 49,
        216, 93, 168, 53, 21, 183, 41, 67, 85, 224, 155, 226, 242, 87, 177, 146,
        70, 190, 12, 162, 19, 137, 114, 25, 165, 163, 192, 23, 59, 9, 94, 179, 107,
        35, 7, 142, 131, 239, 203, 149, 136, 61, 249, 14, 156,
    )

    fun transformBytes(input: List<Int>): List<Int> {
        val output = ArrayList<Int>(input.size)
        var indexB = permutation[1]
        var initialValue = 0
        var valueE = 0
        input.forEachIndexed { index, value ->
            var sumInitial: Int
            if (index == 0) {
                initialValue = permutation[indexB]
                sumInitial = indexB + initialValue
                permutation[1] = initialValue
                permutation[indexB] = indexB
            } else {
                sumInitial = initialValue + valueE
            }
            sumInitial %= permutation.size
            output += value xor permutation[sumInitial]

            val swapIndex = (index + 2) % permutation.size
            valueE = permutation[swapIndex]
            sumInitial = (indexB + valueE) % permutation.size
            initialValue = permutation[sumInitial]
            permutation[sumInitial] = permutation[swapIndex]
            permutation[swapIndex] = initialValue
            indexB = sumInitial
        }
        return output
    }

    fun rc4Encrypt(key: ByteArray, plaintext: String): IntArray {
        val state = IntArray(256) { it }
        var j = 0
        for (i in state.indices) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xff)) % 256
            state.swap(i, j)
        }
        var i = 0
        j = 0
        return IntArray(plaintext.length) { index ->
            i = (i + 1) % 256
            j = (j + state[i]) % 256
            state.swap(i, j)
            plaintext[index].code xor state[(state[i] + state[j]) % 256]
        }
    }

    fun base64Encode(input: IntArray, alphabet: String): String {
        val output = StringBuilder((input.size + 2) / 3 * 4)
        input.asList().chunked(3).forEach { block ->
            val value = (block[0] shl 16) or
                ((block.getOrElse(1) { 0 }) shl 8) or block.getOrElse(2) { 0 }
            output.append(alphabet[(value and 0xfc0000) ushr 18])
            output.append(alphabet[(value and 0x03f000) ushr 12])
            if (block.size > 1) output.append(alphabet[(value and 0x0fc0) ushr 6])
            if (block.size > 2) output.append(alphabet[value and 0x3f])
        }
        val paddingBits = (6 - input.size * 8 % 6) % 6
        repeat(paddingBits / 2) { output.append('=') }
        return output.toString()
    }

    fun abogusEncode(input: List<Int>, alphabet: String): String {
        val output = StringBuilder((input.size + 2) / 3 * 4)
        input.chunked(3).forEach { block ->
            val value = (block[0] shl 16) or
                ((block.getOrElse(1) { 0 }) shl 8) or block.getOrElse(2) { 0 }
            output.append(alphabet[(value and 0xfc0000) ushr 18])
            output.append(alphabet[(value and 0x03f000) ushr 12])
            if (block.size > 1) output.append(alphabet[(value and 0x0fc0) ushr 6])
            if (block.size > 2) output.append(alphabet[value and 0x3f])
        }
        repeat((4 - output.length % 4) % 4) { output.append('=') }
        return output.toString()
    }

    private fun IntArray.swap(left: Int, right: Int) {
        val value = this[left]
        this[left] = this[right]
        this[right] = value
    }
}

internal object Sm3 {
    private val initial = intArrayOf(
        0x7380166f,
        0x4914b2b9,
        0x172442d7,
        0xda8a0600.toInt(),
        0xa96f30bc.toInt(),
        0x163138aa,
        0xe38dee4d.toInt(),
        0xb0fb0e4e.toInt(),
    )

    fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val padding = (56 - (input.size + 1) % 64 + 64) % 64
        val message = ByteArray(input.size + 1 + padding + 8)
        input.copyInto(message)
        message[input.size] = 0x80.toByte()
        for (index in 0 until 8) {
            message[message.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
        }

        val state = initial.copyOf()
        val words = IntArray(68)
        val expanded = IntArray(64)
        message.asList().chunked(64).forEach { block ->
            for (index in 0 until 16) {
                val offset = index * 4
                words[index] = ((block[offset].toInt() and 0xff) shl 24) or
                    ((block[offset + 1].toInt() and 0xff) shl 16) or
                    ((block[offset + 2].toInt() and 0xff) shl 8) or
                    (block[offset + 3].toInt() and 0xff)
            }
            for (index in 16 until 68) {
                words[index] = p1(
                    words[index - 16] xor words[index - 9] xor
                        Integer.rotateLeft(words[index - 3], 15),
                ) xor Integer.rotateLeft(words[index - 13], 7) xor words[index - 6]
            }
            for (index in 0 until 64) expanded[index] = words[index] xor words[index + 4]

            var a = state[0]
            var b = state[1]
            var c = state[2]
            var d = state[3]
            var e = state[4]
            var f = state[5]
            var g = state[6]
            var h = state[7]
            for (index in 0 until 64) {
                val constant = if (index < 16) 0x79cc4519 else 0x7a879d8a
                val rotatedA = Integer.rotateLeft(a, 12)
                val ss1 = Integer.rotateLeft(
                    rotatedA + e + Integer.rotateLeft(constant, index),
                    7,
                )
                val ss2 = ss1 xor rotatedA
                val tt1 = ff(a, b, c, index) + d + ss2 + expanded[index]
                val tt2 = gg(e, f, g, index) + h + ss1 + words[index]
                d = c
                c = Integer.rotateLeft(b, 9)
                b = a
                a = tt1
                h = g
                g = Integer.rotateLeft(f, 19)
                f = e
                e = p0(tt2)
            }
            state[0] = state[0] xor a
            state[1] = state[1] xor b
            state[2] = state[2] xor c
            state[3] = state[3] xor d
            state[4] = state[4] xor e
            state[5] = state[5] xor f
            state[6] = state[6] xor g
            state[7] = state[7] xor h
        }
        return ByteArray(32) { index ->
            (state[index / 4] ushr (24 - index % 4 * 8)).toByte()
        }
    }

    private fun ff(x: Int, y: Int, z: Int, round: Int): Int =
        if (round < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

    private fun gg(x: Int, y: Int, z: Int, round: Int): Int =
        if (round < 16) x xor y xor z else (x and y) or (x.inv() and z)

    private fun p0(value: Int): Int =
        value xor Integer.rotateLeft(value, 9) xor Integer.rotateLeft(value, 17)

    private fun p1(value: Int): Int =
        value xor Integer.rotateLeft(value, 15) xor Integer.rotateLeft(value, 23)
}
