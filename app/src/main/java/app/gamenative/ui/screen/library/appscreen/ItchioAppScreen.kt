package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.winlator.container.ContainerData
import app.gamenative.data.ItchioGame
import app.gamenative.data.LibraryItem
import app.gamenative.service.itchio.ItchioAuthManager
import app.gamenative.service.itchio.ItchioDownloadManager
import app.gamenative.service.itchio.ItchioService
import app.gamenative.ui.component.dialog.ItchioGameManagerDialog
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * itch.io-specific implementation of BaseAppScreen.
 * Handles fetching available uploads, showing the file-picker dialog, and
 * triggering downloads via [ItchioDownloadManager].
 */
class ItchioAppScreen : BaseAppScreen() {

    companion object {
        private const val TAG = "ItchioAppScreen"

        // Keyed by appId. Written from the non-composable onDownloadInstallClick();
        // read inside the composable AdditionalDialogs() via snapshotFlow / direct read.
        // This mirrors the companion-object state-map pattern used in BaseAppScreen.
        private val showDownloadDialog = mutableStateMapOf<String, Boolean>()
    }

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem,
    ): GameDisplayInfo {
        return GameDisplayInfo(
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            name = libraryItem.name,
            developer = "",
            releaseDate = 0L,
            iconUrl = libraryItem.clientIconUrl,
            headerUrl = libraryItem.headerImageUrl,
            heroImageUrl = libraryItem.heroImageUrl,
        )
    }

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean =
        // libraryItem.isInstalled reflects the DB value at page-load time (GamePageViewModel
        // does a one-shot fetch and never re-observes). ItchioDownloadManager.isInstalled()
        // is the live in-memory fallback that becomes true when a download completes this
        // session, allowing performStateRefresh() to see the correct state immediately.
        libraryItem.isInstalled || ItchioDownloadManager.isInstalled(libraryItem.gameId.toLong())

    /** True when the user has stored itch.io credentials and the service is available. */
    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean =
        ItchioService.hasStoredCredentials(context)

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean =
        ItchioDownloadManager.isDownloading(libraryItem.gameId.toLong())

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float =
        ItchioDownloadManager.getProgress(libraryItem.gameId.toLong())

    /**
     * Shows the download file-picker dialog by setting state that [AdditionalDialogs] observes.
     * The actual dialog + download kick-off happens on the UI thread in AdditionalDialogs.
     */
    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit,
    ) {
        // AppScreenContent routes both "Play" and "Install" taps here. Branch on installed
        // state so that tapping Play doesn't reopen the download file-picker dialog.
        if (isInstalled(context, libraryItem)) {
            onClickPlay(true)
        } else {
            showDownloadDialog[libraryItem.appId] = true
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        // Pause/resume not yet supported for itch.io; cancel is handled via onDeleteDownloadClick.
        Timber.tag(TAG).d("onPauseResumeClick: not yet implemented for ${libraryItem.appId}")
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        ItchioDownloadManager.cancelDownload(libraryItem.gameId.toLong())
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).d("onUpdateClick: not yet implemented for ${libraryItem.appId}")
    }

    /**
     * Register a progress listener so [BaseAppScreen.Content]'s polling loop stays in sync
     * with the live download without busy-waiting.
     */
    override fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)?,
    ): (() -> Unit) {
        val gameId = libraryItem.gameId.toLong()
        // Create a scope that lives independently of any composable lifecycle.
        // BaseAppScreen's DisposableEffect calls the returned lambda to cancel it on dispose.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        scope.launch {
            ItchioDownloadManager.progressFlow
                .filter { (id, _) -> id == gameId }
                .collect { (_, progress) ->
                    onProgressChanged(progress)
                    // 0f = download just started, ≥1f = finished — both need a full state refresh
                    // so the Install/Play button and downloading indicators update correctly.
                    if (progress == 0f || progress >= 1f) onStateChanged()
                }
        }

        return { scope.cancel() }
    }

    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
    ) {
        val context = LocalContext.current
        val appId = libraryItem.appId
        val gameId = libraryItem.gameId.toLong()

        // React to showDownloadDialog changes from onDownloadInstallClick()
        val visible = showDownloadDialog[appId] == true

        if (!visible) return

        // Load credentials once — doesn't change while the dialog is open.
        val credentials = remember(appId) {
            ItchioAuthManager.getStoredCredentials(context)
        }

        // Load the DB row asynchronously to get downloadKeyId without blocking the UI thread.
        // Initial value null means "still loading".
        val game by produceState<ItchioGame?>(initialValue = null, key1 = gameId) {
            value = ItchioService.getGame(gameId)
        }

        ItchioGameManagerDialog(
            visible = true,
            gameId = gameId,
            gameName = libraryItem.name,
            heroImageUrl = libraryItem.heroImageUrl,
            apiKey = credentials?.apiKey ?: "",
            // Wait until the DB row is loaded before passing the key. While game == null the
            // dialog shows its loading state anyway (it's fetching uploads at the same time).
            downloadKeyId = game?.downloadKeyId?.toLongOrNull(),
            onDownload = { selectedUploads ->
                showDownloadDialog.remove(appId)
                // Must NOT use rememberCoroutineScope() here — removing from showDownloadDialog
                // triggers a recomposition that exits AdditionalDialogs, which cancels any
                // rememberCoroutineScope scope before the coroutine body can run. Use an explicit
                // IO scope that outlives the composable, matching Steam's GameManagerDialog pattern.
                CoroutineScope(Dispatchers.IO).launch {
                    val apiKey = credentials?.apiKey ?: return@launch
                    selectedUploads.forEach { upload ->
                        ItchioService.downloadGame(
                            context = context,
                            gameId = gameId,
                            uploadId = upload.id,
                            filename = upload.filename,
                            totalBytes = upload.size,
                        )
                    }
                }
            },
            onDismissRequest = { showDownloadDialog.remove(appId) },
        )
    }

    override fun getExportFileExtension(): String = ".itchio"

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? = null

    override fun supportsContainerConfig(): Boolean = false

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData =
        ContainerData()

    override fun saveContainerConfig(
        context: Context,
        libraryItem: LibraryItem,
        config: ContainerData,
    ) {}

    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? = null
}
