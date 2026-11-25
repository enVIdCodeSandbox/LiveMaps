package dev.etran.livemaps

import dev.etran.livemaps.render.ImageResampler
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageResamplerTest {
    @Test fun identityReturnsSameDimensionsAndPixels() {
        val src = IntArray(16) { i -> (0xFF shl 24) or ((i * 15) shl 16) or ((255 - i * 10) shl 8) or 80 }
        val out = ImageResampler.resample(src, 4, 4, 4, 4)
        assertEquals(src.size, out.size)
        assertTrue(src.contentEquals(out))
    }

    @Test fun solidColorDownscalePreservesColor() {
        val c = (0xFF shl 24) or (0x4A shl 16) or (0x92 shl 8) or 0xD1
        val src = IntArray(400 * 300) { c }
        val out = ImageResampler.resample(src, 400, 300, 128, 128)
        assertEquals(128 * 128, out.size)
        for (p in out) {
            assertTrue(abs(((p shr 16) and 255) - 0x4A) <= 1)
            assertTrue(abs(((p shr 8) and 255) - 0x92) <= 1)
            assertTrue(abs((p and 255) - 0xD1) <= 1)
        }
    }

    @Test fun linearDownscaleAvoidsSrgbDarkening() {
        // Checkerboard of pure white (lin=1) and pure black (lin=0) has true physical average lin=0.5 (~188 sRGB),
        // whereas naive sRGB averaging gives ~128. Linear Lanczos should land near 180-195.
        val w = 256; val h = 256
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val src = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (((x xor y) and 1) == 0) white else black
        }
        val out = ImageResampler.resample(src, w, h, 64, 64, sharpen = 0.0)
        val center = out[32 * 64 + 32]
        val r = (center shr 16) and 255
        assertTrue(r in 170..205, "Expected linear-light midpoint near 188, got $r")
    }
}
