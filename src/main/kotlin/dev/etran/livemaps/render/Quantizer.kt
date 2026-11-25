package dev.etran.livemaps.render

import dev.etran.livemaps.model.Dither
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Converts RGB pixels to Minecraft map palette indices.
 *
 * [palette] maps palette index -> RGB for every usable index (indices 0..3 are transparent and are never used).
 * Uses Oklab perceptual color distance with an exact candidate-accelerated 3D lookup table so every
 * 24-bit RGB color maps to its exact closest Minecraft palette color without 6-bit rounding loss.
 */
class Quantizer(paletteIndices: IntArray, paletteRgb: IntArray) {

    private val indices = paletteIndices
    private val rgb = paletteRgb

    // palette index -> packed rgb and Oklab coordinates
    private val rgbByIndex = IntArray(256).also { arr -> for (i in indices.indices) arr[indices[i]] = rgb[i] }
    private val palL = FloatArray(indices.size)
    private val palA = FloatArray(indices.size)
    private val palB = FloatArray(indices.size)

    // 64x64x64 cells: >= 0 means unique palette index; < 0 means -(offset + 1) into [candPool]
    private val cellLut = IntArray(1 shl 18)
    private val candPool: IntArray

    init {
        require(indices.isNotEmpty() && indices.size == rgb.size)
        for (i in rgb.indices) {
            val c = rgb[i]
            val lab = rgbToOklab((c shr 16) and 255, (c shr 8) and 255, c and 255)
            palL[i] = lab[0]; palA[i] = lab[1]; palB[i] = lab[2]
        }

        val pool = ArrayList<Int>(65536)
        val corners = FloatArray(24)
        val tmpDists = FloatArray(indices.size)

        for (ri in 0 until 64) for (gi in 0 until 64) for (bi in 0 until 64) {
            val r0 = ri * 4; val r1 = r0 + 3
            val g0 = gi * 4; val g1 = g0 + 3
            val b0 = bi * 4; val b1 = b0 + 3

            val cLab = rgbToOklab((r0 + r1) * 0.5f, (g0 + g1) * 0.5f, (b0 + b1) * 0.5f)
            val cL = cLab[0]; val cA = cLab[1]; val cB = cLab[2]

            // Max Oklab distance from cell center to any of its 8 RGB corners
            var idx8 = 0
            for (cr in intArrayOf(r0, r1)) for (cg in intArrayOf(g0, g1)) for (cb in intArrayOf(b0, b1)) {
                val kl = rgbToOklab(cr.toFloat(), cg.toFloat(), cb.toFloat())
                corners[idx8++] = kl[0]; corners[idx8++] = kl[1]; corners[idx8++] = kl[2]
            }
            var radSq = 0f
            for (k in 0 until 8) {
                val dl = corners[k * 3] - cL
                val da = corners[k * 3 + 1] - cA
                val db = corners[k * 3 + 2] - cB
                val d = dl * dl + da * da + db * db
                if (d > radSq) radSq = d
            }
            val rad = sqrt(radSq)

            var bestI = 0
            var bestD = Float.MAX_VALUE
            for (i in indices.indices) {
                val dl = palL[i] - cL
                val da = palA[i] - cA
                val db = palB[i] - cB
                val d = dl * dl + da * da + db * db
                tmpDists[i] = d
                if (d < bestD) { bestD = d; bestI = i }
            }
            val limit = sqrt(bestD) + 2f * rad
            val limitSq = limit * limit

            val cellIdx = (ri shl 12) or (gi shl 6) or bi
            // Count candidates within triangle-inequality bound
            var count = 0
            for (i in indices.indices) if (tmpDists[i] <= limitSq) count++
            if (count <= 1) {
                cellLut[cellIdx] = indices[bestI]
            } else {
                val offset = pool.size
                cellLut[cellIdx] = -(offset + 1)
                pool.add(count)
                // Put the cell-center winner first
                pool.add(bestI)
                for (i in indices.indices) {
                    if (i != bestI && tmpDists[i] <= limitSq) pool.add(i)
                }
            }
        }
        candPool = pool.toIntArray()
    }

