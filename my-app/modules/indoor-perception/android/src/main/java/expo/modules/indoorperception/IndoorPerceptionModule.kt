package expo.modules.indoorperception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** A frame should arrive within a couple of render frames; 3 s is generous. */
private const val CAPTURE_TIMEOUT_MS = 3000L

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
  /** Decode / orchestration thread */
  private val executor = Executors.newSingleThreadExecutor()
  /** Parallel YOLO + OCR workers (max 2) */
  private val analyzePool = Executors.newFixedThreadPool(2)
  /** Single-threaded timer that rejects capture promises the render loop never served. */
  private val captureWatchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "perception-capture-watchdog").apply { isDaemon = true }
  }

  private var detector: YoloOnnxDetector? = null
  private var ocr: MlKitOcr? = null

  companion object {
    /** Reject oversized payloads before decode (~2× capture budget). */
    private const val MAX_BASE64_CHARS = 400_000
  }

  private fun getDetector(): YoloOnnxDetector {
    val existing = detector
    if (existing != null) return existing
    val created = YoloOnnxDetector(appContext.reactContext ?: throw IllegalStateException("No React context"))
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
      ArFrameSource.destroy()
      detector?.close()
      ocr?.close()
      detector = null
      ocr = null
      executor.shutdownNow()
      analyzePool.shutdownNow()
      captureWatchdog.shutdownNow()
    }


    // ---------------------------------------------------------------- camera ownership
    //
    // This module owns the app's single ARCore session (see ArFrameSource). Navigation is fed
    // from the same frames via NavigationSensorBridge, because ARCore needs exclusive access to
    // the camera and cannot share it with a second CameraView or Camera2 session.

    View(PerceptionArView::class) {
      // Mounting the view starts the session; nothing else to configure.
    }

    Function("getCameraStatus") {
      mapOf(
        "sessionActive" to (ArFrameSource.session != null),
        "depthSupported" to ArFrameSource.depthSupported,
        "lastError" to ArFrameSource.lastError,
      )
    }

    /**
     * Captures one RGB frame from the live ARCore session, replacing CameraView's
     * takePictureAsync. Feed the result straight into analyzeFrame().
     *
     * `timestampNs` is ARCore's frame timestamp (nanoseconds since boot) - the value
     * SemanticObservation.timestampNs expects, so observations derived from this image line up
     * with the depth data navigation is using.
     */
    AsyncFunction("captureFrame") { promise: Promise ->
      // The capture is served by the GL thread. If the AR view is not mounted, the session died,
      // or the device never delivers a CPU image, that thread simply never runs our callback and
      // the promise would hang forever - taking the caller's perception loop down with it.
      // A watchdog turns that into an ordinary rejection the UI can show.
      val settled = java.util.concurrent.atomic.AtomicBoolean(false)
      captureWatchdog.schedule({
        if (settled.compareAndSet(false, true)) {
          promise.reject(
            "ERR_CAPTURE_TIMEOUT",
            "No camera frame within ${CAPTURE_TIMEOUT_MS} ms. Is <PerceptionArView /> mounted?",
            null,
          )
        }
      }, CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

      ArFrameSource.requestCapture { result ->
        if (!settled.compareAndSet(false, true)) return@requestCapture
        result.fold(
          onSuccess = { captured ->
            promise.resolve(
              mapOf(
                "base64" to captured.base64,
                "width" to captured.width,
                "height" to captured.height,
                "timestampNs" to captured.timestampNanos.toDouble(),
                "rotationDegrees" to captured.rotationDegrees,
                "mimeType" to "image/jpeg",
              ),
            )
          },
          onFailure = { error ->
            promise.reject("ERR_CAPTURE", error.message ?: "Capture failed", error as? Exception)
          },
        )
      }
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
        "modelAssetName" to YoloOnnxDetector.ASSET_NAME
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
          if (base64Image.length > MAX_BASE64_CHARS) {
            throw IllegalArgumentException(
              "Frame base64 too large (${base64Image.length} chars). Recapture at 640x480 q≤0.4."
            )
          }

          val opts = options ?: AnalyzeOptions()
          val bitmap = decodeBase64Bitmap(base64Image)
            ?: throw IllegalArgumentException("Could not decode base64 image")

          var yoloMs = 0L
          var ocrMs = 0L
          var yoloError: String? = null
          var ocrError: String? = null
          var objects: List<Map<String, Any?>> = emptyList()
          var texts: List<Map<String, Any?>> = emptyList()
          var modelLoaded = false
          var modelPath: String? = null

          data class YoloResult(
            val objects: List<Map<String, Any?>>,
            val ms: Long,
            val error: String?,
            val loaded: Boolean,
            val path: String?
          )

          data class OcrResult(
            val texts: List<Map<String, Any?>>,
            val ms: Long,
            val error: String?
          )

          val runBoth = opts.runYolo && opts.runOcr
          var yoloFuture: Future<YoloResult>? = null
          var ocrFuture: Future<OcrResult>? = null

          if (opts.runYolo) {
            val task = Callable {
              val y0 = System.currentTimeMillis()
              try {
                val det = getDetector()
                val loaded = det.ensureLoaded()
                val path = det.modelPath
                if (!loaded) {
                  YoloResult(
                    emptyList(),
                    System.currentTimeMillis() - y0,
                    "Model asset missing: ${YoloOnnxDetector.ASSET_NAME}. Rebuild the app so the asset is packaged",
                    false,
                    path
                  )
                } else {
                  val detections = det.detect(
                    bitmap,
                    confThreshold = opts.confThreshold.toFloat(),
                    iouThreshold = opts.iouThreshold.toFloat(),
                    maxDetections = opts.maxDetections
                  )
                  val mapped = mutableListOf<Map<String, Any?>>()
                  for (d in detections) {
                    val label = d.navLabel ?: continue
                    mapped.add(
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
                  YoloResult(mapped, System.currentTimeMillis() - y0, null, true, path)
                }
              } catch (e: Exception) {
                YoloResult(emptyList(), System.currentTimeMillis() - y0, e.message, false, null)
              }
            }
            if (runBoth) {
              yoloFuture = analyzePool.submit(task)
            } else {
              val r = task.call()
              objects = r.objects
              yoloMs = r.ms
              yoloError = r.error
              modelLoaded = r.loaded
              modelPath = r.path
            }
          }

          if (opts.runOcr) {
            val task = Callable {
              val o0 = System.currentTimeMillis()
              try {
                val lines = getOcr().recognize(bitmap)
                val mapped = mutableListOf<Map<String, Any?>>()
                for (line in lines) {
                  mapped.add(
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
                OcrResult(mapped, System.currentTimeMillis() - o0, null)
              } catch (e: Exception) {
                OcrResult(emptyList(), System.currentTimeMillis() - o0, e.message)
              }
            }
            if (runBoth) {
              ocrFuture = analyzePool.submit(task)
            } else {
              val r = task.call()
              texts = r.texts
              ocrMs = r.ms
              ocrError = r.error
            }
          }

          if (runBoth) {
            val yolo = yoloFuture!!.get(8, TimeUnit.SECONDS)
            val ocrRes = ocrFuture!!.get(8, TimeUnit.SECONDS)
            objects = yolo.objects
            yoloMs = yolo.ms
            yoloError = yolo.error
            modelLoaded = yolo.loaded
            modelPath = yolo.path
            texts = ocrRes.texts
            ocrMs = ocrRes.ms
            ocrError = ocrRes.error
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
