// cowbase — copy-on-write shim for shared-base containers.
//
// When a container is created with the "shared container base" option, its
// C:\Windows\system32 and syswow64 DLLs are symlinks into the shared Wine tree
// (<imagefs>/opt/<proton>/lib/wine/...) instead of private copies, which saves
// ~1.5 GB per container.
//
// A symlink is transparent to writes: open(path, O_WRONLY|O_TRUNC) follows the
// link and truncates the *target*. So without this shim, a game installer
// running vcredist -- or GameNative extracting DXVK -- would overwrite Wine's
// builtin DLL in the shared tree, corrupting it for every container on the
// device, permanently, and surviving deletion of the container that did it.
//
// This library is LD_PRELOADed ahead of libredirect. It interposes the libc
// entry points that can write through a symlink, and when one is about to be
// used to write to a symlink pointing into the shared tree, it first replaces
// that symlink with a private copy of the file. The write then lands in the
// container, and the shared tree is never mutated.
//
// Inert unless COWBASE_ROOTS is set -- see cowbase_init() for the env vars.

#define _GNU_SOURCE

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/sendfile.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define LOG_TAG "cowbase"
#define MAX_ROOTS 8
#define MAX_MARKERS 8
#define MAX_MARKER_LEN 64
#define COPY_BUF_SIZE (128 * 1024)

// ---------------------------------------------------------------------------
// Originals, resolved through the rest of the LD_PRELOAD chain.
//
// RTLD_NEXT means "the next definition of this symbol after me", so our openat
// forwards to libredirect's openat, which forwards to libc's. That keeps the
// chain intact -- we are adding a link to it, not replacing anyone.
// ---------------------------------------------------------------------------

static int (*real_open)(const char *, int, ...);
static int (*real_open64)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);
static int (*real_openat64)(int, const char *, int, ...);
static int (*real_creat)(const char *, mode_t);
static int (*real_creat64)(const char *, mode_t);
static FILE *(*real_fopen)(const char *, const char *);
static FILE *(*real_fopen64)(const char *, const char *);
static int (*real_truncate)(const char *, off_t);
static int (*real_rename)(const char *, const char *);

