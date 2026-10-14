package com.navassist.sim.world

/**
 * Floors stacked over one footprint, joined by a lift: what a run can move between. Each floor is a
 * whole [Scene] at height 0 (the simulator swaps scenes when a ride ends), with its lift door at the
 * same plan position on every floor.
 */
interface Building {
    val floorCount: Int
    fun scene(floor: Int): Scene

    /** What the lift's buttons and display call each floor. */
    val liftLabels: List<String>

    /** Where the lift door is on every floor, in plan coordinates. */
    val liftDoor: Pair<Float, Float>
}

/** How a multi-floor building's floors are named and numbered. Held out by family, as single floors are. */
enum class MultiFloorFamily(val description: String) {
    /** Floor n has the n-xx rooms and the lift calls it "n". */
    FLOOR_DIGIT("the leading digit is the floor, as the lift names it"),

    /** The lift counts a level below: "n+1" holds the n-xx rooms. A wrong ride shows the offset. */
    OFFSET("the leading digit is the floor, but the lift's labels are one higher"),

    /** The leading digit names the wing on every floor: the lift is a trap. */
    DIGIT_IS_WING("the leading digit is the wing; every floor repeats the numbers"),
}

class MultiFloorBuilding(
    val family: MultiFloorFamily,
    val seed: Long,
    val floors: Int,
    val signPolicy: SignPolicy = SignPolicy.ACCURATE,
) : Building {

    private val numbering = if (family == MultiFloorFamily.DIGIT_IS_WING) NumberingFamily.DIGIT_IS_WING else NumberingFamily.FLOOR_DIGIT

    /** The floors, ground first: one layout, the floor's digit in its rooms. */
    val plans: List<ProceduralFloor> = (0 until floors).map { i ->
        ProceduralFloor(numbering, seed, signPolicy, floorNumberOverride = i + 1, withLift = true, nameSuffix = "-f${i + 1}")
    }

    val name = "mf-${family.name.lowercase().replace('_', '-')}-$seed"

    override val floorCount: Int get() = floors

    override fun scene(floor: Int): Scene = plans[floor].build()

    override val liftLabels: List<String> = (0 until floors).map { i ->
        when (family) {
            MultiFloorFamily.OFFSET -> (i + 2).toString()
            else -> (i + 1).toString()
        }
    }

    override val liftDoor: Pair<Float, Float> = plans.first().liftDoor ?: error("$name: no lift")
}
