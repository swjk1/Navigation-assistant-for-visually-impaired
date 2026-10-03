package com.navassist.sim.world

/**
 * A three-storey school, drawn in floor-plan coordinates (x east, y north, metres).
 *
 * Every floor has the same plan: a 3 m main corridor running 60 m east-west, lined with lockers,
 * classrooms both sides (closed doors, numbered plates beside them), a wing corridor running north
 * from x = 44 to 47, a switchback stairwell at each end of the main corridor, a lift niche
 * opposite the middle, and washrooms either side of it. Room numbers carry the floor: 1xx, 2xx,
 * 3xx - even on the south side, odd on the north, 17-20 in the wing.
 *
 * Floor 1 has the entrance lobby south of the middle of the corridor (main doors on its south
 * wall) and an exit door at the north end of the wing. On floors 2 and 3 the stairwells are the
 * fire exits and have EXIT signs over their doors.
 *
 * Hanging signs where corridors meet give room ranges with arrows ("Rooms 107-120 ->").
 */
object School {

    const val STOREY_HEIGHT = 3.6f
    const val FLOORS = 3
    private const val WALL = STOREY_HEIGHT - 0.1f
    private const val CORRIDOR = 3f
    private const val LENGTH = 60f
    private const val WING_X0 = 44f
    private const val WING_X1 = 47f
    private const val WING_END = 27f
    private const val ROOM_DEPTH = 8f

    /** South-side classrooms: (x0, x1, number within the floor). Floor 1 has the lobby at 24-36. */
    private val southRooms = listOf(
        Triple(0f, 8f, 2), Triple(8f, 16f, 4), Triple(16f, 24f, 6), Triple(24f, 30f, 8),
        Triple(30f, 36f, 10), Triple(36f, 44f, 12), Triple(44f, 52f, 14), Triple(52f, 60f, 16),
    )

    /** North-side classrooms off the main corridor. 24-36 is washrooms and the lift niche. */
    private val northRooms = listOf(
        Triple(0f, 8f, 1), Triple(8f, 16f, 3), Triple(16f, 24f, 5), Triple(36f, 44f, 7),
        Triple(47f, 53f, 9), Triple(53f, 60f, 11),
    )

    /** Wing classrooms: (y0, y1, number, on the west side). */
    private val wingRooms = listOf(
        Triple(11f, 19f, 17) to true, Triple(19f, WING_END, 19) to true,
        Triple(11f, 19f, 18) to false, Triple(19f, WING_END, 20) to false,
    )

    /** The school with the user on [floor] (1-3). */
    fun build(floor: Int): Scene {
        require(floor in 1..FLOORS) { "the school has floors 1-$FLOORS" }
        val elevations = (1..FLOORS).map { (it - floor) * STOREY_HEIGHT }
        return SceneBuilder("school-floor$floor").apply {
            for (level in 1..FLOORS) storey(elevations[level - 1]) { floorPlan(level) }
            stairwell(0f, 0f, CORRIDOR, -1f, elevations, STOREY_HEIGHT, exitSignOn = { it > 0 })
            stairwell(LENGTH, 0f, CORRIDOR, 1f, elevations, STOREY_HEIGHT, exitSignOn = { it > 0 })
        }.build()
    }

    /** Where a room's number plate is, in plan coordinates: what "arrived" is measured against. */
    fun platePlan(room: Int): Pair<Float, Float> {
        val number = room % 100
        southRooms.firstOrNull { it.third == number }?.let { return it.first + 2.4f to 0f }
        northRooms.firstOrNull { it.third == number }?.let { return it.first + 2.4f to CORRIDOR }
        wingRooms.firstOrNull { it.first.third == number }?.let { (room, west) ->
            return (if (west) WING_X0 else WING_X1) to room.first + 2.4f
        }
        error("no room $room in the school")
    }

    /** The main doors (floor 1), the wing's end door (floor 1), and the stair doors (any floor). */
    val lobbyDoor = 30f to -10f
    val wingDoor = 45.5f to WING_END
    val westStairDoor = 0f to 1.5f
    val eastStairDoor = LENGTH to 1.5f
    val lifts = listOf(29f to 6f, 31f to 6f)

