package app.gamenative.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.winlator.renderer.GLContinuousRecorder
import java.nio.ByteBuffer

object RecordingUtils {

    /**
     * Muxes a [GLContinuousRecorder.ClipSnapshot] (the last X seconds of encoded H.264 frames)
     * into an mp4 file in Movies/GameNative/ via MediaStore.Video.
     *
     * The snapshot's encoded samples already carry SPS/PPS in [GLContinuousRecorder.ClipSnapshot.format]
     * (the encoder's output format), so no re-encoding happens here — we just write the existing
     * access units to a container. Must be called on a background thread (MediaMuxer + resolver I/O).
     *
     * @param label Used as the filename prefix (typically the game name).
     * @return The [Uri] of the saved video, or null if writing failed.
     */
    fun saveClip(context: Context, snapshot: GLContinuousRecorder.ClipSnapshot, label: String): Uri? {
        if (snapshot.samples.isEmpty()) return null

        val filename = "${label}_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/GameNative",
            )
            // IS_PENDING hides the file from gallery apps until the write is complete.
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            // MediaMuxer needs a writable FileDescriptor; openFileDescriptor gives one from the
            // pending MediaStore entry.
            resolver.openFileDescriptor(uri, "w").use { pfd ->
                requireNotNull(pfd) { "Null file descriptor for $uri" }
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val track = muxer.addTrack(snapshot.format)
                    muxer.start()

                    // Rebase timestamps so the clip starts at t=0.
                    val baseUs = snapshot.samples.first().ptsUs
                    val info = MediaCodec.BufferInfo()
                    for (sample in snapshot.samples) {
                        val buf: ByteBuffer = ByteBuffer.wrap(sample.data)
                        info.set(
                            /* newOffset = */ 0,
                            /* newSize = */ sample.data.size,
                            /* newTimeUs = */ (sample.ptsUs - baseUs).coerceAtLeast(0),
                            /* newFlags = */ if (sample.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                        )
                        muxer.writeSampleData(track, buf, info)
                    }
                    muxer.stop()
                } finally {
                    muxer.release()
                }
            }

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            // Clean up the dangling MediaStore entry on failure.
            resolver.delete(uri, null, null)
            null
        }
    }
}
