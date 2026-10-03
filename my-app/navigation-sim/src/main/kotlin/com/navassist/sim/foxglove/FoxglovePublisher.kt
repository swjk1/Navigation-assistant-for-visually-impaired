package com.navassist.sim.foxglove

import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.state.NavigationSnapshot
import com.navassist.sim.Simulation
import com.navassist.sim.TraceSample
import com.navassist.sim.world.Box
import com.navassist.sim.world.Material
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Where Foxglove messages go: an MCAP file, or a live WebSocket. */
interface FoxgloveSink : AutoCloseable {
    /** Declares a topic; returns the channel id to [publish] on. */
    fun channel(topic: String, schemaName: String, jsonSchema: String): Int

    fun publish(channel: Int, timeNanos: Long, jsonPayload: String)

    override fun close() {}
}

/**
 * Turns a running [Simulation] into Foxglove messages - the building as it really is, and the
 * engine's belief about it, in one 3D view.
 *
 * Frames:
 *   plan    the floor plan: x east, y north, z up (metres). The building and the user live here.
 *   engine  the engine's canonical frame laid flat (x right, y forward, z up), placed in `plan`
 *           where the engine fixed it. Its grid, path, frontiers and depth points live here, so any
 *           disagreement with the building is visible directly.
 *
 * Topics (all JSON, Foxglove well-known schemas unless noted):
 *   /world               SceneUpdate   walls, floors, glass, signs
 *   /user                SceneUpdate   body, phone direction, what they are being told
 *   /tf                  FrameTransform plan -> engine
 *   /engine/grid         Grid          occupancy, int8: -1 unknown, 0 free, 100 occupied ("map" colours)
 *   /engine/plan         SceneUpdate   path, frontiers, goal
 *   /engine/points       PointCloud    the depth returns the engine integrated
 *   /camera/depth        RawImage      the simulated depth image, 16UC1, sensor orientation
 *   /engine/status       navassist.EngineStatus  status, instruction, confidences - for Plot panels
 *   /log                 Log           instruction changes, collisions, arrival
 */
class FoxglovePublisher(private val sink: FoxgloveSink) {

    private val world = sink.channel("/world", "foxglove.SceneUpdate", schema("SceneUpdate"))
    private val user = sink.channel("/user", "foxglove.SceneUpdate", schema("SceneUpdate"))
    private val tf = sink.channel("/tf", "foxglove.FrameTransform", schema("FrameTransform"))
    private val grid = sink.channel("/engine/grid", "foxglove.Grid", schema("Grid"))
    private val plan = sink.channel("/engine/plan", "foxglove.SceneUpdate", schema("SceneUpdate"))
    private val points = sink.channel("/engine/points", "foxglove.PointCloud", schema("PointCloud"))
    private val depth = sink.channel("/camera/depth", "foxglove.RawImage", schema("RawImage"))
    private val status = sink.channel("/engine/status", "navassist.EngineStatus", STATUS_SCHEMA)
    private val log = sink.channel("/log", "foxglove.Log", schema("Log"))

    private var lastWorldAt = -1.0
    private var lastGridAt = -1.0
    private var lastSensorAt = -1.0
    private var lastUserAt = -1.0
    private var lastCommand: String? = null
    private var lastTouching = false

    /** Call after every simulation step. */
    fun onFrame(sim: Simulation, sample: TraceSample, snapshot: NavigationSnapshot) {
        val t = timeOf(sample.seconds)
        if (lastWorldAt < 0 || sample.seconds - lastWorldAt >= WORLD_PERIOD) {
            lastWorldAt = sample.seconds
            sink.publish(world, t, worldScene(sim, t))
        }
        if (sample.seconds - lastUserAt >= USER_PERIOD) {
            lastUserAt = sample.seconds
            sink.publish(user, t, userScene(sim, sample, t))
            frameTransform(sim, t)?.let { sink.publish(tf, t, it) }
            sink.publish(plan, t, planScene(sim, t))
        }
        if (sample.seconds - lastGridAt >= GRID_PERIOD) {
            lastGridAt = sample.seconds
            sink.publish(grid, t, gridMessage(sim, t))
        }
        if (sample.seconds - lastSensorAt >= SENSOR_PERIOD) {
            lastSensorAt = sample.seconds
            pointCloud(sim, t)?.let { sink.publish(points, t, it) }
            depthImage(sim, t)?.let { sink.publish(depth, t, it) }
        }
        sink.publish(status, t, statusMessage(sample, snapshot, t))

        val said = "${snapshot.command}${snapshot.stopReason?.let { " ($it)" } ?: ""}"
        if (said != lastCommand) {
            lastCommand = said
            sink.publish(log, t, logMessage(t, 2, "${snapshot.status}: $said"))
        }
        if (sample.touching && !lastTouching) {
            sink.publish(log, t, logMessage(t, 4, "COLLISION at (%.2f, %.2f)".format(sample.body.x, sample.body.y)))
        }
        lastTouching = sample.touching
    }

