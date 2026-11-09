package app.gamenative.ui.screen.minimal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.gamenative.data.MinimalCollectionSelection
import app.gamenative.steam.MinimalSelection

/**
 * modernMinimal home: routes between the collection picker and the game list based on the
 * selected collection's state.
 */
@Composable
fun MinimalHome(
    onClickPlay: (appId: String, asContainer: Boolean) -> Unit,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    isOffline: Boolean,
) {
    LaunchedEffect(Unit) { MinimalCollectionSelection.ensureLoaded() }
    val selection by MinimalCollectionSelection.selection.collectAsState(initial = null)
    var showPicker by rememberSaveable { mutableStateOf(false) }

    // Home key (home-screen mode) closes an optional "change collection" picker.
    DisposableEffect(Unit) {
        val onHome: (AndroidEvent.HomePressed) -> Unit = { showPicker = false }
        PluviaApp.events.on<AndroidEvent.HomePressed, Unit>(onHome)
        onDispose { PluviaApp.events.off<AndroidEvent.HomePressed, Unit>(onHome) }
    }

    when (val s = selection) {
        null, is MinimalSelection.NotLoaded -> MinUiScreen { MinUiMessage("Loading collections…", Modifier.fillMaxSize()) }
        MinimalSelection.NotSelected -> MinimalCollectionPicker()
        is MinimalSelection.Missing -> MinimalCollectionPicker(missingName = s.lastName.ifEmpty { null })
        is MinimalSelection.Resolved ->
            if (showPicker) {
                MinimalCollectionPicker(onBack = { showPicker = false })
            } else {
                MinimalLibraryScreen(
                    onChangeCollection = { showPicker = true },
                    onClickPlay = onClickPlay,
                    onExit = onExit,
                    onOpenSettings = onOpenSettings,
                    onLogout = onLogout,
                )
            }
    }
}

/**
 * Black full-screen MinUI scaffold: top bar (optional left content + battery), content area,
 * optional bottom hint bar.
 */
@Composable
fun MinUiScreen(
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    hints: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val m = rememberMinUiMetrics()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MinUiColors.Background),
    ) {
        MinUiTopBar(content = topBar)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        Box(modifier = Modifier.fillMaxWidth().height(m.rowHeight)) { hints?.invoke() }
    }
}
