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
    // Unix timestamp (ms) from steam_license.time_created for the PURCHASE_DATE sort.
    // Zero when the sort is not PURCHASE_DATE (the query projects a literal 0 instead of joining).
    @ColumnInfo("time_created_epoch")
    val timeCreatedEpoch: Long = 0,
    // Steam review data for the RATING sort: review_score is the 0-9 bucket ("Very Positive" = 8),
    // review_percentage the % positive. Both are 0 for unrated apps.
    @ColumnInfo("review_score")
    val reviewScore: Int = 0,
    @ColumnInfo("review_percentage")
    val reviewPercentage: Int = 0,
)
