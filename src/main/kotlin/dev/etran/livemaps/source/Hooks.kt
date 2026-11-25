package dev.etran.livemaps.source

import de.bluecolored.bluemap.api.BlueMapAPI
import org.bukkit.Bukkit
import org.bukkit.World
import xyz.jpenilla.squaremap.api.BukkitAdapter
import xyz.jpenilla.squaremap.api.SquaremapProvider
import java.util.function.Consumer

/**
 * The only classes that touch the BlueMap / squaremap APIs. They are only instantiated after the plugin manager
 * confirmed the respective plugin is present, so a server without BlueMap or squaremap never loads these types.
 */
class BlueMapHook(private val publish: (BlueMapState?) -> Unit) {
    private var state: BlueMapState? = null
        set(v) { field = v; publish(v) }

    private val enable = Consumer<BlueMapAPI> { api ->
        try {
            val worldNames = HashMap<String, String>()
            // BlueMap resolves Bukkit worlds for us; must be asked on a thread that may read worlds
            for (w in Bukkit.getWorlds()) {
                api.getWorld(w as World).ifPresent { bw -> bw.maps.forEach { worldNames[it.id] = w.name } }
            }
            val maps = api.maps.map { TargetInfo(it.id, it.name, worldNames[it.id]) }
            val reader: (String, String) -> ByteArray? = { mapId, assetPath ->
                try {
                    BlueMapAPI.getInstance()
                        .flatMap { it.getMap(mapId) }
                        .flatMap { it.assetStorage.readAsset(assetPath) }
                        .orElse(null)
                        ?.use { it.readAllBytes() }
                } catch (_: Throwable) {
                    null
                }
            }
            state = BlueMapState(api.webApp.webRoot, maps, reader)
        } catch (t: Throwable) {
            state = null
            Bukkit.getLogger().warning("[LiveMaps] Could not read BlueMap's maps: $t")
        }
    }
    private val disable = Consumer<BlueMapAPI> { state = null }

    fun register() {
        BlueMapAPI.onEnable(enable)
        BlueMapAPI.onDisable(disable)
    }

    fun unregister() {
        BlueMapAPI.unregisterListener(enable)
        BlueMapAPI.unregisterListener(disable)
        state = null
    }
}

class SquaremapHook(private val publish: (SquaremapState?) -> Unit) {
    /** Call from the main thread. squaremap's API becomes available when squaremap has enabled. */
    fun refresh() {
        publish(try {
            val api = SquaremapProvider.get()
            val worlds = api.mapWorlds().map { mw ->
                val id = mw.identifier().asString()
                val bw = try { BukkitAdapter.bukkitWorld(mw)?.name } catch (_: Throwable) { null }
                TargetInfo(id, bw ?: id, bw)
            }
            SquaremapState(api.webDir(), worlds)
        } catch (_: Throwable) {
            null
        })
    }
}
