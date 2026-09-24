package com.navassist.sim.world

/**
 * The scene library. Every building is drawn in floor-plan coordinates: x east, y north, metres.
 * The user starts near the origin facing north unless a scenario says otherwise.
 */
object Buildings {

    /** A plain corridor [width] wide running north for [length] metres, open at the far end. */
    fun corridor(width: Float = 2f, length: Float = 24f): SceneBuilder =
        SceneBuilder("corridor").apply {
            floor(0f, -1f, width, length)
            wall(0f, -1f, 0f, length)
            wall(width, -1f, width, length)
            wall(0f, -1f, width, -1f)
        }

    /**
     * The corridor with a 0.4 m box 5 m ahead - the obstacle the old map used to erase. It sits in
     * the straight-line path (anyone walking up the middle clips it) but leaves a 1.15 m gap on
     * the right: passable with the engine's 0.45 m body radius, which a gap either side of a
     * centred box would not be.
     */
    fun lowBoxInCorridor() = corridor().apply {
        block(0.3f, 5f, 0.8f, 5.5f, height = 0.4f, name = "box")
    }.build()

    /**
     * Two rooms joined by a doorway [clearWidth] wide, the user starting in the first. Accessible
     * doorways are 0.81-0.91 m clear; the second room is only reachable through it.
     */
    fun doorway(clearWidth: Float = 0.85f) = SceneBuilder("doorway").apply {
        floor(-2f, -1f, 4f, 12f)
        wall(-2f, -1f, -2f, 12f); wall(4f, -1f, 4f, 12f); wall(-2f, -1f, 4f, -1f); wall(-2f, 12f, 4f, 12f)
        // The dividing wall at y = 5, with an opening centred on x = 1.
        val half = clearWidth / 2f
        wall(-2f, 5f, 1f - half, 5f, thickness = 0.15f)
        wall(1f + half, 5f, 4f, 5f, thickness = 0.15f)
    }.build()

    /** A table standing in a room, for the floor-versus-desk estimate. */
    fun roomWithTable() = SceneBuilder("room-with-table").apply {
        floor(0f, -1f, 6f, 8f)
        wall(0f, -1f, 0f, 8f); wall(6f, -1f, 6f, 8f); wall(0f, -1f, 6f, -1f); wall(0f, 8f, 6f, 8f)
        block(2f, 2f, 4f, 3f, height = 0.75f, bottom = 0.7f, trackablePlane = true, name = "tabletop")
        block(2f, 2f, 2.1f, 2.1f, height = 0.7f, name = "table leg")
        block(3.9f, 2f, 4f, 2.1f, height = 0.7f, name = "table leg")
        block(2f, 2.9f, 2.1f, 3f, height = 0.7f, name = "table leg")
        block(3.9f, 2.9f, 4f, 3f, height = 0.7f, name = "table leg")
    }.build()

    /** A glass pane across the corridor. Depth sees straight through it; the user does not. */
    fun glassAcrossCorridor() = corridor().apply {
        block(0f, 7f, 2f, 7.02f, height = 2.6f, material = Material.GLASS, name = "glass pane")
    }.build()

    /**
     * A stairwell opening in the corridor floor with a 0.7 m ledge beside it - too narrow to
     * pass with a body radius of inflation. The floor below is 3 m down.
     */
    fun stairwellDrop() = SceneBuilder("stairwell-drop").apply {
        floor(0f, -1f, 2f, 6f)
        floor(1.3f, 6f, 2f, 8f)
        floor(0f, 8f, 2f, 24f)
        floor(0f, 6f, 1.3f, 8f, height = -3f)
        wall(0f, -1f, 0f, 24f); wall(2f, -1f, 2f, 24f); wall(0f, -1f, 2f, -1f)
    }.build()

