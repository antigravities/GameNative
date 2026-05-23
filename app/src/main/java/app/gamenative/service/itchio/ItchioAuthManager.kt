package app.gamenative.service.itchio

import android.content.Context
import app.gamenative.data.ItchioCredentials
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Manages itch.io authentication via API key.
 *
 * Unlike Steam/GOG/Epic which use OAuth flows, itch.io uses a static API key
 * that the user generates from their account settings. We validate the key by
 * calling the /profile endpoint and store the resulting credentials as JSON.
 *
 * Credential file: context.filesDir/itchio/credentials.json
 */
object ItchioAuthManager {

    private const val PROFILE_URL = "https://api.itch.io/profile"

    private val httpClient = Net.http

    fun credentialsPath(context: Context): String =
        "${context.filesDir}/itchio/credentials.json"

    fun hasStoredCredentials(context: Context): Boolean =
        File(credentialsPath(context)).exists()

    fun getStoredCredentials(context: Context): ItchioCredentials? {
        return try {
            val file = File(credentialsPath(context))
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            ItchioCredentials(
                apiKey = json.getString("api_key"),
                userId = json.getLong("user_id"),
                username = json.getString("username"),
                displayName = json.optString("display_name").takeIf { it.isNotEmpty() },
                profileUrl = json.getString("profile_url"),
            )
        } catch (e: Exception) {
            Timber.e(e, "[ItchioAuthManager] Failed to read credentials")
            null
        }
    }

    private fun saveCredentials(context: Context, credentials: ItchioCredentials) {
        val file = File(credentialsPath(context))
        // Create the itchio/ subdirectory if it doesn't exist yet
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("api_key", credentials.apiKey)
            put("user_id", credentials.userId)
            put("username", credentials.username)
            put("display_name", credentials.displayName ?: "")
            put("profile_url", credentials.profileUrl)
        }
        file.writeText(json.toString())
        Timber.i("[ItchioAuthManager] Credentials saved for user '${credentials.username}'")
    }

    /**
     * Validates the given API key against the itch.io /profile endpoint.
     * On success, saves credentials to disk and returns them.
     * On failure (invalid key or network error), returns Result.failure.
     */
    suspend fun validateAndSave(context: Context, apiKey: String): Result<ItchioCredentials> =
        withContext(Dispatchers.IO) {
            try {
                val url = PROFILE_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("api_key", apiKey)
                    .build()

                val request = okhttp3.Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()

                val body = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty response from itch.io"))

                val json = JSONObject(body)

                // itch.io returns {"errors":["invalid key"]} for bad keys
                if (json.has("errors")) {
                    val errors = json.getJSONArray("errors")
                    val message = if (errors.length() > 0) errors.getString(0) else "unknown error"
                    Timber.w("[ItchioAuthManager] API key rejected: $message")
                    return@withContext Result.failure(Exception("Invalid API key — please check and try again"))
                }

                // Parse the user object from a valid response
                val user = json.getJSONObject("user")
                val credentials = ItchioCredentials(
                    apiKey = apiKey,
                    userId = user.getLong("id"),
                    username = user.getString("username"),
                    displayName = user.optString("display_name").takeIf { it.isNotEmpty() },
                    profileUrl = user.getString("url"),
                )

                saveCredentials(context, credentials)
                Result.success(credentials)
            } catch (e: Exception) {
                Timber.e(e, "[ItchioAuthManager] validateAndSave failed")
                Result.failure(e)
            }
        }

    /**
     * Clears stored credentials. Called by ItchioService.logout().
     */
    fun logout(context: Context): Result<Unit> {
        return try {
            val file = File(credentialsPath(context))
            if (file.exists()) file.delete()
            Timber.i("[ItchioAuthManager] Credentials cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "[ItchioAuthManager] logout failed")
            Result.failure(e)
        }
    }
}
