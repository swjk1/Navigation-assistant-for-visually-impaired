import ExpoModulesCore

public class IndoorPerceptionModule: Module {
  public func definition() -> ModuleDefinition {
    Name("IndoorPerception")

    AsyncFunction("getStatus") { () -> [String: Any] in
      return [
        "platform": "ios",
        "modelLoaded": false,
        "modelAssetName": "yolo26n_int8.tflite"
      ]
    }

    AsyncFunction("ensureModelLoaded") { () -> Bool in
      return false
    }

    AsyncFunction("analyzeFrame") { (_ base64Image: String, _ options: [String: Any]?) in
      return [
        "objects": [],
        "text": [],
        "yoloMs": 0,
        "ocrMs": 0,
        "totalMs": 0,
        "modelLoaded": false,
        "modelPath": NSNull(),
        "yoloError": "YOLO26n + ML Kit path is Android-first. Use Android dev build.",
        "ocrError": "iOS OCR not wired yet",
        "platform": "ios"
      ]
    }
  }
}
