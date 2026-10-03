package com.navassist.sim.webots

import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.state.NavigationSnapshot
import com.navassist.sim.ScenarioRun
import com.navassist.sim.Simulation
import com.navassist.sim.TraceSample
import com.navassist.sim.foxglove.arr
import com.navassist.sim.foxglove.json
import com.navassist.sim.foxglove.obj
import com.navassist.sim.world.Box
import com.navassist.sim.world.Material
import com.navassist.sim.sensor.DepthCamera
import com.navassist.sim.world.SignKind
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exports a simulator run as a Webots project, to WATCH it: the building as solid geometry, the
 * user walking it, what they were being told, the path they took, and the engine's occupancy map
 * drawn on the floor as it grew.
 *
 * Webots is a viewer here, not the simulator: physics, depth and the engine all ran in this JVM,
 * and a small supervisor controller (resources/webots/sim_replay.py) replays the recording. The
 * floor-plan frame (x east, y north, z up) is Webots' default ENU frame, so coordinates carry over.
 *
 *   <dir>/worlds/scene.wbt
 *   <dir>/controllers/sim_replay/sim_replay.py
 *   <dir>/controllers/sim_replay/trace.json
 */
object WebotsExporter {

    /** Collects what the replay needs while the run happens. */
    class Recorder(private val framePeriod: Double = 0.1, private val gridPeriod: Double = 1.0) {
        internal val frames = ArrayList<String>()
        internal val grids = ArrayList<String>()
        private var lastFrameAt = -1.0
        private var lastGridAt = -1.0

        fun onFrame(sim: Simulation, sample: TraceSample, snapshot: NavigationSnapshot) {
            if (lastFrameAt < 0 || sample.seconds - lastFrameAt >= framePeriod) {
                lastFrameAt = sample.seconds
                frames += arr {
                    add(round(sample.seconds))
                    add(round(sample.body.x)); add(round(sample.body.y)); add(round(sample.body.heading))
                    add(round(sample.phoneYaw)); add(round(sim.user.phonePitch))
                    add(snapshot.command.name)
                    add(snapshot.stopReason?.name ?: "")
                    add(snapshot.status.name)
                    add(if (sample.touching) 1 else 0)
                }.let { json { "f" to it } }.removePrefix("{\"f\":").removeSuffix("}")
            }
            val origin = sim.canonicalOriginPlan() ?: return
            if (lastGridAt < 0 || sample.seconds - lastGridAt >= gridPeriod) {
                lastGridAt = sample.seconds
                val grid = sim.engine.occupancyGrid()
                val cells = StringBuilder(grid.cells * grid.cells)
                for (gz in 0 until grid.cells) for (gx in 0 until grid.cells) {
                    cells.append(
                        when (grid.stateAt(gx, gz)) {
                            CellState.FREE -> '.'
                            CellState.OCCUPIED -> '#'
                            CellState.UNKNOWN -> '?'
                        },
                    )
                }
                grids += json {
                    "t" to round(sample.seconds)
                    // Canonical (x, z) -> plan: origin + right * x + forward * z, where the engine's
                    // forward is the phone's yaw when it fixed its frame.
                    "ox" to origin.first; "oy" to origin.second; "yaw" to origin.third
                    "x0" to grid.originX; "z0" to grid.originZ
                    "res" to grid.resolution; "n" to grid.cells
                    "cells" to cells.toString()
                }
            }
        }

        private fun round(v: Double) = Math.round(v * 1000.0) / 1000.0
        private fun round(v: Float) = Math.round(v * 1000.0) / 1000.0
    }

    fun write(directory: File, run: ScenarioRun, recorder: Recorder): File {
        val worlds = File(directory, "worlds").apply { mkdirs() }
        val controller = File(directory, "controllers/sim_replay").apply { mkdirs() }
        val world = File(worlds, "scene.wbt")
        world.writeText(worldFile(run))
        File(controller, "sim_replay.py").writeText(
            WebotsExporter::class.java.getResource("/webots/sim_replay.py")?.readText()
                ?: error("missing bundled webots/sim_replay.py"),
        )
        val title = "${run.scenario.name}  seed ${run.seed}  ${if (run.passed) "PASS" else "FAIL"}"
        File(controller, "trace.json").writeText(
            "{\"title\":${quote(title)},\"summary\":${quote(run.result.summary())}," +
                "\"failures\":${quote(run.failures.joinToString("; "))}," +
                "\"frames\":[${recorder.frames.joinToString(",")}]," +
                "\"grids\":[${recorder.grids.joinToString(",")}]}",
        )
        return world
    }