    // ------------------------------------------------------------------ messages

    private fun worldScene(sim: Simulation, t: Long): String = json {
        "deletions" to arr {}
        "entities" to arr {
            add(obj {
                "timestamp" to time(t)
                "frame_id" to "plan"
                "id" to "building"
                "lifetime" to time(0)
                "frame_locked" to true
                "metadata" to arr {}
                "cubes" to arr { for (box in sim.scene.boxes) add(cube(box, sim.scene.isAbove(box))) }
                "texts" to arr {
                    for (sign in sim.scene.signs) add(obj {
                        "pose" to pose(sign.x, -sign.z, sign.y + 0.25f)
                        "billboard" to true
                        "font_size" to 14
                        "scale_invariant" to true
                        "color" to color(1f, 0.85f, 0.35f, 1f)
                        "text" to sign.text.ifEmpty { sign.kind.name.lowercase() }
                    })
                }
                emptyPrimitives("arrows", "spheres", "cylinders", "lines", "triangles", "models")
            })
        }
    }

    /** [above]: on a storey over the one walked, drawn faint so the 3D view can see down to the user. */
    private fun cube(box: Box, above: Boolean) = obj {
        // World is AR-style (y up, z = -north); plan is x east, y north, z up.
        "pose" to pose((box.minX + box.maxX) / 2f, -(box.minZ + box.maxZ) / 2f, (box.minY + box.maxY) / 2f)
        "size" to obj {
            "x" to (box.maxX - box.minX)
            "y" to (box.maxZ - box.minZ)
            "z" to (box.maxY - box.minY)
        }
        "color" to when {
            above -> color(0.62f, 0.62f, 0.68f, 0.08f)
            box.isFloor && box.maxY < -0.5f -> color(0.12f, 0.12f, 0.14f, 1f)
            box.isFloor -> color(0.28f, 0.28f, 0.32f, 1f)
            box.material == Material.GLASS -> color(0.35f, 0.8f, 0.95f, 0.3f)
            box.material == Material.DARK -> color(0.08f, 0.08f, 0.08f, 1f)
            box.maxY < 1.2f -> color(0.9f, 0.55f, 0.25f, 1f)
            else -> color(0.62f, 0.62f, 0.68f, 1f)
        }
    }

    private fun userScene(sim: Simulation, sample: TraceSample, t: Long): String = json {
        val hold = sim.settings.user.hold
        val body = sample.body
        val phoneX = body.x + sin(body.heading) * hold.reachMeters
        val phoneY = body.y + cos(body.heading) * hold.reachMeters
        "deletions" to arr {}
        "entities" to arr {
            add(obj {
                "timestamp" to time(t)
                "frame_id" to "plan"
                "id" to "user"
                "lifetime" to time(0)
                "frame_locked" to false
                "metadata" to arr {}
                "cylinders" to arr {
                    add(obj {
                        "pose" to pose(body.x, body.y, 0.85f)
                        "size" to obj { "x" to 0.5f; "y" to 0.5f; "z" to 1.7f }
                        "bottom_scale" to 1f
                        "top_scale" to 1f
                        "color" to if (sample.touching) color(1f, 0.2f, 0.2f, 0.8f) else color(0.3f, 0.55f, 1f, 0.6f)
                    })
                }
                "arrows" to arr {
                    // The camera's viewing direction: yaw and downward pitch.
                    val yaw = (PI / 2 - sample.phoneYaw).toFloat()
                    add(obj {
                        "pose" to obj {
                            "position" to vec(phoneX, phoneY, hold.heightMeters)
                            "orientation" to quaternionYawPitch(yaw, sim.user.phonePitch)
                        }
                        "shaft_length" to 0.6f
                        "shaft_diameter" to 0.04f
                        "head_length" to 0.15f
                        "head_diameter" to 0.1f
                        "color" to color(1f, 1f, 1f, 1f)
                    })
                }
                "texts" to arr {
                    add(obj {
                        "pose" to pose(body.x, body.y, 2.1f)
                        "billboard" to true
                        "font_size" to 16
                        "scale_invariant" to true
                        "color" to color(1f, 1f, 1f, 1f)
                        "text" to "${sample.command}${sample.stopReason?.let { "\n$it" } ?: ""}"
                    })
                }
                emptyPrimitives("cubes", "spheres", "lines", "triangles", "models")
            })
        }
    }

