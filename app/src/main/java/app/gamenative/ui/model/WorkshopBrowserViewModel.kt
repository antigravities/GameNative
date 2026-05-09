package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.NetworkMonitor
import app.gamenative.service.SteamService
import app.gamenative.workshop.WorkshopBrowser
import app.gamenative.workshop.WorkshopItemDetail
import app.gamenative.workshop.WorkshopManager
import `in`.dragonbra.javasteam.enums.EResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Drives the in-app Workshop Browser screen.
 *
 * Holds query state (search text, sort, page), handles RPC calls to
 * [WorkshopBrowser], and runs the post-subscribe sequence
 * (RPC → DB.enabledWorkshopItemIds → [WorkshopManager.startWorkshopDownload])
 * that mirrors `SteamAppScreen.kt:1262-1283`.
 *
 * Plain ViewModel (not Hilt) — accesses [SteamService.instance] for SteamClient
 * and DAO. Created per-screen with [androidx.lifecycle.viewmodel.compose.viewModel].
 */
class WorkshopBrowserViewModel(val appId: Int) : ViewModel() {

    private val tag = "WorkshopBrowserVM"

    data class UiState(
        val items: List<WorkshopItemDetail> = emptyList(),
        val searchText: String = "",
        val sort: WorkshopBrowser.QueryType = WorkshopBrowser.QueryType.Popular,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val page: Int = 0,
        val totalResults: Int = 0,
        val hasMore: Boolean = false,
        val subscribeInProgress: Set<Long> = emptySet(),
        val subscribedIds: Set<Long> = emptySet(),
    )

    data class TransientMessage(val text: String, val isError: Boolean)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<TransientMessage?>(null)
    val toast: StateFlow<TransientMessage?> = _toast.asStateFlow()

    private var loadJob: Job? = null
    private var searchDebounceJob: Job? = null

    init {
        loadFirstPage()
    }

