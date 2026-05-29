package app.gamenative.utils

import android.content.ContentValues
import android.content.Context
import android.util.Log
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.winlator.renderer.GLContinuousRecorder
import java.nio.ByteBuffer

object RecordingUtils {

    private const val TAG = "ReplayAudio"
    private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val AAC_BITRATE = 128_000

    private class AacFrame(val data: ByteArray, val ptsUs: Long)

    /**
     * Muxes a [GLContinuousRecorder.ClipSnapshot] (the last X seconds of encoded H.264 frames)
     * into an mp4 in Movies/GameNative/ via MediaStore.Video, optionally with an audio track.
     *
     * The video samples already carry SPS/PPS in their format, so no video re-encoding happens.
     * If [audioPcm] is provided, its raw S16LE PCM is AAC-encoded here (once, at save time) and
     * written as a second track. Both tracks are rebased to a common t0 = first video keyframe pts
     * so audio and video stay aligned. Must be called on a background thread.
     *
     * @param label Used as the filename prefix (typically the game name).
     * @return The [Uri] of the saved video, or null if writing failed.
     */
    fun saveClip(
        context: Context,
        snapshot: GLContinuousRecorder.ClipSnapshot,
        label: String,
        audioPcm: AudioReplayRecorder.PcmSnapshot? = null,
    ): Uri? {
        if (snapshot.samples.isEmpty()) return null

        // Encode audio up front: the muxer needs the AAC format (with csd) before start().
        var aacFormat: MediaFormat? = null
        var aacFrames: List<AacFrame> = emptyList()
        if (audioPcm != null && audioPcm.samples.isNotEmpty()) {
            val encoded = runCatching { encodePcmToAac(audioPcm) }
                .onFailure { Log.e(TAG, "saveClip: AAC encode threw", it) }
                .getOrNull()
            if (encoded != null) {
                aacFormat = encoded.first
                aacFrames = encoded.second
            }
        }

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
            resolver.openFileDescriptor(uri, "w").use { pfd ->
                requireNotNull(pfd) { "Null file descriptor for $uri" }
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val videoTrack = muxer.addTrack(snapshot.format)
                    val audioTrack = if (aacFormat != null) muxer.addTrack(aacFormat) else -1
                    muxer.start()

                    // Rebase everything to the first video keyframe (the snapshot already starts there).
                    val t0 = snapshot.samples.first().ptsUs
                    val info = MediaCodec.BufferInfo()

                    for (s in snapshot.samples) {
                        info.set(
                            /* newOffset = */ 0,
                            /* newSize = */ s.data.size,
                            /* newTimeUs = */ (s.ptsUs - t0).coerceAtLeast(0),
                            /* newFlags = */ if (s.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                        )
                        muxer.writeSampleData(videoTrack, ByteBuffer.wrap(s.data), info)
                    }

                    if (audioTrack >= 0) {
                        for (f in aacFrames) {
                            if (f.ptsUs < t0) continue // precedes the clip start
                            info.set(0, f.data.size, f.ptsUs - t0, 0)
                            muxer.writeSampleData(audioTrack, ByteBuffer.wrap(f.data), info)
                        }
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
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * Synchronously AAC-encodes the windowed S16LE PCM. Returns the encoder's output format
     * (carrying csd-0) and the encoded frames with absolute (monotonic-us) timestamps, or null
     * if nothing was produced.
     */
    private fun encodePcmToAac(pcm: AudioReplayRecorder.PcmSnapshot): Pair<MediaFormat, List<AacFrame>>? {
        val bytesPerSec = pcm.sampleRate * pcm.channels * 2 // S16 = 2 bytes/sample
        if (bytesPerSec <= 0) return null

        val inFormat = MediaFormat.createAudioFormat(AAC_MIME, pcm.sampleRate, pcm.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(AAC_MIME)
        codec.configure(inFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        // Flatten the (potentially thousands of) tiny PCM fragments into one contiguous stream.
        // The monitor delivers ~384-byte chunks; feeding them one-per-input-buffer starves the
        // encoder and makes the encode take minutes. Coalescing lets us fill each input buffer to
        // capacity (~tens of KB), collapsing ~15k feeds to a few hundred.
        val pcm0 = pcm.samples
        val baseNanos = pcm0.first().ptsNanos
        val totalBytes = pcm0.sumOf { it.data.size }
        val pcmData = ByteArray(totalBytes)
        run {
            var off = 0
            for (c in pcm0) { System.arraycopy(c.data, 0, pcmData, off, c.data.size); off += c.data.size }
        }

        val frames = ArrayList<AacFrame>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var fed = 0           // bytes of pcmData already queued

        // Drains every output buffer currently ready, without blocking. Returns true once the
        // end-of-stream output buffer has been seen.
        fun drainReadyOutput(blockMs: Long): Boolean {
            var sawEos = false
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, if (sawEos) 0 else blockMs * 1000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                    continue
                }
                if (outIdx < 0) break // INFO_TRY_AGAIN_LATER (nothing ready within the timeout)
                val ob = codec.getOutputBuffer(outIdx)!!
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (info.size > 0 && !isConfig) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val d = ByteArray(info.size)
                    ob.get(d)
                    frames.add(AacFrame(d, info.presentationTimeUs))
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { sawEos = true; break }
            }
            return sawEos
        }

        try {
            // Phase 1: feed all input, draining ready output non-blocking each iteration so the
            // encoder keeps consuming and we never pay a per-iteration output-timeout stall.
            var inputDone = false
            while (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    ib.clear()
                    val remaining = totalBytes - fed
                    val ptsUs = baseNanos / 1_000 + fed.toLong() * 1_000_000L / bytesPerSec
                    if (remaining <= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val toCopy = minOf(remaining, ib.capacity())
                        ib.put(pcmData, fed, toCopy)
                        codec.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0)
                        fed += toCopy
                    }
                }
                drainReadyOutput(0) // non-blocking
            }
            // Phase 2: block until the encoder has flushed everything (EOS output buffer).
            while (!drainReadyOutput(10)) { /* keep blocking-drain until EOS */ }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }

        return if (outFormat != null && frames.isNotEmpty()) Pair(outFormat, frames) else null
    }
}
