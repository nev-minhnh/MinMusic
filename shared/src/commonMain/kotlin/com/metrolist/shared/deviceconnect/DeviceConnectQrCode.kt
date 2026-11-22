package com.metrolist.shared.deviceconnect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs

/**
 * Draws [text] as a QR code (byte mode, error correction level M), black on white with a quiet zone, for the
 * phone's camera. Nothing is drawn when the text does not fit a version 10 symbol.
 */
@Composable
fun DeviceConnectQrCode(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(text) { encodeDeviceConnectQr(text) } ?: return
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val quiet = 4
        val cells = matrix.size + quiet * 2
        val cell = size.minDimension / cells
        drawRect(Color.White)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[y][x]) {
                    // Slight overlap hides hairline seams between neighbouring modules.
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset((x + quiet) * cell, (y + quiet) * cell),
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

/** QR Code Model 2 encoder (ISO/IEC 18004), byte mode, level M, versions 1 to 10. Rows of dark modules. */
internal fun encodeDeviceConnectQr(text: String): List<BooleanArray>? {
    val data = text.encodeToByteArray()
    val version =
        (1..QR_MAX_VERSION).firstOrNull { version ->
            val countBits = if (version < 10) 8 else 16
            4 + countBits + data.size * 8 <= qrDataCodewords(version) * 8
        } ?: return null
    val codewords = qrAddErrorCorrection(qrDataBytes(data, version), version)
    val symbol = QrSymbol(version)
    symbol.drawFunctionPatterns()
    symbol.drawCodewords(codewords)
    val mask = (0 until 8).minBy { candidate -> symbol.withMask(candidate).penalty() }
    return symbol.withMask(mask).rows()
}

private const val QR_MAX_VERSION = 10

// Level M, indexed by version.
private val QR_ECC_CODEWORDS_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
private val QR_ERROR_CORRECTION_BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

private fun qrRawDataModules(version: Int): Int {
    var result = (16 * version + 128) * version + 64
    if (version >= 2) {
        val alignments = version / 7 + 2
        result -= (25 * alignments - 10) * alignments - 55
        if (version >= 7) result -= 36
    }
    return result
}

private fun qrDataCodewords(version: Int): Int =
    qrRawDataModules(version) / 8 - QR_ECC_CODEWORDS_PER_BLOCK[version] * QR_ERROR_CORRECTION_BLOCKS[version]

private fun qrDataBytes(
    data: ByteArray,
    version: Int,
): ByteArray {
    val capacityBits = qrDataCodewords(version) * 8
    val bits = ArrayList<Boolean>(capacityBits)

    fun append(
        value: Int,
        length: Int,
    ) {
        for (i in length - 1 downTo 0) bits += (value ushr i) and 1 != 0
    }
    append(0b0100, 4)
    append(data.size, if (version < 10) 8 else 16)
    data.forEach { append(it.toInt() and 0xFF, 8) }
    append(0, minOf(4, capacityBits - bits.size))
    append(0, (8 - bits.size % 8) % 8)
    var pad = 0xEC
    while (bits.size < capacityBits) {
        append(pad, 8)
        pad = pad xor 0xEC xor 0x11
    }
    return ByteArray(bits.size / 8) { index ->
        var byte = 0
        for (bit in 0 until 8) byte = (byte shl 1) or (if (bits[index * 8 + bit]) 1 else 0)
        byte.toByte()
    }
}

private fun qrAddErrorCorrection(
    data: ByteArray,
    version: Int,
): ByteArray {
    val blockCount = QR_ERROR_CORRECTION_BLOCKS[version]
    val eccLength = QR_ECC_CODEWORDS_PER_BLOCK[version]
    val rawCodewords = qrRawDataModules(version) / 8
    val shortBlocks = blockCount - rawCodewords % blockCount
    val shortBlockLength = rawCodewords / blockCount
    val divisor = qrReedSolomonDivisor(eccLength)
    val blocks = ArrayList<ByteArray>(blockCount)
    var offset = 0
    for (i in 0 until blockCount) {
        val dataLength = shortBlockLength - eccLength + (if (i < shortBlocks) 0 else 1)
        val blockData = data.copyOfRange(offset, offset + dataLength)
        offset += dataLength
        val ecc = qrReedSolomonRemainder(blockData, divisor)
        // Short blocks get a placeholder byte so every block has the same length while interleaving.
        val padded = if (i < shortBlocks) blockData + byteArrayOf(0) else blockData
        blocks += padded + ecc
    }
    val result = ArrayList<Byte>(rawCodewords)
    for (i in blocks[0].indices) {
        for (j in blocks.indices) {
            if (i != shortBlockLength - eccLength || j >= shortBlocks) result += blocks[j][i]
        }
    }
    return result.toByteArray()
}

private fun qrReedSolomonDivisor(degree: Int): ByteArray {
    val result = ByteArray(degree)
    result[degree - 1] = 1
    var root = 1
    repeat(degree) {
        for (j in result.indices) {
            result[j] = qrMultiply(result[j].toInt() and 0xFF, root).toByte()
            if (j + 1 < result.size) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
        }
        root = qrMultiply(root, 0x02)
    }
    return result
}

private fun qrReedSolomonRemainder(
    data: ByteArray,
    divisor: ByteArray,
): ByteArray {
    val result = ByteArray(divisor.size)
    for (byte in data) {
        val factor = (byte.toInt() xor result[0].toInt()) and 0xFF
        for (i in 0 until result.size - 1) result[i] = result[i + 1]
        result[result.size - 1] = 0
        for (i in result.indices) {
            result[i] = (result[i].toInt() xor qrMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
        }
    }
    return result
}

private fun qrMultiply(
    x: Int,
    y: Int,
): Int {
    var z = 0
    for (i in 7 downTo 0) {
        z = (z shl 1) xor ((z ushr 7) * 0x11D)
        z = z xor (((y ushr i) and 1) * x)
    }
    return z and 0xFF
}

private class QrSymbol(
    private val version: Int,
) {
    val size = version * 4 + 17
    private val modules = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    private fun setFunction(
        x: Int,
        y: Int,
        dark: Boolean,
    ) {
        modules[y][x] = dark
        isFunction[y][x] = true
    }

    fun drawFunctionPatterns() {
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        val positions = alignmentPositions()
        val count = positions.size
        for (i in 0 until count) {
            for (j in 0 until count) {
                val corner = (i == 0 && j == 0) || (i == 0 && j == count - 1) || (i == count - 1 && j == 0)
                if (!corner) drawAlignment(positions[i], positions[j])
            }
        }
        drawFormatBits(0)
        drawVersion()
    }

    private fun drawFinder(
        x: Int,
        y: Int,
    ) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val distance = maxOf(abs(dx), abs(dy))
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) setFunction(xx, yy, distance != 2 && distance != 4)
            }
        }
    }

    private fun drawAlignment(
        x: Int,
        y: Int,
    ) {
        for (dy in -2..2) {
            for (dx in -2..2) setFunction(x + dx, y + dy, maxOf(abs(dx), abs(dy)) != 1)
        }
    }

    private fun alignmentPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
        val result = IntArray(count)
        result[0] = 6
        var position = size - 7
        for (i in count - 1 downTo 1) {
            result[i] = position
            position -= step
        }
        return result
    }

    fun drawFormatBits(mask: Int) {
        // Level M has format bits 00.
        val data = mask
        var remainder = data
        repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
        val bits = ((data shl 10) or remainder) xor 0x5412

        fun bit(index: Int) = (bits ushr index) and 1 != 0
        for (i in 0..5) setFunction(8, i, bit(i))
        setFunction(8, 7, bit(6))
        setFunction(8, 8, bit(7))
        setFunction(7, 8, bit(8))
        for (i in 9 until 15) setFunction(14 - i, 8, bit(i))
        for (i in 0 until 8) setFunction(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) setFunction(8, size - 15 + i, bit(i))
        setFunction(8, size - 8, true)
    }

    private fun drawVersion() {
        if (version < 7) return
        var remainder = version
        repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
        val bits = (version shl 12) or remainder
        for (i in 0 until 18) {
            val dark = (bits ushr i) and 1 != 0
            val a = size - 11 + i % 3
            val b = i / 3
            setFunction(a, b, dark)
            setFunction(b, a, dark)
        }
    }

    fun drawCodewords(data: ByteArray) {
        var index = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vertical in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vertical else vertical
                    if (!isFunction[y][x] && index < data.size * 8) {
                        modules[y][x] = ((data[index ushr 3].toInt() ushr (7 - (index and 7))) and 1) != 0
                        index++
                    }
                }
            }
            right -= 2
        }
    }

    fun withMask(mask: Int): QrSymbol {
        val copy = QrSymbol(version)
        for (y in 0 until size) {
            for (x in 0 until size) {
                copy.isFunction[y][x] = isFunction[y][x]
                val invert =
                    when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                copy.modules[y][x] = modules[y][x] xor (invert && !isFunction[y][x])
            }
        }
        copy.drawFormatBits(mask)
        return copy
    }

    /** Runs of five or more, 2x2 blocks and dark/light imbalance; enough to avoid masks that scan poorly. */
    fun penalty(): Int {
        var result = 0
        for (y in 0 until size) {
            var run = 1
            for (x in 1 until size) {
                if (modules[y][x] == modules[y][x - 1]) {
                    run++
                    if (run == 5) result += 3 else if (run > 5) result++
                } else {
                    run = 1
                }
            }
        }
        for (x in 0 until size) {
            var run = 1
            for (y in 1 until size) {
                if (modules[y][x] == modules[y - 1][x]) {
                    run++
                    if (run == 5) result += 3 else if (run > 5) result++
                } else {
                    run = 1
                }
            }
        }
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val color = modules[y][x]
                if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) result += 3
            }
        }
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
        return result + k.coerceAtLeast(0) * 10
    }

    fun rows(): List<BooleanArray> = modules.map { it.copyOf() }
}
