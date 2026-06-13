package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.api.ApiResult
import app.gamenative.api.PatchApi
import app.gamenative.data.GameSource
import app.gamenative.data.PatchEntry
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.utils.ContainerUtils
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Internal states for the async per-game patch catalog fetch. */
private sealed class PatchesLoadState {
    object Loading : PatchesLoadState()
    object Empty : PatchesLoadState()
    data class Loaded(val entries: List<PatchEntry>) : PatchesLoadState()
}

/** Decodes the JSON-encoded list of selected patch names stored on the container. */
private fun decodeSelectedNames(json: String): List<String> = try {
    Json.decodeFromString<List<String>>(json)
} catch (_: Exception) {
    emptyList()
}

/**
 * Adds or removes [name] from the JSON-encoded selection list and returns the updated JSON.
 * Handles malformed JSON gracefully by starting from an empty list.
 */
private fun togglePatchName(json: String, name: String, selected: Boolean): String {
    val current = decodeSelectedNames(json).toMutableList()
    if (selected) {
        if (!current.contains(name)) current.add(name)
    } else {
        current.remove(name)
    }
    return Json.encodeToString<List<String>>(current)
}

/**
 * Content for the "Patches" tab in [ContainerConfigDialog].
 *
 * Unlike the global Features tab, patches are per-game: the catalog is fetched from
 * {patchDatabaseUrl}/{store}/{storeId}, where the store + id are resolved from [appId] (the
 * container/game identifier). Toggling a patch updates the container's [selectedPatches] JSON
 * list, which `runPatchFlowIfNeeded` in PluviaMain consumes at the next container open to
 * download + install any newly-selected patches (once, after features, before the game).
 *
 * Offline / 404 / parse failure / unresolvable store id → empty-state message (the user can
 * retry by reopening the dialog once connectivity is restored).
 */
@Composable
fun PatchesTabContent(state: ContainerConfigState, appId: String) {
    var loadState: PatchesLoadState by remember { mutableStateOf(PatchesLoadState.Loading) }

    // Fetch the per-game catalog on first composition.
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
            val gameId = runCatching { ContainerUtils.extractGameIdFromContainerId(appId) }.getOrNull()
            val storeId = gameId?.let { ContainerUtils.resolvePatchStoreId(gameSource, it) }
            if (gameSource == GameSource.CUSTOM_GAME || storeId == null) {
                ApiResult.Success(emptyList())
            } else {
                PatchApi.fetchPatches(PrefManager.patchDatabaseUrl, gameSource, storeId)
            }
        }
        loadState = if (result is ApiResult.Success && result.data.isNotEmpty()) {
            PatchesLoadState.Loaded(result.data)
        } else {
            PatchesLoadState.Empty
        }
    }

    when (val s = loadState) {
        is PatchesLoadState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is PatchesLoadState.Empty -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(text = stringResource(R.string.patches_none_available))
            }
        }
        is PatchesLoadState.Loaded -> {
            val config = state.config.value
            // Recompute the decoded list whenever the stored JSON changes (e.g. after a toggle).
            val selectedNames = remember(config.selectedPatches) {
                decodeSelectedNames(config.selectedPatches)
            }
            SettingsGroup {
                for (entry in s.entries) {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(text = entry.name) },
                        subtitle = { Text(text = entry.description) },
                        state = entry.name in selectedNames,
                        onCheckedChange = { checked ->
                            state.config.value = config.copy(
                                selectedPatches = togglePatchName(
                                    config.selectedPatches,
                                    entry.name,
                                    checked,
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}