#define RESOLVE(name)                                                          \
    do {                                                                       \
        if (!real_##name)                                                      \
            real_##name = (typeof(real_##name))dlsym(RTLD_NEXT, #name);        \
    } while (0)

static void resolve_reals(void) {
    RESOLVE(open);
    RESOLVE(open64);
    RESOLVE(openat);
    RESOLVE(openat64);
    RESOLVE(creat);
    RESOLVE(creat64);
    RESOLVE(fopen);
    RESOLVE(fopen64);
    RESOLVE(truncate);
    RESOLVE(rename);
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

static char g_roots[MAX_ROOTS][PATH_MAX];
static int g_root_lens[MAX_ROOTS];
static int g_nroots = 0;

static char g_markers[MAX_MARKERS][MAX_MARKER_LEN];
static int g_nmarkers = 0;

static int g_enabled = 0;
static int g_debug = 0;
static int g_diag_fd = -1;

static pid_t g_pid = 0;
static char g_comm[64] = "?";

// Reentrancy guard. Everything we do internally -- the copy itself, liblog's
// first-use open of /dev/socket/logdw, writing the diag file -- goes through
// open()/openat(), which are the very functions we interpose. Without this we
// would recurse into ourselves on the first copy-up.
static __thread int in_cowbase = 0;

// ---------------------------------------------------------------------------
// Logging. Callers must already hold the reentrancy guard.
//
// liblog, not stdout: the Wine process's stdout is only drained when something
// has registered a debug callback (ProcessHelper.createDebugThread), so lines
// written there are not reliably visible. logcat always is -- `adb logcat -s cowbase`.
// ---------------------------------------------------------------------------

static void cowbase_log(int prio, const char *fmt, ...) {
    char buf[PATH_MAX * 2 + 256];
    va_list ap;

    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);

    __android_log_write(prio, LOG_TAG, buf);

    if (g_diag_fd >= 0) {
        char line[sizeof(buf) + 64];
        int n = snprintf(line, sizeof(line), "%s\n", buf);
        if (n > 0) {
            ssize_t ignored = write(g_diag_fd, line, (size_t)n);
            (void)ignored;
        }
    }
}

// ---------------------------------------------------------------------------
// Config parsing
// ---------------------------------------------------------------------------

// Splits a colon-separated env var into a fixed-size table. Returns the count.
static int split_list(const char *value, char table[][PATH_MAX], int *lens, int max_items,
                      size_t max_item_len) {
    int count = 0;
    const char *p = value;

    while (*p && count < max_items) {
        const char *sep = strchr(p, ':');
        size_t len = sep ? (size_t)(sep - p) : strlen(p);

        if (len > 0 && len < max_item_len) {
            memcpy(table[count], p, len);
            table[count][len] = '\0';
            // Trailing slashes would break the prefix comparison below.
            while (len > 1 && table[count][len - 1] == '/') {
                table[count][--len] = '\0';
            }
            if (lens) lens[count] = (int)len;
            count++;
        }

        if (!sep) break;
        p = sep + 1;
    }

    return count;
}

static void cache_process_identity(void) {
    g_pid = getpid();

    // Read once, here, and cache it: doing this lazily inside a hook would be
    // another reentrant open() on every copy-up.
    int fd = real_open ? real_open("/proc/self/comm", O_RDONLY) : -1;
    if (fd >= 0) {
        ssize_t n = read(fd, g_comm, sizeof(g_comm) - 1);
        close(fd);
        if (n > 0) {
            g_comm[n] = '\0';
            char *nl = strchr(g_comm, '\n');
            if (nl) *nl = '\0';
        }
    }
}

__attribute__((constructor)) static void cowbase_init(void) {
    resolve_reals();

    const char *roots = getenv("COWBASE_ROOTS");
    if (!roots || !*roots) {
        // No roots configured: every hook below becomes a straight pass-through.
        return;
    }

    g_nroots = split_list(roots, g_roots, g_root_lens, MAX_ROOTS, PATH_MAX);
    if (g_nroots == 0) return;

    const char *markers = getenv("COWBASE_MARKERS");
    if (!markers || !*markers) markers = "/windows/system32/:/windows/syswow64/";
    {
        // Markers live in a narrower table; reuse split_list via a temp.
        static char tmp[MAX_MARKERS][PATH_MAX];
        int n = split_list(markers, tmp, NULL, MAX_MARKERS, MAX_MARKER_LEN);
        for (int i = 0; i < n; i++) {
            strncpy(g_markers[i], tmp[i], MAX_MARKER_LEN - 1);
            g_markers[i][MAX_MARKER_LEN - 1] = '\0';
        }
        g_nmarkers = n;
    }

    const char *dbg = getenv("COWBASE_DEBUG");
    g_debug = dbg && strchr("1yY", *dbg) != NULL;

    in_cowbase = 1;
    cache_process_identity();

    const char *diag = getenv("COWBASE_DIAG");
    if (diag && *diag && real_open) {
        g_diag_fd = real_open(diag, O_WRONLY | O_CREAT | O_APPEND, 0644);
    }

    g_enabled = 1;

    cowbase_log(ANDROID_LOG_INFO, "cowbase: armed for %s[%d] (%d root(s), first=%s)", g_comm,
                (int)g_pid, g_nroots, g_roots[0]);
    in_cowbase = 0;
}

// ---------------------------------------------------------------------------
// Matching
// ---------------------------------------------------------------------------

static int wants_write(int flags) {
    return (flags & O_ACCMODE) != O_RDONLY || (flags & (O_TRUNC | O_CREAT)) != 0;
}

static int mode_wants_write(const char *mode) {
    if (!mode) return 0;
    return strchr(mode, 'w') || strchr(mode, 'a') || strchr(mode, '+');
}

// Cheap pre-filter so the common case (a read, or a write somewhere unrelated)
// costs one substring scan and nothing else. Case-insensitive because Wine
// resolves Windows paths case-insensitively.
static int path_is_candidate(const char *path) {
    for (int i = 0; i < g_nmarkers; i++) {
        if (strcasestr(path, g_markers[i])) return 1;
    }
    return 0;
}

static int target_in_roots(const char *target) {
    for (int i = 0; i < g_nroots; i++) {
        int len = g_root_lens[i];
        if (strncmp(target, g_roots[i], (size_t)len) == 0 &&
            (target[len] == '/' || target[len] == '\0')) {
            return 1;
        }
    }
    return 0;
}

// ---------------------------------------------------------------------------
// Copy-up
// ---------------------------------------------------------------------------

static void format_size(off_t bytes, char *out, size_t out_len) {
    if (bytes >= 1024 * 1024) {
        snprintf(out, out_len, "%.1f MB", (double)bytes / (1024.0 * 1024.0));
    } else {
        snprintf(out, out_len, "%lld KB", (long long)(bytes / 1024));
    }
}

static int copy_contents(int src_fd, int dst_fd, off_t size) {
    // sendfile does the copy in the kernel; fall back to read/write if the
    // filesystem doesn't support it.
    off_t remaining = size;
    while (remaining > 0) {
        ssize_t n = sendfile(dst_fd, src_fd, NULL, (size_t)remaining);
        if (n > 0) {
            remaining -= n;
            continue;
        }
        if (n == 0) break;
        if (errno == EINTR) continue;
        break;
    }
    if (remaining == 0) return 0;

    if (lseek(src_fd, 0, SEEK_SET) == (off_t)-1) return -1;
    if (lseek(dst_fd, 0, SEEK_SET) == (off_t)-1) return -1;
    if (ftruncate(dst_fd, 0) != 0) return -1;

    char *buf = malloc(COPY_BUF_SIZE);
    if (!buf) return -1;

    int rc = 0;
    for (;;) {
        ssize_t n = read(src_fd, buf, COPY_BUF_SIZE);
        if (n == 0) break;
        if (n < 0) {
            if (errno == EINTR) continue;
            rc = -1;
            break;
        }
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(dst_fd, buf + off, (size_t)(n - off));
            if (w < 0) {
                if (errno == EINTR) continue;
                rc = -1;
                break;
            }
            off += w;
        }
        if (rc != 0) break;
    }

    free(buf);
    return rc;
}

