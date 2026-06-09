package app.gamenative.data

import androidx.room.ColumnInfo

// Minimal projection used only by the one-time name_sort_key / is_adult backfill
// (SteamService.backfillSortKeysOnce). Fetching just id + name + content_descriptors keeps row
// sizes small and avoids deserializing depots/config blobs for every row during the scan.
data class SteamAppSortKeyRow(
    val id: Int,
    val name: String,
    @ColumnInfo("content_descriptors")
    val contentDescriptors: List<Int> = emptyList(),
)
