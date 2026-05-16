package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.gamenative.R
import app.gamenative.data.PatchEntry

/**
 * Dialog shown before first game launch when patches are available in the patch database.
 *
 * Uses a LazyColumn so it scrolls gracefully with any number of patches. The dialog is
 * capped at 80% of screen height so it never fills the display.
 *
 * All patches start checked; the user may uncheck any they don't want. "Skip" dismisses
 * without installing any patches.
 */
@Composable
fun PatchSelectionDialog(
    patches: List<PatchEntry>,
    onInstall: (List<PatchEntry>) -> Unit,
    onSkip: () -> Unit,
) {
    // Track checked state per patch index (all checked by default)
    val checkedState = remember {
        mutableStateMapOf<Int, Boolean>().also { map ->
            patches.indices.forEach { i -> map[i] = true }
        }
    }

    val configuration = LocalConfiguration.current
    val maxHeightDp = (configuration.screenHeightDp * 0.8f).dp

    Dialog(onDismissRequest = onSkip) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeightDp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.patch_selection_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Scrollable list of patches — weight(1f) ensures it doesn't push the
                // action buttons off screen when there are many items
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(patches.indices.toList()) { index ->
                        val entry = patches[index]
                        val checked = checkedState[index] == true
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { checkedState[index] = it },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (entry.description.isNotBlank()) {
                                    Text(
                                        text = entry.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // Action row pinned at the bottom of the dialog
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSkip) {
                        Text(stringResource(R.string.patch_selection_skip))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    val selected = patches.indices.filter { checkedState[it] == true }.map { patches[it] }
                    Button(
                        onClick = { onInstall(selected) },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.patch_selection_install))
                    }
                }
            }
        }
    }
}
