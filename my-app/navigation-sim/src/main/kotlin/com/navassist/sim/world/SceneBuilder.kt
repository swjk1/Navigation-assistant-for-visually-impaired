package com.navassist.sim.world

import kotlin.math.max
import kotlin.math.min

/**
 * Builds scenes in FLOOR-PLAN coordinates: x east, y north, metres, floor at height 0. Converted
 * to the AR-style world frame (z = -north) internally, so a scene reads like a drawing.
 *
 * Only axis-aligned geometry: corridors, rooms and furniture in real buildings are overwhelmingly
 * grid-aligned, and it keeps ray casting a slab test.
 */
class SceneBuilder(private val name: String) {

    private val boxes = ArrayList<Box>()
    private val signs = ArrayList<Sign>()

    /** A walkable floor slab over the plan rectangle ([Material.DARK] for a black carpet). */
    fun floor(
        x0: Float, y0: Float, x1: Float, y1: Float,
        height: Float = 0f,
        material: Material = Material.SOLID,
    ) = apply {
        boxes += planBox(
            x0, y0, x1, y1, height - 0.1f, height,
            material = material, isFloor = true, trackablePlane = material != Material.DARK, name = "floor",
        )
    }

    /**
     * A wall along an axis-aligned segment from (x0, y0) to (x1, y1). The wall is [thickness]
     * thick and sits on the OUTSIDE of a room drawn with the same corner coordinates when
     * [outside] points that way; by default it is centred on the line.
     */
    fun wall(
        x0: Float, y0: Float, x1: Float, y1: Float,
        height: Float = 2.6f,
        thickness: Float = 0.1f,
        material: Material = Material.SOLID,
        name: String = "wall",
    ) = apply {
        val half = thickness / 2f
        require(x0 == x1 || y0 == y1) { "walls must be axis-aligned: ($x0,$y0)-($x1,$y1)" }
        boxes += if (x0 == x1) {
            planBox(x0 - half, min(y0, y1), x0 + half, max(y0, y1), 0f, height, material = material, name = name)
        } else {
            planBox(min(x0, x1), y0 - half, max(x0, x1), y0 + half, 0f, height, material = material, name = name)
        }
    }

    /** A free-standing block: a box, a bin, a bench, a table (with [trackablePlane]). */
    fun block(
        x0: Float, y0: Float, x1: Float, y1: Float,
        height: Float,
        bottom: Float = 0f,
        material: Material = Material.SOLID,
        trackablePlane: Boolean = false,
        name: String = "block",
    ) = apply {
        boxes += planBox(x0, y0, x1, y1, bottom, height, material = material, trackablePlane = trackablePlane, name = name)
    }

    /**
     * A sign centred at plan (x, y), at [height] above the floor, facing [facing] - the plan
     * direction a reader would be looking FROM ("south" means readable by someone to the south).
     */
    fun sign(
        kind: SignKind,
        text: String,
        x: Float, y: Float,
        facing: Facing,
        height: Float = 1.5f,
        width: Float = 0.3f,
        tall: Float = 0.15f,
    ) = apply {
        val (nx, ny) = facing.planNormal
        // Plan north (+y) is world -z.
        signs += Sign(kind, text, x, height, -y, nx, -ny, width, tall)
    }

    fun build() = Scene(name, boxes.toList(), signs.toList())

    private fun planBox(
        x0: Float, y0: Float, x1: Float, y1: Float,
        bottom: Float, top: Float,
        material: Material = Material.SOLID,
        isFloor: Boolean = false,
        trackablePlane: Boolean = false,
        name: String = "",
    ) = Box(
        minX = min(x0, x1), minY = bottom, minZ = -max(y0, y1),
        maxX = max(x0, x1), maxY = top, maxZ = -min(y0, y1),
        material = material, name = name, isFloor = isFloor, trackablePlane = trackablePlane,
    )
}

/** Which way a sign faces, i.e. where its reader stands. */
enum class Facing(val planNormal: Pair<Float, Float>) {
    NORTH(0f to 1f),
    SOUTH(0f to -1f),
    EAST(1f to 0f),
    WEST(-1f to 0f),
}
