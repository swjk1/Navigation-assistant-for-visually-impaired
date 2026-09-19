package expo.modules.indoorperception

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * One detection, in coordinates normalised against the ORIGINAL image (0..1), which is the space
 * the PerceptionFrame contract uses.
 *
 * `cocoName` keeps its name for source compatibility with the previous TFLite detector, but now
 * carries the indoor class name ("exit sign", "door", ...) rather than a COCO one.
 */
data class YoloDetection(
  val classId: Int,
  val cocoName: String,
  val navLabel: String?,
  val confidence: Float,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
)

/**
 * On-device detector for the fine-tuned indoor YOLO26n model (`indoor_yolo26.onnx`).
 *
 * Why ONNX Runtime rather than TFLite: the fine-tuned weights were exported to ONNX, and that
 * export is END-TO-END (`end2end: true`). The NMS is inside the graph, so the model returns a
 * fixed set of final detections as `[1, N, 6]` — x1, y1, x2, y2, score, classId — in the
 * coordinate space of the 640x640 input. No score decoding, no anchor maths and no NMS on our
 * side; the old TFLite path had all three and none of it is needed here.
 *
 * Coordinates: the bitmap is resized straight to 640x640 rather than letterboxed. That distorts
 * the aspect ratio slightly, but it makes the box mapping exact — divide by 640 and the result is
 * already normalised against the original image, which is what the PerceptionFrame contract
 * wants. Letterboxing would need the padding undone on every box for no real accuracy gain at
 * this resolution.
 */
class YoloOnnxDetector(private val context: Context) {

  companion object {
    const val ASSET_NAME = "indoor_yolo26.onnx"
    const val INPUT_SIZE = 640
    private const val INPUT_NAME = "images"
    private const val PIXELS = INPUT_SIZE * INPUT_SIZE
  }

  private var environment: OrtEnvironment? = null
  private var session: OrtSession? = null

  /** Reused across calls: 3 * 640 * 640 floats is 4.9 MB, far too much to allocate per frame. */
  private val inputBuffer = FloatBuffer.allocate(3 * PIXELS)
  private val pixels = IntArray(PIXELS)

  var modelPath: String? = null
    private set

  val isLoaded: Boolean get() = session != null

  fun ensureLoaded(): Boolean {
    if (session != null) return true
    return try {
      val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
      val env = OrtEnvironment.getEnvironment()
      val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
      }
      session = env.createSession(bytes, options)
      environment = env
      modelPath = "assets/$ASSET_NAME"
      true
    } catch (e: Throwable) {
      session = null
      environment = null
      modelPath = null
      false
    }
  }

  fun close() {
    session?.close()
    session = null
    environment = null
  }

  fun detect(
    bitmap: Bitmap,
    confThreshold: Float = 0.35f,
    @Suppress("UNUSED_PARAMETER") iouThreshold: Float = 0.45f,
    maxDetections: Int = 25,
  ): List<YoloDetection> {
    val ortSession = session ?: return emptyList()
    val env = environment ?: return emptyList()

    fillInput(bitmap)
    val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

    OnnxTensor.createTensor(env, inputBuffer, shape).use { tensor ->
      ortSession.run(mapOf(INPUT_NAME to tensor)).use { results ->
        val raw = results[0].value
        return decode(raw, confThreshold, maxDetections)
      }
    }
  }

  /** Bitmap -> NCHW float32 in 0..1, which is what Ultralytics exports expect. */
  private fun fillInput(bitmap: Bitmap) {
    val scaled = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
      bitmap
    } else {
      Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
    }
    scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

    val array = inputBuffer.array()
    // NCHW: all reds, then all greens, then all blues.
    for (i in 0 until PIXELS) {
      val p = pixels[i]
      array[i] = ((p shr 16) and 0xFF) / 255f
      array[PIXELS + i] = ((p shr 8) and 0xFF) / 255f
      array[2 * PIXELS + i] = (p and 0xFF) / 255f
    }
    inputBuffer.rewind()
    if (scaled !== bitmap) scaled.recycle()
  }

  /**
   * Reads `[1, N, 6]` end-to-end output. Detections come out sorted by score, so the first one
   * below the threshold ends the scan.
   */
  private fun decode(raw: Any?, confThreshold: Float, maxDetections: Int): List<YoloDetection> {
    @Suppress("UNCHECKED_CAST")
    val batch = (raw as? Array<*>)?.firstOrNull() as? Array<FloatArray> ?: return emptyList()
    val detections = ArrayList<YoloDetection>(min(maxDetections, batch.size))

    for (row in batch) {
      if (row.size < 6) continue
      val score = row[4]
      if (score < confThreshold) break
      val classId = row[5].toInt()
      val navLabel = IndoorLabels.toNavLabel(classId) ?: continue

      val x1 = clamp01(row[0] / INPUT_SIZE)
      val y1 = clamp01(row[1] / INPUT_SIZE)
      val x2 = clamp01(row[2] / INPUT_SIZE)
      val y2 = clamp01(row[3] / INPUT_SIZE)
      val width = x2 - x1
      val height = y2 - y1
      if (width <= 0f || height <= 0f) continue

      detections.add(
        YoloDetection(
          classId = classId,
          cocoName = IndoorLabels.NAMES.getOrElse(classId) { "unknown" },
          navLabel = navLabel,
          confidence = score,
          x = x1,
          y = y1,
          width = width,
          height = height,
        ),
      )
      if (detections.size >= maxDetections) break
    }
    return detections
  }

  private fun clamp01(v: Float): Float = max(0f, min(1f, v))
}
