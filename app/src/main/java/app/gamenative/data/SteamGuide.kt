package app.gamenative.data

/**
 * A single Steam Community guide for a game, as returned by the
 * PublishedFile.QueryFiles unified-messages RPC.
 *
 * Guides are "published files" (UGC), the same family as workshop items and
 * screenshots, so they come back as `PublishedFileDetails` protobufs. This is a
 * lightweight UI-facing projection of the fields we actually display.
 */
data class SteamGuide(
    val id: Long,
    val title: String,
    val shortDescription: String,
    val previewUrl: String,
    // vote_data.score (0–1) — the weighted score Steam turns into a star rating.
    val score: Float,
    // Total ratings (votes_up + votes_down); 0 means the guide has no rating yet.
    val ratingsCount: Int,
) {
    /**
     * Canonical Steam Community URL for a guide. Guides don't expose a usable
     * `file_url`, so we build the standard "sharedfiles" detail page from the id.
     */
    val url: String
        get() = "https://steamcommunity.com/sharedfiles/filedetails/?id=$id"
}
