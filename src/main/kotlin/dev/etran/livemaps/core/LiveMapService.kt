package dev.etran.livemaps.core

import dev.etran.livemaps.LiveMapsPlugin
import dev.etran.livemaps.model.CameraMode
import dev.etran.livemaps.model.Dither
import dev.etran.livemaps.model.SourceType
import dev.etran.livemaps.model.ViewSpec
import dev.etran.livemaps.render.Quantizer
import dev.etran.livemaps.render.RenderContext
import dev.etran.livemaps.render.RenderResult
import dev.etran.livemaps.render.TileStore
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.map.MapView
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool
import java.util.logging.Level
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class CheckResult(
    val errors: List<String>,
    val warnings: List<String>,
    val cost: Int,
    val free: Boolean,
    val have: Int,
    val needsConfirm: Boolean,
) { val ok get() = errors.isEmpty() }

sealed class CreateResult {
    class Ok(val live: LiveMap) : CreateResult()
    class Fail(val message: String, val warnings: List<String> = emptyList()) : CreateResult()
}

/** Owns all live maps: creation, validation, the live-update loop, persistence. */
class LiveMapService(private val plugin: LiveMapsPlugin) {
    val items = Items(plugin)
    val maps = ConcurrentHashMap<Int, LiveMap>()
    val placements = ConcurrentHashMap<UUID, Placement>()
    private val frameIndex = ConcurrentHashMap<UUID, UUID>()
    private val tileOwner = ConcurrentHashMap<Int, Pair<LiveMap, Int>>()
    val pendingClear: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @Volatile var quantizer: Quantizer? = null
    lateinit var store: TileStore
    lateinit var pool: ForkJoinPool
    private lateinit var data: DataStore
    private var nextId = 1
    @Volatile private var dirty = false
    private val cacheDir get() = File(plugin.dataFolder, "cache")

    private val cfg get() = plugin.cfg

    // ------------------------------------------------------------------------------------------ lifecycle

