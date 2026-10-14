package com.navassist.sim

import com.navassist.navcore.state.NavigationCommand
import com.navassist.sim.foxglove.FoxglovePublisher
import com.navassist.sim.foxglove.FoxgloveWebSocketSink
import com.navassist.sim.foxglove.InsightPublisher

/**
 * Runs one scenario in REAL time and streams it to Foxglove.
 *
 * By default the scripted user follows the engine. Publish a geometry_msgs/Twist-shaped JSON on
 * /cmd_vel (Foxglove's Teleop panel does this) and you drive instead: linear.x is walking speed,
 * angular.z turns left. Stop publishing for half a second and the script takes over again.
 */
object LiveRunner {

    private const val MANUAL_TIMEOUT_NANOS = 500_000_000L

    @Volatile private var manualForward = 0f
    @Volatile private var manualTurn = 0f
    @Volatile private var manualAt = 0L

    fun run(scenario: Scenario, seed: Long, port: Int) {
        val sink = FoxgloveWebSocketSink(port) { topic, payload ->
            if (!topic.endsWith("cmd_vel")) return@FoxgloveWebSocketSink
            val linear = payload["linear"] as? Map<*, *>
            val angular = payload["angular"] as? Map<*, *>
            manualForward = ((linear?.get("x") as? Double) ?: 0.0).toFloat().coerceIn(-1.5f, 1.5f)
            // ROS convention: positive angular.z turns LEFT; the simulator's positive turn is right.
            manualTurn = -((angular?.get("z") as? Double) ?: 0.0).toFloat().coerceIn(-2f, 2f)
            manualAt = System.nanoTime()
        }
        sink.use {
            // Channels first, so the very first client is told about all of them.
            val simulation = Simulation(scenario.settings(seed))
            val publisher = FoxglovePublisher(sink)
            simulation.listeners += publisher::onFrame
            InsightPublisher(sink).attach(simulation)

            println("Waiting for Foxglove to connect to ws://localhost:$port ...")
            while (!sink.connected) Thread.sleep(100)
            println("Connected. Running ${scenario.name} (seed $seed) in real time.")
            val start = System.nanoTime()
            while (simulation.seconds < simulation.settings.maxSeconds) {
                val manual = System.nanoTime() - manualAt < MANUAL_TIMEOUT_NANOS
                val dt = Simulation.FRAME_SECONDS.toFloat()
                val snapshot = simulation.step(if (manual) { user -> user.drive(manualForward, manualTurn, dt) } else null)
                sink.time(FoxglovePublisher.timeOf(simulation.seconds))
                if (simulation.user.fell || snapshot.command == NavigationCommand.ARRIVED) break
                val due = start + (simulation.seconds * 1e9).toLong()
                val wait = (due - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait)
            }
            val outcome = when {
                simulation.user.fell -> Outcome.FELL
                simulation.lastSnapshot.command == NavigationCommand.ARRIVED -> Outcome.ARRIVED
                else -> Outcome.TIMEOUT
            }
            println(simulation.result(outcome).summary())
            println("Leaving the stream up for 30 s so you can look around.")
            Thread.sleep(30_000)
        }
    }
}
