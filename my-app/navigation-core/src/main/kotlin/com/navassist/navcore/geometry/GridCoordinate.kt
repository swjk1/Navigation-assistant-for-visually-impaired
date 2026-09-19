package com.navassist.navcore.geometry

import kotlin.math.abs
import kotlin.math.max

/**
 * Integer cell index inside an occupancy grid.
 * [gx] indexes the world X axis, [gz] indexes the world Z axis.
 */
data class GridCoordinate(val gx: Int, val gz: Int) {

    /** Chebyshev distance in cells: the admissible heuristic for 8-connected grids. */
    fun chebyshevTo(other: GridCoordinate): Int = max(abs(gx - other.gx), abs(gz - other.gz))

    fun manhattanTo(other: GridCoordinate): Int = abs(gx - other.gx) + abs(gz - other.gz)
}