    fun start() {
        store = TileStore(cfg.tileCacheSize)
        pool = ForkJoinPool(cfg.renderThreads)
        data = DataStore(File(plugin.dataFolder, "data.json").toPath())
        // the palette lookup table takes a moment to build; do it off the main thread
        pool.submit { quantizer = LiveRender.createQuantizer() }

        val loaded = try { data.load(cfg.defaultOptions) } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not read data.json, starting empty (the broken file was kept)", e)
            File(plugin.dataFolder, "data.json").renameTo(File(plugin.dataFolder, "data.json.broken-${System.currentTimeMillis()}"))
            StoredData(1, emptyList(), emptyList(), emptySet())
        }
        nextId = loaded.nextId
        loaded.maps.forEach { live -> register(live); restoreFromDisk(live); attach(live) }
        loaded.placements.filter { maps.containsKey(it.mapId) }.forEach { addPlacement(it) }
        pendingClear.addAll(loaded.pendingClear)
        Bukkit.getScheduler().runTask(plugin, Runnable { plugin.placement.stripLoadedFrameTitles() })
        plugin.logger.info("Loaded ${maps.size} live map(s) and ${placements.size} placement(s).")
    }

    fun stop() {
        saveNow()
        maps.values.forEach { writeDisk(it) }
        pool.shutdownNow()
    }

    /** Rebuilds the pool and cache after a config reload. */
    fun applyConfig() {
        store = TileStore(cfg.tileCacheSize)
        if (pool.parallelism != cfg.renderThreads) {
            val old = pool
            pool = ForkJoinPool(cfg.renderThreads)
            old.shutdown()
        }
    }

    fun ctxFor(type: SourceType, previewing: Boolean = false): RenderContext? {
        val src = plugin.source(type) ?: return null
        return RenderContext(store, src, cfg.renderSettings, pool, if (previewing) 4000 else 1000)
    }

    // ------------------------------------------------------------------------------------------ registry

    private fun register(live: LiveMap) {
        maps[live.id] = live
        live.tileMapIds.forEachIndexed { i, id -> tileOwner[id] = live to i }
        tileOwner[live.thumbMapId] = live to live.tileCount
    }

    private fun unregister(live: LiveMap) {
        maps.remove(live.id)
        live.tileMapIds.forEach { tileOwner.remove(it) }
        tileOwner.remove(live.thumbMapId)
    }

    /** Replaces the default renderers of a Bukkit map with ours. Safe to call repeatedly. */
    private fun attach(view: MapView, live: LiveMap, index: Int) {
        if (view.renderers.any { it is TileRenderer }) return
        view.renderers.toList().forEach { view.removeRenderer(it) }
        view.addRenderer(TileRenderer(live, index))
        view.isTrackingPosition = false
        view.isUnlimitedTracking = false
        view.isLocked = true
    }

    private fun attach(live: LiveMap) {
        live.tileMapIds.forEachIndexed { i, id -> Bukkit.getMap(id)?.let { attach(it, live, i) } }
        Bukkit.getMap(live.thumbMapId)?.let { attach(it, live, live.tileCount) }
    }

    /** MapInitializeEvent: Bukkit lazily loads maps, so we re-attach whenever one of ours initialises. */
    fun onMapInitialize(view: MapView) {
        tileOwner[view.id]?.let { (live, idx) -> attach(view, live, idx) }
    }

    fun placementOfFrame(frame: UUID): Placement? = frameIndex[frame]?.let { placements[it] }

    fun addPlacement(p: Placement) {
        placements[p.id] = p
        p.frames.keys.forEach { frameIndex[it] = p.id }
        markDirty()
    }

    fun removeFrameFromPlacement(frame: UUID) {
        val pid = frameIndex.remove(frame) ?: return
        val p = placements[pid] ?: return
        p.frames.remove(frame)
        if (p.frames.isEmpty()) placements.remove(pid)
        markDirty()
    }

    fun removePlacement(p: Placement) {
        placements.remove(p.id)
        p.frames.keys.forEach { frameIndex.remove(it) }
        markDirty()
    }

    fun markDirty() { dirty = true }

    // ------------------------------------------------------------------------------------------ creation

    fun count(player: Player, material: org.bukkit.Material): Int =
        player.inventory.contents.filterNotNull().filter { it.type == material && !it.hasItemMeta() }.sumOf { it.amount }

    fun isFree(player: Player) =
        !cfg.costEnabled || player.gameMode == GameMode.CREATIVE || player.hasPermission("livemaps.bypass.cost")

    fun check(player: Player, spec: ViewSpec, forUpdate: Boolean = false): CheckResult {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val src = plugin.source(spec.source.type)
        if (src == null || !src.isReady()) errors.add("${spec.source.type.name.lowercase()} is not available on this server right now.")
        else if (!spec.snapshot && src.targets().none { it.id == spec.source.target }) errors.add("Unknown map '${spec.source.target}'.")
        val bypassLimits = player.hasPermission("livemaps.bypass.limits")
        val total = spec.totalMaps
        if (!bypassLimits) {
            if (spec.widthMaps < cfg.minWidth || spec.heightMaps < cfg.minHeight)
                errors.add("Too small: the minimum size is ${cfg.minWidth} x ${cfg.minHeight} maps.")
            if (spec.widthMaps > cfg.maxWidth || spec.heightMaps > cfg.maxHeight)
                errors.add("Too big: the maximum size is ${cfg.maxWidth} x ${cfg.maxHeight} maps.")
            if (!forUpdate) {
                val owned = maps.values.count { it.owner == player.uniqueId }
                if (owned >= cfg.maxMapsPerPlayer) errors.add("You already own $owned live maps (the limit is ${cfg.maxMapsPerPlayer}). Ask an admin to remove some.")
            }
        }
        val needsConfirm = !forUpdate && cfg.warnTotal > 0 && total >= cfg.warnTotal
        if (needsConfirm) warnings.add(
            if (spec.snapshot) {
                "Large map: ${spec.widthMaps} x ${spec.heightMaps} = $total maps. It needs $total item frames."
            } else {
                "Large map: ${spec.widthMaps} x ${spec.heightMaps} = $total maps. It needs $total item frames, takes longer to render, " +
                    "and sends up to $total map updates to every nearby player whenever it changes."
            }
        )
        if (!spec.snapshot && spec.camera.mode != CameraMode.FLAT && spec.source.type == SourceType.SQUAREMAP)
            warnings.add("squaremap only has flat top-down tiles, so the camera angle is ignored (rotation still applies).")
        if (!spec.snapshot && !spec.isFlat && spec.source.type == SourceType.BLUEMAP && total >= 9)
            warnings.add("3D views of large maps take noticeably longer to render.")
        val free = forUpdate || isFree(player)
        val cost = if (free) 0 else cfg.costFor(total)
        val have = if (cost > 0) count(player, cfg.costMaterial) else 0
        if (cost > have) errors.add("You need $cost ${niceName(cfg.costMaterial)} in your inventory (you have $have).")
        return CheckResult(errors, warnings, cost, free, have, needsConfirm)
    }

    private fun niceName(m: org.bukkit.Material) = m.name.lowercase().replace('_', ' ')

    /**
     * Full creation flow. Callable from any thread; the future completes after the kit has been handed over.
     * Order: validate -> render (async, free of charge) -> re-validate and charge -> allocate maps -> give kit.
     */
    fun createFor(player: Player, spec0: ViewSpec, confirmLarge: Boolean): CompletableFuture<CreateResult> {
        val out = CompletableFuture<CreateResult>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                val spec = normalise(spec0)
                val c = check(player, spec)
                if (!c.ok) { out.complete(CreateResult.Fail(c.errors.first(), c.warnings)); return@Runnable }
                if (c.needsConfirm && !confirmLarge) {
                    out.complete(CreateResult.Fail("Please confirm the large-map warning to continue.", c.warnings)); return@Runnable
                }
                val ctx = ctxFor(spec.source.type)
                val q = quantizer
                if (ctx == null || q == null) { out.complete(CreateResult.Fail("The renderer is still starting up, try again in a moment.")); return@Runnable }
                pool.submit {
                    try {
                        val rendered = LiveRender.renderAll(ctx, q, spec)
                        Bukkit.getScheduler().runTask(plugin, Runnable { finishCreate(player, spec, rendered, out) })
                    } catch (e: Exception) {
                        out.complete(CreateResult.Fail("Could not render this view: ${e.message ?: e.javaClass.simpleName}"))
                    }
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Live map creation failed", e)
                out.complete(CreateResult.Fail("Internal error: ${e.message}"))
            }
        })
        return out
    }

    /** Creates a new static BlueMap WebGL snapshot map from a browser-captured image. */
    fun createSnapshotFor(
        player: Player,
        spec0: ViewSpec,
        argb: IntArray,
        widthPx: Int,
        heightPx: Int,
        confirmLarge: Boolean,
    ): CompletableFuture<CreateResult> {
        val out = CompletableFuture<CreateResult>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                val spec = spec0.copy(snapshot = true)
                if (widthPx != spec.pixelWidth || heightPx != spec.pixelHeight || argb.size != widthPx * heightPx) {
                    out.complete(CreateResult.Fail("Image size (${widthPx}x${heightPx}) does not match map size (${spec.pixelWidth}x${spec.pixelHeight})."))
                    return@Runnable
                }
                val c = check(player, spec)
                if (!c.ok) { out.complete(CreateResult.Fail(c.errors.first(), c.warnings)); return@Runnable }
                if (c.needsConfirm && !confirmLarge) {
                    out.complete(CreateResult.Fail("Please confirm the large-map warning to continue.", c.warnings)); return@Runnable
                }
                val q = quantizer
                if (q == null) { out.complete(CreateResult.Fail("The renderer is still starting up, try again in a moment.")); return@Runnable }
                pool.submit {
                    try {
                        val rendered = LiveRender.toOutput(q, spec, RenderResult(argb, widthPx, heightPx), Long.MIN_VALUE)
                        Bukkit.getScheduler().runTask(plugin, Runnable { finishCreate(player, spec, rendered, out) })
                    } catch (e: Exception) {
                        out.complete(CreateResult.Fail("Could not process screenshot: ${e.message ?: e.javaClass.simpleName}"))
                    }
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Snapshot creation failed", e)
                out.complete(CreateResult.Fail("Internal error: ${e.message}"))
            }
        })
        return out
    }

    /** Updates an existing static BlueMap snapshot in-place (existing placed frames update automatically). */
    fun updateSnapshotFor(
        player: Player,
        mapId: Int,
        argb: IntArray,
        widthPx: Int,
        heightPx: Int,
        dither: Dither,
        bluemapHash: String,
        target: String,
        refreshIntervalSeconds: Int? = null,
    ): CompletableFuture<CreateResult> {
        val out = CompletableFuture<CreateResult>()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                val live = maps[mapId]
                if (live == null) { out.complete(CreateResult.Fail("Live map #$mapId no longer exists.")); return@Runnable }
                if (!live.spec.snapshot) { out.complete(CreateResult.Fail("Live map #$mapId is not a BlueMap snapshot.")); return@Runnable }
                if (live.owner != player.uniqueId && !player.hasPermission("livemaps.admin")) {
                    out.complete(CreateResult.Fail("You don't own live map #$mapId.")); return@Runnable
                }
                if (widthPx != live.spec.pixelWidth || heightPx != live.spec.pixelHeight || argb.size != widthPx * heightPx) {
                    out.complete(CreateResult.Fail("Screenshot size (${widthPx}x${heightPx}) must match map #$mapId (${live.spec.pixelWidth}x${live.spec.pixelHeight})."))
                    return@Runnable
                }
                val q = quantizer
                if (q == null) { out.complete(CreateResult.Fail("The renderer is still starting up, try again in a moment.")); return@Runnable }
                val nextSpec = live.spec.copy(
                    source = live.spec.source.copy(target = target.ifBlank { live.spec.source.target }),
                    options = live.spec.options.copy(dither = dither),
                    bluemapHash = bluemapHash.take(512),
                    refreshIntervalSeconds = refreshIntervalSeconds,
                )
                pool.submit {
                    try {
                        val rendered = LiveRender.toOutput(q, nextSpec, RenderResult(argb, widthPx, heightPx), Long.MIN_VALUE)
                        live.spec = nextSpec
                        live.apply(rendered)
                        writeDisk(live)
                        markDirty()
                        out.complete(CreateResult.Ok(live))
                    } catch (e: Exception) {
                        out.complete(CreateResult.Fail("Could not update snapshot: ${e.message ?: e.javaClass.simpleName}"))
                    }
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Snapshot update failed", e)
                out.complete(CreateResult.Fail("Internal error: ${e.message}"))
            }
        })
        return out
    }

    private fun normalise(spec: ViewSpec): ViewSpec =
        if (spec.source.type == SourceType.SQUAREMAP)
            spec.copy(camera = spec.camera.copy(mode = CameraMode.FLAT, tilt = 0.0, y = null), projection = dev.etran.livemaps.model.Projection.ORTHOGRAPHIC, snapshot = false)
        else spec

    private fun finishCreate(player: Player, spec: ViewSpec, rendered: RenderOutput, out: CompletableFuture<CreateResult>) {
        try {
            if (!player.isOnline) { out.complete(CreateResult.Fail("You went offline.")); return }
            val c = check(player, spec)
            if (!c.ok) { out.complete(CreateResult.Fail(c.errors.first())); return }
            if (c.cost > 0) {
                val left = player.inventory.removeItem(ItemStack(cfg.costMaterial, c.cost))
                if (left.isNotEmpty()) {
                    // should not happen (checked above); put back what we took
                    val taken = c.cost - left.values.sumOf { it.amount }
                    if (taken > 0) player.inventory.addItem(ItemStack(cfg.costMaterial, taken))
                    out.complete(CreateResult.Fail("Could not take the required maps from your inventory.")); return
                }
            }
            val world = Bukkit.getWorlds().first()
            val ids = IntArray(spec.totalMaps) { Bukkit.createMap(world).id }
            val thumb = Bukkit.createMap(world).id
            val live = LiveMap(nextId++, player.uniqueId, player.name, System.currentTimeMillis(), spec, ids, thumb)
            register(live)
            live.apply(rendered)
            live.lastCheck = System.currentTimeMillis()
            attach(live)
            markDirty()
            if (spec.snapshot) pool.submit { writeDisk(live) }
            giveKit(player, live)
            out.complete(CreateResult.Ok(live))
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Live map creation failed", e)
            out.complete(CreateResult.Fail("Internal error: ${e.message}"))
        }
    }

    fun giveKit(player: Player, live: LiveMap): Boolean {
        val kit = items.kit(live) ?: return false
        player.inventory.addItem(kit).values.forEach { player.world.dropItem(player.location, it) }
        return true
    }

    fun delete(id: Int): Boolean {
        val live = maps[id] ?: return false
        placements.values.filter { it.mapId == id }.forEach { p ->
            p.frames.keys.forEach { u -> (Bukkit.getEntity(u) as? org.bukkit.entity.ItemFrame)?.let { f ->
                if (items.mapIdOf(f.item) == id) f.setItem(null, false)
            } ?: pendingClear.add(u) }
            removePlacement(p)
        }
        unregister(live)
        live.tileMapIds.plus(live.thumbMapId).forEach { Bukkit.getMap(it)?.let { v -> v.renderers.filterIsInstance<TileRenderer>().forEach(v::removeRenderer) } }
        File(cacheDir, "map-$id.bin").delete()
        markDirty()
        return true
    }

    // ------------------------------------------------------------------------------------------ live updates

    /** Called every second on the main thread. */
    fun tick() {
        val q = quantizer ?: return
        val now = System.currentTimeMillis()
        val held = HashSet<Int>()
        for (p in Bukkit.getOnlinePlayers()) {
            items.mapIdOf(p.inventory.itemInMainHand)?.let { held.add(it) }
            items.mapIdOf(p.inventory.itemInOffHand)?.let { held.add(it) }
        }
        for (live in maps.values) {
            if (live.spec.snapshot) continue
            val interval = (live.spec.refreshIntervalSeconds ?: cfg.updateIntervalSeconds) * 1000L
            if (now - live.lastCheck < interval || live.busy.get()) continue
            if (live.hasContent && live.id !in held && !isNearPlayer(live)) continue
            if (!live.busy.compareAndSet(false, true)) continue
            live.lastCheck = now
            pool.submit {
                try { refresh(live, q, false) } finally { live.busy.set(false) }
            }
        }
        if (dirty) { dirty = false; saveAsync() }
    }

    private fun isNearPlayer(live: LiveMap): Boolean {
        val r = cfg.activeRadius + maxOf(live.width, live.height) * 2.0
        val r2 = r * r
        for (p in placements.values) {
            if (p.mapId != live.id) continue
            val w = Bukkit.getWorld(p.world) ?: continue
            for (pl in w.players) {
                val l = pl.location
                val dx = l.x - p.cx; val dy = l.y - p.cy; val dz = l.z - p.cz
                if (dx * dx + dy * dy + dz * dz <= r2) return true
            }
        }
        return false
    }

    /** Re-renders [live] if its source tiles changed (or [force]). Runs on a pool thread. */
    fun refresh(live: LiveMap, q: Quantizer, force: Boolean): Boolean {
        if (live.spec.snapshot) return false
        val ctx = ctxFor(live.spec.source.type) ?: return false
        if (!ctx.source.isReady()) { live.lastError = "source not ready"; return false }
        return try {
            val t0 = System.nanoTime()
            if (!force && live.hasContent) {
                val fp = dev.etran.livemaps.render.ViewRenderer.fingerprint(ctx, live.spec)
                if (fp == live.fingerprint) return false
            }
            val out = LiveRender.renderAll(ctx, q, live.spec)
            live.apply(out)
            live.lastRenderMs = (System.nanoTime() - t0) / 1_000_000
            writeDiskThrottled(live)
            true
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            if (msg != live.lastError) plugin.logger.warning("Live map #${live.id}: $msg")
            live.lastError = msg
            false
        }
    }

    fun refreshAllNow(force: Boolean): Int {
        val q = quantizer ?: return 0
        var n = 0
        for (live in maps.values) {
            if (live.spec.snapshot) continue
            if (live.busy.compareAndSet(false, true)) { n++; pool.submit { try { refresh(live, q, force) } finally { live.busy.set(false) } } }
        }
        return n
    }

    // ------------------------------------------------------------------------------------------ persistence

    private fun saveAsync() {
        val json = data.toJson(nextId, maps.values, placements.values, pendingClear)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try { data.write(json) } catch (e: Exception) { plugin.logger.log(Level.WARNING, "Could not save data.json", e) }
        })
    }

    fun saveNow() {
        try { data.write(data.toJson(nextId, maps.values, placements.values, pendingClear)) }
        catch (e: Exception) { plugin.logger.log(Level.WARNING, "Could not save data.json", e) }
    }

    private val lastDiskWrite = ConcurrentHashMap<Int, Long>()

    private fun writeDiskThrottled(live: LiveMap) {
        val now = System.currentTimeMillis()
        if (now - (lastDiskWrite[live.id] ?: 0) < 30_000) return
        writeDisk(live)
    }

    /** Keeps the last picture on disk so maps are not blank for the first seconds after a restart. */
    private fun writeDisk(live: LiveMap) {
        if (!live.dirtyForDisk) return
        val snap = live.snapshot() ?: return
        try {
            cacheDir.mkdirs()
            val f = File(cacheDir, "map-${live.id}.bin")
            val tmp = File(cacheDir, "map-${live.id}.bin.tmp")
            GZIPOutputStream(Files.newOutputStream(tmp.toPath())).use { o -> snap.tiles.forEach { o.write(it) }; o.write(snap.thumb) }
            Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            live.dirtyForDisk = false
            lastDiskWrite[live.id] = System.currentTimeMillis()
        } catch (_: Exception) { }
    }

    private fun restoreFromDisk(live: LiveMap) {
        val f = File(cacheDir, "map-${live.id}.bin")
        if (!f.isFile) return
        try {
            GZIPInputStream(Files.newInputStream(f.toPath())).use { i ->
                val tiles = Array(live.tileCount) { i.readNBytes(16384) }
                val thumb = i.readNBytes(16384)
                if (tiles.all { it.size == 16384 } && thumb.size == 16384)
                    live.restore(RenderOutput(tiles, thumb, Long.MIN_VALUE))
            }
        } catch (_: Exception) { }
    }
}