    /**
     * A corridor that meets a cross corridor at a T. The exit door is at the east end of the cross
     * corridor with an exit sign above it; the west branch is a dead end.
     */
    fun tJunctionWithExit() = SceneBuilder("t-junction-exit").apply {
        floor(0f, -1f, 2f, 10f)
        floor(-8f, 10f, 10f, 12f)
        wall(0f, -1f, 2f, -1f)
        wall(0f, -1f, 0f, 10f); wall(2f, -1f, 2f, 10f)
        wall(-8f, 10f, 0f, 10f); wall(2f, 10f, 10f, 10f)
        wall(-8f, 12f, 10f, 12f)
        wall(-8f, 10f, -8f, 12f)
        wall(10f, 10f, 10f, 12f, name = "exit door")
        sign(SignKind.DOOR, "", 9.95f, 11f, Facing.WEST, height = 1.0f, width = 0.9f, tall = 2.0f)
        sign(SignKind.EXIT_SIGN, "EXIT", 9.95f, 11f, Facing.WEST, height = 2.25f, width = 0.6f, tall = 0.3f)
    }.build()

    /** A long corridor of numbered doors on the east wall: 302, 304, ... 320. */
    fun roomCorridor() = SceneBuilder("room-corridor").apply {
        val width = 2.4f
        val length = 34f
        floor(0f, -1f, width, length)
        wall(0f, -1f, 0f, length); wall(width, -1f, width, length); wall(0f, -1f, width, -1f)
        var room = 302
        var y = 3f
        while (y < length - 2f) {
            sign(SignKind.DOOR, "", width - 0.05f, y, Facing.WEST, height = 1.0f, width = 0.9f, tall = 2.0f)
            // Plates sit beside the door, at eye height.
            sign(SignKind.ROOM_PLATE, room.toString(), width - 0.05f, y + 0.75f, Facing.WEST, height = 1.5f, width = 0.2f, tall = 0.15f)
            room += 2
            y += 3f
        }
    }.build()

    /**
     * A corridor that turns a blind corner: 11 m north, then 10 m west. Nothing of the second leg
     * is visible from the start.
     */
    fun lCorridor() = SceneBuilder("l-corridor").apply {
        floor(0f, -1f, 2f, 12f)
        floor(-12f, 10f, 0f, 12f)
        wall(0f, -1f, 2f, -1f)
        wall(2f, -1f, 2f, 12f)
        wall(0f, -1f, 0f, 10f)
        wall(-12f, 10f, 0f, 10f)
        wall(-12f, 12f, 2f, 12f)
        wall(-12f, 10f, -12f, 12f)
    }.build()

    /** A typical office corridor, 1.5 m wall to wall: the engine's safety envelope must fit. */
    fun narrowOfficeCorridor() = corridor(width = 1.5f, length = 22f).build().let { scene ->
        Scene("narrow-office-corridor", scene.boxes, scene.signs)
    }

    /**
     * A 2.4 m corridor with the furniture real corridors have along their walls: a bench, a bin,
     * a water dispenser, a planter. Each narrows the way; none blocks it.
     */
    fun clutteredCorridor() = SceneBuilder("cluttered-corridor").apply {
        val width = 2.4f
        floor(0f, -1f, width, 24f)
        wall(0f, -1f, 0f, 24f); wall(width, -1f, width, 24f); wall(0f, -1f, width, -1f)
        block(1.9f, 4f, 2.35f, 6f, height = 0.45f, name = "bench")
        block(0.05f, 8f, 0.45f, 8.4f, height = 0.7f, name = "bin")
        block(1.95f, 11f, 2.35f, 11.4f, height = 1.1f, name = "water dispenser")
        block(0.05f, 14f, 0.6f, 14.6f, height = 0.9f, name = "planter")
    }.build()

