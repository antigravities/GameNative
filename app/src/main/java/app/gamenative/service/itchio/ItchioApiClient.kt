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
}
