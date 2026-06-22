package app.gamenative.service

import app.gamenative.data.SteamGuide
import app.gamenative.ui.enums.GuideCategory
import app.gamenative.ui.enums.GuideSort
import `in`.dragonbra.javasteam.enums.EPublishedFileQueryType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * Queries Steam Community guides for a game via the PublishedFile.QueryFiles
 * unified-messages RPC. Guides are "published files" (UGC), so the same handler
 * family used for friends/owned-games (see [SteamUnifiedFriends]) applies.
 *
 * This is an `object` (singleton) rather than a per-call class because it is
 * invoked directly from the in-game Quick Menu composable, which has no Steam
 * service instance to hand it. It reaches the connected client through
 * [SteamService.instance].
 */
object SteamGuidesFetcher {

    private const val NUM_PER_PAGE = 50

    // The QueryFiles `filetype` field is a bare uint32 mapping to Steam's
    // EPublishedFileInfoMatchingFileType enum (which JavaSteam does NOT define —
    // its EWorkshopFileType is a different, creation-time enum). AllGuides = 10
    // covers both web and integrated guides. Without this the default query only
    // returns workshop items, so guide-only games come back empty.
    private const val MATCHING_FILETYPE_ALL_GUIDES = 10

    /**
     * In-memory cache so re-opening the menu or toggling back to a previously
     * viewed sort/category doesn't re-hit the network. Keyed by the full query.
     */
    private data class CacheKey(
        val appId: Int,
        val sort: GuideSort,
        val tag: String?,
        val languageTag: String?,
    )

    private val cache = HashMap<CacheKey, List<SteamGuide>>()

    /**
     * Fetches guides for [appId] with the given [sort] and optional [category].
     * Returns an empty list when there is no connected client or the request
     * fails (mirrors the defensive style of [SteamUnifiedFriends.getOwnedGames]).
     *
     * Must be called off the main thread (the `.await()` blocks on the Steam job).
     */
    suspend fun queryGuides(
        appId: Int,
        sort: GuideSort,
        category: GuideCategory,
        languageTag: String? = null,
    ): List<SteamGuide> {
        val key = CacheKey(appId, sort, category.tag, languageTag)
        cache[key]?.let { return it }

        val steamClient = SteamService.instance?.steamClient ?: return emptyList()

        return try {
            val unifiedMessages = steamClient.getHandler<SteamUnifiedMessages>()
            val publishedFile = unifiedMessages!!.createService(PublishedFile::class.java)

            val request = CPublishedFile_QueryFiles_Request.newBuilder().apply {
                queryType = sort.queryType.code()
                this.appid = appId
                filetype = MATCHING_FILETYPE_ALL_GUIDES
                numperpage = NUM_PER_PAGE
                page = 1
                // Trend-ranked ("Most Popular") queries return nothing without a trend window.
                if (sort.queryType == EPublishedFileQueryType.RankedByTrend) days = 7
                returnDetails = true
                returnPreviews = true
                returnShortDescription = true
                returnVoteData = true
                // category.tag is null for "All" — only constrain when a category is chosen.
                category.tag?.let { addRequiredtags(it) }
                // Steam encodes a guide's language as a required tag too. With
                // match_all_tags (default true) the guide must carry both tags.
                languageTag?.let { addRequiredtags(it) }
            }.build()

            val response = publishedFile.queryFiles(request)?.await()
            if (response == null || response.result != EResult.OK) {
                return emptyList()
            }

            val guides = response.body.publishedfiledetailsList.map { d ->
                SteamGuide(
                    id = d.publishedfileid,
                    title = d.title,
                    shortDescription = d.shortDescription,
                    // Prefer the top-level preview_url; fall back to the first preview entry.
                    previewUrl = d.previewUrl.ifEmpty {
                        d.previewsList.firstOrNull()?.url.orEmpty()
                    },
                    score = d.voteData?.score ?: 0f,
                    ratingsCount = (d.voteData?.votesUp ?: 0) + (d.voteData?.votesDown ?: 0),
                )
            }

            cache[key] = guides
            guides
        } catch (e: Exception) {
            // JavaSteam job timeouts surface as a bare CancellationException; let a
            // genuine coroutine cancellation propagate, but log+swallow Steam timeouts.
            coroutineContext.ensureActive()
            Timber.w(e, "Error querying guides for app %d", appId)
            emptyList()
        }
    }
}