    private fun SceneBuilder.floorPlan(level: Int) {
        val ground = level == 1
        val base = level * 100

        // Main corridor, and the wing.
        floor(0f, 0f, LENGTH, CORRIDOR)
        floor(WING_X0, CORRIDOR, WING_X1, WING_END)

        // South side of the main corridor.
        if (ground) {
            wall(0f, 0f, 24f, 0f, height = WALL, thickness = 0.2f)
            wall(36f, 0f, LENGTH, 0f, height = WALL, thickness = 0.2f)
            lobby()
        } else {
            wall(0f, 0f, LENGTH, 0f, height = WALL, thickness = 0.2f)
        }
        for ((x0, x1, number) in southRooms) {
            if (ground && x0 >= 24f && x1 <= 36f) continue
            classroom(x0, x1, 0f, -ROOM_DEPTH, base + number, doorFacing = Facing.NORTH)
        }

        // North side: classrooms, washrooms, the lift niche, and the wing opening.
        wall(0f, CORRIDOR, 28f, CORRIDOR, height = WALL, thickness = 0.2f)
        wall(32f, CORRIDOR, WING_X0, CORRIDOR, height = WALL, thickness = 0.2f)
        wall(WING_X1, CORRIDOR, LENGTH, CORRIDOR, height = WALL, thickness = 0.2f)
        for ((x0, x1, number) in northRooms) {
            classroom(x0, x1, CORRIDOR, CORRIDOR + ROOM_DEPTH, base + number, doorFacing = Facing.SOUTH)
        }
        for ((x0, x1) in listOf(24f to 28f, 32f to 36f)) {
            wall(x0, CORRIDOR, x0, CORRIDOR + ROOM_DEPTH, height = WALL, thickness = 0.2f)
            wall(x1, CORRIDOR, x1, CORRIDOR + ROOM_DEPTH, height = WALL, thickness = 0.2f)
            wall(x0, CORRIDOR + ROOM_DEPTH, x1, CORRIDOR + ROOM_DEPTH, height = WALL, thickness = 0.2f)
            val door = (x0 + x1) / 2f
            sign(SignKind.DOOR, "", door, CORRIDOR - 0.11f, Facing.SOUTH, height = 1.0f, width = 0.9f, tall = 2.0f)
            sign(SignKind.WASHROOM, "", door + 0.8f, CORRIDOR - 0.11f, Facing.SOUTH, height = 1.6f, width = 0.25f, tall = 0.25f)
        }
        // The lift niche, 3 m deep, two lifts on its back wall.
        floor(28f, CORRIDOR, 32f, 6f)
        wall(28f, CORRIDOR, 28f, 6f, height = WALL, thickness = 0.2f)
        wall(32f, CORRIDOR, 32f, 6f, height = WALL, thickness = 0.2f)
        wall(28f, 6f, 32f, 6f, height = WALL, thickness = 0.2f, name = "lift wall")
        for ((x, _) in lifts) {
            sign(SignKind.ELEVATOR, "", x, 5.89f, Facing.SOUTH, height = 1.1f, width = 1.1f, tall = 2.1f)
        }

        // The wing.
        wall(WING_X0, CORRIDOR, WING_X0, WING_END, height = WALL, thickness = 0.2f)
        wall(WING_X1, CORRIDOR, WING_X1, WING_END, height = WALL, thickness = 0.2f)
        wall(WING_X0, WING_END, WING_X1, WING_END, height = WALL, thickness = 0.2f, name = if (ground) "exit door" else "wall")
        for ((room, west) in wingRooms) {
            val (y0, y1, number) = room
            val x = if (west) WING_X0 else WING_X1
            val far = if (west) WING_X0 - ROOM_DEPTH else WING_X1 + ROOM_DEPTH
            wall(far, y0, x, y0, height = WALL, thickness = 0.2f)
            wall(far, y1, x, y1, height = WALL, thickness = 0.2f)
            wall(far, y0, far, y1, height = WALL, thickness = 0.2f)
            val face = if (west) x + 0.11f else x - 0.11f
            val facing = if (west) Facing.EAST else Facing.WEST
            sign(SignKind.DOOR, "", face, y0 + 1.5f, facing, height = 1.0f, width = 0.9f, tall = 2.0f)
            sign(SignKind.ROOM_PLATE, (base + number).toString(), face, y0 + 2.4f, facing, height = 1.5f, width = 0.2f, tall = 0.15f)
            lockers(if (west) x + 0.1f else x - 0.45f, y0 + 3f, if (west) x + 0.45f else x - 0.1f, y1 - 0.5f)
        }
        if (ground) {
            sign(SignKind.DOOR, "", 45.5f, WING_END - 0.11f, Facing.SOUTH, height = 1.0f, width = 0.9f, tall = 2.0f)
            sign(SignKind.EXIT_SIGN, "EXIT", 45.5f, WING_END - 0.11f, Facing.SOUTH, height = 2.3f, width = 0.6f, tall = 0.3f)
        }

        // Hanging signs where the wing meets the main corridor, for people walking either way.
        sign(SignKind.ROOM_RANGE, "Rooms ${base + 17}-${base + 20} <-", 42.5f, 1.5f, Facing.WEST, height = 2.3f, width = 1.0f, tall = 0.3f, hanging = true)
        sign(SignKind.ROOM_RANGE, "Rooms ${base + 17}-${base + 20} ->", 48.5f, 1.5f, Facing.EAST, height = 2.3f, width = 1.0f, tall = 0.3f, hanging = true)
        // And hanging in front of the lift niche, for people stepping out of a lift (facing south,
        // west is on their right).
        sign(SignKind.ROOM_RANGE, "Rooms ${base + 1}-${base + 6} ->", 28.5f, 1.5f, Facing.NORTH, height = 2.3f, width = 1.0f, tall = 0.3f, hanging = true)
        sign(SignKind.ROOM_RANGE, "<- Rooms ${base + 7}-${base + 20}", 31.5f, 1.5f, Facing.NORTH, height = 2.3f, width = 1.0f, tall = 0.3f, hanging = true)
    }

