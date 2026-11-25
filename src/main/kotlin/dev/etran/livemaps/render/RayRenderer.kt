package dev.etran.livemaps.render

import dev.etran.livemaps.model.CameraMode
import dev.etran.livemaps.model.Projection
import dev.etran.livemaps.model.ViewSpec
import dev.etran.livemaps.source.Geometry
import dev.etran.livemaps.source.LevelInfo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Orthonormal camera basis. yaw: compass degrees (0 = north), tilt: degrees from straight down. */
class CameraBasis(yawDeg: Double, tiltDeg: Double) {
    val fx: Double; val fy: Double; val fz: Double
    val rx: Double; val ry: Double; val rz: Double
    val ux: Double; val uy: Double; val uz: Double

    init {
        val psi = Math.toRadians(yawDeg)
        val tau = Math.toRadians(tiltDeg)
        fx = sin(psi) * sin(tau); fy = -cos(tau); fz = -cos(psi) * sin(tau)
        rx = cos(psi); ry = 0.0; rz = sin(psi)
        // up = right x forward
        ux = ry * fz - rz * fy
        uy = rz * fx - rx * fz
        uz = rx * fy - ry * fx
    }
}

/**
 * Software 3D renderer for any camera angle.
 *
 * The terrain is a heightfield of block columns (one color + height per column, from BlueMap's low-res tiles).
 * Every pixel casts a ray that is stepped through the column grid (Amanatides-Woo traversal), which gives exact
 * hits on column tops and sides with no triangle rasterisation. Supports orthographic and perspective projection,
 * orbit and free-flight cameras, sun lighting, optional shadows and distance fog.
 *
 * Limits: only the top surface of each column is known, so overhangs, caves and interiors are not representable.
 */
object RayRenderer {

    private const val SKY_DEFAULT = 0xFF7DABFF.toInt()
    private const val CLIFF_COLOR = 0x7A7468

    /** One distance band of a perspective view, drawn from one resolution level of the source. */
    class Ring(val level: LevelInfo, val inner: Double, val outer: Double, val plan: TilePlan)

    /** A ring together with the tiles loaded for it. */
    class RingSet(val ring: Ring, val set: TileSet)

    /** The view distance asked for by the view, limited by the server. */
    fun viewDistance(ctx: RenderContext, v: ViewSpec): Double {
        val want = if (v.options.viewDistance > 0) v.options.viewDistance else ctx.settings.viewDistance
        return want.coerceIn(32, ctx.settings.maxViewDistance.coerceAtLeast(32)).toDouble()
    }

    fun reach(ctx: RenderContext, v: ViewSpec): Double {
        val base = viewDistance(ctx, v)
        return if (v.projection == Projection.PERSPECTIVE) {
            // an orbit camera always has to be able to see its own target
            if (v.camera.mode == CameraMode.ORBIT) max(base, v.distance * 1.25) else base
        } else hypot(v.pixelWidth * v.zoom / 2.0, v.pixelHeight * v.zoom / 2.0) + base / 2.0
    }

    /** Orthographic views: one level for the whole view, picked by how many blocks one pixel covers. */
    fun level(ctx: RenderContext, v: ViewSpec, geo: Geometry): LevelInfo {
        val f = if (v.projection == Projection.ORTHOGRAPHIC) v.zoom else 0.0
        return geo.levels.lastOrNull { it.blocksPerCell <= f } ?: geo.levels.first()
    }

    /**
     * Distance (blocks) from the camera out to which the finest level is used in a perspective view.
     * Automatic: the distance at which one map pixel is as wide as a cell of the next coarser level.
     */
    fun sharpDistance(ctx: RenderContext, v: ViewSpec, geo: Geometry): Double {
        if (v.options.detailDistance > 0) return v.options.detailDistance.toDouble()
        if (geo.levels.size < 2) return Double.MAX_VALUE
        val tanHalf = tan(Math.toRadians(v.fov) / 2.0)
        return geo.levels[1].blocksPerCell * v.pixelHeight / (2.0 * tanHalf) * ctx.settings.detailBias
    }

    /** Distance the camera is placed behind the target (3D distance along the view direction). */
    private fun back(ctx: RenderContext, v: ViewSpec): Double = when {
        v.camera.mode == CameraMode.FREE -> 0.0
        v.projection == Projection.PERSPECTIVE -> v.distance
        else -> reach(ctx, v) // orthographic: far enough back to see terrain between the camera and the target
    }

    private fun cameraXZ(ctx: RenderContext, v: ViewSpec, basis: CameraBasis): Pair<Double, Double> {
        if (v.camera.mode == CameraMode.FREE) return v.camera.x to v.camera.z
        val b = back(ctx, v)
        return (v.camera.x - basis.fx * b) to (v.camera.z - basis.fz * b)
    }

