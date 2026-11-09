package app.gamenative.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * modernMinimal: rough progress of the current sync, for display only.
 * Package and app PICS requests from other sources (e.g. the 60s change checker) also count
 * as "done", so done can briefly exceed total; [Progress] clamps.
 */
object MinimalSyncProgress {
    data class Progress(
        val packagesDone: Int = 0,
        val packagesTotal: Int = 0,
        val appsDone: Int = 0,
        val appsTotal: Int = 0,
    ) {
        val packagesShown: Int get() = packagesDone.coerceAtMost(packagesTotal)
        val appsShown: Int get() = appsDone.coerceAtMost(appsTotal)
        val packagesActive: Boolean get() = packagesTotal > 0 && packagesDone < packagesTotal
        val appsActive: Boolean get() = appsTotal > 0 && appsDone < appsTotal
        val isActive: Boolean get() = packagesActive || appsActive
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** New login: [packagesTotal] packages need package PICS. */
    fun startLogin(packagesTotal: Int) {
        _progress.value = Progress(packagesTotal = packagesTotal)
    }

    fun packagesProcessed(count: Int) = _progress.update { it.copy(packagesDone = it.packagesDone + count) }

    fun appsQueued(count: Int) = _progress.update { it.copy(appsTotal = it.appsTotal + count) }

    fun appsProcessed(count: Int) = _progress.update { it.copy(appsDone = it.appsDone + count) }

    fun reset() {
        _progress.value = Progress()
    }
}
