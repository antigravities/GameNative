// pulse_capture.c — capture a PulseAudio sink's "monitor" source for the replay buffer.
//
// Background (for readers new to PulseAudio):
//   * A PulseAudio *sink* is an output device. Our games play into the sink named "AAudioSink"
//     (created by PulseAudioComponent.java). Every sink automatically has a paired *source*
//     called "<sink>.monitor" that emits a copy of everything played to the sink. Recording that
//     monitor source gives us exactly the game audio — without touching the prebuilt daemon and
//     without any Android permission (we're a normal PulseAudio client over the same unix socket
//     that pactl already uses).
//
//   * libpulse's "async" API runs an internal event loop on its own thread (a
//     pa_threaded_mainloop). Rule: any pa_* call must be made while holding the mainloop lock
//     (pa_threaded_mainloop_lock/unlock), EXCEPT inside libpulse's own callbacks, which already
//     run under that lock. To wait for an async state change we use the lock + wait/signal
//     pattern: a state callback calls pa_threaded_mainloop_signal(), and our waiting code sits in
//     pa_threaded_mainloop_wait() (which atomically releases the lock while blocked).
//
// Data flow to Java (pull model, so we never call back *into* the JVM from libpulse's thread):
//   libpulse read callback --enqueue--> bounded native FIFO --nativeRead()--> Java reader thread.
//
// Threading contract: nativeStop() assumes the Java reader thread has already been joined, i.e.
// no nativeRead() runs concurrently with nativeStop(). The Kotlin side guarantees this.

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>

#include "pulse_min.h"

#define LOG_TAG "PulseCapture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Cap the FIFO so a stalled Java reader can't make us grow without bound; on overflow we drop the
// oldest chunk (the rolling buffer tolerates a little loss far better than an OOM).
#define MAX_QUEUED_CHUNKS 256

// One delivered PCM fragment plus the monotonic time it arrived.
typedef struct pcm_chunk {
    uint8_t          *data;
    size_t            len;
    int64_t           ts_ns;   // CLOCK_MONOTONIC nanoseconds (== Android System.nanoTime())
    struct pcm_chunk *next;
} pcm_chunk;

typedef struct {
    pa_threaded_mainloop *ml;
    pa_context           *ctx;
    pa_stream            *stream;

    pthread_mutex_t mtx;       // guards the FIFO + stopping flag
    pthread_cond_t  cond;      // signaled when a chunk is enqueued or we're stopping
    pcm_chunk      *head;      // dequeue end
    pcm_chunk      *tail;      // enqueue end
    int             count;
    int             stopping;

    int64_t last_ts_ns;        // ts of the chunk returned by the most recent nativeRead()
} capture_t;

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// ---- FIFO helpers (caller must hold c->mtx) ---------------------------------------------------

static void fifo_push(capture_t *c, const void *src, size_t len, int64_t ts) {
    pcm_chunk *node = (pcm_chunk *)malloc(sizeof(pcm_chunk));
    if (!node) return;
    node->data = (uint8_t *)malloc(len);
    if (!node->data) { free(node); return; }
    memcpy(node->data, src, len);
    node->len = len;
    node->ts_ns = ts;
    node->next = NULL;

    if (c->tail) c->tail->next = node; else c->head = node;
    c->tail = node;
    c->count++;

    // Overflow: drop the oldest chunk.
    if (c->count > MAX_QUEUED_CHUNKS && c->head) {
        pcm_chunk *old = c->head;
        c->head = old->next;
        if (!c->head) c->tail = NULL;
        c->count--;
        free(old->data);
        free(old);
    }
}

static pcm_chunk *fifo_pop(capture_t *c) {
    pcm_chunk *node = c->head;
    if (!node) return NULL;
    c->head = node->next;
    if (!c->head) c->tail = NULL;
    c->count--;
    return node;
}

static void fifo_clear(capture_t *c) {
    pcm_chunk *n = c->head;
    while (n) { pcm_chunk *next = n->next; free(n->data); free(n); n = next; }
    c->head = c->tail = NULL;
    c->count = 0;
}

// ---- libpulse callbacks (run on the mainloop thread, under its lock) --------------------------

// Wake whatever is sitting in pa_threaded_mainloop_wait() whenever the context state changes.
static void context_state_cb(pa_context *ctx, void *userdata) {
    (void)ctx;
    capture_t *c = (capture_t *)userdata;
    pa_threaded_mainloop_signal(c->ml, 0);
}

static void stream_state_cb(pa_stream *s, void *userdata) {
    (void)s;
    capture_t *c = (capture_t *)userdata;
    pa_threaded_mainloop_signal(c->ml, 0);
}

