package expo.modules.indoorperception

object CocoLabels {
  val NAMES = arrayOf(
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
    "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
    "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
    "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
    "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
    "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
  )

  /**
   * Map COCO class → Person 1 ObjectLabel (PRD). Unmapped classes return null.
   * Door/stairs/elevator/trashcan/wall/sign need fine-tune or VLM fill-in.
   */
  fun toNavLabel(classId: Int): String? {
    if (classId !in NAMES.indices) return null
    return when (NAMES[classId]) {
      "person" -> "person"
      "chair", "couch", "bench" -> "chair"
      "stop sign" -> "sign"
      else -> null
    }
  }
}
