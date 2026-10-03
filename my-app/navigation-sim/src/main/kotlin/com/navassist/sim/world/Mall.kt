package com.navassist.sim.world

/**
 * A two-level shopping mall, drawn in floor-plan coordinates (x east, y north, metres).
 *
 * A 14 m concourse runs 84 m east-west between two rows of 10 m shops, each with a glass front, a
 * 3 m open entrance, shelving inside, and its name over the door. On the upper level the middle of
 * the concourse is an atrium open to the ground floor (x 18-66, y 5-9) behind a 1.1 m GLASS
 * balustrade, crossed by a bridge at x 40-44 and a landing at x 32-35 where two escalators arrive
 * from the ground floor. Columns stand along the atrium edges on both levels.
 *
 * Ground floor: entrances with exit signs at both ends, kiosks, planters and benches under the
 * atrium. Upper floor: a food court of tables at the east end. Both floors: a services corridor
 * off the north side (x 45-49) with two lifts and the washrooms, flagged by a hanging washroom sign
 * at its mouth; on the ground floor it ends in an exit to the car park.
 *
 * Shop signs carry names, not numbers - as real malls do.
 */
object Mall {

    const val STOREY_HEIGHT = 5f
    private const val WALL = STOREY_HEIGHT - 0.1f
    private const val LENGTH = 84f
    private const val WIDTH = 14f
    private const val SHOP_DEPTH = 15f

    /** The atrium on the upper level: (x0, x1) segments, all spanning y 5-9. */
    private val atrium = listOf(18f to 32f, 35f to 40f, 44f to 66f)
    private const val ATRIUM_Y0 = 5f
    private const val ATRIUM_Y1 = 9f
    private val columnsX = listOf(18f, 30f, 42f, 54f, 66f)

    private const val ESCALATOR_FOOT = 22f
    private const val ESCALATOR_STEPS = 20
    private const val ESCALATOR_TREAD = 0.5f
    private val escalators = listOf(6f to 7.2f, 7.2f to 8.4f)

    private val shopBays = (0 until 8).map { 2f + it * 10f }
    private const val SERVICES_BAY = 42f

    private val shopNames = mapOf(
        (1 to true) to listOf("Bookstore", "Shoe Palace", "Pharmacy", "Toy World", "Electronics", "Coffee House", "Fashion", "Sports"),
        (1 to false) to listOf("Jewellery", "Optician", "Bakery", "Phone Repair", "", "Home Goods", "Music", "Gift Shop"),
        (2 to true) to listOf("Cinema", "Games", "Bank", "Florist", "Salon", "Tea House", "Noodle Bar", "Burger Grill"),
        (2 to false) to listOf("Outdoor Gear", "Art Supplies", "Pet Shop", "Travel Agent", "", "Kitchen Store", "Taco Stand", "Pizza"),
    )

    /** The mall with the user on [level]: 1 is the ground floor, 2 the upper floor. */
    fun build(level: Int): Scene {
        require(level in 1..2) { "the mall has levels 1 and 2" }
        return SceneBuilder("mall-level$level").apply {
            storey((1 - level) * STOREY_HEIGHT) { groundFloor() }
            storey((2 - level) * STOREY_HEIGHT) { upperFloor() }
        }.build()
    }

    /** Where things are, in plan coordinates. */
    val westEntrance = 0f to 7f
    val eastEntrance = LENGTH to 7f
    val carParkExit = 47f to 24f
    val lifts = listOf(49f to 17f, 49f to 19.5f)
    val washroomDoors = listOf(45f to 17f, 45f to 20f)

    /** The centre of a named shop's entrance. */
    fun shopEntrance(name: String): Pair<Float, Float> {
        for ((key, names) in shopNames) {
            val i = names.indexOf(name)
            if (i >= 0) return shopBays[i] + 5f to if (key.second) 0f else WIDTH
        }
        error("no shop '$name' in the mall")
    }

