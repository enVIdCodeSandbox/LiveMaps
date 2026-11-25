package dev.etran.livemaps.core

import dev.etran.livemaps.model.Dither
import dev.etran.livemaps.model.ViewSpec
import dev.etran.livemaps.render.ImageResampler
import dev.etran.livemaps.render.Quantizer
import dev.etran.livemaps.render.RenderContext
import dev.etran.livemaps.render.RenderResult
import dev.etran.livemaps.render.ViewRenderer
import org.bukkit.map.MapPalette

/** Turns a view into map-palette pixel buffers. */
object LiveRender {
    const val MAP = 128

    /** Builds the quantizer from the server's own map palette so it is always valid for the running Minecraft version. */
    fun createQuantizer(): Quantizer {
        val idx = ArrayList<Int>()
        val rgb = ArrayList<Int>()
        for (i in 4..255) {
            val c = try { @Suppress("DEPRECATION") MapPalette.getColor(i.toByte()) } catch (_: Exception) { continue }
            if (c.alpha == 0) continue
            idx.add(i); rgb.add((c.red shl 16) or (c.green shl 8) or c.blue)
        }
        check(idx.isNotEmpty()) { "Could not read the map palette from the server" }
        return Quantizer(idx.toIntArray(), rgb.toIntArray())
    }

    fun renderAll(ctx: RenderContext, quantizer: Quantizer, spec: ViewSpec): RenderOutput {
        val fp = ViewRenderer.fingerprint(ctx, spec)
        val res = ViewRenderer.render(ctx, spec)
        return toOutput(quantizer, spec, res, fp)
    }

    fun toOutput(quantizer: Quantizer, spec: ViewSpec, res: RenderResult, fingerprint: Long): RenderOutput {
        val q = quantizer.quantize(res.argb, res.width, res.height, spec.options.dither)
        val w = spec.widthMaps
        val h = spec.heightMaps
        val tiles = Array(w * h) { ByteArray(MAP * MAP) }
        for (row in 0 until h) for (col in 0 until w) {
            val t = tiles[row * w + col]
            for (y in 0 until MAP) System.arraycopy(q, (row * MAP + y) * res.width + col * MAP, t, y * MAP, MAP)
        }
        return RenderOutput(tiles, thumbnail(quantizer, res), fingerprint)
    }

    /** Whole view scaled to fit one 128x128 map in linear light, letter-boxed on a dark background. */
    fun thumbnail(quantizer: Quantizer, res: RenderResult): ByteArray {
        val scale = minOf(MAP.toDouble() / res.width, MAP.toDouble() / res.height)
        val tw = (res.width * scale).toInt().coerceIn(1, MAP)
        val th = (res.height * scale).toInt().coerceIn(1, MAP)
        val offX = (MAP - tw) / 2
        val offY = (MAP - th) / 2
        val bg = 0xFF1B1B22.toInt()
        val img = IntArray(MAP * MAP) { bg }
        val scaled = ImageResampler.resample(res.argb, res.width, res.height, tw, th, sharpen = 0.15)
        for (y in 0 until th) {
            System.arraycopy(scaled, y * tw, img, (y + offY) * MAP + offX, tw)
        }
        return quantizer.quantize(img, MAP, MAP, Dither.FLOYD_STEINBERG)
    }
}