// Called when the monitor source has PCM ready. Drain all currently-available fragments.
static void stream_read_cb(pa_stream *s, size_t nbytes, void *userdata) {
    (void)nbytes;
    capture_t *c = (capture_t *)userdata;
    for (;;) {
        const void *data = NULL;
        size_t len = 0;
        if (pa_stream_peek(s, &data, &len) < 0) break; // read error
        if (len == 0) break;                            // nothing more buffered
        if (data != NULL) {                             // data==NULL with len>0 means a "hole"
            pthread_mutex_lock(&c->mtx);
            fifo_push(c, data, len, now_ns());
            pthread_cond_signal(&c->cond);
            pthread_mutex_unlock(&c->mtx);
        }
        pa_stream_drop(s);                              // must drop for both real data and holes
    }
}

// ---- Setup / teardown helpers -----------------------------------------------------------------

// Wait until the context reaches READY, or fails. Must be called with the mainloop locked.
static int wait_for_context_ready(capture_t *c) {
    for (;;) {
        pa_context_state_t st = pa_context_get_state(c->ctx);
        if (st == PA_CONTEXT_READY) return 1;
        if (st == PA_CONTEXT_FAILED || st == PA_CONTEXT_TERMINATED) return 0;
        pa_threaded_mainloop_wait(c->ml); // releases the lock while blocked, re-acquires on wake
    }
}

static int wait_for_stream_ready(capture_t *c) {
    for (;;) {
        pa_stream_state_t st = pa_stream_get_state(c->stream);
        if (st == PA_STREAM_READY) return 1;
        if (st == PA_STREAM_FAILED || st == PA_STREAM_TERMINATED) return 0;
        pa_threaded_mainloop_wait(c->ml);
    }
}

static void destroy_capture(capture_t *c) {
    if (!c) return;
    if (c->ml) {
        pa_threaded_mainloop_lock(c->ml);
        if (c->stream) {
            pa_stream_set_read_callback(c->stream, NULL, NULL);
            pa_stream_set_state_callback(c->stream, NULL, NULL);
            pa_stream_disconnect(c->stream);
            pa_stream_unref(c->stream);
            c->stream = NULL;
        }
        if (c->ctx) {
            pa_context_set_state_callback(c->ctx, NULL, NULL);
            pa_context_disconnect(c->ctx);
            pa_context_unref(c->ctx);
            c->ctx = NULL;
        }
        pa_threaded_mainloop_unlock(c->ml);
        pa_threaded_mainloop_stop(c->ml);
        pa_threaded_mainloop_free(c->ml);
        c->ml = NULL;
    }
    fifo_clear(c);
    pthread_mutex_destroy(&c->mtx);
    pthread_cond_destroy(&c->cond);
    free(c);
}

// ---- JNI -------------------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_app_gamenative_utils_PulseMonitorCapture_nativeStart(
        JNIEnv *env, jobject thiz,
        jstring jserver, jstring jmonitor, jint rate, jint channels) {
    (void)thiz;

    const char *server  = (*env)->GetStringUTFChars(env, jserver, NULL);
    const char *monitor = (*env)->GetStringUTFChars(env, jmonitor, NULL);

    capture_t *c = (capture_t *)calloc(1, sizeof(capture_t));
    if (!c) goto fail_strings;
    pthread_mutex_init(&c->mtx, NULL);
    pthread_cond_init(&c->cond, NULL);

    c->ml = pa_threaded_mainloop_new();
    if (!c->ml) { LOGE("mainloop_new failed"); goto fail; }
    if (pa_threaded_mainloop_start(c->ml) < 0) { LOGE("mainloop_start failed"); goto fail; }

    pa_threaded_mainloop_lock(c->ml);

    c->ctx = pa_context_new(pa_threaded_mainloop_get_api(c->ml), "GameNativeReplay");
    if (!c->ctx) { LOGE("context_new failed"); goto fail_locked; }
    pa_context_set_state_callback(c->ctx, context_state_cb, c);
    if (pa_context_connect(c->ctx, server, PA_CONTEXT_NOFLAGS, NULL) < 0) {
        LOGE("context_connect failed"); goto fail_locked;
    }
    if (!wait_for_context_ready(c)) { LOGE("context did not become ready"); goto fail_locked; }

    pa_sample_spec ss;
    ss.format   = PA_SAMPLE_S16LE;
    ss.rate     = (uint32_t)rate;
    ss.channels = (uint8_t)channels;

    // Ask the server for ~40 ms fragments; with ADJUST_LATENCY it tunes the source latency to
    // match. Other fields are playback-only / "server default" (-1).
    pa_buffer_attr attr;
    attr.maxlength = (uint32_t)-1;
    attr.tlength   = (uint32_t)-1;
    attr.prebuf    = (uint32_t)-1;
    attr.minreq    = (uint32_t)-1;
    attr.fragsize  = (uint32_t)(rate * channels * 2 /*bytes/sample*/ * 40 / 1000);

    c->stream = pa_stream_new(c->ctx, "GameNativeReplayMonitor", &ss, NULL);
    if (!c->stream) { LOGE("stream_new failed"); goto fail_locked; }
    pa_stream_set_state_callback(c->stream, stream_state_cb, c);
    pa_stream_set_read_callback(c->stream, stream_read_cb, c);
    if (pa_stream_connect_record(c->stream, monitor, &attr, PA_STREAM_ADJUST_LATENCY) < 0) {
        LOGE("connect_record failed"); goto fail_locked;
    }
    if (!wait_for_stream_ready(c)) { LOGE("stream did not become ready"); goto fail_locked; }

    pa_threaded_mainloop_unlock(c->ml);
    (*env)->ReleaseStringUTFChars(env, jserver, server);
    (*env)->ReleaseStringUTFChars(env, jmonitor, monitor);
    LOGI("monitor capture started: src=%s %dHz %dch", monitor, rate, channels);
    return (jlong)(intptr_t)c;

fail_locked:
    pa_threaded_mainloop_unlock(c->ml);
fail:
    destroy_capture(c);
fail_strings:
    (*env)->ReleaseStringUTFChars(env, jserver, server);
    (*env)->ReleaseStringUTFChars(env, jmonitor, monitor);
    return 0;
}

