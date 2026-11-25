package dev.etran.livemaps

import dev.etran.livemaps.model.Dither
import dev.etran.livemaps.model.Upscale
import dev.etran.livemaps.model.ViewOptions
import dev.etran.livemaps.render.RenderSettings
import dev.etran.livemaps.source.BlueMapSettings
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration

enum class BreakMode { INDIVIDUAL, ENTIRE }
enum class EntireDrop { KIT, TILES }

class PluginConfig(c: FileConfiguration) {
    // web
    val webEnabled = c.getBoolean("web.enabled", true)
    val webBind = c.getString("web.bind-address", "0.0.0.0")!!
    val webPort = c.getInt("web.port", 8185).coerceIn(1, 65535)
    val webPublicUrl = c.getString("web.public-url", "")!!.trim().trimEnd('/')
    val linkMinutes = c.getInt("web.link-minutes", 10).coerceIn(1, 1440)
    val sessionMinutes = c.getInt("web.session-minutes", 120).coerceIn(1, 10080)
    val maxConcurrentPreviews = c.getInt("web.max-concurrent-previews", 2).coerceIn(1, 16)

    // limits
    val minWidth = c.getInt("limits.min-width", 1).coerceIn(1, 64)
    val minHeight = c.getInt("limits.min-height", 1).coerceIn(1, 64)
    val maxWidth = c.getInt("limits.max-width", 8).coerceIn(minWidth, 64)
    val maxHeight = c.getInt("limits.max-height", 8).coerceIn(minHeight, 64)
    val warnTotal = c.getInt("limits.warn-total-maps", 12).coerceAtLeast(0)
    val maxMapsPerPlayer = c.getInt("limits.max-live-maps-per-player", 10).coerceAtLeast(1)

    // cost
    val costEnabled = c.getBoolean("cost.enabled", true)
    val costMaterial: Material = Material.matchMaterial(c.getString("cost.material", "MAP")!!) ?: Material.MAP
    val costPerMap = c.getInt("cost.per-map", 1).coerceAtLeast(0)

    // update (default 600s = 10 minutes)
    val updateIntervalSeconds = c.getInt("update.interval-seconds", 600).coerceIn(1, 86400)
    val activeRadius = c.getInt("update.active-radius", 96).coerceIn(8, 4096)
    val renderThreads = c.getInt("update.render-threads", 2).coerceIn(1, 16)
    val tileCacheSize = c.getInt("update.tile-cache-size", 40).coerceIn(4, 512)

    // breaking
    val breakMode = enumOf(c.getString("breaking.mode"), BreakMode.INDIVIDUAL)
    val entireDrop = enumOf(c.getString("breaking.entire-drop"), EntireDrop.KIT)
    val checkProtection = c.getBoolean("placement.check-protection", true)

    // sources
    val bluemap = BlueMapSettings(
        enabled = c.getBoolean("bluemap.enabled", true),
        mode = c.getString("bluemap.tiles-mode", "AUTO")!!,
        tilesRoot = c.getString("bluemap.tiles-root", "auto")!!,
        httpBaseUrl = c.getString("bluemap.http-base-url", "")!!,
    )
    val bluemapPublicUrl = c.getString("bluemap.public-url", "")!!.trim().trimEnd('/')
    val squaremapEnabled = c.getBoolean("squaremap.enabled", true)

    // rendering
    val renderSettings = RenderSettings(
        viewDistance = c.getInt("rendering.view-distance", 384).coerceIn(32, 4096),
        voidColor = parseColor(c.getString("rendering.void-color", "#14141E")!!) ?: 0xFF14141E.toInt(),
        skyColorOverride = c.getString("rendering.sky-color", "")!!.takeIf { it.isNotBlank() }?.let { parseColor(it) },
        maxTilesPerRender = c.getInt("rendering.max-tiles-per-render", 64).coerceIn(1, 400),
        maxViewDistance = c.getInt("rendering.max-view-distance", 1536).coerceIn(32, 16384),
        detailBias = c.getDouble("rendering.detail-bias", 1.0).coerceIn(0.25, 4.0),
    )
    val defaultOptions = ViewOptions(
        shading = c.getDouble("rendering.defaults.shading", 0.6).coerceIn(0.0, 1.0),
        dither = enumOf(c.getString("rendering.defaults.dither"), Dither.FLOYD_STEINBERG),
        upscale = enumOf(c.getString("rendering.defaults.upscale"), Upscale.NEAREST),
        supersample = c.getInt("rendering.defaults.supersample", 2).coerceIn(1, 3),
        shadows = c.getBoolean("rendering.defaults.shadows", false),
        fog = c.getDouble("rendering.defaults.fog", 0.25).coerceIn(0.0, 1.0),
    )

    // crafting
    val craftingEnabled = c.getBoolean("crafting.enabled", true)
    val recipeShape: List<String> = c.getStringList("crafting.recipe.shape").takeIf { it.isNotEmpty() } ?: listOf(" M ", "RCR", " M ")
    val recipeIngredients: Map<Char, Material> = run {
        val sec = c.getConfigurationSection("crafting.recipe.ingredients")
        val m = LinkedHashMap<Char, Material>()
        sec?.getKeys(false)?.forEach { k ->
            val mat = Material.matchMaterial(sec.getString(k, "") ?: "")
            if (k.length == 1 && mat != null) m[k[0]] = mat
        }
        if (m.isEmpty()) mapOf('M' to Material.MAP, 'R' to Material.REDSTONE, 'C' to Material.COMPASS) else m
    }

    /** Total cost (in items) of a live map of the given size. */
    fun costFor(totalMaps: Int): Int = if (costEnabled) totalMaps * costPerMap else 0

    companion object {
        inline fun <reified E : Enum<E>> enumOf(s: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name.equals(s?.trim(), true) } ?: default

        fun parseColor(s: String): Int? = runCatching {
            val h = s.trim().removePrefix("#")
            (0xFF000000.toInt()) or h.takeLast(6).toInt(16)
        }.getOrNull()
    }
}