    /**
     * The distance bands of a view and the tiles each one needs. Perspective views over a source with several
     * levels use the finest level near the camera and coarser levels further out; everything else is one band.
     */
    fun rings(ctx: RenderContext, v: ViewSpec, geo: Geometry): List<Ring> {
        val basis = CameraBasis(v.camera.yaw, v.camera.tilt)
        val reach = reach(ctx, v)
        val (camX, camZ) = cameraXZ(ctx, v, basis)
        if (v.projection != Projection.PERSPECTIVE) {
            val lvl = level(ctx, v, geo)
            return listOf(Ring(lvl, 0.0, reach, TilePlan.forBounds(lvl, v.camera.x - reach - 1, v.camera.x + reach + 1, v.camera.z - reach - 1, v.camera.z + reach + 1)))
        }
        val levels = geo.levels
        val out = ArrayList<Ring>()
        val sharp = sharpDistance(ctx, v, geo)
        var inner = 0.0
        for (k in levels.indices) {
            val lvl = levels[k]
            val last = k == levels.size - 1
            // the finest band ends at the sharp distance; each coarser band reaches as many times further as its cells are bigger
            val outer = if (last) reach else min(reach, sharp * levels[k + 1].blocksPerCell / levels[1.coerceAtMost(levels.size - 1)].blocksPerCell.toDouble())
            if (outer <= inner + 1e-6) continue
            out.add(Ring(lvl, inner, outer, TilePlan.forBounds(lvl, camX - outer - 1, camX + outer + 1, camZ - outer - 1, camZ + outer + 1)))
            inner = outer
            if (inner >= reach) break
        }
        return out
    }

    fun totalTiles(rings: List<Ring>): Long = rings.sumOf { it.plan.count }

    fun render(ctx: RenderContext, v: ViewSpec, geo: Geometry): RenderResult {
        val rings = rings(ctx, v, geo)
        val total = totalTiles(rings)
        if (total > ctx.settings.maxTilesPerRender)
            throw IllegalArgumentException("This view needs $total tiles and the limit is ${ctx.settings.maxTilesPerRender}. Lower the view distance or the sharp distance, or zoom in.")
        val sets = rings.map { RingSet(it, TileSet.load(ctx, v.source.target, it.plan)) }
        return render(ctx, v, geo, sets)
    }