// Replaces the symlink at `path` with a private regular file holding the
// target's contents. Returns 1 if a copy-up happened, 0 if nothing was needed,
// -1 on failure.
static int do_copy_up(const char *path, int skip_contents, int flags) {
    char link_target[PATH_MAX];
    ssize_t n = readlink(path, link_target, sizeof(link_target) - 1);
    if (n < 0) return 0;  // EINVAL == not a symlink: already private, nothing to do.
    link_target[n] = '\0';

    char resolved[PATH_MAX];
    if (link_target[0] == '/') {
        snprintf(resolved, sizeof(resolved), "%s", link_target);
    } else {
        // Relative link: resolve against the link's own directory.
        const char *slash = strrchr(path, '/');
        if (!slash) return 0;
        int dir_len = (int)(slash - path);
        snprintf(resolved, sizeof(resolved), "%.*s/%s", dir_len, path, link_target);
    }

    if (!target_in_roots(resolved)) return 0;

    struct stat st;
    if (stat(resolved, &st) != 0) {
        cowbase_log(ANDROID_LOG_ERROR, "cowbase: copy-up FAILED %s: target %s unreadable: %s", path,
                    resolved, strerror(errno));
        return -1;
    }

    const char *slash = strrchr(path, '/');
    if (!slash) return 0;
    int dir_len = (int)(slash - path);

    // Temp file in the same directory, so the rename below is same-filesystem and therefore
    // atomic. The name carries pid *and* tid: two threads copying up the same DLL at once would
    // otherwise share a temp path and interleave their writes into it.
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%.*s/.cowbase-%s-%d-%d", dir_len, path, slash + 1, (int)g_pid,
             (int)gettid());

    int dst_fd = real_open(tmp, O_WRONLY | O_CREAT | O_TRUNC, st.st_mode & 07777);
    if (dst_fd < 0) {
        cowbase_log(ANDROID_LOG_ERROR,
                    "cowbase: copy-up FAILED %s: cannot create %s: %s -- write will reach the "
                    "shared file",
                    path, tmp, strerror(errno));
        return -1;
    }

    int rc = 0;
    if (!skip_contents) {
        int src_fd = real_open(resolved, O_RDONLY);
        if (src_fd < 0) {
            rc = -1;
        } else {
            rc = copy_contents(src_fd, dst_fd, st.st_size);
            close(src_fd);
        }
    }

    if (rc == 0) fchmod(dst_fd, st.st_mode & 07777);
    close(dst_fd);

    if (rc != 0) {
        unlink(tmp);
        cowbase_log(ANDROID_LOG_ERROR,
                    "cowbase: copy-up FAILED %s: %s -- write will reach the shared file", path,
                    strerror(errno));
        return -1;
    }

    // rename(2) does not follow a symlink at the destination, so this replaces
    // the link itself, atomically. Racing processes both succeed; last one wins
    // with identical content.
    if (real_rename(tmp, path) != 0) {
        unlink(tmp);
        cowbase_log(ANDROID_LOG_ERROR,
                    "cowbase: copy-up FAILED %s: rename: %s -- write will reach the shared file",
                    path, strerror(errno));
        return -1;
    }

    char size_str[32];
    format_size(skip_contents ? 0 : st.st_size, size_str, sizeof(size_str));
    cowbase_log(ANDROID_LOG_INFO, "cowbase: copy-up %s <- %s (%s%s, flags=0x%x, by=%s[%d])", path,
                resolved, size_str, skip_contents ? ", truncating" : "", flags, g_comm, (int)g_pid);
    return 1;
}

static void maybe_copy_up(const char *path, int flags) {
    if (!g_enabled || !path || path[0] != '/') return;
    if (!wants_write(flags)) return;
    if (!path_is_candidate(path)) return;
    if (in_cowbase) return;

    in_cowbase = 1;
    // O_TRUNC means the caller is about to zero the file anyway, so there is no
    // point reading the original contents -- just materialize an empty file.
    int did = do_copy_up(path, (flags & O_TRUNC) != 0, flags);
    if (did == 0 && g_debug) {
        cowbase_log(ANDROID_LOG_DEBUG, "cowbase: skip %s (not a shared-base symlink, flags=0x%x)",
                    path, flags);
    }
    in_cowbase = 0;
}

