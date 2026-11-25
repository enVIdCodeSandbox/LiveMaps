package dev.etran.livemaps.core

import dev.etran.livemaps.BreakMode
import dev.etran.livemaps.EntireDrop
import dev.etran.livemaps.LiveMapsPlugin
import dev.etran.livemaps.Msg
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Placing kits onto grids of item frames, and breaking placed maps. */
class PlacementService(private val plugin: LiveMapsPlugin, private val service: LiveMapService) {

    /** True while we ask other plugins about frames, so our own listener ignores those synthetic events. */
    @Volatile var probing = false
        private set

    private val items get() = service.items
    private val cfg get() = plugin.cfg

    private fun frameAt(block: Block, face: BlockFace): ItemFrame? {
        val center = block.location.add(0.5, 0.5, 0.5)
        return block.world.getNearbyEntities(center, 0.75, 0.75, 0.75) { it is ItemFrame && (it as ItemFrame).facing == face }
            .filterIsInstance<ItemFrame>()
            .firstOrNull { it.location.block == block }
    }

    /** Called when a player right-clicks [clicked] holding a kit in [handSlot]. */
    fun tryPlace(player: Player, clicked: ItemFrame, handSlot: EquipmentSlot) {
        val kit = player.inventory.getItem(handSlot)
        val liveId = items.mapIdOf(kit)
        val live = liveId?.let { service.maps[it] }
        if (live == null) { Msg.err(player, "This live map no longer exists."); return }
        if (!player.hasPermission("livemaps.use")) { Msg.err(player, "You may not place live maps."); return }

        val face = clicked.facing
        val basis = GridMath.basis(face, player.location.yaw)
        if (basis == null) { Msg.err(player, "That item frame can't be used."); return }
        val anchor = clicked.location.block
        val world = clicked.world

        val frames = ArrayList<Triple<ItemFrame, Int, Int>>()
        val missing = ArrayList<Block>()
        val occupied = ArrayList<Block>()
        for (row in 0 until live.height) for (col in 0 until live.width) {
            val b = world.getBlockAt(
                anchor.x + col * basis.rx + row * basis.dx,
                anchor.y + col * basis.ry + row * basis.dy,
                anchor.z + col * basis.rz + row * basis.dz,
            )
            val f = if (col == 0 && row == 0) clicked else frameAt(b, face)
            if (f == null) missing.add(b)
            else if (f.item.type != Material.AIR) occupied.add(b)
            else frames.add(Triple(f, col, row))
        }
        if (missing.isNotEmpty() || occupied.isNotEmpty()) {
            val sb = StringBuilder("Can't place ${live.width} x ${live.height} here: ")
            if (missing.isNotEmpty()) sb.append("${missing.size} item frame(s) missing (first at ${coords(missing[0])}). ")
            if (occupied.isNotEmpty()) sb.append("${occupied.size} frame(s) already hold an item (first at ${coords(occupied[0])}). ")
            sb.append("The clicked frame is the top-left corner; every spot of the grid needs an empty item frame.")
            Msg.err(player, Msg.esc(sb.toString()))
            (missing + occupied).take(64).forEach { b ->
                player.spawnParticle(Particle.END_ROD, b.location.add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.0)
            }
            return
        }

        if (cfg.checkProtection) {
            probing = true
            try {
                for ((f, col, row) in frames) {
                    if (col == 0 && row == 0) continue
                    val ev = PlayerInteractEntityEvent(player, f, handSlot)
                    Bukkit.getPluginManager().callEvent(ev)
                    if (ev.isCancelled) { Msg.err(player, "You can't modify the item frame at ${coords(f.location.block)} (protected)."); return }
                }
            } finally { probing = false }
        }

        val rotation = GridMath.rotationFor(face, player.location.yaw)
        val placement = Placement(UUID.randomUUID(), live.id, world.uid, 0.0, 0.0, 0.0)
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for ((f, col, row) in frames) {
            // Place unnamed tiles in frames so hovering over placed maps never shows a hologram title
            val tile = items.tile(live, col, row, named = false)
            if (tile == null) { Msg.err(player, "Map data for this live map is missing."); rollback(frames); return }
            f.setItem(tile, false)
            f.rotation = rotation
            placement.frames[f.uniqueId] = intArrayOf(col, row)
            val l = f.location
            sx += l.x; sy += l.y; sz += l.z
        }
        placement.cx = sx / frames.size; placement.cy = sy / frames.size; placement.cz = sz / frames.size
        service.addPlacement(placement)

        if (player.gameMode != GameMode.CREATIVE) {
            kit.amount -= 1
            player.inventory.setItem(handSlot, if (kit.amount <= 0) null else kit)
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_FRAME_ADD_ITEM, 1f, 1f)
        Msg.ok(player, "Placed live map #${live.id} (${live.width} x ${live.height}).")
        if (!live.hasContent) service.quantizer?.let { q -> service.pool.submit { service.refresh(live, q, true) } }
    }

