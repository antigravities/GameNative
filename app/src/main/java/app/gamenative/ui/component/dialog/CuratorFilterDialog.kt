package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.SteamCurator
import app.gamenative.ui.component.OptionListItem

/**
 * Single-select curator picker. Mirrors [TagFilterDialog] but selecting a curator replaces the
 * previous selection and immediately dismisses (curators are mutually exclusive). The "Clear" button
 * removes the filter entirely. A spinner shows while the followed list or a first-time recommendations
 * fetch is in flight.
 */
@Composable
fun CuratorFilterDialog(
    availableCurators: List<SteamCurator>,
    selectedCuratorId: Long,
    loading: Boolean,
    onCuratorSelected: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Local search state — reset each time the dialog opens via rememberSaveable scoping.
    var search by rememberSaveable { mutableStateOf("") }

    val visibleCurators = if (search.isBlank()) availableCurators
    else availableCurators.filter { it.name.contains(search, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_curator_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text(stringResource(R.string.library_curator_dialog_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )

                // Spinner while fetching the followed list or a curator's first recommendations.
                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            text = stringResource(R.string.library_curator_dialog_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(visibleCurators, key = { it.clanId }) { curator ->
                        OptionListItem(
                            text = curator.name,
                            selected = curator.clanId == selectedCuratorId,
                            onClick = {
                                onCuratorSelected(curator.clanId)
                                onDismiss()
                            },
                            icon = Icons.Default.Storefront,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (visibleCurators.isEmpty() && !loading) {
                        item {
                            Text(
                                text = if (availableCurators.isEmpty())
                                    stringResource(R.string.library_curator_dialog_empty)
                                else
                                    stringResource(R.string.library_curator_dialog_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_tag_dialog_done))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onClear()
                onDismiss()
            }) {
                Text(stringResource(R.string.library_curator_dialog_clear))
            }
        },
    )
}
