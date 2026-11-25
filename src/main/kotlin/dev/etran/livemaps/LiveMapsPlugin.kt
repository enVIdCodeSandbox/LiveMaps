package dev.etran.livemaps

import dev.etran.livemaps.core.Listeners
import dev.etran.livemaps.core.LiveMapService
import dev.etran.livemaps.core.PlacementService
import dev.etran.livemaps.cmd.LiveMapCommand
import dev.etran.livemaps.model.SourceType
import dev.etran.livemaps.source.BlueMapState
import dev.etran.livemaps.source.BlueMapTileSource
import dev.etran.livemaps.source.SquaremapState
import dev.etran.livemaps.source.SquaremapTileSource
import dev.etran.livemaps.source.TargetInfo
import dev.etran.livemaps.source.TileSource
import dev.etran.livemaps.web.WebServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level

class LiveMapsPlugin : JavaPlugin() {

    lateinit var cfg: PluginConfig
        private set
    lateinit var service: LiveMapService
        private set
    lateinit var placement: PlacementService
        private set
    var web: WebServer? = null
        private set

    @Volatile var blueMapState: BlueMapState? = null
    @Volatile var squaremapState: SquaremapState? = null
    private var blueMapHook: AutoCloseable? = null
    private var squaremapRefresh: (() -> Unit)? = null

    private val bluemapSource = BlueMapTileSource({ cfg.bluemap }, { effectiveBlueMapState() })
    private val squaremapSource = SquaremapTileSource({ cfg.squaremapEnabled }, { squaremapState })

    fun source(type: SourceType): TileSource? = when (type) {
        SourceType.BLUEMAP -> bluemapSource.takeIf { cfg.bluemap.enabled }
        SourceType.SQUAREMAP -> squaremapSource.takeIf { cfg.squaremapEnabled }
    }

    /** Finds BlueMap's local web files on disk (via BlueMapAPI or common plugin directories). */
    fun detectBlueMapWebRoot(): Path? {
        blueMapState?.webRoot?.takeIf { Files.isDirectory(it) }?.let { return it }
        val candidates = listOfNotNull(
            dataFolder.toPath().toAbsolutePath().parent?.resolve("BlueMap/web"),
            Path.of("plugins/BlueMap/web").toAbsolutePath(),
            Path.of("bluemap/web").toAbsolutePath(),
        )
        return candidates.firstOrNull { root ->
            Files.isDirectory(root) && (
                Files.isRegularFile(root.resolve("index.html")) ||
                Files.isRegularFile(root.resolve("index.html.gz"))
            )
        }
    }

