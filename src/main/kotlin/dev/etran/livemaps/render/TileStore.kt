package dev.etran.livemaps.render

import dev.etran.livemaps.model.SourceType
import dev.etran.livemaps.source.LevelInfo
import dev.etran.livemaps.source.RawTile
import dev.etran.livemaps.source.TileSource

/**
 * Small LRU cache of decoded tiles. Change detection is by tile stamp (file mtime/size, or content CRC for HTTP),
 * so unchanged tiles are never re-decoded and renders can be skipped entirely when nothing changed.
 */
class TileStore(private val maxTiles: Int) {

    data class Key(val type: SourceType, val target: String, val level: Int, val tx: Int, val tz: Int)

    private class Entry {
        @Volatile var loaded = false
        @Volatile var tile: RawTile? = null
        @Volatile var checkedAt = 0L
    }

    private val map = object : LinkedHashMap<Key, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?) = size > maxTiles
    }

    /**
     * Returns the tile, or null if it does not exist. [maxAgeMs] is how long a previous existence/stamp check may be trusted.
     * If reloading fails, the previously cached tile (if any) is returned.
     */
    fun get(source: TileSource, target: String, level: LevelInfo, tx: Int, tz: Int, maxAgeMs: Long): RawTile? {
        val key = Key(source.type, target, level.index, tx, tz)
        val entry = synchronized(map) { map.getOrPut(key) { Entry() } }
        synchronized(entry) {
            val now = System.currentTimeMillis()
            if (entry.loaded && now - entry.checkedAt <= maxAgeMs) return entry.tile
            val stamp = try { source.stamp(target, level, tx, tz) } catch (_: Exception) { null }
            if (entry.loaded && stamp != null && stamp == (entry.tile?.stamp ?: -1L)) {
                entry.checkedAt = now
                return entry.tile
            }
            try {
                val fresh = source.load(target, level, tx, tz)
                val old = entry.tile
                if (!(old != null && fresh != null && old.stamp == fresh.stamp)) entry.tile = fresh
                entry.loaded = true
            } catch (_: Exception) {
                // keep serving the previous tile; BlueMap/squaremap may be mid-write
                if (!entry.loaded) { entry.tile = null; entry.loaded = true }
            }
            entry.checkedAt = now
            return entry.tile
        }
    }

    fun clear() = synchronized(map) { map.clear() }
}
