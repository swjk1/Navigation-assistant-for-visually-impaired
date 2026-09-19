package com.navassist.navcore.semantic

/**
 * What the user asked for. Shared by every platform: the TypeScript layer translates the JS object
 * into exactly this, and a future Swift adapter would use the same type via Kotlin Multiplatform.
 */
sealed interface NavigationTarget {

    /** Human-readable form, surfaced in the snapshot for the UI. */
    val description: String

    data class Room(val label: String) : NavigationTarget {
        override val description: String get() = "Room $label"

        /** Normalised for matching: "Rm. 314 " and "314" are the same room. */
        val normalizedLabel: String = TargetMatcher.normalizeLabel(label)

        val number: Int? = TargetMatcher.numericPart(label)
    }

    data object Exit : NavigationTarget {
        override val description: String get() = "Exit"
    }

    data object Stairs : NavigationTarget {
        override val description: String get() = "Stairs"
    }

    data object Elevator : NavigationTarget {
        override val description: String get() = "Elevator"
    }

    /** No destination: map and explore only. */
    data object Explore : NavigationTarget {
        override val description: String get() = "Explore"
    }
}
