package com.navassist.navcore.world

import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.semantic.SemanticDirection
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.semantic.TargetMatcher

/** What an observation says, independent of the perception class that produced it. */
enum class EvidenceKind {
    /** A numbered plate, read: "218". */
    ROOM_LABEL,

    /** A name read on a sign: a shop, "washroom". */
    PLACE_NAME,

    /** A wayfinding sign with a range: "Rooms 201-206 ->". */
    ROOM_RANGE,
    EXIT,
    DOOR,
    STAIRS,
    ELEVATOR,

    /** A floor indicator: "2". */
    FLOOR,

    /** A plate was seen but could not be read: too small, too oblique, OCR failed. */
    UNREAD_PLATE,

    /** A sign was seen but could not be read. */
    UNREAD_SIGN,
}

/** How the observation was put in the world. */
enum class Placement {
    /** Resolved against depth: a position. */
    DEPTH,

    /** Only the direction it was seen in. */
    BEARING_ONLY,

    /** Neither: a floor indicator, a sign read without a box. */
    NONE,
}

/**
 * One observation, as it reached the engine. Events are FACTS: what was reported, when, from
 * where. They are never edited or deleted once recorded; everything derived from them (landmarks,
 * numbering fits, hypotheses) can be rebuilt by replaying them in order.
 *
 * Positions are in the engine's canonical frame.
 */
data class ObservationEvent(
    val id: String,
    /** On the engine's clock. */
    val timeMillis: Long,
    val kind: EvidenceKind,
    /** The text it carries: a room number, a name, "201-206", a floor; null when unread or a pictogram. */
    val value: String?,
    /** The direction printed on a sign, relative to the observer's heading then. */
    val direction: SemanticDirection?,
    val placement: Placement,
    val position: Vec2?,
    /** World height of the object, when placed. */
    val heightY: Float?,
    val observerPosition: Vec2,
    val observerYawRadians: Float,
    /** Horizontal direction it was seen in, when known. */
    val bearing: Vec2?,
    /** The recogniser's score, 0..1. Not a calibrated probability. */
    val score: Float,
    /** The camera frame it came from, platform nanoseconds. */
    val captureNanos: Long?,
    /** The landmark it was associated with at recording time, if any. */
    val landmarkId: String?,
    /** The floor it was seen on, as last read off a lift display or floor sign; null before any. */
    val floor: String? = null,
)

/** A reading with no measured range counts as one from this far. */
private const val VOTE_FAR_METERS = 6f

/** What kind of physical thing a landmark is. */
enum class LandmarkKind { PLATE, SIGN, DOOR, EXIT, STAIRS, ELEVATOR }

/**
 * A physical thing in the building, with a stable ID, built from the events associated with it.
 *
 * Its label is a vote over its readings, so one OCR misread does not rename a door.
 */
class Landmark(
    val id: String,
    val kind: LandmarkKind,
    firstPosition: Vec2,
    val firstSeenMillis: Long,
    /** The floor it is on; landmarks are only ever associated within one floor. */
    val floor: String? = null,
) {
    var position: Vec2 = firstPosition
        private set
    var lastSeenMillis: Long = firstSeenMillis
        private set
    val eventIds = ArrayList<String>()
    /** Weighted votes per label: a reading from close up counts for more than one from afar. */
    private val labelVotes = LinkedHashMap<String, Float>()
    private val labelReads = LinkedHashMap<String, Int>()
    private var placements = 0

    /** The most-read label; ties go to the first read. Null until something has been read. */
    val label: String? get() = labelVotes.maxByOrNull { it.value }?.key

    /** Readings that disagree with [label]: OCR misreads, or a mistaken association. */
    val conflictingReads: Int get() = readCount - (label?.let { labelReads[it] } ?: 0)

    val readCount: Int get() = labelReads.values.sum()

    val timesSeen: Int get() = eventIds.size

    internal fun add(event: ObservationEvent) {
        eventIds += event.id
        lastSeenMillis = event.timeMillis
        event.position?.let { p ->
            placements++
            // Running mean of placements: depth error is roughly unbiased, so averaging helps.
            position = position + (p - position) * (1f / placements)
        }
        if (event.kind != EvidenceKind.UNREAD_PLATE && event.kind != EvidenceKind.UNREAD_SIGN) {
            event.value?.let { value ->
                val range = event.position?.distanceTo(event.observerPosition) ?: VOTE_FAR_METERS
                labelVotes[value] = (labelVotes[value] ?: 0f) + 1f / maxOf(1f, range)
                labelReads[value] = (labelReads[value] ?: 0) + 1
            }
        }
    }
}

