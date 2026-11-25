package dev.etran.livemaps.core

import org.bukkit.entity.Player
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView

/**
 * Draws the latest pixels of one live-map tile (or its thumbnail, index == tileCount) onto a Bukkit map.
 * Non-contextual: one shared canvas, redrawn only when a new version has been rendered, so idle maps cost nothing.
 */
class TileRenderer(private val live: LiveMap, private val index: Int) : MapRenderer(false) {
    @Volatile private var drawn = -1

    override fun render(view: MapView, canvas: MapCanvas, player: Player) {
        val v = live.version(index)
        if (v == drawn) return
        val buf = live.buffer(index) ?: return
        drawn = v
        for (y in 0 until 128) {
            val o = y * 128
            for (x in 0 until 128) canvas.setPixel(x, y, buf[o + x])
        }
    }
}
