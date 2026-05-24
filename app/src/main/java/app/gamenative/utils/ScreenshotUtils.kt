package app.gamenative.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.VulkanRenderer
import com.winlator.renderer.XServerRenderer
import java.io.ByteArrayOutputStream

object ScreenshotUtils {

    /**
     * Captures a frame from [renderer], dispatching to the appropriate backend.
     *
     * For [GLRenderer] (VirGL/OpenGL path), this queues a one-shot GL-thread read;
     * [onCaptured] is called on the GL thread — marshal UI work back to Main/IO.
     * [postEffects] controls whether effects (CRT/FSR/FXAA) are included.
     *
     * For [VulkanRenderer], this uses PixelCopy to read the composited surface buffer;
     * [onCaptured] is called on the main thread. [postEffects] is ignored — the Vulkan
     * compositor has no pre-effects capture path.
     */
    fun captureFromGL(renderer: XServerRenderer, postEffects: Boolean = false, onCaptured: (Bitmap?) -> Unit) {
        when (renderer) {
            is GLRenderer -> try {
                renderer.captureFrame({ bitmap -> onCaptured(bitmap) }, postEffects)
            } catch (e: Exception) {
                onCaptured(null)
            }
            is VulkanRenderer -> try {
                renderer.captureFrame { bitmap -> onCaptured(bitmap) }
            } catch (e: Exception) {
                onCaptured(null)
            }
            else -> onCaptured(null)
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
