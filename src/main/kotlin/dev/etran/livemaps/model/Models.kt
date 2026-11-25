package dev.etran.livemaps.model

import com.google.gson.JsonObject
import kotlin.math.max
import kotlin.math.min

enum class SourceType { BLUEMAP, SQUAREMAP }
enum class Dither { NONE, ORDERED, FLOYD_STEINBERG }
enum class Upscale { NEAREST, BILINEAR }
enum class Projection { ORTHOGRAPHIC, PERSPECTIVE }

/** FLAT = straight-down top view, ORBIT = look at a target from a distance, FREE = free-flight camera. */
enum class CameraMode { FLAT, ORBIT, FREE }

/** What a view is rendered from. [target] is a BlueMap map id, or a squaremap world id like `minecraft:overworld`. */
data class SourceRef(val type: SourceType, val target: String)

/**
 * Camera description, shared by all modes.
 *
 * Angles: [yaw] is a compass heading in degrees (0 = looking north, 90 = east).
 * [tilt] is measured from straight down: 0 = looking straight down, 90 = horizontal, 180 = straight up.
 * For FLAT/ORBIT the (x, y, z) is the point being looked at, for FREE it is the camera position.
 * [y] may be null for FLAT/ORBIT, in which case the terrain height at (x, z) is used.
 */
data class Camera(
    val mode: CameraMode = CameraMode.FLAT,
    val x: Double = 0.0,
    val y: Double? = null,
    val z: Double = 0.0,
    val yaw: Double = 0.0,
    val tilt: Double = 0.0,
)

data class ViewOptions(
    /** 0..1 relief shading strength for flat views. */
    val shading: Double = 0.6,
    val dither: Dither = Dither.FLOYD_STEINBERG,
    val upscale: Upscale = Upscale.NEAREST,
    /** Samples per pixel along each axis for 3D views (1..3). */
    val supersample: Int = 2,
    /** Cast shadows in 3D views (slower). */
    val shadows: Boolean = false,
    /** 0..1 distance fog for perspective views. */
    val fog: Double = 0.25,
    /** Sun azimuth (compass degrees) and elevation (degrees above horizon) for 3D lighting. */
    val sunAzimuth: Double = 300.0,
    val sunElevation: Double = 45.0,
    /** 3D views: how far (blocks) the camera can see. 0 = the server default. */
    val viewDistance: Int = 0,
    /**
     * 3D perspective views: distance (blocks) out to which the source's finest resolution level is used.
     * Beyond it coarser levels take over, like BlueMap's hires/lowres distances. 0 = automatic.
     */
    val detailDistance: Int = 0,
)

