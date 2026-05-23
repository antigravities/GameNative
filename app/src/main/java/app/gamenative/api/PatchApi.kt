package app.gamenative.api

import app.gamenative.data.GameSource
import app.gamenative.data.PatchEntry
import app.gamenative.utils.Net
import kotlinx.serialization.json.Json
import timber.log.Timber

object PatchApi {

    private val httpClient = Net.http

    /**
     * Downloads a single file from [url] to [dest], overwriting any existing content.
     * Throws IOException on network failure or non-2xx response.
     */
    fun downloadFile(url: String, dest: java.io.File) {
        val request = GameNativeApi.buildGetRequest(url)
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code} downloading $url")
            }
            dest.parentFile?.mkdirs()
            response.body?.byteStream()?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw java.io.IOException("Empty response body for $url")
        }
    }

    /**
     * Fetches the patch list for a game from the configured patch database URL.
     *
     * The URL is constructed as: {baseUrl}/{storePrefix}/{storeId}
     * where storePrefix is the lowercase store name (steam, gog, epic, amazon) and
     * storeId is the store's native identifier for the game.
     *
     * A 404 response means no patches exist for this game and is treated as success
     * with an empty list, so callers don't need to special-case it.
     *
     * @param baseUrl The user-configured base URL (trailing slash is handled automatically).
     * @param gameSource Which store this game belongs to.
     * @param storeId The store's native string ID for this game.
     */
    fun fetchPatches(
        baseUrl: String,
        gameSource: GameSource,
        storeId: String,
    ): ApiResult<List<PatchEntry>> {
        val normalizedBase = baseUrl.trimEnd('/')
        val storePrefix = when (gameSource) {
            GameSource.STEAM -> "steam"
            GameSource.GOG -> "gog"
            GameSource.EPIC -> "epic"
            GameSource.AMAZON -> "amazon"
            GameSource.CUSTOM_GAME -> return ApiResult.Success(emptyList())
            GameSource.ITCHIO -> return ApiResult.Success(emptyList())
        }
        val url = "$normalizedBase/$storePrefix/$storeId"
        Timber.tag("PatchApi").d("Fetching patches from: $url")

        return try {
            val request = app.gamenative.api.GameNativeApi.buildGetRequest(url)
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            // 404 → no patches defined for this game; not an error
            if (response.code == 404) {
                return ApiResult.Success(emptyList())
            }
            if (!response.isSuccessful) {
                Timber.tag("PatchApi").w("HTTP ${response.code} fetching patches")
                return ApiResult.HttpError(response.code, body)
            }

            val patches = Json { ignoreUnknownKeys = true }.decodeFromString<List<PatchEntry>>(body)
            Timber.tag("PatchApi").d("Received ${patches.size} patch(es)")
            ApiResult.Success(patches)
        } catch (e: Exception) {
            Timber.tag("PatchApi").e(e, "Failed to fetch patches")
            ApiResult.NetworkError(e)
        }
    }
}
