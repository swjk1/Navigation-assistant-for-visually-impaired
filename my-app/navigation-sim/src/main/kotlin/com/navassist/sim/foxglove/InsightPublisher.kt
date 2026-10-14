package com.navassist.sim.foxglove

import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.state.NavigationMemory
import com.navassist.sim.Simulation
import com.navassist.sim.TraceSample
import com.navassist.sim.perception.PerceptionModel
import com.navassist.sim.world.Sign
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What the robot took in and what it remembers, as Foxglove messages - the layers that make
 * perception and memory inspectable, beside [FoxglovePublisher]'s building, grid and path.
 *
 * Topics:
 *   /perception/camera      CompressedImage  each capture as the camera framed it (upright), over
 *                                            the depth image, every sign in frame boxed and coloured
 *                                            by its fate: read, too small, missed, occluded, oblique
 *   /perception/log         Log              one line per capture and per placement
 *   /perception/signs       SceneUpdate      (plan) every sign in the building: never framed (grey),
 *                                            framed but never read (red), read (green), and counts
 *   /perception/placements  SceneUpdate      (plan) where each observation was placed, with a line to
 *                                            the true sign; bearing-only readings as grey rays
 *   /engine/memory          SceneUpdate      (engine) signs held, room plates, the numbering estimate,
 *                                            the destination sighting, the walked graph, ruled-out
 *                                            areas, advice, planner guesses, frontier scores
 *   /engine/frontiers       navassist.Frontiers      every frontier with every term of its score
 *   /engine/memory_summary  navassist.MemorySummary  counts and the numbering estimate, for plots
 */
class InsightPublisher(private val sink: FoxgloveSink) {

    private val camera = sink.channel("/perception/camera", "foxglove.CompressedImage", FoxglovePublisher.schema("CompressedImage"))
    private val log = sink.channel("/perception/log", "foxglove.Log", FoxglovePublisher.schema("Log"))
    private val signs = sink.channel("/perception/signs", "foxglove.SceneUpdate", FoxglovePublisher.schema("SceneUpdate"))
    private val placements = sink.channel("/perception/placements", "foxglove.SceneUpdate", FoxglovePublisher.schema("SceneUpdate"))
    private val memory = sink.channel("/engine/memory", "foxglove.SceneUpdate", FoxglovePublisher.schema("SceneUpdate"))
    private val frontiers = sink.channel("/engine/frontiers", "navassist.Frontiers", FRONTIERS_SCHEMA)
    private val summary = sink.channel("/engine/memory_summary", "navassist.MemorySummary", SUMMARY_SCHEMA)
    private val landmarks = sink.channel("/engine/landmarks", "foxglove.SceneUpdate", FoxglovePublisher.schema("SceneUpdate"))
    private val strategy = sink.channel("/engine/strategy", "navassist.Strategy", STRATEGY_SCHEMA)
    private val strategyLog = sink.channel("/engine/strategy_log", "foxglove.Log", FoxglovePublisher.schema("Log"))
    private var outcomesLogged = 0

    /** Per sign: captures it was framed in, and captures it was read or detected in. */
    private val framed = HashMap<Sign, Int>()
    private val read = HashMap<Sign, Int>()
    private val placed = ArrayList<Pair<Simulation.Placement, Pair<Float, Float>?>>()
    private var lastMemoryAt = -1.0
    private var captures = 0

    /** Hooks into a simulation; call before it runs. Replaces its perception and placement hooks. */
    fun attach(sim: Simulation) {
        sim.perception.onCapture = { capture -> onCapture(sim, capture) }
        sim.onPlaced = { seconds, list -> onPlaced(sim, seconds, list) }
        sim.listeners += { s, sample, _ -> onFrame(s, sample) }
    }

    // ------------------------------------------------------------------ perception

