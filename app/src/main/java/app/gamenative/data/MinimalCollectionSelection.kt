package app.gamenative.data

import app.gamenative.PrefManager
import app.gamenative.steam.MinimalCollectionResolver
import app.gamenative.steam.MinimalSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** The modernMinimal build's chosen Steam collection, persisted in [PrefManager]. */
object MinimalCollectionSelection {
    private val _selectedId = MutableStateFlow(PrefManager.minimalCollectionId)
    val selectedId: StateFlow<String> = _selectedId.asStateFlow()

    val selection: Flow<MinimalSelection> =
        combine(_selectedId, SteamCollectionRepository.collections) { id, collections ->
            MinimalCollectionResolver.resolve(id, PrefManager.minimalCollectionName, collections)
        }
            .distinctUntilChanged()
            .onEach { state ->
                // Keep the cached name current if the collection was renamed on Steam.
                if (state is MinimalSelection.Resolved &&
                    state.collection.name != PrefManager.minimalCollectionName
                ) {
                    PrefManager.minimalCollectionName = state.collection.name
                }
            }

    /** App ids of the resolved collection, or null while unknown (not selected / not loaded / missing). */
    val selectedAppIds: Flow<Set<Int>?> =
        selection.map { (it as? MinimalSelection.Resolved)?.appIds }.distinctUntilChanged()

    fun select(collection: SteamCollection) {
        PrefManager.minimalCollectionId = collection.id
        PrefManager.minimalCollectionName = collection.name
        _selectedId.value = collection.id
    }

    fun clear() {
        PrefManager.minimalCollectionId = ""
        PrefManager.minimalCollectionName = ""
        _selectedId.value = ""
    }

    /** Load collections from the persisted snapshot if nothing has populated the repository yet. */
    fun ensureLoaded() {
        if (SteamCollectionRepository.collections.value == null) {
            SteamCollectionRepository.loadFromCache()
        }
    }
}
