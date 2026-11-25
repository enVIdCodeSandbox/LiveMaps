package dev.etran.livemaps.source

import dev.etran.livemaps.model.SourceType

/** One decoded map tile: [cells] x [cells] block columns. */
class RawTile(
    val cells: Int,
    /** Straight (non-premultiplied) ARGB per cell. Alpha 0 = nothing rendered here. */
    val color: IntArray,
    /** Top block height per cell (null when the source has no height data, e.g. squaremap). */
    val height: ShortArray?,
    /** Block light 0..15 of the top block (BlueMap only). */
    val light: ByteArray?,
    /** Content/modification token used to detect changes. */
    val stamp: Long,
) {
    /** Highest/lowest column top over all rendered cells (used to skip empty air above the terrain when ray-marching). */
    val maxTop: Int by lazy(LazyThreadSafetyMode.PUBLICATION) { scanRange(true) }
    val minTop: Int by lazy(LazyThreadSafetyMode.PUBLICATION) { scanRange(false) }

    private fun scanRange(max: Boolean): Int {
        val h = height ?: return if (max) 64 else 0
        var best = if (max) Int.MIN_VALUE else Int.MAX_VALUE
        for (i in h.indices) {
            if ((color[i] ushr 24) == 0) continue
            val v = h[i].toInt()
            if (max) { if (v > best) best = v } else { if (v < best) best = v }
        }
        return if (best == Int.MIN_VALUE || best == Int.MAX_VALUE) (if (max) 64 else 0) else best
    }
}

/**
 * One resolution level of a tile pyramid. A tile at (tx, tz) covers world blocks
 * [tx*span, (tx+1)*span) horizontally and has [cellsPerTile]^2 cells of [blocksPerCell]^2 blocks.
 */
data class LevelInfo(val index: Int, val blocksPerCell: Int, val cellsPerTile: Int) {
    val span: Int get() = blocksPerCell * cellsPerTile
}

data class Geometry(
    /** Sorted from finest (1 block per cell) to coarsest. */
    val levels: List<LevelInfo>,
    val hasHeight: Boolean,
    val skyColor: Int?,
    val voidColor: Int?,
)

data class TargetInfo(
    val id: String,
    val name: String,
    /** Bukkit world name this target belongs to, if known. */
    val world: String?,
)

interface TileSource {
    val type: SourceType

    /** True when the backing plugin is loaded and ready. */
    fun isReady(): Boolean

    fun targets(): List<TargetInfo>

    fun geometry(target: String): Geometry?

    /**
     * Cheap change token for a tile: -1 if the tile doesn't exist, null if it can't be determined without loading it.
     */
    fun stamp(target: String, level: LevelInfo, tx: Int, tz: Int): Long?

    /** Loads a tile. Returns null if the tile does not exist; throws on I/O or decode problems. */
    fun load(target: String, level: LevelInfo, tx: Int, tz: Int): RawTile?
}
