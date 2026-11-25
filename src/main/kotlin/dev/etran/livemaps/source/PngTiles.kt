package dev.etran.livemaps.source

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO

internal object PngTiles {

    fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else bytes

    fun crc(bytes: ByteArray): Long = CRC32().also { it.update(bytes) }.value

    /**
     * Decodes a BlueMap lowres tile. Layout (see BlueMap's LowresTile): the PNG is (cells+1) wide and 2*(cells+1) tall.
     * The upper half holds straight ARGB colors, the lower half holds per-cell data:
     * low 16 bits = signed height, bits 16..23 = block light.
     */
    fun decodeBlueMap(bytes: ByteArray, stamp: Long): RawTile {
        val img = ImageIO.read(ByteArrayInputStream(gunzipIfNeeded(bytes))) ?: throw IOException("Unreadable PNG")
        val w = img.width
        val h = img.height
        val cells = w - 1
        if (cells < 1) throw IOException("Unexpected tile size ${w}x$h")
        val argb = img.getRGB(0, 0, w, h, null, 0, w)
        val hasData = h >= 2 * w
        val color = IntArray(cells * cells)
        val height = if (hasData) ShortArray(cells * cells) else null
        val light = if (hasData) ByteArray(cells * cells) else null
        for (z in 0 until cells) {
            for (x in 0 until cells) {
                val i = z * cells + x
                color[i] = argb[z * w + x]
                if (hasData) {
                    val d = argb[(w + z) * w + x]
                    height!![i] = (d and 0xFFFF).toShort()
                    light!![i] = ((d ushr 16) and 0xFF).coerceAtMost(15).toByte()
                }
            }
        }
        return RawTile(cells, color, height, light, stamp)
    }

    /** Decodes a squaremap tile (square ARGB image, no height data). */
    fun decodeSquare(bytes: ByteArray, stamp: Long): RawTile {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IOException("Unreadable PNG")
        if (img.width != img.height) throw IOException("Unexpected tile size ${img.width}x${img.height}")
        val n = img.width
        val argb = img.getRGB(0, 0, n, n, null, 0, n)
        return RawTile(n, argb, null, null, stamp)
    }
}
