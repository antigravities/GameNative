package com.winlator.alsaserver;

import java.nio.ByteBuffer;

/**
 * Static tap point for the replay recorder to observe PCM written through {@link ALSAClient}
 * without {@code com.winlator} depending on app-level code. The app registers a {@link Listener};
 * {@link ALSAClient#writeDataToTrack} invokes {@link #onPcm} on each write when one is set.
 *
 * <p>Only relevant when a container uses the ALSA audio driver. The PulseAudio driver bypasses
 * Java entirely and is captured separately (libpulse monitor client).</p>
 */
public final class AudioTap {
    public interface Listener {
        void onPcm(ByteBuffer data, ALSAClient.DataType dataType, int channels, int sampleRate);
    }

    private static volatile Listener listener;

    public static void set(Listener l) { listener = l; }
    public static void clear() { listener = null; }
    public static boolean active() { return listener != null; }

    public static void onPcm(ByteBuffer data, ALSAClient.DataType dataType, int channels, int sampleRate) {
        Listener l = listener;
        if (l != null) l.onPcm(data, dataType, channels, sampleRate);
    }

    private AudioTap() {}
}