    /**
     * An open 10 x 13 m lobby with four free-standing columns between the entrance and the exit
     * door on the far wall, which has an exit sign above it.
     */
    fun lobbyWithPillars() = SceneBuilder("lobby-with-pillars").apply {
        floor(-5f, -1f, 5f, 12f)
        wall(-5f, -1f, 5f, -1f); wall(-5f, -1f, -5f, 12f); wall(5f, -1f, 5f, 12f)
        wall(-5f, 12f, 5f, 12f, name = "north wall with exit")
        for (x in listOf(-2f, 2f)) for (y in listOf(3.5f, 7.5f)) {
            block(x - 0.2f, y - 0.2f, x + 0.2f, y + 0.2f, height = 2.6f, name = "column")
        }
        sign(SignKind.DOOR, "", 0f, 11.95f, Facing.SOUTH, height = 1.0f, width = 0.9f, tall = 2.0f)
        sign(SignKind.EXIT_SIGN, "EXIT", 0f, 11.95f, Facing.SOUTH, height = 2.25f, width = 0.6f, tall = 0.3f)
    }.build()

    /** A corridor opening into a lift lobby with two elevator doors on the far wall. */
    fun elevatorLobby() = SceneBuilder("elevator-lobby").apply {
        floor(0f, -1f, 2f, 8f)
        floor(-3f, 8f, 5f, 14f)
        wall(0f, -1f, 2f, -1f); wall(0f, -1f, 0f, 8f); wall(2f, -1f, 2f, 8f)
        wall(-3f, 8f, 0f, 8f); wall(2f, 8f, 5f, 8f)
        wall(-3f, 8f, -3f, 14f); wall(5f, 8f, 5f, 14f)
        wall(-3f, 14f, 5f, 14f, name = "lift wall")
        for (x in listOf(-0.5f, 2.5f)) {
            sign(SignKind.ELEVATOR, "", x, 13.95f, Facing.SOUTH, height = 1.1f, width = 1.1f, tall = 2.1f)
        }
    }.build()

    /** A corridor that ends at an open stairwell spanning its full width. Stop; never fall. */
    fun corridorEndingAtStairs() = SceneBuilder("corridor-ending-at-stairs").apply {
        floor(0f, -1f, 2f, 9f)
        floor(0f, 9f, 2f, 14f, height = -3f)
        wall(0f, -1f, 2f, -1f); wall(0f, -1f, 0f, 14f); wall(2f, -1f, 2f, 14f); wall(0f, 14f, 2f, 14f)
    }.build()

    /** A corridor with a 4 m stretch of black carpet, which returns little depth. */
    fun darkCarpetCorridor() = SceneBuilder("dark-carpet-corridor").apply {
        floor(0f, -1f, 2f, 4f)
        floor(0f, 4f, 2f, 8f, material = Material.DARK)
        floor(0f, 8f, 2f, 24f)
        wall(0f, -1f, 0f, 24f); wall(2f, -1f, 2f, 24f); wall(0f, -1f, 2f, -1f)
    }.build()

    /**
     * A T-junction with a "Rooms 300-349 ->" sign on the wall ahead, pointing right. Room 314's
     * plate is 6 m down the right (east) branch; the left branch is a dead end.
     */
    fun signpostedJunction() = SceneBuilder("signposted-junction").apply {
        floor(0f, -1f, 2f, 10f)
        floor(-8f, 10f, 12f, 12f)
        wall(0f, -1f, 2f, -1f); wall(0f, -1f, 0f, 10f); wall(2f, -1f, 2f, 10f)
        wall(-8f, 10f, 0f, 10f); wall(2f, 10f, 12f, 10f)
        wall(-8f, 12f, 12f, 12f); wall(-8f, 10f, -8f, 12f); wall(12f, 10f, 12f, 12f)
        sign(SignKind.ROOM_RANGE, "Rooms 300-349 ->", 1f, 11.95f, Facing.SOUTH, height = 1.6f, width = 0.8f, tall = 0.3f)
        sign(SignKind.DOOR, "", 8f, 10.05f, Facing.NORTH, height = 1.0f, width = 0.9f, tall = 2.0f)
        sign(SignKind.ROOM_PLATE, "314", 8.75f, 10.05f, Facing.NORTH, height = 1.5f, width = 0.2f, tall = 0.15f)
    }.build()

    /** The plate position for a room in [roomCorridor], in plan coordinates. */
    fun roomPlatePlan(room: Int): Pair<Float, Float> = 2.35f to 3f + (room - 302) / 2 * 3f + 0.75f
}
