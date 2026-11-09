package app.gamenative.steam

import app.gamenative.data.SteamCollection

/** State of the modernMinimal build's selected Steam collection. */
sealed interface MinimalSelection {
    /** No collection chosen yet; the picker should be shown. */
    data object NotSelected : MinimalSelection

    /**
     * A collection is chosen but collections haven't been loaded (no cache, fetch pending).
     * Callers must treat this as "unknown", never as "empty", so nothing gets purged.
     */
    data class NotLoaded(val id: String, val lastName: String) : MinimalSelection

    /** Collections are loaded but the chosen one no longer exists on Steam; re-prompt. */
    data class Missing(val id: String, val lastName: String) : MinimalSelection

    data class Resolved(val collection: SteamCollection) : MinimalSelection {
        val appIds: Set<Int> get() = collection.appIds
    }
}

object MinimalCollectionResolver {
    fun resolve(selectedId: String, lastName: String, collections: List<SteamCollection>?): MinimalSelection {
        if (selectedId.isEmpty()) return MinimalSelection.NotSelected
        if (collections == null) return MinimalSelection.NotLoaded(selectedId, lastName)
        val match = collections.firstOrNull { it.id == selectedId }
            ?: return MinimalSelection.Missing(selectedId, lastName)
        return MinimalSelection.Resolved(match)
    }
}
