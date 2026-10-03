package com.navassist.sim.world

import kotlin.math.abs
import kotlin.random.Random

/**
 * Single-storey malls laid out from a few parameters, for testing on buildings nothing was tuned on.
 *
 * Built like the ground floor of [Mall]: a concourse between two rows of glass-fronted shops with
 * a 3 m entrance, shelving inside and the name 4.1 m up on the fascia; entrances with exit signs at
 * both ends; a services corridor in place of one shop, with lifts on one wall, washroom doors on
 * the other and a hanging washroom sign at its mouth. What changes between variants: how many
 * shops, how wide, how wide the concourse is, which side and bay the services corridor takes,
 * whether it ends in an exit, the order of the shop names and where the kiosks stand.
 */
class MallVariant(
    val name: String,
    private val shopsPerRow: Int,
    private val shopWidth: Float,
    private val concourseWidth: Float,
    /** Which bay (0-based, west to east) the services corridor replaces. */
    private val servicesBay: Int,
    /** True: the corridor opens off the north row; false: off the south row. */
    private val servicesNorth: Boolean,
    private val servicesExit: Boolean,
    seed: Long,
) {
    private val random = Random(seed)
    val length: Float = MARGIN * 2 + shopsPerRow * shopWidth
    private val bays = (0 until shopsPerRow).map { MARGIN + it * shopWidth }
    private val servicesCentre = bays[servicesBay] + shopWidth / 2f
    private val servicesMouthY = if (servicesNorth) concourseWidth else 0f
    private val servicesEndY = if (servicesNorth) concourseWidth + CORRIDOR_LENGTH else -CORRIDOR_LENGTH
    private val inward = if (servicesNorth) 1f else -1f

    /** Shop names, south row then north row, west to east; "" where the services corridor is. */
    private val names: Map<Boolean, List<String>> = run {
        val pool = NAME_POOL.shuffled(random).iterator()
        listOf(true, false).associateWith { south ->
            bays.indices.map { i -> if (isServices(i, south)) "" else pool.next() }
        }
    }

    /** Kiosks on the concourse centre line, clear of the ends and the services corridor's mouth. */
    private val kiosks: List<Float> = run {
        val count = 2 + random.nextInt(2)
        val spacing = (length - 20f) / count
        (0 until count).map { 10f + spacing * it + random.nextFloat() * (spacing - 4f) }
            .filter { x -> abs(x - servicesCentre) > 5f }
    }

    private fun isServices(bay: Int, south: Boolean) = bay == servicesBay && south != servicesNorth

    /** Every shop name in this mall, south row then north row, west to east. */
    val shopNames: List<String> get() = names.getValue(true).filter { it.isNotEmpty() } + names.getValue(false).filter { it.isNotEmpty() }

    /** Where the lift signs are, in plan coordinates. */
    val lifts: List<Pair<Float, Float>> = listOf(3f, 5.5f).map { (servicesCentre + 2f) to servicesMouthY + inward * it }

    /** Where the washroom doors are, in plan coordinates. */
    val washroomDoors: List<Pair<Float, Float>> = listOf(3f, 6f).map { (servicesCentre - 2f) to servicesMouthY + inward * it }

    /** A point on the open concourse: [fraction] of the way east, [side] metres north of its centre line. */
    fun concoursePoint(fraction: Float, side: Float = 0f): Pair<Float, Float> =
        (MARGIN + (length - 2 * MARGIN) * fraction) to (concourseWidth / 2f + side)

    /** The west end of the concourse, on its centre line. */
    val westStart: Pair<Float, Float> get() = 3f to concourseWidth / 2f

    fun build(): Scene = SceneBuilder("mall-variant-$name").apply {
        storey(0f) {
            floor(0f, 0f, length, concourseWidth)
            for (south in listOf(true, false)) shopRow(south)
            services()
            for ((x, facing) in listOf(0f to Facing.EAST, length to Facing.WEST)) {
                wall(x, 0f, x, concourseWidth, height = WALL, thickness = 0.2f, name = "entrance")
                val face = if (facing == Facing.EAST) x + 0.11f else x - 0.11f
                sign(SignKind.DOOR, "", face, concourseWidth / 2f, facing, height = 1.05f, width = 1.8f, tall = 2.1f)
                sign(SignKind.EXIT_SIGN, "EXIT", face, concourseWidth / 2f, facing, height = 2.5f, width = 0.6f, tall = 0.3f)
            }
            val mid = concourseWidth / 2f
            for (x in kiosks) block(x - 1.5f, mid - 1.5f, x + 1.5f, mid + 1.5f, height = 1.1f, name = "kiosk")
        }
    }.build()

    private fun SceneBuilder.shopRow(south: Boolean) {
        val front = if (south) 0f else concourseWidth
        val back = if (south) -SHOP_DEPTH else concourseWidth + SHOP_DEPTH
        wall(0f, front, bays.first(), front, height = WALL, thickness = 0.2f)
        wall(bays.last() + shopWidth, front, length, front, height = WALL, thickness = 0.2f)
        for ((i, x0) in bays.withIndex()) {
            if (isServices(i, south)) continue
            shop(x0, x0 + shopWidth, front, back, names.getValue(south)[i])
        }
    }

    private fun SceneBuilder.shop(x0: Float, x1: Float, front: Float, back: Float, label: String) {
        val facing = if (back < front) Facing.NORTH else Facing.SOUTH
        val into = if (back < front) -1f else 1f
        val door = (x0 + x1) / 2f
        floor(x0, front, x1, back)
        wall(x0, front, x0, back, height = WALL, thickness = 0.2f)
        wall(x1, front, x1, back, height = WALL, thickness = 0.2f)
        wall(x0, back, x1, back, height = WALL, thickness = 0.2f)
        wallWithGaps(x0, front, x1, front, listOf(door to 3f), height = 3.5f, thickness = 0.1f, material = Material.GLASS, name = "shop front")
        block(x0, front - 0.1f, x1, front + 0.1f, height = WALL, bottom = 3.5f, name = "fascia")
        sign(SignKind.ROOM_PLATE, label, door, front - into * 0.11f, facing, height = 4.1f, width = 3f, tall = 0.6f)
        for (depth in listOf(5f, 9f)) {
            val y = front + into * depth
            block(x0 + 2f, y, x1 - 2f, y + into * 0.6f, height = 1.6f, name = "shelving")
        }
    }

    private fun SceneBuilder.services() {
        val x0 = servicesCentre - 2f
        val x1 = servicesCentre + 2f
        val bay = bays[servicesBay]
        floor(x0, servicesMouthY, x1, servicesEndY)
        wall(bay, servicesMouthY, x0, servicesMouthY, height = WALL, thickness = 0.2f)
        wall(x1, servicesMouthY, bay + shopWidth, servicesMouthY, height = WALL, thickness = 0.2f)
        wall(x0, servicesMouthY, x0, servicesEndY, height = WALL, thickness = 0.2f)
        wall(x1, servicesMouthY, x1, servicesEndY, height = WALL, thickness = 0.2f)
        wall(x0, servicesEndY, x1, servicesEndY, height = WALL, thickness = 0.2f, name = if (servicesExit) "service exit" else "wall")
        for ((x, y) in lifts) sign(SignKind.ELEVATOR, "", x - 0.11f, y, Facing.WEST, height = 1.1f, width = 1.1f, tall = 2.1f)
        for ((x, y) in washroomDoors) {
            sign(SignKind.DOOR, "", x + 0.11f, y, Facing.EAST, height = 1.0f, width = 0.9f, tall = 2.0f)
            sign(SignKind.WASHROOM, "", x + 0.11f, y + inward * 0.8f, Facing.EAST, height = 1.6f, width = 0.25f, tall = 0.25f)
        }
        val towardsConcourse = if (servicesNorth) Facing.SOUTH else Facing.NORTH
        sign(SignKind.WASHROOM, "", servicesCentre, servicesMouthY + inward * 0.5f, towardsConcourse, height = 2.8f, width = 0.5f, tall = 0.5f, hanging = true)
        if (servicesExit) {
            val face = servicesEndY - inward * 0.11f
            sign(SignKind.DOOR, "", servicesCentre, face, towardsConcourse, height = 1.05f, width = 1.8f, tall = 2.1f)
            sign(SignKind.EXIT_SIGN, "EXIT", servicesCentre, face, towardsConcourse, height = 2.5f, width = 0.6f, tall = 0.3f)
        }
    }

    companion object {
        private const val WALL = 4.9f
        private const val SHOP_DEPTH = 15f
        private const val MARGIN = 2f
        private const val CORRIDOR_LENGTH = 10f

        private val NAME_POOL = listOf(
            "Atlas Books", "Bloom", "Cafe Uno", "Denim Hut", "Echo Audio", "Fresh Market", "Glow Beauty", "Harbor Toys",
            "Iron Gym", "Jade Garden", "Kite Sports", "Lumen Lights", "Maple Home", "Nova Phones", "Orbit Games",
            "Pine Pharmacy", "Quill Paper", "River Outfitters", "Saffron Grill", "Tidal Swim", "Umber Leather",
            "Velvet Shoes", "Willow Florist",
        )

        /** The held-out malls: nothing in the engine or the prompts was tuned on these. */
        val heldOut: List<MallVariant> = listOf(
            MallVariant("a", shopsPerRow = 8, shopWidth = 10f, concourseWidth = 14f, servicesBay = 2, servicesNorth = false, servicesExit = true, seed = 11),
            MallVariant("b", shopsPerRow = 10, shopWidth = 8f, concourseWidth = 10f, servicesBay = 7, servicesNorth = true, servicesExit = false, seed = 23),
            MallVariant("c", shopsPerRow = 6, shopWidth = 12f, concourseWidth = 18f, servicesBay = 4, servicesNorth = false, servicesExit = true, seed = 37),
        )
    }
}
