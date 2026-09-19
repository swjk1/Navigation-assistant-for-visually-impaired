package com.navassist.navcore.mapping

/**
 * Derived, human-readable state of an occupancy cell.
 *
 * The grid internally stores log-odds evidence rather than this enum, so that repeated
 * observations reinforce each other and stale observations can decay. The enum is derived on
 * demand via configurable thresholds.
 */
enum class CellState {
    UNKNOWN,
    FREE,
    OCCUPIED,
}

/** Aggregate counts over a grid, used for map confidence and debug output. */
data class GridStats(
    val free: Int,
    val occupied: Int,
    val unknown: Int,
) {
    val total: Int get() = free + occupied + unknown

    /**
     * Fraction of the local grid that has been observed at all. This is the engine's notion of
     * "do I know enough about my surroundings to move?" - deliberately conservative.
     */
    val knownFraction: Float
        get() = if (total == 0) 0f else (free + occupied).toFloat() / total.toFloat()
}
