package app.gamenative.ui.screen

/**
 * Destinations for top level screens, excluding home screen destinations.
 */
sealed class PluviaScreen(val route: String) {
    data object LoginUser : PluviaScreen("login")
    data object Home : PluviaScreen("home")
    data object XServer : PluviaScreen("xserver")
    data object Settings : PluviaScreen("settings")
    data object Chat : PluviaScreen("chat/{id}") {
        fun route(id: Long) = "chat/$id"
        const val ARG_ID = "id"
    }
    data object GamePage : PluviaScreen("gamepage/{appId}") {
        fun route(appId: String) = "gamepage/$appId"
        const val ARG_APP_ID = "appId"
    }

    /** DD2 fork: in-app Steam Workshop browser (search · sort · subscribe). */
    data object WorkshopBrowser : PluviaScreen("workshop_browser/{appId}") {
        fun route(appId: Int) = "workshop_browser/$appId"
        const val ARG_APP_ID = "appId"
    }

    /** DD2 fork: detail of one Workshop item (description · subscribe button). */
    data object WorkshopDetail : PluviaScreen("workshop_detail/{appId}/{publishedFileId}") {
        fun route(appId: Int, publishedFileId: Long) = "workshop_detail/$appId/$publishedFileId"
        const val ARG_APP_ID = "appId"
        const val ARG_FILE_ID = "publishedFileId"
    }
}
