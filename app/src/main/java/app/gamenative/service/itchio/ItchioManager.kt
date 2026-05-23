package app.gamenative.service.itchio

import android.content.Context
import app.gamenative.NetworkMonitor
import app.gamenative.data.ItchioGame
import app.gamenative.db.dao.ItchioGameDao
import timber.log.Timber
import java.io.IOException

/**
 * Business logic for the itch.io game library.
 * Mirrors the object-singleton pattern used by ItchioAuthManager.
 */
object ItchioManager {

    /**
     * Fetches the full library from the itch.io API and persists it to the local DB.
     *
     * - If the device is offline the existing DB contents are left untouched.
     * - Only entries with classification == "game" are stored; tools, soundtracks, etc. are ignored.
     * - Non-installed games are wiped before the fresh set is written, so games the user no longer
     *   owns are removed. Installed games are preserved and their install state is carried over.
     *
     * @return the number of games written, or a failure if the sync could not complete.
     */
    suspend fun refreshLibrary(context: Context, dao: ItchioGameDao): Result<Int> {
        if (!NetworkMonitor.hasInternet.value) {
            Timber.i("[ItchioManager] Offline — skipping library sync, leaving DB untouched")
            return Result.failure(IOException("No internet connection"))
        }

        val credentials = ItchioAuthManager.getStoredCredentials(context)
            ?: return Result.failure(IllegalStateException("Not logged in to itch.io"))

        Timber.i("[ItchioManager] Starting library sync for ${credentials.username}")

        val keys = ItchioApiClient.fetchOwnedKeys(credentials.apiKey)
            .getOrElse { return Result.failure(it) }

        // Filter to actual games only (not tools, soundtracks, books, etc.)
        val games = keys
            .filter { it.game.classification == "game" }
            .map { key ->
                ItchioGame(
                    // game_id is the stable identifier for the game itself (PK, consistent with other platforms)
                    id = key.gameId.toString(),
                    // keyId is the purchase/download key — stored for use during the download flow
                    downloadKeyId = key.keyId.toString(),
                    title = key.game.title,
                    imageUrl = key.game.coverUrl,
                )
            }

        // Wipe non-installed library rows so games no longer owned are cleaned up.
        // Installed games survive deleteAllNonInstalledGames(), and upsert preserves their state.
        dao.deleteAllNonInstalledGames()
        dao.upsertPreservingInstallStatus(games)

        Timber.i("[ItchioManager] Library sync complete: ${games.size} games (${keys.size - games.size} non-game entries skipped)")
        return Result.success(games.size)
    }
}
