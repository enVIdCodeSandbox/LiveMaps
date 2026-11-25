package dev.etran.livemaps.core

import dev.etran.livemaps.model.ViewSpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray

/** Map-palette pixel data for a whole live map: one 128x128 buffer per tile plus a thumbnail. */
class RenderOutput(val tiles: Array<ByteArray>, val thumb: ByteArray, val fingerprint: Long)

/** One live map: its view, the Bukkit map ids backing its tiles, and the latest rendered pixels. */
class LiveMap(
    val id: Int,
    val owner: UUID,
    val ownerName: String,
    val createdAt: Long,
    @Volatile var spec: ViewSpec,
    /** Bukkit map ids, row-major (row * width + col). */
    val tileMapIds: IntArray,
    val thumbMapId: Int,
) {
    val width get() = spec.widthMaps
    val height get() = spec.heightMaps
    val tileCount get() = tileMapIds.size

    /** index [tileCount] is the thumbnail */
    private val buffers = AtomicReferenceArray<ByteArray>(tileCount + 1)
    private val versions = AtomicIntegerArray(tileCount + 1)

    @Volatile var fingerprint: Long = Long.MIN_VALUE
    @Volatile var lastCheck: Long = 0
    @Volatile var lastRenderedAt: Long = 0
    @Volatile var lastRenderMs: Long = 0
    @Volatile var lastError: String? = null
    @Volatile var dirtyForDisk = false
    val busy = AtomicBoolean(false)

    fun buffer(index: Int): ByteArray? = buffers.get(index)
    fun version(index: Int): Int = versions.get(index)
    val hasContent get() = buffers.get(tileCount) != null

    /** Stores new pixels; only tiles whose content actually changed get a new version (so unchanged maps cost nothing). */
    fun apply(out: RenderOutput): Int {
        var changed = 0
        for (i in 0 until tileCount) if (set(i, out.tiles[i])) changed++
        set(tileCount, out.thumb)
        fingerprint = out.fingerprint
        lastRenderedAt = System.currentTimeMillis()
        lastError = null
        if (changed > 0) dirtyForDisk = true
        return changed
    }

    private fun set(i: Int, data: ByteArray): Boolean {
        val old = buffers.get(i)
        if (old != null && old.contentEquals(data)) return false
        buffers.set(i, data)
        versions.incrementAndGet(i)
        return true
    }

    fun restore(out: RenderOutput) {
        for (i in 0 until tileCount) { buffers.set(i, out.tiles[i]); versions.incrementAndGet(i) }
        buffers.set(tileCount, out.thumb); versions.incrementAndGet(tileCount)
    }

    fun snapshot(): RenderOutput? {
        if (!hasContent) return null
        val tiles = Array(tileCount) { buffers.get(it) ?: return null }
        return RenderOutput(tiles, buffers.get(tileCount)!!, fingerprint)
    }
}
