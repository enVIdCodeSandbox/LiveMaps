package dev.etran.livemaps.core

import dev.etran.livemaps.model.ViewSpec
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

enum class ItemKind { KIT, TILE, DESIGNER }

/** Creates and recognises LiveMaps items. All identification is by persistent data, never by name. */
class Items(plugin: Plugin) {
    private val kindKey = NamespacedKey(plugin, "kind")
    private val mapKey = NamespacedKey(plugin, "map")
    private val colKey = NamespacedKey(plugin, "col")
    private val rowKey = NamespacedKey(plugin, "row")
    val designerRecipeKey = NamespacedKey(plugin, "designer")

    private fun text(s: String, color: NamedTextColor = NamedTextColor.GRAY) =
        Component.text(s, color).decoration(TextDecoration.ITALIC, false)

    private fun sourceName(live: LiveMap) = when (live.spec.source.type) {
        dev.etran.livemaps.model.SourceType.BLUEMAP -> if (live.spec.snapshot) "BlueMap snapshot" else "BlueMap"
        dev.etran.livemaps.model.SourceType.SQUAREMAP -> "squaremap"
    }

    private fun filledMap(mapId: Int): ItemStack? {
        val view = Bukkit.getMap(mapId) ?: return null
        val item = ItemStack(Material.FILLED_MAP)
        val meta = item.itemMeta as MapMeta
        meta.mapView = view
        item.itemMeta = meta
        return item
    }

    /** Whole-map kit item (no tile number). Used on creation and when `entire-drop: KIT` picks up the full map. */
    fun kit(live: LiveMap): ItemStack? {
        val item = filledMap(live.thumbMapId) ?: return null
        val meta = item.itemMeta as MapMeta
        meta.displayName(text("Live Map #${live.id}", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true))
        meta.lore(listOf(
            text("${live.width} x ${live.height} maps  -  ${sourceName(live)}"),
            text("Right-click an item frame to place it."),
            text("The clicked frame becomes the top-left corner;"),
            text("every frame of the ${live.width} x ${live.height} grid must exist."),
        ))
        meta.persistentDataContainer.set(kindKey, PersistentDataType.STRING, ItemKind.KIT.name)
        meta.persistentDataContainer.set(mapKey, PersistentDataType.INTEGER, live.id)
        item.itemMeta = meta
        return item
    }

    /**
     * One tile of [live].
     * When [named] is false (inside an ItemFrame), no custom displayName is set so Minecraft does not
     * render a floating hologram title when players hover over the placed map.
     * When [named] is true (dropped/picked up individually), the name includes its 1-based tile number.
     */
    fun tile(live: LiveMap, col: Int, row: Int, named: Boolean = true): ItemStack? {
        val item = filledMap(live.tileMapIds[row * live.width + col]) ?: return null
        val meta = item.itemMeta as MapMeta
        val tileNum = row * live.width + col + 1
        val total = live.width * live.height
        if (named) {
            meta.displayName(text("Live Map #${live.id} (#$tileNum)", NamedTextColor.AQUA))
            meta.lore(listOf(
                text("Tile #$tileNum of $total (col ${col + 1}, row ${row + 1} of ${live.width} x ${live.height})"),
                text(if (live.spec.snapshot) "BlueMap snapshot (re-capture in web app to update)." else "Updates live when placed in an item frame."),
            ))
        } else {
            meta.displayName(null)
            meta.lore(null)
        }
        meta.persistentDataContainer.set(kindKey, PersistentDataType.STRING, ItemKind.TILE.name)
        meta.persistentDataContainer.set(mapKey, PersistentDataType.INTEGER, live.id)
        meta.persistentDataContainer.set(colKey, PersistentDataType.INTEGER, col)
        meta.persistentDataContainer.set(rowKey, PersistentDataType.INTEGER, row)
        item.itemMeta = meta
        return item
    }

    /** Returns an unnamed copy suitable for placing in an ItemFrame without showing a hologram title. */
    fun unnamedFrameCopy(tileItem: ItemStack): ItemStack = tileItem.clone().apply {
        amount = 1
        val meta = itemMeta
        if (meta != null) {
            meta.displayName(null)
            meta.lore(null)
            itemMeta = meta
        }
    }

    fun designer(): ItemStack {
        val item = ItemStack(Material.COMPASS)
        val meta = item.itemMeta
        meta.displayName(text("Live Map Designer", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true))
        meta.lore(listOf(
            text("Right-click to open the Live Map web app,"),
            text("where you choose the view, size and angle."),
        ))
        meta.persistentDataContainer.set(kindKey, PersistentDataType.STRING, ItemKind.DESIGNER.name)
        item.itemMeta = meta
        return item
    }

    fun kindOf(item: ItemStack?): ItemKind? {
        if (item == null || item.type == Material.AIR || !item.hasItemMeta()) return null
        val s = item.itemMeta.persistentDataContainer.get(kindKey, PersistentDataType.STRING) ?: return null
        return ItemKind.values().firstOrNull { it.name == s }
    }

    fun mapIdOf(item: ItemStack?): Int? =
        if (kindOf(item) == null) null else item!!.itemMeta.persistentDataContainer.get(mapKey, PersistentDataType.INTEGER)

    fun tileCoords(item: ItemStack?): Pair<Int, Int>? {
        if (kindOf(item) != ItemKind.TILE) return null
        val pdc = item!!.itemMeta.persistentDataContainer
        val c = pdc.get(colKey, PersistentDataType.INTEGER) ?: return null
        val r = pdc.get(rowKey, PersistentDataType.INTEGER) ?: return null
        return c to r
    }
}

fun ViewSpec.describe(): String = if (snapshot) "BlueMap snapshot" else when (camera.mode) {
    dev.etran.livemaps.model.CameraMode.FLAT -> "top-down"
    dev.etran.livemaps.model.CameraMode.ORBIT -> "orbit ${camera.tilt.toInt()}°"
    dev.etran.livemaps.model.CameraMode.FREE -> "free camera"
}
