package app.gamenative.service.itchio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.gamenative.PluviaApp
import app.gamenative.data.DownloadInfo
import app.gamenative.data.ItchioGame
import app.gamenative.db.dao.ItchioGameDao
import app.gamenative.events.AndroidEvent
import app.gamenative.service.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * itch.io foreground service — orchestrates library sync via ItchioManager.
 *
 * Architecture (mirrors GOGService):
 * - ItchioAuthManager:  Authentication and credential management
 * - ItchioApiClient:    HTTP API layer
 * - ItchioManager:      Game library sync
 * - ItchioService:      This file — Android service lifecycle + sync scheduling
 */
@AndroidEntryPoint
class ItchioService : Service() {

    companion object {
        private const val ACTION_SYNC_LIBRARY = "app.gamenative.ITCHIO_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.ITCHIO_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        private var instance: ItchioService? = null

        // Sync state — stored in companion so it survives service restarts triggered by Android.
        private var syncInProgress: Boolean = false
        private var backgroundSyncJob: Job? = null
        private var lastSyncTimestamp: Long = 0L
        private var hasPerformedInitialSync: Boolean = false

        val isRunning: Boolean
            get() = instance != null

        fun getInstance(): ItchioService? = instance

        fun start(context: Context) {
            if (isRunning) {
                Timber.d("[ItchioService] Already running, skipping start")
                return
            }

            val intent = Intent(context, ItchioService::class.java).apply {
                // First start always syncs; subsequent starts check the throttle.
                if (!hasPerformedInitialSync) {
                    action = ACTION_SYNC_LIBRARY
                } else {
                    val elapsed = System.currentTimeMillis() - lastSyncTimestamp
                    if (elapsed >= SYNC_THROTTLE_MILLIS) action = ACTION_SYNC_LIBRARY
                }
            }
            context.startForegroundService(intent)
        }

        fun stop() {
            instance?.stopSelf()
        }

        fun triggerLibrarySync(context: Context) {
            Timber.i("[ItchioService] Triggering manual library sync")
            val intent = Intent(context, ItchioService::class.java).apply {
                action = ACTION_MANUAL_SYNC
            }
            context.startForegroundService(intent)
        }

        // ==========================================================================
        // SYNC STATE
        // ==========================================================================

        fun isSyncInProgress(): Boolean = syncInProgress

        fun hasActiveOperations(): Boolean =
            syncInProgress || backgroundSyncJob?.isActive == true

        // ==========================================================================
        // AUTHENTICATION
        // ==========================================================================

        fun hasStoredCredentials(context: Context): Boolean =
            ItchioAuthManager.hasStoredCredentials(context)

        suspend fun logout(context: Context): Result<Unit> {
            val result = ItchioAuthManager.logout(context)
            stop()
            return result
        }

        // ==========================================================================
        // LIBRARY
        // ==========================================================================

        suspend fun refreshLibrary(context: Context): Result<Int> {
            val svc = instance
                ?: return Result.failure(IllegalStateException("ItchioService is not running"))
            return ItchioManager.refreshLibrary(context, svc.itchioGameDao)
        }

        suspend fun deleteGame(@Suppress("UNUSED_PARAMETER") context: Context, gameId: String): Result<Unit> {
            val svc = instance
                ?: return Result.failure(IllegalStateException("ItchioService is not running"))

            return try {
                val game = svc.itchioGameDao.getById(gameId)

                // Delete the downloaded file from disk if we have a recorded path.
                val installPath = game?.installPath?.takeIf { it.isNotBlank() }
                if (installPath != null) {
                    val file = java.io.File(installPath)
                    if (file.exists()) {
                        // itch.io installs are single files (zip/installer), not directories.
                        file.delete()
                        Timber.tag("ItchioService").i("Deleted installed file: $installPath")
                    } else {
                        Timber.tag("ItchioService").w("Install file not found at: $installPath")
                    }
                }

                // Clear the install state in the DB.
                svc.itchioGameDao.uninstall(gameId)

                // Clear the in-memory installed set so isInstalled() returns false immediately.
                ItchioDownloadManager.markUninstalled(gameId.toLong())

                // Notify the library screen to refresh the install badge.
                PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(gameId.toIntOrNull() ?: 0))

                Timber.tag("ItchioService").i("Uninstalled game $gameId")
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.tag("ItchioService").e(e, "Failed to uninstall game $gameId")
                Result.failure(e)
            }
        }

