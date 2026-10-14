package com.navassist.sim.perception

import com.navassist.navcore.semantic.SemanticDirection
import com.navassist.navcore.semantic.SemanticObservation
import com.navassist.navcore.sensing.CameraIntrinsics
import com.navassist.sim.world.Scene
import com.navassist.sim.world.Sign
import com.navassist.sim.world.SignKind
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How well perception works. Recall defaults are the validation-set figures of the shipped YOLO
 * model (TECHNICAL.md section 4.1) - the classes navigation needs most are the weakest.
 */
data class PerceptionProfile(
    /** As the app captures: on-device YOLO + OCR every second (navigate.tsx). */
    val captureIntervalSeconds: Double = 1.0,
    /** Capture to observations arriving, on device. */
    val latencyMinSeconds: Double = 0.3,
    val latencyMaxSeconds: Double = 0.8,
    /** Every Nth capture may also make a Gemini round trip, and takes this long instead; 0 = never. */
    val slowEveryNth: Int = 4,
    val slowLatencyMinSeconds: Double = 0.6,
    val slowLatencyMaxSeconds: Double = 2.5,
    val recall: Map<SignKind, Float> = mapOf(
        SignKind.DOOR to 0.63f,
        SignKind.EXIT_SIGN to 0.73f,
        SignKind.STAIR_SIGN to 0.55f,
        SignKind.ELEVATOR to 0.75f,
        SignKind.WASHROOM to 0.95f,
        SignKind.LEFT_ARROW to 0.33f,
        SignKind.RIGHT_ARROW to 0.33f,
    ),
    /** Chance OCR reads a legible plate at all, and chance it gets a character wrong. */
    val ocrReadRate: Float = 0.85f,
    val ocrMisreadRate: Float = 0.08f,
    /** Smallest apparent size, in RGB pixels, at which a detector / OCR can work. */
    val minDetectPixels: Float = 14f,
    val minReadPixels: Float = 12f,
    /**
     * Report text that was seen but not read (SemanticObservation.UnreadSign): a plate or sign too
     * small to read yet big enough to notice, one at too steep an angle, one OCR failed on. Off by
     * default, so runs that predate it are unchanged.
     */
    val reportUnreadable: Boolean = false,
    /** Smallest apparent size at which text is noticed as text, though not read. */
    val minNoticePixels: Float = 6f,

) {
    companion object {
        /** The app before on-device capture ran every second: one pipeline, Gemini included, every 2.5 s. */
        val LEGACY = PerceptionProfile(
            captureIntervalSeconds = 2.5, latencyMinSeconds = 0.6, latencyMaxSeconds = 2.5, slowEveryNth = 0,
        )

        val PERFECT = PerceptionProfile(
            latencyMinSeconds = 0.0,
            latencyMaxSeconds = 0.0,
            slowEveryNth = 0,
            recall = SignKind.entries.associateWith { 1f },
            ocrReadRate = 1f,
            ocrMisreadRate = 0f,
        )
    }
}

/**
 * Stands in for captureFrame -> YOLO/OCR/VLM -> toSemanticObservations.
 *
 * Signs in view are projected into the camera image; each is then detected (or missed) at the
 * profile's recall, OCR text is read (or misread), and the result arrives after a latency, carrying
 * the CAPTURE timestamp - exactly the shape the app now sends.
 *
 * The label mapping mirrors src/perception/perceptionToSemantic.ts. It is a re-implementation, not
 * a shared module (that file is TypeScript); keep the two in step. Image coordinates are produced
 * directly in SENSOR orientation: the upright-image round trip is covered by the JS tests.
 */