    private fun SceneBuilder.groundFloor() {
        floor(0f, 0f, LENGTH, WIDTH)
        shops(1)
        services(ground = true)
        for ((x, facing) in listOf(0f to Facing.EAST, LENGTH to Facing.WEST)) {
            wall(x, 0f, x, WIDTH, height = WALL, thickness = 0.2f, name = "entrance")
            val face = if (facing == Facing.EAST) x + 0.11f else x - 0.11f
            sign(SignKind.DOOR, "", face, 7f, facing, height = 1.05f, width = 1.8f, tall = 2.1f)
            sign(SignKind.EXIT_SIGN, "EXIT", face, 7f, facing, height = 2.5f, width = 0.6f, tall = 0.3f)
        }
        columns()
        block(44f, 5.5f, 47f, 8.5f, height = 1.1f, name = "kiosk")
        block(56f, 5.5f, 59f, 8.5f, height = 1.1f, name = "kiosk")
        block(10f, 6f, 11.5f, 8f, height = 0.6f, name = "planter")
        block(72f, 6f, 73.5f, 8f, height = 0.6f, name = "planter")
        block(14f, 6.5f, 16f, 7f, height = 0.45f, name = "bench")
        block(68f, 7f, 70f, 7.5f, height = 0.45f, name = "bench")
        // Escalators rising east from the ground floor to the upper landing. Low steps are in the
        // way on the ground floor; from the upper landing they are a drop.
        for ((y0, y1) in escalators) {
            for (i in 0 until ESCALATOR_STEPS) {
                val top = (i + 1) * STOREY_HEIGHT / ESCALATOR_STEPS
                val x = ESCALATOR_FOOT + i * ESCALATOR_TREAD
                block(x, y0, x + ESCALATOR_TREAD, y1, height = top, bottom = top - 0.6f, name = "escalator step")
            }
        }
    }

    private fun SceneBuilder.upperFloor() {
        // Walkways either side of the atrium, the ends, the escalator landing and the bridge.
        floor(0f, 0f, LENGTH, ATRIUM_Y0)
        floor(0f, ATRIUM_Y1, LENGTH, WIDTH)
        floor(0f, ATRIUM_Y0, atrium.first().first, ATRIUM_Y1)
        floor(atrium.last().second, ATRIUM_Y0, LENGTH, ATRIUM_Y1)
        for (i in 0 until atrium.lastIndex) floor(atrium[i].second, ATRIUM_Y0, atrium[i + 1].first, ATRIUM_Y1)
        // The escalators' top plates: flush with the floor, and stood on like it. The steps that
        // descend beyond are the drop; the plate is not (it once counted as one, so a user who
        // stepped onto it "fell" where depth, rightly, showed level floor).
        for ((y0, y1) in escalators) {
            val top = ESCALATOR_FOOT + ESCALATOR_STEPS * ESCALATOR_TREAD
            floor(top - ESCALATOR_TREAD, y0, top, y1)
        }
        shops(2)
        services(ground = false)
        wall(0f, 0f, 0f, WIDTH, height = WALL, thickness = 0.2f)
        wall(LENGTH, 0f, LENGTH, WIDTH, height = WALL, thickness = 0.2f)
        columns()

        // Glass balustrade round every atrium segment, open only where the escalators arrive.
        for ((x0, x1) in atrium) {
            balustrade(x0, ATRIUM_Y0, x1, ATRIUM_Y0)
            balustrade(x0, ATRIUM_Y1, x1, ATRIUM_Y1)
            balustrade(x0, ATRIUM_Y0, x0, ATRIUM_Y1)
            if (x1 == ESCALATOR_FOOT + ESCALATOR_STEPS * ESCALATOR_TREAD) {
                balustrade(x1, ATRIUM_Y0, x1, escalators.first().first)
                balustrade(x1, escalators.last().second, x1, ATRIUM_Y1)
            } else {
                balustrade(x1, ATRIUM_Y0, x1, ATRIUM_Y1)
            }
        }

        // Food court: pedestal tables across the east end.
        for (x in listOf(68.5f, 72f, 75.5f, 79f)) for (y in listOf(2.5f, 7f, 11.5f)) {
            block(x - 0.6f, y - 0.4f, x + 0.6f, y + 0.4f, height = 0.75f, bottom = 0.7f, trackablePlane = true, name = "table")
            block(x - 0.1f, y - 0.1f, x + 0.1f, y + 0.1f, height = 0.7f, name = "table pedestal")
        }
    }

    private fun SceneBuilder.balustrade(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (x0 == x1 && y0 == y1) return
        wall(x0, y0, x1, y1, height = 1.1f, thickness = 0.06f, material = Material.GLASS, name = "glass balustrade")
    }