/** Everything needed to render one live map (or a static snapshot captured from BlueMap). */
data class ViewSpec(
    val source: SourceRef,
    val camera: Camera,
    val projection: Projection = Projection.ORTHOGRAPHIC,
    /** Orthographic scale: blocks per map pixel. Larger = more zoomed out. */
    val zoom: Double = 1.0,
    /** ORBIT/perspective: distance between camera and target in blocks. */
    val distance: Double = 128.0,
    /** Vertical field of view in degrees (perspective). */
    val fov: Double = 70.0,
    val widthMaps: Int = 1,
    val heightMaps: Int = 1,
    val options: ViewOptions = ViewOptions(),
    /** True when created from a browser BlueMap WebGL screenshot (never overwritten by background refreshes). */
    val snapshot: Boolean = false,
    /** Optional BlueMap URL hash (e.g. `#world:x:y:z:...`) for jumping back when re-capturing. */
    val bluemapHash: String = "",
    /** Optional per-map re-render interval in seconds; null = use `update.interval-seconds` from config.yml. */
    val refreshIntervalSeconds: Int? = null,
) {
    val pixelWidth get() = widthMaps * 128
    val pixelHeight get() = heightMaps * 128
    val totalMaps get() = widthMaps * heightMaps

    /** A flat top-down orthographic view (what squaremap always is) */
    val isFlat: Boolean
        get() = !snapshot && projection == Projection.ORTHOGRAPHIC && camera.mode != CameraMode.FREE && camera.tilt <= 0.01

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("source", source.type.name)
        addProperty("target", source.target)
        addProperty("mode", camera.mode.name)
        addProperty("x", camera.x)
        camera.y?.let { addProperty("y", it) }
        addProperty("z", camera.z)
        addProperty("yaw", camera.yaw)
        addProperty("tilt", camera.tilt)
        addProperty("projection", projection.name)
        addProperty("zoom", zoom)
        addProperty("distance", distance)
        addProperty("fov", fov)
        addProperty("width", widthMaps)
        addProperty("height", heightMaps)
        addProperty("shading", options.shading)
        addProperty("dither", options.dither.name)
        addProperty("upscale", options.upscale.name)
        addProperty("supersample", options.supersample)
        addProperty("shadows", options.shadows)
        addProperty("fog", options.fog)
        addProperty("sunAzimuth", options.sunAzimuth)
        addProperty("sunElevation", options.sunElevation)
        addProperty("viewDistance", options.viewDistance)
        addProperty("detailDistance", options.detailDistance)
        if (snapshot) addProperty("snapshot", true)
        if (bluemapHash.isNotEmpty()) addProperty("bluemapHash", bluemapHash)
        refreshIntervalSeconds?.let { addProperty("refreshIntervalSeconds", it) }
    }

    companion object {
        const val MIN_ZOOM = 0.125
        const val MAX_ZOOM = 256.0
        const val MIN_REFRESH_SECONDS = 5
        const val MAX_REFRESH_SECONDS = 86400

        /** Parses and clamps a view from JSON (used for web requests and persisted data). Unknown fields fall back to [defaults]. */
        fun fromJson(o: JsonObject, defaults: ViewOptions = ViewOptions()): ViewSpec {
            fun str(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
            fun num(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf { it.isFinite() }
            fun bool(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() }
            fun <E : Enum<E>> enm(k: String, values: Array<E>): E? = str(k)?.let { s -> values.firstOrNull { it.name.equals(s, true) } }

            val type = enm("source", SourceType.values()) ?: throw IllegalArgumentException("Missing or invalid 'source'")
            val target = str("target")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: throw IllegalArgumentException("Missing 'target'")
            val mode = enm("mode", CameraMode.values()) ?: CameraMode.FLAT
            val camera = Camera(
                mode = mode,
                x = (num("x") ?: 0.0).coerceIn(-3.0e7, 3.0e7),
                y = num("y")?.coerceIn(-4096.0, 4096.0),
                z = (num("z") ?: 0.0).coerceIn(-3.0e7, 3.0e7),
                yaw = (((num("yaw") ?: 0.0) % 360.0) + 360.0) % 360.0,
                tilt = (num("tilt") ?: 0.0).coerceIn(0.0, 180.0),
            )
            val defaultProjection = if (mode == CameraMode.FLAT) Projection.ORTHOGRAPHIC else Projection.PERSPECTIVE
            val d = defaults
            val snapshot = bool("snapshot") ?: false
            val bluemapHash = (str("bluemapHash") ?: "").trim().take(512)
            val rawRefresh = num("refreshIntervalSeconds") ?: num("refreshSeconds") ?: num("refresh")
            val refreshIntervalSeconds = rawRefresh?.toInt()?.takeIf { it > 0 }?.coerceIn(MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS)
            return ViewSpec(
                source = SourceRef(type, target),
                camera = if (mode == CameraMode.FLAT) camera.copy(tilt = 0.0) else camera,
                projection = if (mode == CameraMode.FLAT) Projection.ORTHOGRAPHIC else (enm("projection", Projection.values()) ?: defaultProjection),
                zoom = (num("zoom") ?: 1.0).coerceIn(MIN_ZOOM, MAX_ZOOM),
                distance = (num("distance") ?: 128.0).coerceIn(2.0, 4096.0),
                fov = (num("fov") ?: 70.0).coerceIn(20.0, 120.0),
                widthMaps = (num("width") ?: 1.0).toInt().coerceIn(1, 64),
                heightMaps = (num("height") ?: 1.0).toInt().coerceIn(1, 64),
                options = ViewOptions(
                    shading = (num("shading") ?: d.shading).coerceIn(0.0, 1.0),
                    dither = enm("dither", Dither.values()) ?: d.dither,
                    upscale = enm("upscale", Upscale.values()) ?: d.upscale,
                    supersample = (num("supersample") ?: d.supersample.toDouble()).toInt().coerceIn(1, 3),
                    shadows = bool("shadows") ?: d.shadows,
                    fog = (num("fog") ?: d.fog).coerceIn(0.0, 1.0),
                    sunAzimuth = (num("sunAzimuth") ?: d.sunAzimuth).let { ((it % 360) + 360) % 360 },
                    sunElevation = (num("sunElevation") ?: d.sunElevation).coerceIn(5.0, 90.0),
                    viewDistance = (num("viewDistance") ?: d.viewDistance.toDouble()).toInt().let { if (it <= 0) 0 else it.coerceIn(32, 16384) },
                    detailDistance = (num("detailDistance") ?: d.detailDistance.toDouble()).toInt().let { if (it <= 0) 0 else it.coerceIn(16, 16384) },
                ),
                snapshot = snapshot,
                bluemapHash = bluemapHash,
                refreshIntervalSeconds = refreshIntervalSeconds,
            )
        }
    }
}

internal fun clampInt(v: Int, lo: Int, hi: Int) = max(lo, min(hi, v))
