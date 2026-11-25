package dev.etran.livemaps

import dev.etran.livemaps.model.*
import dev.etran.livemaps.render.FlatRenderer
import dev.etran.livemaps.render.ViewRenderer
import dev.etran.livemaps.source.*
import kotlin.test.*

class FlatRendererTest {
    // coordinate-coded colors: R = x, G = z (so we can see exactly where each output pixel came from)
    private fun src(): FakeSource {
        val s = FakeSource()
        for (tx in -1..1) for (tz in -1..1)
            s.put(1, tx, tz, makeTile(64, color = { x, z -> (255 shl 24) or ((x * 4) shl 16) or ((z * 4) shl 8) or (if (tx == 0 && tz == 0) 0 else 200) }))
        return s
    }

    @Test fun oneToOneIsExact() {
        val s = src()
        val r = ViewRenderer.render(ctx(s), view(32.0, 32.0, zoom = 1.0))
        // output pixel (px,py) of a 128x128 view centred on (32,32) maps to world (px-32, py-32) -> in tiles
        // world (0,0) is output pixel (32,32): tile(0,0) local (0,0) -> color 0,0
        val c = r.argb[32 * 128 + 32]
        assertEquals(0, (c shr 16) and 255)
        assertEquals(0, (c shr 8) and 255)
        val c2 = r.argb[(32 + 10) * 128 + (32 + 5)]
        assertEquals(5 * 4, (c2 shr 16) and 255)
        assertEquals(10 * 4, (c2 shr 8) and 255)
    }

    @Test fun rotation180Flips() {
        val s = src()
        val a = ViewRenderer.render(ctx(s), view(32.0, 32.0, yaw = 0.0)).argb
        val b = ViewRenderer.render(ctx(s), view(32.0, 32.0, yaw = 180.0)).argb
        // a 180 degree turn maps pixel (x,y) to (127-x,127-y)
        for (p in listOf(0 to 0, 10 to 20, 100 to 7, 127 to 127)) {
            assertEquals(rgb(a[p.second * 128 + p.first]), rgb(b[(127 - p.second) * 128 + (127 - p.first)]), "pixel $p")
        }
    }

    @Test fun rotation90PutsEastAtTop() {
        // yaw 90 = looking east, so east (+x) is screen-up: moving up on screen must increase world x
        val s = src()
        val r = ViewRenderer.render(ctx(s), view(32.0, 32.0, yaw = 90.0)).argb
        val centre = r[64 * 128 + 64]
        val above = r[(64 - 10) * 128 + 64]
        val rx = (centre shr 16) and 255
        val ax = (above shr 16) and 255
        assertTrue(ax > rx, "x should grow towards the top ($rx -> $ax)")
    }

    @Test fun zoomOutAveragesInsteadOfDecimating() {
        // checkerboard of black/white blocks averaged at 2 blocks per pixel must be mid grey
        val s = FakeSource()
        s.put(1, 0, 0, makeTile(64, color = { x, z -> if ((x + z) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }))
        val r = ViewRenderer.render(ctx(s), view(32.0, 32.0, zoom = 2.0, w = 1, h = 1))
        val c = r.argb[64 * 128 + 64]
        val g = (c shr 8) and 255
        assertTrue(g in 110..145, "expected mid grey, got $g")
    }

    @Test fun picksCoarserLevelWhenZoomedOut() {
        val s = FakeSource(levels = listOf(LevelInfo(1, 1, 64), LevelInfo(2, 4, 64)))
        s.put(1, 0, 0, makeTile(64, color = { _, _ -> 0xFFFF0000.toInt() }))
        s.put(2, 0, 0, makeTile(64, color = { _, _ -> 0xFF00FF00.toInt() }))
        val geo = s.geometry("t")!!
        assertEquals(1, FlatRenderer.level(geo, 1.0).index)
        assertEquals(1, FlatRenderer.level(geo, 3.9).index)
        assertEquals(2, FlatRenderer.level(geo, 4.0).index)
        assertEquals(2, FlatRenderer.level(geo, 40.0).index)
    }

    @Test fun missingTilesUseVoidColor() {
        val s = FakeSource()
        val r = ViewRenderer.render(ctx(s), view(5000.0, 5000.0))
        assertEquals(rgb(0xFF14141E.toInt()), rgb(r.argb[0]))
    }

    @Test fun reliefShadingBrightensSlopesFacingLight() {
        val s = FakeSource()
        // a ramp rising towards +x (east) and +z (south): faces north-west, towards the light
        s.put(1, 0, 0, makeTile(64, color = { _, _ -> 0xFF808080.toInt() }, height = { x, z -> 60 + (x + z) / 8 }))
        val flat = ViewRenderer.render(ctx(s), view(32.0, 32.0, options = ViewOptions(shading = 0.0, supersample = 1)))
        val shaded = ViewRenderer.render(ctx(s), view(32.0, 32.0, options = ViewOptions(shading = 1.0, supersample = 1)))
        val i = 64 * 128 + 64
        assertTrue((shaded.argb[i] and 255) > (flat.argb[i] and 255))
    }

    @Test fun bilinearUpscaleSmooths() {
        val s = FakeSource()
        s.put(1, 0, 0, makeTile(64, color = { x, _ -> if (x < 32) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }))
        val opt = ViewOptions(shading = 0.0, supersample = 1, upscale = Upscale.BILINEAR)
        val r = ViewRenderer.render(ctx(s), view(32.0, 32.0, zoom = 0.25, options = opt))
        // centre column sits exactly on the black/white edge: bilinear must produce an intermediate value
        val mid = (r.argb[64 * 128 + 64] shr 8) and 255
        assertTrue(mid in 20..235, "edge should be blended, got $mid")
    }

    @Test fun fingerprintChangesWithTiles() {
        val s = src()
        val c = ctx(s)
        val v = view(32.0, 32.0)
        val a = ViewRenderer.fingerprint(c, v)
        assertEquals(a, ViewRenderer.fingerprint(c, v))
        s.put(1, 0, 0, makeTile(64, stamp = 99, color = { _, _ -> 0xFF000000.toInt() }))
        assertNotEquals(a, ViewRenderer.fingerprint(c, v))
    }
}
