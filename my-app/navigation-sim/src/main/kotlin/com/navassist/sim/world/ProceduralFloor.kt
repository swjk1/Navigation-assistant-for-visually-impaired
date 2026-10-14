package com.navassist.sim.world

import kotlin.random.Random

/**
 * How a building numbers its rooms. Families, not parameters: a planner tuned on some is judged on
 * the others (held out by family, never by seed), so a rule that only fits the tuning set shows.
 */
enum class NumberingFamily(val description: String) {
    /** Floor digit, then a sequence: odd on one wall, even on the other. The usual case. */
    FLOOR_DIGIT("floor digit, odd and even on opposite walls"),

    /** As [FLOOR_DIGIT] in the main corridor; each wing jumps to its own block (x50s, x70s). */
    WING_BLOCK("floor digit, each wing numbered from its own block"),

    /** One run up one wall and back down the other: no odd/even sides at all. */
    SEQUENTIAL_U("a single run up one wall and back down the other"),

    /** As [FLOOR_DIGIT], with numbers skipped and some rooms split into suites (213A). */
    GAPPY("floor digit with skipped numbers and lettered suites"),

    /** A letter for the half of the building ("E-203", "W-203"): the same digits on both halves. */
    WING_LETTER("a letter per half of the building; the digits repeat"),

    /** The leading digit is the wing, not the floor: 1xx in the main corridor, 2xx, 3xx in the wings. */
    DIGIT_IS_WING("the leading digit names the wing, not the floor"),
}

/** What the range signs at junctions say. */
enum class SignPolicy { ACCURATE, MISSING, MISLEADING }

/**
 * A single-storey school-like floor laid out from a seed: a main corridor of classrooms east-west,
 * up to two wings running north, exits at the ends, range signs where corridors meet. Drawn in plan
 * coordinates (x east, y north).
 *
 * A wing's first [ROOM_DEPTH] metres pass behind the main corridor's north rooms; its own rooms
 * start beyond them, so no two rooms overlap.
 */
