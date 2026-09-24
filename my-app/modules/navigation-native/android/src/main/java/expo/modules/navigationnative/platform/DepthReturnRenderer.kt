package expo.modules.navigationnative.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.navassist.navcore.NavigationConfig
import com.navassist.navcore.NavigationEngine
import com.navassist.navcore.geometry.DepthPointCloud
import com.navassist.navcore.geometry.Pose3D
import com.navassist.navcore.geometry.Vec2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the RAW depth returns of the most recent frame, top-down, in the same world window and
 * at the same scale as [OccupancyMapRenderer].
 *
 * This is deliberately the INPUT to mapping rather than its output. The occupancy grid shows what
 * the engine believes after accumulating evidence over many frames; this shows what the sensor
 * actually reported on one. When the two disagree the difference is the answer: an empty depth
 * view with a full grid is stale map, a full depth view with an empty grid is an integration or
 * floor-estimation bug, and both empty means no depth is arriving at all.
 *
 * Points are coloured by the classification [NavigationEngine] would give them, so the height
 * band that collapses 3D into a 2D grid is visible rather than implied: a return well above head
 * height and a return on the floor land on the SAME cell from above, and only the colour says
 * which is which.
 *
 * Drawing happens on the engine thread (see NavigationRuntime.renderDepth) because it reads the
 * grid window and the captured cloud while the engine may be mid-update. Encoding does not; see
 * [PendingImage].
 */
object DepthReturnRenderer {

    /** Pixels per grid cell. Matches OccupancyMapRenderer so the two views register exactly. */
    private const val CELL_PIXELS = 3

    /** Metres between the faint reference lines. */
    private const val GRID_LINE_METERS = 1f

    private val backgroundColor = Color.rgb(18, 18, 24)
    private val gridLineColor = Color.rgb(38, 38, 48)

    // Floor and obstacle deliberately match the occupancy map's free/obstacle colours, so a
    // point and the cell it produces read as the same thing across the two views.
    private val floorColor = Color.rgb(64, 132, 96)
    private val obstacleColor = Color.rgb(214, 90, 74)

    // The three classes the engine DISCARDS. Visible here precisely because they are invisible
    // in the grid: if the map is empty, it is usually because everything landed in one of these.
    private val overheadColor = Color.rgb(118, 108, 172)
    private val belowFloorColor = Color.rgb(200, 132, 64)
    private val outOfRangeColor = Color.rgb(72, 72, 84)

    private val userColor = Color.WHITE

    data class RenderedDepth(
        val base64: String,
        val width: Int,
        val height: Int,
        val resolutionMeters: Float,
        val sizeMeters: Float,
        /** Points in the frame, before any filtering. */
        val totalReturns: Int,
        val floorReturns: Int,
        val obstacleReturns: Int,
        val overheadReturns: Int,
        val belowFloorReturns: Int,
        val outOfRangeReturns: Int,
        /** Returns that fell outside the rendered window entirely. */
        val offWindowReturns: Int,
        val floorY: Float,
        val floorConfidence: Float,
        /** Age of the rendered frame when it was drawn. 0 when no frame has arrived. */
        val ageMillis: Long,
        val hasFrame: Boolean,
    )

    fun render(
        engine: NavigationEngine,
        config: NavigationConfig,
        cloud: DepthPointCloud?,
        pose: Pose3D,
        ageMillis: Long,
    ): PendingImage<RenderedDepth> {
        val grid = engine.occupancyGrid()
        val cells = grid.cells
        val size = cells * CELL_PIXELS

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        paint.color = backgroundColor
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        // Metre reference lines. Without them a scatter of dots gives no sense of scale, and the
        // whole point of this view is judging whether the ranges coming back are plausible.
        paint.color = gridLineColor
        paint.strokeWidth = 1f
        val stepPixels = GRID_LINE_METERS / grid.resolution * CELL_PIXELS
        var line = 0f
        while (line <= size) {
            canvas.drawLine(line, 0f, line, size.toFloat(), paint)
            canvas.drawLine(0f, line, size.toFloat(), line, paint)
            line += stepPixels
        }

        fun toPixel(world: Vec2): Pair<Float, Float> {
            val cell = grid.worldToGrid(world)
            return (cell.gx * CELL_PIXELS + CELL_PIXELS / 2f) to
                ((cells - 1 - cell.gz) * CELL_PIXELS + CELL_PIXELS / 2f)
        }

        val floor = engine.floorEstimate()
        val minHeight = config.minObstacleHeightMeters
        val maxHeight = config.maxObstacleHeightMeters
        val maxRangeSq = config.maxDepthMeters * config.maxDepthMeters
        val minRangeSq = config.minDepthMeters * config.minDepthMeters

        var floorCount = 0
        var obstacleCount = 0
        var overheadCount = 0
        var belowFloorCount = 0
        var outOfRangeCount = 0
        var offWindowCount = 0

        val origin = pose.position2D
        val count = cloud?.count ?: 0

        for (i in 0 until count) {
            val px = cloud!!.x(i)
            val py = cloud.y(i)
            val pz = cloud.z(i)
            if (px.isNaN() || py.isNaN() || pz.isNaN()) continue

            val dx = px - origin.x
            val dz = pz - origin.z
            val rangeSq = dx * dx + dz * dz
            val height = py - floor.floorY

            // Classified exactly as NavigationEngine.integrateDepth does, in the same order, so
            // this view cannot drift from the behaviour it is meant to explain.
            val color = when {
                rangeSq > maxRangeSq || rangeSq < minRangeSq -> {
                    outOfRangeCount++
                    outOfRangeColor
                }
                height > maxHeight -> {
                    overheadCount++
                    overheadColor
                }
                height < -config.floorBandBelowMeters -> {
                    belowFloorCount++
                    belowFloorColor
                }
                height < minHeight -> {
                    floorCount++
                    floorColor
                }
                else -> {
                    obstacleCount++
                    obstacleColor
                }
            }

            val world = Vec2(px, pz)
            if (!grid.contains(world)) {
                offWindowCount++
                continue
            }

            val (sx, sy) = toPixel(world)
            paint.color = color
            canvas.drawRect(sx - 1f, sy - 1f, sx + 1f, sy + 1f, paint)
        }

        // The sensor's own position and heading: every range above is measured from here.
        val (ux, uy) = toPixel(origin)
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
            RenderedDepth(
                base64 = base64,
                width = size,
                height = size,
                resolutionMeters = resolution,
                sizeMeters = sizeMeters,
                totalReturns = count,
                floorReturns = floorCount,
                obstacleReturns = obstacleCount,
                overheadReturns = overheadCount,
                belowFloorReturns = belowFloorCount,
                outOfRangeReturns = outOfRangeCount,
                offWindowReturns = offWindowCount,
                floorY = floor.floorY,
                floorConfidence = floor.confidence,
                ageMillis = ageMillis,
                hasFrame = cloud != null,
            )
        }
    }
}
