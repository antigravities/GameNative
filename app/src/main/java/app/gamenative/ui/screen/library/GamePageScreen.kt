package app.gamenative.ui.screen.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.R
import app.gamenative.ui.model.GamePageViewModel
import app.gamenative.ui.screen.library.components.LibraryDetailPane

@Composable
fun GamePageScreen(
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: GamePageViewModel = hiltViewModel(),
) {
    val libraryItem by viewModel.libraryItem.collectAsStateWithLifecycle()
    val isLoaded by viewModel.isLoaded.collectAsStateWithLifecycle()
    val notFound by viewModel.notFound.collectAsStateWithLifecycle()

    when {
        !isLoaded -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        notFound -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(R.string.epic_game_not_found))
            }
        }
        else -> {
            LibraryDetailPane(
                libraryItem = libraryItem,
                onClickPlay = { useBoxArt -> libraryItem?.let { onClickPlay(it.appId, useBoxArt) } },
                onTestGraphics = { libraryItem?.let { onTestGraphics(it.appId) } },
                onBack = onBack,
            )
        }
    }
}
