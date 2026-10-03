package com.navassist.navcore.exploration

import com.navassist.navcore.geometry.GeometryUtils
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.InflatedGrid
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shape cues about where a frontier leads, taken from the map alone. Both survive glass, which
 * depth mostly sees through: a shop front reads as open floor, so a doorway test can't tell a shop
 * entrance from more concourse, but the space's long axis and the furniture inside still show.
 */
object SpaceShape {

    /** The long axis of the free space around a point, and how strongly elongated it is. */
    data class Axis(
        /** Unit direction of the long axis (either sense). */
        val direction: Vec2,
        /** 0 for a round or square space, approaching 1 for a long narrow one. */
        val elongation: Float,
    )

    /**
     * Principal axis of the traversable known cells within [radiusMeters] of [center]. Null when
     * too little is known to say.
     */
    fun axis(inflated: InflatedGrid, center: Vec2, radiusMeters: Float, resolution: Float): Axis? {
        val c = inflated.worldToGrid(center)
        val r = (radiusMeters / resolution).toInt()
        var n = 0
        var sx = 0.0
        var sz = 0.0
        var sxx = 0.0
        var szz = 0.0
        var sxz = 0.0
        for (dz in -r..r) for (dx in -r..r) {
            if (dx * dx + dz * dz > r * r) continue
            val gx = c.gx + dx
            val gz = c.gz + dz
            if (!inflated.inBounds(gx, gz) || inflated.isUnknown(gx, gz) || inflated.isBlocked(gx, gz)) continue
            n++
            sx += dx; sz += dz
            sxx += dx.toDouble() * dx; szz += dz.toDouble() * dz; sxz += dx.toDouble() * dz
        }
        if (n < MIN_CELLS) return null
        val mx = sx / n
        val mz = sz / n
        val cxx = sxx / n - mx * mx
        val czz = szz / n - mz * mz
        val cxz = sxz / n - mx * mz
        // Eigen-decomposition of the 2x2 covariance.
        val trace = cxx + czz
        val det = cxx * czz - cxz * cxz
        val gap = sqrt((trace * trace / 4 - det).coerceAtLeast(0.0))
        val major = trace / 2 + gap
        val minor = (trace / 2 - gap).coerceAtLeast(1e-9)
        val angle = 0.5 * atan2(2 * cxz, cxx - czz) // from the x axis, in grid (x, z)
        val direction = Vec2(cos(angle).toFloat(), sin(angle).toFloat())
        // sqrt(major/minor) is the length-to-width ratio; 1 means round. Map 1..4 onto 0..1.
        val ratio = sqrt(major / minor).toFloat()
        return Axis(direction, GeometryUtils.clamp((ratio - 1f) / 3f, 0f, 1f))
    }

    /** How well a direction lines up with the axis: 1 along it either way, 0 across it. */
    fun alignment(axis: Axis, towards: Vec2): Float {
        val length = towards.length()
        if (length < 1e-4f) return 0f
        return abs((axis.direction.x * towards.x + axis.direction.z * towards.z) / length)
    }

    /** Share of known cells within [radiusMeters] of [center] that are blocked, 0..1. */
    fun clutter(inflated: InflatedGrid, center: Vec2, radiusMeters: Float, resolution: Float): Float {
        val c = inflated.worldToGrid(center)
        val r = (radiusMeters / resolution).toInt()
        var known = 0
        var blocked = 0
        for (dz in -r..r) for (dx in -r..r) {
            if (dx * dx + dz * dz > r * r) continue
            val gx = c.gx + dx
            val gz = c.gz + dz
            if (!inflated.inBounds(gx, gz) || inflated.isUnknown(gx, gz)) continue
            known++
            if (inflated.isBlocked(gx, gz)) blocked++
        }
        return if (known < MIN_CELLS) 0f else blocked.toFloat() / known
    }

    private const val MIN_CELLS = 40
}
