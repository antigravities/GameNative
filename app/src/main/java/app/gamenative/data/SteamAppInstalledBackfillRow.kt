package app.gamenative.data

import androidx.room.ColumnInfo

// Minimal projection used only by the one-time isDownloaded backfill
// (SteamService.backfillInstalledFlagOnce). installDir is needed to compute the same
// on-disk directory name SteamService.getAppDirName(SteamAppSummary) does.
data class SteamAppInstalledBackfillRow(
    val id: Int,
    val name: String,
    @ColumnInfo("install_dir")
    val installDir: String,
)
