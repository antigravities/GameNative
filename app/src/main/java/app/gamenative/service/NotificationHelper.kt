package app.gamenative.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import app.gamenative.MainActivity
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.utils.IntentLaunchManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class NotificationHelper @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "pluvia_foreground_service"
        private const val CHANNEL_NAME = "GameNative Foreground Service"
        private const val NOTIFICATION_ID = 1

        // Separate channel for download progress/completion so we can use
        // IMPORTANCE_DEFAULT (banner on completion) without affecting the
        // persistent foreground service tile (IMPORTANCE_LOW).
        private const val DOWNLOAD_CHANNEL_ID = "pluvia_downloads"
        private const val DOWNLOAD_CHANNEL_NAME = "Downloads"

        const val ACTION_EXIT = "com.oxgames.pluvia.EXIT"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
        createDownloadNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Allows to display GameNative foreground notifications"
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun createDownloadNotificationChannel() {
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            DOWNLOAD_CHANNEL_NAME,
            // DEFAULT importance: silent during progress updates (setOnlyAlertOnce suppresses
            // repeated alerts), but shows a heads-up banner for the completion notification.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Shows download progress and completion alerts"
            setShowBadge(true)
        }

        notificationManager.createNotificationChannel(channel)
    }

    fun notify(content: String) {
        val notification = createForegroundNotification(content)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /**
     * Posts or updates a progress-bar notification for an active download.
     * Uses [appId] as the notification ID so multiple concurrent downloads each
     * get their own tile. Safe because Steam appIds are always > 1 (which is
     * reserved for the foreground service notification).
     *
     * Called at most once every 2 s to avoid spamming the notification system.
     */
    fun notifyDownloadProgress(
        appId: Int,
        gameName: String,
        progressPercent: Int,
        bytesDownloaded: Long,
        bytesTotal: Long,
        queueSize: Int = 0,
    ) {
        val pendingIntent = buildGamePageIntent(appId)
        val smallIconRes = smallIconRes()

        // Append "(+N)" when other games are waiting behind this one.
        val displayName = if (queueSize > 0) "$gameName (+$queueSize)" else gameName

        // Format "1.2 GB / 7.4 GB" using Android's built-in size formatter.
        val bodyText = if (bytesTotal > 0L) {
            "${Formatter.formatShortFileSize(context, bytesDownloaded)} / " +
                Formatter.formatShortFileSize(context, bytesTotal)
        } else {
            "$progressPercent%"
        }

        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_download_progress_title, displayName))
            .setContentText(bodyText)
            .setSmallIcon(smallIconRes)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setAutoCancel(false)
            // Suppress sound/vibration on every incremental update after the first.
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent.coerceIn(0, 100), false)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(appId, notification)
    }

    /**
     * Replaces the progress notification with a dismissible "ready to play" banner.
     * Uses IMPORTANCE_DEFAULT channel so Android shows a heads-up alert.
     */
    fun notifyDownloadComplete(appId: Int, gameName: String) {
        val pendingIntent = buildGamePageIntent(appId)
        val smallIconRes = smallIconRes()

        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_download_complete_title))
            .setContentText(context.getString(R.string.notification_download_complete_text, gameName))
            .setSmallIcon(smallIconRes)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(appId, notification)
    }

    /**
     * Cancels the download progress notification (called on user cancellation or failure).
     */
    fun cancelDownloadNotification(appId: Int) {
        notificationManager.cancel(appId)
    }

    fun createForegroundNotification(content: String): Notification {
        val intent = Intent(
            Intent.ACTION_VIEW,
            "pluvia://home".toUri(),
            context,
            MainActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = Intent(context, SteamService::class.java).apply {
            action = ACTION_EXIT
        }
        val stopPendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(smallIconRes())
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Exit", stopPendingIntent) // 0 = no icon
            .build()
    }

    /** Builds a PendingIntent that opens the game detail page for [appId]. */
    private fun buildGamePageIntent(appId: Int): PendingIntent {
        val intent = Intent(IntentLaunchManager.ACTION_OPEN_GAME_PAGE, null, context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("app_id", appId)
            putExtra("game_source", GameSource.STEAM.name)
        }
        // Use appId as the request code so each game gets an independent PendingIntent.
        return PendingIntent.getActivity(
            context,
            appId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun smallIconRes(): Int = if (PrefManager.useAltNotificationIcon) {
        R.drawable.ic_notification_alt
    } else {
        R.drawable.ic_notification
    }
}
