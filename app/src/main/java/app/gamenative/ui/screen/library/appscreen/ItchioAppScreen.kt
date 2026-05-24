package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.winlator.container.ContainerData
import app.gamenative.R
import app.gamenative.data.ItchioGame
import app.gamenative.data.LibraryItem
import app.gamenative.enums.Marker
import app.gamenative.service.itchio.ItchioAuthManager
import app.gamenative.service.itchio.ItchioDownloadManager
import app.gamenative.service.itchio.ItchioInstallerDetector
import app.gamenative.service.itchio.ItchioInstallerType
import app.gamenative.service.itchio.ItchioService
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.ItchioInstallerStep
import app.gamenative.ui.component.dialog.ItchioGameManagerDialog
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        // Mirrors the uninstall-dialog pattern from GOGAppScreen / EpicAppScreen.
        private val uninstallDialogAppIds = mutableStateListOf<String>()

        // Shows the "Run Installer?" dialog for unknown executables that need user consent.
        // Keyed by appId; the stored lambda is the onClickPlay callback to invoke once the
        // user decides, since AdditionalDialogs has no direct access to that callback.
        private val showInstallerConsentDialog = mutableStateMapOf<String, Boolean>()
        private val pendingPlayCallback = mutableStateMapOf<String, (Boolean) -> Unit>()

        fun showUninstallDialog(appId: String) {
            if (!uninstallDialogAppIds.contains(appId)) {
                uninstallDialogAppIds.add(appId)
            }
        }

        fun hideUninstallDialog(appId: String) {
            uninstallDialogAppIds.remove(appId)
        }

        fun shouldShowUninstallDialog(appId: String): Boolean =
            uninstallDialogAppIds.contains(appId)
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

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean {
        val gameId = libraryItem.gameId.toLong()
        // If the user explicitly uninstalled this session, return false immediately.
        // This overrides the stale libraryItem.isInstalled=true that GamePageViewModel cached
        // on page load (it does a one-shot DB fetch and never re-observes the row).
        if (ItchioDownloadManager.isExplicitlyUninstalled(gameId)) return false
        // libraryItem.isInstalled covers "installed in a previous app session" (no in-memory state).
        // ItchioDownloadManager.isInstalled() covers "just installed in this session".
        return libraryItem.isInstalled || ItchioDownloadManager.isInstalled(gameId)
    }

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
     *
     * When the game is already installed, we first check whether the downloaded file is an
     * unknown executable that needs one-time user consent before running as a pre-install step.
     * Known installer types (NSIS, Inno, MSI, 7-zip SFX) run automatically without a prompt.
     */
    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit,
    ) {
        // AppScreenContent routes both "Play" and "Install" taps here. Branch on installed
        // state so that tapping Play doesn't reopen the download file-picker dialog.
        if (isInstalled(context, libraryItem)) {
            val appId = libraryItem.appId
            val gameId = libraryItem.gameId.toLong()

            // Detect the installer type on the IO thread (reads up to 4 MB from disk) so
            // we don't block the main thread, then switch back to Main to update UI state.
            CoroutineScope(Dispatchers.IO).launch {
                val downloadDir = File(context.getExternalFilesDir("itchio"), "$gameId")
                val installerFile = ItchioInstallerStep.findInstallerFile(downloadDir)

                val needsConsent = if (installerFile != null) {
                    val type = ItchioInstallerDetector.detect(installerFile)
                    val dirPath = downloadDir.absolutePath
                    type == ItchioInstallerType.UNKNOWN_EXE &&
                        !MarkerUtils.hasMarker(dirPath, Marker.ITCHIO_INSTALLER_RAN) &&
                        !MarkerUtils.hasMarker(dirPath, Marker.ITCHIO_INSTALLER_CONSENT) &&
                        !MarkerUtils.hasMarker(dirPath, Marker.ITCHIO_INSTALLER_SKIP)
                } else {
                    false
                }

                withContext(Dispatchers.Main) {
                    if (needsConsent) {
                        pendingPlayCallback[appId] = onClickPlay
                        showInstallerConsentDialog[appId] = true
                    } else {
                        onClickPlay(false)
                    }
                }
            }
        } else {
            showDownloadDialog[libraryItem.appId] = true
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        // Pause/resume not yet supported for itch.io; cancel is handled via onDeleteDownloadClick.
        Timber.tag(TAG).d("onPauseResumeClick: not yet implemented for ${libraryItem.appId}")
    }

    /**
     * Handles the trash/delete button. If a download is in progress, cancel it.
     * If the game is installed (and not downloading), show the uninstall confirmation dialog.
     */
    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        if (isDownloading(context, libraryItem)) {
            ItchioDownloadManager.cancelDownload(libraryItem.gameId.toLong())
        } else if (isInstalled(context, libraryItem)) {
            showUninstallDialog(libraryItem.appId)
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).d("onUpdateClick: not yet implemented for ${libraryItem.appId}")
    }

    /** Deletes the installed file and clears the DB row, then shows a snackbar. */
    private fun performUninstall(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).i("performUninstall: gameId=${libraryItem.gameId}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ItchioService.deleteGame(context, libraryItem.gameId.toString())
                if (result.isSuccess) {
                    // Remove the Winlator container if one was created for this game.
                    // deleteContainer is a no-op when no container exists.
                    ContainerUtils.deleteContainer(context, libraryItem.appId)
                    Timber.tag(TAG).i("Uninstalled itch.io game ${libraryItem.appId}")
                    SnackbarManager.show(
                        context.getString(R.string.itchio_uninstall_success, libraryItem.name),
                    )
                } else {
                    val error = result.exceptionOrNull()
                    Timber.tag(TAG).e(error, "Failed to uninstall itch.io game ${libraryItem.appId}")
                    SnackbarManager.show(context.getString(R.string.itchio_uninstall_failed))
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Exception uninstalling itch.io game ${libraryItem.appId}")
                SnackbarManager.show(context.getString(R.string.itchio_uninstall_failed))
            }
        }
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

        // Trigger a state refresh when this game is uninstalled so the Play→Install flip
        // happens immediately without the user navigating away and back.
        scope.launch {
            ItchioDownloadManager.uninstallFlow
                .filter { it == gameId }
                .collect { onStateChanged() }
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

        // ── Download file-picker dialog ──────────────────────────────────────
        val visible = showDownloadDialog[appId] == true

        if (visible) {
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

        // ── Uninstall confirmation dialog ────────────────────────────────────
        var showUninstallDialog by remember { mutableStateOf(shouldShowUninstallDialog(appId)) }
        LaunchedEffect(appId) {
            snapshotFlow { shouldShowUninstallDialog(appId) }
                .collect { shouldShow -> showUninstallDialog = shouldShow }
        }

        if (showUninstallDialog) {
            AlertDialog(
                onDismissRequest = { hideUninstallDialog(appId) },
                title = { Text(stringResource(R.string.itchio_uninstall_game_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.itchio_uninstall_confirmation_message,
                            libraryItem.name,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            hideUninstallDialog(appId)
                            performUninstall(context, libraryItem)
                        },
                    ) {
                        Text(
                            stringResource(R.string.uninstall),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { hideUninstallDialog(appId) }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // ── Installer consent dialog (unknown EXE only) ──────────────────────
        // Shown once when the user taps Play and the downloaded file is an unrecognised
        // executable. "Run" writes a consent marker so the pre-install step activates;
        // "Skip" writes a skip marker so the question is never asked again.
        if (showInstallerConsentDialog[appId] == true) {
            AlertDialog(
                onDismissRequest = {
                    // Dismissed without choosing — don't launch and don't record anything
                    showInstallerConsentDialog.remove(appId)
                    pendingPlayCallback.remove(appId)
                },
                title = { Text(stringResource(R.string.itchio_installer_consent_title)) },
                text = { Text(stringResource(R.string.itchio_installer_consent_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            // Write consent marker on IO thread so the pre-install step
                            // picks it up on the next getPreInstallCommands() call
                            CoroutineScope(Dispatchers.IO).launch {
                                MarkerUtils.addMarker(
                                    File(context.getExternalFilesDir("itchio"), "$gameId")
                                        .absolutePath,
                                    Marker.ITCHIO_INSTALLER_CONSENT,
                                )
                            }
                            showInstallerConsentDialog.remove(appId)
                            pendingPlayCallback.remove(appId)?.invoke(false)
                        },
                    ) { Text(stringResource(R.string.run)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            CoroutineScope(Dispatchers.IO).launch {
                                MarkerUtils.addMarker(
                                    File(context.getExternalFilesDir("itchio"), "$gameId")
                                        .absolutePath,
                                    Marker.ITCHIO_INSTALLER_SKIP,
                                )
                            }
                            showInstallerConsentDialog.remove(appId)
                            pendingPlayCallback.remove(appId)?.invoke(false)
                        },
                    ) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }

    override fun getExportFileExtension(): String = ".itchio"

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? = null

    override fun supportsContainerConfig(): Boolean = true

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        return ContainerUtils.toContainerData(container)
    }

    override fun saveContainerConfig(
        context: Context,
        libraryItem: LibraryItem,
        config: ContainerData,
    ) {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        ContainerUtils.applyToContainer(context, container, config)
    }

    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? = null
}
