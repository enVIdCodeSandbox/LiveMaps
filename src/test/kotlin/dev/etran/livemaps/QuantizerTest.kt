package dev.etran.livemaps

import dev.etran.livemaps.model.Dither
import dev.etran.livemaps.render.Quantizer
import org.bukkit.map.MapPalette
import kotlin.test.*

class QuantizerTest {
    private fun palette(): Quantizer {
        val idx = ArrayList<Int>(); val rgb = ArrayList<Int>()
        for (i in 4..255) {
            val c = try { @Suppress("DEPRECATION") MapPalette.getColor(i.toByte()) } catch (_: Exception) { continue }
            if (c.alpha == 0) continue
            idx.add(i); rgb.add((c.red shl 16) or (c.green shl 8) or c.blue)
        }
        println("palette colors: ${idx.size}, range ${idx.min()}..${idx.max()}")
        return Quantizer(idx.toIntArray(), rgb.toIntArray())
    }

    @Test fun paletteColorsMapToThemselves() {
        val q = palette()
        var bad = 0; var n = 0
        for (i in 4..255) {
            val c = try { @Suppress("DEPRECATION") MapPalette.getColor(i.toByte()) } catch (_: Exception) { continue }
            n++
            val got = q.nearest(c.red, c.green, c.blue)
            val g = q.rgbOf(got)
            val dr = Math.abs(((g shr 16) and 255) - c.red) + Math.abs(((g shr 8) and 255) - c.green) + Math.abs((g and 255) - c.blue)
            if (dr > 12) bad++
        }
        assertTrue(n > 100, "expected a full palette, got $n")
        assertTrue(bad <= n / 20, "too many palette colors not mapping close to themselves: $bad/$n")
    }

    @Test fun neverReturnsTransparentIndices() {
        val q = palette()
        val img = IntArray(64 * 64) { (255 shl 24) or ((it * 7919) and 0xFFFFFF) }
        for (d in Dither.values()) {
            val out = q.quantize(img, 64, 64, d)
            assertTrue(out.all { (it.toInt() and 0xFF) >= 4 }, "dither $d produced a transparent index")
        }
    }

    @Test fun floydSteinbergReproducesAveragesBetterThanNearest() {
        val q = palette()
        // smooth gradient: dithering should keep the *average* colour much closer even if per-pixel error is higher
        val w = 128; val h = 128
        val img = IntArray(w * h) { i -> val x = i % w; val v = 40 + x; (255 shl 24) or (v shl 16) or ((v * 3 / 4) shl 8) or (v / 2) }
        fun avg(out: ByteArray): Triple<Double, Double, Double> {
            var r = 0.0; var g = 0.0; var b = 0.0
            for (b0 in out) { val c = q.rgbOf(b0.toInt() and 0xFF); r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
            return Triple(r / out.size, g / out.size, b / out.size)
        }
        var tr = 0.0; var tg = 0.0; var tb = 0.0
        for (c in img) { tr += (c shr 16) and 255; tg += (c shr 8) and 255; tb += c and 255 }
        val target = Triple(tr / img.size, tg / img.size, tb / img.size)
        fun err(t: Triple<Double, Double, Double>) = Math.abs(t.first - target.first) + Math.abs(t.second - target.second) + Math.abs(t.third - target.third)
        val none = err(avg(q.quantize(img, w, h, Dither.NONE)))
        val fs = err(avg(q.quantize(img, w, h, Dither.FLOYD_STEINBERG)))
        assertTrue(fs < none, "FS avg error $fs should beat nearest $none")
    }
}
