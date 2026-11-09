package app.gamenative.ui.screen.minimal

import app.gamenative.data.DepotInfo
import app.gamenative.service.SteamService
import app.gamenative.service.download.SteamDownloadMode
import app.gamenative.utils.MarkerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What A (or a tap on the selected row) does for a launcher entry. */
enum class PrimaryAction(val label: String) {
    Launch("OPEN"),
    Install("INSTALL"),
    Pause("PAUSE"),
    Resume("RESUME"),
    Uninstall("UNINSTALL"),

    /** Install/resume already requested, job not created yet: A does nothing (debounce). */
    Starting("WAIT"),
}

/** Orphans can only be uninstalled; otherwise download state wins over installed state. */
fun primaryAction(entry: MinimalEntry): PrimaryAction = when {
    entry.orphan -> PrimaryAction.Uninstall
    entry.starting -> PrimaryAction.Starting
    entry.downloadPercent != null -> PrimaryAction.Pause
    entry.paused -> PrimaryAction.Resume
    entry.installed -> PrimaryAction.Launch
    else -> PrimaryAction.Install
}

/** One downloadable depot, reduced to the two ids the DLC default needs. */
data class DepotDlc(val dlcAppId: Int, val optionalDlcId: Int)

/** Matches SteamService.INVALID_APP_ID (literal so this stays JVM-testable). */
private const val INVALID_APP = Int.MAX_VALUE

/**
 * DLC app ids a fresh install includes by default. Mirrors the pre-selection in
 * GameManagerDialog (lines ~129-151) for a game that isn't installed yet: every DLC with
 * its own depots, except "indirect" DLC (separate apps) and optional DLC. Keep in sync.
 */
fun defaultInstallDlcIds(depots: Collection<DepotDlc>, indirectDlcAppIds: Set<Int>): List<Int> {
    val optional = depots.filter { it.optionalDlcId == it.dlcAppId }.mapTo(HashSet()) { it.dlcAppId }
    return depots.asSequence()
        .map { it.dlcAppId }
        .filter { it != INVALID_APP }
        .distinct()
        .filter { it !in indirectDlcAppIds && it !in optional }
        .sorted()
        .toList()
}

/** Launcher actions that talk to SteamService. All run on IO. */
object MinimalGameActions {
    /** Start a fresh install with the default DLC selection (no install dialog). */
    suspend fun startInstall(gameId: Int) = withContext(Dispatchers.IO) {
        val depots: Map<Int, DepotInfo> = SteamService.getDownloadableDepots(gameId)
        val indirect = SteamService.getDownloadableDlcAppsOf(gameId).orEmpty().mapTo(HashSet()) { it.id }
        val dlc = defaultInstallDlcIds(depots.values.map { DepotDlc(it.dlcAppId, it.optionalDlcId) }, indirect)
        Timber.i("Minimal: installing $gameId with ${dlc.size} default DLC")
        // Returns null when blocked (e.g. Wi-Fi only); SteamService already notifies the user.
        SteamService.downloadApp(gameId, dlc, branch = "public", mode = SteamDownloadMode.INSTALL)
    }

    /**
     * Whether [gameId] has a download that can actually be resumed. Stricter than
     * SteamService.hasPartialDownload, which treats any existing folder without a "complete"
     * marker as partial: for an app whose PICS hasn't arrived yet the folder name is empty, so
     * that check looks at steamapps/common itself and reports every new game as paused.
     */
    fun isResumable(gameId: Int): Boolean {
        if (SteamService.workshopPausedApps.contains(gameId)) return true
        if (SteamService.getDownloadingAppInfoOf(gameId) != null) return true
        // Unknown folder name -> nothing on disk can belong to this game yet.
        if (SteamService.getAppDirName(SteamService.getAppInfoOf(gameId)).isBlank()) return false
        return MarkerUtils.hasResumablePartialInstall(SteamService.getAppDirPath(gameId))
    }

    /** Pause keeps the progress snapshot so [resume] can pick up where it left off. */
    fun pause(gameId: Int) {
        SteamService.getAppDownloadInfo(gameId)?.cancel()
    }

    suspend fun resume(gameId: Int) = withContext(Dispatchers.IO) {
        SteamService.downloadApp(gameId, SteamService.resumeModeFor(gameId))
    }
}
