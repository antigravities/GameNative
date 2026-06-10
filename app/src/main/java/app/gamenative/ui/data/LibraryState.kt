package app.gamenative.ui.data

import app.gamenative.PrefManager
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamTag
import app.gamenative.ui.component.dialog.state.CategoryDialogState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.SortOption
import java.util.EnumSet

data class LibraryState(
    val appInfoSortType: EnumSet<AppFilter> = PrefManager.libraryFilter,
    val appInfoList: List<LibraryItem> = emptyList(),
    val isRefreshing: Boolean = false,

    // Human readable, not 0-indexed
    val totalAppsInFilter: Int = 0,
    val currentPaginationPage: Int = 1,
    val lastPaginationPage: Int = 1,

    val modalBottomSheet: Boolean = false,

    val isSearching: Boolean = false,
    val searchQuery: String = "",

    // App Source filters (Steam / Custom Games / GOG / Epic / Amazon)
    val showSteamInLibrary: Boolean = PrefManager.showSteamInLibrary,
    val showCustomGamesInLibrary: Boolean = PrefManager.showCustomGamesInLibrary,
    val showGOGInLibrary: Boolean = PrefManager.showGOGInLibrary,
    val showEpicInLibrary: Boolean = PrefManager.showEpicInLibrary,
    val showAmazonInLibrary: Boolean = PrefManager.showAmazonInLibrary,

    // Loading state for skeleton loaders
    val isLoading: Boolean = false,

    // Number of app PICS requests queued but not yet written to the DB.
    // Non-zero while the library is still syncing after login.
    val picsSyncPending: Int = 0,

    // Total app count for a full refreshAllApps() run; 0 during normal incremental syncs.
    // When non-zero the banner shows "N of M" instead of just "N".
    val picsSyncTotal: Int = 0,

    // Apps sent to the PICS channel so far during a full refreshAllApps() run.
    // Increases 0 → picsSyncTotal in steps of 256; 0 during normal incremental syncs.
    val picsSyncQueued: Int = 0,

    // Refresh counter that increments when custom game images are fetched
    // Used to trigger UI recomposition to show newly downloaded images
    val imageRefreshCounter: Long = 0,

    // Compatibility status map: game name -> compatibility status
    val compatibilityMap: Map<String, GameCompatibilityStatus> = emptyMap(),

    // Device-specific play stats, grouped by platform then game name
    val deviceGameStats: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap(),

    // GPU-specific play stats (across all devices with this GPU), grouped by platform then game name
    val gpuGameStats: Map<GameSource, Map<String, DeviceGameStats>> = emptyMap(),

    // Sort option for the library
    val currentSortOption: SortOption = PrefManager.librarySortOption,

    // Options panel open state
    val isOptionsPanelOpen: Boolean = false,

    // Current library tab for quick filter access
    val currentTab: LibraryTab = LibraryTab.ALL,

    // Per-source game counts for tab badges
    val allCount: Int = 0,
    val steamCount: Int = 0,
    val gogCount: Int = 0,
    val epicCount: Int = 0,
    val amazonCount: Int = 0,
    val localCount: Int = 0,

    // Active category filters (empty = no filtering; categories are OR-ed together)
    val selectedCategories: Set<String> = PrefManager.selectedCategories,

    // State for the "Add to Category" dialog triggered from the game options panel
    val categoryDialogState: CategoryDialogState = CategoryDialogState(),

    // Active Steam store tag ID filters (empty = no filtering; tags are OR-ed together)
    val selectedTagIds: Set<Int> = PrefManager.selectedTagIds,
    // All known Steam tags, populated on first library open from the steam_tag DB table.
    val availableTags: List<SteamTag> = emptyList(),
)

/**
 * Stats shown on a library card. Runs and 5-star reviews are counts that default to 0 when their
 * dataset has no entry (absence means "none recorded"). FPS and session are device measurements
 * that are unknown without a run, so they are null (rendered as "?") and never fall back to GPU.
 */
data class GameCardStats(
    val runsGpu: Int,
    val reviewsDevice: Int,
    val reviewsGpu: Int,
    val fps: Int?,
    val sessionSec: Int?,
)

fun LibraryState.statsFor(item: LibraryItem): GameCardStats? = statsFor(item.gameSource, item.name)

/** Combined device + GPU stats for a game, or null when neither dataset has an entry. */
fun LibraryState.statsFor(source: GameSource, name: String): GameCardStats? {
    val device = deviceGameStats[source]?.get(name)
    val gpu = gpuGameStats[source]?.get(name)
    if (device == null && gpu == null) return null
    return GameCardStats(
        runsGpu = gpu?.successfulRuns ?: 0,
        reviewsDevice = device?.fiveStarReviews ?: 0,
        reviewsGpu = gpu?.fiveStarReviews ?: 0,
        fps = device?.medianFps,
        sessionSec = device?.medianSessionSec,
    )
}
