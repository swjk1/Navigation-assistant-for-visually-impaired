package expo.modules.indoorperception

/**
 * Clockwise rotation of an NV21 image (full-resolution Y plane followed by interleaved V/U at
 * half resolution), so captures can be turned upright before any model sees them.
 *
 * Done on the raw buffer rather than a decoded Bitmap: it is one pass over ~460 KB for a 640x480
 * frame, with no decode/re-encode round trip. Width and height must be even, which camera
 * images always are.
 */
internal object Nv21 {

    /** @return the rotated buffer, or [source] itself for 0 degrees. */
    fun rotate(source: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        val r = ((degrees % 360) + 360) % 360
        if (r == 0) return source
        require(r == 90 || r == 180 || r == 270) { "rotation must be a multiple of 90, was $degrees" }

        val out = ByteArray(source.size)
        val lumaSize = width * height

        // Luma. For a clockwise quarter turn, source (x, y) lands at row x, column height-1-y of
        // an image that is `height` wide.
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val target = when (r) {
                    90 -> x * height + (height - 1 - y)
                    180 -> (height - 1 - y) * width + (width - 1 - x)
                    else -> (width - 1 - x) * height + y
                }
                out[target] = source[row + x]
            }
        }

        // Chroma: the same mapping on the half-resolution grid, moving V/U pairs together.
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (cy in 0 until chromaHeight) {
            for (cx in 0 until chromaWidth) {
                val from = lumaSize + cy * width + cx * 2
                val to = lumaSize + when (r) {
                    90 -> cx * height + (chromaHeight - 1 - cy) * 2
                    180 -> (chromaHeight - 1 - cy) * width + (chromaWidth - 1 - cx) * 2
                    else -> (chromaWidth - 1 - cx) * height + cy * 2
                }
                out[to] = source[from]
                out[to + 1] = source[from + 1]
            }
        }
        return out
    }
}
