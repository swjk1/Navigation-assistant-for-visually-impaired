package com.navassist.sim.world

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How a surface looks to the depth sensor. To the user, everything is solid.
 */
enum class Material {
    /** Ordinary wall, furniture, door. */
    SOLID,

    /** Depth mostly sees THROUGH it. The one obstacle a depth-only map cannot know about. */
    GLASS,

    /** Black or very dark surfaces: most depth pixels come back empty. */
    DARK,
}

/**
 * An axis-aligned box in WORLD coordinates, which follow the AR platform convention: right-handed,
 * +y up, x = east, z = SOUTH (so north, the usual "forward" in a floor plan, is -z). Scenes are
 * authored in floor-plan coordinates through [SceneBuilder] and never touch this directly.
 */
data class Box(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
    val material: Material = Material.SOLID,
    val name: String = "",
    /** A floor slab: something to stand on rather than bump into. */
    val isFloor: Boolean = false,
    /** Whether an AR platform would track the top face as a horizontal plane (floors, tables). */
    val trackablePlane: Boolean = false,
) {
    val topArea: Float get() = (maxX - minX) * (maxZ - minZ)
}

/** What kind of thing a perception model could recognise on a sign or door. */
enum class SignKind {
    EXIT_SIGN,
    DOOR,
    ROOM_PLATE,
    ROOM_RANGE,
    STAIR_SIGN,
    ELEVATOR,
    WASHROOM,
    LEFT_ARROW,
    RIGHT_ARROW,
}

/**
 * Something readable or recognisable, mounted on a surface. Not part of the depth geometry - the
 * wall it hangs on is - only of what the perception stand-in can report.
 */
data class Sign(
    val kind: SignKind,
    /** Text for OCR: a room number, "EXIT", "Rooms 300-349". */
    val text: String,
    /** Centre in WORLD coordinates. */
    val x: Float,
    val y: Float,
    val z: Float,
    /** Unit normal in WORLD coordinates, pointing out of the surface towards viewers. */
    val normalX: Float,
    val normalZ: Float,
    val widthMeters: Float,
    val heightMeters: Float,
    /** Hung from the ceiling to point the way, not mounted where the thing it names is. */
    val hanging: Boolean = false,
)

/** A ray hit: distance along the (unnormalised) ray direction, what was hit, and its normal. */
class Hit(val t: Float, val box: Box, val normalX: Float, val normalY: Float, val normalZ: Float)

class Scene(
    val name: String,
    val boxes: List<Box>,
    val signs: List<Sign>,
) {
    /** Everything the user can walk into: not floors, and not so high they pass underneath. */
    val obstacles: List<Box> = boxes.filter { !it.isFloor && it.minY < BODY_HEIGHT && it.maxY > STEP_HEIGHT }
    val floors: List<Box> = boxes.filter { it.isFloor }

    /**
     * Underside of the lowest floor slab overhead - where the storey above begins. Viewers draw
     * everything from there up see-through, so the storey being walked stays visible.
     */
    val ceilingHeight: Float = floors.filter { it.maxY > BODY_HEIGHT }.minOfOrNull { it.minY } ?: Float.POSITIVE_INFINITY

    /** True for geometry on a storey above the one being walked. */
    fun isAbove(box: Box): Boolean = box.minY >= ceilingHeight - 0.01f

    /**
     * Nearest hit along origin + t * direction for t in (0, tMax].
     *
     * @param skipGlass when true, glass boxes are transparent - how depth usually sees them.
     * @param candidates the boxes to test; [near] narrows them down for many rays from one point.
     */
    fun raycast(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        tMax: Float,
        skipGlass: Boolean = false,
        candidates: List<Box> = boxes,
    ): Hit? {
        var best: Hit? = null
        var bestT = tMax
        for (box in candidates) {
            if (skipGlass && box.material == Material.GLASS) continue
            var tNear = 0f
            var tFar = bestT
            var axis = -1
            var sign = 0f
            // Slab test, one axis at a time.
            for (a in 0..2) {
                val o = when (a) { 0 -> ox; 1 -> oy; else -> oz }
                val d = when (a) { 0 -> dx; 1 -> dy; else -> dz }
                val lo = when (a) { 0 -> box.minX; 1 -> box.minY; else -> box.minZ }
                val hi = when (a) { 0 -> box.maxX; 1 -> box.maxY; else -> box.maxZ }
                if (abs(d) < 1e-9f) {
                    if (o < lo || o > hi) { tNear = Float.MAX_VALUE; break }
                    continue
                }
                var t1 = (lo - o) / d
                var t2 = (hi - o) / d
                var enterSign = -1f
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp; enterSign = 1f }
                if (t1 > tNear) { tNear = t1; axis = a; sign = enterSign }
                if (t2 < tFar) tFar = t2
                if (tNear > tFar) break
            }
            if (axis < 0 || tNear > tFar || tNear <= 1e-4f || tNear >= bestT) continue
            bestT = tNear
            best = Hit(
                tNear, box,
                if (axis == 0) sign else 0f,
                if (axis == 1) sign else 0f,
                if (axis == 2) sign else 0f,
            )
        }
        return best
    }

    /**
     * The boxes within [radius] of world point (x, y, z), in scene order - so a ray from that point
     * no longer than [radius] hits exactly what it would against every box. Large buildings have
     * hundreds of boxes; a depth frame only needs the few dozen in sensor range.
     */
    fun near(x: Float, y: Float, z: Float, radius: Float): List<Box> {
        val r2 = radius * radius
        return boxes.filter { box ->
            val dx = max(max(box.minX - x, 0f), x - box.maxX)
            val dy = max(max(box.minY - y, 0f), y - box.maxY)
            val dz = max(max(box.minZ - z, 0f), z - box.maxZ)
            dx * dx + dy * dy + dz * dz <= r2
        }
    }

    /** Distance from a floor-plan point (world x/z) to the nearest obstacle footprint. */
    fun clearanceAt(x: Float, z: Float): Float {
        var best = Float.MAX_VALUE
        for (box in obstacles) {
            val dx = max(max(box.minX - x, 0f), x - box.maxX)
            val dz = max(max(box.minZ - z, 0f), z - box.maxZ)
            best = min(best, hypot(dx, dz))
        }
        return best
    }

    /** The obstacle a body of [radius] at world (x, z) would be touching, if any. */
    fun obstacleTouching(x: Float, z: Float, radius: Float): Box? = obstacles.firstOrNull { box ->
        val dx = max(max(box.minX - x, 0f), x - box.maxX)
        val dz = max(max(box.minZ - z, 0f), z - box.maxZ)
        sqrt(dx * dx + dz * dz) < radius
    }

    /** True when there is floor at walking level under world (x, z). */
    fun hasFloorAt(x: Float, z: Float): Boolean = floors.any {
        x >= it.minX && x <= it.maxX && z >= it.minZ && z <= it.maxZ && abs(it.maxY) < 0.05f
    }

    /** World-space bounds of everything, for drawing. */
    fun bounds(): FloatArray {
        var minX = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (b in boxes) {
            minX = min(minX, b.minX); minZ = min(minZ, b.minZ)
            maxX = max(maxX, b.maxX); maxZ = max(maxZ, b.maxZ)
        }
        return floatArrayOf(minX, minZ, maxX, maxZ)
    }

    companion object {
        /** Anything whose underside is above this is overhead to a walking person. */
        const val BODY_HEIGHT = 1.9f

        /** Anything lower than this is stepped over (a threshold strip, a cable cover). */
        const val STEP_HEIGHT = 0.05f
    }
}
