package app.gamenative.data

// Minimal projection used only by the one-time name_sort_key backfill
// (SteamService.backfillSortKeysOnce). Fetching just id + name keeps row
// sizes small and avoids deserializing depots/config blobs for every row during the scan.
data class SteamAppSortKeyRow(
    val id: Int,
    val name: String,
)
