package app.gamenative.utils

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.data.GameSource
import app.gamenative.events.AndroidEvent
import app.gamenative.mods.NexusModManager
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.download.SteamDownloadMode
import com.google.android.play.core.splitcompat.SplitCompat
import com.winlator.container.ContainerData
import com.winlator.xenvironment.ImageFsInstaller
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Steam game operations shared by the regular game page (SteamAppScreen) and the minimal
 * launcher, so both run exactly the same sequence. UI concerns (dialogs, snackbars,
 * navigation) stay with the callers.
 */
object SteamGameOps {
    private fun containerId(gameId: Int) = "${GameSource.STEAM.name}_$gameId"

    /**
     * Uninstalls a Steam game: deletes its files and DB state, its Nexus mod installs, and its
     * container, then emits [AndroidEvent.LibraryInstallStatusChanged] on success.
     * @return whether the files were deleted.
     */
    suspend fun uninstall(context: Context, gameId: Int): Boolean {
        val appId = containerId(gameId)
        // Resolve the install dir before deleting (it's only returned while installed).
        val gameRootDir = if (SteamService.isAppInstalled(gameId)) File(SteamService.getAppDirPath(gameId)) else null

        val success = SteamService.deleteApp(gameId)
        DownloadService.invalidateCache()
        if (success) {
            runCatching {
                NexusModManager.deleteInstallsForApp(context = context, appId = appId, gameRootDir = gameRootDir)
            }.onFailure { Timber.w(it, "Failed to clean up Nexus mods for $appId") }
        }
        withContext(Dispatchers.Main) {
            ContainerUtils.deleteContainer(context, appId)
            if (success) PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(gameId, GameSource.STEAM))
        }
        return success
    }

    /**
     * Cancels an in-progress or paused download and deletes the partial files. The caller
     * should invoke this from a coroutine; the cancel itself is main-thread safe.
     */
    suspend fun cancelDownload(gameId: Int) {
        SteamService.getAppDownloadInfo(gameId)?.cancel()
        SteamService.workshopPausedApps.remove(gameId)
        withContext(Dispatchers.IO) {
            SteamService.deleteApp(gameId)
            DownloadService.invalidateCache()
            PluviaApp.events.emit(AndroidEvent.LibraryInstallStatusChanged(gameId, GameSource.STEAM))
        }
    }

    /** Saves container settings; a game-language change re-downloads the language depots. */
    fun saveContainerConfig(context: Context, gameId: Int, config: ContainerData) {
        val appId = containerId(gameId)
        val container = ContainerUtils.getContainer(context, appId)
        ContainerUtils.applyToContainer(context, appId, config)

        if (container.language != config.language) {
            CoroutineScope(Dispatchers.IO).launch {
                SteamService.downloadApp(gameId, SteamDownloadMode.UPDATE)
            }
        }
    }

    /**
     * Downloads (if needed) and installs the ImageFS base system for [appId]'s container
     * variant. Call on an IO coroutine; throws on failure.
     */
    suspend fun installImageFs(context: Context, appId: String) {
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val variant = container.containerVariant

        if (!SteamService.isImageFsInstallable(context, variant)) {
            withContext(Dispatchers.IO) {
                SteamService.downloadImageFs(
                    onDownloadProgress = { },
                    this,
                    variant = variant,
                    context = context,
                ).await()
            }
        }
        if (!SteamService.isImageFsInstalled(context)) {
            withContext(Dispatchers.Main) {
                SplitCompat.install(context)
            }
            withContext(Dispatchers.IO) {
                ImageFsInstaller.installIfNeededFuture(context, context.assets, container) { }.get()
            }
        }
    }
}
