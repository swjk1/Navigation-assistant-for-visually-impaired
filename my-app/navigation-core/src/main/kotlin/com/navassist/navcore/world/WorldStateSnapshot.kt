package com.navassist.navcore.world

import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.NavigationTarget
import com.navassist.navcore.state.NavigationStatus

/**
 * A bounded, versioned view of the world model for the long-term planner and decision models:
 * perceived state only, never ground truth. Anything a planner refers to (a landmark, a frontier)
 * must be named here by ID, so a subgoal can be checked against the snapshot it was chosen from.
 */
data class WorldStateSnapshot(
    /** [WorldModel.revision] when taken: a subgoal chosen on an older revision is revalidated. */
    val revision: Int,
    val nowMillis: Long,
    val goal: NavigationTarget,
    val status: NavigationStatus,
    val pose: Pose3D,
    val walkedMeters: Float,
    val elapsedMillis: Long,
    /** Landmarks, nearest first, bounded. */
    val landmarks: List<LandmarkView>,
    val frontiers: List<FrontierView>,
    /** Signs seen but not read, nearest first: candidates for a closer look. */
    val unreadSigns: List<LandmarkView>,
    /** Where the engine believes the destination is, if it has a sighting. */
    val targetPosition: Vec2?,
    val targetSource: String?,
    val eventCount: Int,
    /** The floor being walked, as last read; null until one is. */
    val floor: String? = null,
)

data class LandmarkView(
    val id: String,
    val kind: LandmarkKind,
    val label: String?,
    val position: Vec2,
    val distanceMeters: Float,
    val timesSeen: Int,
    val readCount: Int,
    val conflictingReads: Int,
    val lastSeenAgeMillis: Long,
)

data class FrontierView(
    val id: String,
    val centroid: Vec2,
    val distanceMeters: Float,
    val score: Float,
    val cells: Int,
)
