package app.gamenative.utils

import android.util.Log
import com.winlator.alsaserver.ALSAClient
import com.winlator.alsaserver.AudioTap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

private const val TAG = "ReplayAudio"

/**
 * JNI bridge to libpulse_capture.so (records a PulseAudio sink's monitor source). See
 * app/src/main/cpp/pulsecapture/. All methods touch one native handle returned by [nativeStart].
 */
private object PulseMonitorCapture {
    /** True if libpulse_capture.so loaded; false means the native calls are unavailable. */
    val available: Boolean = try {
        System.loadLibrary("pulse_capture")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "pulse_capture failed to load", t)
        false
    }

    /** Connects to [server] and starts recording [monitor]; returns a handle, or 0 on failure. */
    external fun nativeStart(server: String, monitor: String, rate: Int, channels: Int): Long

    /** Blocks up to ~200ms for one PCM chunk into [dst]; returns bytes written, 0 timeout, -1 stopped. */
    external fun nativeRead(handle: Long, dst: ByteBuffer): Int

    /** Monotonic-nanos timestamp of the chunk returned by the most recent [nativeRead]. */
    external fun nativeLastTimestampNanos(handle: Long): Long

    /** Unblocks a pending [nativeRead] so the reader thread can exit; must precede [nativeStop]. */
    external fun nativeRequestStop(handle: Long)

    /** Frees native state. Call only after [nativeRequestStop] and joining the reader thread. */
    external fun nativeStop(handle: Long)
}

/**
 * Rolling audio buffer for replay clips. Holds raw S16LE PCM (cheap enough to keep uncompressed —
 * ~5.8 MB for 30s stereo @48kHz — and AAC-encode only at save time, in [RecordingUtils]).
 *
 * Driver-aware: ALSA containers feed PCM through [AudioTap]; PulseAudio (the default) is captured
 * via the native libpulse monitor client. Either way the ring stores S16LE chunks tagged with a
 * `System.nanoTime()`-comparable timestamp so audio lines up with the video buffer's frame pts.
 */
class AudioReplayRecorder(private val bufferSeconds: Int) {

    /** One PCM fragment; [ptsNanos] is comparable to System.nanoTime() / the video frame pts. */
    class PcmChunk(val data: ByteArray, val ptsNanos: Long)

    /** Immutable copy of the windowed PCM plus its format, handed to the muxer. */
    class PcmSnapshot(val sampleRate: Int, val channels: Int, val samples: List<PcmChunk>)

    private val lock = Any()
    private val ring = ArrayDeque<PcmChunk>()
    @Volatile private var running = false

    // Format of the PCM in the ring. PulseAudio is fixed at start; ALSA is learned from the tap.
    @Volatile private var sampleRate = 48000
    @Volatile private var channels = 2

    // PulseAudio path state.
    private var nativeHandle = 0L
    private var readerThread: Thread? = null

    // ALSA path state.
    private var alsaListener: AudioTap.Listener? = null

    /** Start capturing the PulseAudio sink monitor. [server] is a libpulse address (e.g. "unix:/path"). */
    fun startPulseMonitor(server: String, rate: Int, channels: Int): Boolean {
        if (!PulseMonitorCapture.available) {
            Log.w(TAG, "startPulseMonitor: native lib unavailable")
            return false
        }
        val handle = try {
            PulseMonitorCapture.nativeStart(server, "AAudioSink.monitor", rate, channels)
        } catch (e: Throwable) {
            Log.e(TAG, "startPulseMonitor: nativeStart threw (server=$server)", e)
            return false
        }
        if (handle == 0L) return false
        this.sampleRate = rate
        this.channels = channels
        this.nativeHandle = handle
        running = true
        readerThread = thread(name = "ReplayAudioReader") {
            // Direct buffer the native side fills; 64 KB comfortably exceeds one ~40ms fragment.
            val buf = ByteBuffer.allocateDirect(64 * 1024)
            while (running) {
                val n = PulseMonitorCapture.nativeRead(handle, buf)
                if (n < 0) break          // stopped
                if (n == 0) continue      // timeout, no data
                val arr = ByteArray(n)
                buf.position(0)
                buf.get(arr, 0, n)
                append(arr, PulseMonitorCapture.nativeLastTimestampNanos(handle))
            }
        }
        return true
    }

    /** Start capturing ALSA PCM via the Java tap. Format is learned from the first delivered chunk. */
    fun startAlsaTee() {
        running = true
        val listener = AudioTap.Listener { data, dataType, ch, sr ->
            if (!running) return@Listener
            sampleRate = sr
            channels = ch
            append(convertToS16LE(data, dataType), System.nanoTime())
        }
        alsaListener = listener
        AudioTap.set(listener)
    }

    fun stop() {
        running = false
        if (alsaListener != null) {
            AudioTap.clear()
            alsaListener = null
        }
        val handle = nativeHandle
        if (handle != 0L) {
            // Unblock the reader (it waits inside nativeRead until stopping is set), join it, and
            // only then free native state — nativeStop assumes no nativeRead is in flight.
            PulseMonitorCapture.nativeRequestStop(handle)
            readerThread?.join(1000)
            readerThread = null
            PulseMonitorCapture.nativeStop(handle)
            nativeHandle = 0L
        }
    }

    /** Thread-safe copy of the last [bufferSeconds] of PCM, or null if empty. */
    fun snapshot(): PcmSnapshot? = synchronized(lock) {
        if (ring.isEmpty()) {
            Log.w(TAG, "snapshot: ring is empty (no audio captured)")
            return null
        }
        PcmSnapshot(sampleRate, channels, ArrayList(ring))
    }

    private fun append(data: ByteArray, ptsNanos: Long) = synchronized(lock) {
        ring.addLast(PcmChunk(data, ptsNanos))
        val windowNs = bufferSeconds * 1_000_000_000L
        val newest = ptsNanos
        while (ring.size > 1 && newest - ring.first().ptsNanos > windowNs) {
            ring.removeFirst()
        }
    }

    /** Convert any ALSA PCM format to S16LE so the ring (and the AAC encoder) see one layout. */
    private fun convertToS16LE(src: ByteBuffer, dt: ALSAClient.DataType): ByteArray {
        val b = src.duplicate()
        b.position(0)
        val limit = b.limit()
        return when (dt) {
            ALSAClient.DataType.S16LE -> {
                b.order(ByteOrder.LITTLE_ENDIAN)
                ByteArray(limit).also { b.get(it) }
            }
            ALSAClient.DataType.S16BE -> {
                b.order(ByteOrder.BIG_ENDIAN)
                val n = limit / 2
                val out = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) out.putShort(b.short)
                out.array()
            }
            ALSAClient.DataType.U8 -> {
                // Unsigned 8-bit (bias 128) → signed 16-bit (shift into the high byte).
                val out = ByteBuffer.allocate(limit * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until limit) {
                    val u = b.get().toInt() and 0xFF
                    out.putShort(((u - 128) shl 8).toShort())
                }
                out.array()
            }
            ALSAClient.DataType.FLOATLE, ALSAClient.DataType.FLOATBE -> {
                b.order(if (dt == ALSAClient.DataType.FLOATLE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                val n = limit / 4
                val out = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) {
                    val f = b.float.coerceIn(-1f, 1f)
                    out.putShort((f * 32767f).toInt().toShort())
                }
                out.array()
            }
        }
    }
}