// ---------------------------------------------------------------------------
// Interposed entry points.
//
// visibility("default") is mandatory: the module is built with
// -fvisibility=hidden (house convention), and a hidden symbol does not
// interpose -- the library would load cleanly and silently do nothing.
//
// Both open() and openat() are hooked. Bionic's internal open -> openat funnel
// is not a PLT call, so interposing one does not catch callers of the other.
//
// Paths that are not absolute are passed straight through: resolving a
// dirfd-relative path would mean reading /proc/self/fd (another reentrant
// open), and Wine builds absolute unix paths for prefix files.
// ---------------------------------------------------------------------------

#define VARARG_MODE(flags)                                                     \
    ({                                                                         \
        mode_t _m = 0;                                                         \
        if ((flags) & O_CREAT) {                                               \
            va_list _ap;                                                       \
            va_start(_ap, flags);                                              \
            _m = (mode_t)va_arg(_ap, unsigned int);                            \
            va_end(_ap);                                                       \
        }                                                                      \
        _m;                                                                    \
    })

__attribute__((visibility("default"))) int open(const char *pathname, int flags, ...) {
    mode_t mode = VARARG_MODE(flags);
    RESOLVE(open);
    maybe_copy_up(pathname, flags);
    return real_open(pathname, flags, mode);
}

__attribute__((visibility("default"))) int open64(const char *pathname, int flags, ...) {
    mode_t mode = VARARG_MODE(flags);
    RESOLVE(open64);
    RESOLVE(open);
    maybe_copy_up(pathname, flags);
    if (real_open64) return real_open64(pathname, flags, mode);
    return real_open(pathname, flags, mode);
}

__attribute__((visibility("default"))) int openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = VARARG_MODE(flags);
    RESOLVE(openat);
    maybe_copy_up(pathname, flags);
    return real_openat(dirfd, pathname, flags, mode);
}

__attribute__((visibility("default"))) int openat64(int dirfd, const char *pathname, int flags,
                                                    ...) {
    mode_t mode = VARARG_MODE(flags);
    RESOLVE(openat64);
    RESOLVE(openat);
    maybe_copy_up(pathname, flags);
    if (real_openat64) return real_openat64(dirfd, pathname, flags, mode);
    return real_openat(dirfd, pathname, flags, mode);
}

__attribute__((visibility("default"))) int creat(const char *pathname, mode_t mode) {
    RESOLVE(creat);
    RESOLVE(open);
    maybe_copy_up(pathname, O_WRONLY | O_CREAT | O_TRUNC);
    if (real_creat) return real_creat(pathname, mode);
    return real_open(pathname, O_WRONLY | O_CREAT | O_TRUNC, mode);
}

__attribute__((visibility("default"))) int creat64(const char *pathname, mode_t mode) {
    RESOLVE(creat64);
    RESOLVE(creat);
    RESOLVE(open);
    maybe_copy_up(pathname, O_WRONLY | O_CREAT | O_TRUNC);
    if (real_creat64) return real_creat64(pathname, mode);
    if (real_creat) return real_creat(pathname, mode);
    return real_open(pathname, O_WRONLY | O_CREAT | O_TRUNC, mode);
}

__attribute__((visibility("default"))) FILE *fopen(const char *pathname, const char *mode) {
    RESOLVE(fopen);
    if (mode_wants_write(mode)) {
        // "w" truncates; "a" and "r+" keep the existing contents.
        maybe_copy_up(pathname, (mode && mode[0] == 'w') ? (O_WRONLY | O_CREAT | O_TRUNC)
                                                         : (O_WRONLY | O_CREAT));
    }
    return real_fopen(pathname, mode);
}

__attribute__((visibility("default"))) FILE *fopen64(const char *pathname, const char *mode) {
    RESOLVE(fopen64);
    RESOLVE(fopen);
    if (mode_wants_write(mode)) {
        maybe_copy_up(pathname, (mode && mode[0] == 'w') ? (O_WRONLY | O_CREAT | O_TRUNC)
                                                         : (O_WRONLY | O_CREAT));
    }
    if (real_fopen64) return real_fopen64(pathname, mode);
    return real_fopen(pathname, mode);
}

// truncate() follows symlinks. ftruncate() operates on an already-open fd, so
// the open hook has covered it by then and it needs no interposition.
__attribute__((visibility("default"))) int truncate(const char *path, off_t length) {
    RESOLVE(truncate);
    // Conservative: a non-zero length keeps the leading bytes, so copy contents.
    maybe_copy_up(path, O_WRONLY);
    return real_truncate(path, length);
}