    /** Returns live BlueMapAPI state if available, otherwise synthesizes read-only state from local web files. */
    fun effectiveBlueMapState(): BlueMapState? {
        blueMapState?.let { return it }
        if (!cfg.bluemap.enabled) return null
        val root = detectBlueMapWebRoot() ?: return null
        val mapsDir = root.resolve("maps")
        val maps = if (Files.isDirectory(mapsDir)) {
            try {
                Files.list(mapsDir).use { stream ->
                    stream.filter { Files.isDirectory(it) }
                        .map { it.fileName.toString() }
                        .sorted()
                        .map { TargetInfo(it, it, null) }
                        .toList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return BlueMapState(root, maps)
    }

    override fun onEnable() {
        saveDefaultConfig()
        cfg = PluginConfig(config)
        service = LiveMapService(this)
        placement = PlacementService(this, service)

        hookSources()
        service.start()
        server.pluginManager.registerEvents(Listeners(this, service, placement), this)

        val cmd = LiveMapCommand(this)
        getCommand("livemap")?.setExecutor(cmd)
        getCommand("livemap")?.tabCompleter = cmd

        registerRecipe()
        server.scheduler.runTaskTimer(this, Runnable { service.tick() }, 40L, 20L)
        server.scheduler.runTaskTimer(this, Runnable { squaremapRefresh?.invoke() }, 100L, 600L)
        startWeb()
        val bmDetected = blueMapHook != null || detectBlueMapWebRoot() != null
        logger.info("LiveMaps enabled. BlueMap: ${if (bmDetected) "found" else "not found"}, squaremap: ${if (squaremapRefresh != null) "found" else "not found"}.")
    }

    override fun onDisable() {
        web?.stop()
        web = null
        try { blueMapHook?.close() } catch (_: Throwable) {}
        runCatching { server.removeRecipe(service.items.designerRecipeKey) }
        if (::service.isInitialized) service.stop()
    }

    /** Re-reads config.yml and restarts the parts that depend on it. */
    fun reload() {
        reloadConfig()
        cfg = PluginConfig(config)
        bluemapSource.clearCaches()
        squaremapSource.clearCaches()
        service.applyConfig()
        runCatching { server.removeRecipe(service.items.designerRecipeKey) }
        registerRecipe()
        web?.stop()
        startWeb()
    }

    private fun hookSources() {
        val pm = server.pluginManager
        if (pm.getPlugin("BlueMap") != null) {
            try { blueMapHook = Hooking.blueMap(this) } catch (t: Throwable) { logger.log(Level.WARNING, "Could not hook into BlueMap", t) }
        }
        if (pm.getPlugin("squaremap") != null) {
            try {
                squaremapRefresh = Hooking.squaremap(this)
                squaremapRefresh?.invoke()
            } catch (t: Throwable) { logger.log(Level.WARNING, "Could not hook into squaremap", t) }
        }
        if (blueMapHook == null && squaremapRefresh == null && detectBlueMapWebRoot() == null)
            logger.warning("Neither BlueMap nor squaremap was found. LiveMaps needs at least one of them to have anything to show.")
    }

    private fun registerRecipe() {
        if (!cfg.craftingEnabled) return
        try {
            val r = ShapedRecipe(service.items.designerRecipeKey, service.items.designer())
            r.shape(*cfg.recipeShape.toTypedArray())
            val used = cfg.recipeShape.joinToString("").toSet()
            for ((ch, mat) in cfg.recipeIngredients) if (ch in used) r.setIngredient(ch, mat)
            server.addRecipe(r)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "The crafting recipe in config.yml is invalid; the Live Map Designer cannot be crafted", e)
        }
    }

    private fun startWeb() {
        if (!cfg.webEnabled) return
        try {
            web = WebServer(this).also { it.start() }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Could not start the web app on ${cfg.webBind}:${cfg.webPort}", e)
        }
    }

    // ------------------------------------------------------------------------------------------------

    fun webBaseUrl(): String =
        cfg.webPublicUrl.ifEmpty { "http://${Bukkit.getIp().ifBlank { "localhost" }}:${cfg.webPort}" }

    fun sendWebLink(player: Player) {
        val w = web
        if (w == null) { Msg.err(player, "The web app is disabled on this server."); return }
        if (!player.hasPermission("livemaps.use")) { Msg.err(player, "You don't have permission to use LiveMaps."); return }
        val url = "${webBaseUrl()}/auth?t=${w.auth.newLink(player)}"
        Msg.ok(player, "Your personal link (valid for ${cfg.linkMinutes} minutes, only works once):")
        player.sendMessage(
            Component.text("  [ Open the Live Map web app ]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url))
        )
    }
}

/** Isolates the classes that reference the BlueMap/squaremap APIs so they only load when those plugins exist. */
internal object Hooking {
    fun blueMap(plugin: LiveMapsPlugin): AutoCloseable {
        val hook = dev.etran.livemaps.source.BlueMapHook { plugin.blueMapState = it }
        hook.register()
        return AutoCloseable { hook.unregister() }
    }

    fun squaremap(plugin: LiveMapsPlugin): () -> Unit {
        val hook = dev.etran.livemaps.source.SquaremapHook { plugin.squaremapState = it }
        return { hook.refresh() }
    }
}
