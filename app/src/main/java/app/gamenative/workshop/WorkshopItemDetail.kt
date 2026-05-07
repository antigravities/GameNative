package app.gamenative.workshop

/**
 * Richer Workshop item metadata used by the in-app Workshop browser.
 *
 * The browser fetches more fields than the subscription installer (which uses [WorkshopItem]),
 * so this class wraps the lightweight [item] and adds discovery-relevant data:
 * description, tags, vote score, creator, view/subscriber counts.
 *
 * Populated from `PublishedFile.QueryFiles` and `PublishedFile.GetDetails` responses
 * via [WorkshopBrowser].
 */
data class WorkshopItemDetail(
    val item: WorkshopItem,
    val description: String = "",
    val shortDescription: String = "",
    val tags: List<String> = emptyList(),
    /** Steam vote score in the range 0.0 .. 1.0 (proportion of upvotes). */
    val voteScore: Float = 0f,
    val voteUp: Long = 0L,
    val voteDown: Long = 0L,
    /** Creator's Steam ID64. Resolve to display name with a separate ISteamUser RPC if needed. */
    val creatorSteamId: Long = 0L,
    /** Unix timestamp (seconds). */
    val timeCreated: Long = 0L,
    val viewCount: Long = 0L,
    val subscriberCount: Long = 0L,
    val favoritedCount: Long = 0L,
    /** Lifetime number of subscriptions (cumulative; differs from current subscriberCount). */
    val lifetimeSubscriptions: Long = 0L,
    /** True when the local user is currently subscribed. Filled in by browser via AreFilesInSubscriptionList. */
    val isSubscribedByLocalUser: Boolean = false,
)

/**
 * Result of a single page of `PublishedFile.QueryFiles`.
 *
 * Mirrors the shape of [WorkshopFetchResult] but adds [totalResults] and [hasMore] so the
 * browser UI can drive infinite scroll without a second total-only RPC.
 *
 * When [succeeded] is false, callers should preserve any previously loaded items rather
 * than clearing the list (the failure may be a transient network/throttle issue).
 */
data class WorkshopQueryResult(
    val items: List<WorkshopItemDetail>,
    val totalResults: Int = 0,
    val nextCursor: String = "*",
    val hasMore: Boolean = false,
    val succeeded: Boolean,
)
