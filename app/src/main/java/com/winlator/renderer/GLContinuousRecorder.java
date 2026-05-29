package com.winlator.renderer;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Rolling "instant replay" recorder for the OpenGL (VirGL) renderer.
 *
 * <p>Instead of reading pixels back to the CPU every frame (as the screenshot path does via
 * {@code glReadPixels}), this feeds a hardware H.264 encoder whose input is a GL surface. The
 * only per-frame work on the GL thread is a GPU-side texture copy of the back buffer plus a
 * single full-screen quad draw into the encoder's surface — compression happens asynchronously
 * on the device's dedicated video-encode block, so the overhead is small.</p>
 *
 * <p>Encoded access units are kept in an in-memory ring buffer trimmed to the configured
 * number of seconds. On {@link #snapshot()} the current window is copied out (keyframe-aligned)
 * so it can be muxed to an mp4 off-thread.</p>
 *
 * <p>Threading: every method whose name ends in {@code GL} (and {@link #start}, {@link #stop},
 * {@link #onFrameRendered}) must run on the GLSurfaceView's GL thread, where the renderer's
 * EGL context is current. {@link #snapshot()} is safe to call from any thread.</p>
 */
public class GLContinuousRecorder {
    private static final String TAG = "GLContinuousRecorder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC; // "video/avc"
    // EGL extension attribute that marks a surface as encoder-consumable. Not in EGL14 constants.
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    /** One encoded access unit (NAL units for a single frame). */
    public static final class EncodedSample {
        public final byte[] data;
        public final long ptsUs;
        public final boolean keyframe;
        EncodedSample(byte[] data, long ptsUs, boolean keyframe) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.keyframe = keyframe;
        }
    }

    /** Immutable copy of the ring buffer plus the codec config, handed to the muxer. */
    public static final class ClipSnapshot {
        public final MediaFormat format;            // carries csd-0/csd-1 (SPS/PPS)
        public final ArrayList<EncodedSample> samples;
        ClipSnapshot(MediaFormat format, ArrayList<EncodedSample> samples) {
            this.format = format;
            this.samples = samples;
        }
    }

    private final int bufferSeconds;
    private final int bitrate;

    private volatile boolean recording = false;

    // Encoder + its input surface (GL-side target).
    private MediaCodec encoder;
    private Surface inputSurface;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private MediaFormat outputFormat; // set on INFO_OUTPUT_FORMAT_CHANGED

    // EGL: we reuse the renderer's existing display/context and create a second window surface
    // backed by the encoder's input Surface. We never create our own context, so the capture
    // texture and shader program (created in the renderer's context) are usable as-is.
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface encoderEglSurface = EGL14.EGL_NO_SURFACE;

    // GL objects for the blit pass.
    private int program = 0;
    private int aPosLoc = -1;
    private int aTexLoc = -1;
    private int uTexLoc = -1;
    private int vbo = 0;
    private int captureTex = 0;
    private int captureTexW = 0;
    private int captureTexH = 0;
    private int encW = 0;
    private int encH = 0;

    // Ring buffer of encoded samples. Guarded by ringLock.
    private final Object ringLock = new Object();
    private final ArrayDeque<EncodedSample> ring = new ArrayDeque<>();

    private static final float[] QUAD = {
        // x,    y,    u,   v   (triangle strip; v maps GL bottom-left texture origin upright)
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f,
    };

    public GLContinuousRecorder(int bufferSeconds, int bitrateMbps) {
        this.bufferSeconds = Math.max(1, bufferSeconds);
        this.bitrate = Math.max(1, bitrateMbps) * 1_000_000;
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * Initialise the encoder + EGL surface + GL objects for the given output size. Must be
     * called on the GL thread with the renderer's context current. Idempotent: a prior session
     * is torn down first, so it doubles as the "context recreated" rebuild path.
     */
    public void start(int width, int height) {
        stop(); // release any previous generation (also clears the ring)
        if (width <= 0 || height <= 0) return;

        encW = width & ~1;   // H.264 requires even dimensions
        encH = height & ~1;
        if (encW <= 0 || encH <= 0) return;

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

            setupEgl();
            setupGlObjects();
            recording = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start replay recorder: " + e);
            stop();
        }
    }

    /** Tear down everything. Must run on the GL thread (releases GL/EGL objects). */
    public void stop() {
        recording = false;
        releaseGlObjects();
        releaseEgl();
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

    /**
     * Called once per rendered frame at the end of onDrawFrame, while the window surface is
     * current and FBO 0 holds the freshly rendered (not-yet-swapped) image.
     */
    public void onFrameRendered(int width, int height) {
        if (!recording || encoder == null) return;
        try {
            int w = Math.min(width & ~1, encW);
            int h = Math.min(height & ~1, encH);
            if (w <= 0 || h <= 0) return;

            // Remember what the renderer had current so we can restore it before returning.
            EGLSurface prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
            EGLSurface prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
            // Viewport is context-global state; the renderer only re-applies it on change, so we
            // must put it back exactly or subsequent frames render into the wrong rectangle.
            int[] prevViewport = new int[4];
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0);

            // 1. GPU-side copy of the window back buffer (FBO 0) into our capture texture.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captureTex);
            if (captureTexW != w || captureTexH != h) {
                // (Re)define the texture image at the new size and copy in one call.
                GLES20.glCopyTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 0, 0, w, h, 0);
                captureTexW = w;
                captureTexH = h;
            } else {
                GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
            }

            // 2. Make the encoder surface current and blit the capture texture into it.
            EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext);
            GLES20.glViewport(0, 0, encW, encH);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(program);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
            GLES20.glEnableVertexAttribArray(aPosLoc);
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, 0);
            GLES20.glEnableVertexAttribArray(aTexLoc);
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, 8);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captureTex);
            GLES20.glUniform1i(uTexLoc, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPosLoc);
            GLES20.glDisableVertexAttribArray(aTexLoc);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            // 3. Stamp the frame with a wall-clock presentation time and submit to the encoder.
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, System.nanoTime());
            EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface);

            // 4. Restore the renderer's surface, viewport, and blend state.
            EGL14.eglMakeCurrent(eglDisplay, prevDraw, prevRead, eglContext);
            GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            GLES20.glEnable(GLES20.GL_BLEND);

            // 5. Pull whatever the encoder has produced into the ring buffer.
            drainEncoder();
        } catch (Exception e) {
            Log.e(TAG, "onFrameRendered failed, stopping recorder: " + e);
            recording = false;
        }
    }

    /** Copy the current keyframe-aligned window out for muxing. Safe from any thread. */
    public ClipSnapshot snapshot() {
        synchronized (ringLock) {
            if (outputFormat == null || ring.isEmpty()) return null;
            ArrayList<EncodedSample> copy = new ArrayList<>(ring);
            // Drop leading non-keyframes: a clip that begins mid-GOP plays back corrupt.
            int firstKey = 0;
            while (firstKey < copy.size() && !copy.get(firstKey).keyframe) firstKey++;
            if (firstKey >= copy.size()) return null;
            ArrayList<EncodedSample> trimmed = new ArrayList<>(copy.subList(firstKey, copy.size()));
            return new ClipSnapshot(outputFormat, trimmed);
        }
    }

    // --- encoder draining + ring buffer ------------------------------------------------------

    private void drainEncoder() {
        if (encoder == null) return;
        while (true) {
            int idx = encoder.dequeueOutputBuffer(bufferInfo, 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = encoder.getOutputFormat();
            } else if (idx >= 0) {
                ByteBuffer buf = encoder.getOutputBuffer(idx);
                boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (buf != null && bufferInfo.size > 0 && !isConfig) {
                    buf.position(bufferInfo.offset);
                    buf.limit(bufferInfo.offset + bufferInfo.size);
                    byte[] data = new byte[bufferInfo.size];
                    buf.get(data);
                    boolean key = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    addSample(new EncodedSample(data, bufferInfo.presentationTimeUs, key));
                }
                encoder.releaseOutputBuffer(idx, false);
            }
        }
    }

    private void addSample(EncodedSample sample) {
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
                EncodedSample first = ring.peekFirst();
                // Only drop if there's still a keyframe within the window after it.
                if (newest - first.ptsUs > windowUs) ring.removeFirst();
                else break;
            }
        }
    }

    // --- EGL / GL setup ----------------------------------------------------------------------

    private void setupEgl() {
        eglDisplay = EGL14.eglGetCurrentDisplay();
        eglContext = EGL14.eglGetCurrentContext();
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("No current EGL display/context on GL thread");
        }

        int[] attribs = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            throw new IllegalStateException("Unable to find a recordable EGL config");
        }
        eglConfig = configs[0];

        int[] surfaceAttribs = { EGL14.EGL_NONE };
        encoderEglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, inputSurface, surfaceAttribs, 0);
        if (encoderEglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("eglCreateWindowSurface failed: 0x"
                + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    private void setupGlObjects() {
        program = buildProgram();
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos");
        aTexLoc = GLES20.glGetAttribLocation(program, "aTex");
        uTexLoc = GLES20.glGetUniformLocation(program, "uTex");

        FloatBuffer fb = ByteBuffer.allocateDirect(QUAD.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(QUAD).position(0);
        int[] ids = new int[1];
        GLES20.glGenBuffers(1, ids, 0);
        vbo = ids[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, QUAD.length * 4, fb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        GLES20.glGenTextures(1, ids, 0);
        captureTex = ids[0];
        captureTexW = 0;
        captureTexH = 0;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captureTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private static int buildProgram() {
        String vs =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = aTex;\n" +
            "}\n";
        String fs =
            "precision mediump float;\n" +
            "uniform sampler2D uTex;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTex, vTex);\n" +
            "}\n";
        int vsId = compile(GLES20.GL_VERTEX_SHADER, vs);
        int fsId = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vsId);
        GLES20.glAttachShader(prog, fsId);
        GLES20.glLinkProgram(prog);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            throw new RuntimeException("Recorder program link failed: " + log);
        }
        GLES20.glDeleteShader(vsId);
        GLES20.glDeleteShader(fsId);
        return prog;
    }

    private static int compile(int type, String src) {
        int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, src);
        GLES20.glCompileShader(id);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(id);
            GLES20.glDeleteShader(id);
            throw new RuntimeException("Recorder shader compile failed: " + log);
        }
        return id;
    }

    private void releaseGlObjects() {
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0; }
        if (vbo != 0) { GLES20.glDeleteBuffers(1, new int[]{vbo}, 0); vbo = 0; }
        if (captureTex != 0) { GLES20.glDeleteTextures(1, new int[]{captureTex}, 0); captureTex = 0; }
        captureTexW = 0;
        captureTexH = 0;
        aPosLoc = aTexLoc = uTexLoc = -1;
    }

    private void releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface);
        }
        encoderEglSurface = EGL14.EGL_NO_SURFACE;
        eglConfig = null;
        // We do not own the display/context (the renderer does); just drop our references.
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
    }
}
