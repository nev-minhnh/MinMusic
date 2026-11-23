package com.metrolist.core.api.logs

import java.io.File
import java.io.RandomAccessFile

/**
 * The last N lines of a (possibly large) UTF-8 log file. Reads backwards in blocks, so only the tail is touched. Cutting
 * happens at a newline byte, which never occurs inside a multi-byte UTF-8 sequence, so a character that straddles a
 * block boundary is decoded whole. CRLF is returned as LF; a final line terminator is kept.
 */
object TailReader {
    const val BLOCK_SIZE = 64 * 1024

    fun tail(
        file: File,
        maxLines: Int,
        blockSize: Int = BLOCK_SIZE,
    ): String {
        require(maxLines > 0) { "maxLines must be positive" }
        require(blockSize > 0) { "blockSize must be positive" }
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            if (size == 0L) return ""
            val blocks = ArrayDeque<ByteArray>()
            var position = size
            var newlines = 0
            // maxLines + 1 newlines always cover maxLines lines, whether or not the file ends with a terminator.
            while (position > 0 && newlines <= maxLines) {
                val length = minOf(blockSize.toLong(), position).toInt()
                position -= length
                val block = ByteArray(length)
                input.seek(position)
                input.readFully(block)
                newlines += block.count { it == NEWLINE }
                blocks.addFirst(block)
            }
            val data = ByteArray(blocks.sumOf { it.size })
            var offset = 0
            for (block in blocks) {
                block.copyInto(data, offset)
                offset += block.size
            }
            val start = startOfTail(data, maxLines)
            return String(data, start, data.size - start, Charsets.UTF_8).replace("\r\n", "\n")
        }
    }

    /** Index of the first byte of the last [maxLines] lines of [data] (0 when it holds fewer). */
    internal fun startOfTail(
        data: ByteArray,
        maxLines: Int,
    ): Int {
        var index = data.size - 1
        if (index >= 0 && data[index] == NEWLINE) index-- // the terminator of the last line is not a separator
        var seen = 0
        while (index >= 0) {
            if (data[index] == NEWLINE && ++seen == maxLines) return index + 1
            index--
        }
        return 0
    }

    private const val NEWLINE: Byte = '\n'.code.toByte()
}
