package app.gamenative.workshop

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_AreFilesInSubscriptionList_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_Subscribe_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_Unsubscribe_Request
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * In-app Steam Workshop browser.
 *
 * Provides discovery RPCs (`QueryFiles`, `GetDetails`) and subscription mutation
 * (`Subscribe`, `Unsubscribe`, `AreFilesInSubscriptionList`) on top of GameNative's
 * existing [SteamClient] session via [SteamUnifiedMessages]. No additional auth
 * required — these are the same `PublishedFile` service methods that
 * [WorkshopManager.getSubscribedItems] uses for `GetUserFiles`.
 *
 * Calling [subscribeToItem] alone does NOT trigger a download. After a successful
 * Subscribe, the caller must:
 *   1. Update the per-app DB column `enabledWorkshopItemIds` to include the new id.
 *   2. Invoke [WorkshopManager.startWorkshopDownload] with the updated id list.
 * See `_workspace/workshop_manager_existing_vs_new.md` §4 for the canonical 3-step
 * post-subscribe sequence (matches the existing `SteamAppScreen.kt:1262-1283` Save handler).
 */
object WorkshopBrowser {

    private const val TAG = "WorkshopBrowser"

    /** Steam-side enum [EUserUGCList::k_PublishedFileQueryType_*] used by [QueryType]. */
    private const val QUERY_TYPE_RANKED_BY_VOTE: Int = 0
    private const val QUERY_TYPE_RANKED_BY_PUBLICATION_DATE: Int = 1
    private const val QUERY_TYPE_RANKED_BY_TREND: Int = 3
    private const val QUERY_TYPE_RANKED_BY_TEXT_SEARCH: Int = 9
    private const val QUERY_TYPE_RANKED_BY_LAST_UPDATED_DATE: Int = 12
    private const val QUERY_TYPE_RANKED_BY_VOTES_UP: Int = 21

    /** Sort/filter presets surfaced to the browser UI. */
    enum class QueryType(internal val rawValue: Int) {
        Popular(QUERY_TYPE_RANKED_BY_VOTE),
        Newest(QUERY_TYPE_RANKED_BY_PUBLICATION_DATE),
        Trending(QUERY_TYPE_RANKED_BY_TREND),
        RecentlyUpdated(QUERY_TYPE_RANKED_BY_LAST_UPDATED_DATE),
        MostVotesUp(QUERY_TYPE_RANKED_BY_VOTES_UP),
        Subscribed(-1)
    }

    /**
     * Fetches a single page of Workshop items for [appId].
     *
     * @param searchText empty string = no full-text filter; non-empty implies switch to
     *   `RankedByTextSearch` query type regardless of [sort].
     * @param page 1-based.
     * @param perPage typical 20; Steam allows up to ~50.
     */
    suspend fun queryItems(
        appId: Int,
        steamClient: SteamClient,
        searchText: String = "",
        sort: QueryType = QueryType.Popular,
        page: Int = 1,
        perPage: Int = 20,
        requiredTags: List<String> = emptyList()
    ): WorkshopQueryResult = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d(
            "QueryFiles appId=$appId page=$page perPage=$perPage sort=$sort searchText='${searchText.take(40)}'"
        )

        val publishedFile = obtainPublishedFile(steamClient)
            ?: return@withContext WorkshopQueryResult(emptyList(), succeeded = false)

