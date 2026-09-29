package app.gamenative.data

import androidx.room.ColumnInfo

// Minimal projection used only by the one-time isDownloaded backfill
// (SteamService.backfillInstalledFlagOnce). installDir is needed to compute the same
// on-disk directory name SteamService.getAppDirName(SteamAppSummary) does. receivedPics
// tells the backfill whether name/installDir are real PICS data yet, or still a placeholder
// stub row awaiting its full app-info sync.
data class SteamAppInstalledBackfillRow(
    val id: Int,
    val name: String,
    @ColumnInfo("install_dir")
    val installDir: String,
    @ColumnInfo("received_pics")
    val receivedPics: Boolean,
)
