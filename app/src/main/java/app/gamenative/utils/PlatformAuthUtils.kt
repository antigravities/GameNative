package app.gamenative.utils

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService

object PlatformAuthUtils {
    /**
     * GOG / Epic / Amazon are off in the Steam-only minimal launcher: their services never start
     * and their stored credentials (shared with the regular build) are ignored, not deleted.
     */
    val otherStoresEnabled: Boolean get() = !BuildConfig.MINIMAL_UI

    fun isSignedInToAnyPlatform(context: Context): Boolean =
        SteamService.isLoggedIn ||
            (
                otherStoresEnabled && (
                    GOGService.hasStoredCredentials(context) ||
                        EpicService.hasStoredCredentials(context) ||
                        AmazonService.hasStoredCredentials(context)
                    )
                )
}