    private fun onCapture(sim: Simulation, capture: PerceptionModel.Capture) {
        captures++
        val t = FoxglovePublisher.timeOf(capture.seconds)
        for (s in capture.sightings) {
            framed.merge(s.sign, 1, Int::plus)
            if (s.fate == "seen") read.merge(s.sign, 1, Int::plus)
        }
        sink.publish(camera, t, cameraImage(sim, capture, t))
        sink.publish(signs, t, signScene(sim, t))
        val line = if (capture.sightings.isEmpty()) "nothing legible in frame" else capture.sightings
            .sortedBy { it.distanceMeters }
            .joinToString("  ·  ") { s ->
                val name = s.sign.text.ifEmpty { s.sign.kind.name.lowercase() }
                val what = if (s.fate == "seen") "READ" + (if (s.misread) " as '${s.reported}' (misread)" else "") else s.fate
                "%s %s (%.0f px, %.1f m)".format(name, what, s.apparentPixels, s.distanceMeters)
            }
        sink.publish(log, t, logMessage(t, if (capture.sightings.any { it.fate == "seen" }) 2 else 1, "capture $captures: $line"))
    }

    private fun onPlaced(sim: Simulation, seconds: Double, list: List<Simulation.Placement>) {
        val t = FoxglovePublisher.timeOf(seconds)
        for (p in list) {
            val origin = p.located.observerPose?.position2D?.let { sim.canonicalToPlan(it) }
            placed += p to origin
        }
        while (placed.size > MAX_PLACEMENTS) placed.removeAt(0)
        sink.publish(placements, t, placementScene(sim, t))
        val line = list.joinToString("  ·  ") { p ->
            val name = p.sign.text.ifEmpty { p.sign.kind.name.lowercase() }
            if (p.errorMeters != null) "%s placed by depth, %.2f m off".format(name, p.errorMeters) else "$name: bearing only"
        }
        sink.publish(log, t, logMessage(t, 2, "delivered: $line"))
    }

