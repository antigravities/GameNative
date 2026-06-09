package app.gamenative.ui.model

import android.content.Context
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.gog.GogRecommendationsRepository
import app.gamenative.data.gog.GogSeedCollector
import app.gamenative.service.gog.GOGAuthManager
import app.gamenative.data.LibraryPlayHistory
import app.gamenative.data.SteamAppSummary
import app.gamenative.data.SteamCollection
import app.gamenative.data.SteamCollectionRepository
import app.gamenative.events.AndroidEvent
import app.gamenative.data.GOGGame
import app.gamenative.data.EpicGame
import app.gamenative.data.AmazonGame
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.buildLibraryPageQuery
import app.gamenative.utils.NameSortKey
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonArtwork
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.steam.SteamCollectionFilter
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.data.statsFor
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.LibraryTab.Companion.next
import app.gamenative.ui.enums.LibraryTab.Companion.previous
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.CustomGameScanner
import app.gamenative.data.RecommendationRepository
import app.gamenative.data.RecommendedGame
import app.gamenative.utils.DeviceGameStatsCache
import app.gamenative.utils.GpuGameStatsCache
import app.gamenative.utils.GameCompatibilityCache
import app.gamenative.utils.GameCompatibilityService
import app.gamenative.utils.HardwareUtils
import app.gamenative.utils.unaccent
import com.winlator.core.GPUInformation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

