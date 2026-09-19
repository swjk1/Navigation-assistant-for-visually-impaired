package expo.modules.indoorperception

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

data class YoloDetection(
  val classId: Int,
  val cocoName: String,
  val navLabel: String?,
  val confidence: Float,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float
)

/**
 * On-device YOLO26n (TFLite / LiteRT .tflite) detector.
 * Supports common Ultralytics export layouts:
 *  - [1, 84, 8400] / [1, 84, N]  (cls scores + xywh)
 *  - [1, N, 84]                  (transposed)
 *  - [1, N, 6]                   (end-to-end xyxy + conf + cls)
 */
class Yolo26Detector(private val context: Context) {
  companion object {
    const val ASSET_NAME = "yolo26n_int8.tflite"
    const val INPUT_SIZE = 640
  }

  private var interpreter: Interpreter? = null
  var modelPath: String? = null
    private set

  val isLoaded: Boolean
    get() = interpreter != null

  fun ensureLoaded(): Boolean {
    if (interpreter != null) return true
    val buffer = loadModelBuffer() ?: return false
    val options = Interpreter.Options().apply {
      setNumThreads(4)
    }
    interpreter = Interpreter(buffer, options)
    return true
  }

  fun close() {
    interpreter?.close()
    interpreter = null
  }

  fun detect(
    bitmap: Bitmap,
    confThreshold: Float = 0.35f,
    iouThreshold: Float = 0.45f,
    maxDetections: Int = 25
  ): List<YoloDetection> {
    if (!ensureLoaded()) {
      throw IllegalStateException(
        "YOLO26n model not found. Run: npm run download:yolo26 && rebuild the Android app."
      )
    }
    val interp = interpreter!!

    val inputShape = interp.getInputTensor(0).shape()
    val height = if (inputShape.size >= 3) inputShape[1] else INPUT_SIZE
    val width = if (inputShape.size >= 4) inputShape[2] else INPUT_SIZE
    // Handle NCHW [1,3,H,W] vs NHWC [1,H,W,3]
    val nchw = inputShape.size == 4 && inputShape[1] == 3

    val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
    val inputBuffer = if (nchw) {
      bitmapToNchwFloatBuffer(resized, width, height)
    } else {
      bitmapToNhwcFloatBuffer(resized, width, height)
    }

    val outputTensor = interp.getOutputTensor(0)
    val outputShape = outputTensor.shape()
    val outputBuffer = TensorBuffer.createFixedSize(outputShape, outputTensor.dataType())
    interp.run(inputBuffer, outputBuffer.buffer.rewind())

    val flat = outputBuffer.floatArray
    val candidates = decodeOutputs(flat, outputShape, confThreshold)
    return nms(candidates, iouThreshold).take(maxDetections)
  }

  private fun loadModelBuffer(): MappedByteBuffer? {
    // Prefer filesDir copy (downloadable), then module assets
    val local = File(context.filesDir, ASSET_NAME)
    if (local.exists() && local.length() > 0) {
      modelPath = local.absolutePath
      return FileInputStream(local).channel.map(
        FileChannel.MapMode.READ_ONLY, 0, local.length()
      )
    }

    return try {
      val assetBuffer = FileUtil.loadMappedFile(context, ASSET_NAME)
      modelPath = "asset://$ASSET_NAME"
      // Also mirror into filesDir for inspection
      try {
        context.assets.open(ASSET_NAME).use { input ->
          FileOutputStream(local).use { output -> input.copyTo(output) }
        }
      } catch (_: Exception) {
      }
      assetBuffer
    } catch (_: Exception) {
      modelPath = null
      null
    }
  }

