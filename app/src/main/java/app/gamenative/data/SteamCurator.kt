package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// A Steam curator the logged-in user follows, fetched from the "my curators" store endpoint and
// cached locally. clanID is the curator's group/clan id used in the recommendations endpoint.
//
// recommendationsFetchedAt drives the per-curator recommendation refresh TTL: it is 0 until this
// curator's recommendations have been fetched at least once, and is updated to the current epoch-ms
// on every successful (non-empty) recommendations refresh. See LibraryViewModel's stale-while-
// revalidate logic. Defaults are provided so SteamCuratorFetcher can build rows with just id + name.
@Entity("steam_curator")
data class SteamCurator(
    @PrimaryKey @ColumnInfo("clan_id") val clanId: Long,
    @ColumnInfo("name") val name: String,
    @ColumnInfo("recommendations_fetched_at") val recommendationsFetchedAt: Long = 0L,
)