/**
 * The engine's long-term memory of a trip: an append-only evidence ledger and the landmarks built
 * from it. It replaces nothing yet - the semantic hint store still steers exploration - but it is
 * what hypotheses cite and what the long-term planner's snapshot is drawn from.
 *
 * Deterministic: [replay] of [events] rebuilds the same landmarks.
 */
class WorldModel {

    private val ledger = ArrayList<ObservationEvent>()
    private val byId = LinkedHashMap<String, Landmark>()
    private var nextEvent = 1
    private var nextLandmark = 1

    val events: List<ObservationEvent> get() = ledger
    val landmarks: Collection<Landmark> get() = byId.values

    /** Increases with every event: a snapshot taken at one revision is stale at the next. */
    val revision: Int get() = ledger.size

    /**
     * The floor the user is on, as last read (a lift display, a floor sign); null until one is.
     * Set by the engine on a floor change; new events and landmarks are tagged with it.
     */
    var floor: String? = null

    fun landmark(id: String): Landmark? = byId[id]

    private val eventsById = HashMap<String, ObservationEvent>()

    fun event(id: String): ObservationEvent? = eventsById[id]

    fun reset() {
        ledger.clear()
        byId.clear()
        eventsById.clear()
        nextEvent = 1
        nextLandmark = 1
        floor = null
    }

    /**
     * Records one observation and associates it with a landmark when it was placed. Returns the
     * event, or null for an observation that says nothing about the world (none, today).
     */
    fun record(
        observation: SemanticObservation,
        timeMillis: Long,
        observerPose: Pose3D,
        bearing: Vec2?,
    ): ObservationEvent {
        val kind = kindOf(observation)
        val position = observation.worldPosition?.toVec2()
        val placement = when {
            position != null -> Placement.DEPTH
            bearing != null -> Placement.BEARING_ONLY
            else -> Placement.NONE
        }
        val landmark = position?.let { associate(kind, valueOf(observation), it, timeMillis, floor) }
        val event = ObservationEvent(
            id = "obs-%04d".format(nextEvent++),
            timeMillis = timeMillis,
            kind = kind,
            value = valueOf(observation),
            direction = directionOf(observation),
            placement = placement,
            position = position,
            heightY = observation.worldPosition?.y,
            observerPosition = observerPose.position2D,
            observerYawRadians = observerPose.yawRadians,
            bearing = bearing,
            score = observation.confidence,
            captureNanos = observation.timestampNanos,
            landmarkId = landmark?.id,
            floor = floor,
        )
        ledger += event
        eventsById[event.id] = event
        landmark?.add(event)
        return event
    }

    /**
     * The landmark a placed observation belongs to: the nearest of the same kind within the kind's
     * radius whose label does not contradict it, or a new one.
     */
    private fun associate(kind: EvidenceKind, value: String?, position: Vec2, timeMillis: Long, floor: String?): Landmark? {
        val landmarkKind = landmarkKindOf(kind) ?: return null
        val radius = associationRadiusMeters(landmarkKind)
        val match = byId.values.asSequence()
            .filter { it.kind == landmarkKind && it.floor == floor && it.position.distanceTo(position) <= radius }
            .filter { compatible(it.label, value) }
            .minByOrNull { it.position.distanceTo(position) }
        if (match != null) return match
        val prefix = landmarkKind.name.lowercase()
        return Landmark("$prefix-${nextLandmark++}", landmarkKind, position, timeMillis, floor).also { byId[it.id] = it }
    }

