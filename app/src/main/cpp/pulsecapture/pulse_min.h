// pulse_min.h — minimal libpulse (PulseAudio client) declarations.
//
// We link against the prebuilt libpulse.so (v13.0) that already ships in
// app/src/main/jniLibs/<abi>/, but the PulseAudio *headers* are not in this repo. Rather than
// vendor ~20 upstream headers, we declare here only the handful of types/functions our monitor
// recorder uses. Because libpulse is a C library (no name mangling), the linker matches symbols
// by name; our job is only to give the compiler correct *signatures* and ABI-correct structs.
//
// Every value below was verified against pulseaudio-13.0/src/pulse/{sample,def,stream,context,
// thread-mainloop,mainloop-api,channelmap}.h. If the bundled libpulse is ever upgraded, re-check
// the enum ordinals and struct layouts here.
//
// Note on enums: in C an enum has the same size/representation as `int`, and these functions take
// the enums by value. We therefore model them as `int` typedefs plus #define'd constants — this
// is ABI-identical to the real enums and avoids re-listing every enumerator.

#ifndef PULSE_MIN_H
#define PULSE_MIN_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// --- Opaque handle types -----------------------------------------------------------------------
// We only ever hold pointers to these; their internals are private to libpulse.
typedef struct pa_threaded_mainloop pa_threaded_mainloop;
typedef struct pa_mainloop_api      pa_mainloop_api;
typedef struct pa_context           pa_context;
typedef struct pa_stream            pa_stream;
// We always pass NULL for these two, so an incomplete (forward-declared) type is sufficient.
typedef struct pa_channel_map       pa_channel_map;
typedef struct pa_spawn_api         pa_spawn_api;

// --- Enums modeled as int (see note above) -----------------------------------------------------
typedef int pa_sample_format_t;
#define PA_SAMPLE_S16LE        3      // Signed 16-bit PCM, little endian

typedef int pa_context_state_t;
#define PA_CONTEXT_READY       4
#define PA_CONTEXT_FAILED      5
#define PA_CONTEXT_TERMINATED  6

typedef int pa_stream_state_t;
#define PA_STREAM_READY        2
#define PA_STREAM_FAILED       3
#define PA_STREAM_TERMINATED   4

typedef int pa_context_flags_t;
#define PA_CONTEXT_NOFLAGS     0x0000

typedef int pa_stream_flags_t;
#define PA_STREAM_ADJUST_LATENCY 0x2000  // let the server size the fragment for our latency target

// --- Value structs (must be ABI-exact) ---------------------------------------------------------
// pulseaudio-13.0/src/pulse/sample.h
typedef struct pa_sample_spec {
    pa_sample_format_t format;  // one of PA_SAMPLE_*
    uint32_t           rate;    // e.g. 48000
    uint8_t            channels;// 1 = mono, 2 = stereo
} pa_sample_spec;

// pulseaudio-13.0/src/pulse/def.h — five uint32 fields; -1 means "server default".
typedef struct pa_buffer_attr {
    uint32_t maxlength;
    uint32_t tlength;   // playback only
    uint32_t prebuf;    // playback only
    uint32_t minreq;    // playback only
    uint32_t fragsize;  // record only: bytes per delivered fragment
} pa_buffer_attr;

// --- Callback typedefs --------------------------------------------------------------------------
typedef void (*pa_context_notify_cb_t)(pa_context *c, void *userdata);
typedef void (*pa_stream_notify_cb_t)(pa_stream *p, void *userdata);
typedef void (*pa_stream_request_cb_t)(pa_stream *p, size_t nbytes, void *userdata);

// --- Threaded mainloop --------------------------------------------------------------------------
pa_threaded_mainloop *pa_threaded_mainloop_new(void);
void                  pa_threaded_mainloop_free(pa_threaded_mainloop *m);
int                   pa_threaded_mainloop_start(pa_threaded_mainloop *m);
void                  pa_threaded_mainloop_stop(pa_threaded_mainloop *m);
void                  pa_threaded_mainloop_lock(pa_threaded_mainloop *m);
void                  pa_threaded_mainloop_unlock(pa_threaded_mainloop *m);
void                  pa_threaded_mainloop_wait(pa_threaded_mainloop *m);
void                  pa_threaded_mainloop_signal(pa_threaded_mainloop *m, int wait_for_accept);
pa_mainloop_api      *pa_threaded_mainloop_get_api(pa_threaded_mainloop *m);

// --- Context ------------------------------------------------------------------------------------
pa_context        *pa_context_new(pa_mainloop_api *mainloop, const char *name);
void               pa_context_unref(pa_context *c);
void               pa_context_set_state_callback(pa_context *c, pa_context_notify_cb_t cb, void *userdata);
pa_context_state_t pa_context_get_state(const pa_context *c);
int                pa_context_connect(pa_context *c, const char *server, pa_context_flags_t flags, const pa_spawn_api *api);
void               pa_context_disconnect(pa_context *c);

// --- Stream -------------------------------------------------------------------------------------
pa_stream        *pa_stream_new(pa_context *c, const char *name, const pa_sample_spec *ss, const pa_channel_map *map);
void              pa_stream_unref(pa_stream *s);
pa_stream_state_t pa_stream_get_state(const pa_stream *p);
void              pa_stream_set_state_callback(pa_stream *s, pa_stream_notify_cb_t cb, void *userdata);
void              pa_stream_set_read_callback(pa_stream *p, pa_stream_request_cb_t cb, void *userdata);
int               pa_stream_connect_record(pa_stream *s, const char *dev, const pa_buffer_attr *attr, pa_stream_flags_t flags);
int               pa_stream_peek(pa_stream *p, const void **data, size_t *nbytes);
int               pa_stream_drop(pa_stream *p);
int               pa_stream_disconnect(pa_stream *s);

#ifdef __cplusplus
}
#endif

#endif // PULSE_MIN_H
