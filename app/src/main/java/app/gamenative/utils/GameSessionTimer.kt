package app.gamenative.utils

import android.os.SystemClock
import app.gamenative.PrefManager

/**
 * Tracks how long the user has actively been in a game, excluding time while the emulated
 * process is suspended (overlay open under AUTO/MANUAL suspend policy, or app backgrounded).
 *
 * Two figures are exposed:
 *  - [currentSessionMs] — active time for the current launch.
 *  - [totalMs] — lifetime active time, i.e. the persisted total plus the current session.
 *
 * Time is measured with [SystemClock.elapsedRealtime] (a monotonic clock that keeps ticking
 * while the device is awake but is immune to wall-clock/date changes). We bank elapsed time
 * into [accumulatedMs] on each suspend, and keep a [runningSince] marker while the game runs;
 * the live value is the banked total plus the time since the last resume.
 *
 * This is a process-wide singleton (only one game session is ever active at a time) deliberately
 * mirroring how [app.gamenative.PluviaApp] holds the active X-environment globally. All mutation
 * happens on the main thread (Compose / Activity lifecycle callbacks), so no extra locking is
 * needed.
 */
object GameSessionTimer {
    // Active time already banked across previous suspend cycles in this session.
    private var accumulatedMs = 0L

    // elapsedRealtime() captured when the game last resumed; 0L means "currently suspended".
    private var runningSince = 0L

    // Lifetime total persisted before this session started; current session is added on top.
    private var totalBaseMs = 0L

    // The per-game id (e.g. "STEAM_440") this session is tracking; null when no session is active.
    private var activeAppId: String? = null

    /** Begin tracking a new session for [appId]. Assumes the game is running on start. */
    fun startSession(appId: String) {
        activeAppId = appId
        totalBaseMs = PrefManager.getGameTotalPlaytimeMs(appId)
        accumulatedMs = 0L
        runningSince = SystemClock.elapsedRealtime()
    }

    /** Game suspended: bank the elapsed active time and stop the clock. Idempotent. */
    fun onSuspended() {
        if (runningSince != 0L) {
            accumulatedMs += SystemClock.elapsedRealtime() - runningSince
            runningSince = 0L
        }
        // App-backgrounded is exactly when the OS is most likely to kill the process, so flush
        // the banked total here too — complements the periodic checkpoint in XServerScreen.
        persist()
    }

    /** Game resumed: restart the clock. Idempotent, and a no-op when no session is active. */
    fun onResumed() {
        if (activeAppId != null && runningSince == 0L) {
            runningSince = SystemClock.elapsedRealtime()
        }
    }

    /** Active milliseconds in the current launch (frozen while suspended). */
    fun currentSessionMs(): Long =
        accumulatedMs + if (runningSince != 0L) SystemClock.elapsedRealtime() - runningSince else 0L

    /** Lifetime active milliseconds: persisted total plus the current session. */
    fun totalMs(): Long = totalBaseMs + currentSessionMs()

    /**
     * Flush the current lifetime total to disk without ending the session. Idempotent and safe to
     * spam: [totalMs] is an absolute value (base + current session), so repeated writes never
     * double-count. No-op when no session is active.
     */
    fun persist() {
        activeAppId?.let { PrefManager.setGameTotalPlaytimeMs(it, totalMs()) }
    }

    /** Persist the lifetime total and reset. Safe to call when no session is active. */
    fun endSession() {
        persist()
        accumulatedMs = 0L
        runningSince = 0L
        totalBaseMs = 0L
        activeAppId = null
    }
}
