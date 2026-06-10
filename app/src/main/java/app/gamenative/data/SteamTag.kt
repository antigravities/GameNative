package app.gamenative.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Steam store tag, fetched from the tag browse page and cached locally.
// Tag IDs come from PICS (common.store_tags); names are resolved from this table.
@Entity("steam_tag")
data class SteamTag(
    @PrimaryKey val id: Int,
    val name: String,
)
