package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity

// One game a curator has reviewed. Composite PK (curator_id, app_id): a curator reviews an app at
// most once. recommendationType is "recommended", "informational", or "not_recommended" (we only
// store/keep the first two — see SteamCuratorFetcher). blurb/reviewDate are captured now so a future
// "show the curator's review on the game detail page" feature needs no further schema migration.
@Entity("steam_curator_recommendation", primaryKeys = ["curator_id", "app_id"])
data class SteamCuratorRecommendation(
    @ColumnInfo("curator_id") val curatorId: Long,
    @ColumnInfo("app_id") val appId: Int,
    @ColumnInfo("recommendation_type") val recommendationType: String,
    @ColumnInfo("blurb") val blurb: String = "",
    @ColumnInfo("review_date") val reviewDate: String = "",
)
