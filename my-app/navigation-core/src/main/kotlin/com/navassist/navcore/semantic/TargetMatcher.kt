package com.navassist.navcore.semantic

/**
 * Decides whether a semantic observation refers to the thing the user asked for.
 *
 * Kept separate from the hint store so the matching rules (which are fuzzy and will change as the
 * perception layer matures) are testable in isolation.
 */
object TargetMatcher {

    /** Uppercases and strips everything that is not a letter or digit: "Rm. 314" -> "RM314". */
    fun normalizeLabel(label: String): String = buildString {
        for (ch in label) {
            if (ch.isLetterOrDigit()) append(ch.uppercaseChar())
        }
    }

    /** Extracts the leading run of digits anywhere in the label: "B314a" -> 314. */
    fun numericPart(label: String): Int? {
        var start = -1
        var end = -1
        for (i in label.indices) {
            if (label[i].isDigit()) {
                if (start < 0) start = i
                end = i
            } else if (start >= 0) {
                break
            }
        }
        if (start < 0) return null
        return label.substring(start, end + 1).toIntOrNull()
    }

    /**
     * True when [observation] identifies [target].
     *
     * Room matching accepts an exact normalised match, or a numeric match when both sides carry a
     * number (so "314" from a door plate matches a "Room 314" request).
     */
    fun matches(observation: SemanticObservation, target: NavigationTarget): Boolean =
        when (target) {
            is NavigationTarget.Room -> {
                val room = observation as? SemanticObservation.Room
                if (room == null) {
                    false
                } else {
                    val normalized = normalizeLabel(room.label)
                    val numeric = numericPart(room.label)
                    normalized == target.normalizedLabel ||
                        (numeric != null && target.number != null && numeric == target.number)
                }
            }

            NavigationTarget.Exit -> observation is SemanticObservation.Exit
            NavigationTarget.Door -> observation is SemanticObservation.Door
            NavigationTarget.Stairs -> observation is SemanticObservation.Stairs
            NavigationTarget.Elevator -> observation is SemanticObservation.Elevator
            NavigationTarget.Explore -> false
        }

    /**
     * Relevance of a directional room-range sign to the target, in -1..1.
     *
     * A sign that covers the target is strong positive evidence. A sign that does NOT cover it is
     * only WEAK negative evidence: corridor signage is routinely incomplete, and room numbering is
     * a heuristic, never a guarantee. We must never permanently eliminate a branch on this basis.
     */
    fun rangeRelevance(range: SemanticObservation.RoomRange, target: NavigationTarget): Float {
        val number = (target as? NavigationTarget.Room)?.number ?: return 0f
        return if (range.contains(number)) 1f else -0.25f
    }
}
