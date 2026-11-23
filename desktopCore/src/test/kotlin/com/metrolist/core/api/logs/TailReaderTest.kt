package com.metrolist.core.api.logs

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TailReaderTest {
    private val root: File = Files.createTempDirectory("tail-reader").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(
        content: String,
        name: String = "f.log",
    ) = File(root, name).also { it.writeBytes(content.encodeToByteArray()) }

    @Test
    fun returnsTheLastLinesAndKeepsTheFinalNewline() {
        assertEquals("c\nd\n", TailReader.tail(file("a\nb\nc\nd\n"), 2))
        assertEquals("c\nd", TailReader.tail(file("a\nb\nc\nd"), 2))
    }

    @Test
    fun fewerLinesThanAskedReturnsEverything() {
        assertEquals("a\nb\n", TailReader.tail(file("a\nb\n"), 50))
        assertEquals("only", TailReader.tail(file("only"), 5))
    }

    @Test
    fun emptyFileIsEmptyText() {
        assertEquals("", TailReader.tail(file(""), 10))
    }

    @Test
    fun crlfIsReturnedAsLf() {
        assertEquals("b\nc\n", TailReader.tail(file("a\r\nb\r\nc\r\n"), 2))
    }

    @Test
    fun exactlyAsManyLinesAsAskedWithAndWithoutTerminator() {
        assertEquals("a\nb\n", TailReader.tail(file("a\nb\n"), 2))
        assertEquals("a\nb", TailReader.tail(file("a\nb"), 2))
    }

    @Test
    fun blankLinesCountAsLines() {
        assertEquals("\n\nz\n", TailReader.tail(file("a\n\n\nz\n"), 3))
    }

    @Test
    fun multibyteCharacterAtABlockBoundaryIsDecodedWhole() {
        // Every block size from 1 to 9 puts a boundary inside some multi-byte character of this text.
        val text = "x\nXin chào thế giới\nĐây là dòng 3 😀\nlast é\n"
        for (blockSize in 1..9) {
            assertEquals("Đây là dòng 3 😀\nlast é\n", TailReader.tail(file(text), 2, blockSize), "blockSize=$blockSize")
            assertEquals(text, TailReader.tail(file(text), 10, blockSize), "blockSize=$blockSize, all")
        }
    }

    @Test
    fun largeFileReadsOnlyTheTailAcrossManyDefaultBlocks() {
        val lines = (1..30_000).joinToString("") { "line $it\n" }
        assertTrue(lines.length > TailReader.BLOCK_SIZE * 3)
        val tail = TailReader.tail(file(lines), 2000)
        assertEquals(2000, tail.trimEnd('\n').split('\n').size)
        assertTrue(tail.startsWith("line 28001\n"))
        assertTrue(tail.endsWith("line 30000\n"))
    }

    @Test
    fun startOfTailPointsAfterTheCuttingNewline() {
        assertEquals(2, TailReader.startOfTail("a\nb\n".encodeToByteArray(), 1))
        assertEquals(0, TailReader.startOfTail("a\nb\n".encodeToByteArray(), 2))
        assertEquals(0, TailReader.startOfTail(ByteArray(0), 3))
    }

    @Test
    fun nonPositiveArgumentsAreRejected() {
        assertFailsWith<IllegalArgumentException> { TailReader.tail(file("a"), 0) }
        assertFailsWith<IllegalArgumentException> { TailReader.tail(file("a"), 1, 0) }
    }

    @Test
    fun logFilesResolveAppToTheNewestRotationAndOthersByName() {
        val old = File(root, "app.log.1").apply { writeText("old") }
        val current = File(root, "app.log").apply { writeText("new") }
        old.setLastModified(1_000_000L)
        current.setLastModified(2_000_000L)
        File(root, "app.log.bak").writeText("ignored")
        File(root, "shell.log").writeText("s")
        assertEquals("app.log", LogFiles.resolve("app", root)?.name)
        old.setLastModified(3_000_000L)
        assertEquals("app.log.1", LogFiles.resolve("app", root)?.name)
        assertEquals("shell.log", LogFiles.resolve("shell", root)?.name)
        assertNull(LogFiles.resolve("ui", root))
        assertNull(LogFiles.resolve("nope", root))
        assertNull(LogFiles.resolve("app", File(root, "missing-dir")))
    }
}
