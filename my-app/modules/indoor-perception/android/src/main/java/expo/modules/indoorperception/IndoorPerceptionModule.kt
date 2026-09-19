package expo.modules.indoorperception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.util.concurrent.Executors

class AnalyzeOptions : Record {
  @Field
  var confThreshold: Double = 0.35

  @Field
  var iouThreshold: Double = 0.45

  @Field
  var maxDetections: Int = 25

  @Field
  var runYolo: Boolean = true

  @Field
  var runOcr: Boolean = true
}

class IndoorPerceptionModule : Module() {
  private val executor = Executors.newSingleThreadExecutor()
  private var detector: Yolo26Detector? = null
  private var ocr: MlKitOcr? = null

  private fun getDetector(): Yolo26Detector {
    val existing = detector
    if (existing != null) return existing
    val created = Yolo26Detector(appContext.reactContext ?: throw IllegalStateException("No React context"))
    detector = created
    return created
  }

  private fun getOcr(): MlKitOcr {
    val existing = ocr
    if (existing != null) return existing
    val created = MlKitOcr()
    ocr = created
    return created
  }

  override fun definition() = ModuleDefinition {
    Name("IndoorPerception")

    OnDestroy {
      detector?.close()
      ocr?.close()
      detector = null
      ocr = null
      executor.shutdownNow()
    }

    AsyncFunction("getStatus") {
      val det = try {
        getDetector()
      } catch (_: Exception) {
        null
      }
      mapOf(
        "platform" to "android",
        "modelLoaded" to (det?.isLoaded == true),
        "modelAssetName" to Yolo26Detector.ASSET_NAME
      )
    }

    AsyncFunction("ensureModelLoaded") { promise: Promise ->
      executor.execute {
        try {
          val ok = getDetector().ensureLoaded()
          promise.resolve(ok)
        } catch (e: Exception) {
          promise.reject("MODEL_LOAD_FAILED", e.message, e)
        }
      }
    }

    AsyncFunction("analyzeFrame") { base64Image: String, options: AnalyzeOptions?, promise: Promise ->
      executor.execute {
        val totalStart = System.currentTimeMillis()
        try {
          val opts = options ?: AnalyzeOptions()
          val bitmap = decodeBase64Bitmap(base64Image)
            ?: throw IllegalArgumentException("Could not decode base64 image")

          var yoloMs = 0L
          var ocrMs = 0L
          var yoloError: String? = null
          var ocrError: String? = null
          val objects = mutableListOf<Map<String, Any?>>()
          val texts = mutableListOf<Map<String, Any?>>()
          var modelLoaded = false
          var modelPath: String? = null

          if (opts.runYolo) {
            val y0 = System.currentTimeMillis()
            try {
              val det = getDetector()
              modelLoaded = det.ensureLoaded()
              modelPath = det.modelPath
              if (!modelLoaded) {
                yoloError =
                  "Model asset missing: ${Yolo26Detector.ASSET_NAME}. Run npm run download:yolo26"
              } else {
                val detections = det.detect(
                  bitmap,
                  confThreshold = opts.confThreshold.toFloat(),
                  iouThreshold = opts.iouThreshold.toFloat(),
                  maxDetections = opts.maxDetections
                )
                for (d in detections) {
                  val label = d.navLabel ?: continue
                  objects.add(
                    mapOf(
                      "label" to label,
                      "confidence" to d.confidence.toDouble(),
                      "box" to mapOf(
                        "x" to d.x.toDouble(),
                        "y" to d.y.toDouble(),
                        "width" to d.width.toDouble(),
                        "height" to d.height.toDouble()
                      ),
                      "cocoClassId" to d.classId,
                      "cocoName" to d.cocoName
                    )
                  )
                }
              }
            } catch (e: Exception) {
              yoloError = e.message
            }
            yoloMs = System.currentTimeMillis() - y0
          }

          if (opts.runOcr) {
            val o0 = System.currentTimeMillis()
            try {
              val lines = getOcr().recognize(bitmap)
              for (line in lines) {
                texts.add(
                  mapOf(
                    "text" to line.text,
                    "confidence" to line.confidence.toDouble(),
                    "box" to mapOf(
                      "x" to line.x.toDouble(),
                      "y" to line.y.toDouble(),
                      "width" to line.width.toDouble(),
                      "height" to line.height.toDouble()
                    )
                  )
                )
              }
            } catch (e: Exception) {
              ocrError = e.message
            }
            ocrMs = System.currentTimeMillis() - o0
          }

          val totalMs = System.currentTimeMillis() - totalStart
          promise.resolve(
            mapOf(
              "objects" to objects,
              "text" to texts,
              "yoloMs" to yoloMs.toDouble(),
              "ocrMs" to ocrMs.toDouble(),
              "totalMs" to totalMs.toDouble(),
              "modelLoaded" to modelLoaded,
              "modelPath" to modelPath,
              "yoloError" to yoloError,
              "ocrError" to ocrError,
              "platform" to "android"
            )
          )
        } catch (e: Exception) {
          promise.reject("ANALYZE_FAILED", e.message, e)
        }
      }
    }
  }

  private fun decodeBase64Bitmap(base64Image: String): Bitmap? {
    val cleaned = base64Image
      .replace("^data:image/\\w+;base64,".toRegex(), "")
      .replace("\\s".toRegex(), "")
    val bytes = Base64.decode(cleaned, Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  }
}