class ProceduralFloor(
    val family: NumberingFamily,
    val seed: Long,
    val signPolicy: SignPolicy = SignPolicy.ACCURATE,
    /** One floor of a [MultiFloorBuilding]: its rooms' leading digit, in place of a random one. */
    floorNumberOverride: Int? = null,
    /** A lift niche off the south side of the main corridor, at the same place on every floor. */
    val withLift: Boolean = false,
    /** Distinguishes floors of one building in the scene name. */
    nameSuffix: String = "",
) {
    private val random = Random(seed * 1_000_003 + family.ordinal)
    val name = "proc-${family.name.lowercase().replace('_', '-')}-$seed$nameSuffix"

    // Drawn even when overridden, so a building's floors share one layout with a single-floor one.
    val floorNumber = (1 + random.nextInt(5)).let { floorNumberOverride ?: it }
    private val roomWidth = 6f + random.nextInt(4)
    private val roomsPerSide = 8 + random.nextInt(6)
    val length = roomsPerSide * roomWidth

    /** A wing: the slot it opens from, and how many rooms a side it has. */
    private data class Wing(val slot: Int, val rooms: Int) {
        fun x0(roomWidth: Float) = slot * roomWidth + (roomWidth - CORRIDOR) / 2f
        fun x1(roomWidth: Float) = x0(roomWidth) + CORRIDOR
    }

    private val wings: List<Wing> = run {
        val wanted = when {
            family == NumberingFamily.DIGIT_IS_WING || family == NumberingFamily.WING_BLOCK -> 1 + random.nextInt(2)
            else -> random.nextInt(3)
        }
        val slots = (1 until roomsPerSide - 1).shuffled(random)
        val chosen = ArrayList<Int>()
        for (s in slots) {
            if (chosen.size == wanted) break
            if (chosen.all { kotlin.math.abs(it - s) >= 3 }) chosen += s
        }
        chosen.sorted().map { Wing(it, 2 + random.nextInt(3)) }
    }

    /** Numbers rise eastward (true) or westward, in the main corridor. Ground truth: never shown to the agent. */
    val risingEast = random.nextBoolean()
    /** Odd numbers on the north wall (true) or the south, in the main corridor. Ground truth. */
    val oddNorth = random.nextBoolean()

    /** The lift's slot on the south side, if [withLift]: drawn last, so a floor without one is unchanged. */
    private val liftSlot: Int? = if (withLift) 1 + random.nextInt(roomsPerSide - 2) else null

    /** Where the lift door is (its sign), in plan; null without a lift. */
    val liftDoor: Pair<Float, Float>? = liftSlot?.let { (it * roomWidth + roomWidth / 2f) to -LIFT_DEPTH }

    /** One room: its label, where its plate is in plan, and which corridor. */
    data class Room(val label: String, val plateX: Float, val plateY: Float, val inWing: Boolean)

    private val slotsSouth = (0 until roomsPerSide).filter { it != liftSlot }.map { it * roomWidth }
    private val slotsNorth = (0 until roomsPerSide).filter { i -> wings.none { it.slot == i } }.map { it * roomWidth }

    val rooms: List<Room> = number()

    /** Where to start: a point on the corridor centre line, a fraction of the way east. */
    fun corridorPoint(fraction: Float): Pair<Float, Float> = (1.5f + (length - 3f) * fraction) to CORRIDOR / 2f

    private fun number(): List<Room> {
        val base = floorNumber * 100
        val south = slotsSouth.map { it to false }
        val north = slotsNorth.map { it to true }
        // Plate positions: on the corridor wall, 2.4 m into the room's frontage.
        fun plate(x0: Float, northSide: Boolean) = (x0 + 2.4f) to (if (northSide) CORRIDOR else 0f)
        val list = ArrayList<Room>()
        when (family) {
            NumberingFamily.SEQUENTIAL_U -> {
                val first = if (oddNorth) north else south
                val second = if (oddNorth) south else north
                val up = if (risingEast) first.sortedBy { it.first } else first.sortedByDescending { it.first }
                val down = if (risingEast) second.sortedByDescending { it.first } else second.sortedBy { it.first }
                var n = 1
                for ((x0, side) in up + down) {
                    val (px, py) = plate(x0, side)
                    list += Room((base + n++).toString(), px, py, false)
                }
            }
            else -> {
                fun ordered(side: List<Pair<Float, Boolean>>) = if (risingEast) side.sortedBy { it.first } else side.sortedByDescending { it.first }
                val odds = ordered(if (oddNorth) north else south)
                val evens = ordered(if (oddNorth) south else north)
                val skip = family == NumberingFamily.GAPPY
                var odd = 1
                var even = 2
                val digitBase = if (family == NumberingFamily.DIGIT_IS_WING) 100 else base
                for ((x0, side) in odds) {
                    if (skip && random.nextFloat() < 0.3f) odd += 2
                    val (px, py) = plate(x0, side)
                    list += Room(label(digitBase + odd, px), px, py, false)
                    if (skip && random.nextFloat() < 0.2f) list += Room(label(digitBase + odd, px) + "A", px + 3f, py, false)
                    odd += 2
                }
                for ((x0, side) in evens) {
                    if (skip && random.nextFloat() < 0.3f) even += 2
                    val (px, py) = plate(x0, side)
                    list += Room(label(digitBase + even, px), px, py, false)
                    even += 2
                }
            }
        }
        // Wing rooms: west wall odd, east wall even, rising north (away from the main corridor).
        var next = base + (list.maxOfOrNull { (it.label.filter(Char::isDigit).toIntOrNull() ?: base) - base } ?: 0) + 1
        for ((w, wing) in wings.withIndex()) {
            val start = when (family) {
                NumberingFamily.WING_BLOCK -> base + 51 + 20 * w
                NumberingFamily.DIGIT_IS_WING -> 100 * (w + 2) + 1
                else -> next
            }
            var n = if (start % 2 == 0) start + 1 else start
            val x0 = wing.x0(roomWidth)
            val x1 = wing.x1(roomWidth)
            for (i in 0 until wing.rooms) {
                val y = wingRoomsStart + i * roomWidth + 2.4f
                list += Room(label(n, x0), x0, y, true)
                list += Room(label(n + 1, x1), x1, y, true)
                n += 2
            }
            next = n
        }
        return list
    }

    private val wingRoomsStart = CORRIDOR + ROOM_DEPTH

    /** [WING_LETTER]: a letter for which half of the building the room is in. */
    private fun label(number: Int, x: Float): String =
        if (family == NumberingFamily.WING_LETTER) "${if (x < length / 2f) "W" else "E"}-$number" else number.toString()

    fun build(): Scene = SceneBuilder(name).apply {
        storey(0f) {
            floor(0f, 0f, length, CORRIDOR)
            liftSlot?.let { slot ->
                val x0 = slot * roomWidth + (roomWidth - LIFT_WIDTH) / 2f
                wallWithGaps(0f, 0f, length, 0f, gaps = listOf((x0 + LIFT_WIDTH / 2f) to LIFT_WIDTH), height = WALL, thickness = 0.2f)
            } ?: wall(0f, 0f, length, 0f, height = WALL, thickness = 0.2f)
            // North corridor wall, broken by each wing's mouth.
            var from = 0f
            for (wing in wings) {
                val x0 = wing.x0(roomWidth)
                val x1 = wing.x1(roomWidth)
                wall(from, CORRIDOR, x0, CORRIDOR, height = WALL, thickness = 0.2f)
                val end = wingRoomsStart + wing.rooms * roomWidth
                floor(x0, CORRIDOR, x1, end)
                wall(x0, CORRIDOR, x0, end, height = WALL, thickness = 0.2f)
                wall(x1, CORRIDOR, x1, end, height = WALL, thickness = 0.2f)
                wall(x0, end, x1, end, height = WALL, thickness = 0.2f)
                for (i in 0 until wing.rooms) {
                    val y0 = wingRoomsStart + i * roomWidth
                    for ((x, far) in listOf(x0 to x0 - ROOM_DEPTH, x1 to x1 + ROOM_DEPTH)) {
                        wall(far, y0, x, y0, height = WALL, thickness = 0.2f)
                        wall(far, y0 + roomWidth, x, y0 + roomWidth, height = WALL, thickness = 0.2f)
                        wall(far, y0, far, y0 + roomWidth, height = WALL, thickness = 0.2f)
                    }
                }
                from = x1
            }
            wall(from, CORRIDOR, length, CORRIDOR, height = WALL, thickness = 0.2f)
            // End walls with exit doors.
            for ((x, facing) in listOf(0f to Facing.EAST, length to Facing.WEST)) {
                wall(x, 0f, x, CORRIDOR, height = WALL, thickness = 0.2f, name = "end door")
                val face = if (facing == Facing.EAST) x + 0.11f else x - 0.11f
                sign(SignKind.DOOR, "", face, CORRIDOR / 2f, facing, height = 1.0f, width = 0.9f, tall = 2.0f)
                sign(SignKind.EXIT_SIGN, "EXIT", face, CORRIDOR / 2f, facing, height = 2.3f, width = 0.6f, tall = 0.3f)
            }
            for (x0 in slotsSouth) classroomWalls(x0, 0f, -ROOM_DEPTH)
            for (x0 in slotsNorth) classroomWalls(x0, CORRIDOR, CORRIDOR + ROOM_DEPTH)
            for (r in rooms) plate(r)
            rangeSigns()
            liftNiche()
        }
    }.build()

    /** A 3 m deep niche off the south wall with the lift door on its back wall. */
    private fun SceneBuilder.liftNiche() {
        val slot = liftSlot ?: return
        val x0 = slot * roomWidth + (roomWidth - LIFT_WIDTH) / 2f
        val x1 = x0 + LIFT_WIDTH
        floor(x0, -LIFT_DEPTH, x1, 0f)
        // The corridor's south wall is drawn whole; open it in front of the niche.
        wall(x0, 0f, x0, -LIFT_DEPTH, height = WALL, thickness = 0.2f)
        wall(x1, 0f, x1, -LIFT_DEPTH, height = WALL, thickness = 0.2f)
        wall(x0, -LIFT_DEPTH, x1, -LIFT_DEPTH, height = WALL, thickness = 0.2f, name = "lift doors")
        sign(SignKind.ELEVATOR, "", (x0 + x1) / 2f, -LIFT_DEPTH + 0.11f, Facing.NORTH, height = 1.1f, width = 1.1f, tall = 2.1f)
    }

    private fun SceneBuilder.classroomWalls(x0: Float, doorY: Float, backY: Float) {
        val x1 = x0 + roomWidth
        wall(x0, doorY, x0, backY, height = WALL, thickness = 0.2f)
        wall(x1, doorY, x1, backY, height = WALL, thickness = 0.2f)
        wall(x0, backY, x1, backY, height = WALL, thickness = 0.2f)
    }

    /** A room's door and plate on its corridor wall. */
    private fun SceneBuilder.plate(r: Room) {
        if (r.inWing) {
            val west = wings.any { kotlin.math.abs(it.x0(roomWidth) - r.plateX) < 0.01f }
            val face = if (west) r.plateX + 0.11f else r.plateX - 0.11f
            val facing = if (west) Facing.EAST else Facing.WEST
            sign(SignKind.DOOR, "", face, r.plateY - 0.9f, facing, height = 1.0f, width = 0.9f, tall = 2.0f)
            sign(SignKind.ROOM_PLATE, r.label, face, r.plateY, facing, height = 1.5f, width = 0.2f, tall = 0.15f)
        } else {
            val north = r.plateY > 0f
            val face = if (north) r.plateY - 0.11f else r.plateY + 0.11f
            val facing = if (north) Facing.SOUTH else Facing.NORTH
            sign(SignKind.DOOR, "", r.plateX - 0.9f, face, facing, height = 1.0f, width = 0.9f, tall = 2.0f)
            sign(SignKind.ROOM_PLATE, r.label, r.plateX, face, facing, height = 1.5f, width = 0.2f, tall = 0.15f)
        }
    }

    /**
     * Hanging signs: at the middle of the main corridor, facing along it, naming the range ahead;
     * and at each wing's mouth, naming the wing's range with an arrow into it. MISLEADING gives each
     * direction the other's range and points wing arrows the wrong way.
     */
    private fun SceneBuilder.rangeSigns() {
        if (signPolicy == SignPolicy.MISSING) return
        val swap = signPolicy == SignPolicy.MISLEADING
        val main = rooms.filter { !it.inWing }
        val mid = length / 2f
        val west = main.filter { it.plateX < mid }.mapNotNull { number(it.label) }
        val east = main.filter { it.plateX >= mid }.mapNotNull { number(it.label) }
        if (west.isNotEmpty() && east.isNotEmpty()) {
            val ahead = { eastward: Boolean -> if (eastward != swap) east else west }
            sign(SignKind.ROOM_RANGE, "Rooms ${ahead(true).min()}-${ahead(true).max()} ↑", mid, 1.5f, Facing.WEST, height = 2.3f, width = 1.0f, tall = 0.3f, hanging = true)
            sign(SignKind.ROOM_RANGE, "Rooms ${ahead(false).min()}-${ahead(false).max()} ↑", mid, 1.5f, Facing.EAST, height = 2.3f, width = 1.0f, tall = 0.3f, hanging = true)
        }
        for (wing in wings) {
            val x0 = wing.x0(roomWidth)
            val x1 = wing.x1(roomWidth)
            val numbers = rooms.filter { it.inWing && it.plateX >= x0 - 0.01f && it.plateX <= x1 + 0.01f }.mapNotNull { number(it.label) }
            if (numbers.isEmpty()) continue
            val text = "Rooms ${numbers.min()}-${numbers.max()}"
            // Seen from the west, the wing is on the left (north); from the east, on the right.
            sign(SignKind.ROOM_RANGE, "$text ${if (swap) "->" else "<-"}", x0 - 1.5f, 1.5f, Facing.WEST, height = 2.3f, width = 1.0f, tall = 0.3f, hanging = true)
            sign(SignKind.ROOM_RANGE, "$text ${if (swap) "<-" else "->"}", x1 + 1.5f, 1.5f, Facing.EAST, height = 2.3f, width = 1.0f, tall = 0.3f, hanging = true)
        }
    }

    private fun number(label: String): Int? = label.filter(Char::isDigit).toIntOrNull()

    companion object {
        const val CORRIDOR = 3f
        const val ROOM_DEPTH = 7f
        const val LIFT_WIDTH = 3f
        const val LIFT_DEPTH = 3f
        private const val WALL = 3.4f
    }
}
