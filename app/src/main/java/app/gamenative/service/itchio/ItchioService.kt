package app.gamenative.service.itchio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.service.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

/**
 * itch.io Service — thin abstraction layer that will delegate to managers (not yet implemented).
 *
 * Architecture (planned, mirrors GOGService):
 * - ItchioAuthManager:     Authentication and credential management
 * - ItchioManager:         Game library, installation, and launch
 * - ItchioDownloadManager: Download logic
 * - ItchioApiClient:       HTTP API layer
 * - ItchioConstants:       Shared constants (URLs, paths)
 * - ItchioDataModels:      API response data models
 */
@AndroidEntryPoint
class ItchioService : Service() {

    companion object {
        private const val ACTION_SYNC_LIBRARY = "app.gamenative.ITCHIO_SYNC_LIBRARY"
        private const val ACTION_MANUAL_SYNC = "app.gamenative.ITCHIO_MANUAL_SYNC"
        private const val SYNC_THROTTLE_MILLIS = 15 * 60 * 1000L // 15 minutes

        private var instance: ItchioService? = null

        // Sync tracking
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
                // On first start always sync; on subsequent starts check throttle
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
        // AUTHENTICATION — not yet implemented
        // ==========================================================================

        fun hasStoredCredentials(context: Context): Boolean =
            ItchioAuthManager.hasStoredCredentials(context)

        suspend fun logout(context: Context): Result<Unit> {
            val result = ItchioAuthManager.logout(context)
            stop()
            return result
        }

        // ==========================================================================
        // LIBRARY — not yet implemented
        // ==========================================================================

        suspend fun refreshLibrary(context: Context): Result<Int> {
            // TODO: delegate to ItchioManager
            return Result.failure(NotImplementedError("itch.io library sync not yet implemented"))
        }

        suspend fun deleteGame(context: Context, gameId: String): Result<Unit> {
            // TODO: delegate to ItchioManager
            return Result.failure(NotImplementedError("itch.io game deletion not yet implemented"))
        }
    }

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

        // TODO: kick off background library sync based on intent action (mirrors GOGService)

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
