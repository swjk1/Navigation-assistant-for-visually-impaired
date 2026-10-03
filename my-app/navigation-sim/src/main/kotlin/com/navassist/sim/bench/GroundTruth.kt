package com.navassist.sim.bench

import com.navassist.sim.world.Scene
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The building as the benchmark's referee sees it: which floor-plan cells a person can stand in,
 * and the shortest walk from a start to a destination. Used to check every episode is possible
 * and to score how directly a run got there (SPL).
 *
 * Works in FLOOR-PLAN coordinates (x east, y north) on the storey at height 0.
 */
class GroundTruth(scene: Scene, private val clearance: Float = 0.3f, private val resolution: Float = 0.1f) {

    private val minX: Float
    private val minY: Float
    private val width: Int
    private val height: Int
    private val walkable: BooleanArray

    init {
        val floors = scene.floors.filter { abs(it.maxY) < 0.05f }
        minX = floors.minOf { it.minX } - 1f
        val maxX = floors.maxOf { it.maxX } + 1f
        minY = floors.minOf { -it.maxZ } - 1f
        val maxY = floors.maxOf { -it.minZ } + 1f
        width = ceil((maxX - minX) / resolution).toInt()
        height = ceil((maxY - minY) / resolution).toInt()

        // Blocked: no floor under the cell, or an obstacle's footprint on it.
        val blocked = BooleanArray(width * height) { true }
        for (f in floors) fill(blocked, f.minX, -f.maxZ, f.maxX, -f.minZ, false)
        for (o in scene.obstacles) fill(blocked, o.minX, -o.maxZ, o.maxX, -o.minZ, true)

        // Distance to the nearest blocked cell (8-connected, octile), then keep what clears a body.
        val distance = FloatArray(width * height) { Float.MAX_VALUE }
        val queue = PriorityQueue<Long>(compareBy { Float.fromBits((it ushr 32).toInt()) })
        for (i in blocked.indices) if (blocked[i]) { distance[i] = 0f; queue.add(pack(0f, i)) }
        relax(queue, distance) { true }
        walkable = BooleanArray(width * height) { distance[it] * resolution > clearance }
    }

    fun isWalkable(x: Float, y: Float): Boolean = cell(x, y)?.let { walkable[it] } ?: false

    /**
     * Length of the shortest walk from (x, y) to anywhere within [radius] of one of [goals], or
     * null when there is none.
     */
    fun shortestPath(x: Float, y: Float, goals: List<Pair<Float, Float>>, radius: Float): Float? =
        search(x, y, goals, radius)?.first

    /**
     * The shortest walk itself, as plan points from the start to the destination, or null. When
     * the start is not walkable (a body brushing a wall), the nearest walkable cell stands in.
     */
    fun route(x: Float, y: Float, goals: List<Pair<Float, Float>>, radius: Float): List<Pair<Float, Float>>? =
        search(x, y, goals, radius, nearestStart = true)?.second

    private fun search(
        x: Float, y: Float, goals: List<Pair<Float, Float>>, radius: Float, nearestStart: Boolean = false,
    ): Pair<Float, List<Pair<Float, Float>>>? {
        var start = cell(x, y) ?: return null
        if (!walkable[start]) start = (if (nearestStart) nearestWalkable(start) else null) ?: return null
        val distance = FloatArray(width * height) { Float.MAX_VALUE }
        val parent = IntArray(width * height) { -1 }
        distance[start] = 0f
        val queue = PriorityQueue<Long>(compareBy { Float.fromBits((it ushr 32).toInt()) })
        queue.add(pack(0f, start))
        var goal = -1
        relax(queue, distance, parent, stopAt = { i ->
            val cx = minX + (i % width + 0.5f) * resolution
            val cy = minY + (i / width + 0.5f) * resolution
            goals.any { (gx, gy) -> hypot(cx - gx, cy - gy) <= radius }.also { if (it) goal = i }
        }) { walkable[it] }
        if (goal < 0) return null
        val cells = ArrayList<Pair<Float, Float>>()
        var i = goal
        while (i >= 0) {
            cells += (minX + (i % width + 0.5f) * resolution) to (minY + (i / width + 0.5f) * resolution)
            i = parent[i]
        }
        return distance[goal] * resolution to cells.asReversed()
    }

    private fun nearestWalkable(from: Int): Int? {
        val fx = from % width
        val fy = from / width
        for (r in 1..10) for (dy in -r..r) for (dx in -r..r) {
            val nx = fx + dx
            val ny = fy + dy
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
            val n = ny * width + nx
            if (walkable[n]) return n
        }
        return null
    }

    private fun relax(
        queue: PriorityQueue<Long>,
        distance: FloatArray,
        parent: IntArray? = null,
        stopAt: (Int) -> Boolean = { false },
        passable: (Int) -> Boolean,
    ) {
        while (queue.isNotEmpty()) {
            val packed = queue.poll()
            val d = Float.fromBits((packed ushr 32).toInt())
            val i = (packed and 0xFFFFFFFFL).toInt()
            if (d > distance[i]) continue
            if (stopAt(i)) return
            val cx = i % width
            val cy = i / width
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val n = ny * width + nx
                if (!passable(n)) continue
                val nd = d + if (dx != 0 && dy != 0) SQRT2 else 1f
                if (nd < distance[n]) {
                    distance[n] = nd
                    parent?.set(n, i)
                    queue.add(pack(nd, n))
                }
            }
        }
    }

    private fun fill(grid: BooleanArray, x0: Float, y0: Float, x1: Float, y1: Float, value: Boolean) {
        val gx0 = max(0, floor((min(x0, x1) - minX) / resolution).toInt())
        val gx1 = min(width - 1, ceil((max(x0, x1) - minX) / resolution).toInt() - 1)
        val gy0 = max(0, floor((min(y0, y1) - minY) / resolution).toInt())
        val gy1 = min(height - 1, ceil((max(y0, y1) - minY) / resolution).toInt() - 1)
        for (gy in gy0..gy1) for (gx in gx0..gx1) grid[gy * width + gx] = value
    }

    private fun cell(x: Float, y: Float): Int? {
        val gx = floor((x - minX) / resolution).toInt()
        val gy = floor((y - minY) / resolution).toInt()
        return if (gx in 0 until width && gy in 0 until height) gy * width + gx else null
    }

    private fun pack(d: Float, i: Int): Long = (d.toRawBits().toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)

    private companion object {
        val SQRT2 = sqrt(2f)
    }
}
