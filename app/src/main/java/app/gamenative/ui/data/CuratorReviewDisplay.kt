package app.gamenative.ui.data

// A curator's review of one game, resolved for display on the game detail page. Combines the
// recommendation row (blurb/type/date/url) with the curator's name. Returned by
// SteamService.getCuratorReviewForApp for the currently-selected curator only.
data class CuratorReviewDisplay(
    val curatorName: String,
    val recommendationType: String, // "recommended" | "informational"
    val blurb: String,
    val reviewDate: String,
    val reviewUrl: String,
)