    fun onSearchChanged(text: String) {
        if (text == _state.value.searchText) return
        // Update the visible text immediately for responsive UI…
        _state.update { it.copy(searchText = text) }
        // …but defer the actual RPC by 300 ms so a burst of keystrokes
        // collapses to a single query. Each new keystroke cancels the
        // pending debounce + any in-flight query.
        searchDebounceJob?.cancel()
        searchDebounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            loadFirstPage()
        }
    }

    fun onSortChanged(sort: WorkshopBrowser.QueryType) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        loadFirstPage()
    }

    fun loadFirstPage() {
        // Cancel any in-flight query AND reset isLoading to false — without this
        // reset, loadNextPage()'s `if (current.isLoading) return` early-returns
        // because the cancelled job never got to its `isLoading = false` cleanup.
        loadJob?.cancel()
        _state.update {
            it.copy(items = emptyList(), page = 0, hasMore = false, errorMessage = null, isLoading = false)
        }
        loadNextPage()
    }

    fun loadNextPage() {
        val current = _state.value
        if (current.isLoading) return
        if (current.page > 0 && !current.hasMore) return

        val steamClient = SteamService.instance?.steamClient
        if (steamClient == null) {
            _state.update { it.copy(errorMessage = "Steam client unavailable", isLoading = false) }
            return
        }

        _state.update { it.copy(isLoading = true, errorMessage = null) }
        val targetPage = current.page + 1

        loadJob = viewModelScope.launch {
            val result = WorkshopBrowser.queryItems(
                appId = appId,
                steamClient = steamClient,
                searchText = current.searchText,
                sort = current.sort,
                page = targetPage,
                perPage = PAGE_SIZE,
            )

            if (!result.succeeded) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load Workshop items",
                    )
                }
                return@launch
            }

            // Resolve subscription state for the newly fetched items in the background;
            // failure is non-fatal — the badge just won't appear.
            val newIds = result.items.map { it.item.publishedFileId }
            val subscribedMap = if (newIds.isNotEmpty()) {
                WorkshopBrowser.areFilesInSubscriptionList(appId, newIds, steamClient)
            } else emptyMap()

            _state.update { prev ->
                val newSubscribed = prev.subscribedIds + subscribedMap.filterValues { it }.keys
                prev.copy(
                    items = prev.items + result.items,
                    page = targetPage,
                    totalResults = result.totalResults,
                    hasMore = result.hasMore,
                    isLoading = false,
                    subscribedIds = newSubscribed,
                )
            }
        }
    }

    /**
     * Run the full Subscribe sequence:
     *   1. PublishedFile.Subscribe RPC
     *   2. update DB enabledWorkshopItemIds
     *   3. trigger WorkshopManager.startWorkshopDownload (if game installed + online)
     */
    fun subscribe(publishedFileId: Long, context: Context) {
        val steamClient = SteamService.instance?.steamClient ?: run {
            postToast("Steam client unavailable", isError = true)
            return
        }

        markSubscribeInProgress(publishedFileId, true)

        viewModelScope.launch {
            try {
                val rpcResult = WorkshopBrowser.subscribeToItem(publishedFileId, appId, steamClient)
                if (rpcResult != EResult.OK) {
                    Timber.tag(tag).w("Subscribe RPC returned $rpcResult for $publishedFileId")
                    postToast("Subscribe failed: $rpcResult", isError = true)
                    return@launch
                }

                // 2. update DB
                withContext(Dispatchers.IO) {
                    val appDao = SteamService.instance?.appDao
                    if (appDao != null) {
                        val current = WorkshopManager.parseEnabledIds(appDao.getEnabledWorkshopItemIds(appId))
                        val updated = current + publishedFileId
                        appDao.updateWorkshopState(appId, updated.isNotEmpty(), updated.joinToString(","))

                        // 3. trigger download (best-effort)
                        if (SteamService.isAppInstalled(appId) && NetworkMonitor.hasInternet.value) {
                            WorkshopManager.startWorkshopDownload(appId, updated, context)
                        }
                    }
                }

                _state.update { it.copy(subscribedIds = it.subscribedIds + publishedFileId) }
                postToast("Subscribed", isError = false)
            } catch (e: Exception) {
                Timber.tag(tag).e(e, "Subscribe flow failed for $publishedFileId")
                postToast("Subscribe error", isError = true)
            } finally {
                markSubscribeInProgress(publishedFileId, false)
            }
        }
    }

    fun unsubscribe(publishedFileId: Long, context: Context) {
        val steamClient = SteamService.instance?.steamClient ?: run {
            postToast("Steam client unavailable", isError = true)
            return
        }

        markSubscribeInProgress(publishedFileId, true)

        viewModelScope.launch {
            try {
                val rpcResult = WorkshopBrowser.unsubscribeFromItem(publishedFileId, appId, steamClient)
                if (rpcResult != EResult.OK) {
                    Timber.tag(tag).w("Unsubscribe RPC returned $rpcResult for $publishedFileId")
                    postToast("Unsubscribe failed: $rpcResult", isError = true)
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val appDao = SteamService.instance?.appDao ?: return@withContext
                    val current = WorkshopManager.parseEnabledIds(appDao.getEnabledWorkshopItemIds(appId))
                    val updated = current - publishedFileId
                    appDao.updateWorkshopState(appId, updated.isNotEmpty(), updated.joinToString(","))
                }

                _state.update { it.copy(subscribedIds = it.subscribedIds - publishedFileId) }
                postToast("Unsubscribed", isError = false)
            } catch (e: Exception) {
                Timber.tag(tag).e(e, "Unsubscribe flow failed for $publishedFileId")
                postToast("Unsubscribe error", isError = true)
            } finally {
                markSubscribeInProgress(publishedFileId, false)
            }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    private fun postToast(text: String, isError: Boolean) {
        _toast.value = TransientMessage(text, isError)
    }

    private fun markSubscribeInProgress(publishedFileId: Long, inProgress: Boolean) {
        _state.update { prev ->
            val newSet = if (inProgress) {
                prev.subscribeInProgress + publishedFileId
            } else {
                prev.subscribeInProgress - publishedFileId
            }
            prev.copy(subscribeInProgress = newSet)
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val SEARCH_DEBOUNCE_MS = 300L

        /**
         * Factory for [WorkshopBrowserViewModel] that captures the appId argument.
         * Used by [androidx.lifecycle.viewmodel.compose.viewModel] in browser/detail screens.
         */
        fun factory(appId: Int): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == WorkshopBrowserViewModel::class.java)
                    return WorkshopBrowserViewModel(appId) as T
                }
            }
    }
}
