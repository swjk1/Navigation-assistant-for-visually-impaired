package com.navassist.sim

import kotlin.random.Random

/**
 * A [Random] whose stream can be swapped mid-run. Until [reseed] it returns exactly what
 * `Random(seed)` would, call for call - every method delegates to that generator - so runs that
 * never reseed are unchanged. Forked runs (bench/ForkData.kt) reseed at the fork, so continuations
 * of one decision see different sensor noise while everything before the fork is replayed exactly.
 */
class SwitchableRandom(seed: Long) : Random() {
    private var inner: Random = Random(seed)

    fun reseed(seed: Long) {
        inner = Random(seed)
    }

    override fun nextBits(bitCount: Int): Int = inner.nextBits(bitCount)
    override fun nextInt(): Int = inner.nextInt()
    override fun nextInt(until: Int): Int = inner.nextInt(until)
    override fun nextInt(from: Int, until: Int): Int = inner.nextInt(from, until)
    override fun nextLong(): Long = inner.nextLong()
    override fun nextLong(until: Long): Long = inner.nextLong(until)
    override fun nextLong(from: Long, until: Long): Long = inner.nextLong(from, until)
    override fun nextBoolean(): Boolean = inner.nextBoolean()
    override fun nextDouble(): Double = inner.nextDouble()
    override fun nextDouble(until: Double): Double = inner.nextDouble(until)
    override fun nextDouble(from: Double, until: Double): Double = inner.nextDouble(from, until)
    override fun nextFloat(): Float = inner.nextFloat()
    override fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray = inner.nextBytes(array, fromIndex, toIndex)
}
