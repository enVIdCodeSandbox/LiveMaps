package dev.etran.livemaps

import dev.etran.livemaps.model.CameraMode
import dev.etran.livemaps.model.Projection
import dev.etran.livemaps.model.ViewOptions
import dev.etran.livemaps.render.RayRenderer
import dev.etran.livemaps.render.ViewRenderer
import dev.etran.livemaps.source.LevelInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiLevelTest {
    private val fine = 0xFF20C020.toInt()    // green: level 1 (1 block per cell)
    private val coarse = 0xFFC02020.toInt()  // red: level 2 (4 blocks per cell)

    private fun source(): FakeSource {
        val s = FakeSource(levels = listOf(LevelInfo(1, 1, 64), LevelInfo(2, 4, 64)))
        for (tz in -8..7) for (tx in -8..7) s.put(1, tx, tz, makeTile(64, color = { _, _ -> fine }))
        for (tz in -4..3) for (tx in -4..3) s.put(2, tx, tz, makeTile(64, color = { _, _ -> coarse }))
        return s
    }

    private fun opts(view: Int, sharp: Int) = ViewOptions(shading = 0.0, supersample = 1, fog = 0.0, viewDistance = view, detailDistance = sharp)

    /** Free camera 16 blocks above flat ground, looking a bit below the horizon; returns the centre column, top to bottom. */
    private fun column(sharp: Int): List<Int> {
        val s = source()
        val v = view(0.0, 0.0, mode = CameraMode.FREE, y = 80.0, tilt = 70.0, projection = Projection.PERSPECTIVE, options = opts(300, sharp))
        val r = RayRenderer.render(ctx(s, 300), v, s.geometry("t")!!)
        return (0 until r.height).map { r.argb[it * r.width + r.width / 2] }
    }

    private fun isGreen(c: Int) = ((c shr 8) and 255) > ((c shr 16) and 255) + 40
    private fun isRed(c: Int) = ((c shr 16) and 255) > ((c shr 8) and 255) + 40

    @Test fun nearGroundUsesFineLevelAndFarGroundUsesCoarse() {
        val col = column(sharp = 40)
        assertTrue(isGreen(col.last()), "bottom of the picture (ground right below the camera) should come from the fine level")
        assertTrue(col.any { isRed(it) }, "far ground should come from the coarse level")
    }

    @Test fun sharpDistanceAtViewDistanceMeansFinestEverywhere() {
        val col = column(sharp = 300)
        assertTrue(col.any { isGreen(it) })
        assertTrue(col.none { isRed(it) }, "no coarse cells should be used when the sharp distance covers the whole view")
    }

    @Test fun ringsFollowTheSharpDistance() {
        val s = source()
        val geo = s.geometry("t")!!
        val c = ctx(s, 300)
        val v = view(0.0, 0.0, mode = CameraMode.FREE, y = 80.0, tilt = 70.0, projection = Projection.PERSPECTIVE, options = opts(300, 50))
        val rings = RayRenderer.rings(c, v, geo)
        assertEquals(listOf(1, 2), rings.map { it.level.index })
        assertEquals(50.0, rings[0].outer)
        assertEquals(300.0, rings[1].outer)
        val all = RayRenderer.rings(c, v.copy(options = opts(300, 5000)), geo)
        assertEquals(listOf(1), all.map { it.level.index })
    }

    @Test fun viewDistanceIsPerViewAndCappedByTheServer() {
        val s = source()
        val c = ctx(s, 300)
        val v = view(0.0, 0.0, mode = CameraMode.FREE, y = 80.0, tilt = 70.0, projection = Projection.PERSPECTIVE, options = opts(100, 0))
        assertEquals(100.0, RayRenderer.viewDistance(c, v))
        assertEquals(4096.0, RayRenderer.viewDistance(c, v.copy(options = opts(99999, 0))))
    }

    @Test fun fingerprintChangesWhenAFarCoarseTileChanges() {
        val s = source()
        val c = ctx(s, 300)
        val v = view(0.0, 0.0, mode = CameraMode.FREE, y = 80.0, tilt = 70.0, projection = Projection.PERSPECTIVE, options = opts(300, 40))
        val a = ViewRenderer.fingerprint(c, v)
        s.put(2, 1, 1, makeTile(64, stamp = 99, color = { _, _ -> coarse }))
        val b = ViewRenderer.fingerprint(ctx(s, 300), v)
        assertTrue(a != b)
    }
}
