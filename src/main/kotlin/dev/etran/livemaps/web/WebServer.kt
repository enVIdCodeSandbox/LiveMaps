package dev.etran.livemaps.web

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.etran.livemaps.LiveMapsPlugin
import dev.etran.livemaps.core.CheckResult
import dev.etran.livemaps.core.CreateResult
import dev.etran.livemaps.model.Camera
import dev.etran.livemaps.model.CameraMode
import dev.etran.livemaps.model.Dither
import dev.etran.livemaps.model.Projection
import dev.etran.livemaps.model.SourceRef
import dev.etran.livemaps.model.SourceType
import dev.etran.livemaps.model.ViewSpec
import dev.etran.livemaps.render.ImageResampler
import dev.etran.livemaps.render.ViewRenderer
import dev.etran.livemaps.source.PngTiles
import org.bukkit.Bukkit
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import javax.imageio.ImageIO

/**
 * The embedded web app: a single page plus a small JSON API and a same-origin BlueMap reverse proxy,
 * served by the JDK's built-in HTTP server. Everything requires a session created from a one-time link.
 */
class WebServer(private val plugin: LiveMapsPlugin) {
    val auth = WebAuth(plugin)
    private lateinit var http: HttpServer
    private lateinit var executor: ExecutorService
    private val previews = Semaphore(plugin.cfg.maxConcurrentPreviews)
    private val proxyClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }
    @Volatile private var httpDownUntil: Long = 0L
    private val assetTag = "${plugin.description.version}-${System.currentTimeMillis().toString(36)}"
    private val cfg get() = plugin.cfg
    private val service get() = plugin.service

    private val staticFiles = mapOf(
        "/" to ("index.html" to "text/html; charset=utf-8"),
        "/index.html" to ("index.html" to "text/html; charset=utf-8"),
        "/app.js" to ("app.js" to "text/javascript; charset=utf-8"),
        "/style.css" to ("style.css" to "text/css; charset=utf-8"),
    )

    fun start() {
        executor = Executors.newFixedThreadPool(16) { r -> Thread(r, "LiveMaps-Web").also { it.isDaemon = true } }
        http = HttpServer.create(InetSocketAddress(cfg.webBind, cfg.webPort), 32)
        http.executor = executor
        http.createContext("/") { ex ->
            try { route(ex) } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Web request failed: ${ex.requestURI}", e)
                runCatching { send(ex, 500, error("Internal error")) }
            } finally { ex.close() }
        }
        http.start()
        plugin.logger.info("Web app listening on ${cfg.webBind}:${cfg.webPort} (players use ${plugin.webBaseUrl()})")
    }

    fun stop() {
        runCatching { http.stop(0) }
        runCatching { executor.shutdownNow() }
    }

    // ------------------------------------------------------------------------------------------ routing

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        val method = ex.requestMethod

        if (path == "/bluemap") {
            secureHeaders(ex)
            val q = ex.requestURI.rawQuery?.let { "?$it" } ?: ""
            return redirect(ex, "/bluemap/$q")
        }

        if (path.startsWith("/bluemap/")) {
            val sid = cookie(ex, "lm_sid")
            auth.session(sid) ?: run {
                secureHeaders(ex)
                return sendHtml(ex, 401, "<h3>Not logged in</h3><p>Run <code>/livemap web</code> in Minecraft to get a fresh link.</p>")
            }
            return proxyBlueMap(ex, path.removePrefix("/bluemap/"), method)
        }

        secureHeaders(ex)

        staticFiles[path]?.let { (file, type) ->
            if (method != "GET") return send(ex, 405, error("Method not allowed"))
            val rawBytes = plugin.getResource("web/$file")?.use { it.readBytes() } ?: return send(ex, 404, error("Not found"))
            val bytes = if (file == "index.html") {
                String(rawBytes, Charsets.UTF_8)
                    .replace("href=\"/style.css\"", "href=\"/style.css?v=$assetTag\"")
                    .replace("src=\"/app.js\"", "src=\"/app.js?v=$assetTag\"")
                    .toByteArray(Charsets.UTF_8)
            } else {
                rawBytes
            }
            ex.responseHeaders.add("Content-Type", type)
            ex.responseHeaders.add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            ex.responseHeaders.add("Pragma", "no-cache")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return
        }

        if (path == "/auth") {
            val token = query(ex)["t"] ?: return redirect(ex, "/?login=invalid")
            val sid = auth.redeem(token) ?: return redirect(ex, "/?login=invalid")
            ex.responseHeaders.add("Set-Cookie", "lm_sid=$sid; Path=/; HttpOnly; SameSite=Lax; Max-Age=${cfg.sessionMinutes * 60}")
            return redirect(ex, "/")
        }

        if (!path.startsWith("/api/")) return send(ex, 404, error("Not found"))

        val sid = cookie(ex, "lm_sid")
        val session = auth.session(sid) ?: return send(ex, 401, error("Not logged in. Use /livemap web in game to get a link."))
        // state-changing requests must carry a custom header (browsers won't send it cross-site without CORS approval)
        if (method == "POST" && ex.requestHeaders.getFirst("X-LiveMaps") != "1") return send(ex, 403, error("Missing header"))

        when {
            path == "/api/state" && method == "GET" -> state(ex, session)
            path == "/api/preview" && method == "GET" -> preview(ex, session)
            path == "/api/preview-image" && method == "POST" -> previewImage(ex, session)
            path == "/api/check" && method == "POST" -> check(ex, session)
            path == "/api/create" && method == "POST" -> create(ex, session)
            path == "/api/create-snapshot" && method == "POST" -> createSnapshot(ex, session)
            path == "/api/logout" && method == "POST" -> { auth.end(sid); send(ex, 200, JsonObject().apply { addProperty("ok", true) }) }
            else -> send(ex, 404, error("Not found"))
        }
    }

    // ------------------------------------------------------------------------------------------ BlueMap proxy

    private fun proxyBlueMap(ex: HttpExchange, relRaw: String, method: String) {
        val h = ex.responseHeaders
        h.add("X-Content-Type-Options", "nosniff")
        h.add("X-Frame-Options", "SAMEORIGIN")
        h.add("Referrer-Policy", "no-referrer")
        h.add("Content-Security-Policy", "frame-ancestors 'self'")

        if (method != "GET" && method != "HEAD") {
            return sendHtml(ex, 405, "Method not allowed")
        }
        if (!cfg.bluemap.enabled || plugin.source(SourceType.BLUEMAP)?.isReady() != true) {
            return sendHtml(ex, 503, "<h3>BlueMap is not available</h3><p>BlueMap is not enabled or has not finished starting on this server.</p>")
        }
        val rel = relRaw.trimStart('/')
        if (rel.contains("..") || rel.contains('\\')) {
            return sendHtml(ex, 400, "Bad path")
        }
        val isEntryHtml = rel.isEmpty() || rel.equals("index.html", ignoreCase = true)
        val targetName = rel.ifEmpty { "index.html" }

        val served = if (cfg.bluemap.mode.equals("HTTP", ignoreCase = true)) {
            tryServeHttp(ex, h, rel, isEntryHtml, method) || tryServeLocal(ex, h, rel, targetName, isEntryHtml, method)
        } else {
            tryServeLocal(ex, h, rel, targetName, isEntryHtml, method) ||
                (!cfg.bluemap.mode.equals("FILE", ignoreCase = true) && tryServeHttp(ex, h, rel, isEntryHtml, method))
        }
        if (served) return

        // When BlueMap's HTTP server isn't exposed, avoid console errors in the Web UI from live polling endpoints
        if (rel == "live/players.json" || rel.endsWith("/live/players.json")) {
            return sendRawJson(ex, h, method, "{\"players\":[]}")
        }
        if (rel == "live/markers.json" || rel.endsWith("/live/markers.json")) {
            return sendRawJson(ex, h, method, "{}")
        }

        if (plugin.detectBlueMapWebRoot() == null) {
            return sendHtml(
                ex,
                502,
                "<h3>Could not connect to BlueMap</h3><p>BlueMap web files were not found in <code>plugins/BlueMap/web</code> or <code>bluemap/web</code>, and HTTP upstream is unreachable.</p>"
            )
        }
        sendHtml(ex, 404, "Not found")
    }

    private fun tryServeLocal(
        ex: HttpExchange,
        h: Headers,
        rel: String,
        targetName: String,
        isEntryHtml: Boolean,
        method: String,
    ): Boolean {
        val root = plugin.detectBlueMapWebRoot()?.normalize()
        if (root != null) {
            val resolved = root.resolve(targetName).normalize()
            if (!resolved.startsWith(root)) {
                sendHtml(ex, 403, "Forbidden")
                return true
            }
            var filePath: Path? = resolved.takeIf { Files.isRegularFile(it) }
            var isGz = false
            if (filePath == null) {
                val gz = resolved.resolveSibling(resolved.fileName.toString() + ".gz")
                if (Files.isRegularFile(gz)) {
                    filePath = gz
                    isGz = true
                }
            }
            if (filePath != null) {
                val raw = Files.readAllBytes(filePath)
                if (isEntryHtml && method == "GET") {
                    val html = String(if (isGz) PngTiles.gunzipIfNeeded(raw) else raw, Charsets.UTF_8)
                    val patched = injectBlueMapCaptureShim(html).toByteArray(Charsets.UTF_8)
                    h.add("Content-Type", "text/html; charset=utf-8")
                    h.add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                    h.add("Pragma", "no-cache")
                    ex.sendResponseHeaders(200, patched.size.toLong())
                    ex.responseBody.use { it.write(patched) }
                    return true
                }
                val acceptsGzip = ex.requestHeaders.getFirst("Accept-Encoding")?.contains("gzip", ignoreCase = true) ?: true
                val payload = if (isGz && !acceptsGzip) PngTiles.gunzipIfNeeded(raw) else raw
                h.add("Content-Type", mimeType(targetName))
                if (isGz && acceptsGzip) h.add("Content-Encoding", "gzip")
                h.add("Cache-Control", if (targetName.endsWith(".json")) "no-cache" else "public, max-age=300")
                if (method == "HEAD" || payload.isEmpty()) {
                    ex.sendResponseHeaders(200, -1L)
                } else {
                    ex.sendResponseHeaders(200, payload.size.toLong())
                    ex.responseBody.use { it.write(payload) }
                }
                return true
            }
        }

        // Per-map assets stored via BlueMap's AssetStorage (e.g. SQL storage or custom file storage)
        if (rel.startsWith("maps/")) {
            val sub = rel.removePrefix("maps/")
            val slash = sub.indexOf('/')
            if (slash > 0) {
                val mapId = sub.substring(0, slash)
                val assetPath = sub.substring(slash + 1)
                val bytes = plugin.blueMapState?.readMapAsset?.invoke(mapId, assetPath)
                if (bytes != null) {
                    h.add("Content-Type", mimeType(assetPath))
                    h.add("Cache-Control", if (assetPath.endsWith(".json")) "no-cache" else "public, max-age=300")
                    if (method == "HEAD" || bytes.isEmpty()) {
                        ex.sendResponseHeaders(200, -1L)
                    } else {
                        ex.sendResponseHeaders(200, bytes.size.toLong())
                        ex.responseBody.use { it.write(bytes) }
                    }
                    return true
                }
            }
        }

        return false
    }

    private fun tryServeHttp(
        ex: HttpExchange,
        h: Headers,
        rel: String,
        isEntryHtml: Boolean,
        method: String,
    ): Boolean {
        val base = cfg.bluemap.httpBaseUrl.trim().trimEnd('/').ifEmpty { cfg.bluemapPublicUrl }
        if (base.isEmpty()) return false
        if (System.currentTimeMillis() < httpDownUntil) return false

        return try {
            val querySuffix = ex.requestURI.rawQuery?.let { "?$it" } ?: ""
            val targetUri = URI.create("$base/$rel$querySuffix")
            val builder = HttpRequest.newBuilder(targetUri).timeout(Duration.ofSeconds(10))
            if (method == "HEAD") builder.method("HEAD", HttpRequest.BodyPublishers.noBody()) else builder.GET()
            if (isEntryHtml) {
                builder.header("Accept-Encoding", "identity")
            } else {
                ex.requestHeaders.getFirst("Accept-Encoding")?.let { builder.header("Accept-Encoding", it) }
                ex.requestHeaders.getFirst("If-None-Match")?.let { builder.header("If-None-Match", it) }
                ex.requestHeaders.getFirst("If-Modified-Since")?.let { builder.header("If-Modified-Since", it) }
            }
            val upstream = proxyClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            httpDownUntil = 0L
            val status = upstream.statusCode()
            if (isEntryHtml && status == 200 && method == "GET") {
                val rawHtml = String(PngTiles.gunzipIfNeeded(upstream.body()), Charsets.UTF_8)
                val patched = injectBlueMapCaptureShim(rawHtml).toByteArray(Charsets.UTF_8)
                h.add("Content-Type", "text/html; charset=utf-8")
                h.add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                h.add("Pragma", "no-cache")
                ex.sendResponseHeaders(200, patched.size.toLong())
                ex.responseBody.use { it.write(patched) }
                return true
            }
            for (name in listOf("Content-Type", "Content-Encoding", "Cache-Control", "ETag", "Last-Modified", "Vary")) {
                upstream.headers().firstValue(name).ifPresent { h.add(name, it) }
            }
            val bytes = upstream.body()
            if (method == "HEAD" || status == 204 || status == 304 || bytes.isEmpty()) {
                ex.sendResponseHeaders(status, -1L)
            } else {
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            true
        } catch (_: IOException) {
            httpDownUntil = System.currentTimeMillis() + 30_000L
            false
        }
    }

    private fun sendRawJson(ex: HttpExchange, h: Headers, method: String, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        h.add("Content-Type", "application/json; charset=utf-8")
        h.add("Cache-Control", "no-store")
        if (method == "HEAD") {
            ex.sendResponseHeaders(200, -1L)
        } else {
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    /**
     * Forces WebGL contexts inside the proxied BlueMap iframe to preserve their drawing buffer
     * so the designer page can reliably capture screenshots at any moment.
     */
    private fun injectBlueMapCaptureShim(html: String): String {
        val shim = """<script>(function(){var g=HTMLCanvasElement.prototype.getContext;HTMLCanvasElement.prototype.getContext=function(t,a){if(t==='webgl'||t==='webgl2'||t==='experimental-webgl'){a=Object.assign({},a||{},{preserveDrawingBuffer:true});}return g.call(this,t,a);};})();</script>"""
        val headRegex = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
        val m = headRegex.find(html)
        return if (m != null) {
            html.substring(0, m.range.last + 1) + shim + html.substring(m.range.last + 1)
        } else {
            shim + html
        }
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "wasm" -> "application/wasm"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }

    // ------------------------------------------------------------------------------------------ endpoints

    private fun <T> sync(block: () -> T): T = Bukkit.getScheduler().callSyncMethod(plugin, Callable { block() }).get(15, TimeUnit.SECONDS)

    private fun state(ex: HttpExchange, s: WebSession) {
        val o = sync {
            val p = Bukkit.getPlayer(s.player) ?: return@sync null
            val eye = p.eyeLocation
            val bmReady = plugin.source(SourceType.BLUEMAP)?.isReady() == true
            JsonObject().apply {
                add("player", JsonObject().apply {
                    addProperty("name", p.name)
                    addProperty("gamemode", p.gameMode.name)
                    addProperty("world", p.world.name)
                    addProperty("x", eye.x); addProperty("y", eye.y); addProperty("z", eye.z)
                    // our camera convention: compass yaw (0 = north), tilt from straight down
                    addProperty("lookYaw", ((eye.yaw + 180.0) % 360.0 + 360.0) % 360.0)
                    addProperty("lookTilt", (90.0 - eye.pitch).coerceIn(0.0, 180.0))
                })
                val free = service.isFree(p)
                addProperty("free", free)
                add("cost", JsonObject().apply {
                    addProperty("enabled", cfg.costEnabled)
                    addProperty("material", cfg.costMaterial.name.lowercase().replace('_', ' '))
                    addProperty("perMap", cfg.costPerMap)
                    addProperty("have", service.count(p, cfg.costMaterial))
                })
                val bypass = p.hasPermission("livemaps.bypass.limits")
                add("limits", JsonObject().apply {
                    addProperty("minWidth", if (bypass) 1 else cfg.minWidth); addProperty("minHeight", if (bypass) 1 else cfg.minHeight)
                    addProperty("maxWidth", if (bypass) 64 else cfg.maxWidth); addProperty("maxHeight", if (bypass) 64 else cfg.maxHeight)
                    addProperty("warnTotal", cfg.warnTotal)
                    addProperty("maxViewDistance", cfg.renderSettings.maxViewDistance)
                })
                addProperty("updateSeconds", cfg.updateIntervalSeconds)
                addProperty("bluemapUrl", cfg.bluemapPublicUrl.ifEmpty { if (bmReady) "/bluemap/" else "" })
                if (bmReady) addProperty("bluemapEmbedUrl", "/bluemap/")
                add("sources", JsonArray().also { arr ->
                    for (t in SourceType.values()) {
                        val src = plugin.source(t)
                        arr.add(JsonObject().apply {
                            addProperty("type", t.name)
                            addProperty("ready", src?.isReady() == true)
                            add("targets", JsonArray().also { ta ->
                                src?.takeIf { it.isReady() }?.targets()?.forEach { ti ->
                                    ta.add(JsonObject().apply {
                                        addProperty("id", ti.id); addProperty("name", ti.name)
                                        ti.world?.let { addProperty("world", it) }
                                    })
                                }
                            })
                        })
                    }
                })
                add("mySnapshots", JsonArray().also { arr ->
                    service.maps.values
                        .filter { it.spec.snapshot && (it.owner == p.uniqueId || p.hasPermission("livemaps.admin")) }
                        .sortedByDescending { it.id }
                        .forEach { m ->
                            arr.add(JsonObject().apply {
                                addProperty("id", m.id)
                                addProperty("ownerName", m.ownerName)
                                addProperty("target", m.spec.source.target)
                                addProperty("width", m.width)
                                addProperty("height", m.height)
                                addProperty("dither", m.spec.options.dither.name)
                                addProperty("bluemapHash", m.spec.bluemapHash)
                                m.spec.refreshIntervalSeconds?.let { addProperty("refreshIntervalSeconds", it) }
                            })
                        }
                })
                val d = cfg.defaultOptions
                add("defaults", JsonObject().apply {
                    addProperty("shading", d.shading); addProperty("dither", d.dither.name); addProperty("upscale", d.upscale.name)
                    addProperty("supersample", d.supersample); addProperty("shadows", d.shadows); addProperty("fog", d.fog)
                    addProperty("viewDistance", cfg.renderSettings.viewDistance)
                })
            }
        } ?: return send(ex, 403, error("You need to be online in game to use the web app."))
        send(ex, 200, o)
    }

    private fun specFrom(o: JsonObject): ViewSpec = ViewSpec.fromJson(o, cfg.defaultOptions)

    private fun preview(ex: HttpExchange, s: WebSession) {
        val q = query(ex)
        val spec = try { specFrom(JsonObject().also { o -> q.forEach { (k, v) -> o.addProperty(k, v) } }) }
        catch (e: IllegalArgumentException) { return send(ex, 400, error(e.message ?: "Bad request")) }

        val bypass = sync { Bukkit.getPlayer(s.player)?.hasPermission("livemaps.bypass.limits") == true }
        val maxW = if (bypass) 64 else cfg.maxWidth
        val maxH = if (bypass) 64 else cfg.maxHeight
        if (spec.widthMaps > maxW || spec.heightMaps > maxH) return send(ex, 400, error("Preview is limited to $maxW x $maxH maps."))

        val now = System.currentTimeMillis()
        if (now - s.lastPreview < 120) return send(ex, 429, error("Slow down"))
        s.lastPreview = now

        val type = spec.source.type
        val ctx = service.ctxFor(type, previewing = true) ?: return send(ex, 503, error("${type.name.lowercase()} is not available"))
        val quant = service.quantizer ?: return send(ex, 503, error("Renderer is still starting"))
        if (!previews.tryAcquire(20, TimeUnit.SECONDS)) return send(ex, 503, error("Server busy, try again"))
        val png: ByteArray
        try {
            val res = ctx.pool!!.submit(Callable { ViewRenderer.render(ctx, spec) }).get(60, TimeUnit.SECONDS)
            val argb = if (q["palette"] == "0") res.argb
            else quant.toRgb(quant.quantize(res.argb, res.width, res.height, spec.options.dither))
            val img = BufferedImage(res.width, res.height, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, res.width, res.height, argb, 0, res.width)
            val bos = ByteArrayOutputStream(res.width * res.height / 2)
            ImageIO.write(img, "png", bos)
            png = bos.toByteArray()
        } catch (e: java.util.concurrent.ExecutionException) {
            val m = (e.cause?.message ?: "render failed")
            return send(ex, 422, error(m))
        } finally { previews.release() }
        ex.responseHeaders.add("Content-Type", "image/png")
        ex.responseHeaders.add("Cache-Control", "no-store")
        runCatching { ViewRenderer.describeDetail(ctx, spec) }.getOrNull()?.let { ex.responseHeaders.add("X-LiveMaps-Detail", it) }
        ex.sendResponseHeaders(200, png.size.toLong())
        ex.responseBody.use { it.write(png) }
    }

    private fun previewImage(ex: HttpExchange, s: WebSession) {
        val body = try { readJson(ex, maxBytes = 24 * 1024 * 1024) } catch (e: Exception) {
            return send(ex, 400, error(e.message ?: "Bad request"))
        }
        val widthMaps = body.get("width")?.let { runCatching { it.asInt }.getOrNull() }?.coerceIn(1, 64)
            ?: return send(ex, 400, error("Missing 'width'"))
        val heightMaps = body.get("height")?.let { runCatching { it.asInt }.getOrNull() }?.coerceIn(1, 64)
            ?: return send(ex, 400, error("Missing 'height'"))
        val ditherStr = body.get("dither")?.takeIf { !it.isJsonNull }?.asString
        val dither = Dither.values().firstOrNull { it.name.equals(ditherStr, true) } ?: cfg.defaultOptions.dither
        val usePalette = body.get("palette")?.let { runCatching { it.asBoolean }.getOrNull() } ?: true
        val rawImage = body.get("image")?.takeIf { !it.isJsonNull }?.asString ?: return send(ex, 400, error("Missing 'image'"))

        val bypass = sync { Bukkit.getPlayer(s.player)?.hasPermission("livemaps.bypass.limits") == true }
        val maxW = if (bypass) 64 else cfg.maxWidth
        val maxH = if (bypass) 64 else cfg.maxHeight
        if (widthMaps > maxW || heightMaps > maxH) return send(ex, 400, error("Preview is limited to $maxW x $maxH maps."))

        val img = try { decodeImage(rawImage) } catch (e: IllegalArgumentException) {
            return send(ex, 400, error(e.message ?: "Invalid image"))
        }
        val targetW = widthMaps * 128
        val targetH = heightMaps * 128
        val quant = service.quantizer ?: return send(ex, 503, error("Renderer is still starting"))
        val srcArgb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val argb = ImageResampler.resample(srcArgb, img.width, img.height, targetW, targetH)
        val outArgb = if (!usePalette) argb else quant.toRgb(quant.quantize(argb, targetW, targetH, dither))
        val outImg = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        outImg.setRGB(0, 0, targetW, targetH, outArgb, 0, targetW)
        val bos = ByteArrayOutputStream(targetW * targetH / 2)
        ImageIO.write(outImg, "png", bos)
        val png = bos.toByteArray()
        ex.responseHeaders.add("Content-Type", "image/png")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, png.size.toLong())
        ex.responseBody.use { it.write(png) }
    }

    private fun decodeImage(raw: String, maxDim: Int = 4096): BufferedImage {
        val base64 = raw.substringAfter("base64,", raw).trim()
        val bytes = try { Base64.getDecoder().decode(base64) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid base64 image data")
        }
        val img = try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (_: Exception) { null }
            ?: throw IllegalArgumentException("Could not decode PNG image")
        if (img.width < 1 || img.height < 1 || img.width > maxDim || img.height > maxDim) {
            throw IllegalArgumentException("Screenshot size (${img.width}x${img.height}) must be within 1..${maxDim} pixels.")
        }
        return img
    }

    private fun readJson(ex: HttpExchange, maxBytes: Int = 32 * 1024): JsonObject {
        val body = ex.requestBody.readNBytes(maxBytes + 1)
        if (body.size > maxBytes) throw IllegalArgumentException("Request too large")
        return JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject
    }

    private fun checkJson(c: CheckResult) = JsonObject().apply {
        addProperty("ok", c.ok)
        add("errors", JsonArray().also { a -> c.errors.forEach { a.add(it) } })
        add("warnings", JsonArray().also { a -> c.warnings.forEach { a.add(it) } })
        addProperty("cost", c.cost); addProperty("have", c.have); addProperty("free", c.free)
        addProperty("needsConfirm", c.needsConfirm)
    }

    private fun check(ex: HttpExchange, s: WebSession) {
        val body = try { readJson(ex) } catch (e: Exception) { return send(ex, 400, error(e.message ?: "Bad request")) }
        val spec = try { specFrom(body) } catch (e: Exception) { return send(ex, 400, error(e.message ?: "Bad request")) }
        val forUpdate = (body.get("updateId")?.let { runCatching { it.asInt }.getOrNull() } ?: 0) > 0
        val res = sync { Bukkit.getPlayer(s.player)?.let { service.check(it, spec, forUpdate) } }
            ?: return send(ex, 403, error("You need to be online in game."))
        send(ex, 200, checkJson(res))
    }

    private fun create(ex: HttpExchange, s: WebSession) {
        val body = try { readJson(ex) } catch (e: Exception) { return send(ex, 400, error(e.message ?: "Bad request")) }
        val spec = try { specFrom(body) } catch (e: Exception) { return send(ex, 400, error(e.message ?: "Bad request")) }
        val confirm = body.get("confirmLarge")?.let { runCatching { it.asBoolean }.getOrNull() } == true
        val player = sync { Bukkit.getPlayer(s.player) } ?: return send(ex, 403, error("You need to be online in game."))
        val result = try { service.createFor(player, spec, confirm).get(90, TimeUnit.SECONDS) }
        catch (e: Exception) { return send(ex, 504, error("Timed out while creating the map")) }
        when (result) {
            is CreateResult.Ok -> send(ex, 200, JsonObject().apply {
                addProperty("ok", true)
                addProperty("id", result.live.id)
                addProperty("message", "Live map #${result.live.id} created - check your inventory!")
            })
            is CreateResult.Fail -> send(ex, 200, JsonObject().apply {
                addProperty("ok", false)
                addProperty("error", result.message)
                add("warnings", JsonArray().also { a -> result.warnings.forEach { a.add(it) } })
            })
        }
    }

    private fun createSnapshot(ex: HttpExchange, s: WebSession) {
        val body = try { readJson(ex, maxBytes = 24 * 1024 * 1024) } catch (e: Exception) {
            return send(ex, 400, error(e.message ?: "Bad request"))
        }
        val target = body.get("target")?.takeIf { !it.isJsonNull }?.asString?.trim()?.take(128).orEmpty().ifBlank { "world" }
        val widthMaps = body.get("width")?.let { runCatching { it.asInt }.getOrNull() }?.coerceIn(1, 64)
            ?: return send(ex, 400, error("Missing 'width'"))
        val heightMaps = body.get("height")?.let { runCatching { it.asInt }.getOrNull() }?.coerceIn(1, 64)
            ?: return send(ex, 400, error("Missing 'height'"))
        val ditherStr = body.get("dither")?.takeIf { !it.isJsonNull }?.asString
        val dither = Dither.values().firstOrNull { it.name.equals(ditherStr, true) } ?: cfg.defaultOptions.dither
        val confirm = body.get("confirmLarge")?.let { runCatching { it.asBoolean }.getOrNull() } == true
        val updateId = body.get("updateId")?.let { runCatching { it.asInt }.getOrNull() } ?: 0
        val bluemapHash = body.get("bluemapHash")?.takeIf { !it.isJsonNull }?.asString?.trim()?.take(512) ?: ""
        val rawRefresh = body.get("refreshIntervalSeconds")?.takeIf { !it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }
        val refreshIntervalSeconds = rawRefresh?.takeIf { it > 0 }?.coerceIn(ViewSpec.MIN_REFRESH_SECONDS, ViewSpec.MAX_REFRESH_SECONDS)
        val rawImage = body.get("image")?.takeIf { !it.isJsonNull }?.asString ?: return send(ex, 400, error("Missing 'image'"))

        val img = try { decodeImage(rawImage) } catch (e: IllegalArgumentException) {
            return send(ex, 400, error(e.message ?: "Invalid image"))
        }
        val targetW = widthMaps * 128
        val targetH = heightMaps * 128
        val srcArgb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val argb = ImageResampler.resample(srcArgb, img.width, img.height, targetW, targetH)
        val player = sync { Bukkit.getPlayer(s.player) } ?: return send(ex, 403, error("You need to be online in game."))

        val result = try {
            if (updateId > 0) {
                service.updateSnapshotFor(player, updateId, argb, targetW, targetH, dither, bluemapHash, target, refreshIntervalSeconds).get(90, TimeUnit.SECONDS)
            } else {
                val spec = ViewSpec(
                    source = SourceRef(SourceType.BLUEMAP, target),
                    camera = Camera(CameraMode.FREE),
                    projection = Projection.PERSPECTIVE,
                    widthMaps = widthMaps,
                    heightMaps = heightMaps,
                    options = cfg.defaultOptions.copy(dither = dither),
                    snapshot = true,
                    bluemapHash = bluemapHash,
                    refreshIntervalSeconds = refreshIntervalSeconds,
                )
                service.createSnapshotFor(player, spec, argb, targetW, targetH, confirm).get(90, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            return send(ex, 504, error("Timed out while saving the map"))
        }
        when (result) {
            is CreateResult.Ok -> send(ex, 200, JsonObject().apply {
                addProperty("ok", true)
                addProperty("id", result.live.id)
                addProperty(
                    "message",
                    if (updateId > 0) "Updated live map #${result.live.id}! Any placed frames will refresh shortly."
                    else "Live map #${result.live.id} created - check your inventory!"
                )
            })
            is CreateResult.Fail -> send(ex, 200, JsonObject().apply {
                addProperty("ok", false)
                addProperty("error", result.message)
                add("warnings", JsonArray().also { a -> result.warnings.forEach { a.add(it) } })
            })
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private fun error(msg: String) = JsonObject().apply { addProperty("ok", false); addProperty("error", msg) }

    private fun send(ex: HttpExchange, code: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        ex.responseHeaders.add("Pragma", "no-cache")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sendHtml(ex: HttpExchange, code: Int, html: String) {
        val bytes = ("<!doctype html><meta charset=\"utf-8\"><style>body{font-family:system-ui,sans-serif;background:#14161a;color:#e6e8ec;padding:24px}</style>" + html).toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        ex.responseHeaders.add("Pragma", "no-cache")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun redirect(ex: HttpExchange, to: String) {
        ex.responseHeaders.add("Location", to)
        ex.responseHeaders.add("Cache-Control", "no-store, max-age=0")
        ex.sendResponseHeaders(302, -1)
    }

    private fun secureHeaders(ex: HttpExchange) {
        val h = ex.responseHeaders
        h.add("X-Content-Type-Options", "nosniff")
        h.add("X-Frame-Options", "DENY")
        h.add("Referrer-Policy", "no-referrer")
        h.add("Content-Security-Policy", "default-src 'self'; img-src 'self' blob: data:; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-src 'self'; frame-ancestors 'none'")
    }

    private fun query(ex: HttpExchange): Map<String, String> {
        val raw = ex.requestURI.rawQuery ?: return emptyMap()
        val m = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = URLDecoder.decode(if (i < 0) part else part.substring(0, i), Charsets.UTF_8)
            val v = if (i < 0) "" else URLDecoder.decode(part.substring(i + 1), Charsets.UTF_8)
            if (k.length <= 40 && v.length <= 200) m[k] = v
        }
        return m
    }

    private fun cookie(ex: HttpExchange, name: String): String? {
        for (h in ex.requestHeaders["Cookie"] ?: return null) {
            for (c in h.split(';')) {
                val t = c.trim()
                if (t.startsWith("$name=")) return t.substring(name.length + 1)
            }
        }
        return null
    }
}
