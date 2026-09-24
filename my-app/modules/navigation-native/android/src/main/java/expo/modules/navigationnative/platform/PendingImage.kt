package expo.modules.navigationnative.platform

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * A debug picture that has been DRAWN but not yet encoded.
 *
 * Drawing must happen on the engine thread, because it reads the live grid, path and frontiers.
 * PNG compression does not, and it is the expensive half - so it is split off and run elsewhere,
 * instead of stalling mapping several times a second while the debug view is open.
 */
class PendingImage<T>(
    private val bitmap: Bitmap,
    private val finish: (base64: String) -> T,
) {
    /** Compresses and recycles the bitmap. Call once, on any thread. */
    fun encode(): T {
        val out = ByteArrayOutputStream()
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bitmap.recycle()
        }
        return finish(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }
}
