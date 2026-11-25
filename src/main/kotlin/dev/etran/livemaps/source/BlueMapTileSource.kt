package dev.etran.livemaps.source

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.etran.livemaps.model.SourceType
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Snapshot of what BlueMap told us when it (re)loaded. Holds no BlueMap API objects. */
data class BlueMapState(
    val webRoot: Path,
    val maps: List<TargetInfo>,
    val readMapAsset: ((mapId: String, assetPath: String) -> ByteArray?)? = null,
)

class BlueMapSettings(
    val enabled: Boolean,
    /** AUTO, FILE or HTTP */
    val mode: String,
    /** Optional override of the directory that contains one folder per map id. */
    val tilesRoot: String,
    /** Base URL of BlueMap's web server, e.g. http://127.0.0.1:8100 */
    val httpBaseUrl: String,
)

/**
 * Reads BlueMap's already-rendered tiles. It never triggers or influences BlueMap rendering.
 *
 * BlueMap has no tile-reading API, so tiles are read either straight from BlueMap's file storage
 * (default `<webroot>/maps/<id>/tiles/<lod>/x../z...png`) or over HTTP from BlueMap's web server,
 * which also works with SQL storage.
 */
class BlueMapTileSource(
    private val settings: () -> BlueMapSettings,
    private val state: () -> BlueMapState?,
) : TileSource {

    override val type = SourceType.BLUEMAP

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }
    private data class CachedGeometry(val geometry: Geometry?, val at: Long)
    private val geometryCache = ConcurrentHashMap<String, CachedGeometry>()

    override fun isReady() = settings().enabled && state() != null

    override fun targets(): List<TargetInfo> = state()?.maps ?: emptyList()

    private fun root(): Path? {
        val s = settings()
        val override = s.tilesRoot.trim()
        if (override.isNotEmpty() && !override.equals("auto", true)) return Path.of(override)
        return state()?.webRoot?.resolve("maps")
    }

    private fun useHttp(target: String): Boolean {
        val s = settings()
        return when (s.mode.uppercase()) {
            "FILE" -> false
            "HTTP" -> true
            else -> {
                val r = root()
                val fileOk = r != null && Files.isRegularFile(r.resolve(target).resolve("settings.json"))
                !fileOk && s.httpBaseUrl.isNotBlank()
            }
        }
    }

    override fun geometry(target: String): Geometry? {
        val now = System.currentTimeMillis()
        val cached = geometryCache[target]
        if (cached != null && now - cached.at < 60_000 && cached.geometry != null) return cached.geometry
        val g = try { readGeometry(target) } catch (_: Exception) { null }
        geometryCache[target] = CachedGeometry(g, now)
        return g ?: cached?.geometry
    }

    fun clearCaches() = geometryCache.clear()

    private fun readGeometry(target: String): Geometry? {
        val text: String = if (useHttp(target)) {
            val bytes = httpGet("maps/$target/settings.json") ?: return null
            String(PngTiles.gunzipIfNeeded(bytes), Charsets.UTF_8)
        } else {
            val file = root()?.resolve(target)?.resolve("settings.json") ?: return null
            if (!Files.isRegularFile(file)) {
                // settings.json may be compressed with a suffix; try the common ones
                val gz = file.resolveSibling("settings.json.gz")
                if (Files.isRegularFile(gz)) String(PngTiles.gunzipIfNeeded(Files.readAllBytes(gz)), Charsets.UTF_8) else return null
            } else String(Files.readAllBytes(file), Charsets.UTF_8)
        }
        val o = JsonParser.parseString(text).asJsonObject
        val lowres = o.getAsJsonObject("lowres")
        val tileSize = lowres?.get("tileSize")?.let { vec2x(it) } ?: 500
        val factor = lowres?.get("lodFactor")?.asInt ?: 5
        val count = lowres?.get("lodCount")?.asInt ?: 3
        val levels = (1..count).map { lod ->
            var bpc = 1L
            repeat(lod - 1) { bpc *= factor }
            LevelInfo(lod, bpc.coerceAtMost(1_000_000L).toInt(), tileSize)
        }
        return Geometry(levels, true, o.get("skyColor")?.let { parseColor(it) }, o.get("voidColor")?.let { parseColor(it) })
    }

    private fun vec2x(e: JsonElement): Int = when {
        e.isJsonObject -> (e.asJsonObject.get("x") ?: e.asJsonObject.get("X"))?.asInt ?: 500
        e.isJsonArray -> e.asJsonArray[0].asInt
        e.isJsonPrimitive -> e.asInt
        else -> 500
    }

    /** BlueMap serializes colors as an object (r,g,b,a), an array, an int or a hex string depending on version. */
    internal fun parseColor(e: JsonElement): Int? = try {
        when {
            e.isJsonPrimitive && e.asJsonPrimitive.isString -> {
                val s = e.asString.removePrefix("#")
                (0xFF000000.toInt()) or (s.takeLast(6).toInt(16))
            }
            e.isJsonPrimitive -> e.asInt or 0xFF000000.toInt()
            e.isJsonArray -> channels(e.asJsonArray[0].asDouble, e.asJsonArray[1].asDouble, e.asJsonArray[2].asDouble)
            e.isJsonObject -> {
                val o: JsonObject = e.asJsonObject
                channels(o.get("r").asDouble, o.get("g").asDouble, o.get("b").asDouble)
            }
            else -> null
        }
    } catch (_: Exception) { null }

    private fun channels(r: Double, g: Double, b: Double): Int {
        val scale = if (r <= 1.0 && g <= 1.0 && b <= 1.0) 255.0 else 1.0
        fun c(v: Double) = (v * scale).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** BlueMap splits every digit of the coordinates into its own directory level: x-12z3 -> x-1/2/z3 */
    internal fun gridPath(x: Int, z: Int, suffix: String): String {
        val encoded = "x${x}z$z"
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (c in encoded) {
            sb.append(c)
            if (c in '0'..'9') { parts.add(sb.toString()); sb.setLength(0) }
        }
        parts[parts.size - 1] = parts.last() + suffix
        return parts.joinToString("/")
    }

    private fun tileRel(target: String, level: LevelInfo, tx: Int, tz: Int) =
        "maps/$target/tiles/${level.index}/${gridPath(tx, tz, ".png")}"

    private fun tileFile(target: String, level: LevelInfo, tx: Int, tz: Int): Path? =
        root()?.resolve(target)?.resolve("tiles")?.resolve(level.index.toString())?.resolve(gridPath(tx, tz, ".png"))

    override fun stamp(target: String, level: LevelInfo, tx: Int, tz: Int): Long? {
        if (useHttp(target)) return null
        val f = tileFile(target, level, tx, tz) ?: return -1
        return try {
            if (!Files.isRegularFile(f)) -1 else Files.getLastModifiedTime(f).toMillis() * 31 + Files.size(f)
        } catch (_: IOException) { -1 }
    }

    override fun load(target: String, level: LevelInfo, tx: Int, tz: Int): RawTile? {
        if (useHttp(target)) {
            val bytes = httpGet(tileRel(target, level, tx, tz)) ?: return null
            return PngTiles.decodeBlueMap(bytes, PngTiles.crc(bytes))
        }
        val f = tileFile(target, level, tx, tz) ?: return null
        if (!Files.isRegularFile(f)) return null
        val stamp = Files.getLastModifiedTime(f).toMillis() * 31 + Files.size(f)
        val bytes = try { Files.readAllBytes(f) } catch (_: java.nio.file.NoSuchFileException) { return null }
        return PngTiles.decodeBlueMap(bytes, stamp)
    }

    private fun httpGet(relative: String): ByteArray? {
        val base = settings().httpBaseUrl.trimEnd('/')
        if (base.isEmpty()) throw IOException("bluemap.http-base-url is not configured")
        val req = HttpRequest.newBuilder(URI.create("$base/$relative"))
            .timeout(Duration.ofSeconds(10))
            .header("Accept-Encoding", "identity")
            .GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        return when (res.statusCode()) {
            200 -> res.body()
            204, 404 -> null
            else -> throw IOException("BlueMap web server returned HTTP ${res.statusCode()} for $relative")
        }
    }
}
