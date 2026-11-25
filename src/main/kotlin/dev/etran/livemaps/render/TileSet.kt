package dev.etran.livemaps.render

import dev.etran.livemaps.source.Geometry
import dev.etran.livemaps.source.LevelInfo
import dev.etran.livemaps.source.RawTile
import dev.etran.livemaps.source.TileSource
import java.util.concurrent.ForkJoinPool
import java.util.stream.IntStream
import kotlin.math.floor

class RenderSettings(
    /** How far (blocks) 3D views may look. */
    val viewDistance: Int = 384,
    val voidColor: Int = 0xFF14141E.toInt(),
    val skyColorOverride: Int? = null,
    val maxTilesPerRender: Int = 64,
    /** Upper limit for a per-map view distance. */
    val maxViewDistance: Int = 4096,
    /** Automatic detail: 1 = a source cell is about one map pixel wide, >1 = finer (sharper, slower). */
    val detailBias: Double = 1.0,
)

class RenderContext(
    val store: TileStore,
    val source: TileSource,
    val settings: RenderSettings,
    val pool: ForkJoinPool?,
    /** How old a tile existence/stamp check may be (ms) before it's re-checked. */
    val maxAgeMs: Long = 1000,
)

class RenderResult(val argb: IntArray, val width: Int, val height: Int)

/** Range of tiles (at one level) a view needs. */
class TilePlan(val level: LevelInfo, val minTx: Int, val maxTx: Int, val minTz: Int, val maxTz: Int) {
    val count: Long get() = (maxTx - minTx + 1).toLong() * (maxTz - minTz + 1)

    companion object {
        fun forBounds(level: LevelInfo, minX: Double, maxX: Double, minZ: Double, maxZ: Double): TilePlan {
            val span = level.span.toDouble()
            return TilePlan(
                level,
                floor(minX / span).toInt(), floor(maxX / span).toInt(),
                floor(minZ / span).toInt(), floor(maxZ / span).toInt(),
            )
        }
    }
}

/** Immutable set of tiles loaded for one render. Safe to read from many threads. */
class TileSet(val level: LevelInfo, private val tiles: Map<Long, RawTile>) {
    val cells = level.cellsPerTile
    val bpc = level.blocksPerCell
    val isEmpty get() = tiles.isEmpty()

    val maxTop: Float = (tiles.values.maxOfOrNull { it.maxTop } ?: 64).toFloat() + 1f
    val minTop: Float = (tiles.values.minOfOrNull { it.minTop } ?: 0).toFloat() - 1f

    fun tile(tx: Int, tz: Int): RawTile? = tiles[key(tx, tz)]

    /** Cheap sequential cell accessor (not thread-safe, create one per thread). */
    inner class Cursor {
        private var lastKey = Long.MIN_VALUE
        private var lastTile: RawTile? = null
        var cur: RawTile? = null

        /** Returns the index of cell (ix, iz) within [cur], or -1 if that tile isn't loaded. */
        fun locate(ix: Int, iz: Int): Int {
            val tx = Math.floorDiv(ix, cells)
            val tz = Math.floorDiv(iz, cells)
            val k = key(tx, tz)
            if (k != lastKey) { lastKey = k; lastTile = tiles[k] }
            val t = lastTile ?: return -1
            cur = t
            return (iz - tz * cells) * cells + (ix - tx * cells)
        }
    }

    companion object {
        fun key(tx: Int, tz: Int): Long = (tx.toLong() shl 32) or (tz.toLong() and 0xFFFFFFFFL)

        fun load(ctx: RenderContext, target: String, plan: TilePlan): TileSet {
            if (plan.count > ctx.settings.maxTilesPerRender)
                throw IllegalArgumentException("View needs ${plan.count} tiles, limit is ${ctx.settings.maxTilesPerRender}. Zoom in or reduce the view distance.")
            val m = HashMap<Long, RawTile>()
            for (tz in plan.minTz..plan.maxTz) for (tx in plan.minTx..plan.maxTx) {
                ctx.store.get(ctx.source, target, plan.level, tx, tz, ctx.maxAgeMs)?.let { m[key(tx, tz)] = it }
            }
            return TileSet(plan.level, m)
        }

        /** Combined change token of all tiles in the plan. */
        fun fingerprint(ctx: RenderContext, target: String, plan: TilePlan): Long {
            if (plan.count > ctx.settings.maxTilesPerRender) return -1
            var h = 1125899906842597L
            for (tz in plan.minTz..plan.maxTz) for (tx in plan.minTx..plan.maxTx) {
                val st = ctx.store.get(ctx.source, target, plan.level, tx, tz, 0)?.stamp ?: -1L
                h = 31 * h + st
            }
            return h
        }
    }
}

internal fun parallelRows(pool: ForkJoinPool?, rows: Int, body: (Int) -> Unit) {
    if (pool == null) {
        for (y in 0 until rows) body(y)
        return
    }
    val t = Thread.currentThread()
    if (t is java.util.concurrent.ForkJoinWorkerThread && t.pool === pool) {
        // already running inside our pool: a parallel stream here uses this same pool
        IntStream.range(0, rows).parallel().forEach { body(it) }
    } else {
        pool.submit { IntStream.range(0, rows).parallel().forEach { body(it) } }.get()
    }
}
