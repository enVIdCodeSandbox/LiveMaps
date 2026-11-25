package dev.etran.livemaps

import com.google.gson.JsonParser
import dev.etran.livemaps.core.GridMath
import dev.etran.livemaps.model.CameraMode
import dev.etran.livemaps.model.Projection
import dev.etran.livemaps.model.SourceType
import dev.etran.livemaps.model.ViewSpec
import dev.etran.livemaps.source.BlueMapSettings
import dev.etran.livemaps.source.BlueMapTileSource
import org.bukkit.Rotation
import org.bukkit.block.BlockFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelpersTest {
    private val bm = BlueMapTileSource({ BlueMapSettings(true, "FILE", "auto", "") }, { null })

    @Test fun blueMapGridPathSplitsEveryDigit() {
        assertEquals("x1/2/3/z-4/5.png", bm.gridPath(123, -45, ".png"))
        assertEquals("x0/z0.png", bm.gridPath(0, 0, ".png"))
        assertEquals("x-1/z7.json", bm.gridPath(-1, 7, ".json"))
    }

    @Test fun colorFormats() {
        assertEquals(0xFF336699.toInt(), bm.parseColor(JsonParser.parseString("\"#336699\"")))
        assertEquals(0xFF336699.toInt(), bm.parseColor(JsonParser.parseString("[0.2, 0.4, 0.6]")))
        assertEquals(0xFF336699.toInt(), bm.parseColor(JsonParser.parseString("[51, 102, 153]")))
        assertNotNull(bm.parseColor(JsonParser.parseString("{\"r\":0.1,\"g\":0.2,\"b\":0.3}")))
        assertNull(bm.parseColor(JsonParser.parseString("null")))
    }

    @Test fun wallGridIsReadLeftToRightAsSeenByTheViewer() {
        // the viewer of a north-facing frame looks south, so their right-hand side is -x (west)
        assertEquals(-1, GridMath.basis(BlockFace.NORTH, 0f)!!.rx)
        assertEquals(1, GridMath.basis(BlockFace.SOUTH, 0f)!!.rx)
        assertEquals(-1, GridMath.basis(BlockFace.EAST, 0f)!!.rz)   // looking west, right is north (-z)
        assertEquals(1, GridMath.basis(BlockFace.WEST, 0f)!!.rz)    // looking east, right is south (+z)
        for (f in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) assertEquals(-1, GridMath.basis(f, 0f)!!.dy)
        assertNull(GridMath.basis(BlockFace.NORTH_EAST, 0f))
    }

    @Test fun floorGridFollowsPlayerFacing() {
        // bukkit yaw 180 = looking north: the map top points north, right is east, rows run towards the player (south)
        val b = GridMath.basis(BlockFace.UP, 180f)!!
        assertEquals(listOf(1, 0, 0, 0, 0, 1), listOf(b.rx, b.ry, b.rz, b.dx, b.dy, b.dz))
        assertEquals(Rotation.NONE, GridMath.rotationFor(BlockFace.UP, 180f))
        assertEquals(BlockFace.EAST, GridMath.cardinal(270f))
        assertEquals(BlockFace.SOUTH, GridMath.cardinal(-10f))
    }

    @Test fun viewSpecRoundTripAndClamping() {
        val o = JsonParser.parseString(
            """{"source":"BLUEMAP","target":"world","mode":"FREE","x":10.5,"y":80,"z":-3,"yaw":-90,"tilt":400,"zoom":0,"fov":500,"width":99,"height":0}"""
        ).asJsonObject
        val s = ViewSpec.fromJson(o)
        assertEquals(SourceType.BLUEMAP, s.source.type)
        assertEquals(CameraMode.FREE, s.camera.mode)
        assertEquals(270.0, s.camera.yaw)
        assertEquals(180.0, s.camera.tilt)
        assertEquals(120.0, s.fov)
        assertEquals(64, s.widthMaps)
        assertEquals(1, s.heightMaps)
        assertEquals(Projection.PERSPECTIVE, s.projection)
        assertFalse(s.snapshot)
        val back = ViewSpec.fromJson(s.toJson())
        assertEquals(s.camera, back.camera)
        assertEquals(s.widthMaps, back.widthMaps)
        // flat views are always straight down and orthographic, whatever the request says
        val flat = ViewSpec.fromJson(JsonParser.parseString("""{"source":"SQUAREMAP","target":"w","mode":"FLAT","tilt":60,"projection":"PERSPECTIVE"}""").asJsonObject)
        assertEquals(0.0, flat.camera.tilt)
        assertEquals(Projection.ORTHOGRAPHIC, flat.projection)
        assertNull(flat.refreshIntervalSeconds)
    }

    @Test fun refreshIntervalRoundTripAndClamping() {
        val def = ViewSpec.fromJson(JsonParser.parseString("""{"source":"SQUAREMAP","target":"world","refreshIntervalSeconds":0}""").asJsonObject)
        assertNull(def.refreshIntervalSeconds)

        val low = ViewSpec.fromJson(JsonParser.parseString("""{"source":"SQUAREMAP","target":"world","refreshIntervalSeconds":1}""").asJsonObject)
        assertEquals(ViewSpec.MIN_REFRESH_SECONDS, low.refreshIntervalSeconds)

        val high = ViewSpec.fromJson(JsonParser.parseString("""{"source":"SQUAREMAP","target":"world","refreshIntervalSeconds":999999}""").asJsonObject)
        assertEquals(ViewSpec.MAX_REFRESH_SECONDS, high.refreshIntervalSeconds)

        val custom = ViewSpec.fromJson(JsonParser.parseString("""{"source":"SQUAREMAP","target":"world","refreshIntervalSeconds":180}""").asJsonObject)
        assertEquals(180, custom.refreshIntervalSeconds)
        val back = ViewSpec.fromJson(custom.toJson())
        assertEquals(180, back.refreshIntervalSeconds)
    }

    @Test fun snapshotSpecRoundTrip() {
        val hash = "#world:667:168:3221:120:0.4:0.9:0:0:perspective"
        val o = JsonParser.parseString(
            """{"source":"BLUEMAP","target":"world","mode":"FREE","width":3,"height":2,"snapshot":true,"bluemapHash":"$hash"}"""
        ).asJsonObject
        val s = ViewSpec.fromJson(o)
        assertTrue(s.snapshot)
        assertFalse(s.isFlat)
        assertEquals(hash, s.bluemapHash)
        val back = ViewSpec.fromJson(s.toJson())
        assertTrue(back.snapshot)
        assertEquals(hash, back.bluemapHash)
        assertEquals(3, back.widthMaps)
        assertEquals(2, back.heightMaps)
    }

    @Test fun rejectsBadSpec() {
        val e = runCatching { ViewSpec.fromJson(JsonParser.parseString("""{"source":"dynmap","target":"x"}""").asJsonObject) }.exceptionOrNull()
        assertNotNull(e)
    }
}