    /** Unread is compatible with anything; read labels must agree up to one OCR character. */
    private fun compatible(existing: String?, incoming: String?): Boolean {
        if (existing == null || incoming == null) return true
        if (existing.equals(incoming, ignoreCase = true)) return true
        val a = TargetMatcher.normalizeLabel(existing)
        val b = TargetMatcher.normalizeLabel(incoming)
        return a.length == b.length && a.zip(b).count { (x, y) -> x != y } <= 1
    }

    companion object {
        /** Rebuilds a model from a ledger: landmarks come out the same as when it was recorded. */
        fun replay(events: List<ObservationEvent>): WorldModel {
            val model = WorldModel()
            for (e in events) {
                val landmark = e.position?.let { model.associate(e.kind, e.value, it, e.timeMillis, e.floor) }
                val copy = e.copy(id = "obs-%04d".format(model.nextEvent++), landmarkId = landmark?.id)
                model.ledger += copy
                model.eventsById[copy.id] = copy
                landmark?.add(copy)
            }
            return model
        }

        fun kindOf(o: SemanticObservation): EvidenceKind = when (o) {
            is SemanticObservation.Room ->
                if (TargetMatcher.numericPart(o.label) != null) EvidenceKind.ROOM_LABEL else EvidenceKind.PLACE_NAME
            is SemanticObservation.RoomRange -> EvidenceKind.ROOM_RANGE
            is SemanticObservation.Exit -> EvidenceKind.EXIT
            is SemanticObservation.Door -> EvidenceKind.DOOR
            is SemanticObservation.Stairs -> EvidenceKind.STAIRS
            is SemanticObservation.Elevator -> EvidenceKind.ELEVATOR
            is SemanticObservation.Floor -> EvidenceKind.FLOOR
            is SemanticObservation.UnreadSign ->
                if (o.plate) EvidenceKind.UNREAD_PLATE else EvidenceKind.UNREAD_SIGN
        }

        private fun valueOf(o: SemanticObservation): String? = when (o) {
            is SemanticObservation.Room -> o.label
            is SemanticObservation.RoomRange -> "${o.min}-${o.max}"
            is SemanticObservation.Floor -> o.floor
            else -> null
        }

        private fun directionOf(o: SemanticObservation): SemanticDirection? = when (o) {
            is SemanticObservation.RoomRange -> o.direction
            is SemanticObservation.Exit -> o.direction
            is SemanticObservation.Door -> o.direction
            else -> null
        }

        fun landmarkKindOf(kind: EvidenceKind): LandmarkKind? = when (kind) {
            EvidenceKind.ROOM_LABEL, EvidenceKind.UNREAD_PLATE -> LandmarkKind.PLATE
            EvidenceKind.PLACE_NAME, EvidenceKind.ROOM_RANGE, EvidenceKind.UNREAD_SIGN -> LandmarkKind.SIGN
            EvidenceKind.EXIT -> LandmarkKind.EXIT
            EvidenceKind.DOOR -> LandmarkKind.DOOR
            EvidenceKind.STAIRS -> LandmarkKind.STAIRS
            EvidenceKind.ELEVATOR -> LandmarkKind.ELEVATOR
            EvidenceKind.FLOOR -> null
        }

        /** Two placements of one thing land within this of each other; two things rarely do. */
        fun associationRadiusMeters(kind: LandmarkKind): Float = when (kind) {
            LandmarkKind.PLATE -> 1.0f
            LandmarkKind.DOOR -> 1.0f
            LandmarkKind.SIGN -> 1.5f
            LandmarkKind.EXIT, LandmarkKind.STAIRS, LandmarkKind.ELEVATOR -> 2.0f
        }
    }
}
