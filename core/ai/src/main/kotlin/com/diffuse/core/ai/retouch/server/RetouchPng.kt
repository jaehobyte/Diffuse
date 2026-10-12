package com.diffuse.core.ai.retouch.server

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * specs/skin_retouch_pipeline.md §8.1. The wire's two PNG shapes, byte for byte: 8-bit RGBA
 * (colour type 6) for pixels and 8-bit grayscale (colour type 0) for masks.
 *
 * Hand-written rather than `Bitmap.compress` / `BitmapFactory`, because the contract is about
 * exactly the things those hide: the platform encoder picks the colour type from the bitmap's
 * alpha flag, `ALPHA_8` has no defined PNG form, and decoding premultiplies — so a semi-transparent
 * pixel would not survive a round trip, and a wrong channel count could not even be seen.
 */
@Suppress("TooManyFunctions") // Encoder and decoder of one format, kept side by side.
internal object RetouchPng {

    const val COLOR_GRAY = 0
    const val COLOR_RGBA = 6

    /** One decoded image. [samples] is row-major, [channels] bytes per pixel, no filter bytes. */
    class Image(val width: Int, val height: Int, val colorType: Int, val samples: ByteArray) {
        val channels: Int get() = channelsOf(colorType)
    }

    /** [argb] are straight-alpha colour ints, as `Bitmap.getPixels` returns them. */
    fun encodeRgba(width: Int, height: Int, argb: IntArray): ByteArray {
        require(argb.size == width * height) { "pixels are not ${width}x$height" }
        val samples = ByteArray(argb.size * RGBA_CHANNELS)
        for (i in argb.indices) {
            val pixel = argb[i]
            val at = i * RGBA_CHANNELS
            samples[at] = (pixel ushr RED_SHIFT).toByte()
            samples[at + 1] = (pixel ushr GREEN_SHIFT).toByte()
            samples[at + 2] = pixel.toByte()
            samples[at + ALPHA_OFFSET] = (pixel ushr ALPHA_SHIFT).toByte()
        }
        return encode(width, height, COLOR_RGBA, samples)
    }

    fun encodeGray(width: Int, height: Int, values: ByteArray): ByteArray {
        require(values.size == width * height) { "mask is not ${width}x$height" }
        return encode(width, height, COLOR_GRAY, values)
    }