        try {
            val effectiveQueryType = if (searchText.isNotEmpty()) {
                QUERY_TYPE_RANKED_BY_TEXT_SEARCH
            } else {
                sort.rawValue
            }

            var items: List<WorkshopItemDetail> = emptyList()
            var total: Int = 0

            if( sort != QueryType.Subscribed ){
                val request = CPublishedFile_QueryFiles_Request.newBuilder().apply {
                    this.appid = appId
                    this.queryType = effectiveQueryType
                    this.page = page
                    this.numperpage = perPage
                    this.searchText = searchText
                    this.returnDetails = true
                    this.returnPreviews = true
                    this.returnVoteData = true
                    this.returnShortDescription = true
                    this.returnTags = true
                    if (requiredTags.isNotEmpty()) {
                        addAllRequiredtags(requiredTags)
                    }
                }.build()

                val response = withTimeoutOrNull(30_000L) {
                    publishedFile.queryFiles(request).toFuture().await()
                }
                if (response == null) {
                    Timber.tag(TAG).e("QueryFiles timed out for appId=$appId page=$page")
                    return@withContext WorkshopQueryResult(emptyList(), succeeded = false)
                }
                if (response.result != EResult.OK) {
                    Timber.tag(TAG).e("QueryFiles failed: result=${response.result} appId=$appId page=$page")
                    return@withContext WorkshopQueryResult(emptyList(), succeeded = false)
                }

                val body = response.body.build()

                items = body.publishedfiledetailsList.map { detail ->
                    detail.toWorkshopItemDetail(appId)
                }

                total = body.total

                Timber.tag(TAG).d("QueryFiles -> ${items.size} items, total=${body.total} [appId=$appId page=$page]")
            } else {
                val request = CPublishedFile_GetUserFiles_Request.newBuilder().apply {
                    this.steamid = steamClient.steamID!!.convertToUInt64()
                    this.appid = appId
                    this.page = page
                    this.type = "mysubscriptions"
                    this.numperpage = perPage
                    // 0xFFFFFFFF = k_EUGCMatchingUGCType_All; default 0 omits GameManagedItems
                    this.filetype = 0xFFFFFFFF.toInt()
                    this.returnPreviews = true
                    this.returnVoteData = true
                    this.returnShortDescription = true
                    this.returnTags = true

                    if( requiredTags.isNotEmpty() ){
                        addAllRequiredtags(requiredTags)
                    }
                }.build()

                val response = withTimeoutOrNull(30_000L) {
                    publishedFile.getUserFiles(request).toFuture().await()
                }
                if (response == null) {
                    Timber.tag(TAG).e("GetUserFiles timed out for appId=$appId page=$page")
                    return@withContext WorkshopQueryResult(emptyList(), succeeded = false)
                }
                if (response.result != EResult.OK) {
                    Timber.tag(TAG).e("GetUserFiles failed: result=${response.result} appId=$appId page=$page")
                    return@withContext WorkshopQueryResult(emptyList(), succeeded = false)
                }

                val body = response.body.build()

                items = body.publishedfiledetailsList.map { detail ->
                    detail.toWorkshopItemDetail(appId)
                }

                Timber.tag(TAG).d("GetUserFiles -> ${items.size} items, total=${body.total} [appId=$appId page=$page]")
                // Shouldn't be too bad, hopefully users aren't subscribing to millions of items
                if( searchText.isNotEmpty() ){
                    items = items.filter { item -> item.item.title.contains(searchText, true) }
                }

                total = body.total
            }

            WorkshopQueryResult(
                items = items,
                totalResults = total,
                hasMore = (page * perPage) < total,
                succeeded = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "QueryFiles call failed for appId=$appId page=$page")
            WorkshopQueryResult(emptyList(), succeeded = false)
        }
    }

    /**
     * Fetches detailed metadata for one or more workshop items.
     *
     * Bulk lookup; pass up to ~100 ids per call.
     */
    suspend fun getDetails(
        publishedFileIds: List<Long>,
        steamClient: SteamClient,
    ): List<WorkshopItemDetail> = withContext(Dispatchers.IO) {
        if (publishedFileIds.isEmpty()) return@withContext emptyList()

        val publishedFile = obtainPublishedFile(steamClient)
            ?: return@withContext emptyList()

        try {
            val request = CPublishedFile_GetDetails_Request.newBuilder().apply {
                addAllPublishedfileids(publishedFileIds)
                this.includetags = true
                this.includeadditionalpreviews = true
                this.includechildren = false
                this.includevotes = true
                this.includeforsaledata = false
                this.includemetadata = true
                this.shortDescription = false
            }.build()

            val response = withTimeoutOrNull(30_000L) {
                publishedFile.getDetails(request).toFuture().await()
            }
            if (response == null) {
                Timber.tag(TAG).e("GetDetails timed out (${publishedFileIds.size} ids)")
                return@withContext emptyList()
            }
            if (response.result != EResult.OK) {
                Timber.tag(TAG).e("GetDetails failed: result=${response.result}")
                return@withContext emptyList()
            }

            val body = response.body.build()
            body.publishedfiledetailsList.map { detail ->
                // appId fallback comes from the detail itself for GetDetails
                detail.toWorkshopItemDetail(detail.consumerAppid)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GetDetails call failed (${publishedFileIds.size} ids)")
            emptyList()
        }
    }

    /**
     * Subscribes the current user to [publishedFileId].
     *
     * Steam-side success only — does NOT initiate download. See class doc for the
     * full post-subscribe sequence.
     */
    suspend fun subscribeToItem(
        publishedFileId: Long,
        appId: Int,
        steamClient: SteamClient,
    ): EResult = withContext(Dispatchers.IO) {
        val publishedFile = obtainPublishedFile(steamClient)
            ?: return@withContext EResult.Fail

        try {
            val request = CPublishedFile_Subscribe_Request.newBuilder().apply {
                this.publishedfileid = publishedFileId
                this.listType = LIST_TYPE_MY_SUBSCRIPTIONS
                this.appid = appId
                this.notifyClient = true
            }.build()

            val response = withTimeoutOrNull(15_000L) {
                publishedFile.subscribe(request).toFuture().await()
            }
            if (response == null) {
                Timber.tag(TAG).e("Subscribe timed out for $publishedFileId (appId=$appId)")
                return@withContext EResult.Timeout
            }
            Timber.tag(TAG).i("Subscribe $publishedFileId (appId=$appId) -> ${response.result}")
            response.result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Subscribe call failed for $publishedFileId")
            EResult.Fail
        }
    }

    /**
     * Unsubscribes the current user from [publishedFileId].
     *
     * After success, callers should:
     *   1. Remove the id from the per-app DB `enabledWorkshopItemIds`.
     *   2. Call [WorkshopManager.cleanupUnsubscribedItems] with the refreshed list.
     *   3. Call [WorkshopManager.configureSymlinksForApp] to clear symlinks.
     */
    suspend fun unsubscribeFromItem(
        publishedFileId: Long,
        appId: Int,
        steamClient: SteamClient,
    ): EResult = withContext(Dispatchers.IO) {
        val publishedFile = obtainPublishedFile(steamClient)
            ?: return@withContext EResult.Fail

        try {
            val request = CPublishedFile_Unsubscribe_Request.newBuilder().apply {
                this.publishedfileid = publishedFileId
                this.listType = LIST_TYPE_MY_SUBSCRIPTIONS
                this.appid = appId
                this.notifyClient = true
            }.build()

            val response = withTimeoutOrNull(15_000L) {
                publishedFile.unsubscribe(request).toFuture().await()
            }
            if (response == null) {
                Timber.tag(TAG).e("Unsubscribe timed out for $publishedFileId (appId=$appId)")
                return@withContext EResult.Timeout
            }
            Timber.tag(TAG).i("Unsubscribe $publishedFileId (appId=$appId) -> ${response.result}")
            response.result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unsubscribe call failed for $publishedFileId")
            EResult.Fail
        }
    }

    /**
     * Bulk subscription-state lookup. Returns a map { publishedFileId -> isSubscribed }.
     *
     * Used by the browser grid to badge items the user already has subscribed.
     */
    suspend fun areFilesInSubscriptionList(
        appId: Int,
        publishedFileIds: List<Long>,
        steamClient: SteamClient,
    ): Map<Long, Boolean> = withContext(Dispatchers.IO) {
        if (publishedFileIds.isEmpty()) return@withContext emptyMap()

        val publishedFile = obtainPublishedFile(steamClient)
            ?: return@withContext emptyMap()

        try {
            val request = CPublishedFile_AreFilesInSubscriptionList_Request.newBuilder().apply {
                this.appid = appId
                addAllPublishedfileids(publishedFileIds)
                this.listtype = LIST_TYPE_MY_SUBSCRIPTIONS
            }.build()

            val response = withTimeoutOrNull(15_000L) {
                publishedFile.areFilesInSubscriptionList(request).toFuture().await()
            }
            if (response == null || response.result != EResult.OK) {
                Timber.tag(TAG).w(
                    "AreFilesInSubscriptionList failed: result=${response?.result} (appId=$appId, ${publishedFileIds.size} ids)"
                )
                return@withContext emptyMap()
            }

            val body = response.body.build()
            body.filesList.associate { entry -> entry.publishedfileid to entry.inlist }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "AreFilesInSubscriptionList call failed for appId=$appId")
            emptyMap()
        }
    }

    /** Steam-side `EPublishedFileListType::k_PublishedFileListType_MySubscriptions` = 1. */
    private const val LIST_TYPE_MY_SUBSCRIPTIONS: Int = 1

    private fun obtainPublishedFile(steamClient: SteamClient): PublishedFile? {
        val unifiedMessages = steamClient.getHandler<SteamUnifiedMessages>()
        if (unifiedMessages == null) {
            Timber.tag(TAG).e("SteamUnifiedMessages handler not available")
            return null
        }
        return unifiedMessages.createService<PublishedFile>()
    }
}

