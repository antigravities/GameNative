package app.gamenative.ui.local

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal that provides a navigator function for opening the in-app
 * Steam Workshop browser for a given Steam appId.
 *
 * `null` (the default) means the browser entry point should be hidden — for
 * example on the standalone preview screens or when the host hasn't wired
 * navigation. The provider lives in `PluviaMain`, where the [NavHostController]
 * is in scope, and resolves to:
 *
 * ```kotlin
 * { appId -> navController.navigate(PluviaScreen.WorkshopBrowser.route(appId)) }
 * ```
 *
 * This avoids prop-drilling the callback through the
 * `HomeScreen → HomeLibraryScreen → LibraryScreenContent → LibraryDetailPane →
 * AppScreen → AppScreenContent` chain (six layers) — DD2 fork only.
 */
val LocalWorkshopBrowseEntry = compositionLocalOf<((appId: Int) -> Unit)?> { null }
