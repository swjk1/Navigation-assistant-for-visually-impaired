package expo.modules.indoorperception

/**
 * Classes of the fine-tuned YOLO26n model (`indoor_yolo26.onnx`).
 *
 * This replaces the generic COCO list. COCO has no door, no exit sign and no lift — the three
 * things this app most needs — so the old mapping could only ever emit `person` and `chair`.
 * This model was trained on an indoor-navigation dataset and names the things that matter.
 */
object IndoorLabels {

  /** Index = class id, exactly as exported in the model's `names` metadata. */
  val NAMES = arrayOf(
    "accessibility",      // 0
    "door",               // 1
    "elevator",           // 2
    "elevator sign",      // 3
    "exit sign",          // 4
    "fire alarm",         // 5
    "fire extinguisher",  // 6
    "handle",             // 7
    "left arrow",         // 8
    "men-s washroom",     // 9
    "person",             // 10
    "push handle",        // 11
    "right arrow",        // 12
    "stair sign",         // 13
    "trash can",          // 14
    "water dispenser",    // 15
    "women-s washroom",   // 16
  )

  /**
   * Maps a detected class onto Person 1's `ObjectLabel` contract.
   *
   * Signs are reported as their subject rather than as generic `sign`: an "exit sign" is how a
   * user finds an exit, and flattening it to `sign` would throw away the only useful part. The
   * navigation engine then treats an exit sign as exit evidence, a stair sign as stairs, and so
   * on — see src/perception/perceptionToSemantic.ts.
   *
   * Returns null for classes the navigation layer has no use for; those detections are dropped.
   */
  fun toNavLabel(classId: Int): String? {
    if (classId !in NAMES.indices) return null
    return when (NAMES[classId]) {
      "door" -> "door"
      "elevator", "elevator sign" -> "elevator"
      "stair sign" -> "stairs"
      "exit sign" -> "exit sign"
      "left arrow" -> "left arrow"
      "right arrow" -> "right arrow"
      "men-s washroom", "women-s washroom", "accessibility" -> "washroom"
      "person" -> "person"
      "trash can" -> "trashcan"
      // handle / push handle are parts of a door, not places; fire alarm, fire extinguisher and
      // water dispenser are landmarks a sighted guide might mention but are not navigable.
      else -> null
    }
  }
}
