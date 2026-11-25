package dev.etran.livemaps.cmd

import com.google.gson.JsonObject
import dev.etran.livemaps.LiveMapsPlugin
import dev.etran.livemaps.Msg
import dev.etran.livemaps.core.CreateResult
import dev.etran.livemaps.core.describe
import dev.etran.livemaps.model.SourceType
import dev.etran.livemaps.model.ViewSpec
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class LiveMapCommand(private val plugin: LiveMapsPlugin) : CommandExecutor, TabCompleter {

    private val service get() = plugin.service
    private val cfg get() = plugin.cfg

    private val keys = listOf("x=", "y=", "z=", "zoom=", "yaw=", "tilt=", "mode=", "proj=", "distance=", "fov=", "view=", "sharp=", "shading=", "dither=", "shadows=", "fog=", "refresh=")

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) return help(sender)
        when (args[0].lowercase()) {
            "web" -> {
                if (sender !is Player) return Msg.err(sender, "Only players can use this.").let { true }
                plugin.sendWebLink(sender)
            }
            "create" -> create(sender, args.drop(1))
            "give" -> give(sender, args.drop(1))
            "list" -> list(sender, args.drop(1))
            "info" -> info(sender, args.drop(1))
            "delete" -> delete(sender, args.drop(1))
            "refresh" -> refresh(sender, args.drop(1))
            "sources" -> sources(sender)
            "reload" -> {
                if (!sender.hasPermission("livemaps.admin")) return denied(sender)
                plugin.reload()
                Msg.ok(sender, "Configuration reloaded.")
            }
            else -> return help(sender)
        }
        return true
    }

    private fun denied(s: CommandSender): Boolean { Msg.err(s, "You don't have permission."); return true }

    private fun help(s: CommandSender): Boolean {
        Msg.info(s, "Commands:")
        val lines = mutableListOf("/livemap web <gray>- get your link to the map designer")
        if (s.hasPermission("livemaps.command.create")) lines.add("/livemap create <bluemap|squaremap> <map> <width> <height> [key=value...] [confirm]")
        lines.add("/livemap list | info <id> | sources")
        if (s.hasPermission("livemaps.admin")) lines.add("/livemap give <player> <id> | delete <id> | refresh <id|all> | reload")
        lines.forEach { s.sendMessage(Msg.parse("  <gray>$it")) }
        return true
    }

    // ------------------------------------------------------------------------------------------ create

    private fun create(sender: CommandSender, a: List<String>) {
        if (sender !is Player) return Msg.err(sender, "Only players can create live maps (the kit goes to your inventory).")
        if (!sender.hasPermission("livemaps.command.create")) { denied(sender); return }
        if (a.size < 4) {
            Msg.err(sender, "Usage: /livemap create <bluemap|squaremap> <map> <width> <height> [x=.. z=.. zoom=.. yaw=.. tilt=.. mode=flat|orbit|free proj=ortho|persp distance=.. fov=.. view=<blocks> sharp=<blocks> refresh=<sec>] [confirm]")
            return
        }
        val type = SourceType.values().firstOrNull { it.name.equals(a[0], true) }
            ?: return Msg.err(sender, "Unknown source '${Msg.esc(a[0])}'. Use bluemap or squaremap.")
        val o = JsonObject()
        o.addProperty("source", type.name)
        o.addProperty("target", a[1])
        o.addProperty("width", a[2].toIntOrNull() ?: return Msg.err(sender, "Width must be a whole number."))
        o.addProperty("height", a[3].toIntOrNull() ?: return Msg.err(sender, "Height must be a whole number."))
        var confirm = false
        val given = HashSet<String>()
        for (t in a.drop(4)) {
            if (t.equals("confirm", true)) { confirm = true; continue }
            val i = t.indexOf('=')
            if (i <= 0) return Msg.err(sender, "Unrecognised option '${Msg.esc(t)}'. Use key=value.")
            val k = t.substring(0, i).lowercase()
            val v = t.substring(i + 1)
            given.add(k)
            when (k) {
                "proj" -> o.addProperty("projection", if (v.startsWith("p", true)) "PERSPECTIVE" else "ORTHOGRAPHIC")
                "dir", "rotation" -> o.addProperty("yaw", v)
                "view", "viewdistance" -> o.addProperty("viewDistance", v)
                "sharp", "detail" -> o.addProperty("detailDistance", v)
                "refresh", "interval" -> o.addProperty("refreshIntervalSeconds", v)
                else -> o.addProperty(k, v)
            }
        }
        val loc = sender.eyeLocation
        if ("x" !in given) o.addProperty("x", loc.x)
        if ("z" !in given) o.addProperty("z", loc.z)
        val mode = o.get("mode")?.asString?.uppercase()
        if (mode == "FREE") {
            if ("y" !in given) o.addProperty("y", loc.y)
            if ("yaw" !in given) o.addProperty("yaw", ((loc.yaw + 180.0) % 360.0 + 360.0) % 360.0)
            if ("tilt" !in given) o.addProperty("tilt", (90.0 - loc.pitch).coerceIn(0.0, 180.0))
            if ("proj" !in given) o.addProperty("projection", "PERSPECTIVE")
        } else if (mode == "ORBIT") {
            if ("tilt" !in given) o.addProperty("tilt", 50.0)
            if ("proj" !in given) o.addProperty("projection", "PERSPECTIVE")
        }
        val spec = try { ViewSpec.fromJson(o, cfg.defaultOptions) } catch (e: Exception) { return Msg.err(sender, Msg.esc(e.message ?: "Invalid options")) }

        val check = service.check(sender, spec)
        check.warnings.forEach { Msg.warn(sender, Msg.esc(it)) }
        if (!check.ok) return Msg.err(sender, Msg.esc(check.errors.first()))
        if (check.needsConfirm && !confirm) {
            Msg.warn(sender, "Add the word <white>confirm</white> to the end of the command to create this large map.")
            return
        }
        Msg.info(sender, "Rendering ${spec.widthMaps} x ${spec.heightMaps} (${spec.describe()})...")
        service.createFor(sender, spec, confirm).thenAccept { r ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                when (r) {
                    is CreateResult.Ok -> Msg.ok(sender, "Created live map #${r.live.id}. The kit is in your inventory: put an empty item frame on every spot of a ${r.live.width} x ${r.live.height} grid, then right-click the top-left frame with it.")
                    is CreateResult.Fail -> Msg.err(sender, Msg.esc(r.message))
                }
            })
        }
    }

    private fun give(sender: CommandSender, a: List<String>) {
        if (!sender.hasPermission("livemaps.admin")) { denied(sender); return }
        if (a.size < 2) return Msg.err(sender, "Usage: /livemap give <player> <id>")
        val target = Bukkit.getPlayerExact(a[0]) ?: return Msg.err(sender, "Player not online.")
        val live = a[1].toIntOrNull()?.let { service.maps[it] } ?: return Msg.err(sender, "No live map with that id.")
        if (service.giveKit(target, live)) Msg.ok(sender, "Gave kit #${live.id} to ${target.name}.") else Msg.err(sender, "Could not create the item.")
    }

    // ------------------------------------------------------------------------------------------ queries

    private fun list(sender: CommandSender, a: List<String>) {
        val all = sender.hasPermission("livemaps.admin") && a.firstOrNull()?.equals("all", true) == true
        val mine = service.maps.values.filter { all || (sender is Player && it.owner == sender.uniqueId) }.sortedBy { it.id }
        if (mine.isEmpty()) return Msg.info(sender, if (all) "There are no live maps." else "You don't own any live maps.")
        mine.forEach { m ->
            val placed = service.placements.values.count { it.mapId == m.id }
            val kind = if (m.spec.snapshot) "snapshot" else m.spec.source.type.name.lowercase()
            sender.sendMessage(Msg.parse("<gray>#${m.id} <white>${m.width}x${m.height}</white> $kind:${Msg.esc(m.spec.source.target)} <dark_gray>by ${Msg.esc(m.ownerName)}, placed $placed time(s)"))
        }
    }

    private fun formatInterval(sec: Int): String = when {
        sec % 3600 == 0 -> "${sec / 3600}h"
        sec % 60 == 0 -> "${sec / 60}m"
        else -> "${sec}s"
    }

    private fun info(sender: CommandSender, a: List<String>) {
        val m = a.firstOrNull()?.toIntOrNull()?.let { service.maps[it] } ?: return Msg.err(sender, "Usage: /livemap info <id>")
        if (!sender.hasPermission("livemaps.admin") && (sender !is Player || sender.uniqueId != m.owner)) { denied(sender); return }
        val s = m.spec
        val viewLine = if (s.snapshot) {
            "View: ${s.describe()}" + (if (s.bluemapHash.isNotEmpty()) " (${Msg.esc(s.bluemapHash)})" else "")
        } else {
            "View: ${s.describe()} at ${s.camera.x.toInt()}, ${s.camera.z.toInt()}, ${if (s.projection == dev.etran.livemaps.model.Projection.ORTHOGRAPHIC) "${s.zoom} blocks/pixel" else "fov ${s.fov.toInt()}, distance ${s.distance.toInt()}"}, rotation ${s.camera.yaw.toInt()}°"
        }
        val effSec = s.refreshIntervalSeconds ?: cfg.updateIntervalSeconds
        val refreshLine = if (s.snapshot) {
            "Updates: static snapshot (re-capture in /livemap web to update)"
        } else {
            "Refresh interval: ${formatInterval(effSec)}${if (s.refreshIntervalSeconds == null) " (server default)" else " (custom)"}"
        }
        Msg.info(sender, "Live map #${m.id} by ${Msg.esc(m.ownerName)}")
        listOf(
            "Size: ${m.width} x ${m.height} (${m.tileCount} maps)",
            "Source: ${s.source.type.name.lowercase()} / ${Msg.esc(s.source.target)}",
            viewLine,
            refreshLine,
            "Last render: ${if (m.lastRenderedAt == 0L) "never" else "${(System.currentTimeMillis() - m.lastRenderedAt) / 1000}s ago (${m.lastRenderMs} ms)"}",
            m.lastError?.let { "Last error: ${Msg.esc(it)}" } ?: "Status: ok",
        ).forEach { sender.sendMessage(Msg.parse("  <gray>$it")) }
    }

    private fun sources(sender: CommandSender) {
        for (t in SourceType.values()) {
            val src = plugin.source(t)
            if (src == null || !src.isReady()) { sender.sendMessage(Msg.parse("<gray>${t.name.lowercase()}: <red>not available")); continue }
            sender.sendMessage(Msg.parse("<gray>${t.name.lowercase()}: <green>ready<gray> - ${src.targets().joinToString { Msg.esc(it.id) }}"))
        }
    }

    private fun delete(sender: CommandSender, a: List<String>) {
        val m = a.firstOrNull()?.toIntOrNull()?.let { service.maps[it] } ?: return Msg.err(sender, "Usage: /livemap delete <id>")
        if (!sender.hasPermission("livemaps.admin") && (sender !is Player || sender.uniqueId != m.owner)) { denied(sender); return }
        service.delete(m.id)
        Msg.ok(sender, "Deleted live map #${m.id} and removed it from any item frames.")
    }

    private fun refresh(sender: CommandSender, a: List<String>) {
        if (!sender.hasPermission("livemaps.admin")) { denied(sender); return }
        val arg = a.firstOrNull() ?: return Msg.err(sender, "Usage: /livemap refresh <id|all>")
        if (arg.equals("all", true)) { Msg.ok(sender, "Refreshing ${service.refreshAllNow(true)} map(s)."); return }
        val m = arg.toIntOrNull()?.let { service.maps[it] } ?: return Msg.err(sender, "No live map with that id.")
        if (m.spec.snapshot) return Msg.warn(sender, "Map #${m.id} is a static BlueMap snapshot; re-capture it in /livemap web to update.")
        val q = service.quantizer ?: return Msg.err(sender, "Renderer still starting.")
        service.pool.submit { service.refresh(m, q, true) }
        Msg.ok(sender, "Refreshing #${m.id}.")
    }

    // ------------------------------------------------------------------------------------------ tab completion

    override fun onTabComplete(sender: CommandSender, cmd: Command, alias: String, args: Array<out String>): List<String> {
        fun f(opts: Collection<String>) = opts.filter { it.startsWith(args.last(), true) }
        if (args.size == 1) {
            val subs = mutableListOf("web", "list", "info", "sources", "delete")
            if (sender.hasPermission("livemaps.command.create")) subs.add("create")
            if (sender.hasPermission("livemaps.admin")) subs.addAll(listOf("give", "refresh", "reload"))
            return f(subs)
        }
        return when (args[0].lowercase()) {
            "create" -> when {
                args.size == 2 -> f(listOf("bluemap", "squaremap"))
                args.size == 3 -> f(SourceType.values().firstOrNull { it.name.equals(args[1], true) }?.let { plugin.source(it)?.targets()?.map { t -> t.id } } ?: emptyList())
                args.size <= 5 -> f(listOf("1", "2", "3", "4"))
                else -> f(keys + "confirm")
            }
            "give" -> if (args.size == 2) f(Bukkit.getOnlinePlayers().map { it.name }) else if (args.size == 3) f(service.maps.keys.map { it.toString() }) else emptyList()
            "info", "delete" -> if (args.size == 2) f(service.maps.keys.map { it.toString() }) else emptyList()
            "refresh" -> if (args.size == 2) f(listOf("all") + service.maps.keys.map { it.toString() }) else emptyList()
            "list" -> if (args.size == 2 && sender.hasPermission("livemaps.admin")) f(listOf("all")) else emptyList()
            else -> emptyList()
        }
    }
}
