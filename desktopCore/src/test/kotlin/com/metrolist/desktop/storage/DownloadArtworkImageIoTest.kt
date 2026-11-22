package com.metrolist.desktop.storage

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadArtworkImageIoTest {
    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(200, 40, 40)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun largeImageIsScaledToMaxOnTheLongerSideKeepingAspect() {
        val cover = assertNotNull(DownloadArtwork.encode(png(2400, 1200)))
        assertEquals(DownloadArtwork.MAX_PX, cover.width)
        assertEquals(DownloadArtwork.MAX_PX / 2, cover.height)
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(cover.jpeg)))
        assertEquals(cover.width, decoded.width)
        assertEquals(cover.height, decoded.height)
    }

    @Test
    fun smallImageIsNeverScaledUp() {
        val cover = assertNotNull(DownloadArtwork.encode(png(300, 500)))
        assertEquals(300, cover.width)
        assertEquals(500, cover.height)
    }

    @Test
    fun outputIsAJpegWithTheSourceColour() {
        val cover = assertNotNull(DownloadArtwork.encode(png(64, 64)))
        assertTrue(cover.jpeg.size > 3 && cover.jpeg[0] == 0xFF.toByte() && cover.jpeg[1] == 0xD8.toByte())
        val pixel = Color(assertNotNull(ImageIO.read(ByteArrayInputStream(cover.jpeg))).getRGB(32, 32))
        assertTrue(kotlin.math.abs(pixel.red - 200) < 12 && kotlin.math.abs(pixel.green - 40) < 12)
    }

    @Test
    fun undecodableBytesGiveNull() {
        assertNull(DownloadArtwork.encode(byteArrayOf(1, 2, 3, 4)))
    }
}
