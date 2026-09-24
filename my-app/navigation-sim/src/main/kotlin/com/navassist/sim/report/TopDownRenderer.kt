package com.navassist.sim.report

import com.navassist.navcore.mapping.CellState
import com.navassist.navcore.state.NavigationCommand
import com.navassist.sim.SimResult
import com.navassist.sim.Simulation
import com.navassist.sim.world.Material
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/**
 * A top-down picture of one run: the real building, what the engine believed at the end (its
 * occupancy grid, overlaid), and the path the virtual user walked, coloured by the instruction
 * they were following. Written for failing scenarios, so a red test comes with a picture.
 */
object TopDownRenderer {

    private const val PIXELS_PER_METER = 40f
    private const val MARGIN_METERS = 1f
    private const val HEADER_PIXELS = 64

    private val background = Color(18, 18, 24)
    private val floorColor = Color(58, 58, 66)
    private val wallColor = Color(150, 150, 160)
    private val glassColor = Color(90, 200, 230)
    private val gridFree = Color(64, 180, 110, 90)
    private val gridOccupied = Color(230, 80, 70, 150)
    private val signColor = Color(240, 200, 90)
    private val textColor = Color(220, 220, 230)

    private fun commandColor(command: NavigationCommand): Color = when (command) {
        NavigationCommand.STRAIGHT -> Color(90, 210, 120)
        NavigationCommand.TURN_LEFT, NavigationCommand.TURN_RIGHT -> Color(90, 160, 240)
        NavigationCommand.STOP -> Color(230, 80, 70)
        NavigationCommand.SCAN -> Color(240, 200, 90)
        NavigationCommand.ARRIVED -> Color(255, 255, 255)
    }

    fun render(sim: Simulation, result: SimResult, title: String, file: File) {
        val bounds = sim.scene.bounds()
        // World x/z -> plan x/y (y = -z).
        val minX = bounds[0] - MARGIN_METERS
        val maxX = bounds[2] + MARGIN_METERS
        val minY = -bounds[3] - MARGIN_METERS
        val maxY = -bounds[1] + MARGIN_METERS
        val width = ((maxX - minX) * PIXELS_PER_METER).toInt().coerceAtLeast(360)
        val height = ((maxY - minY) * PIXELS_PER_METER).toInt() + HEADER_PIXELS

        fun px(x: Float) = (x - minX) * PIXELS_PER_METER
        fun py(y: Float) = HEADER_PIXELS + (maxY - y) * PIXELS_PER_METER

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = background
        g.fillRect(0, 0, width, height)

        fun rect(x0: Float, y0: Float, x1: Float, y1: Float) =
            Rectangle2D.Float(px(x0), py(y1), (x1 - x0) * PIXELS_PER_METER, (y1 - y0) * PIXELS_PER_METER)

        // The building. Floors below walking level (a stairwell's bottom) are not drawn: holes read as holes.
        for (box in sim.scene.floors.filter { kotlin.math.abs(it.maxY) < 0.05f }) {
            g.color = floorColor
            g.fill(rect(box.minX, -box.maxZ, box.maxX, -box.minZ))
        }

        // What the engine believed at the end.
        val grid = sim.engine.occupancyGrid()
        val half = grid.resolution / 2f
        for (gz in 0 until grid.cells) {
            for (gx in 0 until grid.cells) {
                val state = grid.stateAt(gx, gz)
                if (state == CellState.UNKNOWN) continue
                val (x, y) = sim.canonicalToPlan(grid.gridToWorld(gx, gz)) ?: continue
                g.color = if (state == CellState.FREE) gridFree else gridOccupied
                g.fill(rect(x - half, y - half, x + half, y + half))
            }
        }

        for (box in sim.scene.obstacles) {
            val shape = rect(box.minX, -box.maxZ, box.maxX, -box.minZ)
            if (box.material == Material.GLASS) {
                g.color = glassColor
                g.stroke = BasicStroke(2f)
                g.draw(shape)
            } else {
                g.color = wallColor
                g.fill(shape)
            }
        }

        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
        for (sign in sim.scene.signs) {
            g.color = signColor
            val x = px(sign.x)
            val y = py(-sign.z)
            g.fill(Ellipse2D.Float(x - 3f, y - 3f, 6f, 6f))
            val label = sign.text.ifEmpty { sign.kind.name.lowercase() }
            g.drawString(label, x + 5f, y + 4f)
        }

        // The walk.
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val trace = sim.trace
        for (i in 1 until trace.size) {
            val a = trace[i - 1].body
            val b = trace[i].body
            if (a.x == b.x && a.y == b.y) continue
            g.color = commandColor(trace[i].acting)
            g.draw(Line2D.Float(px(a.x), py(a.y), px(b.x), py(b.y)))
        }
        // Collisions.
        g.color = Color(255, 60, 60)
        g.stroke = BasicStroke(3f)
        for (i in 1 until trace.size) {
            if (trace[i].touching && !trace[i - 1].touching) {
                val x = px(trace[i].body.x)
                val y = py(trace[i].body.y)
                g.draw(Line2D.Float(x - 7f, y - 7f, x + 7f, y + 7f))
                g.draw(Line2D.Float(x - 7f, y + 7f, x + 7f, y - 7f))
            }
        }
        // Where they ended up, and which way they faced.
        val end = result.finalBody
        g.color = Color.WHITE
        g.fill(Ellipse2D.Float(px(end.x) - 6f, py(end.y) - 6f, 12f, 12f))
        g.draw(Line2D.Float(px(end.x), py(end.y), px(end.x + sin(end.heading) * 0.7f), py(end.y + cos(end.heading) * 0.7f)))

        g.color = textColor
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        g.drawString(title, 8, 18)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        val summary = result.summary()
        val split = summary.indexOf(", scan")
        g.drawString(if (split > 0) summary.substring(0, split) else summary, 8, 36)
        if (split > 0) g.drawString(summary.substring(split + 2), 8, 52)
        g.dispose()

        file.parentFile?.mkdirs()
        ImageIO.write(image, "png", file)
    }
}
