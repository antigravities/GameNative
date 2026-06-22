package app.gamenative.ui.enums

import androidx.annotation.StringRes
import app.gamenative.R

/**
 * Single-select category filter for the in-game Guides tab.
 *
 * Each entry maps to the exact Steam Community guide *tag* string that gets sent
 * in `requiredtags` of the PublishedFile.QueryFiles request. [ALL] has a null
 * tag, meaning "don't filter by category".
 */
enum class GuideCategory(
    @param:StringRes val displayTextRes: Int,
    val tag: String?,
) {
    ALL(displayTextRes = R.string.guides_category_all, tag = null),
    ACHIEVEMENTS(displayTextRes = R.string.guides_category_achievements, tag = "Achievements"),
    WALKTHROUGHS(displayTextRes = R.string.guides_category_walkthroughs, tag = "Walkthroughs"),
    MODDING(displayTextRes = R.string.guides_category_modding, tag = "Modding Or Configuration"),
    GAMEPLAY_BASICS(displayTextRes = R.string.guides_category_gameplay_basics, tag = "Gameplay Basics"),
    SECRETS(displayTextRes = R.string.guides_category_secrets, tag = "Secrets"),
    GAME_MODES(displayTextRes = R.string.guides_category_game_modes, tag = "Game Modes"),
}