    /**
     * @param maxPixels checked against IHDR **before** anything is inflated, so a hostile or
     * broken header cannot make the client allocate a picture it will reject anyway.
     * @return null for anything that is not a non-interlaced 8-bit gray or RGBA PNG, or is corrupt.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun decode(bytes: ByteArray, maxPixels: Long): Image? {
        if (bytes.size < SIGNATURE.size || !SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }) {
            return null
        }
        var offset = SIGNATURE.size
        var header: IntArray? = null
        val idat = ByteArrayOutputStream()
        var ended = false
        while (offset + CHUNK_OVERHEAD <= bytes.size && !ended) {
            val length = readInt(bytes, offset)
            if (length < 0 || offset + CHUNK_OVERHEAD + length > bytes.size) return null
            val type = String(bytes, offset + LENGTH_BYTES, TYPE_BYTES, Charsets.US_ASCII)
            val dataStart = offset + LENGTH_BYTES + TYPE_BYTES
            val crc = CRC32().apply { update(bytes, offset + LENGTH_BYTES, TYPE_BYTES + length) }
            if (crc.value.toInt() != readInt(bytes, dataStart + length)) return null
            when (type) {
                "IHDR" -> {
                    if (header != null || length != IHDR_LENGTH) return null
                    header = IntArray(IHDR_FIELDS).also { h ->
                        h[0] = readInt(bytes, dataStart)
                        h[1] = readInt(bytes, dataStart + INT_BYTES)
                        for (i in 2 until IHDR_FIELDS) {
                            h[i] = bytes[dataStart + 2 * INT_BYTES + i - 2].toInt() and BYTE_MASK
                        }
                    }
                }
                "IDAT" -> {
                    if (header == null) return null
                    idat.write(bytes, dataStart, length)
                }
                "IEND" -> ended = true
                else -> if (header == null) return null
            }
            offset = dataStart + length + CRC_BYTES
        }
        val h = header ?: return null
        val width = h[0]
        val height = h[1]
        val colorType = h[IHDR_COLOR]
        val valid = ended && width > 0 && height > 0 &&
            width.toLong() * height <= maxPixels &&
            h[IHDR_DEPTH] == BIT_DEPTH && (colorType == COLOR_GRAY || colorType == COLOR_RGBA) &&
            h[IHDR_COMPRESSION] == 0 && h[IHDR_FILTER] == 0 && h[IHDR_INTERLACE] == 0
        if (!valid) return null
        val channels = channelsOf(colorType)
        val raw = inflate(idat.toByteArray(), (width * channels + 1) * height) ?: return null
        val samples = unfilter(raw, width, height, channels) ?: return null
        return Image(width, height, colorType, samples)
    }

    private fun encode(width: Int, height: Int, colorType: Int, samples: ByteArray): ByteArray {
        val channels = channelsOf(colorType)
        val stride = width * channels
        // Filter type 0 on every row: deterministic, and the deflate does the real work.
        val raw = ByteArray((stride + 1) * height)
        for (y in 0 until height) {
            System.arraycopy(samples, y * stride, raw, y * (stride + 1) + 1, stride)
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(width)
                writeInt(height)
                writeByte(BIT_DEPTH)
                writeByte(colorType)
                writeByte(0)
                writeByte(0)
                writeByte(0)
            }
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", deflate(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        DataOutputStream(out).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
        }
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 2 + BUFFER_BYTES)
            val buffer = ByteArray(BUFFER_BYTES)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Exactly [expected] bytes or null: a short or overlong stream is a corrupt image. */
    private fun inflate(data: ByteArray, expected: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArray(expected)
            var written = 0
            while (written < expected && !inflater.finished()) {
                val n = inflater.inflate(out, written, expected - written)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                written += n
            }
            val trailing = ByteArray(1)
            val overlong = !inflater.finished() && inflater.inflate(trailing) > 0
            if (written != expected || overlong) null else out
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun unfilter(raw: ByteArray, width: Int, height: Int, channels: Int): ByteArray? {
        val stride = width * channels
        val out = ByteArray(stride * height)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            val src = y * (stride + 1) + 1
            val row = y * stride
            val prior = row - stride
            for (x in 0 until stride) {
                val value = raw[src + x].toInt() and BYTE_MASK
                val left = if (x >= channels) out[row + x - channels].toInt() and BYTE_MASK else 0
                val up = if (y > 0) out[prior + x].toInt() and BYTE_MASK else 0
                val upLeft =
                    if (y > 0 && x >= channels) out[prior + x - channels].toInt() and BYTE_MASK else 0
                val predicted = when (filter) {
                    FILTER_NONE -> 0
                    FILTER_SUB -> left
                    FILTER_UP -> up
                    FILTER_AVERAGE -> (left + up) / 2
                    FILTER_PAETH -> paeth(left, up, upLeft)
                    else -> return null
                }
                out[row + x] = (value + predicted).toByte()
            }
        }
        return out
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and BYTE_MASK shl ALPHA_SHIFT) or
            (bytes[at + 1].toInt() and BYTE_MASK shl RED_SHIFT) or
            (bytes[at + 2].toInt() and BYTE_MASK shl GREEN_SHIFT) or
            (bytes[at + LAST_BYTE].toInt() and BYTE_MASK)

    private fun channelsOf(colorType: Int): Int = if (colorType == COLOR_RGBA) RGBA_CHANNELS else 1

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A,
    )
    private const val RGBA_CHANNELS = 4
    private const val ALPHA_OFFSET = 3
    private const val LAST_BYTE = 3
    private const val BIT_DEPTH = 8
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE_MASK = 0xFF
    private const val INT_BYTES = 4
    private const val LENGTH_BYTES = 4
    private const val TYPE_BYTES = 4
    private const val CRC_BYTES = 4
    private const val CHUNK_OVERHEAD = LENGTH_BYTES + TYPE_BYTES + CRC_BYTES
    private const val IHDR_LENGTH = 13
    private const val IHDR_FIELDS = 7
    private const val IHDR_DEPTH = 2
    private const val IHDR_COLOR = 3
    private const val IHDR_COMPRESSION = 4
    private const val IHDR_FILTER = 5
    private const val IHDR_INTERLACE = 6
    private const val FILTER_NONE = 0
    private const val FILTER_SUB = 1
    private const val FILTER_UP = 2
    private const val FILTER_AVERAGE = 3
    private const val FILTER_PAETH = 4
    private const val BUFFER_BYTES = 64 * 1024
}
