package com.winlator.renderer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Rolling "instant replay" recorder for the Vulkan renderer.
 *
 * <p>This is the Vulkan counterpart of {@link GLContinuousRecorder}. The per-frame frame capture
 * itself happens in native code: the encoder's input {@link Surface} is wrapped as a second Vulkan
 * swapchain and each composited frame is blitted into it on the GPU (see
 * {@code VulkanRendererContext::recordAndPresent}). This class only owns the {@link MediaCodec}
 * encoder, hands its input Surface to native via {@code nativeStartRecording}, and drains the
 * encoded H.264 access units into an in-memory ring buffer on a background thread.</p>
 *
 * <p>Because compression runs on the device's dedicated video-encode block and the only per-frame
 * GPU work is a single blit + present, the overhead is small. The ring buffer / {@link #snapshot()}
 * logic mirrors {@link GLContinuousRecorder} and reuses its {@link GLContinuousRecorder.EncodedSample}
 * and {@link GLContinuousRecorder.ClipSnapshot} types so {@code RecordingUtils.saveClip} works
 * unchanged across both renderers.</p>
 *
 * <p>Threading: {@link #start} and {@link #stop} are called from the UI thread; draining runs on a
 * dedicated thread; {@link #snapshot()} is safe from any thread.</p>
 */
public class VulkanContinuousRecorder {
    private static final String TAG = "VkContinuousRecorder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC; // "video/avc"

    private final int bufferSeconds;
    private final int bitrate;

    private volatile boolean recording = false;

    private MediaCodec encoder;
    private Surface inputSurface;
    private Thread drainThread;
    private volatile MediaFormat outputFormat; // set on INFO_OUTPUT_FORMAT_CHANGED

    // Ring buffer of encoded samples. Guarded by ringLock.
    private final Object ringLock = new Object();
    private final ArrayDeque<GLContinuousRecorder.EncodedSample> ring = new ArrayDeque<>();

    public VulkanContinuousRecorder(int bufferSeconds, int bitrateMbps) {
        this.bufferSeconds = Math.max(1, bufferSeconds);
        this.bitrate = Math.max(1, bitrateMbps) * 1_000_000;
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * Create + start the encoder for the given output size and return its input {@link Surface},
     * which the caller must hand to native ({@code nativeStartRecording}) so the renderer can
     * present frames into it. Returns null on failure. Must be called from a single thread; a prior
     * session is torn down first.
     */
    public Surface start(int width, int height) {
        stop(); // release any previous generation (also clears the ring)
        int encW = width & ~1;   // H.264 requires even dimensions
        int encH = height & ~1;
        if (encW <= 0 || encH <= 0) return null;

        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, encW, encH);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            // Nominal frame rate; actual cadence is driven by presentation timestamps (VFR).
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            // 1s GOP so the ring buffer can always be trimmed to a keyframe boundary.
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(MIME);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();

            recording = true;
            drainThread = new Thread(this::drainLoop, "VkReplayDrain");
            drainThread.start();
            return inputSurface;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start replay recorder: " + e);
            stop();
            return null;
        }
    }

    /**
     * Tear everything down. Safe to call from the UI thread. The caller MUST ensure native has
     * already released the encoder Surface (the native swapchain) before this runs — i.e. call
     * {@code nativeStopRecording} (which tears the encoder swapchain down synchronously) first.
     */
    public void stop() {
        recording = false;
        Thread t = drainThread;
        drainThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (encoder != null) {
            try { encoder.stop(); } catch (Exception ignored) {}
            try { encoder.release(); } catch (Exception ignored) {}
            encoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        outputFormat = null;
        synchronized (ringLock) { ring.clear(); }
    }

    /** Copy the current keyframe-aligned window out for muxing. Safe from any thread. */
    public GLContinuousRecorder.ClipSnapshot snapshot() {
        synchronized (ringLock) {
            if (outputFormat == null || ring.isEmpty()) return null;
            ArrayList<GLContinuousRecorder.EncodedSample> copy = new ArrayList<>(ring);
            // Drop leading non-keyframes: a clip that begins mid-GOP plays back corrupt.
            int firstKey = 0;
            while (firstKey < copy.size() && !copy.get(firstKey).keyframe) firstKey++;
            if (firstKey >= copy.size()) return null;
            ArrayList<GLContinuousRecorder.EncodedSample> trimmed =
                new ArrayList<>(copy.subList(firstKey, copy.size()));
            return new GLContinuousRecorder.ClipSnapshot(outputFormat, trimmed);
        }
    }

    // --- encoder draining + ring buffer ------------------------------------------------------

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (recording) {
            try {
                int idx = encoder.dequeueOutputBuffer(info, 10_000); // 10 ms
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = encoder.getOutputFormat();
                } else if (idx >= 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(idx);
                    boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (buf != null && info.size > 0 && !isConfig) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        buf.get(data);
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        addSample(new GLContinuousRecorder.EncodedSample(data, info.presentationTimeUs, key));
                    }
                    encoder.releaseOutputBuffer(idx, false);
                }
            } catch (IllegalStateException e) {
                // Encoder stopped/released underneath us during teardown.
                break;
            } catch (Exception e) {
                Log.e(TAG, "drain failed: " + e);
                break;
            }
        }
    }

    private void addSample(GLContinuousRecorder.EncodedSample sample) {
        long windowUs = bufferSeconds * 1_000_000L;
        synchronized (ringLock) {
            ring.addLast(sample);
            long newest = sample.ptsUs;
            // Drop frames older than the window...
            while (ring.size() > 1 && newest - ring.peekFirst().ptsUs > windowUs) {
                ring.removeFirst();
            }
            // ...then drop any leading non-keyframes so the window starts on a sync frame.
            while (ring.size() > 1 && !ring.peekFirst().keyframe) {
                GLContinuousRecorder.EncodedSample first = ring.peekFirst();
                if (newest - first.ptsUs > windowUs) ring.removeFirst();
                else break;
            }
        }
    }
}
