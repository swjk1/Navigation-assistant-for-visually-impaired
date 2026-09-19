package com.navassist.navcore.planning

/**
 * Minimal portable binary min-heap over integer payloads keyed by a Float priority.
 *
 * `java.util.PriorityQueue` is JVM-only and would block a later Kotlin Multiplatform move, and a
 * boxed `PriorityQueue<Node>` would allocate one object per expansion. This heap stores primitives
 * in growable arrays and is reused across plans.
 */
class IntBinaryHeap(initialCapacity: Int = 256) {

    private var payloads = IntArray(initialCapacity)
    private var keys = FloatArray(initialCapacity)

    var size: Int = 0
        private set

    val isEmpty: Boolean get() = size == 0

    fun clear() {
        size = 0
    }

    fun push(payload: Int, key: Float) {
        if (size == payloads.size) grow()
        payloads[size] = payload
        keys[size] = key
        siftUp(size)
        size++
    }

    /** Removes and returns the payload with the smallest key. Caller must check [isEmpty]. */
    fun pop(): Int {
        val top = payloads[0]
        size--
        if (size > 0) {
            payloads[0] = payloads[size]
            keys[0] = keys[size]
            siftDown(0)
        }
        return top
    }

    fun peekKey(): Float = keys[0]

    private fun grow() {
        val newCapacity = payloads.size * 2
        payloads = payloads.copyOf(newCapacity)
        keys = keys.copyOf(newCapacity)
    }

    private fun siftUp(startIndex: Int) {
        var i = startIndex
        val payload = payloads[i]
        val key = keys[i]
        while (i > 0) {
            val parent = (i - 1) / 2
            if (keys[parent] <= key) break
            payloads[i] = payloads[parent]
            keys[i] = keys[parent]
            i = parent
        }
        payloads[i] = payload
        keys[i] = key
    }

    private fun siftDown(startIndex: Int) {
        var i = startIndex
        val payload = payloads[i]
        val key = keys[i]
        val half = size / 2
        while (i < half) {
            var child = 2 * i + 1
            val right = child + 1
            if (right < size && keys[right] < keys[child]) child = right
            if (keys[child] >= key) break
            payloads[i] = payloads[child]
            keys[i] = keys[child]
            i = child
        }
        payloads[i] = payload
        keys[i] = key
    }
}
