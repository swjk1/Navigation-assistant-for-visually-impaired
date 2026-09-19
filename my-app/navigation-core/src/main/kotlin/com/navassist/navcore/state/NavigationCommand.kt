package com.navassist.navcore.state

/**
 * The movement instruction handed to the user-facing layer (speech / haptics).
 *
 * Deliberately tiny. Everything the planner knows is compressed into one of these six values,
 * because the consumer is a person walking down a corridor, not a motor controller.
 *
 * STOP is the safe default: it is emitted whenever the engine is not certain the way ahead is
 * both known and clear.
 */
enum class NavigationCommand {
    /** Keep walking forward. Only ever emitted when the path ahead is known-free. */
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    /** Stand still. Safe default under any uncertainty. */
    STOP,
    /** Stand still and sweep the phone around so the engine can acquire depth / recover tracking. */
    SCAN,
    ARRIVED,
}
