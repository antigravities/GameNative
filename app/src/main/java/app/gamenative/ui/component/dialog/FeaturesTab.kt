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
import app.gamenative.data.PatchEntry
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Internal states for the async catalog fetch. */
private sealed class FeaturesLoadState {
    object Loading : FeaturesLoadState()
    object Empty : FeaturesLoadState()
    data class Loaded(val entries: List<PatchEntry>) : FeaturesLoadState()
}

/** Decodes the JSON-encoded list of selected feature names stored on the container. */
private fun decodeSelectedNames(json: String): List<String> = try {
    Json.decodeFromString<List<String>>(json)
} catch (_: Exception) {
    emptyList()
}

/**
 * Adds or removes [name] from the JSON-encoded selection list and returns the updated JSON.
 * Handles malformed JSON gracefully by starting from an empty list.
 */
private fun toggleFeatureName(json: String, name: String, selected: Boolean): String {
    val current = decodeSelectedNames(json).toMutableList()
    if (selected) {
        if (!current.contains(name)) current.add(name)
    } else {
        current.remove(name)
    }
    return Json.encodeToString<List<String>>(current)
}

/**
 * Content for the "Features" tab in [ContainerConfigDialog].
 *
 * Fetches the feature catalog from {patchDatabaseUrl}/features on first composition and
 * renders a toggle per [PatchEntry].  Toggling a feature updates the container's
 * [selectedFeatures] JSON list, which [runFeatureFlowIfNeeded] in PluviaMain consumes at
 * the next container open to download + install any newly-selected features.
 *
 * Offline / 404 / parse failure → empty-state message (tab is still shown; the user can
 * retry by reopening the dialog once connectivity is restored).
 */
@Composable
fun FeaturesTabContent(state: ContainerConfigState) {
    var loadState: FeaturesLoadState by remember { mutableStateOf(FeaturesLoadState.Loading) }

    // Fetch the catalog from the patch database on first composition.
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            PatchApi.fetchFeatures(PrefManager.patchDatabaseUrl)
        }
        loadState = if (result is ApiResult.Success && result.data.isNotEmpty()) {
            FeaturesLoadState.Loaded(result.data)
        } else {
            FeaturesLoadState.Empty
        }
    }

    when (val s = loadState) {
        is FeaturesLoadState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is FeaturesLoadState.Empty -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(text = stringResource(R.string.features_none_available))
            }
        }
        is FeaturesLoadState.Loaded -> {
            val config = state.config.value
            // Recompute the decoded list whenever the stored JSON changes (e.g. after a toggle).
            val selectedNames = remember(config.selectedFeatures) {
                decodeSelectedNames(config.selectedFeatures)
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
                                selectedFeatures = toggleFeatureName(
                                    config.selectedFeatures,
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
