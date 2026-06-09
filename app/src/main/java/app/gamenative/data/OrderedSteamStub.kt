package app.gamenative.data

import androidx.room.ColumnInfo

// Lightweight projection for the library's per-filter ordered skeleton (see
// SteamAppDao.orderedSteamRows). Carries only the columns needed to (a) identify a row for a later
// by-id page fetch and (b) merge with in-memory non-Steam entries by the active sort. No blobs are
// loaded, so materializing the full ordered set of ~45k rows stays cheap.
//
// isDownloaded is the app_info installed flag used only as the INSTALLED_FIRST/RECENTLY_PLAYED sort
// tier; it is projected as 0 when the query has no app_info join (name/size sorts), where it's unused.
data class OrderedSteamStub(
    val id: Int,
    @ColumnInfo("name_sort_key")
    val nameSortKey: String = "",
    @ColumnInfo("size_bytes")
    val sizeBytes: Long = 0,
    @ColumnInfo("is_downloaded")
    val isDownloaded: Boolean = false,
)
