package app.gamenative.ui.screen.minimal

import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PluviaApp
import app.gamenative.data.MinimalCollectionSelection
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import app.gamenative.data.DownloadInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import timber.log.Timber
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** modernMinimal launcher list: collection apps + installed orphans, with install/download state. */
@HiltViewModel
class MinimalLibraryViewModel @Inject constructor(
    private val steamAppDao: SteamAppDao,
) : ViewModel() {

    // Last (id, receivedPics) set seen, so install state is re-read only when it can change.
    private var lastRowKeys: Set<Pair<Int, Boolean>> = emptySet()

    /**
     * Only observed while the screen is subscribed: this ViewModel stays on the back stack under
     * a running game, and an always-hot Room observer re-ran the query (and the per-row disk
     * checks below) on every steam_app write.
     */
    private val rows = steamAppDao.observeMinimalRows()
        .onEach { r ->
            // Recompute install state when rows are added/removed AND when a row's PICS lands:
            // the install folder name (and so the on-disk checks) only exists after PICS.
            val keys = r.mapTo(HashSet()) { it.id to it.receivedPics }
            if (keys != lastRowKeys) {
                lastRowKeys = keys
                refreshInstalled(r.map { it.id })
                ensureProgressTicker()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val installedIds = MutableStateFlow<Set<Int>>(emptySet())
    private val downloadProgress = MutableStateFlow<Map<Int, Int>>(emptyMap())
    private val pausedIds = MutableStateFlow<Set<Int>>(emptySet())

    /**
     * Install / resume requested but the download job doesn't exist yet (depot resolution can
     * take a few seconds). appId -> request time, so a request that never produces a job is
     * dropped after [STARTING_TIMEOUT_MS] instead of sticking forever.
     */
    private val startingIds = MutableStateFlow<Map<Int, Long>>(emptyMap())

    private data class DownloadState(val progress: Map<Int, Int>, val paused: Set<Int>, val starting: Set<Int>)

    // combine() takes at most 5 typed flows, so the download-related ones are pre-combined.
    private val downloadState = combine(downloadProgress, pausedIds, startingIds) { progress, paused, starting ->
        DownloadState(progress, paused, starting.keys)
    }

    val state: StateFlow<MinimalLibraryContent> = combine(
        rows,
        MinimalCollectionSelection.selectedAppIds.filterNotNull(),
        installedIds,
        downloadState,
    ) { r, collection, installed, dl ->
        buildMinimalLibrary(r, collection, installed, dl.progress, dl.paused, dl.starting)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MinimalLibraryContent.Empty)

    private val capsuleCache = LruCache<Int, String>(32)
    private var progressJob: Job? = null

    private val onInstallStatusChanged: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = {
        refreshInstalled()
    }
    private val onDownloadStatusChanged: (AndroidEvent.DownloadStatusChanged) -> Unit = {
        refreshInstalled()
        ensureProgressTicker()
    }

    init {
        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.on<AndroidEvent.DownloadStatusChanged, Unit>(onDownloadStatusChanged)
    }

    private fun refreshInstalled(ids: List<Int> = rows.value.map { it.id }) {
        viewModelScope.launch {
            val (installed, paused) = withContext(Dispatchers.IO) {
                ids.filterTo(HashSet()) { SteamService.isAppInstalled(it) } to
                    ids.filterTo(HashSet()) { MinimalGameActions.isResumable(it) }
            }
            installedIds.value = installed
            pausedIds.value = paused
        }
    }

    /** Re-read install / download state right after a user action (pause, resume, install...). */
    fun refreshState() {
        refreshInstalled()
        ensureProgressTicker()
    }

    private fun activeDownloads(): Map<Int, Int> =
        rows.value.asSequence()
            .mapNotNull { row ->
                val info = SteamService.getAppDownloadInfo(row.id) ?: return@mapNotNull null
                if (!info.isActive()) return@mapNotNull null
                row.id to (info.getProgress() * 100f).roundToInt().coerceIn(0, 100)
            }
            .toMap()

    /**
     * Poll download progress once a second while something is downloading or starting. A
     * "starting" entry clears as soon as its job shows up (or after the timeout).
     */
    private fun ensureProgressTicker() {
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            while (isActive) {
                val active = activeDownloads()
                downloadProgress.value = active
                val now = System.currentTimeMillis()
                // Clear once the job is actually running. (A paused download's old, inactive
                // DownloadInfo may still exist, so "exists" isn't enough.)
                startingIds.update { starting ->
                    starting.filter { (id, since) -> id !in active && now - since < STARTING_TIMEOUT_MS }
                }
                if (active.isEmpty() && startingIds.value.isEmpty()) break
                delay(1_000)
            }
            refreshInstalled()
        }
    }

    private fun isBusy(gameId: Int): Boolean =
        gameId in startingIds.value || SteamService.getAppDownloadInfo(gameId)?.isActive() == true

    /** Fresh install with default DLC. Ignored while one is already starting or running. */
    fun install(gameId: Int) = startDownload(gameId) { MinimalGameActions.startInstall(gameId) }

    /** Resume a paused download. Ignored while one is already starting or running. */
    fun resume(gameId: Int) = startDownload(gameId) { MinimalGameActions.resume(gameId) }

    private fun startDownload(gameId: Int, start: suspend () -> DownloadInfo?) {
        if (isBusy(gameId)) return
        // Mark first (synchronously) so a second press in the same frame is ignored.
        startingIds.update { it + (gameId to System.currentTimeMillis()) }
        ensureProgressTicker()
        viewModelScope.launch {
            val job = try {
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Minimal: failed to start download for $gameId")
                null
            }
            // Blocked (e.g. Wi-Fi only) or failed: SteamService already told the user; clear now.
            if (job == null) startingIds.update { it - gameId }
            refreshState()
        }
    }

    fun pause(gameId: Int) {
        MinimalGameActions.pause(gameId)
        refreshState()
    }

    /**
     * Library capsule (600x900) for [appId]. getCapsuleUrl() already falls back to the
     * PICS-listed header file (hashed store_item_assets path); the fixed `.../header.jpg`
     * is a last resort since it no longer exists for many newer games.
     */
    suspend fun capsuleUrl(appId: Int): String? {
        capsuleCache.get(appId)?.let { return it }
        val app = withContext(Dispatchers.IO) { steamAppDao.findApp(appId) } ?: return null
        val url = app.getCapsuleUrl().ifEmpty { app.headerUrl }
        if (url.isNotEmpty()) capsuleCache.put(appId, url)
        return url.ifEmpty { null }
    }

    private companion object {
        const val STARTING_TIMEOUT_MS = 60_000L
    }

    override fun onCleared() {
        PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.off<AndroidEvent.DownloadStatusChanged, Unit>(onDownloadStatusChanged)
        super.onCleared()
    }
}