    /** Palette index nearest to the given 24-bit RGB color (exact Oklab perceptual match). */
    fun nearest(r: Int, g: Int, b: Int): Int {
        val rc = r.coerceIn(0, 255)
        val gc = g.coerceIn(0, 255)
        val bc = b.coerceIn(0, 255)
        val entry = cellLut[((rc shr 2) shl 12) or ((gc shr 2) shl 6) or (bc shr 2)]
        if (entry >= 0) return entry

        val lr = SRGB_TO_LIN[rc]; val lg = SRGB_TO_LIN[gc]; val lb = SRGB_TO_LIN[bc]
        val l_ = cbrt(0.41222147f * lr + 0.53633254f * lg + 0.05144599f * lb)
        val m_ = cbrt(0.21190350f * lr + 0.68069955f * lg + 0.10739696f * lb)
        val s_ = cbrt(0.08830246f * lr + 0.28171884f * lg + 0.62997870f * lb)
        val pL = 0.21045426f * l_ + 0.79361779f * m_ - 0.00407205f * s_
        val pA = 1.97799850f * l_ - 2.42859221f * m_ + 0.45059371f * s_
        val pB = 0.02590404f * l_ + 0.78277177f * m_ - 0.80867577f * s_

        val off = -entry - 1
        val count = candPool[off]
        var bestIdx = candPool[off + 1]
        var bestD = Float.MAX_VALUE
        for (k in 1..count) {
            val pi = candPool[off + k]
            val dl = palL[pi] - pL
            val da = palA[pi] - pA
            val db = palB[pi] - pB
            val d = dl * dl + da * da + db * db
            if (d < bestD) { bestD = d; bestIdx = pi }
        }
        return indices[bestIdx]
    }

    fun rgbOf(index: Int): Int = rgbByIndex[index.coerceIn(0, 255)]

    /** Palette indices back to RGB (what the player will actually see). */
    fun toRgb(q: ByteArray): IntArray = IntArray(q.size) { (255 shl 24) or rgbByIndex[q[it].toInt() and 0xFF] }

    /** Quantizes a whole image (so error diffusion is seamless across map tiles). */
    fun quantize(argb: IntArray, w: Int, h: Int, dither: Dither): ByteArray {
        val out = ByteArray(w * h)
        when (dither) {
            Dither.NONE -> for (i in out.indices) {
                val c = argb[i]
                out[i] = nearest((c shr 16) and 255, (c shr 8) and 255, c and 255).toByte()
            }
            Dither.ORDERED -> for (y in 0 until h) for (x in 0 until w) {
                val c = argb[y * w + x]
                val t = (((BAYER[(y and 7) * 8 + (x and 7)] + 0.5) / 64.0 - 0.5) * 26.0).toInt()
                out[y * w + x] = nearest(
                    ((c shr 16) and 255) + t, ((c shr 8) and 255) + t, (c and 255) + t,
                ).toByte()
            }
            Dither.FLOYD_STEINBERG -> floyd(argb, w, h, out)
        }
        return out
    }

