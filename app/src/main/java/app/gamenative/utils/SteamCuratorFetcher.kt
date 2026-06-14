package app.gamenative.utils

import app.gamenative.data.SteamCurator
import app.gamenative.data.SteamCuratorRecommendation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

private const val CURATORS_URL =
    "https://store.steampowered.com/curators/ajaxgetcurators//" +
        "?query=&start=%d&count=50&dynamic_data=&filter=mycurators&appid=0"

// Regex to extract the g_rgTopCurators JSON array from the results_html JavaScript snippet.
// Steam embeds the curator list as a JS variable assignment inside the HTML fragment.
private val CURATORS_VAR_REGEX = Regex("""var g_rgTopCurators\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL)

/**
 * Fetches the list of curators the logged-in user follows.
 *
 * The Steam Store "my curators" endpoint requires authentication via the
 * steamLoginSecure cookie (steamId64 + "||" + accessToken), exactly as documented
 * in the JavaSteam SampleWebCookie sample (line 161). Returns an empty list on any
 * error so the caller can degrade gracefully.
 */
suspend fun fetchMyCurators(
    httpClient: OkHttpClient,
    steamId64: Long,
    accessToken: String,
    sessionId: String,
): List<SteamCurator> = withContext(Dispatchers.IO) {
    if (steamId64 == 0L || accessToken.isBlank()) {
        Timber.tag("SteamCuratorFetcher").w("Missing auth for curator fetch")
        return@withContext emptyList()
    }

    val cookieHeader = "steamLoginSecure=${steamId64}||${accessToken}; sessionid=$sessionId"
    val result = mutableListOf<SteamCurator>()
    var start = 0

    try {
        while (true) {
            val url = CURATORS_URL.format(start)
            val body = getWithCookie(httpClient, url, cookieHeader) ?: break

            val json = JSONObject(body)
            val totalCount = json.optInt("total_count", 0)
            val resultsHtml = json.optString("results_html", "")

            // The curator list is embedded as a JS variable inside the HTML fragment.
            val match = CURATORS_VAR_REGEX.find(resultsHtml)
            if (match != null) {
                val array = JSONArray(match.groupValues[1])
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.optString("clanID", "").toLongOrNull() ?: continue
                    val name = obj.optString("name", "").trim()
                    if (id != 0L && name.isNotEmpty()) result.add(SteamCurator(id, name))
                }
            }

            start += 50
            if (start >= totalCount || totalCount == 0) break
        }
    } catch (e: Exception) {
        Timber.tag("SteamCuratorFetcher").e(e, "Failed to fetch followed curators")
    }

    Timber.tag("SteamCuratorFetcher").d("Fetched ${result.size} followed curators")
    result
}

// Curator recommendations endpoint. %d = curator clanID, %d = pagination start. Public (no auth
// needed) but we pass the login cookie for parity with fetchMyCurators. Note: no "/render/" segment.
private const val CURATOR_RECS_URL =
    "https://store.steampowered.com/curator/%d/ajaxgetfilteredrecommendations/" +
        "?query&start=%d&count=100&dynamic_data=&tagids=&sort=recent&app_types=&curations=&reset=false"

// Each recommendation in results_html is a top-level <div ... class="recommendation" >. We split on
// this marker so per-item regexes can't bleed across entries.
private const val REC_BLOCK_MARKER = "class=\"recommendation\""

// data-ds-appid appears twice per item (outer div + inner <a>); the first match is enough.
private val REC_APPID_REGEX = Regex("""data-ds-appid="(\d+)"""")
// The recommendation type is a span class: color_recommended / color_informational /
// color_not_recommended. The class attribute uses SINGLE quotes in the live markup, so allow both.
private val REC_TYPE_REGEX = Regex("""color_(recommended|informational|not_recommended)""")
// Blurb text and review date. DOT_MATCHES_ALL because the desc div spans newlines/tabs.
private val REC_DESC_REGEX =
    Regex("""<div class="recommendation_desc">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
private val REC_DATE_REGEX = Regex("""<span class="curator_review_date">(.*?)</span>""")

/**
 * Fetches the games a curator has reviewed, keeping only "Recommended" and "Informational" entries
 * (drops "Not Recommended"). Pages through all results (count=100 per page) until total_count is
 * reached. Returns an empty list on any error so the caller can leave its cache untouched.
 */
suspend fun fetchCuratorRecommendations(
    httpClient: OkHttpClient,
    curatorId: Long,
    cookieHeader: String,
): List<SteamCuratorRecommendation> = withContext(Dispatchers.IO) {
    val result = mutableListOf<SteamCuratorRecommendation>()
    val seenAppIds = mutableSetOf<Int>()
    var start = 0

    try {
        while (true) {
            val url = CURATOR_RECS_URL.format(curatorId, start)
            val body = getWithCookie(httpClient, url, cookieHeader) ?: break

            val json = JSONObject(body)
            // total_count is sometimes a String, sometimes an int — optInt handles the int case and
            // we fall back to parsing the string form.
            val totalCount = json.optInt("total_count", json.optString("total_count", "0").toIntOrNull() ?: 0)
            val resultsHtml = json.optString("results_html", "")

            // Split into per-recommendation chunks. The first chunk (index 0) is the prefix before the
            // first marker and contains no appid, so it's harmless to scan.
            val blocks = resultsHtml.split(REC_BLOCK_MARKER)
            var parsedThisPage = 0
            for (block in blocks) {
                val appId = REC_APPID_REGEX.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                parsedThisPage++
                if (!seenAppIds.add(appId)) continue // dedupe across pages

                val type = REC_TYPE_REGEX.find(block)?.groupValues?.get(1) ?: continue
                // Keep only recommended + informational; skip not_recommended.
                if (type == "not_recommended") continue

                val blurb = REC_DESC_REGEX.find(block)?.groupValues?.get(1)?.let { unescapeHtml(it.trim()) } ?: ""
                val date = REC_DATE_REGEX.find(block)?.groupValues?.get(1)?.trim().orEmpty()
                result.add(SteamCuratorRecommendation(curatorId, appId, type, blurb, date))
            }

            // Stop if we've paged past the total, or a page yielded no parseable items (safety net
            // against an unexpected response shape causing an infinite loop).
            start += 100
            if (parsedThisPage == 0 || start >= totalCount || totalCount == 0) break
        }
    } catch (e: Exception) {
        Timber.tag("SteamCuratorFetcher").e(e, "Failed to fetch recommendations for curator $curatorId")
    }

    Timber.tag("SteamCuratorFetcher").d("Fetched ${result.size} recommendations for curator $curatorId")
    result
}

// Minimal HTML entity unescape for the short curator blurbs (full HTML parsing is overkill here).
private fun unescapeHtml(s: String): String = s
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&#039;", "'")

private fun getWithCookie(httpClient: OkHttpClient, url: String, cookie: String): String? {
    return try {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.tag("SteamCuratorFetcher").w("HTTP ${response.code} for $url")
                null
            } else {
                response.body?.string()
            }
        }
    } catch (e: Exception) {
        Timber.tag("SteamCuratorFetcher").e(e, "Request failed: $url")
        null
    }
}
