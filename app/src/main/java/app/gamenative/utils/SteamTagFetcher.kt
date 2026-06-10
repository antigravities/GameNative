package app.gamenative.utils

import app.gamenative.data.SteamTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

private const val TAG_BROWSE_URL = "https://store.steampowered.com/tag/browse/"

// One-time / periodic fetch of Steam store tags from the tag browse page.
// The page contains elements like:
//   <div data-tagid="492">Indie</div>
// We extract all (tagId, name) pairs via regex. Names are trimmed of whitespace.
suspend fun fetchSteamTagsFromWeb(httpClient: OkHttpClient): List<SteamTag> =
    withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(TAG_BROWSE_URL).build()
            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("SteamTagFetcher").w("HTTP ${response.code} fetching tag list")
                    return@withContext emptyList()
                }
                response.body?.string()
            } ?: return@withContext emptyList()

            val regex = Regex("""data-tagid="(\d+)">([^<]+)</div>""")
            regex.findAll(body).mapNotNull { m ->
                val id = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                SteamTag(id = id, name = m.groupValues[2].trim())
            }.toList().also {
                Timber.tag("SteamTagFetcher").d("Fetched ${it.size} Steam tags")
            }
        } catch (e: Exception) {
            Timber.tag("SteamTagFetcher").e(e, "Failed to fetch Steam tags")
            emptyList()
        }
    }