    private fun frameTransform(sim: Simulation, t: Long): String? {
        val (ox, oy, yaw0) = sim.canonicalOriginPlan() ?: return null
        // Engine x (canonical right) is plan (cos yaw0, -sin yaw0): a rotation of -yaw0 about z.
        val half = -yaw0 / 2f
        return json {
            "timestamp" to time(t)
            "parent_frame_id" to "plan"
            "child_frame_id" to "engine"
            "translation" to vec(ox, oy, sim.settings.user.hold.heightMeters)
            "rotation" to obj { "x" to 0; "y" to 0; "z" to sin(half); "w" to cos(half) }
        }
    }

    private fun gridMessage(sim: Simulation, t: Long): String {
        val g = sim.engine.occupancyGrid()
        val n = g.cells
        val data = ByteArray(n * n)
        for (gz in 0 until n) {
            for (gx in 0 until n) {
                data[gz * n + gx] = when (g.stateAt(gx, gz)) {
                    CellState.FREE -> 0
                    CellState.OCCUPIED -> 100
                    CellState.UNKNOWN -> -1
                }
            }
        }
        val floor = sim.engine.floorEstimate().floorY
        return json {
            "timestamp" to time(t)
            "frame_id" to "engine"
            // Engine frame: x = canonical x, y = canonical z, z = canonical y.
            "pose" to pose(g.originX, g.originZ, floor + 0.01f)
            "column_count" to n
            "cell_size" to obj { "x" to g.resolution; "y" to g.resolution }
            "row_stride" to n
            "cell_stride" to 1
            "fields" to arr { add(obj { "name" to "occupancy"; "offset" to 0; "type" to 2 }) }
            "data" bytes data
        }
    }

    private fun planScene(sim: Simulation, t: Long): String = json {
        val floor = sim.engine.floorEstimate().floorY
        "deletions" to arr {}
        "entities" to arr {
            add(obj {
                "timestamp" to time(t)
                "frame_id" to "engine"
                "id" to "plan"
                "lifetime" to time(0)
                "frame_locked" to true
                "metadata" to arr {}
                "lines" to arr {
                    val path = sim.engine.currentPathWorld()
                    if (path.size >= 2) add(obj {
                        "type" to 0
                        "pose" to pose(0f, 0f, 0f)
                        "thickness" to 0.06f
                        "scale_invariant" to false
                        "points" to arr { for (p in path) add(vec(p.x, p.z, floor + 0.05f)) }
                        "color" to color(0.35f, 0.63f, 0.94f, 1f)
                        "colors" to arr {}
                        "indices" to arr {}
                    })
                }
                "spheres" to arr {
                    for (f in sim.engine.frontiers()) add(obj {
                        "pose" to pose(f.centroid.x, f.centroid.z, floor + 0.1f)
                        "size" to obj { "x" to 0.2f; "y" to 0.2f; "z" to 0.2f }
                        "color" to color(0.94f, 0.78f, 0.35f, 1f)
                    })
                    sim.engine.currentGoalWorld()?.let { goal ->
                        add(obj {
                            "pose" to pose(goal.x, goal.z, floor + 0.15f)
                            "size" to obj { "x" to 0.3f; "y" to 0.3f; "z" to 0.3f }
                            "color" to color(1f, 1f, 1f, 1f)
                        })
                    }
                }
                emptyPrimitives("arrows", "cubes", "cylinders", "triangles", "texts", "models")
            })
        }
    }

