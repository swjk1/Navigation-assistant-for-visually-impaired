package com.navassist.navcore.geometry

/**
 * Device pose expressed in the CANONICAL navigation frame.
 *
 * Canonical frame definition (identical for every platform adapter):
 *  - origin: device position at the moment navigation started
 *  - +y: up (against gravity)
 *  - +z: the initial device forward direction, projected onto the floor plane
 *  - +x: right
 *  - yaw = 0 points along +z; POSITIVE yaw turns clockwise seen from above (to the right)
 *
 * Platform adapters (ARCore on Android, ARKit on iOS) convert their own world conventions into
 * this frame. Nothing below this type may assume ARCore/ARKit axis conventions.
 */
data class Pose3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val yawRadians: Float,
) {
    /** Position projected onto the navigation plane. */
    val position2D: Vec2 get() = Vec2(x, z)

    val position3D: Vec3 get() = Vec3(x, y, z)

    /** Unit heading vector on the navigation plane. */
    val forward2D: Vec2 get() = Vec2.fromYaw(yawRadians)

    companion object {
        val IDENTITY = Pose3D(0f, 0f, 0f, 0f)
    }
}
