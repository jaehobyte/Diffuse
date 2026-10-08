package com.diffuse.core.ai.retouch.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.zip.CRC32

/** specs/skin_retouch_pipeline.md §8.1: the two wire PNG shapes, exactly. Plain JVM. */
class RetouchPngTest {

    @Test
    fun `rgba round-trips straight alpha without premultiplication`() {
        val argb = intArrayOf(0x80FF0000.toInt(), 0x01020304, 0xFFFFFFFF.toInt(), 0x00ABCDEF, 0x7F102030, 0)
        val png = RetouchPng.encodeRgba(3, 2, argb)

        val decoded = RetouchPng.decode(png, MAX)!!

        assertEquals(RetouchPng.COLOR_RGBA, decoded.colorType)
        assertEquals(3, decoded.width)
        assertEquals(2, decoded.height)
        // R, G, B, A of the first pixel: alpha 0x80 did not scale the red channel.
        assertEquals(0xFF, decoded.samples[0].toInt() and 0xFF)
        assertEquals(0x80, decoded.samples[3].toInt() and 0xFF)
        // A fully transparent pixel keeps its colour, which premultiplication would have zeroed.
        assertEquals(0xAB, decoded.samples[12].toInt() and 0xFF)
        assertEquals(0x00, decoded.samples[15].toInt() and 0xFF)
    }

    @Test
    fun `the gray header says color type 0 and bit depth 8`() {
        val png = RetouchPng.encodeGray(2, 2, byteArrayOf(0, -1, -1, 0))

        assertEquals(8, png[24].toInt())
        assertEquals(0, png[25].toInt())
        val decoded = RetouchPng.decode(png, MAX)!!
        assertArrayEquals(byteArrayOf(0, -1, -1, 0), decoded.samples)
    }

    @Test
    fun `the pixel limit is checked from the header`() {
        val png = RetouchPng.encodeGray(4, 4, ByteArray(16))

        assertNull(RetouchPng.decode(png, 15))
        assertNotNull(RetouchPng.decode(png, 16))
    }

    @Test
    fun `a corrupt checksum is not an image`() {
        val png = RetouchPng.encodeGray(2, 2, ByteArray(4))
        png[png.size - 20] = (png[png.size - 20] + 1).toByte()

        assertNull(RetouchPng.decode(png, MAX))
    }

    @Test
    fun `truncated data and non-png bytes are rejected`() {
        val png = RetouchPng.encodeRgba(8, 8, IntArray(64) { it * 0x010101 })

        assertNull(RetouchPng.decode(png.copyOf(png.size - 30), MAX))
        assertNull(RetouchPng.decode("not a png".toByteArray(), MAX))
    }

    @Test
    fun `an rgb png is decoded as unsupported rather than guessed`() {
        assertNull(RetouchPng.decode(withColorType(RetouchPng.encodeGray(1, 1, ByteArray(1)), 2), MAX))
    }

    @Test
    fun `sub up average and paeth filters decode`() {
        // A 2x2 gray image, one row per filter, hand-built: every row decodes to 10, 20.
        for (filter in 1..4) {
            val raw = when (filter) {
                1 -> byteArrayOf(1, 10, 10, 1, 10, 10) // sub: 10, 10+10
                2 -> byteArrayOf(0, 10, 20, 2, 0, 0) // up: row 2 = row 1
                3 -> byteArrayOf(0, 10, 20, 3, 5, 5) // average: 5+10/2, 5+(10+20)/2
                else -> byteArrayOf(0, 10, 20, 4, 0, 0) // paeth: predicts up
            }
            val decoded = RetouchPng.decode(pngFromRaw(2, 2, raw), MAX)
            assertNotNull("filter $filter", decoded)
            assertEquals("filter $filter", 10, decoded!!.samples[2].toInt())
            assertEquals("filter $filter", 20, decoded.samples[3].toInt())
        }
    }

    private fun withColorType(png: ByteArray, colorType: Int): ByteArray {
        val copy = png.copyOf()
        copy[25] = colorType.toByte()
        val crc = CRC32().apply { update(copy, 12, 17) }.value.toInt()
        for (i in 0 until 4) copy[29 + i] = (crc ushr (24 - 8 * i)).toByte()
        return copy
    }

    /** Swaps the IDAT of an encoder PNG for [raw], so a test can choose the filter bytes. */
    private fun pngFromRaw(width: Int, height: Int, raw: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(256)
        val idat = buffer.copyOf(deflater.deflate(buffer))
        deflater.end()
        val out = java.io.ByteArrayOutputStream()
        val template = RetouchPng.encodeGray(width, height, ByteArray(width * height))
        out.write(template, 0, 33)
        val stream = java.io.DataOutputStream(out)
        stream.writeInt(idat.size)
        val type = "IDAT".toByteArray()
        stream.write(type)
        stream.write(idat)
        stream.writeInt(CRC32().apply { update(type); update(idat) }.value.toInt())
        stream.writeInt(0)
        val end = "IEND".toByteArray()
        stream.write(end)
        stream.writeInt(CRC32().apply { update(end) }.value.toInt())
        return out.toByteArray()
    }

    private companion object {
        const val MAX = 16_777_216L
    }
}
