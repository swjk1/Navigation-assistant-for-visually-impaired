package com.navassist.sim.world

import kotlin.math.ceil

/**
 * A switchback stairwell through every storey of a building, entered through a doorway from the
 * end of a corridor.
 *
 * Seen from the corridor on each storey: a landing, then two flights side by side behind a rail -
 * the flight UP (steps as solid blocks: an obstacle) and the flight DOWN from the storey below
 * (steps below the floor: a drop the user falls into, as down a real stair). Stairs as a way
 * between floors are not modelled; the user never climbs them.
 *
 * Laid out in local coordinates: u runs from the doorway into the stairwell, v across it.
 *
 * @param entryX plan x of the wall between corridor and stairwell.
 * @param y0 plan y of the stairwell's south side; it is [width] wide, north of that.
 * @param direction +1 when the stairwell lies east of [entryX], -1 when west.
 * @param storeys the elevation of every storey, lowest first, relative to the storey walked.
 * @param exitSignOn whether the doorway on storey index i also has an EXIT sign over it - on
 *   upper floors the stairs are the fire exit.
 */
fun SceneBuilder.stairwell(
    entryX: Float,
    y0: Float,
    width: Float,
    direction: Float,
    storeys: List<Float>,
    storeyHeight: Float,
    doorWidth: Float = 1.8f,
    exitSignOn: (Int) -> Boolean = { false },
) = apply {
    val flight = (width - RAIL) / 2f
    val steps = ceil(storeyHeight / 2f / MAX_RISE).toInt()
    val rise = storeyHeight / 2f / steps
    val flightStart = LANDING
    val flightEnd = LANDING + steps * TREAD
    val length = flightEnd + HALF_LANDING
    val wallHeight = storeyHeight - 0.1f

    fun x(u: Float) = entryX + direction * u
    fun y(v: Float) = y0 + v
    val facing = if (direction > 0f) Facing.WEST else Facing.EAST

    for ((index, elevation) in storeys.withIndex()) {
        val lowest = index == 0
        val highest = index == storeys.lastIndex
        storey(elevation) {
            floor(x(0f), y(0f), x(flightStart), y(width))
            if (lowest) floor(x(flightStart), y(0f), x(length), y(width))
            if (highest) {
                // Nothing goes up from the top floor: floor where the up flight would be, and a
                // guard rail along the stair opening.
                floor(x(flightStart), y(flight), x(length), y(width))
                block(x(flightStart), y(flight), x(length), y(flight + RAIL), height = 1.0f, name = "stair guard rail")
            } else {
                for (i in 0 until steps) {
                    val u = flightStart + i * TREAD
                    block(x(u), y(width - flight), x(u + TREAD), y(width), height = (i + 1) * rise, name = "stair step up")
                }
                block(x(flightEnd), y(0f), x(length), y(width), height = storeyHeight / 2f, bottom = storeyHeight / 2f - 0.15f, name = "half landing")
                // The flight from the half landing up to the next storey, over the flight down.
                for (j in 0 until steps) {
                    val u = flightEnd - (j + 1) * TREAD
                    val top = storeyHeight / 2f + (j + 1) * rise
                    block(x(u), y(0f), x(u + TREAD), y(flight), height = top, bottom = top - 0.35f, name = "stair step")
                }
                block(x(flightStart), y(flight), x(flightEnd), y(flight + RAIL), height = 1.0f, name = "stair rail")
            }
            wall(x(0f), y(0f), x(length), y(0f), height = wallHeight, thickness = 0.2f, name = "stairwell wall")
            wall(x(0f), y(width), x(length), y(width), height = wallHeight, thickness = 0.2f, name = "stairwell wall")
            wall(x(length), y(0f), x(length), y(width), height = wallHeight, thickness = 0.2f, name = "stairwell wall")
            wallWithGaps(
                x(0f), y(0f), x(0f), y(width), listOf(y(width / 2f) to doorWidth),
                height = wallHeight, thickness = 0.2f, name = "stairwell wall",
            )
            block(x(-0.1f), y((width - doorWidth) / 2f), x(0.1f), y((width + doorWidth) / 2f), height = wallHeight, bottom = 2.2f, name = "lintel")

            val face = x(-0.11f)
            sign(SignKind.STAIR_SIGN, "Stairs", face, y(width - (width - doorWidth) / 4f), facing, height = 1.6f, width = 0.3f, tall = 0.3f)
            if (exitSignOn(index)) {
                sign(SignKind.EXIT_SIGN, "EXIT", face, y(width / 2f), facing, height = 2.4f, width = 0.6f, tall = 0.3f)
            }
        }
    }
}

private const val RAIL = 0.2f
private const val MAX_RISE = 0.18f
private const val TREAD = 0.28f
private const val LANDING = 1.6f
private const val HALF_LANDING = 1.4f
