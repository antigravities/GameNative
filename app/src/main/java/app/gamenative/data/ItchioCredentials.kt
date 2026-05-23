package app.gamenative.data

/**
 * Credentials for an authenticated itch.io session.
 * Stored as JSON at context.filesDir/itchio/credentials.json.
 */
data class ItchioCredentials(
    val apiKey: String,
    val userId: Long,
    val username: String,
    // display_name can be null if the user hasn't set one
    val displayName: String?,
    val profileUrl: String,
)