        // ==========================================================================
        // DOWNLOADS
        // ==========================================================================

        /** Returns the active [DownloadInfo] for [gameId], or null if not downloading. */
        fun getDownloadInfo(gameId: Long): DownloadInfo? =
            ItchioDownloadManager.getDownloadInfo(gameId)

        /**
         * Looks up the DB row for [gameId]. Returns null if the service is not running
         * or the game is not in the database.
         */
        suspend fun getGame(gameId: Long): ItchioGame? {
            val svc = instance ?: return null
            return svc.itchioGameDao.getById(gameId.toString())
        }

        /**
         * Starts a background download for a single itch.io upload.
         * The download runs on the service's IO scope so it survives UI navigation.
         * Silently no-ops if the service is not running or credentials are missing.
         */
        suspend fun downloadGame(
            context: Context,
            gameId: Long,
            uploadId: Long,
            filename: String,
            totalBytes: Long,
        ): Result<Unit> {
            val svc = instance
                ?: return Result.failure(IllegalStateException("ItchioService is not running"))
            val creds = ItchioAuthManager.getStoredCredentials(context)
                ?: return Result.failure(IllegalStateException("Not logged in to itch.io"))
            val game = svc.itchioGameDao.getById(gameId.toString())
                ?: return Result.failure(IllegalStateException("Game $gameId not found in DB"))

            ItchioDownloadManager.startDownload(
                context = context,
                gameId = gameId,
                uploadId = uploadId,
                filename = filename,
                totalBytes = totalBytes,
                apiKey = creds.apiKey,
                downloadKeyId = game.downloadKeyId.toLongOrNull(),
                dao = svc.itchioGameDao,
                scope = svc.scope,
            )
            return Result.success(Unit)
        }
    }

    // Hilt injects the DAO because ItchioService is annotated @AndroidEntryPoint.
    @Inject
    lateinit var itchioGameDao: ItchioGameDao

    private lateinit var notificationHelper: NotificationHelper

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = { stop() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationHelper = NotificationHelper(applicationContext)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)
        PluviaApp.events.emit(AndroidEvent.ServiceReady)
        Timber.d("[ItchioService] onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("[ItchioService] onStartCommand() action=${intent?.action}")

        // All Android foreground services must post a notification within ~5 s of starting.
        val notification = notificationHelper.createServiceNotification(
            NotificationHelper.NOTIFICATION_ID_ITCHIO, "Connected",
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_ITCHIO,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_ITCHIO, notification)
        }
        notificationHelper.markActive(NotificationHelper.NOTIFICATION_ID_ITCHIO)

        // Determine whether to kick off a library sync for this start command.
        // ACTION_MANUAL_SYNC bypasses the throttle (user-initiated); ACTION_SYNC_LIBRARY obeys it.
        // A null action means Android restarted a killed service — re-sync if overdue.
        val shouldSync = when (intent?.action) {
            ACTION_MANUAL_SYNC -> true
            ACTION_SYNC_LIBRARY -> true
            null -> {
                val elapsed = System.currentTimeMillis() - lastSyncTimestamp
                !hasPerformedInitialSync || elapsed >= SYNC_THROTTLE_MILLIS
            }
            else -> false
        }

        if (shouldSync && !syncInProgress) {
            backgroundSyncJob = scope.launch {
                syncInProgress = true
                try {
                    ItchioManager.refreshLibrary(applicationContext, itchioGameDao)
                    lastSyncTimestamp = System.currentTimeMillis()
                    hasPerformedInitialSync = true
                } finally {
                    syncInProgress = false
                }
            }
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Timber.w("[ItchioService] Foreground service timeout — restarting")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)
        backgroundSyncJob?.cancel()
        syncInProgress = false
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationHelper.cancel(NotificationHelper.NOTIFICATION_ID_ITCHIO)
        instance = null
        Timber.d("[ItchioService] onDestroy()")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!hasActiveOperations()) {
            Timber.i("[ItchioService] Task removed, no active work — stopping")
            stopSelf()
        } else {
            Timber.i("[ItchioService] Task removed, active work exists — keeping alive")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
