package expo.modules.navigationnative.platform

import com.google.ar.core.Pose
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * ARCore world frame -> CANONICAL navigation frame.
 *
 * This class is the entire reason the navigation core never has to know what ARCore is. Everything
 * downstream works in the canonical frame defined by [Pose3D]:
 *
 *   origin = device position when navigation started
 *   +y     = up (against gravity)
 *   +z     = initial device forward, projected onto the floor
 *   +x     = right
 *   yaw    = atan2(x, z), so 0 is straight ahead and POSITIVE yaw is a turn to the RIGHT
 *
 * ARCore's world frame is right-handed with +y up but with x/z chosen arbitrarily at session
 * start. We therefore build an orthonormal basis (right, up, forward) from the first tracked pose
 * and express everything relative to it.
 *
 * Note on handedness: (right, up, forward) as defined above is LEFT-handed, because we want
 * "positive yaw = turn right". The change of basis is an orthonormal reflection, so lengths and
 * angle magnitudes are preserved exactly; only the sign convention of rotation flips, which is
 * precisely the intent. A future ARKit adapter performs the same construction from ARKit's own
 * right-handed, y-up world frame and produces identical canonical data.
 */
class ArCorePoseProvider {

    private var originX = 0f
    private var originY = 0f
    private var originZ = 0f

    // Horizontal basis vectors expressed in ARCore world coordinates.
    private var forwardX = 0f
    private var forwardZ = 1f
    private var rightX = 1f
    private var rightZ = 0f

    var isEstablished: Boolean = false
        private set

    fun reset() {
        isEstablished = false
    }

    /**
     * Fixes the canonical frame from the first well-tracked camera pose of the session.
     * Called once; everything afterwards is expressed relative to it.
     */
    fun establish(cameraPose: Pose) {
        originX = cameraPose.tx()
        originY = cameraPose.ty()
        originZ = cameraPose.tz()

        // ARCore cameras look along their own -Z axis.
        val zAxis = cameraPose.zAxis
        var fx = -zAxis[0]
        var fz = -zAxis[2]
        var length = sqrt(fx * fx + fz * fz)

        if (length < 1e-3f) {
            // Phone pointed almost straight up or down: -Z has no usable horizontal component.
            // Fall back to the "top of the screen" direction (the pose's +Y axis).
            val yAxis = cameraPose.yAxis
            fx = yAxis[0]
            fz = yAxis[2]
            length = sqrt(fx * fx + fz * fz)
            if (length < 1e-3f) {
                fx = 0f
                fz = 1f
                length = 1f
            }
        }

        forwardX = fx / length
        forwardZ = fz / length
        // right = forward x up, with up = (0, 1, 0) in ARCore world coordinates.
        rightX = -forwardZ
        rightZ = forwardX
        isEstablished = true
    }

    /** ARCore world point -> canonical world point. */
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

    /** ARCore camera pose -> canonical device pose (position + yaw). */
    fun toCanonicalPose(cameraPose: Pose): Pose3D {
        val position = toCanonicalPoint(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())

        val zAxis = cameraPose.zAxis
        var hx = -zAxis[0]
        var hz = -zAxis[2]
        if (hx * hx + hz * hz < 1e-4f) {
            // Steeply pitched phone: use the screen-up direction as the heading instead.
            val yAxis = cameraPose.yAxis
            hx = yAxis[0]
            hz = yAxis[2]
        }
        val canonicalX = hx * rightX + hz * rightZ
        val canonicalZ = hx * forwardX + hz * forwardZ
        val yaw = atan2(canonicalX, canonicalZ)

        return Pose3D(position.x, position.y, position.z, yaw)
    }

    /**
     * Collapses "camera space -> ARCore world -> canonical world" into a single 3x4 transform, so
     * that converting a few thousand depth points per frame costs 9 multiply-adds each instead of
     * two matrix multiplications and an allocation.
     *
     * @param cameraMatrix the column-major 4x4 produced by [Pose.toMatrix].
     * @param out receives 12 floats: rows of the 3x4 transform (row-major).
     */
    fun fillCanonicalFromCamera(cameraMatrix: FloatArray, out: FloatArray) {
        val m = cameraMatrix
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
        /** Applies a 3x4 transform produced by [fillCanonicalFromCamera] to a camera-space point. */
        fun transform(t: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
            out[0] = t[0] * x + t[1] * y + t[2] * z + t[3]
            out[1] = t[4] * x + t[5] * y + t[6] * z + t[7]
            out[2] = t[8] * x + t[9] * y + t[10] * z + t[11]
        }
    }
}