private const val PLAYABLE_FPS_THRESHOLD = 30
private const val PROVEN_RUNS_THRESHOLD = 5

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryPlayHistoryDao: LibraryPlayHistoryDao,
    private val steamAppDao: SteamAppDao,
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState(isLoading = true))
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Keep the library scroll state. This will last longer as the VM will stay alive.
    var listState: LazyGridState by mutableStateOf(LazyGridState(0, 0))

    private val onInstallStatusChanged: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = {
        onFilterApps(paginationCurrentPage)
    }

    private val onCustomGameImagesFetched: (AndroidEvent.CustomGameImagesFetched) -> Unit = {
        // Increment refresh counter and refresh the library list to pick up newly fetched images
        _state.update { it.copy(imageRefreshCounter = it.imageRefreshCounter + 1) }
        onFilterApps(paginationCurrentPage)
    }

    private val onRecommendationToggleChanged: (AndroidEvent.RecommendationToggleChanged) -> Unit = {
        refreshRecommendationHero()
    }

    // How many items loaded on one page of results
    @Volatile private var paginationCurrentPage: Int = 0
    @Volatile private var lastPageInCurrentFilter: Int = 0

    // Complete and unfiltered app list
    private var appList: List<SteamAppSummary> = emptyList()
    private var gogGameList: List<GOGGame> = emptyList()
    private var epicGameList: List<EpicGame> = emptyList()
    private var amazonGameList: List<AmazonGame> = emptyList()
    private var playHistoryByAppId: Map<String, Long> = emptyMap()

    @Volatile private var steamCollections: List<SteamCollection>? = null
    // Caches the LibraryItem built for each Steam app ID.
    // Key: Steam appId (Int). Value: the SteamAppSummary instance that produced the item,
    // paired with the resulting LibraryItem. Validated by reference equality (===) because
    // appList is only replaced when the owned-app count changes, so the same
    // SteamAppSummary objects are reused across onFilterApps() calls between DAO emissions.
    private val steamItemCache = ConcurrentHashMap<Int, Pair<SteamAppSummary, LibraryItem>>()

    // Track if this is the first load to apply minimum load time
    private var isFirstLoad = true

    // Cached recommendation (fetched once at startup)
    @Volatile private var cachedRecommendation: RecommendedGame? = null
    @Volatile private var cachedFeatured: app.gamenative.data.FeaturedItem? = null

    // Track debounce job for search
    private var searchDebounceJob: Job? = null
    private val SEARCH_DEBOUNCE_MS = 500L // 500ms debounce


    // Cache GPU name to avoid repeated calls
    private val gpuName: String by lazy {
        try {
            val gpu = GPUInformation.getRenderer(context)
            if (gpu.isNullOrEmpty()) {
                Timber.tag("LibraryViewModel").w("GPU name is null or empty")
                "Unknown GPU"
            } else {
                Timber.tag("LibraryViewModel").d("Retrieved GPU name: $gpu")
                gpu
            }
        } catch (e: Exception) {
            Timber.tag("LibraryViewModel").e(e, "Failed to get GPU name")
            "Unknown GPU"
        }
    }

    // Pairs a LibraryItem with its installed state for sorting and final list assembly.
    private data class LibraryEntry(val item: LibraryItem, val isInstalled: Boolean, val lastPlayed: Long = 0L)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (gpuName != "Unknown GPU") {
                DeviceGameStatsCache.refreshIfStale(
                    deviceModel = HardwareUtils.getMachineName(),
                    gpuName = gpuName,
                    modernBuild = BuildConfig.MODERN_ANDROID,
                )
                GpuGameStatsCache.refreshIfStale(
                    gpuName = gpuName,
                    modernBuild = BuildConfig.MODERN_ANDROID,
                )
            } else {
                Timber.tag("LibraryViewModel").w("Skipping device/GPU game stats fetch - GPU name is unknown")
            }
            _state.update {
                it.copy(
                    deviceGameStats = DeviceGameStatsCache.getAll(),
                    gpuGameStats = GpuGameStatsCache.getAll(),
                )
            }
            // Re-run filtering/sorting now that stats are available, if anything depends on them.
            if (usesStats(_state.value)) {
                onFilterApps(paginationCurrentPage)
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch(Dispatchers.IO) {
            // Re-create the underlying DAO Flow whenever the EXPIRED filter is toggled,
            // so apps with Expired or missing licenses are surfaced/hidden accordingly.
            _state
                .map { it.appInfoSortType.contains(AppFilter.EXPIRED) }
                .distinctUntilChanged()
                .flatMapLatest { includeExpired ->
                    steamAppDao.getAllOwnedAppSummaries(includeExpired = includeExpired)
                }
                .collect { apps ->
                    Timber.tag("LibraryViewModel").d("Collecting ${apps.size} apps")
                    // Check if the list has actually changed before triggering a re-filter
                    if (appList != apps) {
                        // Clear the item cache before replacing the list. When the DAO emits a new
                        // list, all SteamAppSummary instances are new objects, so every cache entry
                        // would be a reference-equality miss anyway. Clearing eagerly releases the
                        // old SteamAppSummary + LibraryItem pairs (which include depot maps) instead
                        // of holding them until each slot is overwritten by onFilterApps().
                        steamItemCache.clear()
                        appList = apps
                        onFilterApps(paginationCurrentPage)
                    }
                }
        }

        viewModelScope.launch(Dispatchers.IO) {
            libraryPlayHistoryDao.getAll().collect { entries ->
                val playHistory = entries.associate { it.appId to it.lastPlayed }
                if (playHistoryByAppId != playHistory) {
                    playHistoryByAppId = playHistory
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        // Mirror SteamService's PICS sync counters into LibraryState so the UI can show a banner
        // while the library is still loading from Steam after login.
        viewModelScope.launch(Dispatchers.IO) {
            SteamService.picsSyncPending.collect { pending ->
                _state.update { it.copy(picsSyncPending = pending) }
            }
        }

        // One-time backfill of size_bytes for rows synced before the column existed. Wait for any
        // in-flight PICS sync to settle (returns immediately when none), then let the library
        // finish its initial render before starting, so the one-time scan never competes with the
        // first load. No-op after the first successful run (guarded inside backfillSizesOnce).
        viewModelScope.launch(Dispatchers.IO) {
            SteamService.picsSyncPending.first { it == 0 }
            delay(5_000L)
            // Sort-key backfill first: it drives the library's visible ordering, so existing
            // installs upgrading to v24 get correct ordering before the (informational) size
            // backfill runs. Both are no-ops after their first successful run.
            SteamService.backfillSortKeysOnce()
            SteamService.backfillSizesOnce()
        }
        viewModelScope.launch(Dispatchers.IO) {
            SteamService.picsSyncTotal.collect { total ->
                _state.update { it.copy(picsSyncTotal = total) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            SteamService.picsSyncQueued.collect { queued ->
                _state.update { it.copy(picsSyncQueued = queued) }
            }
        }

        // Collect GOG games
        viewModelScope.launch(Dispatchers.IO) {
            gogGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} GOG games")
                // Check if the list has actually changed before triggering a re-filter
                if (gogGameList != games) {
                    gogGameList = games
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            epicGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} Epic games")

                val hasChanges = epicGameList.size != games.size || epicGameList != games
                epicGameList = games

                if (hasChanges) {
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            amazonGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} Amazon games")
                val hasChanges = amazonGameList.size != games.size || amazonGameList != games
                amazonGameList = games
                if (hasChanges) {
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        // Load any cached collections immediately, then observe live updates.
        SteamCollectionRepository.loadFromCache()
        viewModelScope.launch(Dispatchers.IO) {
            SteamCollectionRepository.collections.collect { collections ->
                steamCollections = collections
                // Reconcile the persisted selection against freshly-loaded collections.
                val current = _state.value.selectedSteamCollectionIds
                val recon = SteamCollectionFilter.reconcile(current, collections)
                if (recon.removedAny) {
                    PrefManager.librarySteamCollections = recon.cleaned
                }
                _state.update {
                    it.copy(
                        steamCollections = collections,
                        selectedSteamCollectionIds = recon.cleaned,
                    )
                }
                if (recon.removedAny) {
                    SnackbarManager.show(context.getString(R.string.steam_collections_removed))
                }
                onFilterApps(paginationCurrentPage)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            SteamCollectionRepository.skippedDynamic.collect { skipped ->
                _state.update { it.copy(skippedDynamicCollections = skipped) }
            }
        }

        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.on<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
        PluviaApp.events.on<AndroidEvent.RecommendationToggleChanged, Unit>(onRecommendationToggleChanged)

        refreshRecommendationHero()
    }

    private fun refreshRecommendationHero() {
        viewModelScope.launch(Dispatchers.IO) {
            val hero = RecommendationRepository.getHero(context)
            cachedFeatured = hero.featured
            cachedRecommendation = when {
                // A live featured takes the slot (still gated by the showRecommendations
                // toggle at display time), regardless of GOG consent.
                hero.featured != null -> null
                PrefManager.showRecommendations && PrefManager.recDisclosureShown -> runCatching {
                    val owned = GogSeedCollector.collect(
                        context,
                        libraryPlayHistoryDao,
                        gogGameDao,
                        epicGameDao,
                        amazonGameDao,
                    )
                    val userId = GOGAuthManager.getStoredCredentials(context).getOrNull()?.userId
                    val daySeed = System.currentTimeMillis() / (24L * 60 * 60 * 1000)
                    GogRecommendationsRepository.getDailyHero(context, owned, userId, daySeed)
                }.getOrNull() ?: hero.recommendation
                else -> hero.recommendation
            }
            onFilterApps(paginationCurrentPage)
        }
    }

    override fun onCleared() {
        searchDebounceJob?.cancel()
        PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.off<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
        PluviaApp.events.off<AndroidEvent.RecommendationToggleChanged, Unit>(onRecommendationToggleChanged)
        super.onCleared()
    }

    fun onModalBottomSheet(value: Boolean) {
        _state.update { it.copy(modalBottomSheet = value) }
    }

    fun onIsSearching(value: Boolean) {
        _state.update { it.copy(isSearching = value) }
        if (!value) {
            onSearchQuery("")
        }
    }

    fun onSourceToggle(source: GameSource) {
        val current = _state.value
        when (source) {
            GameSource.STEAM -> {
                val newValue = !current.showSteamInLibrary
                PrefManager.showSteamInLibrary = newValue
                _state.update { it.copy(showSteamInLibrary = newValue) }
            }

            GameSource.CUSTOM_GAME -> {
                val newValue = !current.showCustomGamesInLibrary
                PrefManager.showCustomGamesInLibrary = newValue
                _state.update { it.copy(showCustomGamesInLibrary = newValue) }
            }
            GameSource.GOG -> {
                val newValue = !current.showGOGInLibrary
                PrefManager.showGOGInLibrary = newValue
                _state.update { it.copy(showGOGInLibrary = newValue) }
            }
            GameSource.EPIC -> {
                val newValue = !current.showEpicInLibrary
                PrefManager.showEpicInLibrary = newValue
                _state.update { it.copy(showEpicInLibrary = newValue) }
            }
            GameSource.AMAZON -> {
                val newValue = !current.showAmazonInLibrary
                PrefManager.showAmazonInLibrary = newValue
                _state.update { it.copy(showAmazonInLibrary = newValue) }
            }
        }
        onFilterApps(paginationCurrentPage)
    }

    fun onSortOptionChanged(sortOption: SortOption) {
        PrefManager.librarySortOption = sortOption
        _state.update { it.copy(currentSortOption = sortOption) }
        onFilterApps()
    }

    fun onOptionsPanelToggle(isOpen: Boolean) {
        _state.update { it.copy(isOptionsPanelOpen = isOpen) }
    }

    fun onTabChanged(tab: LibraryTab) {
        _state.update { it.copy(currentTab = tab) }
        onFilterApps(0) // Reset to first page and refresh
    }

    fun onNextTab() {
        _state.update { currentState ->
            val nextTab = currentState.currentTab.next()
            Timber.tag("LibraryViewModel").d("Tab next via bumper: ${currentState.currentTab} -> $nextTab")
            currentState.copy(currentTab = nextTab)
        }
        onFilterApps(0)
    }

    fun onPreviousTab() {
        _state.update { currentState ->
            val previousTab = currentState.currentTab.previous()
            Timber.tag("LibraryViewModel").d("Tab previous via bumper: ${currentState.currentTab} -> $previousTab")
            currentState.copy(currentTab = previousTab)
        }
        onFilterApps(0)
    }

    fun onSearchQuery(value: String) {
        // Update UI immediately for responsive typing
        _state.update { it.copy(searchQuery = value) }

        // Cancel previous debounce job
        searchDebounceJob?.cancel()

        // Start new debounce job
        searchDebounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            // Only trigger filter after user stops typing
            onFilterApps()
        }
    }

    // TODO: include other sort types
    fun onFilterChanged(value: AppFilter) {
        _state.update { currentState ->
            val updatedFilter = EnumSet.copyOf(currentState.appInfoSortType)

            if (updatedFilter.contains(value)) {
                updatedFilter.remove(value)
            } else {
                updatedFilter.add(value)
            }

            PrefManager.libraryFilter = updatedFilter

            currentState.copy(appInfoSortType = updatedFilter)
        }

        onFilterApps()
    }

    fun onSteamCollectionToggle(id: String) {
        _state.update { currentState ->
            val updated = currentState.selectedSteamCollectionIds.toMutableSet()
            if (!updated.add(id)) updated.remove(id)
            PrefManager.librarySteamCollections = updated
            currentState.copy(selectedSteamCollectionIds = updated)
        }
        onFilterApps()
    }

    fun onClearSteamCollections() {
        _state.update { currentState ->
            PrefManager.librarySteamCollections = emptySet()
            currentState.copy(selectedSteamCollectionIds = emptySet())
        }
        onFilterApps()
    }

    fun onPageChange(pageIncrement: Int) {
        // Amount to change by
        var toPage = max(0, paginationCurrentPage + pageIncrement)
        toPage = min(toPage, lastPageInCurrentFilter)
        onFilterApps(toPage)
    }

    fun onRefresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }

            // Clear compatibility cache on manual refresh to get fresh data
            GameCompatibilityCache.clear()
            DeviceGameStatsCache.clear()
            GpuGameStatsCache.clear()

            try {
                val newApps = SteamService.refreshOwnedGamesFromServer()
                if (newApps > 0) {
                    Timber.tag("LibraryViewModel").i("Queued $newApps newly owned games for PICS sync")
                } else {
                    Timber.tag("LibraryViewModel").d("No newly owned games discovered during refresh")
                }
                if (app.gamenative.service.gog.GOGService.hasStoredCredentials(context)) {
                    Timber.tag("LibraryViewModel").i("Triggering GOG library refresh")
                    app.gamenative.service.gog.GOGService.triggerLibrarySync(context)
                }
                if (AmazonService.hasStoredCredentials(context)) {
                    Timber.tag("LibraryViewModel").i("Triggering Amazon library refresh")
                    AmazonService.triggerLibrarySync(context)
                }
            } catch (e: Exception) {
                Timber.tag("LibraryViewModel").e(e, "Failed to refresh owned games from server")
            } finally {
                onFilterApps(0).join()
                // Fetch compatibility for current page after refresh
                val currentPageGames = _state.value.appInfoList.map { it.name }
                if (currentPageGames.isNotEmpty()) {
                    fetchCompatibilityForPage(currentPageGames)
                }
                if (gpuName != "Unknown GPU") {
                    DeviceGameStatsCache.refreshIfStale(
                        deviceModel = HardwareUtils.getMachineName(),
                        gpuName = gpuName,
                        modernBuild = BuildConfig.MODERN_ANDROID,
                    )
                    GpuGameStatsCache.refreshIfStale(
                        gpuName = gpuName,
                        modernBuild = BuildConfig.MODERN_ANDROID,
                    )
                }
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        deviceGameStats = DeviceGameStatsCache.getAll(),
                        gpuGameStats = GpuGameStatsCache.getAll(),
                    )
                }
                if (usesStats(_state.value)) {
                    onFilterApps(paginationCurrentPage)
                }
            }
        }
    }

    fun addCustomGameFolder(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedPath = File(path).absolutePath
            val libraryItem = CustomGameScanner.createLibraryItemFromFolder(normalizedPath)
            if (libraryItem == null) {
                Timber.tag("LibraryViewModel").w("Selected folder is not a valid custom game: $normalizedPath")
                return@launch
            }

            val manualFolders = PrefManager.customGameManualFolders.toMutableSet()
            if (!manualFolders.contains(normalizedPath)) {
                manualFolders.add(normalizedPath)
                PrefManager.customGameManualFolders = manualFolders
            }

            CustomGameScanner.invalidateCache()
            onFilterApps(paginationCurrentPage)
        }
    }

    /** Whether the current sort or any active filter depends on per-game stats. */
    private fun usesStats(state: LibraryState): Boolean {
        val statSorts = setOf(
            SortOption.FPS_HIGH,
            SortOption.RUNS_HIGH,
            SortOption.REVIEWS_HIGH,
            SortOption.REVIEWS_GPU_HIGH,
        )
        if (state.currentSortOption in statSorts) return true
        return state.appInfoSortType.any {
            it == AppFilter.PLAYABLE || it == AppFilter.FIVE_STAR ||
                it == AppFilter.FIVE_STAR_GPU || it == AppFilter.PROVEN_GPU
        }
    }

    /**
     * Returns true if a game satisfies all active stat filters. Applied per-source (like
     * [GameCompatibilityCache]'s compatible filter) so the per-source tab counts stay accurate.
     * Games with no stats data are hidden whenever a stat filter is active.
     */
    private fun passesStatsFilters(state: LibraryState, source: GameSource, name: String): Boolean {
        val filters = state.appInfoSortType
        val playable = filters.contains(AppFilter.PLAYABLE)
        val fiveStar = filters.contains(AppFilter.FIVE_STAR)
        val fiveStarGpu = filters.contains(AppFilter.FIVE_STAR_GPU)
        val proven = filters.contains(AppFilter.PROVEN_GPU)
        if (!playable && !fiveStar && !fiveStarGpu && !proven) return true

        val stats = state.statsFor(source, name)
        if (playable && (stats?.fps ?: 0) < PLAYABLE_FPS_THRESHOLD) return false
        if (fiveStar && (stats?.reviewsDevice ?: 0) < 1) return false
        if (fiveStarGpu && (stats?.reviewsGpu ?: 0) < 1) return false
        if (proven && (stats?.runsGpu ?: 0) < PROVEN_RUNS_THRESHOLD) return false
        return true
    }

    private fun onFilterApps(paginationPage: Int = 0): Job {
        Timber.tag("LibraryViewModel").d("onFilterApps - appList.size: ${appList.size}, isFirstLoad: $isFirstLoad")
        return viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true) }
            val currentState = _state.value
            // Route the COMPATIBLE filter (a name-keyed network/in-memory cache) and family-sharing
            // (a multi-valued owner JSON column) through the original in-memory path — neither can be
            // expressed in SQL, and both yield small or rare result sets. Everything else (the common
            // browse/search case) takes the SQL fast path, which materializes only the visible page.
            if (currentState.appInfoSortType.contains(AppFilter.COMPATIBLE) ||
                SteamService.familyMembers.isNotEmpty() ||
                currentState.selectedSteamCollectionIds.isNotEmpty()
            ) {
                filterAppsInMemory(currentState, paginationPage)
            } else {
                filterAppsSql(currentState, paginationPage)
            }
            if (isFirstLoad) isFirstLoad = false
        }
    }

    // Original full-list filtering, retained verbatim as the fallback for filters that can't be
    // expressed in SQL (COMPATIBLE, family-sharing). Loads the whole appList, filters/sorts it all,
    // then paginates with take(endIndex).
    private suspend fun filterAppsInMemory(currentState: LibraryState, paginationPage: Int) {
            val currentFilter = AppFilter.getAppType(currentState.appInfoSortType)

            // Fetch download directory apps once on IO thread and cache as a HashSet for O(1) lookups
            val downloadDirectoryApps = DownloadService.getDownloadDirectoryApps() + SteamService.getImportedAppDirs()
            val downloadDirectorySet = downloadDirectoryApps.toHashSet()

            fun passesCompatibleFilter(gameName: String): Boolean {
                if (!currentState.appInfoSortType.contains(AppFilter.COMPATIBLE)) {
                    return true
                }
                val cached = GameCompatibilityCache.getCached(gameName) ?: return true
                val status = compatibilityStatusFor(cached)
                return status == GameCompatibilityStatus.COMPATIBLE || status == GameCompatibilityStatus.GPU_COMPATIBLE
            }

            // Reusable owner / family-sharing / installed predicates used by both paths below.
            // owner_account_id is a JSON array stored as a string, so these checks stay in Kotlin.
            fun ownerMatches(item: SteamAppSummary): Boolean {
                val owners = SteamService.familyMembers.ifEmpty {
                    SteamService.userSteamId?.let { listOf(it.accountID.toInt()) } ?: emptyList()
                }
                return owners.isEmpty() || owners.any { item.ownerAccountId.contains(it) }
            }
            fun sharedMatches(item: SteamAppSummary): Boolean =
                currentState.appInfoSortType.contains(AppFilter.SHARED) ||
                    item.ownerAccountId.contains(PrefManager.steamUserAccountId) ||
                    PrefManager.steamUserAccountId == 0
            fun installedMatches(item: SteamAppSummary): Boolean {
                val installedOnly = currentState.currentTab.installedOnly ||
                    currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                return !installedOnly || downloadDirectorySet.contains(SteamService.getAppDirName(item))
            }

            val steamOwnerTypeFiltered: List<SteamAppSummary> =
                if (currentState.searchQuery.isNotBlank()) {
                    // --- SQL search path ---
                    // SQLite applies type IN (...) and LOWER(name) LIKE ‘%%’ so we only
                    // deserialize the rows that actually match, instead of all 45k summaries.
                    // The === steamItemCache is bypassed here (fresh DB objects won’t match), but
                    // the result set is small so depot recalculation on cache miss is cheap.
                    val typeCodes = currentFilter.map { it.code }
                    if (typeCodes.isEmpty()) {
                        emptyList()
                    } else {
                        steamAppDao.searchOwnedAppSummaries(
                            searchQuery = currentState.searchQuery,
                            types = typeCodes,
                            includeExpired = currentState.appInfoSortType.contains(AppFilter.EXPIRED)
                        )
                            .asSequence()
                            .filter { ownerMatches(it) }
                            .filter { sharedMatches(it) }
                            .filter { installedMatches(it) }
                            .toList()
                    }
                } else {
                    // --- Existing Kotlin path (unchanged) ---
                    // appList holds stable SteamAppSummary references; === cache remains valid.
                    appList
                        .asSequence()
                        .filter { ownerMatches(it) }
                        .filter { item -> currentFilter.any { item.type == it } }
                        .filter { sharedMatches(it) }
                        .filter { installedMatches(it) }
                        .toList()
                }

            // Per-collection counts: computed from the owner/type/search-filtered set (independent of the
            // current collection selection) so each collection shows how many games it would contribute.
            val steamCollectionCounts: Map<String, Int> = steamCollections?.associate { collection ->
                collection.id to steamOwnerTypeFiltered.count { it.id in collection.appIds }
            } ?: emptyMap()

            // Apply the Steam collection filter — union/OR, fail-open (see SteamCollectionFilter).
            // Resolve the allowed app-id set once for the whole pass instead of per app.
            val allowedSteamAppIds = SteamCollectionFilter.allowedAppIds(
                selectedIds = currentState.selectedSteamCollectionIds,
                collections = steamCollections,
            )
            val steamFilteredBeforeCompatibility: List<SteamAppSummary> =
                (
                    if (allowedSteamAppIds == null) {
                        steamOwnerTypeFiltered
                    } else {
                        steamOwnerTypeFiltered.filter { it.id in allowedSteamAppIds }
                    }
                )

            // Filter Steam apps (no sort here): the combined list is fully re-sorted below by
            // sortComparator, so any ordering applied at this stage was pure wasted work — a full
            // ~45k sort (with a per-comparison String.lowercase() allocation) whose result was
            // immediately discarded. Just apply the compatibility filter and move on.
            val filteredSteamApps: List<SteamAppSummary> = steamFilteredBeforeCompatibility
                .asSequence()
                .filter { item -> passesCompatibleFilter(item.name) }
                .filter { item -> passesStatsFilters(currentState, GameSource.STEAM, item.name) }
                .toList()

            // Map Steam apps to UI items.
            fun lastPlayedFor(appId: String): Long = playHistoryByAppId[appId] ?: 0L

            // Track appIds to filter out custom-game entries that duplicate an imported Steam game.
            val steamEntriesAppIds = mutableSetOf<String>()
            val steamEntries: List<LibraryEntry> = filteredSteamApps.map { item ->
                val isInstalled = downloadDirectorySet.contains(SteamService.getAppDirName(item))
                // Compute appId once so it is accessible in both the cache-hit and cache-miss
                // paths below, and so every Steam item is tracked for custom-game deduplication.
                val appId = "${GameSource.STEAM.name}_${item.id}"
                steamEntriesAppIds.add(appId)

                // Cache check: if the same SteamAppSummary *instance* is stored (===), the
                // underlying DB row hasn't changed and we can reuse the LibraryItem as-is.
                // appList is only replaced when the owned-app count changes, so the same
                // SteamAppSummary objects are reused between onFilterApps() calls.
                val cached = steamItemCache[item.id]
                val libraryItem = if (cached != null && cached.first === item) {
                    cached.second // cache hit: no work needed
                } else {
                    // Cache miss: build the item. sizeBytes is read straight from the summary's
                    // size_bytes column (computed at PICS-write time), so no per-item depot work.
                    LibraryItem(
                        index = 0, // temporary, will be re-indexed after combining and paginating
                        appId = appId,
                        name = item.name,
                        iconHash = item.clientIconHash,
                        capsuleImageUrl = item.getCapsuleUrl(),
                        headerImageUrl = item.headerUrl,
                        heroImageUrl = item.getHeroUrl(),
                        isShared = (PrefManager.steamUserAccountId != 0 && !item.ownerAccountId.contains(PrefManager.steamUserAccountId)),
                        sizeBytes = item.sizeBytes,
                    ).also { newItem -> steamItemCache[item.id] = item to newItem }
                }

                LibraryEntry(item = libraryItem, isInstalled = isInstalled, lastPlayed = lastPlayedFor(appId))
            }

            // Scan Custom Games roots and create UI items (filtered by search query inside scanner)
            // Only include custom games if GAME filter is selected
            val customGameItems = if (currentState.appInfoSortType.contains(AppFilter.GAME)) {
                CustomGameScanner.scanAsLibraryItems(
                    query = currentState.searchQuery,
                )
            } else {
                emptyList()
            }
            val customEntries = customGameItems
                .filter { !steamEntriesAppIds.contains(it.appId) } // Filter out imported steam appId
                .filter { passesStatsFilters(currentState, it.gameSource, it.name) }
                .map { LibraryEntry(it, true, lastPlayed = lastPlayedFor(it.appId)) }

            // Filter GOG games
            val filteredGOGGames = gogGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val gogEntries = filteredGOGGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.GOG, it.title) }
                .map { game ->
                    val appId = "${GameSource.GOG.name}_${game.id}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.iconUrl.ifEmpty { game.imageUrl },
                            capsuleImageUrl = game.verticalCoverUrl.ifEmpty { game.iconUrl.ifEmpty { game.imageUrl } },
                            headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                            heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                            isShared = false,
                            gameSource = GameSource.GOG,
                        ),
                        isInstalled = game.isInstalled,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            // Filter Epic games
            val filteredEpicGames = epicGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val epicEntries = filteredEpicGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.EPIC, it.title) }
                .map { game ->
                    val appId = "${GameSource.EPIC.name}_${game.id}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.artSquare.ifEmpty { game.artCover },
                            capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
                            headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                            heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                            isShared = false,
                            gameSource = GameSource.EPIC,
                        ),
                        isInstalled = game.isInstalled,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            // Amazon games
            val filteredAmazonGames = amazonGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val amazonEntries = filteredAmazonGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.AMAZON, it.title) }
                .map { game ->
                    val layoutHero = AmazonArtwork.layoutHeroFromProductJson(game.productJson)
                        .ifEmpty { game.heroUrl.ifEmpty { game.artUrl } }
                    val appId = "${GameSource.AMAZON.name}_${game.appId}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.artUrl,
                            capsuleImageUrl = game.artUrl,
                            headerImageUrl = layoutHero,
                            heroImageUrl = layoutHero.ifEmpty { game.artUrl },
                            gridHeroImageScale = AmazonArtwork.GRID_HERO_ZOOM_SCALE,
                            isShared = false,
                            gameSource = GameSource.AMAZON,
                        ),
                        isInstalled = game.isInstalled,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            // Calculate installed counts
            val gogInstalledCount = filteredGOGGames.count { it.isInstalled }
            val epicInstalledCount = filteredEpicGames.count { it.isInstalled }
            val amazonInstalledCount = filteredAmazonGames.count { it.isInstalled }
            // Save game counts for skeleton loaders (only when not searching, to get accurate counts)
            // This needs to happen before filtering by source, so we save the total counts
            if (currentState.searchQuery.isEmpty()) {
                PrefManager.customGamesCount = customGameItems.size
                PrefManager.steamGamesCount = steamFilteredBeforeCompatibility.size
                PrefManager.gogGamesCount = filteredGOGGames.size
                PrefManager.gogInstalledGamesCount = gogInstalledCount
                PrefManager.epicGamesCount = filteredEpicGames.size
                PrefManager.epicInstalledGamesCount = epicInstalledCount
                PrefManager.amazonInstalledGamesCount = amazonInstalledCount
                Timber.tag("LibraryViewModel").d("Saved counts - Custom: ${customGameItems.size}, Steam: ${steamFilteredBeforeCompatibility.size}, GOG: ${filteredGOGGames.size}, GOG installed: $gogInstalledCount, Epic: ${filteredEpicGames.size}, Epic installed: $epicInstalledCount, Amazon installed: $amazonInstalledCount")
            }

            // Compute effective source filters based on current tab
            // ALL tab uses user preferences, other tabs override with their presets
            // Use captured currentState (not _state.value) to avoid TOCTOU race
            val currentTab = currentState.currentTab
            val includeSteam = if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showSteamInLibrary
            } else {
                currentTab.showSteam
            }
            val includeOpen = if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showCustomGamesInLibrary
            } else {
                currentTab.showCustom
            }

            val includeGOG = (if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showGOGInLibrary
            } else {
                currentTab.showGoG
            }) && GOGService.hasStoredCredentials(context)

            val includeEpic = (if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showEpicInLibrary
            } else {
                currentTab.showEpic
            }) && EpicService.hasStoredCredentials(context)

            val includeAmazon = (if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showAmazonInLibrary
            } else {
                currentTab.showAmazon
            }) && AmazonService.hasStoredCredentials(context)

            // Combine both lists and apply sort option
            val sortComparator: Comparator<LibraryEntry> = when (currentState.currentSortOption) {
                SortOption.INSTALLED_FIRST -> compareBy<LibraryEntry> { entry ->
                    if (entry.isInstalled) 0 else 1
                }.thenBy { it.item.name.lowercase() }

                SortOption.NAME_ASC -> compareBy { it.item.name.lowercase() }

                SortOption.NAME_DESC -> compareByDescending { it.item.name.lowercase() }

                SortOption.RECENTLY_PLAYED -> LibrarySortUtils.recentlyPlayedComparator(
                    name = { it.item.name },
                    isInstalled = { it.isInstalled },
                    lastPlayed = { it.lastPlayed },
                )

                SortOption.SIZE_SMALLEST -> compareBy<LibraryEntry> { it.item.sizeBytes }
                    .thenBy { it.item.name.lowercase() }

                SortOption.SIZE_LARGEST -> compareByDescending<LibraryEntry> { it.item.sizeBytes }
                    .thenBy { it.item.name.lowercase() }

                SortOption.FPS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.fps ?: -1
                }.thenBy { it.item.name.lowercase() }

                SortOption.RUNS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.runsGpu ?: -1
                }.thenBy { it.item.name.lowercase() }

                SortOption.REVIEWS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.reviewsDevice ?: -1
                }.thenBy { it.item.name.lowercase() }

                SortOption.REVIEWS_GPU_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.reviewsGpu ?: -1
                }.thenBy { it.item.name.lowercase() }
            }

            // A Steam collection can only contain Steam apps, so when one is selected the non-Steam
            // sources can't match it — keep them out of the combined list (and their tab counts).
            val steamCollectionSelected = allowedSteamAppIds != null

            val combined = buildList {
                if (includeSteam) addAll(steamEntries)
                if (includeOpen && !steamCollectionSelected) addAll(customEntries)
                if (includeGOG && !steamCollectionSelected) addAll(gogEntries)
                if (includeEpic && !steamCollectionSelected) addAll(epicEntries)
                if (includeAmazon && !steamCollectionSelected) addAll(amazonEntries)
            }.sortedWith(sortComparator).mapIndexed { idx, entry ->
                entry.item.copy(index = idx, isInstalled = entry.isInstalled)
            }

            // Total count for the current filter
            val totalFound = combined.size

            // Determine how many pages and slice the list for incremental loading
            val pageSize = PrefManager.itemsPerPage
            // Update internal pagination state
            paginationCurrentPage = paginationPage
            lastPageInCurrentFilter = if (totalFound == 0) 0 else (totalFound - 1) / pageSize
            // Calculate how many items to show: (pagesLoaded * pageSize)
            val endIndex = min((paginationPage + 1) * pageSize, totalFound)
            var pagedList = combined.take(endIndex)

            // Prepend the hero (featured > recommendation) as first item on ALL tab when
            // enabled and not searching.
            val featured = cachedFeatured
            val rec = cachedRecommendation
            if (PrefManager.showRecommendations
                && currentTab == LibraryTab.ALL
                && currentState.searchQuery.isEmpty()
            ) {
                val heroItem = when {
                    featured != null -> LibraryItem(
                        index = -1,
                        appId = "FEATURED_${featured.campaignId}",
                        name = featured.title,
                        heroImageUrl = featured.heroImageUrl,
                        headerImageUrl = featured.heroImageUrl,
                        capsuleImageUrl = featured.capsuleImageUrl ?: featured.heroImageUrl,
                        iconHash = featured.iconUrl ?: featured.capsuleImageUrl ?: featured.heroImageUrl,
                        isRecommended = true,
                        isFeatured = true,
                        recommendedGameId = featured.campaignId,
                        recSource = "hero",
                        gameSource = GameSource.STEAM,
                    )
                    rec != null -> LibraryItem(
                        index = -1,
                        appId = "RECOMMENDED_${rec.id}",
                        name = rec.name,
                        heroImageUrl = rec.heroImageUrl,
                        capsuleImageUrl = rec.capsuleImageUrl,
                        iconHash = rec.iconUrl ?: rec.capsuleImageUrl,
                        isRecommended = true,
                        recommendedGameId = rec.id,
                        recSource = "hero",
                        gameSource = GameSource.STEAM,
                    )
                    else -> null
                }
                if (heroItem != null) {
                    pagedList = listOf(heroItem) + pagedList.map { it.copy(index = it.index + 1) }
                }
            }

            Timber.tag("LibraryViewModel").d("Filtered list size (with Custom Games): $totalFound")

            if (isFirstLoad) {
                isFirstLoad = false
            }

            // Fetch compatibility for current page games
            fetchCompatibilityForPage(pagedList.map { it.name })

            _state.update {
                it.copy(
                    appInfoList = pagedList,
                    currentPaginationPage = paginationPage + 1, // visual display is not 0 indexed
                    lastPaginationPage = lastPageInCurrentFilter + 1,
                    totalAppsInFilter = totalFound,
                    isLoading = false, // Loading complete
                    // Per-source counts for tab badges
                    // Use user prefs + auth state only (not current tab) so badges stay stable across tab switches
                    allCount = (if (currentState.showSteamInLibrary) steamEntries.size else 0) +
                        (if (currentState.showCustomGamesInLibrary) customEntries.size else 0) +
                        (if (currentState.showGOGInLibrary && GOGService.hasStoredCredentials(context)) gogEntries.size else 0) +
                        (if (currentState.showEpicInLibrary && EpicService.hasStoredCredentials(context)) epicEntries.size else 0) +
                        (if (currentState.showAmazonInLibrary && AmazonService.hasStoredCredentials(context)) amazonEntries.size else 0),
                    steamCount = if (currentState.showSteamInLibrary) steamEntries.size else 0,
                    gogCount = if (currentState.showGOGInLibrary && GOGService.hasStoredCredentials(context)) gogEntries.size else 0,
                    epicCount = if (currentState.showEpicInLibrary && EpicService.hasStoredCredentials(context)) epicEntries.size else 0,
                    amazonCount = if (currentState.showAmazonInLibrary && AmazonService.hasStoredCredentials(context)) amazonEntries.size else 0,
                    localCount = if (currentState.showCustomGamesInLibrary) customEntries.size else 0,
                    steamCollectionCounts = steamCollectionCounts,
                )
            }
    }

    /**
     * SQL fast path: filtering, sorting, and LIMIT happen in SQLite so only the visible page of Steam
     * rows is materialized (instead of all ~45k). Non-Steam sources (small) are loaded in memory and
     * merged with the Steam page. Used for the common case; [filterAppsInMemory] handles COMPATIBLE /
     * family-sharing. Note: pagination here is cumulative (take first endIndex), matching the existing
     * "load more" behaviour — so deep paging approaches a full load, but early pages stay cheap.
     */
    private suspend fun filterAppsSql(currentState: LibraryState, paginationPage: Int) {
        val currentFilter = AppFilter.getAppType(currentState.appInfoSortType)
        val typeCodes = currentFilter.map { it.code }
        val currentTab = currentState.currentTab

        // Filesystem-based installed detection, used for the per-item badge (and the comparator's
        // installed tier) so the displayed "installed" state stays accurate even though the SQL
        // ORDER BY uses app_info.is_downloaded as its tier proxy.
        val downloadDirectorySet =
            (DownloadService.getDownloadDirectoryApps() + SteamService.getImportedAppDirs()).toHashSet()
        fun isInDownloadDirectory(item: SteamAppSummary): Boolean {
            val primaryName = SteamService.getAppDirName(item)
            val altName = item.name
            return downloadDirectorySet.contains(primaryName) ||
                (altName.isNotEmpty() && altName != primaryName && downloadDirectorySet.contains(altName))
        }

        // Source include flags: ALL tab honours user prefs, other tabs use their presets.
        val includeSteam = if (currentTab == LibraryTab.ALL) currentState.showSteamInLibrary else currentTab.showSteam
        val includeOpen = if (currentTab == LibraryTab.ALL) currentState.showCustomGamesInLibrary else currentTab.showCustom
        val includeGOG = (if (currentTab == LibraryTab.ALL) currentState.showGOGInLibrary else currentTab.showGoG) &&
            GOGService.hasStoredCredentials(context)
        val includeEpic = (if (currentTab == LibraryTab.ALL) currentState.showEpicInLibrary else currentTab.showEpic) &&
            EpicService.hasStoredCredentials(context)
        val includeAmazon = (if (currentTab == LibraryTab.ALL) currentState.showAmazonInLibrary else currentTab.showAmazon) &&
            AmazonService.hasStoredCredentials(context)

        val installedFilter = currentTab.installedOnly || currentState.appInfoSortType.contains(AppFilter.INSTALLED)
        val includeExpired = if (currentState.appInfoSortType.contains(AppFilter.EXPIRED)) 1 else 0
        val search = currentState.searchQuery

        // ── Steam counts ──
        val steamCountable = typeCodes.isNotEmpty()
        suspend fun steamCount(): Int = if (installedFilter) {
            steamAppDao.countInstalledOwnedAppSummaries(typeCodes, search, includeExpired = includeExpired)
        } else {
            steamAppDao.countOwnedAppSummaries(typeCodes, search, includeExpired = includeExpired)
        }
        val steamBadgeCount = if (steamCountable) steamCount() else 0
        val steamTotalCount = if (steamCountable && includeSteam) steamBadgeCount else 0

        // ── Non-Steam sources (small; built fully in memory) ──
        val customGameItems = if (currentState.appInfoSortType.contains(AppFilter.GAME)) {
            CustomGameScanner.scanAsLibraryItems(query = search)
        } else {
            emptyList()
        }
        // Dedup custom games that are actually imported Steam installs: find which "STEAM_x" candidates
        // are owned Steam apps (page-stable, unlike deduping against only the fetched Steam page).
        val steamPrefix = "${GameSource.STEAM.name}_"
        val steamDupAppIds: Set<String> = if (customGameItems.isNotEmpty()) {
            val candidateIds = customGameItems.mapNotNull {
                if (it.appId.startsWith(steamPrefix)) it.appId.removePrefix(steamPrefix).toIntOrNull() else null
            }
            if (candidateIds.isNotEmpty()) {
                steamAppDao._getOwnedAppSummariesByIds(candidateIds, includeExpired = includeExpired)
                    .mapTo(HashSet()) { "$steamPrefix${it.id}" }
            } else {
                emptySet()
            }
        } else {
            emptySet()
        }
        // COMPATIBLE is never active on this path, so the compatibility predicate is a no-op.
        val nonSteam = buildNonSteamEntries(currentState, customGameItems, steamDupAppIds) { true }

        val gogFinal = if (includeGOG) nonSteam.gogEntries else emptyList()
        val epicFinal = if (includeEpic) nonSteam.epicEntries else emptyList()
        val amazonFinal = if (includeAmazon) nonSteam.amazonEntries else emptyList()
        val customFinal = if (includeOpen) nonSteam.customEntries else emptyList()
        val nonSteamFinalCount = gogFinal.size + epicFinal.size + amazonFinal.size + customFinal.size

        // ── Pagination math ──
        val pageSize = PrefManager.itemsPerPage
        paginationCurrentPage = paginationPage
        val total = steamTotalCount + nonSteamFinalCount
        lastPageInCurrentFilter = if (total == 0) 0 else (total - 1) / pageSize
        val endIndex = min((paginationPage + 1) * pageSize, total)

        // ── Steam page slice ──
        // Fetch the top endIndex Steam rows (already filtered/ordered/limited in SQL). Merging these
        // with all non-Steam and taking endIndex yields the correct global top-endIndex, because the
        // merged top endIndex can use at most endIndex Steam rows.
        val steamPage = if (includeSteam && steamCountable && endIndex > 0) {
            val query = buildLibraryPageQuery(
                types = typeCodes,
                search = search,
                sortOption = currentState.currentSortOption,
                installedFilter = installedFilter,
                limit = endIndex,
                offset = 0,
                includeExpired = includeExpired,
            )
            steamAppDao.pageOwnedAppSummaries(query)
        } else {
            emptyList()
        }
        val steamPageEntries = steamPage.map { item ->
            LibraryEntry(
                item = LibraryItem(
                    index = 0,
                    appId = "$steamPrefix${item.id}",
                    name = item.name,
                    iconHash = item.clientIconHash,
                    capsuleImageUrl = item.getCapsuleUrl(),
                    headerImageUrl = item.headerUrl,
                    heroImageUrl = item.getHeroUrl(),
                    isShared = (PrefManager.steamUserAccountId != 0 && !item.ownerAccountId.contains(PrefManager.steamUserAccountId)),
                    sizeBytes = item.sizeBytes,
                ),
                isInstalled = isInDownloadDirectory(item),
            )
        }

        // ── Merge + sort + slice ──
        val combined = buildList {
            if (includeSteam) addAll(steamPageEntries)
            if (includeOpen) addAll(customFinal)
            if (includeGOG) addAll(gogFinal)
            if (includeEpic) addAll(epicFinal)
            if (includeAmazon) addAll(amazonFinal)
        }
        val sortKeyOf = combined.associate { it.item.appId to NameSortKey.of(it.item.name) }
        val sortComparator = librarySortComparator(currentState, sortKeyOf)
        var pagedList = combined.sortedWith(sortComparator).take(endIndex).mapIndexed { idx, entry ->
            entry.item.copy(
                index = idx,
                isInstalled = entry.isInstalled,
            )
        }
        pagedList = withRecommendation(pagedList, currentState)

        // Per-collection counts for the Steam Collections picker. This path only runs when no
        // collection is currently selected (see onFilterApps), so mirror filterAppsInMemory's
        // computation from the in-memory appList rather than threading collection filtering
        // through the SQL query builder.
        val steamCollectionCounts: Map<String, Int> = steamCollections?.associate { collection ->
            collection.id to appList.count { it.id in collection.appIds }
        } ?: emptyMap()

        // Persist skeleton-loader counts (only when not searching, to keep them accurate).
        if (search.isEmpty()) {
            PrefManager.customGamesCount = customGameItems.size
            PrefManager.steamGamesCount = steamBadgeCount
            PrefManager.gogGamesCount = nonSteam.gogFilteredCount
            PrefManager.gogInstalledGamesCount = nonSteam.gogInstalledCount
            PrefManager.epicGamesCount = nonSteam.epicFilteredCount
            PrefManager.epicInstalledGamesCount = nonSteam.epicInstalledCount
            PrefManager.amazonInstalledGamesCount = nonSteam.amazonInstalledCount
        }

        fetchCompatibilityForPage(pagedList.map { it.name })

        // Badges use prefs + auth state (not the current tab) so they stay stable across tab switches,
        // matching the in-memory path. Steam uses the pre-hidden/category badge count; non-Steam uses
        // pre-hidden/category entry sizes.
        val gogHasCreds = GOGService.hasStoredCredentials(context)
        val epicHasCreds = EpicService.hasStoredCredentials(context)
        val amazonHasCreds = AmazonService.hasStoredCredentials(context)
        val steamBadge = if (currentState.showSteamInLibrary) steamBadgeCount else 0
        val gogBadge = if (currentState.showGOGInLibrary && gogHasCreds) nonSteam.gogEntries.size else 0
        val epicBadge = if (currentState.showEpicInLibrary && epicHasCreds) nonSteam.epicEntries.size else 0
        val amazonBadge = if (currentState.showAmazonInLibrary && amazonHasCreds) nonSteam.amazonEntries.size else 0
        val localBadge = if (currentState.showCustomGamesInLibrary) nonSteam.customEntries.size else 0

        _state.update {
            it.copy(
                appInfoList = pagedList,
                currentPaginationPage = paginationPage + 1, // visual display is not 0 indexed
                lastPaginationPage = lastPageInCurrentFilter + 1,
                totalAppsInFilter = total,
                isLoading = false,
                allCount = steamBadge + localBadge + gogBadge + epicBadge + amazonBadge,
                steamCount = steamBadge,
                gogCount = gogBadge,
                epicCount = epicBadge,
                amazonCount = amazonBadge,
                localCount = localBadge,
                steamCollectionCounts = steamCollectionCounts,
            )
        }
    }

    // Holds the non-Steam (GOG / Epic / Amazon / custom) library entries plus the counts the
    // PrefManager skeleton loaders need. Entries are pre-hidden/category (the caller applies those).
    private data class NonSteamResult(
        val gogEntries: List<LibraryEntry>,
        val epicEntries: List<LibraryEntry>,
        val amazonEntries: List<LibraryEntry>,
        val customEntries: List<LibraryEntry>,
        val gogFilteredCount: Int,
        val epicFilteredCount: Int,
        val amazonFilteredCount: Int,
        val gogInstalledCount: Int,
        val epicInstalledCount: Int,
        val amazonInstalledCount: Int,
    )

    // Builds non-Steam library entries from the in-memory source lists. Shared by the SQL path; the
    // logic mirrors the in-memory path's non-Steam handling (search filter, installed filter, compat
    // filter, custom-game dedup against imported Steam appIds).
    private fun buildNonSteamEntries(
        currentState: LibraryState,
        customGameItems: List<LibraryItem>,
        steamDupAppIds: Set<String>,
        passesCompatibleFilter: (String) -> Boolean,
    ): NonSteamResult {
        val installedOnly = currentState.currentTab.installedOnly ||
            currentState.appInfoSortType.contains(AppFilter.INSTALLED)
        val query = currentState.searchQuery

        val customEntries = customGameItems
            .filter { !steamDupAppIds.contains(it.appId) }
            .map { LibraryEntry(it, true) }

        val filteredGOGGames = gogGameList.asSequence()
            .filter { if (query.isNotEmpty()) matches(it.title, query) else true }
            .filter { if (installedOnly) it.isInstalled else true }
            .toList()
        val gogEntries = filteredGOGGames.filter { passesCompatibleFilter(it.title) }.map { game ->
            LibraryEntry(
                item = LibraryItem(
                    index = 0,
                    appId = "${GameSource.GOG.name}_${game.id}",
                    name = game.title,
                    iconHash = game.iconUrl.ifEmpty { game.imageUrl },
                    capsuleImageUrl = game.iconUrl.ifEmpty { game.imageUrl },
                    headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                    heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                    isShared = false,
                    gameSource = GameSource.GOG,
                ),
                isInstalled = game.isInstalled,
            )
        }

        val filteredEpicGames = epicGameList.asSequence()
            .filter { if (query.isNotEmpty()) matches(it.title, query) else true }
            .filter { if (installedOnly) it.isInstalled else true }
            .toList()
        val epicEntries = filteredEpicGames.filter { passesCompatibleFilter(it.title) }.map { game ->
            LibraryEntry(
                item = LibraryItem(
                    index = 0,
                    appId = "${GameSource.EPIC.name}_${game.id}",
                    name = game.title,
                    iconHash = game.artSquare.ifEmpty { game.artCover },
                    capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
                    headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                    heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                    isShared = false,
                    gameSource = GameSource.EPIC,
                ),
                isInstalled = game.isInstalled,
            )
        }

        val filteredAmazonGames = amazonGameList.asSequence()
            .filter { if (query.isNotEmpty()) matches(it.title, query) else true }
            .filter { if (installedOnly) it.isInstalled else true }
            .toList()
        val amazonEntries = filteredAmazonGames.filter { passesCompatibleFilter(it.title) }.map { game ->
            val layoutHero = AmazonArtwork.layoutHeroFromProductJson(game.productJson)
                .ifEmpty { game.heroUrl.ifEmpty { game.artUrl } }
            LibraryEntry(
                item = LibraryItem(
                    index = 0,
                    appId = "AMAZON_${game.appId}",
                    name = game.title,
                    iconHash = game.artUrl,
                    capsuleImageUrl = game.artUrl,
                    headerImageUrl = layoutHero,
                    heroImageUrl = layoutHero.ifEmpty { game.artUrl },
                    gridHeroImageScale = AmazonArtwork.GRID_HERO_ZOOM_SCALE,
                    isShared = false,
                    gameSource = GameSource.AMAZON,
                ),
                isInstalled = game.isInstalled,
            )
        }

        return NonSteamResult(
            gogEntries = gogEntries,
            epicEntries = epicEntries,
            amazonEntries = amazonEntries,
            customEntries = customEntries,
            gogFilteredCount = filteredGOGGames.size,
            epicFilteredCount = filteredEpicGames.size,
            amazonFilteredCount = filteredAmazonGames.size,
            gogInstalledCount = filteredGOGGames.count { it.isInstalled },
            epicInstalledCount = filteredEpicGames.count { it.isInstalled },
            amazonInstalledCount = filteredAmazonGames.count { it.isInstalled },
        )
    }

    // Builds the library sort comparator for the current SortOption. [sortKeyOf] maps each entry's
    // composite appId to its NameSortKey.
    private fun librarySortComparator(
        currentState: LibraryState,
        sortKeyOf: Map<String, String>,
    ): Comparator<LibraryEntry> {
        return when (currentState.currentSortOption) {
            SortOption.INSTALLED_FIRST -> compareBy<LibraryEntry> { if (it.isInstalled) 0 else 1 }
                .thenBy { sortKeyOf.getValue(it.item.appId) }
            SortOption.NAME_ASC -> compareBy { sortKeyOf.getValue(it.item.appId) }
            SortOption.NAME_DESC -> compareByDescending { sortKeyOf.getValue(it.item.appId) }
            SortOption.RECENTLY_PLAYED -> compareBy<LibraryEntry> { if (it.isInstalled) 0 else 1 }
                .thenBy { sortKeyOf.getValue(it.item.appId) }
            SortOption.SIZE_SMALLEST -> compareBy<LibraryEntry> { it.item.sizeBytes }
                .thenBy { sortKeyOf.getValue(it.item.appId) }
            SortOption.SIZE_LARGEST -> compareByDescending<LibraryEntry> { it.item.sizeBytes }
                .thenBy { sortKeyOf.getValue(it.item.appId) }
            SortOption.FPS_HIGH -> compareByDescending<LibraryEntry> {
                currentState.statsFor(it.item)?.fps ?: -1
            }.thenBy { sortKeyOf.getValue(it.item.appId) }
            SortOption.RUNS_HIGH -> compareByDescending<LibraryEntry> {
                currentState.statsFor(it.item)?.runsGpu ?: -1
            }.thenBy { sortKeyOf.getValue(it.item.appId) }
            SortOption.REVIEWS_HIGH -> compareByDescending<LibraryEntry> {
                currentState.statsFor(it.item)?.reviewsDevice ?: -1
            }.thenBy { sortKeyOf.getValue(it.item.appId) }
            SortOption.REVIEWS_GPU_HIGH -> compareByDescending<LibraryEntry> {
                currentState.statsFor(it.item)?.reviewsGpu ?: -1
            }.thenBy { sortKeyOf.getValue(it.item.appId) }
        }
    }

    // Prepends the recommended game as the first item on the ALL tab when enabled and not searching.
    private fun withRecommendation(pagedList: List<LibraryItem>, currentState: LibraryState): List<LibraryItem> {
        val rec = cachedRecommendation
        if (rec != null &&
            PrefManager.showRecommendations &&
            currentState.currentTab == LibraryTab.ALL &&
            currentState.searchQuery.isEmpty()
        ) {
            val recItem = LibraryItem(
                index = -1,
                appId = "RECOMMENDED_${rec.id}",
                name = rec.name,
                heroImageUrl = rec.heroImageUrl,
                capsuleImageUrl = rec.capsuleImageUrl,
                iconHash = rec.iconUrl ?: rec.capsuleImageUrl,
                isRecommended = true,
                recommendedGameId = rec.id,
                gameSource = GameSource.STEAM,
            )
            return listOf(recItem) + pagedList.map { it.copy(index = it.index + 1) }
        }
        return pagedList
    }

    /**
     * Compares the game name against the search query using an exact match
     * and then again using a normalized form with diacritics removed.
     */
    private fun matches(gameName: String, searchQuery:String): Boolean {
        return gameName.contains(searchQuery, ignoreCase = true) || gameName.unaccent().contains(searchQuery, ignoreCase = true)
    }

    /**
     * Fetches compatibility information for games in paginated batches.
     * Checks cache first, then fetches uncached games in batches of 50.
     */
    private fun fetchCompatibilityForPage(gameNames: List<String>) {
        if (gameNames.isEmpty()) {
            Timber.tag("LibraryViewModel").d("fetchCompatibilityForPage: No game names provided")
            return
        }

        Timber.tag("LibraryViewModel").d("fetchCompatibilityForPage: Fetching compatibility for ${gameNames.size} games, GPU: $gpuName")

        // Don't make API calls if GPU name is unknown
        if (gpuName == "Unknown GPU") {
            Timber.tag("LibraryViewModel").w("Skipping compatibility fetch - GPU name is unknown")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Separate cached and uncached games
                val uncachedGames = mutableListOf<String>()
                val cachedResults = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

                for (gameName in gameNames) {
                    val cached = GameCompatibilityCache.getCached(gameName)
                    if (cached != null) {
                        cachedResults[gameName] = cached
                        Timber.tag("LibraryViewModel").d("Using cached result for: $gameName")
                    } else {
                        uncachedGames.add(gameName)
                    }
                }

                Timber.tag("LibraryViewModel").d("Cached: ${cachedResults.size}, Uncached: ${uncachedGames.size}")

                // Update state with cached results immediately (for instant UI update)
                if (cachedResults.isNotEmpty()) {
                    updateCompatibilityState(cachedResults)
                }

                // Only fetch if there are uncached games
                if (uncachedGames.isEmpty()) {
                    Timber.tag("LibraryViewModel").d("All games in page are cached, skipping API call")
                    return@launch
                }

                // Fetch uncached games in batches of 25
                val batchSize = 25
                val fetchedResults = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

                for (i in uncachedGames.indices step batchSize) {
                    val batch = uncachedGames.subList(i, min(i + batchSize, uncachedGames.size))
                    Timber.tag("LibraryViewModel").d("Fetching batch ${i / batchSize + 1} with ${batch.size} games")
                    val batchResults = GameCompatibilityService.fetchCompatibility(batch, gpuName)

                    if (batchResults != null) {
                        Timber.tag("LibraryViewModel").d("Received ${batchResults.size} results from API")
                        // Cache all results using batch caching
                        GameCompatibilityCache.cacheAll(batchResults)
                        fetchedResults.putAll(batchResults)
                    } else {
                        Timber.tag("LibraryViewModel").w("API returned null for batch")
                    }
                }

                // Update state with newly fetched results
                if (fetchedResults.isNotEmpty()) {
                    updateCompatibilityState(fetchedResults)
                    // Re-apply list filtering once new compatibility data is available
                    if (_state.value.appInfoSortType.contains(AppFilter.COMPATIBLE)) {
                        onFilterApps(paginationCurrentPage)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("LibraryViewModel").e(e, "Error fetching compatibility data: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Updates the state with compatibility results.
     */
    private fun updateCompatibilityState(
        results: Map<String, GameCompatibilityService.GameCompatibilityResponse>
    ) {
        val compatibilityMap = results.mapValues { (gameName, response) ->
            compatibilityStatusFor(response)
        }

        // Update state with compatibility map (merge with existing)
        _state.update { currentState ->
            val mergedMap = currentState.compatibilityMap.toMutableMap()
            mergedMap.putAll(compatibilityMap)
            Timber.tag("LibraryViewModel").d("Updated state with ${compatibilityMap.size} compatibility entries, total: ${mergedMap.size}")
            currentState.copy(compatibilityMap = mergedMap)
        }
    }

    private fun compatibilityStatusFor(
        response: GameCompatibilityService.GameCompatibilityResponse,
    ): GameCompatibilityStatus {
        return when {
            response.isNotWorking -> GameCompatibilityStatus.NOT_COMPATIBLE
            !response.hasBeenTried -> GameCompatibilityStatus.UNKNOWN
            response.gpuPlayableCount > 0 -> GameCompatibilityStatus.GPU_COMPATIBLE
            response.totalPlayableCount > 0 -> GameCompatibilityStatus.COMPATIBLE
            else -> GameCompatibilityStatus.UNKNOWN
        }
    }
}