    private fun SceneBuilder.columns() {
        for (x in columnsX) for (y in listOf(ATRIUM_Y0, ATRIUM_Y1)) {
            block(x - 0.3f, y - 0.3f, x + 0.3f, y + 0.3f, height = WALL, name = "column")
        }
    }

    /** Both rows of shops on [level], the north row leaving room for the services corridor. */
    private fun SceneBuilder.shops(level: Int) {
        for (south in listOf(true, false)) {
            val front = if (south) 0f else WIDTH
            val back = if (south) -SHOP_DEPTH else WIDTH + SHOP_DEPTH
            val names = shopNames.getValue(level to south)
            // The shop-front line either side of the row.
            wall(0f, front, shopBays.first(), front, height = WALL, thickness = 0.2f)
            wall(shopBays.last() + 10f, front, LENGTH, front, height = WALL, thickness = 0.2f)
            for ((i, x0) in shopBays.withIndex()) {
                if (!south && x0 == SERVICES_BAY) continue
                shop(x0, x0 + 10f, front, back, names[i])
            }
        }
    }

    private fun SceneBuilder.shop(x0: Float, x1: Float, front: Float, back: Float, name: String) {
        val facing = if (back < front) Facing.NORTH else Facing.SOUTH
        val inward = if (back < front) -1f else 1f
        val door = (x0 + x1) / 2f
        floor(x0, front, x1, back)
        wall(x0, front, x0, back, height = WALL, thickness = 0.2f)
        wall(x1, front, x1, back, height = WALL, thickness = 0.2f)
        wall(x0, back, x1, back, height = WALL, thickness = 0.2f)
        // Glass either side of a 3 m entrance, a solid fascia above carrying the name.
        wallWithGaps(x0, front, x1, front, listOf(door to 3f), height = 3.5f, thickness = 0.1f, material = Material.GLASS, name = "shop front")
        block(x0, front - 0.1f, x1, front + 0.1f, height = WALL, bottom = 3.5f, name = "fascia")
        sign(SignKind.ROOM_PLATE, name, door, front - inward * 0.11f, facing, height = 4.1f, width = 3f, tall = 0.6f)
        for (depth in listOf(5f, 9f)) {
            val y = front + inward * depth
            block(x0 + 2f, y, x1 - 2f, y + inward * 0.6f, height = 1.6f, name = "shelving")
        }
    }

    /**
     * The services corridor off the north side of the concourse: lifts on its east wall, washroom
     * doors on its west wall, a hanging washroom sign at its mouth, and on the ground floor an exit
     * to the car park at its end.
     */
    private fun SceneBuilder.services(ground: Boolean) {
        val x0 = 45f
        val x1 = 49f
        val end = 24f
        floor(x0, WIDTH, x1, end)
        wall(SERVICES_BAY, WIDTH, x0, WIDTH, height = WALL, thickness = 0.2f)
        wall(x1, WIDTH, SERVICES_BAY + 10f, WIDTH, height = WALL, thickness = 0.2f)
        wall(x0, WIDTH, x0, end, height = WALL, thickness = 0.2f)
        wall(x1, WIDTH, x1, end, height = WALL, thickness = 0.2f)
        wall(x0, end, x1, end, height = WALL, thickness = 0.2f, name = if (ground) "car park exit" else "wall")
        for ((x, y) in lifts) {
            sign(SignKind.ELEVATOR, "", x - 0.11f, y, Facing.WEST, height = 1.1f, width = 1.1f, tall = 2.1f)
        }
        for ((x, y) in washroomDoors) {
            sign(SignKind.DOOR, "", x + 0.11f, y, Facing.EAST, height = 1.0f, width = 0.9f, tall = 2.0f)
            sign(SignKind.WASHROOM, "", x + 0.11f, y + 0.8f, Facing.EAST, height = 1.6f, width = 0.25f, tall = 0.25f)
        }
        sign(SignKind.WASHROOM, "", (x0 + x1) / 2f, WIDTH - 0.5f, Facing.SOUTH, height = 2.8f, width = 0.5f, tall = 0.5f, hanging = true)
        if (ground) {
            sign(SignKind.DOOR, "", carParkExit.first, end - 0.11f, Facing.SOUTH, height = 1.05f, width = 1.8f, tall = 2.1f)
            sign(SignKind.EXIT_SIGN, "EXIT", carParkExit.first, end - 0.11f, Facing.SOUTH, height = 2.5f, width = 0.6f, tall = 0.3f)
        }
    }
}
