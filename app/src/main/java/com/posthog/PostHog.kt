package com.posthog

/**
 * Local no-op replacement for the PostHog SDK (this fork removed the real dependency so the app
 * never sends analytics). It deliberately lives in the original `com.posthog` package so the many
 * existing `import com.posthog.PostHog` / `PostHog.capture(...)` call sites keep compiling
 * unchanged, which keeps the diff small and upstream merges clean.
 *
 * In Kotlin, `object` declares a singleton, so `PostHog.capture(...)` is a plain static-style call.
 */
object PostHog {
    @Suppress("UNUSED_PARAMETER")
    fun capture(event: String, properties: Map<String, Any?>? = null) {
        // Intentionally empty: nothing is recorded or transmitted.
    }

    @Suppress("UNUSED_PARAMETER")
    fun register(key: String, value: Any) {
        // Intentionally empty.
    }
}