/**
 * Maps a `PublishedFileDetails` protobuf to our domain object.
 *
 * Defined as an extension because it's the same shape regardless of which RPC
 * (QueryFiles / GetDetails) returned the detail.
 */
private fun `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.PublishedFileDetails.toWorkshopItemDetail(
    fallbackAppId: Int,
): WorkshopItemDetail {
    val effectiveAppId = if (consumerAppid != 0) consumerAppid else fallbackAppId

    val baseItem = WorkshopItem(
        publishedFileId = publishedfileid,
        appId = effectiveAppId,
        title = title.ifEmpty { publishedfileid.toString() },
        fileSizeBytes = fileSize,
        manifestId = hcontentFile,
        timeUpdated = timeUpdated.toLong(),
        fileUrl = fileUrl ?: "",
        fileName = filename ?: "",
        previewUrl = previewUrl ?: "",
    )

    // Extract actual screenshots from the repeated `previews` field.
    // Deliberately excludes the top-level previewUrl (field 11), which is usually a small
    // square thumbnail not suitable for 16:9 display.
    val screenshotUrls = previewsList
        .filter { it.youtubevideoid.isNullOrEmpty() && it.url.isNotEmpty() }
        .sortedBy { it.sortorder }
        .map { it.url }

    val totalVotes = (voteData.votesUp.toLong() + voteData.votesDown.toLong()).coerceAtLeast(0L)
    val score = if (totalVotes > 0) voteData.score else 0f

    return WorkshopItemDetail(
        item = baseItem,
        description = fileDescription ?: "",
        shortDescription = shortDescription ?: "",
        tags = tagsList.map { it.tag },
        voteScore = score,
        voteUp = voteData.votesUp.toLong(),
        voteDown = voteData.votesDown.toLong(),
        creatorSteamId = creator,
        timeCreated = timeCreated.toLong(),
        viewCount = views.toLong(),
        subscriberCount = subscriptions.toLong(),
        favoritedCount = favorited.toLong(),
        lifetimeSubscriptions = lifetimeSubscriptions.toLong(),
        previewUrls = screenshotUrls,
    )
}
