package com.metrolist.core.api.library

import com.metrolist.core.server.ApiException
import com.metrolist.core.server.ArtImages
import com.metrolist.core.server.ArtRequests
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverAndArtTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        System.setProperty("java.awt.headless", "true")
        dir = Files.createTempDirectory("cover-art-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun image(
        width: Int,
        height: Int,
        color: Color = Color.RED,
        format: String = "png",
        name: String = "pic.$format",
    ): File {
        val picture = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = picture.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return File(dir, name).also { ImageIO.write(picture, format, it) }
    }

    // --- covers ---------------------------------------------------------------------------------------------------

    @Test
    fun storeCopiesTheCoverUnderAHashedName() {
        val covers = File(dir, "covers")
        val stored = CoverFiles.store("LPabcd1234", image(40, 20), covers)
        assertTrue(Regex("LPabcd1234-[0-9a-f]{8}").matches(stored.id), stored.id)
        assertTrue(stored.file.isFile)
        assertEquals("${stored.id}.png", stored.file.name)
        assertEquals(stored.file, CoverFiles.findById(stored.id, covers))
    }

    @Test
    fun aNewCoverReplacesTheOldOneAndGetsANewId() {
        val covers = File(dir, "covers")
        val first = CoverFiles.store("LPabcd1234", image(40, 20, Color.RED, name = "a.png"), covers)
        val second = CoverFiles.store("LPabcd1234", image(40, 20, Color.BLUE, name = "b.png"), covers)
        assertTrue(first.id != second.id)
        assertFalse(first.file.exists(), "the old cover file is removed")
        assertTrue(second.file.isFile)
        assertEquals(listOf(second.file.name), covers.list().orEmpty().toList())
    }

    @Test
    fun otherPlaylistsKeepTheirCovers() {
        val covers = File(dir, "covers")
        val other = CoverFiles.store("LPother0001", image(10, 10, name = "o.png"), covers)
        CoverFiles.store("LPabcd1234", image(10, 10, Color.GREEN, name = "g.png"), covers)
        CoverFiles.removeCovers("LPabcd1234", covers)
        assertTrue(other.file.exists())
        assertEquals(1, covers.list().orEmpty().size)
    }

    @Test
    fun rejectsWhatIsNotAnImage() {
        val text = File(dir, "x.png").also { it.writeText("not a picture at all") }
        assertEquals("bad_request", assertFailsWith<ApiException> { CoverFiles.validate(text) }.code)
        assertEquals("bad_request", assertFailsWith<ApiException> { CoverFiles.validate(File(dir, "missing.png")) }.code)
    }

    @Test
    fun rejectsAnUnsafePlaylistId() {
        val picture = image(10, 10)
        assertEquals("bad_request", assertFailsWith<ApiException> { CoverFiles.store("../evil", picture, File(dir, "c")) }.code)
        assertFalse(CoverFiles.isSafeCoverId("a/b"))
        assertTrue(CoverFiles.isSafeCoverId("LPabcd1234-0a1b2c3d"))
    }

    @Test
    fun pixelLimitIs16Point7Megapixels() {
        CoverFiles.checkDimensions(4000, 4000) // 16.0 MP
        CoverFiles.checkDimensions(4096, 4077) // 16,699,392 px
        assertEquals("bad_request", assertFailsWith<ApiException> { CoverFiles.checkDimensions(4096, 4096) }.code) // 16,777,216
        assertEquals("bad_request", assertFailsWith<ApiException> { CoverFiles.checkDimensions(0, 10) }.code)
        assertEquals("bad_request", assertFailsWith<ApiException> { CoverFiles.checkDimensions(20_000, 1_000) }.code)
    }

    @Test
    fun fileSizeLimitIs16MiB() {
        val big = File(dir, "big.png")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(CoverFiles.MAX_BYTES + 1) }
        assertEquals("bad_request", assertFailsWith<ApiException> { CoverFiles.validate(big) }.code)
    }

    @Test
    fun coverIdOfAStoredPathOrUri() {
        val covers = File(dir, "covers").also { it.mkdirs() }
        val file = File(covers, "LPabcd1234-0a1b2c3d.jpg")
        assertEquals("LPabcd1234-0a1b2c3d", CoverFiles.idOfStoredCover(file.absolutePath, covers))
        assertEquals("LPabcd1234-0a1b2c3d", CoverFiles.idOfStoredCover(file.toURI().toString(), covers))
        assertNull(CoverFiles.idOfStoredCover(File(dir, "elsewhere/LPx-1.jpg").absolutePath, covers))
        assertNull(CoverFiles.idOfStoredCover("https://i.ytimg.com/vi/x/hqdefault.jpg", covers))
        assertNull(CoverFiles.idOfStoredCover(null, covers))
        assertNull(CoverFiles.idOfStoredCover("  ", covers))
    }

    @Test
    fun artOfACoverIsACoreUrl() {
        val covers = CoverFiles.dir
        val stored = File(covers, "LPabcd1234-0a1b2c3d.jpg")
        assertEquals("core:cover/LPabcd1234-0a1b2c3d", LibraryDto.art(stored.absolutePath, "https://x/y.jpg")?.src)
        assertEquals("https://x/y.jpg", LibraryDto.art(null, "https://x/y.jpg")?.src)
        assertEquals("https://c/z.jpg", LibraryDto.art("https://c/z.jpg", "https://x/y.jpg")?.src)
        assertNull(LibraryDto.art(null, null))
        assertNull(LibraryDto.art("", "  "))
    }

    // --- art route: token, buckets, resize ---------------------------------------------------------------------------

    @Test
    fun tokenCheck() {
        assertTrue(ArtRequests.tokenOk("abc123", "abc123"))
        assertFalse(ArtRequests.tokenOk("abc124", "abc123"))
        assertFalse(ArtRequests.tokenOk(null, "abc123"))
        assertFalse(ArtRequests.tokenOk("", ""))
        assertFalse(ArtRequests.tokenOk("abc123", ""))
    }

    @Test
    fun widthRoundsUpToABucket() {
        assertEquals(64, ArtRequests.bucket(1))
        assertEquals(64, ArtRequests.bucket(64))
        assertEquals(128, ArtRequests.bucket(65))
        assertEquals(256, ArtRequests.bucket(200))
        assertEquals(512, ArtRequests.bucket(512))
        assertEquals(768, ArtRequests.bucket(600))
        assertEquals(1080, ArtRequests.bucket(1000))
        assertEquals(1080, ArtRequests.bucket(5000))
        assertEquals(ArtRequests.DEFAULT_BUCKET, ArtRequests.bucket(null))
        assertEquals(ArtRequests.DEFAULT_BUCKET, ArtRequests.bucket(0))
    }

    @Test
    fun fitKeepsTheAspectRatioAndNeverEnlarges() {
        assertEquals(256 to 128, ArtImages.fit(1000, 500, 256))
        assertEquals(128 to 256, ArtImages.fit(500, 1000, 256))
        assertEquals(256 to 256, ArtImages.fit(1200, 1200, 256))
        assertEquals(100 to 60, ArtImages.fit(100, 60, 256))
        assertEquals(64 to 1, ArtImages.fit(10_000, 10, 64))
    }

    @Test
    fun resizedJpegHasTheBucketDimensions() {
        val bytes = ArtImages.resizeToJpeg(image(1000, 500), 256)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, bytes[1].toInt() and 0xFF)
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
        assertEquals(256, decoded.width)
        assertEquals(128, decoded.height)
    }

    @Test
    fun aSmallPictureIsReencodedAtItsOwnSize() {
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(ArtImages.resizeToJpeg(image(50, 30, format = "png"), 512))))
        assertEquals(50, decoded.width)
        assertEquals(30, decoded.height)
    }

    @Test
    fun aTransparentPngGetsAWhiteBackground() {
        val picture = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val file = File(dir, "alpha.png").also { ImageIO.write(picture, "png", it) }
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(ArtImages.resizeToJpeg(file, 64))))
        val rgb = decoded.getRGB(10, 10) and 0xFFFFFF
        assertTrue(rgb > 0xF0F0F0, "pixel ${rgb.toString(16)}")
    }

    @Test
    fun scalingAHugeReductionStaysInsideTheTarget() {
        val scaled = ArtImages.scale(BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB), 64, 32)
        assertEquals(64, scaled.width)
        assertEquals(32, scaled.height)
    }
}
