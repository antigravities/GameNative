package app.gamenative.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.winlator.renderer.GLRenderer
import java.io.ByteArrayOutputStream

object ScreenshotUtils {

    /**
     * Queues a screenshot capture on the GL thread via [renderer]. The [onCaptured]
     * callback is invoked on the GL thread with the resulting Bitmap, or null on
     * failure. Callers must dispatch any UI work (Toasts, etc.) back to the main
     * thread — e.g., via [kotlinx.coroutines.withContext] or a Handler.
     *
     * Driver-agnostic: works with Turnip/DRI3 GPU hardware buffers and software paths.
     *
     * @param postEffects true = capture the composited frame shown on screen (with effects
     *                    like CRT/FSR/FXAA applied); false = capture the raw game frame
     *                    before any post-processing (default, preserves prior behavior).
     */
    fun captureFromGL(renderer: GLRenderer, postEffects: Boolean = false, onCaptured: (Bitmap?) -> Unit) {
        try {
            renderer.captureFrame({ bitmap -> onCaptured(bitmap) }, postEffects)
        } catch (e: Exception) {
            onCaptured(null)
        }
    }

    /**
     * Saves [bitmap] as a PNG to Pictures/GameNative/ in the system gallery via
     * MediaStore. Uses IS_PENDING so the file is invisible to other apps until
     * fully written. Must be called on a background thread (ContentResolver I/O).
     *
     * @param label Used as the filename prefix (typically the game name).
     * @return The [Uri] of the saved image, or null if writing failed.
     */
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, label: String): Uri? {
        val filename = "${label}_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/GameNative",
            )
            // IS_PENDING hides the file from gallery apps until the write is complete.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            // Clean up the dangling MediaStore entry on failure.
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * Compresses [bitmap] to JPEG bytes. JPEG is preferred over PNG for Steam uploads
     * because it's significantly smaller while the Steam screenshot viewer displays it
     * natively at full quality.
     */
    fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int = 90): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /**
     * Scales [bitmap] down and encodes as JPEG for use as the Steam thumbnail.
     * Steam requires a thumbnail alongside the full image when registering a screenshot.
     */
    fun generateThumbnailBytes(bitmap: Bitmap, maxWidth: Int = 320, quality: Int = 80): ByteArray {
        val scale = maxWidth.toFloat() / bitmap.width
        val thumb = Bitmap.createScaledBitmap(
            bitmap,
            maxWidth,
            (bitmap.height * scale).toInt(),
            /* filter= */ true,
        )
        val out = ByteArrayOutputStream()
        thumb.compress(Bitmap.CompressFormat.JPEG, quality, out)
        thumb.recycle()
        return out.toByteArray()
    }
}