    fun render(ctx: RenderContext, v: ViewSpec, geo: Geometry, rs: List<RingSet>): RenderResult {
        val w = v.pixelWidth
        val h = v.pixelHeight
        val ss = v.options.supersample.coerceIn(1, 3)
        val opts = v.options
        val basis = CameraBasis(v.camera.yaw, v.camera.tilt)
        val reach = reach(ctx, v)
        val perspective = v.projection == Projection.PERSPECTIVE
        val voidColor = geo.voidColor ?: ctx.settings.voidColor
        val skyColor = ctx.settings.skyColorOverride ?: geo.skyColor ?: SKY_DEFAULT

        // --- camera position -------------------------------------------------------------------------------
        var found = 64.0
        if (v.camera.y == null) {
            // terrain height under the target, from the finest loaded level that has it
            for (r in rs) {
                val cur = r.set.Cursor()
                val idx = cur.locate(floor(v.camera.x / r.set.bpc).toInt(), floor(v.camera.z / r.set.bpc).toInt())
                if (idx >= 0 && (cur.cur!!.color[idx] ushr 24) != 0) { found = cur.cur!!.height!![idx] + 1.0; break }
            }
        }
        val ty: Double = v.camera.y ?: found
        val camX: Double; val camY: Double; val camZ: Double
        if (v.camera.mode == CameraMode.FREE) {
            camX = v.camera.x; camY = ty; camZ = v.camera.z
        } else {
            val back = back(ctx, v)
            camX = v.camera.x - basis.fx * back
            camY = ty - basis.fy * back
            camZ = v.camera.z - basis.fz * back
        }
        val orthoBack = back(ctx, v)

        // --- lighting ---------------------------------------------------------------------------------------
        val az = Math.toRadians(opts.sunAzimuth)
        val el = Math.toRadians(opts.sunElevation)
        val lx = sin(az) * cos(el); val ly = sin(el); val lz = -cos(az) * cos(el)
        val ambient = 0.45
        val topNorm = ambient + (1 - ambient) * ly // top faces render at their true color
        val tanHalf = tan(Math.toRadians(v.fov) / 2.0)
        val aspect = w.toDouble() / h
        val zoom = v.zoom
        val sw = w * ss
        val sh = h * ss
        val out = IntArray(w * h)
        val inv = 1.0 / (ss * ss)

        parallelRows(ctx.pool, h) { py ->
            val tracers = rs.map { Tracer(it.set, reach * 2, it.set.bpc) }
            val shadowTracers = if (opts.shadows) rs.map { Tracer(it.set, 96.0, it.set.bpc) } else null
            for (px in 0 until w) {
                var accR = 0; var accG = 0; var accB = 0
                for (j in 0 until ss) for (i in 0 until ss) {
                    // normalised device coordinates of this sub-sample, nx right, ny up
                    val nx = ((px * ss + i + 0.5) / sw) * 2.0 - 1.0
                    val ny = 1.0 - ((py * ss + j + 0.5) / sh) * 2.0
                    val ox: Double; val oy: Double; val oz: Double
                    var dx: Double; var dy: Double; var dz: Double
                    if (perspective) {
                        ox = camX; oy = camY; oz = camZ
                        dx = basis.fx + basis.rx * nx * tanHalf * aspect + basis.ux * ny * tanHalf
                        dy = basis.fy + basis.ry * nx * tanHalf * aspect + basis.uy * ny * tanHalf
                        dz = basis.fz + basis.rz * nx * tanHalf * aspect + basis.uz * ny * tanHalf
                        val len = sqrt(dx * dx + dy * dy + dz * dz)
                        dx /= len; dy /= len; dz /= len
                    } else {
                        val sx = nx * w * zoom / 2.0
                        val sy = ny * h * zoom / 2.0
                        ox = camX + basis.rx * sx + basis.ux * sy
                        oy = camY + basis.ry * sx + basis.uy * sy
                        oz = camZ + basis.rz * sx + basis.uz * sy
                        dx = basis.fx; dy = basis.fy; dz = basis.fz
                    }
                    val horiz = hypot(dx, dz)
                    // perspective: reach is measured from the camera; orthographic: from the target plane
                    var hit = -1
                    if (perspective) {
                        // nearest band first; a ray only moves on to a coarser band if it found nothing in the finer one
                        for (k in rs.indices) {
                            val r = rs[k].ring
                            if (k > 0 && horiz < 1e-9) break
                            val tIn = if (k == 0) 0.0 else r.inner / horiz
                            val tOut = if (horiz < 1e-9) 1e9 else r.outer / horiz
                            if (tracers[k].trace(ox, oy, oz, dx, dy, dz, tOut, tIn)) { hit = k; break }
                        }
                    } else {
                        val tLimit = orthoBack + reach / max(horiz, 1e-6)
                        if (tracers[0].trace(ox, oy, oz, dx, dy, dz, tLimit)) hit = 0
                    }
                    val color: Int
                    if (hit >= 0) {
                        val tracer = tracers[hit]
                        val shadowTracer = shadowTracers?.get(hit)
                        val t = tracer.hitT
                        val tile = tracer.hitTile!!
                        val idx = tracer.hitIdx
                        var base = tile.color[idx]
                        val top = tracer.hitTop
                        val face = tracer.hitFace // 0 = top, 1 = x side, 2 = z side
                        val hy = oy + dy * t
                        var nxn = 0.0; var nyn = 0.0; var nzn = 0.0
                        when (face) {
                            0 -> nyn = 1.0
                            1 -> nxn = -tracer.hitStep.toDouble()
                            else -> nzn = -tracer.hitStep.toDouble()
                        }
                        if (face != 0) {
                            val depth = (top - hy).coerceAtLeast(0.0)
                            val mix = (min(1.0, max(0.0, (depth - 1.0) / 3.0)) * 0.6)
                            base = lerpColor(base, CLIFF_COLOR, mix)
                        }
                        var lit = ambient + (1 - ambient) * max(0.0, nxn * lx + nyn * ly + nzn * lz)
                        if (shadowTracer != null && lit > ambient + 1e-6) {
                            val hx = ox + dx * t + nxn * 0.02
                            val hyy = hy + nyn * 0.02
                            val hz = oz + dz * t + nzn * 0.02
                            if (shadowTracer.trace(hx, hyy, hz, lx, ly, lz, 96.0)) lit = ambient
                        }
                        var k = lit / topNorm
                        val bl = tile.light?.get(idx)?.toInt() ?: 0
                        k += bl / 15.0 * 0.18
                        k = k.coerceIn(0.0, 1.25)
                        var r = ((base shr 16) and 255) * k
                        var g = ((base shr 8) and 255) * k
                        var b = (base and 255) * k
                        if (perspective && opts.fog > 0.0) {
                            val f = opts.fog * smooth(0.35 * reach, reach, t)
                            r += (((skyColor shr 16) and 255) - r) * f
                            g += (((skyColor shr 8) and 255) - g) * f
                            b += ((skyColor and 255) - b) * f
                        }
                        color = (min(255, r.toInt()) shl 16) or (min(255, g.toInt()) shl 8) or min(255, b.toInt())
                    } else {
                        // looking into nothing: sky when pointing up/level, void when pointing down
                        color = if (dy > -0.02) skyGradient(skyColor, dy) else voidColor
                    }
                    accR += (color shr 16) and 255; accG += (color shr 8) and 255; accB += color and 255
                }
                out[py * w + px] = (255 shl 24) or ((accR * inv).toInt().coerceIn(0, 255) shl 16) or
                    ((accG * inv).toInt().coerceIn(0, 255) shl 8) or (accB * inv).toInt().coerceIn(0, 255)
            }
        }
        return RenderResult(out, w, h)
    }

