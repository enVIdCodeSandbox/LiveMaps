package dev.etran.livemaps

import dev.etran.livemaps.model.*
import dev.etran.livemaps.render.CameraBasis
import dev.etran.livemaps.render.RayRenderer
import dev.etran.livemaps.render.ViewRenderer
import kotlin.math.abs
import kotlin.test.*

class RayRendererTest {
    private val green = 0xFF40A040.toInt()
    private val red = 0xFFD02020.toInt()

    /** flat green plain at height 63, with a 6x6 red tower 30 blocks tall at x,z in 40..45 */
    private fun world(): FakeSource {
        val s = FakeSource()
        for (tx in -1..1) for (tz in -1..1) {
            val tower = tx == 0 && tz == 0
            s.put(1, tx, tz, makeTile(64, color = { x, z -> if (tower && x in 40..45 && z in 40..45) red else green },
                height = { x, z -> if (tower && x in 40..45 && z in 40..45) 93 else 63 }))
        }
        return s
    }

    private val plain = ViewOptions(shading = 0.0, supersample = 1, fog = 0.0)

    @Test fun cameraBasisIsOrthonormalAndOriented() {
        val b = CameraBasis(0.0, 90.0) // looking north, horizontal
        assertEquals(0.0, b.fx, 1e-9); assertEquals(0.0, b.fy, 1e-9); assertEquals(-1.0, b.fz, 1e-9)
        assertEquals(1.0, b.rx, 1e-9) // right = east
        assertEquals(1.0, b.uy, 1e-9) // up = +y
        val d = CameraBasis(90.0, 0.0) // straight down, heading east: screen up = east
        assertEquals(-1.0, d.fy, 1e-9)
        assertEquals(1.0, d.ux, 1e-9)
        for (yaw in listOf(0.0, 33.0, 200.0)) for (tilt in listOf(10.0, 60.0, 130.0)) {
            val c = CameraBasis(yaw, tilt)
            assertEquals(1.0, c.fx * c.fx + c.fy * c.fy + c.fz * c.fz, 1e-9)
            assertEquals(0.0, c.fx * c.rx + c.fy * c.ry + c.fz * c.rz, 1e-9)
            assertEquals(0.0, c.fx * c.ux + c.fy * c.uy + c.fz * c.uz, 1e-9)
            assertEquals(0.0, c.rx * c.ux + c.ry * c.uy + c.rz * c.uz, 1e-9)
        }
    }

    @Test fun orthoStraightDownMatchesTerrainColors() {
        val s = world()
        // ORBIT mode with tilt 0 ortho is "flat"; force the ray path by using a tiny tilt
        val v = view(32.0, 32.0, mode = CameraMode.ORBIT, tilt = 0.001, zoom = 1.0, options = plain)
        val r = RayRenderer.render(ctx(s), v, s.geometry("t")!!)
        // world (42,42) is the tower top -> screen (64+10, 64+10)
        val tower = r.argb[(64 + 10) * 128 + (64 + 10)]
        val ground = r.argb[(64 - 20) * 128 + (64 - 20)]
        assertEquals(rgb(red), rgb(tower))
        assertEquals(rgb(green), rgb(ground))
    }

    @Test fun tiltedViewSeesTowerSideAndSky() {
        val s = world()
        // camera south of the tower looking north, 60 degrees from straight down
        val v = view(42.0, 42.0, mode = CameraMode.ORBIT, y = 70.0, yaw = 0.0, tilt = 70.0,
            projection = Projection.PERSPECTIVE, distance = 40.0, options = plain)
        val r = RayRenderer.render(ctx(s), v, s.geometry("t")!!).argb
        // the screen must contain red (tower), green (ground) and sky-ish (blue) pixels
        var reds = 0; var greens = 0; var sky = 0
        for (c in r) {
            val rr = (c shr 16) and 255; val gg = (c shr 8) and 255; val bb = c and 255
            if (rr > 60 && rr > gg * 3 / 2 && rr > bb * 3 / 2) reds++
            if (gg > rr && gg > bb && gg > 90) greens++
            if (bb > 200 && bb > rr) sky++
        }
        assertTrue(reds > 200, "tower visible ($reds)")
        assertTrue(greens > 2000, "ground visible ($greens)")
        assertTrue(sky > 500, "sky visible ($sky)")
    }

