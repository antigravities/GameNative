package app.gamenative.data

import androidx.room.ColumnInfo
import app.gamenative.enums.AppType
import app.gamenative.enums.Language
import app.gamenative.service.SteamService

data class SteamAppSummary(
    val id: Int,
    val name: String,
    val type: AppType,
    @ColumnInfo("package_id")
    val packageId: Int = SteamService.INVALID_PKG_ID,
    @ColumnInfo("client_icon_hash")
    val clientIconHash: String = "",
    @ColumnInfo("library_assets")
    val libraryAssets: LibraryAssetsInfo = LibraryAssetsInfo(),
    @ColumnInfo("owner_account_id")
    val ownerAccountId: List<Int> = emptyList(),
    @ColumnInfo("install_dir")
    val installDir: String = "",
) {
    val headerUrl: String
        get() = "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg"

    fun getCapsuleUrl(language: Language = Language.english, large: Boolean = false): String {
        val capsules = if (large) libraryAssets.libraryCapsule.image2x else libraryAssets.libraryCapsule.image
        val imageLink = getFallbackUrl(capsules, language)
        return if (imageLink.isNullOrEmpty()) "" else "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/$imageLink"
    }

    fun getHeroUrl(language: Language = Language.english, large: Boolean = false): String {
        val images = if (large) libraryAssets.libraryHero.image2x else libraryAssets.libraryHero.image
        val imageLink = getFallbackUrl(images, language)
        return if (imageLink.isNullOrEmpty()) "" else "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/$imageLink"
    }

    private fun getFallbackUrl(images: Map<Language, String>, language: Language): String? {
        return when {
            images.containsKey(language) -> images[language]
            images.isNotEmpty() -> images.values.first()
            else -> ""
        }
    }
}