class PerceptionModel(
    private val profile: PerceptionProfile,
    private val intrinsics: CameraIntrinsics,
    private val random: Random,
) {
    /**
     * What happened to one sign at one capture, for observability: every sign in front of the
     * camera and inside the frame, read or not, and why.
     *
     * @param fate `seen` (detected, or read and parsed), or why not: `too-close`, `behind-or-oblique`,
     *        `occluded`, `too-small`, `not-detected`, `ocr-failed`, `unparsed`.
     * @param box the sign's bounding box in the SENSOR image, 0..1: left, top, right, bottom.
     * @param apparentPixels its smaller side in image pixels, as the size gates judge it.
     * @param reported what perception reported: the text read, or the class detected.
     */
    class SignSighting(
        val sign: Sign,
        val fate: String,
        val distanceMeters: Float,
        val box: FloatArray,
        val apparentPixels: Float,
        val reported: String? = null,
        val misread: Boolean = false,
    )

    /** One capture: when, from where (camera-to-world, column-major), and every sign in frame. */
    class Capture(val seconds: Double, val timestampNanos: Long, val cameraToWorld: FloatArray, val sightings: List<SignSighting>)

    /** Observability: told about every capture. Null (the default) records nothing. */
    var onCapture: ((Capture) -> Unit)? = null

    private class Pending(val dueSeconds: Double, val observations: List<SemanticObservation>, val signs: List<Sign>)

    /** The sign each observation in the last [deliver] came from, in the same order. */
    var lastDeliveredSigns: List<Sign> = emptyList()
        private set

    private var sightings: ArrayList<SignSighting>? = null
    private var lastRead: String? = null
    private var lastMisread = false

    private var pending: Pending? = null
    private var nextCaptureSeconds = profile.captureIntervalSeconds

    var captures = 0
        private set
    var detections = 0
        private set

    /** Diagnostics: told why each sign was or was not reported at every capture. */
    var probe: ((Sign, String) -> Unit)? = null

    /** Diagnostics: where in the sensor image (0..1) each reported sign was. */
    var seenAt: ((Sign, Float, Float) -> Unit)? = null

    private fun miss(sign: Sign, why: String): SemanticObservation? {
        probe?.invoke(sign, why)
        return null
    }

    /** Whether a capture should be taken now. Captures never overlap, as in the app. */
    fun wantsCapture(nowSeconds: Double): Boolean = pending == null && nowSeconds >= nextCaptureSeconds

    /** Takes a capture: what this camera can see now, delivered later. */
    fun capture(nowSeconds: Double, timestampNanos: Long, scene: Scene, cameraToWorld: FloatArray) {
        captures++
        nextCaptureSeconds = nowSeconds + profile.captureIntervalSeconds
        sightings = if (onCapture != null) ArrayList() else null
        val sources = ArrayList<Sign>()
        val observations = scene.signs.mapNotNull { sign ->
            observe(sign, scene, cameraToWorld, timestampNanos)?.also { sources += sign }
        }
        sightings?.let { onCapture?.invoke(Capture(nowSeconds, timestampNanos, cameraToWorld.copyOf(), it)) }
        sightings = null
        detections += observations.size
        val slow = profile.slowEveryNth > 0 && captures % profile.slowEveryNth == 0
        val latency = if (slow) {
            profile.slowLatencyMinSeconds + random.nextDouble() * (profile.slowLatencyMaxSeconds - profile.slowLatencyMinSeconds)
        } else {
            profile.latencyMinSeconds + random.nextDouble() * (profile.latencyMaxSeconds - profile.latencyMinSeconds)
        }
        pending = Pending(nowSeconds + latency, observations, sources)
    }

    /** Observations whose analysis has finished by now. */
    fun deliver(nowSeconds: Double): List<SemanticObservation> {
        val due = pending ?: return emptyList()
        if (due.dueSeconds > nowSeconds) return emptyList()
        pending = null
        lastDeliveredSigns = due.signs
        return due.observations
    }

    private fun observe(sign: Sign, scene: Scene, m: FloatArray, timestampNanos: Long): SemanticObservation? {
        val ox = m[12]
        val oy = m[13]
        val oz = m[14]
        val tx = sign.x - ox
        val ty = sign.y - oy
        val tz = sign.z - oz
        val distance = sqrt(tx * tx + ty * ty + tz * tz)

        // Camera space: project onto the camera axes (columns of the rotation). Geometry only - no
        // random draws - so the sightings record can say where in the frame a sign was, whatever
        // its fate, without changing any run.
        val cx = tx * m[0] + ty * m[1] + tz * m[2]
        val cy = tx * m[4] + ty * m[5] + tz * m[6]
        val cz = tx * m[8] + ty * m[9] + tz * m[10]
        val depth = -cz
        val u = if (depth > 0.1f) intrinsics.cx + intrinsics.fx * cx / depth else Float.NaN
        val v = if (depth > 0.1f) intrinsics.cy - intrinsics.fy * cy / depth else Float.NaN
        val inFrame = depth > 0.1f && u >= 0f && v >= 0f && u < intrinsics.width && v < intrinsics.height
        val apparent = if (depth > 0.1f) intrinsics.fy * minOf(sign.widthMeters, sign.heightMeters) / depth else 0f

        fun record(fate: String) {
            val list = sightings ?: return
            if (!inFrame) return
            list += SignSighting(sign, fate, distance, boxOf(sign, m), apparent, lastRead, lastMisread)
        }
        fun missed(why: String): SemanticObservation? {
            record(why)
            return miss(sign, why)
        }
        lastRead = null
        lastMisread = false

        if (distance < 0.3f) return missed("too-close")
        // Only readable from in front.
        val facing = (-tx * sign.normalX - tz * sign.normalZ) / distance
        if (facing < 0.3f) {
            // Too steep to read, but a plate seen edge-on is still a plate.
            if (profile.reportUnreadable && facing >= OBLIQUE_NOTICE && inFrame && isText(sign) && apparent >= profile.minNoticePixels &&
                !occludedFrom(scene, ox, oy, oz, tx, ty, tz, distance)
            ) {
                record("behind-or-oblique")
                return unread(sign, u, v, m, timestampNanos)
            }
            return missed("behind-or-oblique")
        }
        if (cz >= -0.1f) return miss(sign, "behind-camera")
        if (!inFrame) return miss(sign, "out-of-frame")

        // Occluded? Glass does not block the view, only depth-like surfaces do.
        val hit = scene.raycast(ox, oy, oz, tx, ty, tz, 1f, skipGlass = true)
        if (hit != null && hit.t < 1f - 0.12f / distance) return missed("occluded")

        val sensorX = u / intrinsics.width
        val sensorY = v / intrinsics.height
        seenAt?.invoke(sign, sensorX, sensorY)
        val (boxWidth, boxHeight) = boxSize(sign, m)
        // Portrait capture rotated upright (90 degrees clockwise): upright x = 1 - sensor y.
        val uprightX = 1f - sensorY
        val confidence = 0.55f + 0.4f * random.nextFloat()

        return when (sign.kind) {
            SignKind.ROOM_PLATE, SignKind.ROOM_RANGE -> {
                if (apparent < profile.minReadPixels) {
                    if (profile.reportUnreadable && apparent >= profile.minNoticePixels) {
                        record("too-small")
                        return unread(sign, u, v, m, timestampNanos)
                    }
                    return missed("too-small")
                }
                if (random.nextFloat() >= profile.ocrReadRate) {
                    if (profile.reportUnreadable) {
                        record("ocr-failed")
                        return unread(sign, u, v, m, timestampNanos)
                    }
                    return missed("ocr-failed")
                }
                (text(sign.text, sensorX, sensorY, uprightX, timestampNanos)?.withBox(boxWidth, boxHeight) ?: missed("unparsed"))
                    ?.also { probe?.invoke(sign, "seen"); record("seen") }
            }
            else -> {
                if (apparent < profile.minDetectPixels) return missed("too-small")
                if (random.nextFloat() >= (profile.recall[sign.kind] ?: 0f)) return missed("not-detected")
                detected(sign.kind, confidence, sensorX, sensorY, uprightX, timestampNanos)?.withBox(boxWidth, boxHeight)
                    ?.also { probe?.invoke(sign, "seen"); lastRead = sign.kind.name; record("seen") }
            }
        }
    }

    private fun isText(sign: Sign) = sign.kind == SignKind.ROOM_PLATE || sign.kind == SignKind.ROOM_RANGE

    private fun occludedFrom(scene: Scene, ox: Float, oy: Float, oz: Float, tx: Float, ty: Float, tz: Float, distance: Float): Boolean {
        val hit = scene.raycast(ox, oy, oz, tx, ty, tz, 1f, skipGlass = true)
        return hit != null && hit.t < 1f - 0.12f / distance
    }

    /** Text seen but not read: where it was in the frame, so depth can place it. */
    private fun unread(sign: Sign, u: Float, v: Float, m: FloatArray, timestampNanos: Long): SemanticObservation {
        val (w, h) = boxSize(sign, m)
        return SemanticObservation.UnreadSign(
            plate = sign.kind == SignKind.ROOM_PLATE,
            confidence = 0.6f,
            normalizedX = u / intrinsics.width,
            normalizedY = v / intrinsics.height,
            timestampNanos = timestampNanos,
            normalizedWidth = w,
            normalizedHeight = h,
        )
    }

    /** The sign's box in the SENSOR image, 0..1: left, top, right, bottom, clamped to the frame. */
    private fun boxOf(sign: Sign, m: FloatArray): FloatArray {
        val rightX = -sign.normalZ
        val rightZ = sign.normalX
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (sx in listOf(-0.5f, 0.5f)) for (sy in listOf(-0.5f, 0.5f)) {
            val px = sign.x + rightX * sign.widthMeters * sx - m[12]
            val py = sign.y + sign.heightMeters * sy - m[13]
            val pz = sign.z + rightZ * sign.widthMeters * sx - m[14]
            val depth = -(px * m[8] + py * m[9] + pz * m[10])
            if (depth <= 0.05f) continue
            val u = intrinsics.cx + intrinsics.fx * (px * m[0] + py * m[1] + pz * m[2]) / depth
            val v = intrinsics.cy - intrinsics.fy * (px * m[4] + py * m[5] + pz * m[6]) / depth
            minU = minOf(minU, u); maxU = maxOf(maxU, u)
            minV = minOf(minV, v); maxV = maxOf(maxV, v)
        }
        if (minU > maxU) return floatArrayOf(0f, 0f, 0f, 0f)
        val w = intrinsics.width.toFloat()
        val h = intrinsics.height.toFloat()
        return floatArrayOf((minU / w).coerceIn(0f, 1f), (minV / h).coerceIn(0f, 1f), (maxU / w).coerceIn(0f, 1f), (maxV / h).coerceIn(0f, 1f))
    }

    /** The sign's bounding box in the SENSOR image, 0..1: its four corners, projected. */
    private fun boxSize(sign: Sign, m: FloatArray): Pair<Float, Float> {
        // Along the sign's face: horizontal, perpendicular to its normal; and straight up.
        val rightX = -sign.normalZ
        val rightZ = sign.normalX
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (sx in listOf(-0.5f, 0.5f)) for (sy in listOf(-0.5f, 0.5f)) {
            val px = sign.x + rightX * sign.widthMeters * sx - m[12]
            val py = sign.y + sign.heightMeters * sy - m[13]
            val pz = sign.z + rightZ * sign.widthMeters * sx - m[14]
            val cx = px * m[0] + py * m[1] + pz * m[2]
            val cy = px * m[4] + py * m[5] + pz * m[6]
            val depth = -(px * m[8] + py * m[9] + pz * m[10])
            if (depth <= 0.05f) continue
            val u = intrinsics.cx + intrinsics.fx * cx / depth
            val v = intrinsics.cy - intrinsics.fy * cy / depth
            minU = minOf(minU, u); maxU = maxOf(maxU, u)
            minV = minOf(minV, v); maxV = maxOf(maxV, v)
        }
        return (maxU - minU) / intrinsics.width to (maxV - minV) / intrinsics.height
    }

    private fun SemanticObservation.withBox(width: Float, height: Float): SemanticObservation = when (this) {
        is SemanticObservation.Room -> copy(normalizedWidth = width, normalizedHeight = height)
        is SemanticObservation.Exit -> if (normalizedX != null) copy(normalizedWidth = width, normalizedHeight = height) else this
        is SemanticObservation.Door -> copy(normalizedWidth = width, normalizedHeight = height)
        is SemanticObservation.Stairs -> copy(normalizedWidth = width, normalizedHeight = height)
        is SemanticObservation.Elevator -> copy(normalizedWidth = width, normalizedHeight = height)
        else -> this
    }

    /** Mirrors `fromObject` in perceptionToSemantic.ts. */
    private fun detected(
        kind: SignKind,
        confidence: Float,
        x: Float,
        y: Float,
        uprightX: Float,
        timestampNanos: Long,
    ): SemanticObservation? {
        val direction = clockDirection(uprightX)
        return when (kind) {
            SignKind.STAIR_SIGN -> SemanticObservation.Stairs(confidence, x, y, timestampNanos)
            SignKind.ELEVATOR -> SemanticObservation.Elevator(confidence, x, y, timestampNanos)
            SignKind.EXIT_SIGN -> SemanticObservation.Exit(direction, confidence, x, y, timestampNanos)
            SignKind.LEFT_ARROW -> SemanticObservation.Exit(SemanticDirection.LEFT, confidence * 0.4f, timestampNanos = timestampNanos)
            SignKind.RIGHT_ARROW -> SemanticObservation.Exit(SemanticDirection.RIGHT, confidence * 0.4f, timestampNanos = timestampNanos)
            SignKind.WASHROOM -> SemanticObservation.Room("washroom", confidence, x, y, timestampNanos)
            SignKind.DOOR -> SemanticObservation.Door(direction, confidence, x, y, timestampNanos)
            SignKind.ROOM_PLATE, SignKind.ROOM_RANGE -> null
        }
    }

    /** Mirrors `fromText` in perceptionToSemantic.ts. */
    private fun text(raw: String, x: Float, y: Float, uprightX: Float, timestampNanos: Long): SemanticObservation? {
        val read = if (random.nextFloat() < profile.ocrMisreadRate) misread(raw) else raw
        lastRead = read
        lastMisread = read != raw
        val direction = when {
            uprightX < 0.35f -> SemanticDirection.LEFT
            uprightX > 0.65f -> SemanticDirection.RIGHT
            else -> SemanticDirection.FORWARD
        }
        RANGE.find(read)?.let { match ->
            val min = match.groupValues[1].toInt()
            val max = match.groupValues[2].toInt()
            if (max >= min) return SemanticObservation.RoomRange(min, max, arrowDirection(read) ?: direction, 0.9f, timestampNanos)
        }
        return when {
            Regex("\\b(exit|way\\s*out)\\b", RegexOption.IGNORE_CASE).containsMatchIn(read) ->
                SemanticObservation.Exit(direction, 0.9f, x, y, timestampNanos)
            Regex("\\bstairs?\\b", RegexOption.IGNORE_CASE).containsMatchIn(read) ->
                SemanticObservation.Stairs(0.9f, x, y, timestampNanos)
            Regex("\\b(elevator|lift)\\b", RegexOption.IGNORE_CASE).containsMatchIn(read) ->
                SemanticObservation.Elevator(0.9f, x, y, timestampNanos)
            read.any { it.isDigit() } -> SemanticObservation.Room(read, 0.9f, x, y, timestampNanos)
            isPlaceName(read) -> SemanticObservation.Room(read, 0.9f, x, y, timestampNanos)
            else -> null
        }
    }

    /** Mirrors `isPlaceName` in perceptionToSemantic.ts. */
    private fun isPlaceName(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 3..30) return false
        if (!trimmed.first().isLetter() || !trimmed.all { it.isLetter() || it.isWhitespace() || it in "&'.-" }) return false
        if (trimmed.count { it.isLetter() } < 3) return false
        return trimmed.uppercase().replace(Regex("\\s+"), " ") !in NOT_PLACE_NAMES
    }

    /** Mirrors `arrowDirection` in perceptionToSemantic.ts. */
    private fun arrowDirection(text: String): SemanticDirection? = when {
        Regex("→|->|⟶|➡|▶|⇒").containsMatchIn(text) -> SemanticDirection.RIGHT
        Regex("←|<-|⟵|⬅|◀|⇐").containsMatchIn(text) -> SemanticDirection.LEFT
        Regex("↑|⬆|▲|⇑").containsMatchIn(text) -> SemanticDirection.FORWARD
        else -> null
    }

    /** One character wrong, the way OCR fails on small plates: 314 -> 31A, 3l4, 374. */
    private fun misread(text: String): String {
        if (text.isEmpty()) return text
        val i = random.nextInt(text.length)
        val replacement = "017AlIO"[random.nextInt(7)]
        return text.substring(0, i) + replacement + text.substring(i + 1)
    }

    /** Mirrors clockFromBox + DIRECTION_BY_CLOCK: 9-10 o'clock left, 2-3 o'clock right. */
    private fun clockDirection(uprightX: Float) = when {
        uprightX < 0.28f -> SemanticDirection.LEFT
        uprightX >= 0.72f -> SemanticDirection.RIGHT
        else -> SemanticDirection.FORWARD
    }

    private companion object {
        /** Mirrors NOT_PLACE_NAMES in perceptionToSemantic.ts. */
        val NOT_PLACE_NAMES = setOf(
            "PUSH", "PULL", "OPEN", "CLOSED", "SALE", "CAUTION", "DANGER", "WARNING", "NOTICE", "ENTER",
            "ENTRANCE", "IN", "OUT", "NO", "STOP", "WET FLOOR", "NO SMOKING", "NO ENTRY", "STAFF ONLY",
            "PRIVATE", "KEEP OUT", "KEEP CLEAR", "FIRE DOOR", "FIRE DOOR KEEP SHUT", "EMERGENCY",
            "DO NOT ENTER", "AUTHORIZED PERSONNEL ONLY", "WELCOME", "THANK YOU", "OFFICE HOURS",
        )

        /** Seen at least this face-on (cosine), a sign too steep to read is still noticed. */
        const val OBLIQUE_NOTICE = 0.1f

        val RANGE =Regex("(\\d{2,4})\\s*(?:-|–|—|to)\\s*(\\d{2,4})")
    }
}