// Block (up to ~200 ms) for one PCM chunk, copy it into the direct ByteBuffer, and record its
// timestamp for nativeLastTimestampNanos(). Returns bytes written, 0 on timeout, -1 if stopping.
JNIEXPORT jint JNICALL
Java_app_gamenative_utils_PulseMonitorCapture_nativeRead(
        JNIEnv *env, jobject thiz, jlong handle, jobject dst) {
    (void)thiz;
    capture_t *c = (capture_t *)(intptr_t)handle;
    if (!c) return -1;

    uint8_t *out = (uint8_t *)(*env)->GetDirectBufferAddress(env, dst);
    jlong cap    = (*env)->GetDirectBufferCapacity(env, dst);
    if (!out || cap <= 0) return -1;

    pthread_mutex_lock(&c->mtx);
    while (c->head == NULL && !c->stopping) {
        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline); // pthread_cond_timedwait uses CLOCK_REALTIME
        deadline.tv_nsec += 200 * 1000000L;
        if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }
        pthread_cond_timedwait(&c->cond, &c->mtx, &deadline);
    }
    if (c->stopping) { pthread_mutex_unlock(&c->mtx); return -1; }
    pcm_chunk *node = fifo_pop(c);
    pthread_mutex_unlock(&c->mtx);
    if (!node) return 0; // timed out with no data

    size_t n = node->len < (size_t)cap ? node->len : (size_t)cap;
    memcpy(out, node->data, n);
    c->last_ts_ns = node->ts_ns; // single reader thread, so no lock needed
    free(node->data);
    free(node);
    return (jint)n;
}

JNIEXPORT jlong JNICALL
Java_app_gamenative_utils_PulseMonitorCapture_nativeLastTimestampNanos(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    capture_t *c = (capture_t *)(intptr_t)handle;
    return c ? (jlong)c->last_ts_ns : 0;
}

// Wake a blocked nativeRead() so the Java reader thread can return and be joined. This must be
// called BEFORE nativeStop(): nativeRead() blocks until stopping is set, so we have to unblock it
// first, then join, and only then free (in nativeStop) — otherwise nativeStop would free state
// the reader is still touching.
JNIEXPORT void JNICALL
Java_app_gamenative_utils_PulseMonitorCapture_nativeRequestStop(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    capture_t *c = (capture_t *)(intptr_t)handle;
    if (!c) return;
    pthread_mutex_lock(&c->mtx);
    c->stopping = 1;
    pthread_cond_broadcast(&c->cond);
    pthread_mutex_unlock(&c->mtx);
}

// Free everything. The caller must have already called nativeRequestStop() and joined the reader
// thread, so no nativeRead() is in flight.
JNIEXPORT void JNICALL
Java_app_gamenative_utils_PulseMonitorCapture_nativeStop(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    capture_t *c = (capture_t *)(intptr_t)handle;
    if (!c) return;
    destroy_capture(c);
    LOGI("monitor capture stopped");
}
