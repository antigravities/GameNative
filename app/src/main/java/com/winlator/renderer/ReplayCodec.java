package com.winlator.renderer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;

/**
 * Shared encoder factory for the replay recorders ({@link GLContinuousRecorder} and
 * {@link VulkanContinuousRecorder}).
 *
 * <p>Picks the video codec based on {@code PrefManager.replayBufferCodec}: HEVC (H.265) by
 * default — roughly half the file size of H.264 at equal quality, on the same hardware
 * video-encode block — or AVC (H.264) when the user wants maximum playback compatibility.
 * If HEVC is requested but no HEVC encoder is available on the device, it transparently
 * falls back to AVC so recording always works.</p>
 *
 * <p>The returned {@link MediaCodec} is configured but NOT started: the caller still owns
 * {@code createInputSurface()} + {@code start()} (the surface lifetime differs between the
 * GL and Vulkan paths). Both encoders use surface input, so the codec choice never affects
 * the downstream ring buffer / muxing — the muxer keys off the encoder's *output* format,
 * which carries the right codec-specific data (SPS/PPS for AVC, VPS/SPS/PPS for HEVC).</p>
 */
final class ReplayCodec {
    private static final String TAG = "ReplayCodec";

    private ReplayCodec() {}

    /** A configured-but-not-started encoder plus the MIME that was actually selected. */
    static final class Encoder {
        final MediaCodec codec;
        final String mime;
        Encoder(MediaCodec codec, String mime) {
            this.codec = codec;
            this.mime = mime;
        }
    }

    /**
     * Create + configure a surface-input video encoder for the given (even) dimensions and
     * bitrate. Returns null only if even AVC could not be created.
     */
    static Encoder create(int encW, int encH, int bitrate) {
        // Read the user's choice the same way the recorders read the bitrate pref.
        String pref = app.gamenative.PrefManager.INSTANCE.getReplayBufferCodec();
        boolean wantHevc = !"avc".equalsIgnoreCase(pref);

        if (wantHevc) {
            Encoder e = tryCreate(MediaFormat.MIMETYPE_VIDEO_HEVC, encW, encH, bitrate, true);
            if (e != null) return e;
            Log.w(TAG, "HEVC encoder unavailable; falling back to H.264");
        }
        return tryCreate(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH, bitrate, false);
    }

    private static Encoder tryCreate(String mime, int encW, int encH, int bitrate, boolean checkSupport) {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(mime, encW, encH);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            // Nominal frame rate; actual cadence is driven by presentation timestamps (VFR).
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            // 1s GOP so the ring buffer can always be trimmed to a keyframe boundary.
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            // For HEVC, confirm an encoder exists before constructing a doomed codec. (The
            // try/catch below is still the real safety net for devices that misreport.)
            if (checkSupport) {
                MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                if (list.findEncoderForFormat(format) == null) return null;
            }

            MediaCodec encoder = MediaCodec.createEncoderByType(mime);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return new Encoder(encoder, mime);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "Failed to create " + mime + " encoder: " + e);
            return null;
        }
    }
}