    private fun smooth(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun skyGradient(sky: Int, dy: Double): Int {
        // slightly lighter towards the horizon
        val up = dy.coerceIn(0.0, 1.0)
        val f = 0.22 * (1 - up)
        return lerpColor(sky, 0xFFFFFF, f)
    }

    private fun lerpColor(a: Int, b: Int, t: Double): Int {
        val r = ((a shr 16) and 255) + (((b shr 16) and 255) - ((a shr 16) and 255)) * t
        val g = ((a shr 8) and 255) + (((b shr 8) and 255) - ((a shr 8) and 255)) * t
        val bl = (a and 255) + ((b and 255) - (a and 255)) * t
        return (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or bl.toInt().coerceIn(0, 255)
    }

    /**
     * Traverses the column grid along a ray. A column at cell (ix, iz) is solid from -infinity up to
     * `top = height + 1`. Not thread-safe; use one per thread.
     */
    class Tracer(private val set: TileSet, private val maxReach: Double, private val cs: Int) {
        private val cursor = set.Cursor()
        var hitT = 0.0
        var hitIdx = 0
        var hitTile: dev.etran.livemaps.source.RawTile? = null
        var hitTop = 0.0
        /** 0 = column top, 1 = side crossed along x, 2 = side crossed along z */
        var hitFace = 0
        /** Sign of the grid step that entered the hit cell (for side faces). */
        var hitStep = 0

        fun trace(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, tLimit: Double, tStart: Double = 0.0): Boolean {
            val yMax = set.maxTop.toDouble()
            val yMin = set.minTop.toDouble()
            var t = tStart
            var tEnd = tLimit
            if (oy > yMax) {
                if (dy >= 0.0) return false
                t = max(t, (yMax - oy) / dy)
            }
            if (dy < 0.0) tEnd = min(tEnd, (yMin - oy) / dy)
            else if (dy > 0.0) tEnd = min(tEnd, (yMax - oy) / dy)
            if (t >= tEnd) return false

            val c = cs.toDouble()
            val px = ox + dx * t
            val pz = oz + dz * t
            var ix = floor(px / c).toInt()
            var iz = floor(pz / c).toInt()
            val stepX = if (dx > 0) 1 else -1
            val stepZ = if (dz > 0) 1 else -1
            val tDeltaX = if (dx == 0.0) Double.POSITIVE_INFINITY else c / abs(dx)
            val tDeltaZ = if (dz == 0.0) Double.POSITIVE_INFINITY else c / abs(dz)
            var tMaxX = if (dx == 0.0) Double.POSITIVE_INFINITY else ((if (dx > 0) (ix + 1) * c else ix * c) - ox) / dx
            var tMaxZ = if (dz == 0.0) Double.POSITIVE_INFINITY else ((if (dz > 0) (iz + 1) * c else iz * c) - oz) / dz
            var tEnter = t
            var face = 0
            var step = 0
            var first = true
            var guard = ((maxReach / c) * 2 + 64).toInt() + 16

            while (guard-- > 0) {
                val tExit = min(min(tMaxX, tMaxZ), tEnd)
                val idx = cursor.locate(ix, iz)
                if (idx >= 0) {
                    val tile = cursor.cur!!
                    if ((tile.color[idx] ushr 24) != 0) {
                        val top = tile.height!![idx] + 1.0
                        val yEnter = oy + dy * tEnter
                        if (yEnter <= top) {
                            record(tEnter, idx, tile, top, if (first) 0 else face, step)
                            return true
                        }
                        val yExit = oy + dy * tExit
                        if (yExit <= top) {
                            record((top - oy) / dy, idx, tile, top, 0, 0)
                            return true
                        }
                    }
                }
                if (tExit >= tEnd) return false
                if (tMaxX < tMaxZ) {
                    ix += stepX; tEnter = tMaxX; tMaxX += tDeltaX; face = 1; step = stepX
                } else {
                    iz += stepZ; tEnter = tMaxZ; tMaxZ += tDeltaZ; face = 2; step = stepZ
                }
                first = false
            }
            return false
        }

        private fun record(t: Double, idx: Int, tile: dev.etran.livemaps.source.RawTile, top: Double, face: Int, step: Int) {
            hitT = t; hitIdx = idx; hitTile = tile; hitTop = top; hitFace = face; hitStep = step
        }
    }
}