    private fun pointCloud(sim: Simulation, t: Long): String? {
        val cloud = sim.lastFrame?.points ?: return null
        if (cloud.count == 0) return null
        val buffer = ByteBuffer.allocate(cloud.count * 12).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until cloud.count) {
            buffer.putFloat(cloud.x(i))
            buffer.putFloat(cloud.z(i))
            buffer.putFloat(cloud.y(i))
        }
        return json {
            "timestamp" to time(t)
            "frame_id" to "engine"
            "pose" to pose(0f, 0f, 0f)
            "point_stride" to 12
            "fields" to arr {
                add(obj { "name" to "x"; "offset" to 0; "type" to 7 })
                add(obj { "name" to "y"; "offset" to 4; "type" to 7 })
                add(obj { "name" to "z"; "offset" to 8; "type" to 7 })
            }
            "data" bytes buffer.array()
        }
    }

    private fun depthImage(sim: Simulation, t: Long): String? {
        val image = sim.deliveredDepth ?: return null
        val buffer = ByteBuffer.allocate(image.width * image.height * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (value in image.depthMm) buffer.putShort(value)
        return json {
            "timestamp" to time(t)
            "frame_id" to "camera"
            "width" to image.width
            "height" to image.height
            "encoding" to "16UC1"
            "step" to image.width * 2
            "data" bytes buffer.array()
        }
    }

    private fun statusMessage(sample: TraceSample, snapshot: NavigationSnapshot, t: Long): String = json {
        val d = snapshot.debug
        "timestamp" to time(t)
        "status" to snapshot.status.name
        "command" to snapshot.command.name
        "stopReason" to snapshot.stopReason?.name
        "acting" to sample.acting.name
        "headingErrorDegrees" to (snapshot.headingErrorDegrees ?: 0f)
        "mapConfidence" to snapshot.mapConfidence
        "scanProgress" to (d?.scanProgress ?: 0f)
        "floorConfidence" to (d?.floorConfidence ?: 0f)
        "frontiers" to (d?.frontierCount ?: 0)
        "pathLength" to (d?.pathLength ?: 0)
        "depthPoints" to (d?.depthPointsLastFrame ?: 0)
        "userX" to sample.body.x
        "userY" to sample.body.y
    }

    private fun logMessage(t: Long, level: Int, message: String) = json {
        "timestamp" to time(t)
        "level" to level
        "message" to message
        "name" to "engine"
        "file" to ""
        "line" to 0
    }

    // ------------------------------------------------------------------ helpers

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

    /** Rotation taking +x to the direction with this yaw (CCW from +x) and downward pitch. */
    private fun quaternionYawPitch(yaw: Float, pitchDown: Float) = obj {
        val cy = cos(yaw / 2f)
        val sy = sin(yaw / 2f)
        val cp = cos(pitchDown / 2f)
        val sp = sin(pitchDown / 2f)
        "x" to -sy * sp
        "y" to cy * sp
        "z" to sy * cp
        "w" to cy * cp
    }

    companion object {
        /** Sim time 0 is shown as 2026-01-01T00:00:00Z. */
        const val BASE_NANOS = 1_767_225_600_000_000_000L

        fun timeOf(seconds: Double): Long = BASE_NANOS + (seconds * 1e9).toLong()

        private const val WORLD_PERIOD = 2.0
        private const val USER_PERIOD = 0.1
        private const val GRID_PERIOD = 0.5
        private const val SENSOR_PERIOD = 1.0 / 3.0

        fun schema(name: String): String =
            FoxglovePublisher::class.java.getResource("/foxglove/$name.json")?.readText()
                ?: error("missing bundled schema foxglove/$name.json")

        val STATUS_SCHEMA = """
            {"title":"navassist.EngineStatus","type":"object","properties":{
              "timestamp":{"type":"object","properties":{"sec":{"type":"integer"},"nsec":{"type":"integer"}}},
              "status":{"type":"string"},"command":{"type":"string"},"stopReason":{"type":["string","null"]},
              "acting":{"type":"string"},"headingErrorDegrees":{"type":"number"},"mapConfidence":{"type":"number"},
              "scanProgress":{"type":"number"},"floorConfidence":{"type":"number"},"frontiers":{"type":"integer"},
              "pathLength":{"type":"integer"},"depthPoints":{"type":"integer"},"userX":{"type":"number"},
              "userY":{"type":"number"}}}
        """.trimIndent()
    }
}

/** Writes Foxglove messages to an MCAP file. */
class McapSink(private val writer: McapWriter) : FoxgloveSink {
    private val schemas = HashMap<String, Int>()

    override fun channel(topic: String, schemaName: String, jsonSchema: String): Int {
        val schemaId = schemas.getOrPut(schemaName) {
            writer.schema(schemaName, "jsonschema", jsonSchema.toByteArray(Charsets.UTF_8))
        }
        return writer.channel(schemaId, topic, "json")
    }

    override fun publish(channel: Int, timeNanos: Long, jsonPayload: String) =
        writer.message(channel, timeNanos, jsonPayload.toByteArray(Charsets.UTF_8))

    override fun close() = writer.close()
}
