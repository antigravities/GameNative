package app.gamenative.ui.screen.minimal

import app.gamenative.db.dao.MinimalAppRow
import app.gamenative.enums.AppType

/** One launcher row. */
data class MinimalEntry(
    val appId: Int,
    val name: String,
    val installed: Boolean,
    /** Installed but no longer in the selected collection: uninstall-only. */
    val orphan: Boolean,
    /** Non-null while a download is in progress. */
    val downloadPercent: Int?,
    /** A partial download exists but nothing is downloading (paused). */
    val paused: Boolean = false,
    /** Install / resume was requested but the download job doesn't exist yet. */
    val starting: Boolean = false,
)

data class MinimalLibraryContent(
    val entries: List<MinimalEntry>,
    /** Collection apps whose PICS hasn't landed yet (not shown). */
    val pendingCount: Int,
    /** Number of apps in the selected collection, shown or not. */
    val collectionSize: Int = 0,
) {
    /** Nothing to show yet, but the collection isn't empty: still syncing. */
    val isSyncing: Boolean get() = entries.isEmpty() && (pendingCount > 0 || collectionSize > 0)

    companion object {
        val Empty = MinimalLibraryContent(emptyList(), 0)
    }
}

private val LAUNCHABLE_TYPES = setOf(AppType.game, AppType.application, AppType.tool, AppType.demo)

/** Matches SteamService.INVALID_APP_ID (kept literal so this stays a pure, JVM-testable function). */
private const val NO_PARENT = Int.MAX_VALUE

fun buildMinimalLibrary(
    rows: List<MinimalAppRow>,
    collectionIds: Set<Int>,
    installed: Set<Int>,
    downloading: Map<Int, Int>,
    paused: Set<Int> = emptySet(),
    starting: Set<Int> = emptySet(),
): MinimalLibraryContent {
    val entries = rows.asSequence()
        .filter { it.receivedPics && it.type in LAUNCHABLE_TYPES }
        .filter { it.dlcForAppId <= 0 || it.dlcForAppId == NO_PARENT }
        .mapNotNull { row ->
            val inCollection = row.id in collectionIds
            val isInstalled = row.id in installed
            if (!inCollection && !isInstalled) return@mapNotNull null
            MinimalEntry(
                appId = row.id,
                name = row.name.ifBlank { row.id.toString() },
                installed = isInstalled,
                orphan = !inCollection,
                downloadPercent = downloading[row.id],
                paused = row.id in paused && row.id !in downloading,
                starting = row.id in starting && row.id !in downloading,
            )
        }
        .sortedWith(compareBy<MinimalEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.appId })
        .toList()

    // Collection apps with a stub row still waiting for PICS. Ids without any row (e.g. not
    // owned, or package not mapped yet) aren't counted, so this can't get stuck above zero.
    val pending = rows.count { !it.receivedPics && it.id in collectionIds }
    return MinimalLibraryContent(entries, pending, collectionIds.size)
}
