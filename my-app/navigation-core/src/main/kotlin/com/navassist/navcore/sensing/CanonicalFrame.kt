package com.navassist.navcore.sensing

import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Platform world frame -> CANONICAL navigation frame.
 *
 * Input is any AR platform's world: right-handed, +y up (gravity-aligned), x/z arbitrary, with
 * camera poses as column-major 4x4 camera-to-world matrices whose camera looks along its own -Z.
 * ARCore (`Pose.toMatrix`) and ARKit (`ARCamera.transform`) both produce exactly that, and so does
 * the simulator.
 *
 * Output is the frame defined by [Pose3D]:
 *
 *   origin = device position when the frame was established
 *   +y     = up (against gravity)
 *   +z     = initial device forward, projected onto the floor
 *   +x     = right
 *   yaw    = atan2(x, z), so 0 is straight ahead and POSITIVE yaw is a turn to the RIGHT
 *
 * Note on handedness: (right, up, forward) is LEFT-handed, because we want "positive yaw = turn
 * right". The change of basis is an orthonormal reflection, so lengths and angle magnitudes are
 * preserved exactly; only the sign convention of rotation flips, which is precisely the intent.
 *
 * This used to live in the Android adapter, where only a phone could exercise it.
 */
class CanonicalFrame {

    private var originX = 0f
    private var originY = 0f
    private var originZ = 0f

    // Horizontal basis vectors expressed in platform world coordinates.
    private var forwardX = 0f
    private var forwardZ = 1f
    private var rightX = 1f
    private var rightZ = 0f

    var isEstablished: Boolean = false
        private set

    fun reset() {
        isEstablished = false
    }

    /** Fixes the canonical frame from a camera-to-world matrix (column-major 4x4). */
    fun establish(cameraToWorld: FloatArray) {
        val m = cameraToWorld
        originX = m[12]
        originY = m[13]
        originZ = m[14]

        // The camera looks along its own -Z; column 2 is +Z in world coordinates.
        var fx = -m[8]
        var fz = -m[10]
        var length = sqrt(fx * fx + fz * fz)
        if (length < 1e-3f) {
            // Pointed almost straight up or down: -Z has no usable horizontal component.
            // Fall back to the camera's +Y axis (column 1).
            fx = m[4]
            fz = m[6]
            length = sqrt(fx * fx + fz * fz)
            if (length < 1e-3f) {
                fx = 0f
                fz = 1f
                length = 1f
            }
        }

        forwardX = fx / length
        forwardZ = fz / length
        // right = forward x up, with up = (0, 1, 0).
        rightX = -forwardZ
        rightZ = forwardX
        isEstablished = true
    }

    /** Platform world point -> canonical world point. */
    fun toCanonicalPoint(worldX: Float, worldY: Float, worldZ: Float): Vec3 {
        val dx = worldX - originX
        val dy = worldY - originY
        val dz = worldZ - originZ
        return Vec3(
            dx * rightX + dz * rightZ,
            dy,
            dx * forwardX + dz * forwardZ,
        )
    }

    /** Camera-to-world matrix -> canonical device pose (position + yaw). */
    fun toCanonicalPose(cameraToWorld: FloatArray): Pose3D {
        val t = FloatArray(12)
        cameraToCanonical(cameraToWorld, t)
        return poseOf(t)
    }

    /**
     * Collapses "camera space -> platform world -> canonical world" into a single 3x4 transform,
     * so that converting a few thousand depth points per frame costs 9 multiply-adds each instead
     * of two matrix multiplications and an allocation.
     *
     * @param out receives 12 floats: rows of the 3x4 transform (row-major).
     */
    fun cameraToCanonical(cameraToWorld: FloatArray, out: FloatArray) {
        val m = cameraToWorld
        // world = M * cameraPoint (column-major: m[col * 4 + row])
        // canonical.x = (world - origin) . right
        out[0] = m[0] * rightX + m[2] * rightZ
        out[1] = m[4] * rightX + m[6] * rightZ
        out[2] = m[8] * rightX + m[10] * rightZ
        out[3] = (m[12] - originX) * rightX + (m[14] - originZ) * rightZ
        // canonical.y = (world - origin).y
        out[4] = m[1]
        out[5] = m[5]
        out[6] = m[9]
        out[7] = m[13] - originY
        // canonical.z = (world - origin) . forward
        out[8] = m[0] * forwardX + m[2] * forwardZ
        out[9] = m[4] * forwardX + m[6] * forwardZ
        out[10] = m[8] * forwardX + m[10] * forwardZ
        out[11] = (m[12] - originX) * forwardX + (m[14] - originZ) * forwardZ
    }

    companion object {
        /** Applies a 3x4 transform produced by [cameraToCanonical] to a camera-space point. */
        fun transform(t: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
            out[0] = t[0] * x + t[1] * y + t[2] * z + t[3]
            out[1] = t[4] * x + t[5] * y + t[6] * z + t[7]
            out[2] = t[8] * x + t[9] * y + t[10] * z + t[11]
        }

        /** The canonical pose of the camera a [cameraToCanonical] transform describes. */
        fun poseOf(t: FloatArray): Pose3D {
            // The camera looks along its own -Z; the transform's third column is +Z in canonical.
            var hx = -t[2]
            var hz = -t[10]
            if (hx * hx + hz * hz < 1e-4f) {
                // Steeply pitched phone: use the camera's +Y (screen-up) direction instead.
                hx = t[1]
                hz = t[9]
            }
            return Pose3D(t[3], t[7], t[11], atan2(hx, hz))
        }
    }
}
