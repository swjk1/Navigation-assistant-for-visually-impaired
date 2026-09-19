package com.navassist.navcore.planning

import com.navassist.navcore.geometry.GridCoordinate
import com.navassist.navcore.mapping.InflatedGrid

/**
 * Line-of-sight shortcutting.
 *
 * Raw 8-connected A* produces stair-stepped paths. Fed straight into the navigation controller
 * those become a stream of alternating "slight left / slight right", which is useless as spoken
 * guidance for a person. Greedily replacing runs of cells with straight segments whenever the
 * whole segment is traversable gives a path a human can actually walk.
 *
 * This is deliberately NOT a sophisticated motion planner. Theta* could replace it later; the
 * output contract (a shorter list of waypoints) would not change.
 */
object PathSmoother {

    fun smooth(grid: InflatedGrid, path: List<GridCoordinate>): List<GridCoordinate> {
        if (path.size <= 2) return path
        val result = ArrayList<GridCoordinate>(path.size)
        var anchor = 0
        result.add(path[0])
        while (anchor < path.size - 1) {
            // Walk as far ahead as line of sight allows, then commit to that waypoint.
            var furthest = anchor + 1
            for (candidate in path.size - 1 downTo anchor + 1) {
                if (grid.hasLineOfSight(path[anchor], path[candidate])) {
                    furthest = candidate
                    break
                }
            }
            result.add(path[furthest])
            anchor = furthest
        }
        return result
    }
}
