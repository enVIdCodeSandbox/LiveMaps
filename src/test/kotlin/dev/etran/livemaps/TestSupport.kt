package dev.etran.livemaps

import dev.etran.livemaps.model.*
import dev.etran.livemaps.render.RenderContext
import dev.etran.livemaps.render.RenderSettings
import dev.etran.livemaps.render.TileStore
import dev.etran.livemaps.source.*

/** In-memory tile source: one level (1 block per cell, 500 cells per tile) unless more are given. */
class FakeSource(
    private val tiles: MutableMap<Triple<Int, Int, Int>, RawTile> = HashMap(),
    val levels: List<LevelInfo> = listOf(LevelInfo(1, 1, 64)),
    val hasHeight: Boolean = true,
) : TileSource {
    override val type = SourceType.BLUEMAP
    override fun isReady() = true
    override fun targets() = listOf(TargetInfo("t", "T", null))
    override fun geometry(target: String) = Geometry(levels, hasHeight, null, null)
    override fun stamp(target: String, level: LevelInfo, tx: Int, tz: Int): Long? = tiles[Triple(level.index, tx, tz)]?.stamp ?: -1L
    override fun load(target: String, level: LevelInfo, tx: Int, tz: Int) = tiles[Triple(level.index, tx, tz)]
    fun put(level: Int, tx: Int, tz: Int, t: RawTile) { tiles[Triple(level, tx, tz)] = t }
}

fun ctx(src: TileSource, view: Int = 200) = RenderContext(TileStore(64), src, RenderSettings(viewDistance = view, maxTilesPerRender = 400), null, 0)

/** A tile of [cells]^2 where color(x,z) and height(x,z) are produced by the given functions (local coordinates). */
fun makeTile(cells: Int, stamp: Long = 1, color: (Int, Int) -> Int, height: ((Int, Int) -> Int)? = {_, _ -> 63}): RawTile {
    val c = IntArray(cells * cells)
    val h = if (height != null) ShortArray(cells * cells) else null
    for (z in 0 until cells) for (x in 0 until cells) {
        c[z * cells + x] = color(x, z)
        if (h != null) h[z * cells + x] = height!!(x, z).toShort()
    }
    return RawTile(cells, c, h, if (h != null) ByteArray(cells * cells) else null, stamp)
}

fun view(
    x: Double, z: Double,
    mode: CameraMode = CameraMode.FLAT, y: Double? = null,
    yaw: Double = 0.0, tilt: Double = 0.0,
    projection: Projection = Projection.ORTHOGRAPHIC,
    zoom: Double = 1.0, distance: Double = 64.0,
    w: Int = 1, h: Int = 1,
    options: ViewOptions = ViewOptions(shading = 0.0, supersample = 1, fog = 0.0),
) = ViewSpec(SourceRef(SourceType.BLUEMAP, "t"), Camera(mode, x, y, z, yaw, tilt), projection, zoom, distance, 70.0, w, h, options)

fun rgb(c: Int) = c and 0xFFFFFF
