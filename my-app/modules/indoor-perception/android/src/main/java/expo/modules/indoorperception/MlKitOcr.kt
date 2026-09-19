package expo.modules.indoorperception

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

data class OcrDetection(
  val text: String,
  val confidence: Float,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float
)

class MlKitOcr {
  private val recognizer =
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

  fun recognize(bitmap: Bitmap, timeoutMs: Long = 2500L): List<OcrDetection> {
    val image = InputImage.fromBitmap(bitmap, 0)
    val result = Tasks.await(recognizer.process(image), timeoutMs, TimeUnit.MILLISECONDS)
    val w = bitmap.width.toFloat().coerceAtLeast(1f)
    val h = bitmap.height.toFloat().coerceAtLeast(1f)
    val out = mutableListOf<OcrDetection>()

    for (block in result.textBlocks) {
      for (line in block.lines) {
        val text = line.text?.trim().orEmpty()
        if (text.isEmpty()) continue
        val box = line.boundingBox
        val conf = try {
          line.confidence
        } catch (_: Exception) {
          0.75f
        } ?: 0.75f

        if (box != null) {
          out.add(
            OcrDetection(
              text = text.take(50),
              confidence = conf.coerceIn(0f, 1f),
              x = (box.left / w).coerceIn(0f, 1f),
              y = (box.top / h).coerceIn(0f, 1f),
              width = (box.width() / w).coerceIn(0f, 1f),
              height = (box.height() / h).coerceIn(0f, 1f)
            )
          )
        } else {
          out.add(
            OcrDetection(
              text = text.take(50),
              confidence = conf.coerceIn(0f, 1f),
              x = 0f, y = 0f, width = 0f, height = 0f
            )
          )
        }
      }
    }
    return out
  }

  fun close() {
    recognizer.close()
  }
}
