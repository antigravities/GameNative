package app.gamenative.service.itchio

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.db.dao.ItchioGameDao
import app.gamenative.utils.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages in-progress itch.io file downloads.
 *
 * Downloads are single-file HTTP streams (zip/installer). The itch.io download
 * endpoint issues a redirect to a CDN URL; OkHttp follows it automatically.
 *
 * Downloaded files land at:
 *   {context.getExternalFilesDir("itchio")}/{gameId}/{filename}
 */
object ItchioDownloadManager {

    private const val TAG = "ItchioDownloadManager"
    private const val CHUNK_SIZE = 64 * 1024 // 64 KB per read
    private const val IN_PROGRESS_MARKER = ".itchio_download_in_progress"

    // Keyed by game ID. Uses DownloadInfo so AppScreenContent gets ETA/speed for free.
    private val activeDownloads = ConcurrentHashMap<Long, DownloadInfo>()

    // In-memory record of games that finished downloading this session. Survives until the
    // process dies; on the next launch GamePageViewModel re-reads isInstalled from the DB.
    // Checked by ItchioAppScreen.isInstalled() so the game-page button updates immediately
    // after completion without waiting for GamePageViewModel to re-fetch the DB row.
    private val installedGameIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    // Tracks games explicitly uninstalled this session. Checked by ItchioAppScreen.isInstalled()
    // to override the stale libraryItem.isInstalled=true that GamePageViewModel cached on page load.
    private val uninstalledGameIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun isInstalled(gameId: Long): Boolean = gameId in installedGameIds
    fun isExplicitlyUninstalled(gameId: Long): Boolean = gameId in uninstalledGameIds

    // Broadcasts (gameId, progress 0..1) to any subscriber. extraBufferCapacity prevents
    // tryEmit from dropping events when the collector is briefly busy between chunks.
    private val _progressFlow = MutableSharedFlow<Pair<Long, Float>>(extraBufferCapacity = 64)
    val progressFlow: SharedFlow<Pair<Long, Float>> = _progressFlow

    // Signals that a game was uninstalled. observeGameState() subscribes to this to trigger
    // a performStateRefresh() so the Play→Install button flip happens without navigating away.
    private val _uninstallFlow = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val uninstallFlow: SharedFlow<Long> = _uninstallFlow

    fun isDownloading(gameId: Long): Boolean = activeDownloads.containsKey(gameId)

    /** Returns 0f when not downloading, or a 0..1 fraction otherwise. */
    fun getProgress(gameId: Long): Float = activeDownloads[gameId]?.getProgress() ?: 0f

    fun getDownloadInfo(gameId: Long): DownloadInfo? = activeDownloads[gameId]

    fun cancelDownload(gameId: Long) {
        activeDownloads[gameId]?.cancel()
        // Entry is removed inside the download coroutine's finally block.
    }

    /** Records an uninstall: clears the installed flag and signals observeGameState listeners. */
    fun markUninstalled(gameId: Long) {
        installedGameIds.remove(gameId)
        uninstalledGameIds.add(gameId)
        _uninstallFlow.tryEmit(gameId)
    }

    /**
     * Starts a coroutine (on [scope]) that downloads the given itch.io upload and
     * streams it to disk. Returns immediately; progress is tracked via [DownloadInfo].
     *
     * Silently no-ops if a download for [gameId] is already in progress.
     */
    fun startDownload(
        context: Context,
        gameId: Long,
        uploadId: Long,
        filename: String,
        totalBytes: Long,
        apiKey: String,
        downloadKeyId: Long?,
        dao: ItchioGameDao,
        scope: CoroutineScope,
    ) {
        if (activeDownloads.containsKey(gameId)) {
            Timber.tag(TAG).w("Download already in progress for game $gameId — ignoring")
            return
        }

        // A new download supersedes any prior uninstall record for this game so that
        // isExplicitlyUninstalled() returns false once the download completes.
        uninstalledGameIds.remove(gameId)

        // DownloadInfo uses Int for gameId (Steam-era type). itch.io IDs fit in Int for all
        // practical games, but we coerce just in case.
        val downloadInfo = DownloadInfo(
            jobCount = 1,
            gameId = gameId.toInt(),
            downloadingAppIds = CopyOnWriteArrayList(listOf(gameId.toInt())),
        )
        downloadInfo.setTotalExpectedBytes(totalBytes)
        downloadInfo.setActive(true)
        activeDownloads[gameId] = downloadInfo
        // Signal that a download has started so observeGameState() subscribers update the UI.
        _progressFlow.tryEmit(gameId to 0f)

        val job = scope.launch(Dispatchers.IO) {
            try {
                performDownload(context, gameId, uploadId, filename, apiKey, downloadKeyId, downloadInfo, dao)
            } catch (e: CancellationException) {
                Timber.tag(TAG).i("Download cancelled for game $gameId")
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Download failed for game $gameId")
                downloadInfo.failedToDownload()
            } finally {
                activeDownloads.remove(gameId)
            }
        }
        downloadInfo.setDownloadJob(job)
    }

    private suspend fun performDownload(
        context: Context,
        gameId: Long,
        uploadId: Long,
        filename: String,
        apiKey: String,
        downloadKeyId: Long?,
        downloadInfo: DownloadInfo,
        dao: ItchioGameDao,
    ) = withContext(Dispatchers.IO) {
        // Build destination: Android/data/<package>/files/itchio/<gameId>/<filename>
        val destDir = File(context.getExternalFilesDir("itchio"), "$gameId")
        destDir.mkdirs()
        val destFile = File(destDir, filename)
        val markerFile = File(destDir, IN_PROGRESS_MARKER)

        markerFile.createNewFile()

        try {
            var url = "https://api.itch.io/uploads/$uploadId/download?api_key=$apiKey"
            if (downloadKeyId != null) url += "&download_key_id=$downloadKeyId"

            Timber.tag(TAG).i("Starting download: game=$gameId upload=$uploadId → ${destFile.path}")

            val request = Request.Builder().url(url).build()
            // OkHttp follows the itch.io 302 redirect to the CDN URL automatically.
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code} downloading upload $uploadId")
                }

                response.body?.let { body ->
                    body.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            val buffer = ByteArray(CHUNK_SIZE)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadInfo.updateBytesDownloaded(bytesRead.toLong())
                                downloadInfo.emitProgressChange()
                                _progressFlow.tryEmit(gameId to downloadInfo.getProgress())
                            }
                        }
                    }
                } ?: throw Exception("Empty response body for upload $uploadId")
            }

            // Success — clean up marker and record the download path in DB
            markerFile.delete()
            val game = dao.getById(gameId.toString())
            if (game != null) {
                dao.update(game.copy(installPath = destFile.absolutePath, isInstalled = true))
            }
            // Add to the in-memory set BEFORE emitting 1f so that ItchioAppScreen.isInstalled()
            // returns true by the time observeGameState's onStateChanged() fires and
            // BaseAppScreen.performStateRefresh() re-checks the installed state.
            installedGameIds.add(gameId)
            _progressFlow.tryEmit(gameId to 1f)
            Timber.tag(TAG).i("Download complete: game=$gameId → ${destFile.path}")
        } catch (e: CancellationException) {
            // On cancellation, leave the partial file (future resume support) but keep the marker.
            Timber.tag(TAG).i("Download cancelled mid-stream for game $gameId")
            throw e
        } catch (e: Exception) {
            // On error, remove the incomplete file and marker to avoid a corrupt state.
            destFile.delete()
            markerFile.delete()
            throw e
        }
    }
}
