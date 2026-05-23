package app.gamenative.service.itchio

import app.gamenative.utils.Net
import kotlinx.coroutines.delay
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Thin HTTP wrapper for the itch.io REST API.
 * Uses the same Net.http client (DoH, 30s timeout) as ItchioAuthManager.
 */
object ItchioApiClient {

    private const val BASE_URL = "https://api.itch.io"

    // Polite delay between paginated requests so we don't hammer the API.
    private const val PAGE_DELAY_MS = 500L

    // itch.io returns up to this many entries per page; used to detect the last page.
    private const val DEFAULT_PAGE_SIZE = 50

    /** A single downloadable file attached to a game on itch.io. */
    data class UploadEntry(
        val id: Long,
        val filename: String,
        /** Human-readable name; falls back to filename if the API omits it. */
        val displayName: String,
        val size: Long,
        /** Platform trait tags, e.g. ["p_windows"], ["p_linux"], or [] for extras like manuals. */
        val traits: List<String>,
        val type: String,
    )

    /** A single entry from /profile/owned-keys. */
    data class OwnedKeyEntry(
        /** The download-key ID (outer "id" field). Required to initiate a download later. */
        val keyId: Long,
        /** The game's stable ID (outer "game_id" / inner "game.id"). */
        val gameId: Long,
        val game: GameEntry,
    )

    data class GameEntry(
        val title: String,
        val coverUrl: String,
        /** e.g. "game", "tool", "comic", "book", "soundtrack", "other", "game_mod" */
        val classification: String,
    )

    /**
     * Paginates through /profile/owned-keys and returns every key the account owns.
     * Stops when a page returns fewer items than per_page (indicating the final page).
     */
    suspend fun fetchOwnedKeys(apiKey: String): Result<List<OwnedKeyEntry>> {
        val allKeys = mutableListOf<OwnedKeyEntry>()
        var page = 1

        try {
            while (true) {
                val url = "$BASE_URL/profile/owned-keys?api_key=$apiKey&page=$page"
                Timber.d("[ItchioApiClient] Fetching owned keys page $page")

                val request = Request.Builder().url(url).build()
                val body = Net.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return Result.failure(Exception("HTTP ${response.code} fetching owned keys (page $page)"))
                    }
                    response.body?.string()
                        ?: return Result.failure(Exception("Empty response body (page $page)"))
                }

                val json = JSONObject(body)

                // itch.io surfaces API-level errors as {"errors":["..."]}
                val errors = json.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    return Result.failure(Exception("API error: ${errors.getString(0)}"))
                }

                val perPage = json.optInt("per_page", DEFAULT_PAGE_SIZE)
                val keysArray = json.optJSONArray("owned_keys")
                    ?: return Result.failure(Exception("Missing owned_keys in response (page $page)"))

                for (i in 0 until keysArray.length()) {
                    val entry = keysArray.getJSONObject(i)
                    val gameObj = entry.optJSONObject("game") ?: continue
                    allKeys.add(
                        OwnedKeyEntry(
                            keyId = entry.getLong("id"),
                            gameId = entry.getLong("game_id"),
                            game = GameEntry(
                                title = gameObj.optString("title", ""),
                                coverUrl = gameObj.optString("cover_url", ""),
                                classification = gameObj.optString("classification", ""),
                            ),
                        ),
                    )
                }

                Timber.d("[ItchioApiClient] Page $page: got ${keysArray.length()} keys (${allKeys.size} total so far)")

                // Fewer results than a full page means this was the last page.
                if (keysArray.length() < perPage) break

                page++
                delay(PAGE_DELAY_MS)
            }
        } catch (e: Exception) {
            Timber.e(e, "[ItchioApiClient] Failed to fetch owned keys")
            return Result.failure(e)
        }

        return Result.success(allKeys)
    }

    /**
     * Returns all downloadable uploads for [gameId].
     * Pass [downloadKeyId] = null for free/public games (omits the query parameter).
     *
     * The uploads list for a single game is small enough that no pagination is needed.
     */
    suspend fun fetchUploads(
        apiKey: String,
        gameId: Long,
        downloadKeyId: Long?,
    ): Result<List<UploadEntry>> {
        var url = "$BASE_URL/games/$gameId/uploads?api_key=$apiKey"
        if (downloadKeyId != null) url += "&download_key_id=$downloadKeyId"

        return try {
            val request = Request.Builder().url(url).build()
            val body = Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("HTTP ${response.code} fetching uploads for game $gameId"))
                }
                response.body?.string()
                    ?: return Result.failure(Exception("Empty response body fetching uploads for game $gameId"))
            }

            val json = JSONObject(body)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                return Result.failure(Exception("API error: ${errors.getString(0)}"))
            }

            val uploadsArray = json.optJSONArray("uploads")
                ?: return Result.failure(Exception("Missing uploads in response for game $gameId"))

            val entries = mutableListOf<UploadEntry>()
            for (i in 0 until uploadsArray.length()) {
                val u = uploadsArray.getJSONObject(i)
                val filename = u.optString("filename", "")
                val traitsArray = u.optJSONArray("traits")
                val traits = if (traitsArray != null) {
                    (0 until traitsArray.length()).map { traitsArray.getString(it) }
                } else {
                    emptyList()
                }
                entries.add(
                    UploadEntry(
                        id = u.getLong("id"),
                        filename = filename,
                        displayName = u.optString("display_name", "").ifBlank { filename },
                        size = u.optLong("size", 0L),
                        traits = traits,
                        type = u.optString("type", ""),
                    )
                )
            }

            Timber.d("[ItchioApiClient] fetchUploads game $gameId: ${entries.size} uploads")
            Result.success(entries)
        } catch (e: Exception) {
            Timber.e(e, "[ItchioApiClient] Failed to fetch uploads for game $gameId")
            Result.failure(e)
        }
    }
}