    // ------------------------------------------------------------------ world file

    private fun worldFile(run: ScenarioRun): String = buildString {
        val sim = run.simulation
        val scene = sim.scene
        val b = scene.bounds() // world x/z; plan y = -z
        val minX = b[0]; val maxX = b[2]; val minY = -b[3]; val maxY = -b[1]
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val span = maxOf(maxX - minX, maxY - minY)

        appendLine("#VRML_SIM R2025a utf8")
        appendLine()
        appendLine("WorldInfo {")
        appendLine("  info [ \"Navigation simulator replay: ${run.scenario.name}, seed ${run.seed}\" ]")
        appendLine("  title \"${run.scenario.name}\"")
        appendLine("  basicTimeStep 32")
        appendLine("}")
        // An overview from the south, looking north and down at the middle of the building.
        val eye = floatArrayOf(cx, minY - span * 0.35f, span * 0.75f + 3f)
        val (axis, angle) = lookAt(eye, floatArrayOf(cx, cy, 0f))
        appendLine("Viewpoint {")
        appendLine("  orientation ${axis[0]} ${axis[1]} ${axis[2]} $angle")
        appendLine("  position ${eye[0]} ${eye[1]} ${eye[2]}")
        appendLine("}")
        appendLine("Background { skyColor [ 0.12 0.12 0.15 ] luminosity 0.8 }")
        appendLine("DirectionalLight { direction 0.3 0.5 -1 intensity 1.4 castShadows FALSE }")
        appendLine("DirectionalLight { direction -0.4 -0.2 -1 intensity 0.6 castShadows FALSE }")

        for ((i, box) in scene.boxes.withIndex()) append(solid("box $i ${box.name}", box, scene.isAbove(box)))
        for ((i, sign) in scene.signs.withIndex()) {
            val alongX = abs(sign.normalX) > 0.5f
            val sx = if (alongX) 0.03f else sign.widthMeters
            val sy = if (alongX) sign.widthMeters else 0.03f
            val (r, g, bl) = when (sign.kind) {
                SignKind.EXIT_SIGN -> Triple(0.1f, 0.8f, 0.3f)
                SignKind.DOOR -> Triple(0.45f, 0.3f, 0.18f)
                SignKind.ROOM_PLATE, SignKind.ROOM_RANGE -> Triple(0.95f, 0.95f, 0.95f)
                else -> Triple(0.95f, 0.8f, 0.3f)
            }
            // Sit the plate just proud of the wall so it is not buried in it.
            val px = sign.x + sign.normalX * 0.03f
            val py = -sign.z - sign.normalZ * 0.03f
            val transparency = if (sign.y >= scene.ceilingHeight) ABOVE_TRANSPARENCY else 0f
            appendLine("Solid {")
            appendLine("  translation $px $py ${sign.y}")
            appendLine("  children [ Shape { appearance PBRAppearance { baseColor $r $g $bl transparency $transparency roughness 1 metalness 0 } geometry Box { size $sx $sy ${sign.heightMeters} } } ]")
            appendLine("  name \"sign $i ${sign.kind.name.lowercase()} ${sign.text}\"")
            appendLine("}")
        }

        val start = sim.settings.start
        val hold = sim.settings.user.hold
        appendLine("DEF USER Robot {")
        appendLine("  translation ${start.x} ${start.y} 0")
        appendLine("  rotation 0 0 1 ${PI / 2 - start.heading}")
        appendLine("  name \"user\"")
        appendLine("  controller \"sim_replay\"")
        appendLine("  supervisor TRUE")
        appendLine("  children [")
        appendLine("    Pose { translation 0 0 0.85 children [ Shape { appearance PBRAppearance { baseColor 0.3 0.55 1 transparency 0.25 roughness 1 metalness 0 } geometry Cylinder { height 1.7 radius 0.25 } } ] }")
        appendLine("    Pose { translation 0.12 0 1.6 children [ Shape { appearance PBRAppearance { baseColor 1 1 1 roughness 1 metalness 0 } geometry Sphere { radius 0.1 } } ] }")
        appendLine("    DEF PHONE Pose {")
        appendLine("      translation ${hold.reachMeters} 0 ${hold.heightMeters}")
        appendLine("      children [")
        appendLine("        Shape { appearance PBRAppearance { baseColor 0.1 0.1 0.1 roughness 1 metalness 0 } geometry Box { size 0.02 0.08 0.15 } }")
        appendLine("        Pose { translation 0.35 0 0 rotation 0 1 0 1.5708 children [ Shape { appearance PBRAppearance { baseColor 1 1 1 transparency 0.3 roughness 1 metalness 0 } geometry Cone { bottomRadius 0.12 height 0.6 } } ] }")
        // What the phone's camera sees, at half the simulated RGB resolution and the same field of
        // view, portrait when the phone is held portrait. Webots shows it as an overlay on the 3D
        // view (double-click it for its own window). The near plane sits past the pointing cone.
        val k = DepthCamera.DEFAULT_RGB
        val (across, along) = if (hold.portrait) k.height to k.width else k.width to k.height
        val focal = if (hold.portrait) k.fy else k.fx
        appendLine("        Camera { name \"phone camera\" width ${across / 2} height ${along / 2} fieldOfView ${2 * atan(across / 2f / focal)} near 0.7 }")
        appendLine("      ]")
        appendLine("    }")
        appendLine("  ]")
        appendLine("}")
    }