    @Test fun towerOccludesGroundBehindIt() {
        val s = world()
        // looking north horizontally from south of the tower at tower mid-height: centre pixel must hit the tower, not the far plain
        val v = view(42.0, 62.0, mode = CameraMode.FREE, y = 78.0, yaw = 0.0, tilt = 90.0,
            projection = Projection.PERSPECTIVE, options = plain)
        val r = RayRenderer.render(ctx(s), v, s.geometry("t")!!).argb
        val c = r[64 * 128 + 64]
        assertTrue(((c shr 16) and 255) > ((c shr 8) and 255) + 25, "centre should be the red tower, got ${Integer.toHexString(c)}")
    }

    @Test fun lookingStraightUpIsSky() {
        val s = world()
        val v = view(32.0, 32.0, mode = CameraMode.FREE, y = 80.0, tilt = 180.0, projection = Projection.PERSPECTIVE, options = plain)
        val r = RayRenderer.render(ctx(s), v, s.geometry("t")!!).argb
        val c = r[64 * 128 + 64]
        assertTrue((c and 255) > 200, "sky should be blueish, got ${Integer.toHexString(c)}")
    }

    @Test fun sideFacesAreShadedDifferentlyFromTops() {
        val s = world()
        val v = view(42.0, 20.0, mode = CameraMode.ORBIT, y = 80.0, yaw = 0.0, tilt = 45.0,
            projection = Projection.ORTHOGRAPHIC, zoom = 0.5, options = plain)
        val r = RayRenderer.render(ctx(s), v, s.geometry("t")!!).argb
        val reds = r.filter { c -> ((c shr 16) and 255) > 100 && ((c shr 8) and 255) < 90 }.map { rgb(it) }.toSet()
        assertTrue(reds.size >= 2, "tower top and side should have different shades, got ${reds.size} distinct")
    }

    @Test fun shadowsDarkenGroundBesideTower() {
        val s = world()
        val base = view(42.0, 42.0, mode = CameraMode.ORBIT, y = 63.0, tilt = 0.001, zoom = 0.5, options = plain)
        val lit = RayRenderer.render(ctx(s), base, s.geometry("t")!!).argb
        val shadowed = RayRenderer.render(ctx(s), base.copy(options = plain.copy(shadows = true, sunElevation = 25.0, sunAzimuth = 270.0)), s.geometry("t")!!).argb
        val diff = lit.indices.count { abs((lit[it] and 255) - (shadowed[it] and 255)) > 5 }
        assertTrue(diff > 300, "shadow should change a good number of pixels ($diff)")
    }

    @Test fun squaremapStyleSourceFallsBackToFlat() {
        val s = FakeSource(hasHeight = false)
        s.put(1, 0, 0, makeTile(64, color = { _, _ -> green }, height = null))
        val v = view(32.0, 32.0, mode = CameraMode.ORBIT, tilt = 60.0, projection = Projection.PERSPECTIVE)
        val r = ViewRenderer.render(ctx(s), v)
        assertEquals(rgb(green), rgb(r.argb[64 * 128 + 64]))
    }

    @Test fun supersamplingKeepsEdgesSmooth() {
        val s = world()
        val v = view(42.0, 42.0, mode = CameraMode.ORBIT, y = 63.0, tilt = 0.001, zoom = 0.6, options = plain.copy(supersample = 3))
        val r = RayRenderer.render(ctx(s), v, s.geometry("t")!!).argb
        // there must be blended colors between green and red at the tower boundary
        val blended = r.count { c -> val rr = (c shr 16) and 255; val gg = (c shr 8) and 255; rr in 90..170 && gg in 60..140 }
        assertTrue(blended > 20, "expected antialiased edge pixels ($blended)")
    }
}
