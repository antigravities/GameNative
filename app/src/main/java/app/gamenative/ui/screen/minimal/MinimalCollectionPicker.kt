package app.gamenative.ui.screen.minimal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import app.gamenative.data.MinimalCollectionSelection
import app.gamenative.data.SteamCollection
import app.gamenative.data.SteamCollectionRepository
import app.gamenative.service.SteamService

/**
 * modernMinimal: choose which Steam collection the launcher shows.
 *
 * @param missingName name of a previously selected collection that no longer exists on Steam.
 * @param onBack non-null when the picker can be dismissed (opened to change an existing choice).
 */
@Composable
fun MinimalCollectionPicker(
    missingName: String? = null,
    onBack: (() -> Unit)? = null,
) {
    val m = rememberMinUiMetrics()
    val collections by SteamCollectionRepository.collections.collectAsState()
    val sorted = remember(collections) {
        collections?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    var pageSize by remember { mutableStateOf(6) }
    var listState by remember { mutableStateOf(MinUiListState(pageSize = pageSize)) }
    LaunchedEffect(sorted?.size, pageSize) {
        listState = listState.resized(sorted?.size ?: 0, pageSize)
    }

    val choose: (Int) -> Unit = { index ->
        sorted?.getOrNull(index)?.let { MinimalCollectionSelection.select(it) }
        onBack?.invoke()
    }
    val refresh = { SteamService.requestMinimalCollectionRefresh(force = true) }
    // Shared by the A button and the tappable A pill.
    val onA: () -> Unit = {
        when {
            sorted == null -> Unit
            sorted.isEmpty() -> refresh()
            else -> choose(listState.selected)
        }
    }

    if (onBack != null) BackHandler(onBack = onBack)

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MinUiColors.Background)
            .focusRequester(focus)
            .minUiKeys(
                onUp = { listState = listState.moveBy(-1) },
                onDown = { listState = listState.moveBy(1) },
                onPageUp = { listState = listState.pageBy(-1) },
                onPageDown = { listState = listState.pageBy(1) },
                onA = onA,
                onB = onBack,
            )
            .focusable(),
    ) {
        // Top bar: title (or the deleted-collection notice).
        MinUiTopBar(
            title = if (missingName != null) "‘$missingName’ no longer exists on Steam" else "Choose a collection",
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                sorted == null -> MinUiMessage("Loading collections…", Modifier.fillMaxSize())
                sorted.isEmpty() -> MinUiMessage(
                    "No collections found.\nCreate one in Steam, then press A to refresh.",
                    Modifier.fillMaxSize(),
                )
                else -> MinUiList(
                    items = sorted,
                    state = listState,
                    label = SteamCollection::name,
                    trailing = { it.appIds.size.toString() },
                    onSelectedChange = { listState = listState.copy(selected = it) },
                    onActivate = choose,
                    onPageSize = { pageSize = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        MinUiHintBar(
            left = if (onBack != null) listOf(MinUiHint("B", "BACK", onBack)) else emptyList(),
            right = when {
                sorted == null -> emptyList()
                sorted.isEmpty() -> listOf(MinUiHint("A", "REFRESH", onA))
                else -> listOf(MinUiHint("A", "SELECT", onA))
            },
            modifier = Modifier.height(m.rowHeight),
        )
    }
}

