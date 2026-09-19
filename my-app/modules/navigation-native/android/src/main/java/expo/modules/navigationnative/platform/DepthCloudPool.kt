package expo.modules.navigationnative.platform

import com.navassist.navcore.geometry.DepthPointCloud

/**
 * Hand-off buffers between the GL/AR thread and the mapping worker.
 *
 * The depth provider reuses ONE point cloud per AR frame so it never allocates in the render
 * loop, but that buffer is overwritten on the very next frame. Publishing it straight to the
 * worker would let the GL thread rewrite points while the worker is integrating them.
 *
 * A tiny round-robin pool solves it without locking: at most three clouds are live at any moment
 * (one being written by GL, one queued in the conflated channel, one being read by the worker).
 */
class DepthCloudPool(capacity: Int, private val slots: Int = 3) {

    private val clouds = Array(slots) { DepthPointCloud.allocate(capacity) }
    private var next = 0

    /** Copies [source] into the next pool slot and returns that slot. */
    fun copyOf(source: DepthPointCloud): DepthPointCloud {
        val target = clouds[next]
        next = (next + 1) % slots
        target.clear()
        val count = minOf(source.count, target.capacity)
        System.arraycopy(source.xyz, 0, target.xyz, 0, count * 3)
        target.count = count
        return target
    }
}