    private fun floyd(argb: IntArray, w: Int, h: Int, out: ByteArray) {
        // Serpentine Floyd-Steinberg with clamped error to prevent edge color bleeding
        var curR = FloatArray(w + 2); var curG = FloatArray(w + 2); var curB = FloatArray(w + 2)
        var nxtR = FloatArray(w + 2); var nxtG = FloatArray(w + 2); var nxtB = FloatArray(w + 2)
        val strength = 0.88f
        val maxErr = 42f
        for (y in 0 until h) {
            val ltr = (y and 1) == 0
            var x = if (ltr) 0 else w - 1
            val dx = if (ltr) 1 else -1
            while (x in 0 until w) {
                val c = argb[y * w + x]
                val sr = ((c shr 16) and 255).toFloat()
                val sg = ((c shr 8) and 255).toFloat()
                val sb = (c and 255).toFloat()
                val r = (sr + curR[x + 1].coerceIn(-maxErr, maxErr)).coerceIn(0f, 255f)
                val g = (sg + curG[x + 1].coerceIn(-maxErr, maxErr)).coerceIn(0f, 255f)
                val b = (sb + curB[x + 1].coerceIn(-maxErr, maxErr)).coerceIn(0f, 255f)
                val idx = nearest((r + 0.5f).toInt(), (g + 0.5f).toInt(), (b + 0.5f).toInt())
                out[y * w + x] = idx.toByte()
                val p = rgbByIndex[idx]
                val er = ((r - ((p shr 16) and 255)) * strength).coerceIn(-maxErr, maxErr)
                val eg = ((g - ((p shr 8) and 255)) * strength).coerceIn(-maxErr, maxErr)
                val eb = ((b - (p and 255)) * strength).coerceIn(-maxErr, maxErr)

                curR[x + 1 + dx] += er * (7f / 16f); curG[x + 1 + dx] += eg * (7f / 16f); curB[x + 1 + dx] += eb * (7f / 16f)
                nxtR[x + 1 - dx] += er * (3f / 16f); nxtG[x + 1 - dx] += eg * (3f / 16f); nxtB[x + 1 - dx] += eb * (3f / 16f)
                nxtR[x + 1] += er * (5f / 16f); nxtG[x + 1] += eg * (5f / 16f); nxtB[x + 1] += eb * (5f / 16f)
                nxtR[x + 1 + dx] += er * (1f / 16f); nxtG[x + 1 + dx] += eg * (1f / 16f); nxtB[x + 1 + dx] += eb * (1f / 16f)
                x += dx
            }
            var t = curR; curR = nxtR; nxtR = t
            t = curG; curG = nxtG; nxtG = t
            t = curB; curB = nxtB; nxtB = t
            java.util.Arrays.fill(nxtR, 0f)
            java.util.Arrays.fill(nxtG, 0f)
            java.util.Arrays.fill(nxtB, 0f)
        }
    }

    /** Mean absolute RGB error between an image and its quantized version (for tests/diagnostics). */
    fun meanError(argb: IntArray, q: ByteArray): Double {
        var sum = 0.0
        for (i in argb.indices) {
            val c = argb[i]; val p = rgbByIndex[q[i].toInt() and 0xFF]
            sum += abs(((c shr 16) and 255) - ((p shr 16) and 255)) + abs(((c shr 8) and 255) - ((p shr 8) and 255)) + abs((c and 255) - (p and 255))
        }
        return sum / (argb.size * 3.0)
    }

    companion object {
        private val SRGB_TO_LIN = FloatArray(256) { i ->
            val c = i / 255.0
            (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
        }

        private fun srgbLin(v: Float): Float {
            val c = (v / 255.0).coerceIn(0.0, 1.0)
            return (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
        }

        private fun rgbToOklab(r: Int, g: Int, b: Int): FloatArray =
            linToOklab(SRGB_TO_LIN[r.coerceIn(0, 255)], SRGB_TO_LIN[g.coerceIn(0, 255)], SRGB_TO_LIN[b.coerceIn(0, 255)])

        private fun rgbToOklab(r: Float, g: Float, b: Float): FloatArray =
            linToOklab(srgbLin(r), srgbLin(g), srgbLin(b))

        private fun linToOklab(r: Float, g: Float, b: Float): FloatArray {
            val l_ = cbrt(0.41222147f * r + 0.53633254f * g + 0.05144599f * b)
            val m_ = cbrt(0.21190350f * r + 0.68069955f * g + 0.10739696f * b)
            val s_ = cbrt(0.08830246f * r + 0.28171884f * g + 0.62997870f * b)
            return floatArrayOf(
                0.21045426f * l_ + 0.79361779f * m_ - 0.00407205f * s_,
                1.97799850f * l_ - 2.42859221f * m_ + 0.45059371f * s_,
                0.02590404f * l_ + 0.78277177f * m_ - 0.80867577f * s_,
            )
        }

        private val BAYER = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
        )
    }
}
