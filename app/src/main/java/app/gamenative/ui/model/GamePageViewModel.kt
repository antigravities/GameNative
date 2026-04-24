package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.utils.CustomGameScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

@HiltViewModel
class GamePageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val steamAppDao: SteamAppDao,
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
) : ViewModel() {

    private val appId: String = checkNotNull(savedStateHandle[PluviaScreen.GamePage.ARG_APP_ID])

    private val _libraryItem = MutableStateFlow<LibraryItem?>(null)
    val libraryItem: StateFlow<LibraryItem?> = _libraryItem.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _notFound = MutableStateFlow(false)
    val notFound: StateFlow<Boolean> = _notFound.asStateFlow()

    init {
        viewModelScope.launch {
            val underscoreIndex = appId.indexOf('_')
            if (underscoreIndex < 0) {
                Timber.w("[GamePageViewModel]: Malformed appId=$appId")
                _notFound.value = true
                _isLoaded.value = true
                return@launch
            }

            val sourceStr = appId.substring(0, underscoreIndex)
            val numericId = appId.substring(underscoreIndex + 1)
            val gameSource = GameSource.entries.find { it.name == sourceStr }

            if (gameSource == GameSource.STEAM) {
                // Steam syncs metadata (names, artwork) in the background via PICS. Rather
                // than doing a one-shot query that may return null before PICS has loaded
                // the row, we observe the DB reactively and wait until a name is present.
                val id = numericId.toIntOrNull()
                if (id == null) {
                    Timber.w("[GamePageViewModel]: Non-integer Steam id in appId=$appId")
                    _notFound.value = true
                    _isLoaded.value = true
                    return@launch
                }
                val app = withTimeoutOrNull(30_000L) {
                    steamAppDao.observeApp(id)
                        .filter { it != null && it.name.isNotEmpty() }
                        .first()
                }
                if (app != null) {
                    _libraryItem.value = LibraryItem(
                        appId = appId,
                        name = app.name,
                        iconHash = app.clientIconHash,
                        capsuleImageUrl = app.getCapsuleUrl(),
                        headerImageUrl = app.getHeaderImageUrl().orEmpty().ifEmpty { app.headerUrl },
                        heroImageUrl = app.getHeroUrl().ifEmpty { app.headerUrl },
                        gameSource = GameSource.STEAM,
                    )
                } else {
                    Timber.w("[GamePageViewModel]: Game not found or timed out for appId=$appId")
                    _notFound.value = true
                }
                _isLoaded.value = true
            } else {
                // Non-Steam sources (GOG, Epic, Amazon, Custom) don't have background PICS
                // sync, so a one-shot DAO query is sufficient.
                val item = resolveNonSteamLibraryItem(gameSource, numericId, appId)
                if (item != null) {
                    _libraryItem.value = item
                } else {
                    Timber.w("[GamePageViewModel]: Game not found for appId=$appId")
                    _notFound.value = true
                }
                _isLoaded.value = true
            }
        }
    }

    private suspend fun resolveNonSteamLibraryItem(
        gameSource: GameSource?,
        numericId: String,
        appId: String,
    ): LibraryItem? {
        return when (gameSource) {
            GameSource.GOG -> {
                gogGameDao.getById(numericId)?.let { game ->
                    LibraryItem(
                        appId = appId,
                        name = game.title,
                        iconHash = game.iconUrl.ifEmpty { game.imageUrl },
                        capsuleImageUrl = game.iconUrl.ifEmpty { game.imageUrl },
                        headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                        heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                        gameSource = GameSource.GOG,
                    )
                }
            }
            GameSource.EPIC -> {
                val id = numericId.toIntOrNull() ?: return null
                epicGameDao.getById(id)?.let { game ->
                    LibraryItem(
                        appId = appId,
                        name = game.title,
                        iconHash = game.artSquare.ifEmpty { game.artCover },
                        capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
                        headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                        heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                        gameSource = GameSource.EPIC,
                    )
                }
            }
            GameSource.AMAZON -> {
                val game = amazonGameDao.getByProductId(numericId)
                    ?: numericId.toIntOrNull()?.let { amazonGameDao.getByAppId(it) }
                game?.let {
                    LibraryItem(
                        appId = "${GameSource.AMAZON.name}_${it.appId}",
                        name = it.title,
                        iconHash = it.artUrl,
                        capsuleImageUrl = it.artUrl,
                        headerImageUrl = it.heroUrl.ifEmpty { it.artUrl },
                        heroImageUrl = it.heroUrl.ifEmpty { it.artUrl },
                        gameSource = GameSource.AMAZON,
                    )
                }
            }
            GameSource.CUSTOM_GAME -> {
                CustomGameScanner.scanAsLibraryItems(query = "")
                    .firstOrNull { it.appId == appId }
            }
            else -> null
        }
    }
}
