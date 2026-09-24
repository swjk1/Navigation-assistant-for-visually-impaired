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

    /** The plate position for a room in [roomCorridor], in plan coordinates. */
    fun roomPlatePlan(room: Int): Pair<Float, Float> = 2.35f to 3f + (room - 302) / 2 * 3f + 0.75f
}
