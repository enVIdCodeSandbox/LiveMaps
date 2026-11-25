package dev.etran.livemaps.core

import org.bukkit.block.BlockFace
import org.bukkit.Rotation

/** Pure geometry for laying a width x height grid of item frames out from the frame the player clicked. */
object GridMath {

    /** Integer block step vectors: [right] moves one column, [down] moves one row. */
    data class Basis(val rx: Int, val ry: Int, val rz: Int, val dx: Int, val dy: Int, val dz: Int)

    /** Direction the player is looking at (horizontal), from the Bukkit yaw (0 = south, 90 = west, 180 = north, 270 = east). */
    fun cardinal(yaw: Float): BlockFace {
        val y = ((yaw % 360f) + 360f) % 360f
        return when (((y + 45f) / 90f).toInt() % 4) {
            0 -> BlockFace.SOUTH
            1 -> BlockFace.WEST
            2 -> BlockFace.NORTH
            else -> BlockFace.EAST
        }
    }

    private fun cw(f: BlockFace) = when (f) {
        BlockFace.NORTH -> BlockFace.EAST
        BlockFace.EAST -> BlockFace.SOUTH
        BlockFace.SOUTH -> BlockFace.WEST
        else -> BlockFace.NORTH
    }

    /**
     * For wall frames (front facing [face]) the viewer stands in front of the frame, so "right" is
     * (looking direction) x up, and rows go down. For floor/ceiling frames the top of the map points the way the
     * player is facing and rows run towards the player.
     */
    fun basis(face: BlockFace, playerYaw: Float): Basis? = when (face) {
        BlockFace.NORTH -> Basis(-1, 0, 0, 0, -1, 0)
        BlockFace.SOUTH -> Basis(1, 0, 0, 0, -1, 0)
        BlockFace.EAST -> Basis(0, 0, -1, 0, -1, 0)
        BlockFace.WEST -> Basis(0, 0, 1, 0, -1, 0)
        BlockFace.UP, BlockFace.DOWN -> {
            val fwd = cardinal(playerYaw)
            val right = cw(fwd)
            Basis(right.modX, 0, right.modZ, -fwd.modX, 0, -fwd.modZ)
        }
        else -> null
    }

    /**
     * Item rotation that makes the top of the map point along [fwd] in a floor or ceiling frame.
     * Assumes the unrotated map has its top towards north; best-effort, wall frames need no rotation.
     */
    fun rotationFor(face: BlockFace, playerYaw: Float): Rotation {
        if (face != BlockFace.UP && face != BlockFace.DOWN) return Rotation.NONE
        val fwd = cardinal(playerYaw)
        val steps = when (fwd) { BlockFace.NORTH -> 0; BlockFace.EAST -> 1; BlockFace.SOUTH -> 2; else -> 3 }
        // floor frames are viewed from above (clockwise = N->E), ceiling frames from below (clockwise = N->W)
        val s = if (face == BlockFace.UP) steps else (4 - steps) % 4
        return when (s) { 0 -> Rotation.NONE; 1 -> Rotation.CLOCKWISE; 2 -> Rotation.FLIPPED; else -> Rotation.COUNTER_CLOCKWISE }
    }
}
