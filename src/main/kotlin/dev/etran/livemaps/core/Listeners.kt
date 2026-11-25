package dev.etran.livemaps.core

import dev.etran.livemaps.BreakMode
import dev.etran.livemaps.LiveMapsPlugin
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.server.MapInitializeEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.ShapedRecipe
import java.util.UUID

class Listeners(
    private val plugin: LiveMapsPlugin,
    private val service: LiveMapService,
    private val placement: PlacementService,
) : Listener {

    private val items get() = service.items
    private val lastLink = HashMap<UUID, Long>()

    @EventHandler
    fun onMapInit(e: MapInitializeEvent) = service.onMapInitialize(e.map)

    // ---- placing -----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteractFrame(e: PlayerInteractEntityEvent) {
        if (placement.probing) return
        val frame = e.rightClicked as? ItemFrame ?: return
        val held = e.player.inventory.getItem(e.hand)
        when (items.kindOf(held)) {
            ItemKind.KIT -> {
                e.isCancelled = true // never put the kit itself into a frame
                placement.tryPlace(e.player, frame, e.hand)
            }
            ItemKind.TILE -> {
                if (frame.item.type == Material.AIR) {
                    e.isCancelled = true // strip displayName so re-placed tiles also have no hologram title
                    placement.placeSingleTile(e.player, frame, e.hand, held)
                }
            }
            ItemKind.DESIGNER -> e.isCancelled = true
            else -> {}
        }
    }

    @EventHandler(ignoreCancelled = false)
    fun onInteract(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        if (e.action != Action.RIGHT_CLICK_AIR && e.action != Action.RIGHT_CLICK_BLOCK) return
        if (items.kindOf(e.item) != ItemKind.DESIGNER) return
        e.isCancelled = true
        val p = e.player
        val now = System.currentTimeMillis()
        if (now - (lastLink[p.uniqueId] ?: 0) < 2000) return
        lastLink[p.uniqueId] = now
        plugin.sendWebLink(p)
    }

    // ---- breaking ----------------------------------------------------------------------------------------

    private fun playerOf(e: org.bukkit.entity.Entity?): Player? = when (e) {
        is Player -> e
        is Projectile -> e.shooter as? Player
        else -> null
    }

    /** A player hits a frame: first hit pops the item out, second hit breaks the frame. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFrameHit(e: EntityDamageByEntityEvent) {
        val frame = e.entity as? ItemFrame ?: return
        if (!placement.isLiveTile(frame)) return
        val p = service.placementOfFrame(frame.uniqueId)
        val by = playerOf(e.damager)
        if (placement.breakMode() == BreakMode.ENTIRE && p != null) {
            e.isCancelled = true
            placement.breakWhole(p, by, frame)
        } else {
            val drop = placement.namedTileFor(frame.item)
            e.isCancelled = true
            frame.setItem(null, false)
            frame.world.playSound(frame.location, Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, 1f, 1f)
            service.removeFrameFromPlacement(frame.uniqueId)
            if (by?.gameMode != GameMode.CREATIVE && drop != null) {
                frame.world.dropItemNaturally(frame.location, drop)
            }
        }
    }

    /** The frame itself breaks (second hit, explosion, support block removed). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFrameBreak(e: HangingBreakEvent) {
        val frame = e.entity as? ItemFrame ?: return
        val p = service.placementOfFrame(frame.uniqueId)
        if (!placement.isLiveTile(frame) && p == null) return
        val by = playerOf((e as? HangingBreakByEntityEvent)?.remover)
        if (placement.breakMode() == BreakMode.ENTIRE && p != null) {
            placement.breakWhole(p, by, frame)
        } else {
            if (placement.isLiveTile(frame)) {
                val drop = placement.namedTileFor(frame.item)
                frame.setItem(null, false)
                if (by?.gameMode != GameMode.CREATIVE && drop != null) {
                    frame.world.dropItemNaturally(frame.location, drop)
                }
            }
            service.removeFrameFromPlacement(frame.uniqueId)
        }
    }

    @EventHandler
    fun onEntitiesLoad(e: EntitiesLoadEvent) = placement.clearPending(e.entities)

    // ---- crafting ----------------------------------------------------------------------------------------

    @EventHandler
    fun onPrepareCraft(e: PrepareItemCraftEvent) {
        val r: Recipe = e.recipe ?: return
        if (r is ShapedRecipe && r.key == items.designerRecipeKey) {
            val who = e.view.player
            if (!who.hasPermission("livemaps.craft")) e.inventory.result = null
        }
    }
}
