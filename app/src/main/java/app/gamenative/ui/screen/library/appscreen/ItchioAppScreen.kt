package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.runtime.Composable
import com.winlator.container.ContainerData
import app.gamenative.data.LibraryItem
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import timber.log.Timber

/**
 * itch.io-specific implementation of BaseAppScreen.
 * No install/download/launch logic yet — all overrides are stubs.
 */
class ItchioAppScreen : BaseAppScreen() {

    companion object {
        private const val TAG = "ItchioAppScreen"
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
        libraryItem.isInstalled

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean {
        Timber.tag(TAG).d("isValidToDownload: not yet implemented for ${libraryItem.appId}")
        return false
    }

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean = false

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float = 0f

    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit,
    ) {
        Timber.tag(TAG).d("onDownloadInstallClick: not yet implemented for ${libraryItem.appId}")
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).d("onPauseResumeClick: not yet implemented for ${libraryItem.appId}")
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).d("onDeleteDownloadClick: not yet implemented for ${libraryItem.appId}")
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        Timber.tag(TAG).d("onUpdateClick: not yet implemented for ${libraryItem.appId}")
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
