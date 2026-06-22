package app.gamenative.ui.enums

import androidx.annotation.StringRes
import app.gamenative.R
import `in`.dragonbra.javasteam.enums.EPublishedFileQueryType

/**
 * Sort options for the in-game Guides tab. Each maps to a Steam
 * [EPublishedFileQueryType] used as the `query_type` of PublishedFile.QueryFiles.
 */
enum class GuideSort(
    @param:StringRes val displayTextRes: Int,
    val queryType: EPublishedFileQueryType,
) {
    MOST_POPULAR(displayTextRes = R.string.guides_sort_popular, queryType = EPublishedFileQueryType.RankedByTrend),
    TOP_RATED(displayTextRes = R.string.guides_sort_top_rated, queryType = EPublishedFileQueryType.RankedByVote),
    MOST_RECENT(displayTextRes = R.string.guides_sort_recent, queryType = EPublishedFileQueryType.RankedByPublicationDate),
}
