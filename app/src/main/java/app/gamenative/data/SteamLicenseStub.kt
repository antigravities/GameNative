package app.gamenative.data

import androidx.room.ColumnInfo

// Minimal three-column projection for the onLicenseList diff and full-package-update
// queue path. Fetching only these columns avoids deserializing the large app_ids/depot_ids
// lists for all ~59k licenses on every login.
data class SteamLicenseStub(
    val packageId: Int,
    @ColumnInfo("last_change_number") val lastChangeNumber: Int,
    @ColumnInfo("access_token") val accessToken: Long,
)