    /** Places a single live-map tile into an empty frame without its custom displayName (prevents hologram title). */
    fun placeSingleTile(player: Player, frame: ItemFrame, handSlot: EquipmentSlot, held: ItemStack) {
        if (frame.item.type != Material.AIR) return
        frame.setItem(items.unnamedFrameCopy(held), false)
        if (player.gameMode != GameMode.CREATIVE) {
            held.amount -= 1
            player.inventory.setItem(handSlot, if (held.amount <= 0) null else held)
        }
        frame.world.playSound(frame.location, Sound.ENTITY_ITEM_FRAME_ADD_ITEM, 1f, 1f)
    }

    /** Builds the named inventory/drop item (with tile #) for a tile inside an ItemFrame. */
    fun namedTileFor(held: ItemStack): ItemStack? {
        val liveId = items.mapIdOf(held) ?: return null
        val coords = items.tileCoords(held) ?: return held.clone()
        val live = service.maps[liveId] ?: return held.clone()
        return items.tile(live, coords.first, coords.second, named = true) ?: held.clone()
    }

    private fun rollback(frames: List<Triple<ItemFrame, Int, Int>>) {
        frames.forEach { (f, _, _) -> if (items.kindOf(f.item) != null) f.setItem(null, false) }
    }

    private fun coords(b: Block) = "${b.x} ${b.y} ${b.z}"

    /** True if [frame] holds a live map tile. */
    fun isLiveTile(frame: ItemFrame) = items.kindOf(frame.item) == ItemKind.TILE

    /** Removes every tile of a placement from the wall and hands out drops according to the config. */
    fun breakWhole(p: Placement, by: Player?, origin: ItemFrame?) {
        val creative = by?.gameMode == GameMode.CREATIVE
        var dropAt: Location? = origin?.location
        val live = service.maps[p.mapId]
        for ((uuid, coords) in p.frames.entries.toList()) {
            val f = Bukkit.getEntity(uuid) as? ItemFrame
            if (f == null) { service.pendingClear.add(uuid); continue }
            if (dropAt == null) dropAt = f.location
            val held = f.item
            if (items.mapIdOf(held) == p.mapId) {
                f.setItem(null, false)
                if (!creative && cfg.entireDrop == EntireDrop.TILES) {
                    val drop = namedTileFor(held) ?: live?.let { items.tile(it, coords[0], coords[1], named = true) } ?: held
                    f.world.dropItemNaturally(f.location, drop)
                }
            }
        }
        if (!creative && cfg.entireDrop == EntireDrop.KIT && live != null && dropAt != null) {
            items.kit(live)?.let { dropAt.world.dropItemNaturally(dropAt, it) }
        }
        service.removePlacement(p)
    }

    fun stripFrameTitleIfNeeded(frame: ItemFrame) {
        val item = frame.item
        if (items.kindOf(item) == ItemKind.TILE && item.hasItemMeta() && item.itemMeta.hasDisplayName()) {
            frame.setItem(items.unnamedFrameCopy(item), false)
        }
    }

    /** Strips legacy hologram titles on any already-loaded placed frames from earlier versions. */
    fun stripLoadedFrameTitles() {
        for (p in service.placements.values) {
            for (uuid in p.frames.keys) {
                val f = Bukkit.getEntity(uuid) as? ItemFrame ?: continue
                stripFrameTitleIfNeeded(f)
            }
        }
    }

    /** Clears leftover tiles in frames that were in unloaded chunks when their map was broken or deleted. */
    fun clearPending(frames: Collection<org.bukkit.entity.Entity>) {
        for (e in frames) {
            if (e !is ItemFrame) continue
            if (service.pendingClear.remove(e.uniqueId)) {
                if (items.kindOf(e.item) == ItemKind.TILE) e.setItem(null, false)
                service.markDirty()
            } else {
                stripFrameTitleIfNeeded(e)
            }
        }
    }

    fun breakMode(): BreakMode = cfg.breakMode
}
