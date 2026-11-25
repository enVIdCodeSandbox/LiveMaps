package dev.etran.livemaps.render

import dev.etran.livemaps.model.Upscale
import dev.etran.livemaps.model.ViewSpec
import dev.etran.livemaps.source.Geometry
import dev.etran.livemaps.source.LevelInfo
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Top-down renderer.
 *
 * Picks the coarsest source level that is still at least as detailed as the output (so large views are
 * downsampled from real data instead of being decimated), box-filters with supersampling, and applies relief
 * shading from the height data when the source has it.
 */
object FlatRenderer {

    fun level(geo: Geometry, zoom: Double): LevelInfo =
        geo.levels.lastOrNull { it.blocksPerCell <= zoom } ?: geo.levels.first()

    fun plan(v: ViewSpec, geo: Geometry): TilePlan {
        val lvl = level(geo, v.zoom)
        val hx = v.pixelWidth * v.zoom / 2.0
        val hy = v.pixelHeight * v.zoom / 2.0
        val r = hypot(hx, hy) + lvl.blocksPerCell + 1
        return TilePlan.forBounds(lvl, v.camera.x - r, v.camera.x + r, v.camera.z - r, v.camera.z + r)
    }

    fun render(ctx: RenderContext, v: ViewSpec, geo: Geometry): RenderResult {
        val plan = plan(v, geo)
        val set = TileSet.load(ctx, v.source.target, plan)
        return render(ctx, v, geo, set)
    }

    fun render(ctx: RenderContext, v: ViewSpec, geo: Geometry, set: TileSet): RenderResult {
        val w = v.pixelWidth
        val h = v.pixelHeight
        val z = v.zoom
        val bpc = set.bpc
        val cells = set.cells
        val yaw = Math.toRadians(v.camera.yaw)
        val c = cos(yaw)
        val s = sin(yaw)
        val cx = v.camera.x
        val cz = v.camera.z
        val bilinear = v.options.upscale == Upscale.BILINEAR && z < bpc * 1.0 + 1e-9
        // sub-samples per axis: how many source cells fit in one output pixel (1..4)
        val n = Math.ceil(z / bpc - 0.15).toInt().coerceIn(1, 4)
        val inv = 1.0 / (n * n)
        val out = IntArray(w * h)
        val hbuf = FloatArray(w * h)
        val voidColor = geo.voidColor ?: ctx.settings.voidColor
        val hasHeight = geo.hasHeight

        parallelRows(ctx.pool, h) { py ->
            val cur = set.Cursor()
            for (px in 0 until w) {
                var sa = 0.0; var sr = 0.0; var sg = 0.0; var sb = 0.0
                var sh = 0.0; var hn = 0.0
                for (j in 0 until n) for (i in 0 until n) {
                    val sx = (px + (i + 0.5) / n - w / 2.0) * z
                    val sy = (py + (j + 0.5) / n - h / 2.0) * z
                    // screen right = (c, s), screen down = (-s, c) in (x, z)
                    val wx = cx + c * sx - s * sy
                    val wz = cz + s * sx + c * sy
                    if (bilinear) {
                        val u = wx / bpc - 0.5
                        val vv = wz / bpc - 0.5
                        val x0 = floor(u).toInt(); val z0 = floor(vv).toInt()
                        val fx = u - x0; val fz = vv - z0
                        for (k in 0..3) {
                            val dx = k and 1; val dz = k shr 1
                            val wgt = (if (dx == 0) 1 - fx else fx) * (if (dz == 0) 1 - fz else fz)
                            val idx = cur.locate(x0 + dx, z0 + dz)
                            if (idx < 0) continue
                            val t = cur.cur!!
                            val col = t.color[idx]
                            val a = (col ushr 24) / 255.0
                            if (a <= 0.0) continue
                            val wa = wgt * a
                            sa += wa; sr += wa * ((col shr 16) and 255); sg += wa * ((col shr 8) and 255); sb += wa * (col and 255)
                            if (hasHeight) { sh += wa * t.height!![idx]; hn += wa }
                        }
                    } else {
                        val idx = cur.locate(floor(wx / bpc).toInt(), floor(wz / bpc).toInt())
                        if (idx < 0) continue
                        val t = cur.cur!!
                        val col = t.color[idx]
                        val a = (col ushr 24) / 255.0
                        if (a <= 0.0) continue
                        sa += a; sr += a * ((col shr 16) and 255); sg += a * ((col shr 8) and 255); sb += a * (col and 255)
                        if (hasHeight) { sh += a * t.height!![idx]; hn += a }
                    }
                }
                val p = py * w + px
                val alpha = sa * inv
                var r: Double; var g: Double; var b: Double
                if (sa > 0.0) { r = sr / sa; g = sg / sa; b = sb / sa } else { r = 0.0; g = 0.0; b = 0.0 }
                val vr = (voidColor shr 16) and 255; val vg = (voidColor shr 8) and 255; val vb = voidColor and 255
                val aa = alpha.coerceIn(0.0, 1.0)
                r = r * aa + vr * (1 - aa); g = g * aa + vg * (1 - aa); b = b * aa + vb * (1 - aa)
                out[p] = (255 shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
                hbuf[p] = if (hn > 0.0) (sh / hn).toFloat() else Float.NaN
            }
        }

        if (hasHeight && v.options.shading > 0.0) applyRelief(ctx, out, hbuf, w, h, v.options.shading)
        return RenderResult(out, w, h)
    }

    /** Light from the top-left of the screen: surfaces rising towards the bottom-right face the light and get brighter. */
    private fun applyRelief(ctx: RenderContext, px: IntArray, hb: FloatArray, w: Int, h: Int, strength: Double) {
        parallelRows(ctx.pool, h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val h0 = hb[i]
                if (h0.isNaN()) continue
                val nw = hb[(maxOf(y - 1, 0)) * w + maxOf(x - 1, 0)].let { if (it.isNaN()) h0 else it }
                val se = hb[(minOf(y + 1, h - 1)) * w + minOf(x + 1, w - 1)].let { if (it.isNaN()) h0 else it }
                val g = (se - nw) * 0.5
                val f = 1.0 + strength * 0.35 * tanh(g * 0.5)
                val c = px[i]
                val r = (((c shr 16) and 255) * f).roundToInt().coerceIn(0, 255)
                val gg = (((c shr 8) and 255) * f).roundToInt().coerceIn(0, 255)
                val b = ((c and 255) * f).roundToInt().coerceIn(0, 255)
                px[i] = (255 shl 24) or (r shl 16) or (gg shl 8) or b
            }
        }
    }
}
