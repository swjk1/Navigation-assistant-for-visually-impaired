package expo.modules.navigationnative.platform

import com.navassist.navcore.geometry.DepthPointCloud

/**
 * Hand-off buffers between the GL/AR thread and the mapping worker.
 *
 * The depth provider reuses ONE point cloud per AR frame so it never allocates in the render
 * loop, but that buffer is overwritten on the very next frame. Publishing it straight to the
 * worker would let the GL thread rewrite points while the worker is integrating them.
 *
 * Buffers are OWNED: [copyOf] hands one out and nobody else may touch it until it comes back
 * through [release]. This used to be a round-robin that assumed the worker always finished within
 * two GL frames - true until mapping fell behind, which is precisely when frames are skipped, and
 * then the GL thread rewrote the cloud the engine was still reading.
 *
 * Three buffers are enough: one being filled on the GL thread, one waiting as the latest frame,
 * one being read by the engine.
 */
class DepthCloudPool(capacity: Int, slots: Int = 3) {

    private val clouds = Array(slots) { DepthPointCloud.allocate(capacity) }
    private val inUse = BooleanArray(slots)

    /** Copies [source] into a free buffer, or returns null when every buffer is owned. */
    fun copyOf(source: DepthPointCloud): DepthPointCloud? {
        val target = acquire() ?: return null
        val count = minOf(source.count, target.capacity)
        System.arraycopy(source.xyz, 0, target.xyz, 0, count * 3)
        target.count = count
        return target
    }

    /** Returns a buffer handed out by [copyOf]. Anything else - e.g. an empty cloud - is ignored. */
    @Synchronized
    fun release(cloud: DepthPointCloud) {
        for (i in clouds.indices) {
            if (clouds[i] === cloud) {
                inUse[i] = false
                return
            }
        }
    }

    @Synchronized
    private fun acquire(): DepthPointCloud? {
        for (i in clouds.indices) {
            if (!inUse[i]) {
                inUse[i] = true
                return clouds[i].also { it.clear() }
            }
        }
        return null
    }
}
