package app.gamenative.service.itchio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.gamenative.PluviaApp
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

        fun deleteGame(context: Context, gameId: String): Result<Unit> {
            // TODO: delegate to ItchioManager once download/install is implemented
            return Result.failure(NotImplementedError("itch.io game deletion not yet implemented"))
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
