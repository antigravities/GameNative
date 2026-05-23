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
        private const val GROUP_KEY = "app.gamenative.services"

        const val NOTIFICATION_ID_STEAM = 1
        const val NOTIFICATION_ID_GOG = 2
        const val NOTIFICATION_ID_EPIC = 3
        const val NOTIFICATION_ID_AMAZON = 4
        const val NOTIFICATION_ID_ITCHIO = 5
        private const val NOTIFICATION_ID_SUMMARY = 100

        // Separate channel for download progress/completion so we can use
        // IMPORTANCE_DEFAULT (banner on completion) without affecting the
        // persistent foreground service tile (IMPORTANCE_LOW).
        private const val DOWNLOAD_CHANNEL_ID = "pluvia_downloads"
        private const val DOWNLOAD_CHANNEL_NAME = "Downloads"

        // High-importance channel so game invites appear as heads-up banners.
        private const val INVITE_CHANNEL_ID = "game_invites"
        private const val INVITE_CHANNEL_NAME = "Game Invites"

        const val ACTION_EXIT = "com.oxgames.pluvia.EXIT"

        // Intent action + extras for the "tap to join" notification.
        const val ACTION_GAME_INVITE_ACCEPT  = "app.gamenative.GAME_INVITE_ACCEPT"
        const val EXTRA_INVITE_APP_ID        = "invite_app_id"
        const val EXTRA_INVITE_LOBBY_ID      = "invite_lobby_id"
        const val EXTRA_INVITE_SENDER_NAME   = "invite_sender_name"
        const val EXTRA_INVITE_GAME_NAME     = "invite_game_name"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private val activeServices = mutableSetOf<Int>()

    init {
        createNotificationChannel()
        createDownloadNotificationChannel()
        createInviteNotificationChannel()
    }

    private fun serviceNameFor(id: Int): String = when (id) {
        NOTIFICATION_ID_STEAM -> "Steam"
        NOTIFICATION_ID_GOG -> "GOG"
        NOTIFICATION_ID_EPIC -> "Epic Games"
        NOTIFICATION_ID_AMAZON -> "Amazon Games"
        NOTIFICATION_ID_ITCHIO -> "itch.io"
        else -> context.getString(R.string.app_name)
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

    @Synchronized
    fun notify(content: String, id: Int = NOTIFICATION_ID_STEAM) {
        val notification = createServiceNotification(id, content)
        notificationManager.notify(id, notification)
        activeServices.add(id)
        refreshSummary()
    }

    @Synchronized
    fun cancel(id: Int = NOTIFICATION_ID_STEAM) {
        notificationManager.cancel(id)
        if (activeServices.remove(id)) refreshSummary()
    }

    /**
     * Builds a per-service foreground notification. Each foreground service must
     * post its own notification (Android requires one notification per FGS), but
     * they share a notification group so the system collapses them into a single
     * "GameNative · Connected" entry in the shade.
     *
     * Callers must invoke [markActive] after their `startForeground(...)` call
     * so the group summary is posted/updated.
     */
    fun createServiceNotification(id: Int, content: String): Notification =
        buildNotification(
            title = serviceNameFor(id),
            content = content,
            isSummary = false,
        )

    /** Legacy single-notification helper. Defaults to the Steam service entry. */
    fun createForegroundNotification(content: String): Notification =
        createServiceNotification(NOTIFICATION_ID_STEAM, content)

    @Synchronized
    fun markActive(id: Int) {
        if (activeServices.add(id)) refreshSummary()
    }

    private fun refreshSummary() {
        if (activeServices.isEmpty()) {
            notificationManager.cancel(NOTIFICATION_ID_SUMMARY)
            return
        }
        notificationManager.notify(NOTIFICATION_ID_SUMMARY, buildSummary())
    }

    private fun buildSummary(): Notification = buildNotification(
        title = context.getString(R.string.app_name),
        content = "Connected",
        isSummary = true,
    )

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

    private fun createInviteNotificationChannel() {
        val channel = NotificationChannel(
            INVITE_CHANNEL_ID,
            INVITE_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Steam game lobby invites from friends"
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Derives a stable notification ID from (appId, lobbyId) so that simultaneous invites
     * from different friends or for different games each get their own notification tile.
     */
    private fun inviteNotificationId(appId: Int, lobbyId: Long): Int =
        (appId.toLong() xor lobbyId).toInt()

    /**
     * Shows a heads-up notification for an incoming Steam lobby invite.
     * Tapping the notification starts MainActivity with ACTION_GAME_INVITE_ACCEPT,
     * which triggers the install/bionic/launch flow.
     */
    fun notifyGameInvite(appId: Int, lobbyId: Long, gameName: String, senderName: String) {
        val notifId = inviteNotificationId(appId, lobbyId)
        val acceptIntent = Intent(ACTION_GAME_INVITE_ACCEPT, null, context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INVITE_APP_ID, appId)
            putExtra(EXTRA_INVITE_LOBBY_ID, lobbyId)
            putExtra(EXTRA_INVITE_SENDER_NAME, senderName)
            putExtra(EXTRA_INVITE_GAME_NAME, gameName)
        }
        // Use notifId as the request code so each (appId, lobbyId) pair gets an independent PI.
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            acceptIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, INVITE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_game_invite_title, senderName, gameName))
            .setContentText(context.getString(R.string.notification_game_invite_text))
            .setSmallIcon(smallIconRes())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(notifId, notification)
    }

    /** Dismisses the invite notification for a specific (appId, lobbyId) pair. */
    fun cancelGameInviteNotification(appId: Int, lobbyId: Long) {
        notificationManager.cancel(inviteNotificationId(appId, lobbyId))
    }

    private fun buildNotification(title: String, content: String, isSummary: Boolean): Notification {
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

        // Route Exit through a BroadcastReceiver, NOT through startForegroundService.
        // The latter would oblige whichever service was named in the Intent to call
        // startForeground(...) within ~5s of being started — but the ACTION_EXIT branch
        // in SteamService just emits EndProcess and returns, which crashes the app when
        // the targeted service wasn't already running (e.g. Exit tapped on a GOG
        // notification with no active Steam session).
        val stopIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_EXIT
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(smallIconRes())
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)
            .addAction(0, "Exit", stopPendingIntent)

        if (isSummary) builder.setGroupSummary(true)

        return builder.build()
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