    /** The capture as the user would see it (upright portrait), drawn over the depth image. */
    private fun cameraImage(sim: Simulation, capture: PerceptionModel.Capture, t: Long): String {
        // The sensor image is landscape; the capture is rotated 90 degrees clockwise to upright:
        // upright x = 1 - sensor y, upright y = sensor x.
        val w = IMAGE_WIDTH
        val h = IMAGE_HEIGHT
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        sim.deliveredDepth?.let { depth ->
            for (y in 0 until h) for (x in 0 until w) {
                val sx = (y.toFloat() / h * depth.width).toInt().coerceIn(0, depth.width - 1)
                val sy = ((1f - x.toFloat() / w) * depth.height).toInt().coerceIn(0, depth.height - 1)
                val mm = depth.depthMm[sy * depth.width + sx].toInt() and 0xFFFF
                image.setRGB(x, y, if (mm == 0) 0x101828 else {
                    val v = (230 - mm / 8000f * 200f).toInt().coerceIn(30, 230)
                    (v shl 16) or (v shl 8) or v
                })
            }
        }
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        for (s in capture.sightings.sortedByDescending { it.distanceMeters }) {
            val (l, top, r, b) = s.box.toList()
            val x0 = ((1f - b) * w).toInt()
            val x1 = ((1f - top) * w).toInt()
            val y0 = (l * h).toInt()
            val y1 = (r * h).toInt()
            val c = fateColor(s.fate)
            g.color = c
            g.stroke = BasicStroke(if (s.fate == "seen") 3f else 2f)
            g.drawRect(x0, y0, maxOf(2, x1 - x0), maxOf(2, y1 - y0))
            val label = buildString {
                append(s.sign.text.ifEmpty { s.sign.kind.name.lowercase() })
                append("  ")
                append(if (s.fate == "seen") (if (s.misread) "read '${s.reported}'" else "READ") else s.fate)
                append("  %.0fpx %.1fm".format(s.apparentPixels, s.distanceMeters))
            }
            val metrics = g.fontMetrics
            val ty = (y0 - 4).coerceAtLeast(metrics.ascent + 2)
            val tx = x0.coerceIn(2, maxOf(2, w - metrics.stringWidth(label) - 2))
            g.color = Color(0, 0, 0, 170)
            g.fillRect(tx - 2, ty - metrics.ascent, metrics.stringWidth(label) + 4, metrics.height)
            g.color = c
            g.drawString(label, tx, ty)
        }
        g.color = Color(0, 0, 0, 170)
        g.fillRect(0, 0, w, 28)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 18)
        val seen = capture.sightings.count { it.fate == "seen" }
        g.drawString("%.1f s   capture %d   %d sign(s) in frame, %d read".format(capture.seconds, captures, capture.sightings.size, seen), 6, 21)
        g.dispose()
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return json {
            "timestamp" to time(t)
            "frame_id" to "camera"
            "data" bytes png
            "format" to "png"
        }
    }

    private fun signScene(sim: Simulation, t: Long): String = json {
        "deletions" to arr {}
        "entities" to arr {
            add(obj {
                "timestamp" to time(t)
                "frame_id" to "plan"
                "id" to "signs"
                "lifetime" to time(0)
                "frame_locked" to true
                "metadata" to arr {}
                "cubes" to arr {
                    for (sign in sim.scene.signs) {
                        if (sign.y < 0f || sign.y > sim.scene.ceilingHeight) continue
                        val c = when {
                            (read[sign] ?: 0) > 0 -> floatArrayOf(0.25f, 0.85f, 0.4f, 0.95f)
                            (framed[sign] ?: 0) > 0 -> floatArrayOf(0.95f, 0.3f, 0.25f, 0.95f)
                            else -> floatArrayOf(0.5f, 0.5f, 0.55f, 0.6f)
                        }
                        add(obj {
                            "pose" to pose(sign.x + sign.normalX * 0.08f, -sign.z - sign.normalZ * 0.08f, sign.y)
                            "size" to obj { "x" to 0.22f; "y" to 0.22f; "z" to 0.22f }
                            "color" to color(c[0], c[1], c[2], c[3])
                        })
                    }
                }
                "texts" to arr {
                    for (sign in sim.scene.signs) {
                        val n = framed[sign] ?: continue
                        add(obj {
                            "pose" to pose(sign.x, -sign.z, sign.y + 0.45f)
                            "billboard" to true
                            "font_size" to 20
                            "scale_invariant" to true
                            "color" to color(1f, 1f, 1f, 0.9f)
                            "text" to "${sign.text.ifEmpty { sign.kind.name.lowercase() }}  read ${read[sign] ?: 0}/$n"
                        })
                    }
                }
                emptyPrimitives("arrows", "spheres", "cylinders", "lines", "triangles", "models")
            })
        }
    }

    private fun placementScene(sim: Simulation, t: Long): String = json {
        "deletions" to arr {}
        "entities" to arr {
            add(obj {
                "timestamp" to time(t)
                "frame_id" to "plan"
                "id" to "placements"
                "lifetime" to time(0)
                "frame_locked" to true
                "metadata" to arr {}
                "spheres" to arr {
                    for ((p, _) in placed) {
                        val (x, y) = p.planPosition ?: continue
                        val e = p.errorMeters ?: 0f
                        val c = when {
                            e < 0.5f -> floatArrayOf(0.25f, 0.85f, 0.4f)
                            e < 1.5f -> floatArrayOf(0.95f, 0.8f, 0.25f)
                            else -> floatArrayOf(0.95f, 0.3f, 0.25f)
                        }
                        add(obj {
                            "pose" to pose(x, y, p.sign.y)
                            "size" to obj { "x" to 0.16f; "y" to 0.16f; "z" to 0.16f }
                            "color" to color(c[0], c[1], c[2], 0.9f)
                        })
                    }
                }
                "lines" to arr {
                    add(obj {
                        // Placement -> truth, and bearing-only rays, as one line list.
                        "type" to 2
                        "pose" to pose(0f, 0f, 0f)
                        "thickness" to 0.025f
                        "scale_invariant" to false
                        "points" to arr {
                            for ((p, origin) in placed) {
                                val at = p.planPosition
                                if (at != null) {
                                    add(vec(at.first, at.second, p.sign.y))
                                    add(vec(p.sign.x, -p.sign.z, p.sign.y))
                                } else if (origin != null) {
                                    val bearing = p.located.bearing ?: continue
                                    val tip = sim.canonicalToPlan(p.located.observerPose!!.position2D + bearing * RAY_METERS) ?: continue
                                    add(vec(origin.first, origin.second, 1.3f))
                                    add(vec(tip.first, tip.second, 1.3f))
                                }
                            }
                        }
                        "color" to color(0.85f, 0.85f, 0.9f, 0.7f)
                        "colors" to arr {}
                        "indices" to arr {}
                    })
                }
                emptyPrimitives("arrows", "cubes", "cylinders", "texts", "triangles", "models")
            })
        }
    }

    // ------------------------------------------------------------------ memory

    private fun onFrame(sim: Simulation, sample: TraceSample) {
        if (lastMemoryAt >= 0 && sample.seconds - lastMemoryAt < MEMORY_PERIOD) return
        lastMemoryAt = sample.seconds
        val t = FoxglovePublisher.timeOf(sample.seconds)
        val m = sim.engine.memory()
        val floor = sim.engine.floorEstimate().floorY
        sink.publish(memory, t, memoryScene(m, floor, t))
        sink.publish(frontiers, t, frontierTable(m, t))
        sink.publish(summary, t, summaryMessage(m, t))
        sink.publish(landmarks, t, landmarkScene(sim, floor, t))
        sink.publish(strategy, t, strategyMessage(sim, t))
        val history = sim.engine.executor.history
        while (outcomesLogged < history.size) {
            val o = history[outcomesLogged++]
            sink.publish(strategyLog, t, logMessage(t, if (o.status.name == "SUCCEEDED") 2 else 3,
                "${o.skill} ${o.targetId ?: ""}: ${o.status} - ${o.reason} (%.1f m, %.0f s)".format(o.travelledMeters, o.elapsedMillis / 1000.0)))
        }
    }

    /**
     * The world model's landmarks, by ID: what a planner may name. Read ones coloured by kind and
     * labelled "id label"; unread ones grey, labelled "id ?"; the active subgoal's target ringed.
     */
    private fun landmarkScene(sim: Simulation, floor: Float, t: Long): String = json {
        val active = sim.engine.executor.active
        "deletions" to arr {}
        "entities" to arr {
            add(obj {
                "timestamp" to time(t)
                "frame_id" to "engine"
                "id" to "landmarks"
                "lifetime" to time(0)
                "frame_locked" to true
                "metadata" to arr {}
                "spheres" to arr {
                    for (l in sim.engine.world.landmarks) {
                        val c = if (l.readCount == 0 && (l.kind.name == "PLATE" || l.kind.name == "SIGN")) floatArrayOf(0.6f, 0.6f, 0.6f) else kindColor(l.kind.name)
                        add(sphere(l.position, floor + 1.2f, 0.22f, c, 0.9f))
                    }
                    active?.targetPosition?.let { add(sphere(it, floor + 0.1f, 0.5f, floatArrayOf(1f, 0.9f, 0.2f), 0.5f)) }
                }
                "texts" to arr {
                    for (l in sim.engine.world.landmarks) add(text(l.position, floor + 1.55f, "${l.id} ${l.label ?: "?"}", 0.9f))
                    active?.targetPosition?.let { add(text(it, floor + 0.5f, "${active.skill} ${active.targetId ?: ""}", 1f)) }
                }
                emptyPrimitives("arrows", "cubes", "cylinders", "lines", "triangles", "models")
            })
        }
    }

    /** The planner's state for plots and tables: the active subgoal and every hypothesis. */
    private fun strategyMessage(sim: Simulation, t: Long): String = json {
        val e = sim.engine
        val active = e.executor.active
        "timestamp" to time(t)
        "skill" to (active?.skill?.name ?: "none")
        "target" to (active?.targetId ?: "")
        "purpose" to (active?.purpose ?: "")
        "outcomes" to e.executor.history.size
        "rejections" to e.rejections.size
        "landmarks" to e.world.landmarks.size
        "events" to e.world.events.size
        "hypotheses" to arr {
            for (h in e.beliefs.hypotheses) add(obj {
                "id" to h.id
                "family" to h.family.name
                "proposition" to h.proposition
                "status" to h.status.name
                "belief" to h.belief
                "support" to h.support.size
                "against" to h.against.size
            })
        }
    }

    private fun memoryScene(m: NavigationMemory, floor: Float, t: Long): String = json {
        "deletions" to arr {}
        "entities" to arr {
            add(obj {
                "timestamp" to time(t)
                "frame_id" to "engine"
                "id" to "memory"
                "lifetime" to time(0)
                "frame_locked" to true
                "metadata" to arr {}
                "spheres" to arr {
                    // Signs placed by depth.
                    for (s in m.signs) {
                        val p = s.position ?: continue
                        add(sphere(p, floor + 1.4f, 0.18f, kindColor(s.kind), fade(s.ageMillis)))
                    }
                    // Room plates in the numbering ledger.
                    for (p in m.plates) add(sphere(p.position, floor + 1.6f, 0.12f, floatArrayOf(0.35f, 0.85f, 0.95f), 0.9f))
                    // Where the numbering puts the destination.
                    m.numbering?.let { add(sphere(it.position, floor + 1.0f, 0.35f, floatArrayOf(0.9f, 0.35f, 0.9f), 0.5f + 0.5f * it.confidence)) }
                    // The destination sighting, by how it was placed.
                    m.sighting?.let { s ->
                        val c = when (s.source) {
                            "depth" -> floatArrayOf(0.25f, 0.9f, 0.4f)
                            "ranged" -> floatArrayOf(0.95f, 0.6f, 0.2f)
                            else -> floatArrayOf(0.95f, 0.9f, 0.3f)
                        }
                        add(sphere(Vec2(s.worldPosition.x, s.worldPosition.z), floor + 0.6f, 0.45f, c, 0.85f))
                    }
                    // The walked graph's places.
                    for (n in m.nodes) add(sphere(n.position, floor + 0.05f, 0.14f, floatArrayOf(0.7f, 0.7f, 0.75f), 0.8f))
                }
                "cylinders" to arr {
                    for ((area, failures) in m.blacklisted) add(obj {
                        "pose" to pose(area.x, area.z, floor + 0.02f)
                        "size" to obj { "x" to 1.6f; "y" to 1.6f; "z" to 0.03f }
                        "bottom_scale" to 1f
                        "top_scale" to 1f
                        "color" to color(0.95f, 0.25f, 0.25f, 0.15f + 0.1f * failures.coerceAtMost(4))
                    })
                    for ((h, weight) in m.plan) add(obj {
                        "pose" to pose(h.position.x, h.position.z, floor + 0.03f)
                        "size" to obj { "x" to 2f * h.radiusMeters; "y" to 2f * h.radiusMeters; "z" to 0.04f }
                        "bottom_scale" to 1f
                        "top_scale" to 1f
                        "color" to color(0.6f, 0.4f, 0.95f, 0.1f + 0.4f * weight)
                    })
                }
                "arrows" to arr {
                    // Signs known only by the direction they were seen in.
                    for (s in m.signs) {
                        if (s.position != null) continue
                        val b = s.bearing ?: continue
                        add(arrow(s.observerPosition, b, floor + 1.4f, 1.5f, kindColor(s.kind), fade(s.ageMillis)))
                    }
                    for (a in m.advice) {
                        val c = if (a.weight >= 0f) floatArrayOf(0.3f, 0.6f, 1f) else floatArrayOf(1f, 0.35f, 0.3f)
                        add(arrow(a.origin, a.direction, floor + 0.3f, 0.6f + 2.4f * kotlin.math.abs(a.weight), c, 0.9f))
                    }
                    m.numbering?.let { add(arrow(it.position, it.rising, floor + 1.0f, 1.5f, floatArrayOf(0.9f, 0.35f, 0.9f), 0.8f)) }
                }
                "lines" to arr {
                    if (m.edges.isNotEmpty()) add(obj {
                        "type" to 2
                        "pose" to pose(0f, 0f, 0f)
                        "thickness" to 0.04f
                        "scale_invariant" to false
                        "points" to arr {
                            val byId = m.nodes.associateBy { it.id }
                            for (e in m.edges) {
                                val a = byId[e.fromId] ?: continue
                                val b = byId[e.toId] ?: continue
                                add(vec(a.position.x, a.position.z, floor + 0.05f))
                                add(vec(b.position.x, b.position.z, floor + 0.05f))
                            }
                        }
                        "color" to color(0.7f, 0.7f, 0.75f, 0.7f)
                        "colors" to arr {}
                        "indices" to arr {}
                    })
                }
                "texts" to arr {
                    for (s in m.signs) {
                        val anchor = s.position ?: s.observerPosition
                        add(text(anchor, floor + 1.75f, "${s.label ?: s.kind.lowercase()}${s.pointing?.let { " ${it.name.lowercase()}" } ?: ""}", fade(s.ageMillis)))
                    }
                    for (p in m.plates) add(text(p.position, floor + 1.85f, "#${p.number}", 0.9f))
                    m.numbering?.let {
                        add(text(it.position, floor + 1.6f, "numbering: target here? conf %.2f, %d plates%s".format(
                            it.confidence, it.platesUsed, if (it.sideKnown) ", side known" else ""), 1f))
                    }
                    m.sighting?.let { s ->
                        add(text(Vec2(s.worldPosition.x, s.worldPosition.z), floor + 1.2f, "TARGET (${s.source}, conf %.2f)".format(s.confidence), 1f))
                    }
                    for ((h, weight) in m.plan) add(text(h.position, floor + 0.4f, "planner guess, weight %.2f".format(weight), 0.9f))
                    // Frontier scores, the chosen one marked.
                    for (f in m.frontiers) {
                        val chosen = m.selectedFrontier?.let { it.centroid.distanceTo(f.centroid) < 0.3f } ?: false
                        add(text(f.centroid, floor + 0.35f, (if (chosen) "> " else "") + "%.2f".format(f.score), if (chosen) 1f else 0.7f))
                    }
                }
                emptyPrimitives("cubes", "triangles", "models")
            })
        }
    }

    private fun frontierTable(m: NavigationMemory, t: Long): String = json {
        "timestamp" to time(t)
        "frontiers" to arr {
            for (f in m.frontiers) add(obj {
                "id" to f.id
                "selected" to (m.selectedFrontier?.let { it.centroid.distanceTo(f.centroid) < 0.3f } ?: false)
                "x" to f.centroid.x
                "z" to f.centroid.z
                "cells" to f.cellCount
                "score" to f.score
                "informationGain" to f.estimatedInformationGain
                "semantic" to f.semanticScore
                "distanceMeters" to f.distanceMeters
                "revisit" to f.revisitPenalty
                "risk" to f.riskPenalty
                "advice" to f.adviceScore
                "plan" to f.planScore
            })
        }
    }

    private fun summaryMessage(m: NavigationMemory, t: Long): String = json {
        "timestamp" to time(t)
        "signsHeld" to m.signs.size
        "platesRead" to m.plates.size
        "numberingConfidence" to (m.numbering?.confidence ?: 0f)
        "numberingPlates" to (m.numbering?.platesUsed ?: 0)
        "targetLikelyOnAnotherFloor" to m.targetLikelyOnAnotherFloor
        "sighting" to (m.sighting?.source ?: "none")
        "frontiers" to m.frontiers.size
        "blacklisted" to m.blacklisted.size
        "graphNodes" to m.nodes.size
        "graphEdges" to m.edges.size
        "adviceDirections" to m.advice.size
        "adviceAgeSeconds" to m.adviceAgeMillis / 1000f
        "plannerGuesses" to m.plan.size
        "captures" to captures
        "signsFramed" to framed.size
        "signsRead" to read.size
    }

    // ------------------------------------------------------------------ helpers

    private fun fateColor(fate: String): Color = when (fate) {
        "seen" -> Color(70, 220, 110)
        "too-small" -> Color(240, 200, 60)
        "not-detected", "ocr-failed", "unparsed" -> Color(240, 80, 70)
        "occluded" -> Color(180, 110, 240)
        else -> Color(160, 160, 170)
    }

    private fun kindColor(kind: String): FloatArray = when (kind) {
        "ROOM" -> floatArrayOf(0.35f, 0.85f, 0.95f)
        "ROOM_RANGE" -> floatArrayOf(0.4f, 0.6f, 1f)
        "EXIT" -> floatArrayOf(0.3f, 0.9f, 0.4f)
        "DOOR" -> floatArrayOf(0.8f, 0.65f, 0.4f)
        "STAIRS" -> floatArrayOf(0.95f, 0.6f, 0.2f)
        "ELEVATOR" -> floatArrayOf(0.95f, 0.85f, 0.3f)
        else -> floatArrayOf(0.8f, 0.8f, 0.85f)
    }

    /** Older evidence is drawn fainter; hints are dropped after 30 s. */
    private fun fade(ageMillis: Long) = (1f - ageMillis / 40_000f).coerceIn(0.25f, 1f)

    private fun sphere(p: Vec2, z: Float, size: Float, c: FloatArray, alpha: Float) = obj {
        "pose" to pose(p.x, p.z, z)
        "size" to obj { "x" to size; "y" to size; "z" to size }
        "color" to color(c[0], c[1], c[2], alpha)
    }

    private fun arrow(from: Vec2, direction: Vec2, z: Float, length: Float, c: FloatArray, alpha: Float) = obj {
        val yaw = atan2(direction.z, direction.x)
        "pose" to obj {
            "position" to vec(from.x, from.z, z)
            "orientation" to obj { "x" to 0; "y" to 0; "z" to sin(yaw / 2f); "w" to cos(yaw / 2f) }
        }
        "shaft_length" to length
        "shaft_diameter" to 0.04f
        "head_length" to 0.18f
        "head_diameter" to 0.12f
        "color" to color(c[0], c[1], c[2], alpha)
    }

    private fun text(p: Vec2, z: Float, words: String, alpha: Float) = obj {
        "pose" to pose(p.x, p.z, z)
        "billboard" to true
        "font_size" to 20
        "scale_invariant" to true
        "color" to color(1f, 1f, 1f, alpha)
        "text" to words
    }

    private fun logMessage(t: Long, level: Int, message: String) = json {
        "timestamp" to time(t)
        "level" to level
        "message" to message
        "name" to "perception"
        "file" to ""
        "line" to 0
    }

    private fun JsonObject.emptyPrimitives(vararg names: String) {
        for (name in names) name to arr {}
    }

    private fun time(nanos: Long) = obj {
        "sec" to nanos / 1_000_000_000
        "nsec" to nanos % 1_000_000_000
    }

    private fun vec(x: Float, y: Float, z: Float) = obj { "x" to x; "y" to y; "z" to z }

    private fun pose(x: Float, y: Float, z: Float) = obj {
        "position" to vec(x, y, z)
        "orientation" to obj { "x" to 0; "y" to 0; "z" to 0; "w" to 1 }
    }

    private fun color(r: Float, g: Float, b: Float, a: Float) = obj { "r" to r; "g" to g; "b" to b; "a" to a }

    private companion object {
        const val IMAGE_WIDTH = 480
        const val IMAGE_HEIGHT = 640
        const val MEMORY_PERIOD = 0.5
        const val MAX_PLACEMENTS = 400
        const val RAY_METERS = 3f

        val FRONTIERS_SCHEMA = """
            {"title":"navassist.Frontiers","type":"object","properties":{
              "timestamp":{"type":"object","properties":{"sec":{"type":"integer"},"nsec":{"type":"integer"}}},
              "frontiers":{"type":"array","items":{"type":"object","properties":{
                "id":{"type":"string"},"selected":{"type":"boolean"},"x":{"type":"number"},"z":{"type":"number"},
                "cells":{"type":"integer"},"score":{"type":"number"},"informationGain":{"type":"number"},
                "semantic":{"type":"number"},"distanceMeters":{"type":"number"},"revisit":{"type":"number"},
                "risk":{"type":"number"},"advice":{"type":"number"},"plan":{"type":"number"}}}}}}
        """.trimIndent()

        val STRATEGY_SCHEMA = """
            {"title":"navassist.Strategy","type":"object","properties":{
              "timestamp":{"type":"object","properties":{"sec":{"type":"integer"},"nsec":{"type":"integer"}}},
              "skill":{"type":"string"},"target":{"type":"string"},"purpose":{"type":"string"},
              "outcomes":{"type":"integer"},"rejections":{"type":"integer"},"landmarks":{"type":"integer"},"events":{"type":"integer"},
              "hypotheses":{"type":"array","items":{"type":"object","properties":{
                "id":{"type":"string"},"family":{"type":"string"},"proposition":{"type":"string"},"status":{"type":"string"},
                "belief":{"type":"number"},"support":{"type":"integer"},"against":{"type":"integer"}}}}}}
        """.trimIndent()

        val SUMMARY_SCHEMA = """
            {"title":"navassist.MemorySummary","type":"object","properties":{
              "timestamp":{"type":"object","properties":{"sec":{"type":"integer"},"nsec":{"type":"integer"}}},
              "signsHeld":{"type":"integer"},"platesRead":{"type":"integer"},"numberingConfidence":{"type":"number"},
              "numberingPlates":{"type":"integer"},"targetLikelyOnAnotherFloor":{"type":"boolean"},
              "sighting":{"type":"string"},"frontiers":{"type":"integer"},"blacklisted":{"type":"integer"},
              "graphNodes":{"type":"integer"},"graphEdges":{"type":"integer"},"adviceDirections":{"type":"integer"},
              "adviceAgeSeconds":{"type":"number"},"plannerGuesses":{"type":"integer"},"captures":{"type":"integer"},
              "signsFramed":{"type":"integer"},"signsRead":{"type":"integer"}}}
        """.trimIndent()
    }
}

private operator fun Pair<Float, Float>.component1() = first