    /** Storeys above the one walked are drawn nearly clear, so the overview can see the run. */
    private const val ABOVE_TRANSPARENCY = 0.85f

    private fun solid(name: String, box: Box, above: Boolean): String {
        val px = (box.minX + box.maxX) / 2f
        val py = -(box.minZ + box.maxZ) / 2f
        val pz = (box.minY + box.maxY) / 2f
        val (r, g, b, transparency) = when {
            box.isFloor && box.maxY < -0.5f -> listOf(0.1f, 0.1f, 0.12f, 0f)
            box.isFloor -> listOf(0.3f, 0.3f, 0.34f, 0f)
            box.material == Material.GLASS -> listOf(0.4f, 0.85f, 1f, 0.7f)
            box.material == Material.DARK -> listOf(0.05f, 0.05f, 0.05f, 0f)
            box.maxY < 1.2f -> listOf(0.9f, 0.55f, 0.25f, 0f)
            else -> listOf(0.7f, 0.7f, 0.75f, 0f)
        }.let { if (above) it.take(3) + maxOf(it[3], ABOVE_TRANSPARENCY) else it }
        return "Solid {\n" +
            "  translation $px $py $pz\n" +
            "  children [ Shape { appearance PBRAppearance { baseColor $r $g $b transparency $transparency roughness 1 metalness 0 } " +
            "geometry Box { size ${box.maxX - box.minX} ${box.maxZ - box.minZ} ${box.maxY - box.minY} } } ]\n" +
            "  name ${quote(name)}\n" +
            "}\n"
    }

    /**
     * Axis-angle orientation for a Webots Viewpoint (which looks along its own +x, z up) placed at
     * [eye] looking at [target].
     */
    private fun lookAt(eye: FloatArray, target: FloatArray): Pair<FloatArray, Float> {
        val dx = target[0] - eye[0]
        val dy = target[1] - eye[1]
        val dz = target[2] - eye[2]
        val yaw = atan2(dy, dx)
        val pitch = atan2(-dz, hypot(dx, dy)) // positive looks down
        // q = qz(yaw) * qy(pitch)
        val cy = cos(yaw / 2); val sy = sin(yaw / 2)
        val cp = cos(pitch / 2); val sp = sin(pitch / 2)
        val w = cy * cp
        val x = -sy * sp
        val y = cy * sp
        val z = sy * cp
        val angle = 2 * acos(w.coerceIn(-1f, 1f))
        val s = sqrt(1 - w * w).coerceAtLeast(1e-6f)
        return floatArrayOf(x / s, y / s, z / s) to angle
    }

    private fun quote(text: String) = json { "s" to text }.removePrefix("{\"s\":").removeSuffix("}")
}
