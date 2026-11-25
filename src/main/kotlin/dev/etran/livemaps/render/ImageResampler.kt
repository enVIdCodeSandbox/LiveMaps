package dev.etran.livemaps.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * High-quality linear-light Lanczos-3 image resampler with luminance-only detail sharpening.
 * Keeps block edges crisp and prevents gamma-space darkening/muddiness when shrinking
 * high-resolution BlueMap screenshots down to Minecraft map grids.
 */
object ImageResampler {

    private val SRGB_TO_LIN = FloatArray(256) { i ->
        val c = i / 255.0
        (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    private val LIN_TO_SRGB = IntArray(4097) { i ->
        val c = i / 4096.0
        val s = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        (s * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun toSrgb(lin: Float): Int {
        val idx = (lin.coerceIn(0f, 1f) * 4096f + 0.5f).toInt().coerceIn(0, 4096)
        return LIN_TO_SRGB[idx]
    }

    private fun lanczos3(x: Double): Double {
        val ax = abs(x)
        if (ax >= 3.0) return 0.0
        if (ax < 1e-7) return 1.0
        val px = PI * ax
        return (sin(px) / px) * (sin(px / 3.0) / (px / 3.0))
    }

    private class TapSpan(val start: Int, val weights: FloatArray)

    private fun buildSpans(srcLen: Int, dstLen: Int): Array<TapSpan> {
        val scale = dstLen.toDouble() / srcLen.toDouble()
        val supportScale = if (scale < 1.0) scale else 1.0
        val radius = 3.0 / supportScale
        return Array(dstLen) { dst ->
            val center = (dst + 0.5) / scale - 0.5
            val left = floor(center - radius).toInt().coerceAtLeast(0)
            val right = ceil(center + radius).toInt().coerceAtMost(srcLen - 1)
            val n = right - left + 1
            val w = DoubleArray(n)
            var sum = 0.0
            for (i in 0 until n) {
                val weight = lanczos3((left + i - center) * supportScale)
                w[i] = weight
                sum += weight
            }
            val fw = FloatArray(n)
            val inv = if (sum != 0.0) 1.0 / sum else 1.0
            for (i in 0 until n) fw[i] = (w[i] * inv).toFloat()
            TapSpan(left, fw)
        }
    }

    /**
     * Resamples [srcArgb] ([srcW]x[srcH]) to [dstW]x[dstH] in linear light, optionally applying
     * luminance-only sharpening ([sharpen] in 0..1) so fine details survive downscaling.
     */
    fun resample(srcArgb: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int, sharpen: Double = 0.22): IntArray {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0)
        if (srcW == dstW && srcH == dstH) return srcArgb

        val xSpans = buildSpans(srcW, dstW)
        val ySpans = buildSpans(srcH, dstH)

        // Horizontal pass: srcH rows of dstW pixels in linear RGB
        val tmpR = FloatArray(dstW * srcH)
        val tmpG = FloatArray(dstW * srcH)
        val tmpB = FloatArray(dstW * srcH)
        for (y in 0 until srcH) {
            val srcRow = y * srcW
            val dstRow = y * dstW
            for (x in 0 until dstW) {
                val span = xSpans[x]
                val w = span.weights
                var r = 0f; var g = 0f; var b = 0f
                for (i in w.indices) {
                    val c = srcArgb[srcRow + span.start + i]
                    val wt = w[i]
                    r += SRGB_TO_LIN[(c ushr 16) and 255] * wt
                    g += SRGB_TO_LIN[(c ushr 8) and 255] * wt
                    b += SRGB_TO_LIN[c and 255] * wt
                }
                val o = dstRow + x
                tmpR[o] = r.coerceIn(0f, 1f)
                tmpG[o] = g.coerceIn(0f, 1f)
                tmpB[o] = b.coerceIn(0f, 1f)
            }
        }

        // Vertical pass: dstH rows of dstW pixels
        val outR = FloatArray(dstW * dstH)
        val outG = FloatArray(dstW * dstH)
        val outB = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val span = ySpans[y]
            val w = span.weights
            val dstRow = y * dstW
            for (x in 0 until dstW) {
                var r = 0f; var g = 0f; var b = 0f
                for (i in w.indices) {
                    val srcIdx = (span.start + i) * dstW + x
                    val wt = w[i]
                    r += tmpR[srcIdx] * wt
                    g += tmpG[srcIdx] * wt
                    b += tmpB[srcIdx] * wt
                }
                val o = dstRow + x
                outR[o] = r.coerceIn(0f, 1f)
                outG[o] = g.coerceIn(0f, 1f)
                outB[o] = b.coerceIn(0f, 1f)
            }
        }

        val sharpAmt = if (srcW > dstW || srcH > dstH) sharpen.coerceIn(0.0, 0.8).toFloat() else 0f
        val lum = if (sharpAmt > 0f && dstW >= 3 && dstH >= 3) FloatArray(dstW * dstH) { i ->
            0.2126f * outR[i] + 0.7152f * outG[i] + 0.0722f * outB[i]
        } else null

        return IntArray(dstW * dstH) { i ->
            var r = outR[i]; var g = outG[i]; var b = outB[i]
            if (lum != null) {
                val x = i % dstW
                val y = i / dstW
                if (x in 1 until (dstW - 1) && y in 1 until (dstH - 1)) {
                    val blur = (
                        lum[i - dstW - 1] + 2f * lum[i - dstW] + lum[i - dstW + 1] +
                        2f * lum[i - 1] + 4f * lum[i] + 2f * lum[i + 1] +
                        lum[i + dstW - 1] + 2f * lum[i + dstW] + lum[i + dstW + 1]
                    ) * (1f / 16f)
                    val curL = lum[i]
                    val delta = (curL - blur) * sharpAmt
                    if (curL > 1e-4f) {
                        val gain = ((curL + delta) / curL).coerceIn(0.6f, 1.5f)
                        r = (r * gain).coerceIn(0f, 1f)
                        g = (g * gain).coerceIn(0f, 1f)
                        b = (b * gain).coerceIn(0f, 1f)
                    } else if (delta > 0f) {
                        r = (r + delta).coerceIn(0f, 1f)
                        g = (g + delta).coerceIn(0f, 1f)
                        b = (b + delta).coerceIn(0f, 1f)
                    }
                }
            }
            (0xFF shl 24) or (toSrgb(r) shl 16) or (toSrgb(g) shl 8) or toSrgb(b)
        }
    }
}
