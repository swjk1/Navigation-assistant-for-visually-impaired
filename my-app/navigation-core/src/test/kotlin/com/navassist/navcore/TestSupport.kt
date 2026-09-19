package com.navassist.navcore

import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.NavigationFrame
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.mapping.InflatedGrid
import com.navassist.navcore.mapping.ObstacleInflator
import com.navassist.navcore.mapping.OccupancyGrid
import kotlin.math.max

/**
 * Helpers shared by the core tests.
 *
 * Everything here builds synthetic input. No ARCore session, no Android context, no Expo module is
 * ever constructed - which is the whole point of keeping the core platform-free.
 */
object TestSupport {

    /** Planning config with inflation disabled, for tests about graph topology rather than safety. */
    fun bareConfig(gridSizeMeters: Float = 2.0f) = NavigationConfig(
        gridResolutionMeters = 0.1f,
        gridSizeMeters = gridSizeMeters,
        inflationRadiusMeters = 0f,
        clearanceCostRadiusMeters = 0f,
        minFrontierCells = 3,
        informationGainRadiusCells = 4,
    )

    /**
     * Builds an occupancy grid from an ASCII map.
     *   '#' occupied, '.' free, '?' unknown.
     * Row 0 of [rows] is gz = 0, so the map reads bottom-up in world terms; tests only care about
     * internal consistency.
     */
    fun gridFromAscii(rows: List<String>, resolution: Float = 0.1f): OccupancyGrid {
        val width = rows.maxOf { it.length }
        val n = max(width, rows.size)
        val grid = OccupancyGrid(cells = n, resolution = resolution)
        for (gz in rows.indices) {
            val row = rows[gz]
            for (gx in row.indices) {
                val state = when (row[gx]) {
                    '#' -> CellState.OCCUPIED
                    '.' -> CellState.FREE
                    else -> CellState.UNKNOWN
                }
                grid.setState(gx, gz, state)
            }
        }
        return grid
    }

    fun inflate(grid: OccupancyGrid, config: NavigationConfig): InflatedGrid =
        ObstacleInflator(config).inflate(grid)

    /** Renders a grid back to ASCII. Handy when a planning test fails. */
    fun asciiOf(grid: OccupancyGrid): String = buildString {
        for (gz in 0 until grid.cells) {
            for (gx in 0 until grid.cells) {
                append(
                    when (grid.stateAt(gx, gz)) {
                        CellState.OCCUPIED -> '#'
                        CellState.FREE -> '.'
                        CellState.UNKNOWN -> '?'
                    },
                )
            }
            append('\n')
        }
    }
}

/**
 * A 2D "building" made of wall segments, plus a simulated depth sensor.
 *
 * `sense()` casts a fan of rays from the pose, intersects them with the walls, and returns depth
 * points in canonical world coordinates - exactly the shape an ARCore or ARKit adapter produces.
 * That lets a full ARCore -> depth -> map -> frontier -> A* -> command pipeline run as a unit test.
 */
class SyntheticWorld(
    private val walls: List<Pair<Vec2, Vec2>>,
    val floorY: Float = 0f,
) {
    fun sense(
        pose: Pose3D,
        fovDegrees: Float = 70f,
        rayCount: Int = 90,
        maxRange: Float = 6f,
    ): DepthPointCloud {
        // Up to 1 floor sample every 20 cm plus 3 obstacle heights per ray.
        val cloud = DepthPointCloud.allocate(rayCount * (kotlin.math.ceil(maxRange / 0.2f).toInt() + 4))
        val halfFov = GeometryUtils.degreesToRadians(fovDegrees / 2f)
        for (i in 0 until rayCount) {
            val t = if (rayCount == 1) 0.5f else i.toFloat() / (rayCount - 1)
            val yaw = pose.yawRadians - halfFov + t * (2f * halfFov)
            val direction = Vec2.fromYaw(yaw)
            val hit = castRay(pose.position2D, direction, maxRange)
            val range = hit ?: maxRange

            // Floor returns along the ray give the map its FREE evidence and feed floor estimation.
            var d = 0.4f
            while (d < range - 0.05f) {
                val p = pose.position2D + direction * d
                cloud.add(p.x, floorY, p.z)
                d += 0.2f
            }
            if (hit != null) {
                val p = pose.position2D + direction * hit
                // A wall is sampled at several heights, all inside the obstacle band.
                cloud.add(p.x, floorY + 0.25f, p.z)
                cloud.add(p.x, floorY + 1.0f, p.z)
                cloud.add(p.x, floorY + 1.7f, p.z)
            }
        }
        return cloud
    }

    fun frame(
        timestampNanos: Long,
        pose: Pose3D,
        trackingConfidence: Float = 1f,
        tracking: Boolean = true,
        useFloorHint: Boolean = true,
    ): NavigationFrame {
        val points = if (tracking) sense(pose) else DepthPointCloud.EMPTY
        return NavigationFrame(
            timestampNanos = timestampNanos,
            pose = pose,
            points = points,
            trackingConfidence = trackingConfidence,
            tracking = tracking,
            depthAvailable = points.count > 0,
            floorHint = if (useFloorHint) floorY else null,
            floorHintConfidence = if (useFloorHint) 0.9f else 0f,
        )
    }

    /** Distance to the closest wall along the ray, or null when nothing is hit within [maxRange]. */
    private fun castRay(origin: Vec2, direction: Vec2, maxRange: Float): Float? {
        var closest: Float? = null
        for ((a, b) in walls) {
            val hit = intersectRaySegment(origin, direction, a, b) ?: continue
            if (hit in 0.05f..maxRange && (closest == null || hit < closest!!)) closest = hit
        }
        return closest
    }

    private fun intersectRaySegment(origin: Vec2, direction: Vec2, a: Vec2, b: Vec2): Float? {
        val segment = b - a
        val denominator = direction.x * segment.z - direction.z * segment.x
        if (kotlin.math.abs(denominator) < 1e-6f) return null
        val delta = a - origin
        val t = (delta.x * segment.z - delta.z * segment.x) / denominator
        val u = (delta.x * direction.z - delta.z * direction.x) / denominator
        if (t < 0f || u < 0f || u > 1f) return null
        return t
    }

    companion object {
        /** Two parallel walls along +Z, open at the far end. */
        fun corridor(halfWidth: Float = 1.0f, fromZ: Float = -1f, toZ: Float = 12f) = SyntheticWorld(
            listOf(
                Vec2(-halfWidth, fromZ) to Vec2(-halfWidth, toZ),
                Vec2(halfWidth, fromZ) to Vec2(halfWidth, toZ),
                Vec2(-halfWidth, fromZ) to Vec2(halfWidth, fromZ),
            ),
        )

        /** Corridor closed off at [endZ]: the canonical dead end. */
        fun deadEndCorridor(halfWidth: Float = 1.0f, endZ: Float = 4f) = SyntheticWorld(
            listOf(
                Vec2(-halfWidth, -1f) to Vec2(-halfWidth, endZ),
                Vec2(halfWidth, -1f) to Vec2(halfWidth, endZ),
                Vec2(-halfWidth, -1f) to Vec2(halfWidth, -1f),
                Vec2(-halfWidth, endZ) to Vec2(halfWidth, endZ),
            ),
        )
    }
}