    /**
     * A classroom between x0 and x1 whose door wall is at [doorY] (the corridor wall, already
     * drawn) and back wall at [backY]: side walls, back wall, door and plate, lockers either side
     * of the door along the corridor.
     */
    private fun SceneBuilder.classroom(x0: Float, x1: Float, doorY: Float, backY: Float, number: Int, doorFacing: Facing) {
        wall(x0, doorY, x0, backY, height = WALL, thickness = 0.2f)
        wall(x1, doorY, x1, backY, height = WALL, thickness = 0.2f)
        wall(x0, backY, x1, backY, height = WALL, thickness = 0.2f)
        val face = if (doorFacing == Facing.NORTH) doorY + 0.11f else doorY - 0.11f
        sign(SignKind.DOOR, "", x0 + 1.5f, face, doorFacing, height = 1.0f, width = 0.9f, tall = 2.0f)
        sign(SignKind.ROOM_PLATE, number.toString(), x0 + 2.4f, face, doorFacing, height = 1.5f, width = 0.2f, tall = 0.15f)
        // Lockers along the corridor wall, clear of the door and the plate.
        val (ly0, ly1) = if (doorFacing == Facing.NORTH) doorY + 0.1f to doorY + 0.45f else doorY - 0.45f to doorY - 0.1f
        lockers(x0 + 3f, ly0, x1 - 0.5f, ly1)
    }

    private fun SceneBuilder.lockers(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (x1 - x0 < 0.5f || y1 - y0 < 0.3f) return
        block(x0, y0, x1, y1, height = 1.8f, name = "lockers")
    }

    /**
     * The entrance lobby, floor 1 only: 12 x 10 m south of the corridor, the main doors in its
     * south wall, a reception desk, benches and a trophy case, and hanging range signs where it
     * opens onto the corridor.
     */
    private fun SceneBuilder.lobby() {
        floor(24f, -10f, 36f, 0f)
        wall(24f, -10f, 24f, 0f, height = WALL, thickness = 0.2f)
        wall(36f, -10f, 36f, 0f, height = WALL, thickness = 0.2f)
        wall(24f, -10f, 36f, -10f, height = WALL, thickness = 0.2f, name = "main doors")
        sign(SignKind.DOOR, "", 30f, -9.89f, Facing.NORTH, height = 1.0f, width = 1.8f, tall = 2.1f)
        sign(SignKind.EXIT_SIGN, "EXIT", 30f, -9.89f, Facing.NORTH, height = 2.4f, width = 0.6f, tall = 0.3f)
        block(32.5f, -5f, 35.9f, -4.2f, height = 1.1f, name = "reception desk")
        block(24.1f, -8f, 24.6f, -3f, height = 2.0f, name = "trophy case")
        block(26f, -2.5f, 28f, -2f, height = 0.45f, name = "bench")
        block(32f, -2.5f, 34f, -2f, height = 0.45f, name = "bench")
        sign(SignKind.ROOM_RANGE, "<- Rooms 101-106", 28f, -0.5f, Facing.SOUTH, height = 2.3f, width = 1.0f, tall = 0.3f, hanging = true)
        sign(SignKind.ROOM_RANGE, "Rooms 107-120 ->", 32f, -0.5f, Facing.SOUTH, height = 2.3f, width = 1.0f, tall = 0.3f, hanging = true)
    }
}