  private fun bitmapToNhwcFloatBuffer(bitmap: Bitmap, w: Int, h: Int): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(1 * w * h * 3 * 4).order(ByteOrder.nativeOrder())
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    for (pixel in pixels) {
      buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
      buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
      buffer.putFloat((pixel and 0xFF) / 255f)
    }
    buffer.rewind()
    return buffer
  }

  private fun bitmapToNchwFloatBuffer(bitmap: Bitmap, w: Int, h: Int): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(1 * 3 * w * h * 4).order(ByteOrder.nativeOrder())
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    // R plane
    for (pixel in pixels) buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
    // G plane
    for (pixel in pixels) buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
    // B plane
    for (pixel in pixels) buffer.putFloat((pixel and 0xFF) / 255f)
    buffer.rewind()
    return buffer
  }

  private fun decodeOutputs(
    data: FloatArray,
    shape: IntArray,
    confThreshold: Float
  ): MutableList<YoloDetection> {
    val out = mutableListOf<YoloDetection>()
    if (shape.size < 2) return out

    // End-to-end style: [1, N, 6] => x1,y1,x2,y2,conf,cls
    if (shape.size == 3 && shape[2] == 6) {
      val n = shape[1]
      for (i in 0 until n) {
        val base = i * 6
        val conf = data[base + 4]
        if (conf < confThreshold) continue
        val classId = data[base + 5].toInt()
        val x1 = data[base]
        val y1 = data[base + 1]
        val x2 = data[base + 2]
        val y2 = data[base + 3]
        out.add(boxFromXyxy(x1, y1, x2, y2, classId, conf))
      }
      return out
    }

    // Ultralytics classic: [1, C, N] where C = 4 + numClasses (e.g. 84)
    if (shape.size == 3 && shape[1] < shape[2]) {
      val channels = shape[1]
      val anchors = shape[2]
      val numClasses = channels - 4
      for (i in 0 until anchors) {
        var bestCls = 0
        var bestScore = 0f
        for (c in 0 until numClasses) {
          val score = data[(4 + c) * anchors + i]
          if (score > bestScore) {
            bestScore = score
            bestCls = c
          }
        }
        if (bestScore < confThreshold) continue
        val cx = data[0 * anchors + i]
        val cy = data[1 * anchors + i]
        val w = data[2 * anchors + i]
        val h = data[3 * anchors + i]
        out.add(boxFromCxcywh(cx, cy, w, h, bestCls, bestScore))
      }
      return out
    }

    // Transposed: [1, N, C]
    if (shape.size == 3 && shape[2] > shape[1]) {
      val anchors = shape[1]
      val channels = shape[2]
      val numClasses = channels - 4
      for (i in 0 until anchors) {
        val base = i * channels
        var bestCls = 0
        var bestScore = 0f
        for (c in 0 until numClasses) {
          val score = data[base + 4 + c]
          if (score > bestScore) {
            bestScore = score
            bestCls = c
          }
        }
        if (bestScore < confThreshold) continue
        out.add(
          boxFromCxcywh(
            data[base], data[base + 1], data[base + 2], data[base + 3],
            bestCls, bestScore
          )
        )
      }
    }

    return out
  }

  private fun boxFromCxcywh(
    cx: Float, cy: Float, w: Float, h: Float, classId: Int, conf: Float
  ): YoloDetection {
    // Values may be pixels (0..640) or normalized
    val norm = cx <= 1.5f && cy <= 1.5f && w <= 1.5f && h <= 1.5f
    val ncx = if (norm) cx else cx / INPUT_SIZE
    val ncy = if (norm) cy else cy / INPUT_SIZE
    val nw = if (norm) w else w / INPUT_SIZE
    val nh = if (norm) h else h / INPUT_SIZE
    val x = clamp01(ncx - nw / 2f)
    val y = clamp01(ncy - nh / 2f)
    val name = CocoLabels.NAMES.getOrElse(classId) { "class_$classId" }
    return YoloDetection(
      classId = classId,
      cocoName = name,
      navLabel = CocoLabels.toNavLabel(classId),
      confidence = conf,
      x = x,
      y = y,
      width = clamp01(nw),
      height = clamp01(nh)
    )
  }

  private fun boxFromXyxy(
    x1: Float, y1: Float, x2: Float, y2: Float, classId: Int, conf: Float
  ): YoloDetection {
    val norm = x2 <= 1.5f && y2 <= 1.5f
    val nx1 = if (norm) x1 else x1 / INPUT_SIZE
    val ny1 = if (norm) y1 else y1 / INPUT_SIZE
    val nx2 = if (norm) x2 else x2 / INPUT_SIZE
    val ny2 = if (norm) y2 else y2 / INPUT_SIZE
    val x = clamp01(min(nx1, nx2))
    val y = clamp01(min(ny1, ny2))
    val w = clamp01(kotlin.math.abs(nx2 - nx1))
    val h = clamp01(kotlin.math.abs(ny2 - ny1))
    val name = CocoLabels.NAMES.getOrElse(classId) { "class_$classId" }
    return YoloDetection(
      classId = classId,
      cocoName = name,
      navLabel = CocoLabels.toNavLabel(classId),
      confidence = conf,
      x = x,
      y = y,
      width = w,
      height = h
    )
  }

  private fun nms(detections: List<YoloDetection>, iouThreshold: Float): List<YoloDetection> {
    val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
    val keep = mutableListOf<YoloDetection>()
    while (sorted.isNotEmpty()) {
      val best = sorted.removeAt(0)
      keep.add(best)
      sorted.removeAll { iou(best, it) > iouThreshold && it.classId == best.classId }
    }
    return keep
  }

  private fun iou(a: YoloDetection, b: YoloDetection): Float {
    val ax2 = a.x + a.width
    val ay2 = a.y + a.height
    val bx2 = b.x + b.width
    val by2 = b.y + b.height
    val ix1 = max(a.x, b.x)
    val iy1 = max(a.y, b.y)
    val ix2 = min(ax2, bx2)
    val iy2 = min(ay2, by2)
    val inter = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
    val union = a.width * a.height + b.width * b.height - inter
    return if (union <= 0f) 0f else inter / union
  }

  private fun clamp01(v: Float): Float = max(0f, min(1f, v))
}
