package dev.etran.livemaps.source

import com.google.gson.JsonParser
import dev.etran.livemaps.model.SourceType
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class SquaremapState(val webDir: Path, val worlds: List<TargetInfo>)

/**
 * Reads squaremap's rendered tiles (`<web>/tiles/<world>/<zoom>/<x>_<z>.png`, 512px, highest zoom = 1 block per pixel).
 * Reads files only; it never asks squaremap to render anything.
 */
class SquaremapTileSource(
    private val enabled: () -> Boolean,
    private val state: () -> SquaremapState?,
) : TileSource {

    override val type = SourceType.SQUAREMAP

    private data class CachedGeometry(val geometry: Geometry?, val at: Long)
    private val geometryCache = ConcurrentHashMap<String, CachedGeometry>()

    override fun isReady() = enabled() && state() != null
    override fun targets(): List<TargetInfo> = state()?.worlds ?: emptyList()

    fun clearCaches() = geometryCache.clear()

    private fun worldDir(target: String): Path? {
        val tiles = state()?.webDir?.resolve("tiles") ?: return null
        // squaremap names folders after the world identifier with ':' replaced by '_'
        val candidates = listOf(target.replace(":", "_"), target.substringAfter(':'))
        return candidates.map { tiles.resolve(it) }.firstOrNull { Files.isDirectory(it) }
    }

    override fun geometry(target: String): Geometry? {
        val now = System.currentTimeMillis()
        val cached = geometryCache[target]
        if (cached != null && now - cached.at < 60_000 && cached.geometry != null) return cached.geometry
        val g = try { readGeometry(target) } catch (_: Exception) { null }
        geometryCache[target] = CachedGeometry(g, now)
        return g ?: cached?.geometry
    }

    private fun readGeometry(target: String): Geometry? {
        val dir = worldDir(target) ?: return null
        var maxZoom = -1
        val settings = dir.resolve("settings.json")
        if (Files.isRegularFile(settings)) {
            maxZoom = JsonParser.parseString(String(Files.readAllBytes(settings), Charsets.UTF_8))
                .asJsonObject.getAsJsonObject("zoom")?.get("max")?.asInt ?: -1
        }
        if (maxZoom < 0) {
            maxZoom = Files.list(dir).use { s ->
                s.map { it.fileName.toString().toIntOrNull() }.filter { it != null }.map { it!! }.toList().maxOrNull()
            } ?: return null
        }
        // zoom `maxZoom` is 1 block per pixel, each lower zoom halves the resolution
        val levels = (0..maxZoom).map { k -> LevelInfo(maxZoom - k, 1 shl k, 512) }
        return Geometry(levels, false, null, null)
    }

    private fun tileFile(target: String, level: LevelInfo, tx: Int, tz: Int): Path? =
        worldDir(target)?.resolve(level.index.toString())?.resolve("${tx}_$tz.png")

    override fun stamp(target: String, level: LevelInfo, tx: Int, tz: Int): Long? {
        val f = tileFile(target, level, tx, tz) ?: return -1
        return try {
            if (!Files.isRegularFile(f)) -1 else Files.getLastModifiedTime(f).toMillis() * 31 + Files.size(f)
        } catch (_: IOException) { -1 }
    }

    override fun load(target: String, level: LevelInfo, tx: Int, tz: Int): RawTile? {
        val f = tileFile(target, level, tx, tz) ?: return null
        if (!Files.isRegularFile(f)) return null
        val stamp = Files.getLastModifiedTime(f).toMillis() * 31 + Files.size(f)
        val bytes = try { Files.readAllBytes(f) } catch (_: java.nio.file.NoSuchFileException) { return null }
        return PngTiles.decodeSquare(bytes, stamp)
    }
}
