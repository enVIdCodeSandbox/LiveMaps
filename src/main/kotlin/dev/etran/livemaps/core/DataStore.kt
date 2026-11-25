package dev.etran.livemaps.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.etran.livemaps.model.ViewSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A set of item frames that were filled together from one kit. */
class Placement(
    val id: UUID,
    val mapId: Int,
    val world: UUID,
    var cx: Double,
    var cy: Double,
    var cz: Double,
) {
    /** frame entity uuid -> [col, row] */
    val frames = LinkedHashMap<UUID, IntArray>()
}

class StoredData(
    val nextId: Int,
    val maps: List<LiveMap>,
    val placements: List<Placement>,
    val pendingClear: Set<UUID>,
)

/** Persists live maps and placements as one JSON file (written atomically). */
class DataStore(private val file: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(defaults: dev.etran.livemaps.model.ViewOptions): StoredData {
        if (!Files.isRegularFile(file)) return StoredData(1, emptyList(), emptyList(), emptySet())
        val root = JsonParser.parseString(String(Files.readAllBytes(file), Charsets.UTF_8)).asJsonObject
        val maps = ArrayList<LiveMap>()
        for (e in root.getAsJsonArray("maps") ?: JsonArray()) {
            try {
                val o = e.asJsonObject
                val tiles = o.getAsJsonArray("tiles").map { it.asInt }.toIntArray()
                val spec = ViewSpec.fromJson(o.getAsJsonObject("spec"), defaults)
                if (tiles.size != spec.widthMaps * spec.heightMaps) continue
                maps.add(LiveMap(
                    o.get("id").asInt, UUID.fromString(o.get("owner").asString), o.get("ownerName")?.asString ?: "?",
                    o.get("createdAt")?.asLong ?: 0, spec, tiles, o.get("thumb").asInt,
                ))
            } catch (_: Exception) { /* skip corrupt entry */ }
        }
        val placements = ArrayList<Placement>()
        for (e in root.getAsJsonArray("placements") ?: JsonArray()) {
            try {
                val o = e.asJsonObject
                val p = Placement(
                    UUID.fromString(o.get("id").asString), o.get("map").asInt, UUID.fromString(o.get("world").asString),
                    o.get("cx").asDouble, o.get("cy").asDouble, o.get("cz").asDouble,
                )
                for (f in o.getAsJsonArray("frames")) {
                    val fo = f.asJsonObject
                    p.frames[UUID.fromString(fo.get("uuid").asString)] = intArrayOf(fo.get("col").asInt, fo.get("row").asInt)
                }
                placements.add(p)
            } catch (_: Exception) { }
        }
        val pending = (root.getAsJsonArray("pendingClear") ?: JsonArray()).mapNotNull { runCatching { UUID.fromString(it.asString) }.getOrNull() }.toSet()
        val maxId = maps.maxOfOrNull { it.id } ?: 0
        return StoredData(maxOf(root.get("nextId")?.asInt ?: 1, maxId + 1), maps, placements, pending)
    }

    fun toJson(nextId: Int, maps: Collection<LiveMap>, placements: Collection<Placement>, pendingClear: Collection<UUID>): String {
        val root = JsonObject()
        root.addProperty("version", 1)
        root.addProperty("nextId", nextId)
        val ma = JsonArray()
        for (m in maps) ma.add(JsonObject().apply {
            addProperty("id", m.id); addProperty("owner", m.owner.toString()); addProperty("ownerName", m.ownerName)
            addProperty("createdAt", m.createdAt); add("spec", m.spec.toJson())
            add("tiles", JsonArray().also { a -> m.tileMapIds.forEach { a.add(it) } }); addProperty("thumb", m.thumbMapId)
        })
        root.add("maps", ma)
        val pa = JsonArray()
        for (p in placements) pa.add(JsonObject().apply {
            addProperty("id", p.id.toString()); addProperty("map", p.mapId); addProperty("world", p.world.toString())
            addProperty("cx", p.cx); addProperty("cy", p.cy); addProperty("cz", p.cz)
            add("frames", JsonArray().also { a -> p.frames.forEach { (u, c) -> a.add(JsonObject().apply {
                addProperty("uuid", u.toString()); addProperty("col", c[0]); addProperty("row", c[1])
            }) } })
        })
        root.add("placements", pa)
        root.add("pendingClear", JsonArray().also { a -> pendingClear.forEach { a.add(it.toString()) } })
        return gson.toJson(root)
    }

    fun write(json: String) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(tmp, json.toByteArray(Charsets.UTF_8))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
