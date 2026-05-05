package app.gamenative.ui.component.dialog.state

data class CategoryDialogState(
    val visible: Boolean = false,
    /** Composite app ID (e.g. "STEAM_570") of the game being categorized. */
    val appId: String = "",
    /** Current value of the text field. */
    val input: String = "",
    /** Existing category names shown as tappable suggestion chips. */
    val existingCategories: List<String> = emptyList(),
    /** Categories this specific game currently belongs to (shown as removable chips). */
    val currentCategories: List<String> = emptyList(),
)
