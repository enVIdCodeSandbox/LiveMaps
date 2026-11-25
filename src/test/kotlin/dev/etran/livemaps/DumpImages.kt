package dev.etran.livemaps

import dev.etran.livemaps.model.*
import dev.etran.livemaps.render.ViewRenderer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin
import kotlin.math.cos
import kotlin.test.Test

/** Writes sample renders to build/dump when -Dlivemaps.dump=true (visual inspection helper, not an assertion). */
class DumpImages {
    @Test fun dump() {
        if (System.getProperty("livemaps.dump") != "true") return
        val s = FakeSource(levels = listOf(dev.etran.livemaps.source.LevelInfo(1, 1, 256)))
        fun terrain(x: Int, z: Int): Int {
            val h = 64 + (14 * sin(x / 23.0) * cos(z / 17.0) + 9 * sin((x + z) / 11.0)).toInt()
            return h
        }
        for (tx in -1..1) for (tz in -1..1) {
            s.put(1, tx, tz, makeTile(256, color = { x, z ->
                val wx = tx * 256 + x; val wz = tz * 256 + z
                val h = terrain(wx, wz)
                val col = when {
                    h < 62 -> 0xFF3F76E4.toInt()
                    h < 66 -> 0xFFD8C98F.toInt()
                    h < 78 -> 0xFF5B9E3A.toInt()
                    h < 86 -> 0xFF8A8A8A.toInt()
                    else -> 0xFFF4F7F7.toInt()
                }
                col
            }, height = { x, z -> terrain(tx * 256 + x, tz * 256 + z).coerceAtLeast(62) }))
        }
        val out = File("build/dump"); out.mkdirs()
        val c = ctx(s, 300)
        fun save(name: String, v: ViewSpec) {
            val r = ViewRenderer.render(c, v)
            val img = BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, r.width, r.height, r.argb, 0, r.width)
            ImageIO.write(img, "png", File(out, "$name.png"))
        }
        val opts = ViewOptions(shading = 0.7, supersample = 2, fog = 0.35)
        save("flat", view(0.0, 0.0, zoom = 1.0, w = 3, h = 3, options = opts))
        save("flat_rot", view(0.0, 0.0, zoom = 1.0, yaw = 35.0, w = 3, h = 3, options = opts))
        save("orbit_persp", view(0.0, 0.0, mode = CameraMode.ORBIT, yaw = 20.0, tilt = 62.0, projection = Projection.PERSPECTIVE, distance = 110.0, w = 4, h = 3, options = opts))
        save("orbit_iso", view(0.0, 0.0, mode = CameraMode.ORBIT, yaw = 45.0, tilt = 50.0, zoom = 0.5, w = 3, h = 3, options = opts.copy(shadows = true)))
        save("free_low", view(-120.0, 120.0, y = 100.0, mode = CameraMode.FREE, yaw = 330.0, tilt = 84.0, projection = Projection.PERSPECTIVE, w = 4, h = 2, options = opts))
    }
}
