package expo.modules.navigationnative.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.navassist.navcore.NavigationEngine
import com.navassist.navcore.geometry.Vec2
import com.navassist.navcore.mapping.CellState
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the occupancy grid to a small PNG for the debug UI.
 *
 * The grid itself never crosses the bridge - 14 400 cells several times a second is exactly the
 * kind of traffic the architecture forbids. A picture of it is a few kilobytes, is produced only
 * when debug mode is on, and is pulled on demand rather than pushed with every snapshot.
 *
 * Drawing happens on the engine thread (see NavigationRuntime.renderMap) because it reads the
 * live grid, path and frontier state while the engine may be mid-update. Encoding does not; see
 * [PendingImage].
 */
object OccupancyMapRenderer {

    /** Pixels per grid cell. 3 keeps 120x120 cells legible without smoothing artefacts. */
    private const val CELL_PIXELS = 3

    private val unknownColor = Color.rgb(24, 24, 30)
    private val freeColor = Color.rgb(64, 132, 96)
    private val occupiedColor = Color.rgb(214, 90, 74)
    private val frontierColor = Color.rgb(240, 200, 90)
    private val pathColor = Color.rgb(90, 160, 240)
    private val goalColor = Color.rgb(240, 240, 255)
    private val userColor = Color.rgb(255, 255, 255)

    data class RenderedMap(
        val base64: String,
        val width: Int,
        val height: Int,
        val resolutionMeters: Float,
        val sizeMeters: Float,
    )

    fun render(engine: NavigationEngine): PendingImage<RenderedMap> {
        val grid = engine.occupancyGrid()
        val cells = grid.cells
        val size = cells * CELL_PIXELS

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        // The grid is drawn with +Z (forward) UP the screen, so the picture matches what the user
        // is facing rather than being mirrored vertically like raw array order would give.
        for (gz in 0 until cells) {
            for (gx in 0 until cells) {
                paint.color = when (grid.stateAt(gx, gz)) {
                    CellState.FREE -> freeColor
                    CellState.OCCUPIED -> occupiedColor
                    CellState.UNKNOWN -> unknownColor
                }
                val left = (gx * CELL_PIXELS).toFloat()
                val top = ((cells - 1 - gz) * CELL_PIXELS).toFloat()
                canvas.drawRect(left, top, left + CELL_PIXELS, top + CELL_PIXELS, paint)
            }
        }

        fun toPixel(world: Vec2): Pair<Float, Float> {
            val cell = grid.worldToGrid(world)
            return (cell.gx * CELL_PIXELS + CELL_PIXELS / 2f) to
                ((cells - 1 - cell.gz) * CELL_PIXELS + CELL_PIXELS / 2f)
        }

        // Frontiers: where the engine believes unexplored space begins.
        paint.color = frontierColor
        for (frontier in engine.frontiers()) {
            val (x, y) = toPixel(frontier.centroid)
            canvas.drawCircle(x, y, CELL_PIXELS * 1.6f, paint)
        }

        // Planned path.
        paint.color = pathColor
        paint.strokeWidth = CELL_PIXELS * 1.2f
        val path = engine.currentPathWorld()
        for (i in 0 until path.size - 1) {
            val (x1, y1) = toPixel(path[i])
            val (x2, y2) = toPixel(path[i + 1])
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        engine.currentGoalWorld()?.let { goal ->
            paint.color = goalColor
            val (x, y) = toPixel(goal)
            canvas.drawCircle(x, y, CELL_PIXELS * 1.4f, paint)
        }

        // The user, with a stub showing which way they are facing.
        val pose = engine.currentPose()
        val (ux, uy) = toPixel(pose.position2D)
        paint.color = userColor
        canvas.drawCircle(ux, uy, CELL_PIXELS * 1.8f, paint)
        paint.strokeWidth = CELL_PIXELS * 0.9f
        val heading = CELL_PIXELS * 6f
        canvas.drawLine(
            ux,
            uy,
            ux + sin(pose.yawRadians) * heading,
            // Screen y grows downwards while world z grows up the picture, hence the negation.
            uy - cos(pose.yawRadians) * heading,
            paint,
        )

        val resolution = grid.resolution
        val sizeMeters = grid.sizeMeters
        return PendingImage(bitmap) { base64 ->
            RenderedMap(
                base64 = base64,
                width = size,
                height = size,
                resolutionMeters = resolution,
                sizeMeters = sizeMeters,
            )
        }
    }
}
