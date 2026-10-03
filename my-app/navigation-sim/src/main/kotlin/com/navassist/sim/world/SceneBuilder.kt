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

    /** Height of the storey being drawn; everything is placed relative to its floor. */
    private var elevation = 0f

    /**
     * Draws one storey of a multi-storey building, its floor [elevation] metres above (or below)
     * the storey the user walks on - which is always at height 0.
     */
    fun storey(elevation: Float, draw: SceneBuilder.() -> Unit) = apply {
        val previous = this.elevation
        this.elevation = previous + elevation
        draw()
        this.elevation = previous
    }

    /** A walkable floor slab over the plan rectangle ([Material.DARK] for a black carpet). */
    fun floor(
        x0: Float, y0: Float, x1: Float, y1: Float,
        height: Float = 0f,
        material: Material = Material.SOLID,
    ) = apply {
        boxes += planBox(
            x0, y0, x1, y1, elevation + height - 0.1f, elevation + height,
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
            planBox(x0 - half, min(y0, y1), x0 + half, max(y0, y1), elevation, elevation + height, material = material, name = name)
        } else {
            planBox(min(x0, x1), y0 - half, max(x0, x1), y0 + half, elevation, elevation + height, material = material, name = name)
        }
    }

    /**
     * A wall like [wall] with openings in it: each of [gaps] is (centre, width), measured along
     * the wall in plan coordinates.
     */
    fun wallWithGaps(
        x0: Float, y0: Float, x1: Float, y1: Float,
        gaps: List<Pair<Float, Float>>,
        height: Float = 2.6f,
        thickness: Float = 0.1f,
        material: Material = Material.SOLID,
        name: String = "wall",
    ) = apply {
        val alongX = y0 == y1
        val start = if (alongX) min(x0, x1) else min(y0, y1)
        val end = if (alongX) max(x0, x1) else max(y0, y1)
        var from = start
        for ((centre, width) in gaps.sortedBy { it.first }) {
            val to = centre - width / 2f
            if (to > from) {
                if (alongX) wall(from, y0, to, y0, height, thickness, material, name) else wall(x0, from, x0, to, height, thickness, material, name)
            }
            from = maxOf(from, centre + width / 2f)
        }
        if (end > from) {
            if (alongX) wall(from, y0, end, y0, height, thickness, material, name) else wall(x0, from, x0, end, height, thickness, material, name)
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
        boxes += planBox(
            x0, y0, x1, y1, elevation + bottom, elevation + height,
            material = material, trackablePlane = trackablePlane, name = name,
        )
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
        hanging: Boolean = false,
    ) = apply {
        val (nx, ny) = facing.planNormal
        // Plan north (+y) is world -z.
        signs += Sign(kind, text, x, elevation + height, -y, nx, -ny, width, tall, hanging)
    }

    /**
     * The scene. A solid box entirely inside another of the same kind - the shared wall of two
     * rooms drawn one after the other - is dropped: no ray can reach it first, and big buildings
     * would otherwise carry hundreds of them.
     */
    fun build(name: String = this.name): Scene {
        val kept = boxes.filterIndexed { i, box ->
            box.isFloor || box.trackablePlane || boxes.withIndex().none { (j, other) ->
                j != i && !other.isFloor && other.material == box.material && other.contains(box) &&
                    (!box.contains(other) || j < i)
            }
        }
        return Scene(name, kept, signs.toList())
    }

    private fun Box.contains(other: Box) =
        minX <= other.minX && minY <= other.minY && minZ <= other.minZ &&
            maxX >= other.maxX && maxY >= other.maxY && maxZ >= other.maxZ

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
